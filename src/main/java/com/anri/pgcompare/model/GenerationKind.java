package com.anri.pgcompare.model;

/**
 * Способ хранения generated-колонки, из {@code pg_attribute.attgenerated}.
 * До PostgreSQL 17 существует только STORED; VIRTUAL добавлен в 18.
 */
public enum GenerationKind {
    /** Выражение вычисляется при записи и сохраняется в строке. */
    STORED("STORED"),
    /** Выражение вычисляется при чтении и в строке не хранится. */
    VIRTUAL("VIRTUAL");

    private final String sql;

    /**
     * Сохраняет текстовое представление вида.
     *
     * @param sql текстовое представление вида в DDL
     */
    GenerationKind(String sql) {
        this.sql = sql;
    }

    /**
     * @return текст вида, подставляемый в генерируемый DDL
     */
    public String sql() {
        return sql;
    }

    /**
     * Преобразует код каталога в значение перечисления.
     *
     * @param code значение {@code pg_attribute.attgenerated}
     * @return найденный вид либо {@code null}, если код неизвестен
     */
    public static GenerationKind fromCatalogCode(char code) {
        return switch (code) {
            case 's' -> STORED;
            case 'v' -> VIRTUAL;
            default -> null;
        };
    }
}
