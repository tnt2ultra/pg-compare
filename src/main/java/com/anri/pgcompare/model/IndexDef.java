package com.anri.pgcompare.model;

/**
 * Определение отдельного индекса; индексы, обслуживающие констрейнты, в снимок не входят.
 *
 * @param name       имя индекса
 * @param table      имя таблицы, на которой создан индекс
 * @param unique     {@code true} для уникального индекса
 * @param definition каноническое определение индекса (тело CREATE INDEX: метод, колонки, порядок, предикат)
 */
public record IndexDef(
        String name,
        String table,
        boolean unique,
        String definition
) {
}
