package com.anri.pgcompare.model;

/**
 * Column definition as read from pg_catalog. Names are stored as returned by the DB
 * (quoted identifiers keep their case), comparisons use normalized keys.
 */
public record ColumnDef(
        String name,
        String dataType,
        boolean nullable,
        String defaultValue,
        String comment
) {
}
