package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.SequenceDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads sequences that exist in their own right. Sequences backing an identity column
 * are skipped: they are part of the column definition and must not be created separately.
 */
@Component
public class SequenceExtractor {

    private static final String SQL = """
            SELECT c.relname AS name,
                   s.seqstart AS start_value,
                   s.seqincrement AS increment,
                   s.seqmin AS min_value,
                   s.seqmax AS max_value
            FROM pg_catalog.pg_sequence s
            JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                               WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                                 AND d.objid = c.oid
                                 AND d.deptype = 'i')
            ORDER BY c.relname
            """;

    public List<SequenceDef> extract(JdbcTemplate jdbc, String schema) {
        return jdbc.query(SQL, (rs, i) -> new SequenceDef(
                        rs.getString("name"),
                        rs.getLong("start_value"),
                        rs.getLong("increment"),
                        rs.getLong("min_value"),
                        rs.getLong("max_value")),
                schema);
    }
}
