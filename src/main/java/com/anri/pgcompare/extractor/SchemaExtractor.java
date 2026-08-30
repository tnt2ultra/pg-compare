package com.anri.pgcompare.extractor;

import com.anri.pgcompare.exception.CompareException;
import com.anri.pgcompare.model.SchemaSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Фасад: читает одну схему через все экстракторы и собирает {@link SchemaSnapshot}.
 * Проверяет, что схема существует, и объявляет в логе типы отношений, которые в снимок
 * сознательно не входят, — иначе пустой дифф легко принять за «расхождений нет».
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaExtractor {

    /**
     * Отношения, живущие в схеме, но намеренно не входящие в {@link SchemaSnapshot}.
     * Их подсчёт держит пропуск заметным в логе, чтобы пустой дифф не читался как
     * «расхождений нет».
     */
    private static final String IGNORED_RELATIONS_SQL = """
            SELECT c.relkind AS kind, count(*) AS total
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('v', 'm', 'p', 'f')
            GROUP BY c.relkind
            ORDER BY c.relkind
            """;

    /** Подписи {@code pg_class.relkind} для сообщений о непокрытых объектах. */
    private static final Map<Character, String> IGNORED_RELATION_LABELS = Map.of(
            'v', "представления",
            'm', "материализованные представления",
            'p', "секционированные таблицы",
            'f', "foreign-таблицы");

    /** Таблицы и колонки вместе с комментариями. */
    private final TableExtractor tableExtractor;

    /** PK / FK / UNIQUE / CHECK / EXCLUDE. */
    private final ConstraintExtractor constraintExtractor;

    /** Самостоятельные индексы. */
    private final IndexExtractor indexExtractor;

    /** Sequence, не принадлежащие identity-колонкам. */
    private final SequenceExtractor sequenceExtractor;

    /**
     * Собирает полный снимок схемы.
     *
     * @param connection открытое подключение к базе (остается открытым — закрывает его вызывающий код)
     * @param schema     имя схемы
     * @return снимок схемы в памяти
     * @throws CompareException если схема не существует
     */
    public SchemaSnapshot extract(Connection connection, String schema) {
        // suppressClose=true: JdbcTemplate не владеет подключением, его жизненным циклом
        // управляет вызывающий код (try-with-resources в CompareCommand)
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        validateSchemaExists(jdbc, schema);
        warnAboutIgnoredRelations(jdbc, schema);
        return new SchemaSnapshot(
                schema,
                tableExtractor.extract(jdbc, schema),
                constraintExtractor.extract(jdbc, schema),
                indexExtractor.extract(jdbc, schema),
                sequenceExtractor.extract(jdbc, schema));
    }

    /**
     * Выводит в лог количество объектов каждого непокрытого типа.
     *
     * @param jdbc   шаблон для запросов к читаемой схеме
     * @param schema имя схемы
     */
    private void warnAboutIgnoredRelations(JdbcTemplate jdbc, String schema) {
        jdbc.query(IGNORED_RELATIONS_SQL, (RowCallbackHandler) rs -> {
            char kind = rs.getString("kind").charAt(0);
            String label = IGNORED_RELATION_LABELS.getOrDefault(kind, "объекты с relkind " + kind);
            log.warn("Схема '{}': {} вне зоны сравнения — {} шт.", schema, label, rs.getInt("total"));
        }, schema);
    }

    /**
     * Проверяет существование схемы до чтения: без этой проверки опечатка в имени схемы
     * выглядела бы как «обе схемы пустые и потому идентичны».
     *
     * @param jdbc   шаблон для запросов к каталогу
     * @param schema имя схемы
     * @throws CompareException если схемы в этом подключении нет
     */
    private void validateSchemaExists(JdbcTemplate jdbc, String schema) {
        List<String> found = jdbc.query(
                "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = ?",
                (rs, i) -> rs.getString(1), schema);
        if (found.isEmpty()) {
            throw new CompareException("Схема '%s' не существует в этом подключении".formatted(schema));
        }
    }
}
