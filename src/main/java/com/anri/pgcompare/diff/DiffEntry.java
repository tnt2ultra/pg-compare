package com.anri.pgcompare.diff;

/**
 * Одна запись отчёта о различии двух схем.
 *
 * @param objectType  тип объекта, которого касается различие
 * @param objectName  полностью квалифицированное имя объекта, например "users.email" для колонки
 * @param changeType  тип изменения (добавление, удаление, изменение)
 * @param severity    насколько изменение опасно для приложений
 * @param description готовый текст различия; попадает в отчёт и потому остаётся английским
 * @param before      значение со стороны источника ({@code null} для {@code ADDED})
 * @param after       значение со стороны цели ({@code null} для {@code REMOVED}); это
 *                    определение объекта целиком — {@link SchemaDiff} сериализуется в JSON как есть
 */
public record DiffEntry(
        ObjectType objectType,
        String objectName,
        ChangeType changeType,
        Severity severity,
        String description,
        Object before,
        Object after
) {
}
