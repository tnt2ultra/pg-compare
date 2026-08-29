package com.anri.pgcompare.model;

import java.util.List;

/**
 * @param columns            ordered constraint columns
 * @param referencedTable    FK only: referenced table (may be schema-qualified)
 * @param referencedColumns  FK only: ordered referenced columns
 * @param definition         PK/UNIQUE/CHECK: canonical definition text used for comparison
 */
public record ConstraintDef(
        String name,
        ConstraintType type,
        String table,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String definition
) {
}
