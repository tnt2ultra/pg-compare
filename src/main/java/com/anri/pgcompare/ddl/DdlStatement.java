package com.anri.pgcompare.ddl;

/**
 * One generated statement with an optional explanatory comment.
 */
public record DdlStatement(String sql, String comment) {

    public static DdlStatement of(String sql) {
        return new DdlStatement(sql, null);
    }

    public static DdlStatement commented(String sql, String comment) {
        return new DdlStatement(sql, comment);
    }
}
