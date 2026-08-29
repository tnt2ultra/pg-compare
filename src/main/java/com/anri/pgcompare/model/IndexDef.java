package com.anri.pgcompare.model;

/**
 * @param definition canonical index definition (CREATE INDEX body: method, columns, order, predicate)
 */
public record IndexDef(
        String name,
        String table,
        boolean unique,
        String definition
) {
}
