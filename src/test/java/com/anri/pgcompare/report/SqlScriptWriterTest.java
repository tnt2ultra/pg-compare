package com.anri.pgcompare.report;

import com.anri.pgcompare.ddl.DdlGenerator;
import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.diff.Severity;
import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.TableDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptWriterTest {

    private final SqlScriptWriter writer = new SqlScriptWriter(new DdlGenerator());

    @TempDir
    Path dir;

    @Test
    void wrapsStatementsInOneTransaction() throws IOException {
        String script = write(diffWithOneStatement(), true);

        assertThat(script).contains("BEGIN;", "COMMIT;");
        assertThat(script.indexOf("BEGIN;")).isLessThan(script.indexOf("ALTER TABLE"));
        assertThat(script.indexOf("ALTER TABLE")).isLessThan(script.indexOf("COMMIT;"));
    }

    @Test
    void warnsWhenTheTransactionWrapperIsDisabled() throws IOException {
        String script = write(diffWithOneStatement(), false);

        assertThat(script).doesNotContain("BEGIN;", "COMMIT;");
        assertThat(script).contains("-- Not wrapped");
        assertThat(script).contains("ALTER TABLE \"app\".\"users\" ADD COLUMN \"age\" integer;");
    }

    @Test
    void identicalSchemasProduceAnEmptyNonTransactionalScript() throws IOException {
        String script = write(new SchemaDiff("app", "app_v2", List.of()), true);

        assertThat(script).contains("nothing to migrate");
        assertThat(script).doesNotContain("BEGIN;", "COMMIT;");
    }

    @Test
    void perStatementReviewNotesPrecedeTheirStatement() throws IOException {
        SchemaDiff d = new SchemaDiff("app", "app_v2", List.of(new DiffEntry(
                ObjectType.TABLE, "legacy", ChangeType.REMOVED, Severity.BREAKING, "removed",
                new TableDef("legacy", null, List.of()), null)));

        String script = write(d, true);

        assertThat(script).contains("-- BREAKING: drops all data in the table\nDROP TABLE \"app\".\"legacy\";");
    }

    private SchemaDiff diffWithOneStatement() {
        return new SchemaDiff("app", "app_v2", List.of(new DiffEntry(
                ObjectType.COLUMN, "users.age", ChangeType.ADDED, Severity.NON_BREAKING, "added", null,
                new ColumnDef("age", "integer", true, null, null))));
    }

    private String write(SchemaDiff diff, boolean transactional) throws IOException {
        Path out = dir.resolve("migration.sql");
        writer.write(diff, out, transactional);
        return Files.readString(out);
    }
}
