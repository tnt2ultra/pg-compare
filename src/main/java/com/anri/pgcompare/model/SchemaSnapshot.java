package com.anri.pgcompare.model;

import java.util.List;

/**
 * Неизменяемое представление одной схемы базы данных в памяти — полный снимок, по которому
 * строится дифф.
 *
 * @param schemaName  имя схемы, вычитанной в этот снимок
 * @param tables      таблицы с их колонками
 * @param constraints констрейнты схемы
 * @param indexes     отдельные индексы схемы
 * @param sequences   sequence, существующие самостоятельно
 */
public record SchemaSnapshot(
        String schemaName,
        List<TableDef> tables,
        List<ConstraintDef> constraints,
        List<IndexDef> indexes,
        List<SequenceDef> sequences
) {
}
