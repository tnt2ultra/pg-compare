package com.anri.pgcompare.diff;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ColumnGeneration;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.IdentityKind;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Compares two schema snapshots in memory. Pure logic: no I/O, fully unit-testable.
 * Objects are keyed by lowercased name — PostgreSQL stores unquoted identifiers
 * lowercased, so this only canonicalizes what the server already folds.
 */
public class SchemaDiffer {

    private final SeverityClassifier severityClassifier;

    public SchemaDiffer(SeverityClassifier severityClassifier) {
        this.severityClassifier = severityClassifier;
    }

    public SchemaDiff diff(SchemaSnapshot source, SchemaSnapshot target) {
        List<DiffEntry> entries = new ArrayList<>();
        diffTables(source, target, entries);
        diffConstraints(source, target, entries);
        diffIndexes(source, target, entries);
        diffSequences(source, target, entries);
        return new SchemaDiff(source.schemaName(), target.schemaName(), List.copyOf(entries));
    }

    private void diffTables(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, TableDef> sourceTables = byName(source.tables(), TableDef::name);
        Map<String, TableDef> targetTables = byName(target.tables(), TableDef::name);

        for (Map.Entry<String, TableDef> e : targetTables.entrySet()) {
            if (!sourceTables.containsKey(e.getKey())) {
                entries.add(add(ObjectType.TABLE, e.getKey(), e.getValue(),
                        "Table exists only in target"));
            }
        }
        for (Map.Entry<String, TableDef> e : sourceTables.entrySet()) {
            TableDef sourceTable = e.getValue();
            TableDef targetTable = targetTables.get(e.getKey());
            if (targetTable == null) {
                entries.add(remove(ObjectType.TABLE, e.getKey(), sourceTable,
                        "Table exists only in source"));
            } else {
                diffColumns(e.getKey(), sourceTable, targetTable, entries);
            }
        }
    }

