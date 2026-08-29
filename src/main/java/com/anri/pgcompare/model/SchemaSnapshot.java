package com.anri.pgcompare.model;

import java.util.List;

/**
 * Immutable in-memory representation of one database schema.
 */
public record SchemaSnapshot(
        String schemaName,
        List<TableDef> tables,
        List<ConstraintDef> constraints,
        List<IndexDef> indexes,
        List<SequenceDef> sequences
) {
}
