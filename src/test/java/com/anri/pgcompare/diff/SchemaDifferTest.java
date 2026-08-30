package com.anri.pgcompare.diff;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ColumnGeneration;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.ConstraintType;
import com.anri.pgcompare.model.GenerationKind;
import com.anri.pgcompare.model.IdentityKind;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты сравнения снимков схемы: обнаружение добавлений/снятий/изменений, регистронезависимое
 * сопоставление имён и severity записей. БД не нужна — тесты работают на records из пакета model.
 * Ожидания сравнивают тексты описаний различий дословно: они попадают в JSON-отчёт и потому
 * остаются английскими.
 */
class SchemaDifferTest {

    private final SchemaDiffer differ = new SchemaDiffer(new SeverityClassifier());

    /**
     * Фикстура снимка с именем схемы {@code public} и остальными секциями по умолчанию.
     *
     * @param tables таблицы
     * @param constraints констрейнты
     * @param indexes индексы
     * @param sequences sequence
     * @return снимок схемы
     */
    private SchemaSnapshot snapshot(List<TableDef> tables,
                                    List<ConstraintDef> constraints,
                                    List<IndexDef> indexes,
                                    List<SequenceDef> sequences) {
        return new SchemaSnapshot("public", tables, constraints, indexes, sequences);
    }

