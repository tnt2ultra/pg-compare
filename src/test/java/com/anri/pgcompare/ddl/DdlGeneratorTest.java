package com.anri.pgcompare.ddl;

import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.diff.Severity;
import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.ConstraintType;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlGeneratorTest {

    private final DdlGenerator generator = new DdlGenerator();

    private SchemaDiff diff(List<DiffEntry> entries) {
        return new SchemaDiff("app", "app_v2", entries);
    }

    @Test
    void addedTableGeneratesCreateWithColumns() {
        TableDef table = new TableDef("users", null, List.of(
                new ColumnDef("id", "bigint", false, null, null),
                new ColumnDef("email", "character varying(255)", true, null, null)));
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.TABLE, "users", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null, table)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo("""
                CREATE TABLE "app"."users" (
                    "id" bigint NOT NULL,
                    "email" character varying(255)
                )""");
    }

    @Test
    void addedTableIncludesDefaults() {
        TableDef table = new TableDef("users", null, List.of(
                new ColumnDef("created_at", "timestamp with time zone", false, "now()", null)));
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.TABLE, "users", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null, table)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).contains("\"created_at\" timestamp with time zone NOT NULL DEFAULT now()");
    }

    @Test
    void removedTableGeneratesBreakingDrop() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.TABLE, "users", ChangeType.REMOVED,
                Severity.BREAKING, "removed", new TableDef("users", null, List.of()), null)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo("DROP TABLE \"app\".\"users\"");
        assertThat(statements.getFirst().comment()).contains("BREAKING");
    }

    @Test
    void addedColumnGeneratesAddColumn() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.COLUMN, "users.age", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null,
                new ColumnDef("age", "integer", true, null, null))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql())
                .isEqualTo("ALTER TABLE \"app\".\"users\" ADD COLUMN \"age\" integer");
    }

    @Test
    void addedNotNullColumnGetsReviewComment() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.COLUMN, "users.name", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null,
                new ColumnDef("name", "text", false, null, null))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().comment()).contains("review");
    }

    @Test
    void modifiedColumnGeneratesAlterStatements() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.COLUMN, "users.email", ChangeType.MODIFIED,
                Severity.BREAKING, "changed",
                new ColumnDef("email", "character varying(100)", false, "x", null),
                new ColumnDef("email", "character varying(255)", true, null, null))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0).sql())
                .isEqualTo("ALTER TABLE \"app\".\"users\" ALTER COLUMN \"email\" TYPE character varying(255)");
        assertThat(statements.get(1).sql())
                .isEqualTo("ALTER TABLE \"app\".\"users\" ALTER COLUMN \"email\" DROP NOT NULL");
        assertThat(statements.get(2).sql())
                .isEqualTo("ALTER TABLE \"app\".\"users\" ALTER COLUMN \"email\" DROP DEFAULT");
    }

    @Test
    void addedConstraintGeneratesAddConstraint() {
        ConstraintDef fk = new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                List.of("user_id"), "users", List.of("id"),
                "FOREIGN KEY (user_id) REFERENCES users(id)");
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.CONSTRAINT, "orders.orders_user_fk",
                ChangeType.ADDED, Severity.NON_BREAKING, "added", null, fk)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo(
                "ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"orders_user_fk\" "
                        + "FOREIGN KEY (\"user_id\") REFERENCES \"app\".\"users\" (\"id\")");
    }

    @Test
    void crossSchemaForeignKeyReferenceIsKeptQualified() {
        ConstraintDef fk = new ConstraintDef("orders_lookup_fk", ConstraintType.FOREIGN_KEY, "orders",
                List.of("status_id"), "shared.status", List.of("id"),
                "FOREIGN KEY (status_id) REFERENCES shared.status(id)");
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.CONSTRAINT, "orders.orders_lookup_fk",
                ChangeType.ADDED, Severity.NON_BREAKING, "added", null, fk)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).contains("REFERENCES \"shared\".\"status\"");
    }

    @Test
    void addedPrimaryKeyGeneratesInlineDefinition() {
        ConstraintDef pk = new ConstraintDef("users_pkey", ConstraintType.PRIMARY_KEY, "users",
                List.of("id"), null, null, "PRIMARY KEY (id)");
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.CONSTRAINT, "users.users_pkey",
                ChangeType.ADDED, Severity.NON_BREAKING, "added", null, pk)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).isEqualTo(
                "ALTER TABLE \"app\".\"users\" ADD CONSTRAINT \"users_pkey\" PRIMARY KEY (\"id\")");
    }

    @Test
    void removedConstraintGeneratesDrop() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.CONSTRAINT, "orders.orders_pkey",
                ChangeType.REMOVED, Severity.NON_BREAKING, "removed",
                new ConstraintDef("orders_pkey", ConstraintType.PRIMARY_KEY, "orders",
                        List.of("id"), null, null, "PRIMARY KEY (id)"), null)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql())
                .isEqualTo("ALTER TABLE \"app\".\"orders\" DROP CONSTRAINT \"orders_pkey\"");
    }

    @Test
    void addedIndexUsesCanonicalDefinitionWithSourceSchema() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.INDEX, "users.idx_email", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null,
                new IndexDef("idx_email", "users", false,
                        "CREATE INDEX idx_email ON users USING btree (email)"))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql())
                .isEqualTo("CREATE INDEX idx_email ON \"app\".users USING btree (email)");
    }

    @Test
    void removedIndexGeneratesDrop() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.INDEX, "users.idx_email", ChangeType.REMOVED,
                Severity.NON_BREAKING, "removed",
                new IndexDef("idx_email", "users", false, "CREATE INDEX idx_email ON users (email)"), null)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo("DROP INDEX \"app\".\"idx_email\"");
    }

    @Test
    void modifiedIndexGeneratesDropAndRecreate() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.INDEX, "users.idx_email", ChangeType.MODIFIED,
                Severity.NON_BREAKING, "changed",
                new IndexDef("idx_email", "users", false, "CREATE INDEX idx_email ON users (email)"),
                new IndexDef("idx_email", "users", false, "CREATE INDEX idx_email ON users (email, name)"))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).sql()).isEqualTo("DROP INDEX \"app\".\"idx_email\"");
        assertThat(statements.get(1).sql()).isEqualTo("CREATE INDEX idx_email ON \"app\".users (email, name)");
    }

    @Test
    void addedSequenceGeneratesCreate() {
        SchemaDiff d = diff(List.of(new DiffEntry(ObjectType.SEQUENCE, "orders_id_seq", ChangeType.ADDED,
                Severity.NON_BREAKING, "added", null,
                new SequenceDef("orders_id_seq", 100, 5, 1, 999999))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).isEqualTo(
                "CREATE SEQUENCE \"app\".\"orders_id_seq\" START WITH 100 INCREMENT BY 5 MINVALUE 1 MAXVALUE 999999");
    }

    @Test
    void droppedColumnSkipsDependentConstraintAndIndexDrops() {
        SchemaDiff d = diff(List.of(
                // orders.status is being dropped...
                new DiffEntry(ObjectType.COLUMN, "orders.status", ChangeType.REMOVED, Severity.BREAKING,
                        "removed", new ColumnDef("status", "varchar(20)", false, null, null), null),
                // ...so this index and check constraint disappear with it and must not be dropped explicitly
                new DiffEntry(ObjectType.INDEX, "orders.idx_status", ChangeType.REMOVED, Severity.NON_BREAKING,
                        "removed", new IndexDef("idx_status", "orders", false,
                                "CREATE INDEX idx_status ON orders USING btree (status)"), null),
                new DiffEntry(ObjectType.CONSTRAINT, "orders.status_check", ChangeType.REMOVED,
                        Severity.NON_BREAKING, "removed",
                        new ConstraintDef("status_check", ConstraintType.CHECK, "orders",
                                List.of("status"), null, null, "CHECK ((status)::text = 'new'::text)"), null),
                // unrelated objects must still be emitted
                new DiffEntry(ObjectType.COLUMN, "orders.note", ChangeType.REMOVED, Severity.BREAKING,
                        "removed", new ColumnDef("note", "text", true, null, null), null)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).extracting(DdlStatement::sql)
                .containsExactly(
                        "ALTER TABLE \"app\".\"orders\" DROP COLUMN \"status\"",
                        "ALTER TABLE \"app\".\"orders\" DROP COLUMN \"note\"");
    }

    @Test
    void droppedTableSkipsItsConstraintsIndexesAndColumns() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.TABLE, "legacy", ChangeType.REMOVED, Severity.BREAKING, "removed",
                        new TableDef("legacy", null, List.of()), null),
                new DiffEntry(ObjectType.CONSTRAINT, "legacy.legacy_pkey", ChangeType.REMOVED,
                        Severity.NON_BREAKING, "removed",
                        new ConstraintDef("legacy_pkey", ConstraintType.PRIMARY_KEY, "legacy",
                                List.of("id"), null, null, "PRIMARY KEY (id)"), null),
                new DiffEntry(ObjectType.INDEX, "legacy.idx_x", ChangeType.REMOVED, Severity.NON_BREAKING,
                        "removed", new IndexDef("idx_x", "legacy", false, "CREATE INDEX idx_x ON legacy (x)"), null),
                new DiffEntry(ObjectType.COLUMN, "legacy.x", ChangeType.REMOVED, Severity.BREAKING, "removed",
                        new ColumnDef("x", "integer", true, null, null), null)));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo("DROP TABLE \"app\".\"legacy\"");
    }

    @Test
    void commentChangesGenerateCommentOnStatements() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.COMMENT, "users", ChangeType.MODIFIED, Severity.NON_BREAKING,
                        "comment changed", new TableDef("users", "old", List.of()),
                        new TableDef("users", "target comment", List.of())),
                new DiffEntry(ObjectType.COMMENT, "users.email", ChangeType.MODIFIED, Severity.NON_BREAKING,
                        "comment changed",
                        new ColumnDef("email", "text", true, null, "old"),
                        new ColumnDef("email", "text", true, null, "User email"))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).extracting(DdlStatement::sql).containsExactly(
                "COMMENT ON TABLE \"app\".\"users\" IS 'target comment'",
                "COMMENT ON COLUMN \"app\".\"users\".\"email\" IS 'User email'");
    }

    @Test
    void removedCommentGeneratesCommentIsNull() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.COMMENT, "users", ChangeType.MODIFIED, Severity.NON_BREAKING,
                        "comment changed", new TableDef("users", "old", List.of()),
                        new TableDef("users", null, List.of()))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).isEqualTo("COMMENT ON TABLE \"app\".\"users\" IS NULL");
    }

    @Test
    void commentLiteralsEscapeSingleQuotes() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.COMMENT, "users", ChangeType.MODIFIED, Severity.NON_BREAKING,
                        "comment changed", new TableDef("users", null, List.of()),
                        new TableDef("users", "user's table", List.of()))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements.getFirst().sql()).isEqualTo("COMMENT ON TABLE \"app\".\"users\" IS 'user''s table'");
    }

    @Test
    void commentOnDroppedTableIsSkipped() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.TABLE, "legacy", ChangeType.REMOVED, Severity.BREAKING, "removed",
                        new TableDef("legacy", null, List.of()), null),
                new DiffEntry(ObjectType.COMMENT, "legacy", ChangeType.MODIFIED, Severity.NON_BREAKING,
                        "comment changed", new TableDef("legacy", "old", List.of()),
                        new TableDef("legacy", "new", List.of()))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().sql()).isEqualTo("DROP TABLE \"app\".\"legacy\"");
    }

    @Test
    void addedTableWithCommentEmitsCommentOn() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.TABLE, "users", ChangeType.ADDED, Severity.NON_BREAKING, "added", null,
                        new TableDef("users", "User accounts", List.of()))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).extracting(DdlStatement::sql).containsExactly(
                "CREATE TABLE \"app\".\"users\" (\n)",
                "COMMENT ON TABLE \"app\".\"users\" IS 'User accounts'");
    }

    @Test
    void orderIsSequencesTablesColumnsConstraintsIndexes() {
        SchemaDiff d = diff(List.of(
                new DiffEntry(ObjectType.INDEX, "users.idx", ChangeType.ADDED, Severity.NON_BREAKING, "d", null,
                        new IndexDef("idx", "users", false, "CREATE INDEX idx ON users USING btree (email)")),
                new DiffEntry(ObjectType.TABLE, "users", ChangeType.ADDED, Severity.NON_BREAKING, "d", null,
                        new TableDef("users", null, List.of())),
                new DiffEntry(ObjectType.SEQUENCE, "seq", ChangeType.ADDED, Severity.NON_BREAKING, "d", null,
                        new SequenceDef("seq", 1, 1, 1, 100)),
                new DiffEntry(ObjectType.CONSTRAINT, "users.pk", ChangeType.ADDED, Severity.NON_BREAKING, "d", null,
                        new ConstraintDef("pk", ConstraintType.PRIMARY_KEY, "users",
                                List.of("id"), null, null, "PRIMARY KEY (id)")),
                new DiffEntry(ObjectType.COLUMN, "users.age", ChangeType.ADDED, Severity.NON_BREAKING, "d", null,
                        new ColumnDef("age", "integer", true, null, null))));

        List<DdlStatement> statements = generator.generate(d);

        assertThat(statements).extracting(DdlStatement::sql).map(sql -> sql.split(" ")[0])
                .containsExactly("CREATE", "CREATE", "ALTER", "ALTER", "CREATE");
        assertThat(statements.get(0).sql()).startsWith("CREATE SEQUENCE");
        assertThat(statements.get(1).sql()).startsWith("CREATE TABLE");
        assertThat(statements.get(2).sql()).contains("ADD COLUMN");
        assertThat(statements.get(3).sql()).contains("ADD CONSTRAINT");
        assertThat(statements.get(4).sql()).startsWith("CREATE INDEX");
    }
}
