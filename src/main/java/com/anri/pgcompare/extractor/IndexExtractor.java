package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.IndexDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Читает самостоятельные индексы; индексы, обслуживающие PK/UNIQUE-констрейнты,
 * исключены (они уже отдаются как констрейнты).
 */
@Component
public class IndexExtractor {

    private static final String SQL = """
            SELECT i.relname AS name,
                   tbl.relname AS table_name,
                   ix.indisunique AS unique,
                   pg_get_indexdef(ix.indexrelid) AS definition
            FROM pg_catalog.pg_index ix
            JOIN pg_catalog.pg_class i ON i.oid = ix.indexrelid
            JOIN pg_catalog.pg_class tbl ON tbl.oid = ix.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
            WHERE n.nspname = ?
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = ix.indexrelid)
            ORDER BY tbl.relname, i.relname
            """;

    /**
     * Вычитывает схему одним запросом к {@code pg_index}: имя, таблица-владелец, флаг уникальности
     * и каноническое определение из {@code pg_get_indexdef}. Индексы, на которые опирается любой
     * констрейнт ({@code pg_constraint.conindid}), отсекаются условием {@code NOT EXISTS} — иначе
     * PK/UNIQUE попали бы в дифф дважды, как констрейнт и как индекс.
     *
     * <p>Сортировка {@code tbl.relname, i.relname} даёт детерминированный порядок, нужный для
     * стабильного диффа между прогонами.
     *
     * @param jdbc   шаблон для запросов к каталогу конкретной БД
     * @param schema имя сравниваемой схемы
     * @return индексы схемы (без констрейнтных), отсортированные по таблице, затем по имени индекса
     */
    public List<IndexDef> extract(JdbcTemplate jdbc, String schema) {
        return jdbc.query(SQL, (rs, i) -> new IndexDef(
                        rs.getString("name"),
                        rs.getString("table_name"),
                        rs.getBoolean("unique"),
                        DefinitionNormalizer.normalize(rs.getString("definition"), schema)),
                schema);
    }
}
