package com.anri.pgcompare.ddl;

import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.exception.CompareException;
import com.anri.pgcompare.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.anri.pgcompare.diff.ChangeType.*;

/**
 * Turns a SchemaDiff (source = state to migrate, target = desired state) into a
 * migration script to be applied on the source database. Statements are emitted in
 * dependency-safe order: sequences -> tables -> columns -> constraints
 * (drops and non-FK first, FK last) -> indexes. Object names are qualified with the
 * source schema; extractor definitions were normalized (own schema prefix stripped),
 * so the generator re-qualifies where needed.
 */
public class DdlGenerator {

    /** Matches `FOREIGN KEY (cols) REFERENCES tbl (cols)`, stopping before MATCH / ON clauses. */
    private static final Pattern FK_HEAD_PATTERN =
            Pattern.compile("(?i)^\\s*FOREIGN KEY\\s*\\(.*?\\)\\s*REFERENCES\\s+\\S+?\\s*\\(.*?\\)");

    /** An unqualified regclass literal, e.g. the sequence inside {@code nextval('doc_seq'::regclass)}. */
    private static final Pattern REGCLASS_LITERAL_PATTERN =
            Pattern.compile("'([\\w$]+)'::(?:pg_catalog\\.)?regclass");

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
        List<ColumnDef> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append("    ").append(columnBody(schema, columns.get(i)));
            if (i < columns.size() - 1) {
                sql.append(',');
            }
            sql.append('\n');
        }
        return sql.append(")").toString();
    }

    /** Column body shared by CREATE TABLE and ADD COLUMN: type, identity/generation, NOT NULL, DEFAULT. */
    private String columnBody(String schema, ColumnDef c) {
        StringBuilder sql = new StringBuilder(q(c.name())).append(' ').append(c.dataType());
        if (c.identity() != null) {
            sql.append(" GENERATED ").append(c.identity().sql()).append(" AS IDENTITY");
        }
        if (c.generated() != null) {
            sql.append(" GENERATED ALWAYS AS (").append(c.generated().expression()).append(") ")
                    .append(c.generated().kind().sql());
        }
        if (!c.nullable()) {
            sql.append(" NOT NULL");
        }
        if (c.defaultValue() != null) {
            sql.append(" DEFAULT ").append(qualifyDefault(schema, c.defaultValue()));
        }
        return sql.toString();
    }

    /**
     * The extractor strips the compared schema from {@code 'seq'::regclass} literals for
     * comparison; put it back so the default resolves without relying on search_path.
     * Already-qualified (cross-schema) references are left alone.
     */
    private String qualifyDefault(String schema, String defaultValue) {
        Matcher literal = REGCLASS_LITERAL_PATTERN.matcher(defaultValue);
        if (!literal.find()) {
            return defaultValue;
        }
        return literal.replaceFirst(
                Matcher.quoteReplacement("'" + q(schema) + "." + q(literal.group(1)) + "'::regclass"));
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
                    out.add(DdlStatement.commented(
                            "ALTER TABLE %s ADD COLUMN %s".formatted(table, columnBody(schema, c)),
                            c.nullable() || c.identity() != null || c.generated() != null
                                    ? null
                                    : "review: NOT NULL on existing rows requires a default"));
                    if (c.comment() != null) {
                        out.add(DdlStatement.of("COMMENT ON COLUMN %s.%s IS %s"
                                .formatted(table, column, commentLiteral(c.comment()))));
                    }
                }
                case REMOVED -> out.add(DdlStatement.commented(
                        "ALTER TABLE %s DROP COLUMN %s".formatted(table, column),
                        "BREAKING: drops column data"));
                case MODIFIED ->
                        emitColumnAlter(schema, table, column, (ColumnDef) e.before(), (ColumnDef) e.after(), out);
            }
        }
    }

    private void emitColumnAlter(String schema, String table, String column,
                                 ColumnDef before, ColumnDef after, List<DdlStatement> out) {
        if (!Objects.equals(before.generated(), after.generated())) {
            // a generation expression cannot be altered in place; the value is derived from the
            // row, so recreating the column does not lose user data
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP COLUMN %s".formatted(table, column),
                    "review: generated column is recreated from its new expression,"
                            + " dependent indexes and constraints are dropped by PostgreSQL"));
            out.add(DdlStatement.of("ALTER TABLE %s ADD COLUMN %s"
                    .formatted(table, columnBody(schema, after))));
            return;
        }
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
                    : "ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s".formatted(
                            table, column, qualifyDefault(schema, after.defaultValue()))));
        }
        if (!Objects.equals(before.identity(), after.identity())) {
            if (after.identity() == null) {
                out.add(DdlStatement.commented(
                        "ALTER TABLE %s ALTER COLUMN %s DROP IDENTITY IF EXISTS".formatted(table, column),
                        "identity removed; the backing sequence is left behind, review whether to drop it"));
            } else if (before.identity() == null) {
                out.add(DdlStatement.commented(
                        "ALTER TABLE %s ALTER COLUMN %s ADD GENERATED %s AS IDENTITY".formatted(
                                table, column, after.identity().sql()),
                        "review: existing rows need values before an identity can take over"));
            } else {
                out.add(DdlStatement.of("ALTER TABLE %s ALTER COLUMN %s SET GENERATED %s".formatted(
                        table, column, after.identity().sql())));
            }
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
                    "definition changed, re-added below; the drop fails if a foreign key"
                            + " or a view depends on this constraint"));
        }
        // a modified constraint is dropped above and re-created from the target definition here;
        // non-FK before FK so referenced tables are ready before validation
        List<DiffEntry> toCreate = new ArrayList<>(added);
        toCreate.addAll(modified);
        emitAddConstraints(toCreate, schema, out, false);
        emitAddConstraints(toCreate, schema, out, true);
    }

    private void emitAddConstraints(List<DiffEntry> toCreate, String schema, List<DdlStatement> out,
                                    boolean foreignKeys) {
        for (DiffEntry e : toCreate) {
            ConstraintDef c = (ConstraintDef) e.after();
            boolean isFk = c.type() == ConstraintType.FOREIGN_KEY;
            if (isFk != foreignKeys) {
                continue;
            }
            String[] parts = splitName(e.objectName());
            String sql = "ALTER TABLE %s ADD CONSTRAINT %s %s%s".formatted(
                    qualify(schema, parts[0]), q(parts[1]), inlineDefinition(schema, c), c.flagsClause());
            out.add(DdlStatement.of(sql));
        }
    }

    private String inlineDefinition(String schema, ConstraintDef c) {
        return switch (c.type()) {
            case PRIMARY_KEY -> "PRIMARY KEY (" + columns(c.columns()) + ")";
            case UNIQUE -> "UNIQUE (" + columns(c.columns()) + ")";
            case CHECK, EXCLUSION -> requireDefinition(c);
            case FOREIGN_KEY -> "FOREIGN KEY (" + columns(c.columns()) + ") REFERENCES "
                    + qualify(schema, c.referencedTable()) + " (" + columns(c.referencedColumns()) + ")"
                    + foreignKeyTail(c.definition());
        };
    }

    /** EXCLUDE and CHECK are rendered from the canonical definition only; without one there is nothing to emit. */
    private String requireDefinition(ConstraintDef c) {
        if (c.definition() == null) {
            throw new CompareException(
                    "Cannot generate DDL for %s constraint '%s': no definition".formatted(c.type(), c.name()));
        }
        return c.definition();
    }

    /**
     * {@code pg_get_constraintdef} renders MATCH / ON DELETE / ON UPDATE only inside the
     * definition text, after the referenced column list, so the tail is carried over verbatim
     * instead of being rebuilt from the (absent) structured fields.
     */
    private String foreignKeyTail(String definition) {
        if (definition == null) {
            return "";
        }
        Matcher head = FK_HEAD_PATTERN.matcher(definition);
        if (!head.find()) {
            return "";
        }
        String tail = definition.substring(head.end()).trim();
        return tail.isEmpty() ? "" : " " + tail;
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
                "$1" + Matcher.quoteReplacement(q(schema)) + ".$2$3");
    }

    private List<DiffEntry> select(SchemaDiff diff, ObjectType objectType) {
        return diff.entries().stream()
                .filter(e -> e.objectType() == objectType)
                .toList();
    }

    /** Word-boundary match of column names in a canonical index definition. */
    private boolean mentionsAnyColumn(String definition, Set<String> columnNames) {
        for (String column : columnNames) {
            if (definition.matches("(?i).*\\b" + Pattern.quote(column) + "\\b.*")) {
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
