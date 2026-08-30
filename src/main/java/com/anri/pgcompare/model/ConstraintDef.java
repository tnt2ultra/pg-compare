package com.anri.pgcompare.model;

import java.util.List;

/**
 * @param columns            ordered constraint columns
 * @param referencedTable    FK only: referenced table (may be schema-qualified)
 * @param referencedColumns  FK only: ordered referenced columns
 * @param definition         canonical definition text from pg_get_constraintdef, used for comparison
 * @param notValid           FK/CHECK declared NOT VALID (pg_constraint.convalidated is false)
 * @param deferrable         pg_constraint.condeferrable — never rendered by pg_get_constraintdef,
 *                           so it is carried as a structured flag instead
 * @param initiallyDeferred  pg_constraint.condeferred
 */
public record ConstraintDef(
        String name,
        ConstraintType type,
        String table,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String definition,
        boolean notValid,
        boolean deferrable,
        boolean initiallyDeferred
) {

    public ConstraintDef(String name, ConstraintType type, String table, List<String> columns,
                         String referencedTable, List<String> referencedColumns, String definition) {
        this(name, type, table, columns, referencedTable, referencedColumns, definition, false, false, false);
    }

    /**
     * Trailing clauses that {@code pg_get_constraintdef} omits. Part of the comparison, so a
     * flag-only change is reported, and appended verbatim to the generated ADD CONSTRAINT.
     */
    public String flagsClause() {
        StringBuilder clause = new StringBuilder();
        if (notValid) {
            clause.append(" NOT VALID");
        }
        if (deferrable) {
            clause.append(" DEFERRABLE");
        }
        if (initiallyDeferred) {
            clause.append(" INITIALLY DEFERRED");
        }
        return clause.toString();
    }
}
