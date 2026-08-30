package com.anri.pgcompare.model;

import java.util.List;

/**
 * Определение констрейнта. Сравнение идёт по каноническому тексту определения плюс по
 * структурным флагам, которые сервер в этот текст не печатает.
 *
 * @param name                имя констрейнта
 * @param type                вид констрейнта
 * @param table               имя таблицы, на которой определён констрейнт
 * @param columns             колонки констрейнта в порядке объявления
 * @param referencedTable     только FK: опорная (referenced) таблица (может быть с именем схемы)
 * @param referencedColumns   только FK: колонки опорной таблицы в порядке объявления
 * @param definition          канонический текст определения из {@code pg_get_constraintdef},
 *                            используется для сравнения
 * @param notValid            FK/CHECK, объявленные как {@code NOT VALID}
 *                            ({@code pg_constraint.convalidated} — {@code false})
 * @param deferrable          {@code pg_constraint.condeferrable} — {@code pg_get_constraintdef}
 *                            никогда не рендерит это свойство, поэтому оно переносится
 *                            отдельным структурным флагом
 * @param initiallyDeferred   {@code pg_constraint.condeferred}
 */
public record ConstraintDef(
        String name,
        ConstraintType type,
        String table,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String definition,
        boolean notValid,
        boolean deferrable,
        boolean initiallyDeferred
) {

    /**
     * Конструктор без флагов: констрейнт объявлен без {@code NOT VALID} и без
     * {@code DEFERRABLE}.
     *
     * @param name                имя констрейнта
     * @param type                вид констрейнта
     * @param table               имя таблицы, на которой определён констрейнт
     * @param columns             колонки констрейнта в порядке объявления
     * @param referencedTable     только FK: опорная (referenced) таблица
     * @param referencedColumns   только FK: колонки опорной таблицы в порядке объявления
     * @param definition          канонический текст определения из {@code pg_get_constraintdef}
     */
    public ConstraintDef(String name, ConstraintType type, String table, List<String> columns,
                         String referencedTable, List<String> referencedColumns, String definition) {
        this(name, type, table, columns, referencedTable, referencedColumns, definition, false, false, false);
    }

    /**
     * Хвост флаговых предложений, которые {@code pg_get_constraintdef} не печатает.
     * Участвует в сравнении — поэтому различие только по флагам тоже попадает в отчёт, —
     * и дословно дописывается к генерируемому {@code ADD CONSTRAINT}.
     *
     * @return склейка {@code NOT VALID}, {@code DEFERRABLE}, {@code INITIALLY DEFERRED}
     *         в этом порядке либо пустая строка, если флаги не установлены
     */
    public String flagsClause() {
        StringBuilder clause = new StringBuilder();
        if (notValid) {
            clause.append(" NOT VALID");
        }
        if (deferrable) {
            clause.append(" DEFERRABLE");
        }
        if (initiallyDeferred) {
            clause.append(" INITIALLY DEFERRED");
        }
        return clause.toString();
    }
}
