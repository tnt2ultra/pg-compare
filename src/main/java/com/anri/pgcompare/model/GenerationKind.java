package com.anri.pgcompare.model;

/**
 * Storage of a generated column, from {@code pg_attribute.attgenerated}.
 * Only STORED exists up to PostgreSQL 17; VIRTUAL was added in 18.
 */
public enum GenerationKind {
    STORED("STORED"),
    VIRTUAL("VIRTUAL");

    private final String sql;

    GenerationKind(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static GenerationKind fromCatalogCode(char code) {
        return switch (code) {
            case 's' -> STORED;
            case 'v' -> VIRTUAL;
            default -> null;
        };
    }
}
