package com.anri.pgcompare.diff;

import java.util.List;

/**
 * @param sourceSchema schema name of the source side
 * @param targetSchema schema name of the target side
 * @param entries      all differences, ordered by object type and name
 */
public record SchemaDiff(
        String sourceSchema,
        String targetSchema,
        List<DiffEntry> entries
) {

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
