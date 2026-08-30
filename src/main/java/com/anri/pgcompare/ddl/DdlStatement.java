package com.anri.pgcompare.ddl;

/**
 * Один сгенерированный оператор с необязательным поясняющим комментарием.
 *
 * @param sql     текст SQL-оператора
 * @param comment пояснение к оператору либо {@code null}; пишется строкой {@code -- ...} выше
 */
public record DdlStatement(String sql, String comment) {

    /**
     * Оператор без поясняющего комментария.
     *
     * @param sql текст SQL-оператора
     * @return запись без комментария
     */
    public static DdlStatement of(String sql) {
        return new DdlStatement(sql, null);
    }

    /**
     * Оператор с пояснением: так помечаются небезопасные действия, требующие ревью.
     *
     * @param sql     текст SQL-оператора
     * @param comment пояснение к оператору
     * @return запись с комментарием
     */
    public static DdlStatement commented(String sql, String comment) {
        return new DdlStatement(sql, comment);
    }
}
