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
 * Читает таблицы, колонки и их комментарии из {@code pg_catalog}. Все строки схемы
 * вычитываются одним запросом на каждый вид объектов, а затем группируются по
 * таблицам в памяти.
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

    /**
     * Сначала одним запросом к {@code pg_attribute} вычитываются все колонки схемы: тип из
     * {@code format_type}, флаг NULL/NOT NULL, коды identity и generated, выражение дефолта из
     * {@code pg_attrdef} и комментарий из {@code pg_description}. Колонки раскладываются по
     * таблицам в {@link LinkedHashMap}, после чего запросом к {@code pg_class} (relkind
     * {@code 'r'}) забираются сами таблицы с их комментариями.
     *
     * <p>Запрос колонок отсортирован по {@code tbl.relname, a.attnum}, поэтому порядок колонок
     * внутри таблицы совпадает с порядком объявления; запрос таблиц отсортирован по имени.
     * Оба порядка нужны для стабильного диффа между прогонами.
     *
     * @param jdbc   шаблон для запросов к каталогу конкретной БД
     * @param schema имя сравниваемой схемы
     * @return таблицы схемы с их колонками, отсортированные по имени таблицы
     */
    public List<TableDef> extract(JdbcTemplate jdbc, String schema) {
        Map<String, List<ColumnDef>> columnsByTable = new LinkedHashMap<>();
        jdbc.query(COLUMNS_SQL, rs -> {
            String expression = DefinitionNormalizer.normalizeDefault(rs.getString("column_expression"), schema);
            char generated = code(rs.getString("generated"));
            // pg_attrdef хранит и DEFAULT, и выражение генерации: одно и то же значение
            // pg_get_expr попадает либо в defaultValue, либо в ColumnGeneration — разделение
            // идёт по attgenerated, иначе сгенерированная колонка получила бы фантомный дефолт
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

    /**
     * Флаги в {@code pg_catalog} — одиночные символы, дополненные пробелами; пустое значение
     * означает «флаг не установлен». {@code '\0'} используется в коде как признанный маркер
     * «не задано»: тип {@code char} не допускает {@code null}, а нулевой символ не совпадает
     * ни с одним реальным кодом каталога ({@code 's'} / {@code 'v'} у generated и
     * {@code 'a'} / {@code 'd'} у identity), так что проверка {@code == '\0'} однозначно
     * отличает unset от любого установленного флага.
     *
     * @param value сырое значение флага, вернутое драйвером (может быть {@code null} или пустым)
     * @return первый символ значения либо {@code '\0'}, если флаг не установлен
     */
    private static char code(String value) {
        return value == null || value.isEmpty() ? '\0' : value.charAt(0);
    }
}
