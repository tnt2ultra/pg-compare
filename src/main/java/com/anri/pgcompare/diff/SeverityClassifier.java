package com.anri.pgcompare.diff;

/**
 * Определяет, насколько опасно изменение для приложений, работающих со схемой.
 * Комментарии — только документация, они не ломают запущенное приложение; любое удаление
 * забирает то, на что приложения опираются — данные, гарантию уникальности, нужную для
 * {@code ON CONFLICT}, referential action или план запроса, — поэтому удаления считаются
 * ломающими.
 */
public class SeverityClassifier {

    /**
     * Классифицирует запись отчёта по типу объекта и типу изменения.
     *
     * @param objectType тип объекта (таблица, колонка, констрейнт, ...)
     * @param changeType тип изменения (добавление, удаление, изменение)
     * @return severity для отчёта и сводки
     */
    public Severity classify(ObjectType objectType, ChangeType changeType) {
        if (objectType == ObjectType.COMMENT) {
            return Severity.INFO;
        }
        return switch (changeType) {
            case REMOVED -> Severity.BREAKING;
            case ADDED -> Severity.NON_BREAKING;
            // изменённая колонка переписывает данные, которые приложения уже читают;
            // переопределённые констрейнт или индекс оставляют объект на месте
            case MODIFIED -> objectType == ObjectType.COLUMN
                    ? Severity.BREAKING
                    : Severity.NON_BREAKING;
        };
    }
}
