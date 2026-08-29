package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.TableDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads tables and columns from pg_catalog. All columns of the schema are fetched
 * in one query and grouped by table in memory.
 */
@Component
public class TableExtractor {

    private static final String TABLES_SQL = """
            SELECT c.relname AS name
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind = 'r'
            ORDER BY c.relname
            """;

    private static final String COLUMNS_SQL = """
            SELECT tbl.relname AS table_name,
                   a.attname AS name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   pg_get_expr(d.adbin, d.adrelid) AS default_value,
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
            ColumnDef column = new ColumnDef(
                    rs.getString("name"),
                    rs.getString("data_type"),
                    rs.getBoolean("nullable"),
                    DefinitionNormalizer.normalizeDefault(rs.getString("default_value"), schema),
                    rs.getString("comment"));
            columnsByTable.computeIfAbsent(rs.getString("table_name"), k -> new java.util.ArrayList<>()).add(column);
        }, schema);

        List<TableDef> tables = jdbc.query(TABLES_SQL,
                (rs, i) -> new TableDef(rs.getString("name"),
                        columnsByTable.getOrDefault(rs.getString("name"), List.of())),
                schema);
        return tables;
    }
}
