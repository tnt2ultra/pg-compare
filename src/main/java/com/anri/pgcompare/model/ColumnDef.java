package com.anri.pgcompare.model;

/**
 * Column definition as read from pg_catalog. Names are stored as returned by the DB
 * (quoted identifiers keep their case), comparisons use normalized keys.
 *
 * @param identity   identity kind for {@code GENERATED ... AS IDENTITY} columns, null otherwise
 * @param generated  expression of a {@code GENERATED ALWAYS AS (...)} column, null otherwise.
 *                   PostgreSQL keeps that expression in pg_attrdef, so it is reported here and
 *                   never as {@code defaultValue} — the two render as different DDL.
 */
public record ColumnDef(
        String name,
        String dataType,
        boolean nullable,
        String defaultValue,
        IdentityKind identity,
        ColumnGeneration generated,
        String comment
) {

    public ColumnDef(String name, String dataType, boolean nullable, String defaultValue, String comment) {
        this(name, dataType, nullable, defaultValue, null, null, comment);
    }
}
