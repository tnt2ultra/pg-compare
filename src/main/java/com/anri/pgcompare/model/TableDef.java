package com.anri.pgcompare.model;

import java.util.List;

/**
 * Определение таблицы с её колонками и комментарием.
 *
 * @param name    имя таблицы внутри схемы
 * @param comment комментарий таблицы либо {@code null}, если он не задан
 * @param columns колонки в порядке объявления: порядок фиксирован, чтобы повторные прогоны
 *                давали идентичный отчёт
 */
public record TableDef(
        String name,
        String comment,
        List<ColumnDef> columns
) {
}
