package com.anri.pgcompare.diff;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.ConstraintType;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDifferTest {

    private final SchemaDiffer differ = new SchemaDiffer(new SeverityClassifier());

    private SchemaSnapshot snapshot(List<TableDef> tables,
                                    List<ConstraintDef> constraints,
                                    List<IndexDef> indexes,
                                    List<SequenceDef> sequences) {
        return new SchemaSnapshot("public", tables, constraints, indexes, sequences);
    }

    @Test
    void identicalSnapshotsProduceNoEntries() {
        SchemaSnapshot s = snapshot(
                List.of(new TableDef("users", List.of(
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
                List.of(new TableDef("orders", List.of())),
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
                List.of(new TableDef("orders", List.of())),
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
                List.of(new TableDef("users", List.of(
                        new ColumnDef("email", "character varying(100)", true, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of(
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
                List.of(new TableDef("users", List.of(
                        new ColumnDef("phone", "text", false, null, null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of(
                        new ColumnDef("phone", "text", true, null, null)))),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.entries().getFirst().description()).isEqualTo("became nullable");
    }

    @Test
    void addedColumnIsNonBreaking() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of(
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
                List.of(new TableDef("users", List.of(
                        new ColumnDef("created_at", "timestamp without time zone", true, "now()", null)))),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of(
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
                List.of(new TableDef("orders", List.of())),
                List.of(new ConstraintDef("orders_user_fk", ConstraintType.FOREIGN_KEY, "orders",
                        List.of("user_id"), "users", List.of("id"),
                        "FOREIGN KEY (user_id) REFERENCES users(id)")),
                List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("orders", List.of())),
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
    void indexChangesAreDetected() {
        SchemaSnapshot source = snapshot(
                List.of(new TableDef("users", List.of())),
                List.of(),
                List.of(new IndexDef("idx_email", "users", false,
                        "CREATE INDEX idx_email ON users USING btree (email)")),
                List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of())),
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
                List.of(new TableDef("Users", List.of())),
                List.of(), List.of(), List.of());
        SchemaSnapshot target = snapshot(
                List.of(new TableDef("users", List.of())),
                List.of(), List.of(), List.of());

        SchemaDiff diff = differ.diff(source, target);

        assertThat(diff.isEmpty()).isTrue();
    }
}
