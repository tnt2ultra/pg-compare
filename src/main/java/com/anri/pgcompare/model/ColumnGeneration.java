package com.anri.pgcompare.model;

/**
 * Expression of a generated column and how it is materialized.
 */
public record ColumnGeneration(String expression, GenerationKind kind) {
}
