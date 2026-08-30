package com.anri.pgcompare.diff;

import java.util.List;

/**
 * Результат сравнения двух схем — то, что уходит в JSON-отчёт и в консольную сводку.
 *
 * @param sourceSchema имя схемы со стороны источника
 * @param targetSchema имя схемы со стороны цели
 * @param entries      все различия, упорядоченные по типу объекта и имени
 */
public record SchemaDiff(
        String sourceSchema,
        String targetSchema,
        List<DiffEntry> entries
) {

    /**
     * @return {@code true}, если схемы идентичны и записей нет
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
