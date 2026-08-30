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
 * Facade: reads one schema through all extractors and assembles a SchemaSnapshot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaExtractor {

    /**
     * Relations that live in a schema but are deliberately not part of a SchemaSnapshot.
     * Counting them keeps the omission visible in the log instead of looking like "no differences".
     */
    private static final String IGNORED_RELATIONS_SQL = """
            SELECT c.relkind AS kind, count(*) AS total
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('v', 'm', 'p', 'f')
            GROUP BY c.relkind
            ORDER BY c.relkind
            """;

    private static final Map<Character, String> IGNORED_RELATION_LABELS = Map.of(
            'v', "views",
            'm', "materialized views",
            'p', "partitioned tables",
            'f', "foreign tables");

    private final TableExtractor tableExtractor;
    private final ConstraintExtractor constraintExtractor;
    private final IndexExtractor indexExtractor;
    private final SequenceExtractor sequenceExtractor;

    public SchemaSnapshot extract(Connection connection, String schema) {
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

    private void warnAboutIgnoredRelations(JdbcTemplate jdbc, String schema) {
        jdbc.query(IGNORED_RELATIONS_SQL, (RowCallbackHandler) rs -> {
            char kind = rs.getString("kind").charAt(0);
            String label = IGNORED_RELATION_LABELS.getOrDefault(kind, "relations of kind " + kind);
            log.warn("Schema '{}' has {} {} not covered by the comparison", schema, rs.getInt("total"), label);
        }, schema);
    }

    private void validateSchemaExists(JdbcTemplate jdbc, String schema) {
        List<String> found = jdbc.query(
                "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = ?",
                (rs, i) -> rs.getString(1), schema);
        if (found.isEmpty()) {
            throw new CompareException("Schema '%s' does not exist on the connection".formatted(schema));
        }
    }
}
