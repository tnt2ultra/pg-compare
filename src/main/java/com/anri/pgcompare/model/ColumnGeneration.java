package com.anri.pgcompare.model;

/**
 * Выражение generated-колонки и способ его материализации.
 *
 * @param expression выражение колонки, возвращённое {@code pg_get_expr} и нормализованное
 *                   для сравнения
 * @param kind       способ хранения: {@code STORED} либо {@code VIRTUAL}
 */
public record ColumnGeneration(String expression, GenerationKind kind) {
}
