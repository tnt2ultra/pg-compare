package com.anri.pgcompare.model;

/**
 * Определение колонки, вычитанное из {@code pg_catalog}. Имена хранятся в том виде, как их
 * вернула БД (экранированные идентификаторы сохраняют регистр), а сравнение идёт по
 * нормализованным ключам.
 *
 * @param name         имя колонки
 * @param dataType     тип колонки в том виде, как его вернул {@code format_type}
 * @param nullable     {@code true}, если колонка допускает NULL
 * @param defaultValue выражение значения по умолчанию либо {@code null}; для
 *                     generated-колонок всегда {@code null}
 * @param identity     вид identity для колонок {@code GENERATED ... AS IDENTITY},
 *                     {@code null} иначе
 * @param generated    выражение {@code GENERATED ALWAYS AS (...)} колонки, {@code null} иначе.
 *                     PostgreSQL хранит это выражение в {@code pg_attrdef}, поэтому оно
 *                     сообщается здесь и никогда как {@code defaultValue} — в DDL это две
 *                     разные конструкции.
 * @param comment      комментарий колонки либо {@code null}, если он не задан
 */
public record ColumnDef(
        String name,
        String dataType,
        boolean nullable,
        String defaultValue,
        IdentityKind identity,
        ColumnGeneration generated,
        String comment
) {

    /**
     * Упрощённый конструктор для колонки без identity и без generated-выражения.
     *
     * @param name         имя колонки
     * @param dataType     тип колонки
     * @param nullable     допускает ли колонка NULL
     * @param defaultValue выражение значения по умолчанию либо {@code null}
     * @param comment      комментарий колонки либо {@code null}
     */
    public ColumnDef(String name, String dataType, boolean nullable, String defaultValue, String comment) {
        this(name, dataType, nullable, defaultValue, null, null, comment);
    }
}