    private void diffColumns(String tableName, TableDef sourceTable, TableDef targetTable,
                             List<DiffEntry> entries) {
        Map<String, ColumnDef> sourceColumns = byName(sourceTable.columns(), ColumnDef::name);
        Map<String, ColumnDef> targetColumns = byName(targetTable.columns(), ColumnDef::name);

        for (Map.Entry<String, ColumnDef> e : targetColumns.entrySet()) {
            if (!sourceColumns.containsKey(e.getKey())) {
                entries.add(add(ObjectType.COLUMN, tableName + "." + e.getKey(), e.getValue(),
                        "Column exists only in target"));
            }
        }
        for (Map.Entry<String, ColumnDef> e : sourceColumns.entrySet()) {
            String columnName = e.getKey();
            ColumnDef before = e.getValue();
            ColumnDef after = targetColumns.get(columnName);
            if (after == null) {
                entries.add(remove(ObjectType.COLUMN, tableName + "." + columnName, before,
                        "Column exists only in source"));
                continue;
            }
            List<String> changes = columnChanges(before, after);
            if (!changes.isEmpty()) {
                entries.add(new DiffEntry(ObjectType.COLUMN, tableName + "." + columnName,
                        ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.COLUMN, ChangeType.MODIFIED),
                        String.join("; ", changes), before, after));
            }
            diffComment(before.comment(), after.comment(),
                    tableName + "." + columnName, before, after, entries);
        }
        diffComment(sourceTable.comment(), targetTable.comment(),
                tableName, sourceTable, targetTable, entries);
    }

    private List<String> columnChanges(ColumnDef before, ColumnDef after) {
        List<String> changes = new ArrayList<>();
        if (!normalizeType(before.dataType()).equals(normalizeType(after.dataType()))) {
            changes.add("type changed: %s -> %s".formatted(before.dataType(), after.dataType()));
        }
        if (before.nullable() != after.nullable()) {
            changes.add(before.nullable()
                    ? "became NOT NULL"
                    : "became nullable");
        }
        if (!normalizeDefault(before.defaultValue()).equals(normalizeDefault(after.defaultValue()))) {
            changes.add("default changed: %s -> %s".formatted(
                    display(before.defaultValue()), display(after.defaultValue())));
        }
        if (!Objects.equals(before.identity(), after.identity())) {
            changes.add("identity changed: %s -> %s".formatted(
                    displayIdentity(before.identity()), displayIdentity(after.identity())));
        }
        if (!Objects.equals(before.generated(), after.generated())) {
            changes.add("generation changed: %s -> %s".formatted(
                    displayGeneration(before.generated()), displayGeneration(after.generated())));
        }
        return changes;
    }

    private String displayIdentity(IdentityKind identity) {
        return identity == null ? "none" : "GENERATED " + identity.sql() + " AS IDENTITY";
    }

    private String displayGeneration(ColumnGeneration generated) {
        return generated == null ? "none"
                : "GENERATED ALWAYS AS (%s) %s".formatted(generated.expression(), generated.kind().sql());
    }

    /**
     * Emits a COMMENT entry when the two comment texts differ. Missing comment is null,
     * so a comment added on one side and removed on the other is a plain change.
     * beforeOwner/afterOwner are the owning table/column definitions rendered in the report.
     */
    private void diffComment(String before, String after, String entryName,
                             Object beforeOwner, Object afterOwner, List<DiffEntry> entries) {
        if (Objects.equals(normalizeComment(before), normalizeComment(after))) {
            return;
        }
        String description = "comment changed: %s -> %s".formatted(
                displayComment(before), displayComment(after));
        entries.add(new DiffEntry(ObjectType.COMMENT, entryName, ChangeType.MODIFIED,
                severityClassifier.classify(ObjectType.COMMENT, ChangeType.MODIFIED),
                description, beforeOwner, afterOwner));
    }

    private void diffConstraints(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, ConstraintDef> sourceConstraints = byName(source.constraints(), c -> c.table() + "." + c.name());
        Map<String, ConstraintDef> targetConstraints = byName(target.constraints(), c -> c.table() + "." + c.name());

        for (Map.Entry<String, ConstraintDef> e : targetConstraints.entrySet()) {
            if (!sourceConstraints.containsKey(e.getKey())) {
                entries.add(add(ObjectType.CONSTRAINT, e.getKey(), e.getValue(),
                        "Constraint exists only in target"));
            }
        }
        for (Map.Entry<String, ConstraintDef> e : sourceConstraints.entrySet()) {
            ConstraintDef before = e.getValue();
            ConstraintDef after = targetConstraints.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.CONSTRAINT, e.getKey(), before,
                        "Constraint exists only in source"));
            } else {
                List<String> changes = constraintChanges(before, after);
                if (!changes.isEmpty()) {
                    entries.add(new DiffEntry(ObjectType.CONSTRAINT, e.getKey(), ChangeType.MODIFIED,
                            severityClassifier.classify(ObjectType.CONSTRAINT, ChangeType.MODIFIED),
                            String.join("; ", changes), before, after));
                }
            }
        }
    }

    private List<String> constraintChanges(ConstraintDef before, ConstraintDef after) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(before.definition(), after.definition())) {
            changes.add("definition changed: %s -> %s".formatted(before.definition(), after.definition()));
        }
        if (!before.flagsClause().equals(after.flagsClause())) {
            // pg_get_constraintdef renders neither deferrability nor NOT VALID, so options
            // are compared through the structured flags that produce them
            changes.add("options changed: %s -> %s".formatted(
                    displayFlags(before.flagsClause()), displayFlags(after.flagsClause())));
        }
        return changes;
    }

    private String displayFlags(String flagsClause) {
        return flagsClause.isEmpty() ? "none" : flagsClause.trim();
    }

    private void diffIndexes(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, IndexDef> sourceIndexes = byName(source.indexes(), i -> i.table() + "." + i.name());
        Map<String, IndexDef> targetIndexes = byName(target.indexes(), i -> i.table() + "." + i.name());

        for (Map.Entry<String, IndexDef> e : targetIndexes.entrySet()) {
            if (!sourceIndexes.containsKey(e.getKey())) {
                entries.add(add(ObjectType.INDEX, e.getKey(), e.getValue(),
                        "Index exists only in target"));
            }
        }
        for (Map.Entry<String, IndexDef> e : sourceIndexes.entrySet()) {
            IndexDef before = e.getValue();
            IndexDef after = targetIndexes.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.INDEX, e.getKey(), before,
                        "Index exists only in source"));
            } else if (!Objects.equals(before.definition(), after.definition())) {
                entries.add(new DiffEntry(ObjectType.INDEX, e.getKey(), ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.INDEX, ChangeType.MODIFIED),
                        "definition changed: %s -> %s".formatted(before.definition(), after.definition()),
                        before, after));
            }
        }
    }

    private void diffSequences(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, SequenceDef> sourceSequences = byName(source.sequences(), SequenceDef::name);
        Map<String, SequenceDef> targetSequences = byName(target.sequences(), SequenceDef::name);

        for (Map.Entry<String, SequenceDef> e : targetSequences.entrySet()) {
            if (!sourceSequences.containsKey(e.getKey())) {
                entries.add(add(ObjectType.SEQUENCE, e.getKey(), e.getValue(),
                        "Sequence exists only in target"));
            }
        }
        for (Map.Entry<String, SequenceDef> e : sourceSequences.entrySet()) {
            SequenceDef before = e.getValue();
            SequenceDef after = targetSequences.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.SEQUENCE, e.getKey(), before,
                        "Sequence exists only in source"));
            } else if (!Objects.equals(before, after)) {
                entries.add(new DiffEntry(ObjectType.SEQUENCE, e.getKey(), ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.SEQUENCE, ChangeType.MODIFIED),
                        "sequence parameters changed", before, after));
            }
        }
    }

    private <T> Map<String, T> byName(List<T> items, Function<T, String> keyFn) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(keyFn.apply(item).toLowerCase(), item);
        }
        return map;
    }

    private DiffEntry add(ObjectType type, String name, Object after, String description) {
        return new DiffEntry(type, name, ChangeType.ADDED,
                severityClassifier.classify(type, ChangeType.ADDED), description, null, after);
    }

    private DiffEntry remove(ObjectType type, String name, Object before, String description) {
        return new DiffEntry(type, name, ChangeType.REMOVED,
                severityClassifier.classify(type, ChangeType.REMOVED), description, before, null);
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private String normalizeDefault(String defaultValue) {
        return defaultValue == null ? "" : defaultValue.replaceAll("\\s+", " ").trim();
    }

    private String normalizeComment(String comment) {
        return comment == null ? "" : comment.trim();
    }

    private String display(String value) {
        return value == null ? "none" : value;
    }

    private String displayComment(String comment) {
        return comment == null ? "(no comment)" : "'" + comment + "'";
    }
}
