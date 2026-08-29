package com.anri.pgcompare.extractor;

import com.anri.pgcompare.exception.CompareException;
import com.anri.pgcompare.model.SchemaSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.List;

/**
 * Facade: reads one schema through all extractors and assembles a SchemaSnapshot.
 */
@Component
@RequiredArgsConstructor
public class SchemaExtractor {

    private final TableExtractor tableExtractor;
    private final ConstraintExtractor constraintExtractor;
    private final IndexExtractor indexExtractor;
    private final SequenceExtractor sequenceExtractor;

    public SchemaSnapshot extract(Connection connection, String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        validateSchemaExists(jdbc, schema);
        return new SchemaSnapshot(
                schema,
                tableExtractor.extract(jdbc, schema),
                constraintExtractor.extract(jdbc, schema),
                indexExtractor.extract(jdbc, schema),
                sequenceExtractor.extract(jdbc, schema));
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
