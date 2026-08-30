package com.anri.pgcompare.model;

/**
 * Вид identity из {@code pg_attribute.attidentity}: как заполняется sequence колонки.
 */
public enum IdentityKind {
    /** Значение выдаёт только sequence: явная вставка в такую колонку отклоняется. */
    ALWAYS("ALWAYS"),
    /** Sequence подставляет значение по умолчанию, но явно указанное значение принимается. */
    BY_DEFAULT("BY DEFAULT");

    private final String sql;

    /**
     * Сохраняет текстовое представление вида.
     *
     * @param sql текстовое представление вида в DDL
     */
    IdentityKind(String sql) {
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
     * @param code значение {@code pg_attribute.attidentity}
     * @return найденный вид либо {@code null}, если код неизвестен
     */
    public static IdentityKind fromCatalogCode(char code) {
        return switch (code) {
            case 'a' -> ALWAYS;
            case 'd' -> BY_DEFAULT;
            default -> null;
        };
    }
}
