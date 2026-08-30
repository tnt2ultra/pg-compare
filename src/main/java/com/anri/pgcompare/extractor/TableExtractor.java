package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ColumnGeneration;
import com.anri.pgcompare.model.GenerationKind;
import com.anri.pgcompare.model.IdentityKind;
import com.anri.pgcompare.model.TableDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads tables, columns and their comments from pg_catalog. All rows of the schema
 * are fetched in one query per kind and grouped by table in memory.
 */
@Component
public class TableExtractor {

    private static final String TABLES_SQL = """
            SELECT c.relname AS name,
                   de.description AS comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_description de
                   ON de.objoid = c.oid AND de.objsubid = 0
            WHERE n.nspname = ? AND c.relkind = 'r'
            ORDER BY c.relname
            """;

    private static final String COLUMNS_SQL = """
            SELECT tbl.relname AS table_name,
                   a.attname AS name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   a.attidentity AS identity,
                   a.attgenerated AS generated,
                   pg_get_expr(d.adbin, d.adrelid) AS column_expression,
                   de.description AS comment
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class tbl ON tbl.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_description de ON de.objoid = a.attrelid AND de.objsubid = a.attnum
            WHERE n.nspname = ? AND tbl.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY tbl.relname, a.attnum
            """;

    public List<TableDef> extract(JdbcTemplate jdbc, String schema) {
        Map<String, List<ColumnDef>> columnsByTable = new LinkedHashMap<>();
        jdbc.query(COLUMNS_SQL, rs -> {
            String expression = DefinitionNormalizer.normalizeDefault(rs.getString("column_expression"), schema);
            char generated = code(rs.getString("generated"));
            ColumnDef column = new ColumnDef(
                    rs.getString("name"),
                    rs.getString("data_type"),
                    rs.getBoolean("nullable"),
                    generated == '\0' ? expression : null,
                    IdentityKind.fromCatalogCode(code(rs.getString("identity"))),
                    generated == '\0' ? null
                            : new ColumnGeneration(expression, GenerationKind.fromCatalogCode(generated)),
                    rs.getString("comment"));
            columnsByTable.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>()).add(column);
        }, schema);

        return jdbc.query(TABLES_SQL,
                (rs, i) -> new TableDef(rs.getString("name"),
                        rs.getString("comment"),
                        columnsByTable.getOrDefault(rs.getString("name"), List.of())),
                schema);
    }

    /** pg_catalog flags are blank-padded single characters; '\0' marks "not set". */
    private static char code(String value) {
        return value == null || value.isEmpty() ? '\0' : value.charAt(0);
    }
}
