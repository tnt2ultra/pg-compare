package com.anri.pgcompare.model;

/**
 * Identity kind from {@code pg_attribute.attidentity}: how the column's sequence is fed.
 */
public enum IdentityKind {
    ALWAYS("ALWAYS"),
    BY_DEFAULT("BY DEFAULT");

    private final String sql;

    IdentityKind(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static IdentityKind fromCatalogCode(char code) {
        return switch (code) {
            case 'a' -> ALWAYS;
            case 'd' -> BY_DEFAULT;
            default -> null;
        };
    }
}
