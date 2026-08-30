package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.SequenceDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Читает sequences, которые существуют сами по себе. Sequences, обслуживающие identity-колонку,
 * пропускаются: они входят в определение колонки и не должны создаваться отдельно.
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

    /**
     * Вычитывает схему одним запросом к {@code pg_sequence}: имя (из {@code pg_class}) и параметры
     * {@code seqstart}, {@code seqincrement}, {@code seqmin}, {@code seqmax}. Внутренние зависимости
     * отсекаются {@code NOT EXISTS} по {@code pg_depend} с deptype {@code 'i'} — это sequences,
     * созданные identity-колонкой; они пересоздаются её определением, и отдельный
     * {@code CREATE SEQUENCE} для них дал бы дубликат. Текущее значение ({@code last_value})
     * в снимок не входит: сравниваются только параметры определения.
     *
     * <p>Сортировка {@code ORDER BY c.relname} даёт детерминированный порядок, нужный для
     * стабильного диффа между прогонами.
     *
     * @param jdbc   шаблон для запросов к каталогу конкретной БД
     * @param schema имя сравниваемой схемы
     * @return «обычные» sequences схемы, отсортированные по имени
     */
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