    @Test
    void identicalSnapshotsProduceNoEntries() {
        SchemaSnapshot s = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("id", "bigint", false, null, null)))),
                List.of(new ConstraintDef("users_pkey", ConstraintType.PRIMARY_KEY, "users",
                        List.of("id"), null, null, "PRIMARY KEY (id)")),
                List.of(), List.of());

        SchemaDiff diff = differ.diff(s, s);

        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void addedAndRemovedTablesAreDetected() {
        SchemaSnapshot source = snapshot(List.of(), List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("orders", null, List.of())),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        DiffEntry entry = diff.entries().getFirst();
        assertThat(entry.objectType()).isEqualTo(ObjectType.TABLE);
        assertThat(entry.changeType()).isEqualTo(ChangeType.ADDED);
        assertThat(entry.severity()).isEqualTo(Severity.NON_BREAKING);
    }

    @Test
    void removedTableIsBreaking() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("orders", null, List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(List.of(), List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.REMOVED);
        assertThat(diff.entries().getFirst().severity()).isEqualTo(Severity.BREAKING);
    }

    @Test
    void columnTypeChangeIsModifiedAndBreaking() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("email", "character varying(100)", true, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("email", "character varying(255)", true, null, null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        DiffEntry entry = diff.entries().getFirst();
        assertThat(entry.objectType()).isEqualTo(ObjectType.COLUMN);
        assertThat(entry.changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(entry.severity()).isEqualTo(Severity.BREAKING);
        assertThat(entry.objectName()).isEqualTo("users.email");
        assertThat(entry.description()).contains("character varying(100) -> character varying(255)");
    }

    @Test
    void notNullToggleIsDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("phone", "text", false, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("phone", "text", true, null, null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().description()).isEqualTo("became nullable");
    }

    @Test
    void addedColumnIsNonBreaking() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("age", "integer", true, null, null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.ADDED);
        assertThat(diff.entries().getFirst().severity()).isEqualTo(Severity.NON_BREAKING);
        assertThat(diff.entries().getFirst().objectName()).isEqualTo("users.age");
    }

    @Test
    void defaultValueChangeIsDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("created_at", "timestamp without time zone", true, "now()", null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("created_at", "timestamp without time zone", true,
                                "LOCALTIMESTAMP(6)", null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().description()).contains("default changed");
    }

    @Test
    void constraintDefinitionChangeIsDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("orders", null, List.of())),
                List.of(new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                        List.of("user_id"), "users", List.of("id"),
                        "FOREIGN KEY (user_id) REFERENCES users(id)")),
                List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("orders", null, List.of())),
                List.of(new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                        List.of("user_id"), "users", List.of("uid"),
                        "FOREIGN KEY (user_id) REFERENCES users(uid)")),
                List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(diff.entries().getFirst().objectName()).isEqualTo("orders.orders_user_fk");
    }

    @Test
    void identityChangeIsDetectedAsBreaking() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("id", "bigint", false, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("id", "bigint", false, null, IdentityKind.ALWAYS, null, null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(diff.entries().getFirst().description())
                .isEqualTo("identity changed: none -> GENERATED ALWAYS AS IDENTITY");
    }

    @Test
    void generatedExpressionChangeIsDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("docs", null, List.of(new ColumnDef("doubled", "integer", true,
                        null, null, new ColumnGeneration("(total * 2)", GenerationKind.STORED), null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("docs", null, List.of(new ColumnDef("doubled", "integer", true,
                        null, null, new ColumnGeneration("(total * 3)", GenerationKind.STORED), null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().description())
                .isEqualTo("generation changed: GENERATED ALWAYS AS ((total * 2)) STORED"
                        + " -> GENERATED ALWAYS AS ((total * 3)) STORED");
    }

    @Test
    void constraintOptionChangeWithoutDefinitionChangeIsDetected() {
        ConstraintDef before = new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                List.of("user_id"), "users", List.of("id"),
                "FOREIGN KEY (user_id) REFERENCES users(id)");
        ConstraintDef after = new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                List.of("user_id"), "users", List.of("id"),
                "FOREIGN KEY (user_id) REFERENCES users(id)", false, true, true);
        SchemaSnapshot source = snapshot(List.of(new TableDef("orders", null, List.of())),
                List.of(before), List.of(), List.of());
        SchemaSnapshot target = snapshot(List.of(new TableDef("orders", null, List.of())),
                List.of(after), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(diff.entries().getFirst().description())
                .isEqualTo("options changed: none -> DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    void indexChangesAreDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of())),
                List.of(),
                List.of(new IndexDef("idx_email", "users", false,
                        "CREATE INDEX idx_email ON users USING btree (email)")),
                List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of())),
                List.of(),
                List.of(new IndexDef("idx_phone", "users", false,
                        "CREATE INDEX idx_phone ON users USING btree (phone)")),
                List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(2);
        assertThat(diff.entries()).extracting(DiffEntry::changeType)
                .containsExactlyInAnyOrder(ChangeType.ADDED, ChangeType.REMOVED);
    }

    @Test
    void sequenceParameterChangeIsDetected() {
        SchemaSnapshot source = snapshot(List.of(), List.of(), List.of(),
                List.of(new SequenceDef("orders_id_seq", 1, 1, 1, 1000)));
        SchemaSnapshot target = snapshot(List.of(), List.of(), List.of(),
                List.of(new SequenceDef("orders_id_seq", 1, 10, 1, 1000)));

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().objectType()).isEqualTo(ObjectType.SEQUENCE);
        assertThat(diff.entries().getFirst().changeType()).isEqualTo(ChangeType.MODIFIED);
    }

    @Test
    void sameNamesDifferentCaseAreMatched() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("Users", null, List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of())),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void tableCommentChangeProducesCommentEntry() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", "old comment", List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", "new comment", List.of())),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        DiffEntry entry = diff.entries().getFirst();
        assertThat(entry.objectType()).isEqualTo(ObjectType.COMMENT);
        assertThat(entry.changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(entry.severity()).isEqualTo(Severity.INFO);
        assertThat(entry.objectName()).isEqualTo("users");
        assertThat(entry.description()).contains("'old comment'").contains("'new comment'");
    }

    @Test
    void columnCommentAddedProducesCommentEntry() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("id", "bigint", false, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of(
                        new ColumnDef("id", "bigint", false, null, "Primary key")))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        DiffEntry entry = diff.entries().getFirst();
        assertThat(entry.objectType()).isEqualTo(ObjectType.COMMENT);
        assertThat(entry.objectName()).isEqualTo("users.id");
        assertThat(entry.description()).contains("(no comment)").contains("'Primary key'");
    }

    @Test
    void identicalCommentsProduceNoEntries() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", "same", List.of(
                        new ColumnDef("id", "bigint", false, null, "same column comment")))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", "same", List.of(
                        new ColumnDef("id", "bigint", false, null, "same column comment")))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void commentRemovedOnTargetIsReported() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", "documented", List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", null, List.of())),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().objectType()).isEqualTo(ObjectType.COMMENT);
        assertThat(diff.entries().getFirst().description()).contains("(no comment)");
    }
}
