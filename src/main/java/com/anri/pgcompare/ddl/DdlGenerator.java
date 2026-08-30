package com.anri.pgcompare.ddl;

import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.ConstraintType;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.anri.pgcompare.diff.ChangeType.ADDED;
import static com.anri.pgcompare.diff.ChangeType.MODIFIED;
import static com.anri.pgcompare.diff.ChangeType.REMOVED;

/**
 * Turns a SchemaDiff (source = state to migrate, target = desired state) into a
 * migration script to be applied on the source database. Statements are emitted in
 * dependency-safe order: sequences -> tables -> columns -> constraints
 * (drops and non-FK first, FK last) -> indexes. Object names are qualified with the
 * source schema; extractor definitions were normalized (own schema prefix stripped),
 * so the generator re-qualifies where needed.
 */
@Component
public class DdlGenerator {

    public List<DdlStatement> generate(SchemaDiff diff) {
        String schema = diff.sourceSchema();
        // tables dropped by this migration make their column/constraint/index entries redundant
        Set<String> droppedTables = select(diff, ObjectType.TABLE).stream()
                .filter(e -> e.changeType() == REMOVED)
                .map(DiffEntry::objectName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        // dropping a column cascades to constraints and indexes using it,
        // so explicit drops of those objects would fail at runtime
        Map<String, Set<String>> droppedColumnsByTable = select(diff, ObjectType.COLUMN).stream()
                .filter(e -> e.changeType() == REMOVED)
                .collect(Collectors.groupingBy(
                        e -> splitName(e.objectName())[0].toLowerCase(),
                        Collectors.mapping(e -> splitName(e.objectName())[1], Collectors.toSet())));
        List<DdlStatement> statements = new ArrayList<>();
        sequences(diff, schema, statements);
        tables(diff, schema, statements);
        columns(diff, schema, droppedTables, statements);
        comments(diff, schema, droppedTables, statements);
        constraints(diff, schema, droppedTables, droppedColumnsByTable, statements);
        indexes(diff, schema, droppedTables, droppedColumnsByTable, statements);
        return statements;
    }

    private void sequences(SchemaDiff diff, String schema, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.SEQUENCE)) {
            if (e.changeType() == ADDED) {
                SequenceDef s = (SequenceDef) e.after();
                out.add(DdlStatement.of("CREATE SEQUENCE %s START WITH %d INCREMENT BY %d MINVALUE %d MAXVALUE %d"
                        .formatted(qualify(schema, e.objectName()), s.startValue(), s.increment(),
                                s.minValue(), s.maxValue())));
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented("DROP SEQUENCE %s".formatted(qualify(schema, e.objectName())),
                        "BREAKING: sequence removal"));
            } else if (e.changeType() == MODIFIED) {
                SequenceDef before = (SequenceDef) e.before();
                SequenceDef after = (SequenceDef) e.after();
                StringBuilder sql = new StringBuilder("ALTER SEQUENCE ")
                        .append(qualify(schema, e.objectName()));
                if (before.startValue() != after.startValue()) {
                    sql.append(" START WITH ").append(after.startValue());
                }
                if (before.increment() != after.increment()) {
                    sql.append(" INCREMENT BY ").append(after.increment());
                }
                if (before.minValue() != after.minValue()) {
                    sql.append(" MINVALUE ").append(after.minValue());
                }
                if (before.maxValue() != after.maxValue()) {
                    sql.append(" MAXVALUE ").append(after.maxValue());
                }
                out.add(DdlStatement.of(sql.toString()));
            }
        }
    }

    private void tables(SchemaDiff diff, String schema, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.TABLE)) {
            if (e.changeType() == ADDED) {
                TableDef t = (TableDef) e.after();
                out.add(DdlStatement.of(createTable(schema, t)));
                if (t.comment() != null) {
                    out.add(DdlStatement.of("COMMENT ON TABLE %s IS %s"
                            .formatted(qualify(schema, t.name()), commentLiteral(t.comment()))));
                }
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented("DROP TABLE %s".formatted(qualify(schema, e.objectName())),
                        "BREAKING: drops all data in the table"));
            }
        }
    }

    private String createTable(String schema, TableDef table) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ")
                .append(qualify(schema, table.name())).append(" (\n");
        for (int i = 0; i < table.columns().size(); i++) {
            ColumnDef c = table.columns().get(i);
            sql.append("    ").append(q(c.name())).append(' ').append(c.dataType());
            if (!c.nullable()) {
                sql.append(" NOT NULL");
            }
            if (c.defaultValue() != null) {
                sql.append(" DEFAULT ").append(c.defaultValue());
            }
            if (i < table.columns().size() - 1) {
                sql.append(',');
            }
            sql.append('\n');
        }
        return sql.append(")").toString();
    }

    private void columns(SchemaDiff diff, String schema, Set<String> droppedTables, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.COLUMN)) {
            String[] parts = splitName(e.objectName());
            if (droppedTables.contains(parts[0].toLowerCase())) {
                continue;
            }
            String table = qualify(schema, parts[0]);
            String column = q(parts[1]);
            switch (e.changeType()) {
                case ADDED -> {
                    ColumnDef c = (ColumnDef) e.after();
                    StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table)
                            .append(" ADD COLUMN ").append(column).append(' ').append(c.dataType());
                    if (!c.nullable()) {
                        sql.append(" NOT NULL");
                    }
                    if (c.defaultValue() != null) {
                        sql.append(" DEFAULT ").append(c.defaultValue());
                    }
                    out.add(DdlStatement.commented(sql.toString(),
                            c.nullable() ? null : "review: NOT NULL on existing rows requires a default"));
                    if (c.comment() != null) {
                        out.add(DdlStatement.of("COMMENT ON COLUMN %s.%s IS %s"
                                .formatted(table, column, commentLiteral(c.comment()))));
                    }
                }
                case REMOVED -> out.add(DdlStatement.commented(
                        "ALTER TABLE %s DROP COLUMN %s".formatted(table, column),
                        "BREAKING: drops column data"));
                case MODIFIED -> emitColumnAlter(table, column, (ColumnDef) e.before(), (ColumnDef) e.after(), out);
            }
        }
    }

    private void emitColumnAlter(String table, String column, ColumnDef before, ColumnDef after,
                                 List<DdlStatement> out) {
        if (!before.dataType().equals(after.dataType())) {
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s ALTER COLUMN %s TYPE %s".formatted(table, column, after.dataType()),
                    "review: implicit cast may fail, consider a USING clause"));
        }
        if (before.nullable() != after.nullable()) {
            out.add(DdlStatement.of(after.nullable()
                    ? "ALTER TABLE %s ALTER COLUMN %s DROP NOT NULL".formatted(table, column)
                    : "ALTER TABLE %s ALTER COLUMN %s SET NOT NULL".formatted(table, column)));
        }
        if (!Objects.equals(before.defaultValue(), after.defaultValue())) {
            out.add(DdlStatement.of(after.defaultValue() == null
                    ? "ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT".formatted(table, column)
                    : "ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s".formatted(table, column, after.defaultValue())));
        }
    }

    /** COMMENT ON statements for changed table/column comments (target state wins). */
    private void comments(SchemaDiff diff, String schema, Set<String> droppedTables,
                          List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.COMMENT)) {
            String[] parts = splitName(e.objectName());
            if (droppedTables.contains(parts[0].toLowerCase())) {
                continue;
            }
            String targetComment = commentOf(e.after());
            if (parts.length == 1) {
                out.add(DdlStatement.of("COMMENT ON TABLE %s IS %s"
                        .formatted(qualify(schema, parts[0]), commentLiteral(targetComment))));
            } else {
                out.add(DdlStatement.of("COMMENT ON COLUMN %s.%s IS %s"
                        .formatted(qualify(schema, parts[0]), q(parts[1]), commentLiteral(targetComment))));
            }
        }
    }

    private String commentOf(Object owner) {
        if (owner instanceof TableDef t) {
            return t.comment();
        }
        if (owner instanceof ColumnDef c) {
            return c.comment();
        }
        return null;
    }

    /** SQL string literal; null renders as NULL (COMMENT ... IS NULL removes the comment). */
    private String commentLiteral(String comment) {
        return comment == null ? "NULL" : "'" + comment.replace("'", "''") + "'";
    }

    private void constraints(SchemaDiff diff, String schema, Set<String> droppedTables,
                             Map<String, Set<String>> droppedColumnsByTable, List<DdlStatement> out) {
        List<DiffEntry> added = new ArrayList<>();
        List<DiffEntry> removed = new ArrayList<>();
        List<DiffEntry> modified = new ArrayList<>();
        for (DiffEntry e : select(diff, ObjectType.CONSTRAINT)) {
            String[] parts = splitName(e.objectName());
            String table = parts[0].toLowerCase();
            if (droppedTables.contains(table)) {
                continue;
            }
            if (e.changeType() != ADDED
                    && ((ConstraintDef) e.before()).columns().stream()
                            .anyMatch(col -> droppedColumnsByTable.getOrDefault(table, Set.of())
                                    .contains(col))) {
                continue; // dropped implicitly with its column
            }
            switch (e.changeType()) {
                case ADDED -> added.add(e);
                case REMOVED -> removed.add(e);
                case MODIFIED -> modified.add(e);
            }
        }
        for (DiffEntry e : removed) {
            String[] parts = splitName(e.objectName());
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP CONSTRAINT %s".formatted(qualify(schema, parts[0]), q(parts[1])),
                    "constraint no longer present in target"));
        }
        for (DiffEntry e : modified) {
            String[] parts = splitName(e.objectName());
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP CONSTRAINT %s".formatted(qualify(schema, parts[0]), q(parts[1])),
                    "definition changed, re-added below"));
        }
        // non-FK before FK so referenced tables are ready before validation
        emitAddConstraints(added, schema, out, false);
        emitAddConstraints(added, schema, out, true);
    }

    private void emitAddConstraints(List<DiffEntry> added, String schema, List<DdlStatement> out,
                                    boolean foreignKeys) {
        for (DiffEntry e : added) {
            ConstraintDef c = (ConstraintDef) e.after();
            boolean isFk = c.type() == ConstraintType.FOREIGN_KEY;
            if (isFk != foreignKeys) {
                continue;
            }
            String[] parts = splitName(e.objectName());
            String sql = "ALTER TABLE %s ADD CONSTRAINT %s %s".formatted(
                    qualify(schema, parts[0]), q(parts[1]), inlineDefinition(schema, c));
            out.add(DdlStatement.of(sql));
        }
    }

    private String inlineDefinition(String schema, ConstraintDef c) {
        return switch (c.type()) {
            case PRIMARY_KEY -> "PRIMARY KEY (" + columns(c.columns()) + ")";
            case UNIQUE -> "UNIQUE (" + columns(c.columns()) + ")";
            case CHECK -> c.definition() == null ? "CHECK (TRUE)" : stripPrefix(c.definition());
            case FOREIGN_KEY -> "FOREIGN KEY (" + columns(c.columns()) + ") REFERENCES "
                    + qualify(schema, c.referencedTable()) + " (" + columns(c.referencedColumns()) + ")";
        };
    }

    private String stripPrefix(String pgDef) {
        int open = pgDef.indexOf('(');
        return open >= 0 ? pgDef.substring(open) : pgDef;
    }

    private String columns(List<String> columns) {
        return columns.stream().map(this::q).collect(Collectors.joining(", "));
    }

    private void indexes(SchemaDiff diff, String schema, Set<String> droppedTables,
                         Map<String, Set<String>> droppedColumnsByTable, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.INDEX)) {
            String[] parts = splitName(e.objectName());
            String table = parts[0].toLowerCase();
            if (droppedTables.contains(table)) {
                continue;
            }
            if (e.changeType() == REMOVED
                    && mentionsAnyColumn(((IndexDef) e.before()).definition(),
                            droppedColumnsByTable.getOrDefault(table, Set.of()))) {
                continue; // dropped implicitly with its column
            }
            if (e.changeType() == ADDED) {
                IndexDef i = (IndexDef) e.after();
                out.add(DdlStatement.of(qualifyIndexTable(i.definition(), schema)));
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented(
                        "DROP INDEX %s.%s".formatted(q(schema), q(parts[1])),
                        "index no longer present in target"));
            } else if (e.changeType() == MODIFIED) {
                IndexDef after = (IndexDef) e.after();
                out.add(DdlStatement.commented(
                        "DROP INDEX %s.%s".formatted(q(schema), q(parts[1])),
                        "definition changed, recreated next"));
                out.add(DdlStatement.of(qualifyIndexTable(after.definition(), schema)));
            }
        }
    }

    /** Index definitions were normalized (schema prefix stripped); re-qualify the table. */
    private String qualifyIndexTable(String definition, String schema) {
        return definition.replaceFirst("(?i)(\\sON\\s)(\"?[\\w]+\"?)(\\s|\\()",
                "$1" + java.util.regex.Matcher.quoteReplacement(q(schema)) + ".$2$3");
    }

    private List<DiffEntry> select(SchemaDiff diff, ObjectType objectType) {
        return diff.entries().stream()
                .filter(e -> e.objectType() == objectType)
                .toList();
    }

    /** Word-boundary match of column names in a canonical index definition. */
    private boolean mentionsAnyColumn(String definition, Set<String> columnNames) {
        for (String column : columnNames) {
            if (definition.matches("(?i).*\\b" + java.util.regex.Pattern.quote(column) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private String[] splitName(String qualifiedName) {
        int dot = qualifiedName.indexOf('.');
        if (dot < 0) {
            return new String[]{qualifiedName};
        }
        return new String[]{qualifiedName.substring(0, dot), qualifiedName.substring(dot + 1)};
    }

    /** Qualified name: schema.table; cross-schema references (already dotted) are kept as-is. */
    private String qualify(String schema, String tableName) {
        if (tableName.contains(".")) {
            return Arrays.stream(tableName.split("\\.")).map(this::q).collect(Collectors.joining("."));
        }
        return q(schema) + "." + q(tableName);
    }

    /** Quotes an identifier with double quotes; names come from pg_catalog as stored. */
    private String q(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
