package com.anri.pgcompare.extractor;

import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.ConstraintType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Reads PK / FK / UNIQUE / CHECK / EXCLUDE constraints. Column lists are ordered by position
 * via WITH ORDINALITY, comma-joined server-side to keep the mapping trivial. Constraint kinds
 * this tool cannot render are reported and skipped instead of failing the whole comparison.
 */
@Component
@Slf4j
public class ConstraintExtractor {

    private static final String SQL = """
            SELECT con.conname AS name,
                   con.contype AS type,
                   tbl.relname AS table_name,
                   array_to_string((SELECT array_agg(a.attname ORDER BY u.ord)
                                      FROM unnest(con.conkey) WITH ORDINALITY u(attnum, ord)
                                      JOIN pg_catalog.pg_attribute a
                                        ON a.attrelid = con.conrelid AND a.attnum = u.attnum), ',') AS columns,
                   rs.nspname AS ref_schema,
                   rtbl.relname AS ref_table,
                   array_to_string((SELECT array_agg(a.attname ORDER BY u.ord)
                                      FROM unnest(con.confkey) WITH ORDINALITY u(attnum, ord)
                                      JOIN pg_catalog.pg_attribute a
                                        ON a.attrelid = con.confrelid AND a.attnum = u.attnum), ',') AS ref_columns,
                   pg_get_constraintdef(con.oid) AS definition,
                   NOT con.convalidated AS not_valid,
                   con.condeferrable AS deferrable,
                   con.condeferred AS initially_deferred
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class tbl ON tbl.oid = con.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
            LEFT JOIN pg_catalog.pg_class rtbl ON rtbl.oid = con.confrelid
            LEFT JOIN pg_catalog.pg_namespace rs ON rs.oid = rtbl.relnamespace
            WHERE n.nspname = ?
            ORDER BY tbl.relname, con.conname
            """;

    public List<ConstraintDef> extract(JdbcTemplate jdbc, String schema) {
        return jdbc.query(SQL, (rs, i) -> {
            ConstraintType type = mapType(rs.getString("type"));
            if (type == null) {
                log.warn("Skipping constraint '%s' on table '%s' of %s: unsupported kind",
                        rs.getString("name"), rs.getString("table_name"), schema);
                return null;
            }
            String refSchema = rs.getString("ref_schema");
            String refTable = rs.getString("ref_table");
            String qualifiedRefTable = refTable == null ? null
                    : (refSchema != null && !refSchema.equals(schema) ? refSchema + "." + refTable : refTable);
            return new ConstraintDef(
                    rs.getString("name"),
                    type,
                    rs.getString("table_name"),
                    splitColumns(rs.getString("columns")),
                    qualifiedRefTable,
                    splitColumns(rs.getString("ref_columns")),
                    DefinitionNormalizer.normalize(rs.getString("definition"), schema),
                    rs.getBoolean("not_valid"),
                    rs.getBoolean("deferrable"),
                    rs.getBoolean("initially_deferred"));
        }, schema).stream().filter(Objects::nonNull).toList();
    }

    /** Future catalog kinds are skipped rather than failing the comparison of the whole schema. */
    private ConstraintType mapType(String contype) {
        return switch (contype) {
            case "p" -> ConstraintType.PRIMARY_KEY;
            case "f" -> ConstraintType.FOREIGN_KEY;
            case "u" -> ConstraintType.UNIQUE;
            case "c" -> ConstraintType.CHECK;
            case "x" -> ConstraintType.EXCLUSION;
            default -> null;
        };
    }

    private List<String> splitColumns(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.asList(joined.split(","));
    }
}
