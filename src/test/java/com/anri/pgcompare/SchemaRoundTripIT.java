package com.anri.pgcompare;

import com.anri.pgcompare.ddl.DdlGenerator;
import com.anri.pgcompare.ddl.DdlStatement;
import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.diff.SeverityClassifier;
import com.anri.pgcompare.diff.SchemaDiffer;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.extractor.ConstraintExtractor;
import com.anri.pgcompare.extractor.IndexExtractor;
import com.anri.pgcompare.extractor.SchemaExtractor;
import com.anri.pgcompare.extractor.SequenceExtractor;
import com.anri.pgcompare.extractor.TableExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end round trip against a real PostgreSQL: two schemas are created, diffed,
 * the generated migration is applied to the source schema, and re-diffing must yield
 * zero differences. This is the regression net for DDL generation: any statement that
 * is silently dropped, mis-rendered or emitted in an invalid order leaves residual
 * differences and fails the last assertion.
 *
 * <p>Runs against a Testcontainers PostgreSQL by default; set {@code PGCOMPARE_IT_URL}
 * (with optional {@code PGCOMPARE_IT_USER} / {@code PGCOMPARE_IT_PASSWORD}) to use an
 * already running server instead — handy for a CI service container or for a Docker
 * socket Testcontainers cannot reach. Skipped when neither is available.
 */
class SchemaRoundTripIT {

    private static final String SOURCE_SCHEMA = "src";
    private static final String TARGET_SCHEMA = "tgt";

    private static PostgreSQLContainer<?> container;

    @BeforeAll
    static void prepareDatabase() {
        if (externalUrl() != null) {
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is unavailable and PGCOMPARE_IT_URL is not set: skipping the round-trip IT");
        container = new PostgreSQLContainer<>("postgres:17");
        container.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    private static String externalUrl() {
        return System.getenv("PGCOMPARE_IT_URL");
    }

    private final SchemaExtractor extractor = new SchemaExtractor(
            new TableExtractor(), new ConstraintExtractor(), new IndexExtractor(),
            new SequenceExtractor());
    private final SchemaDiffer differ = new SchemaDiffer(new SeverityClassifier());
    private final DdlGenerator generator = new DdlGenerator();

    private static final List<String> SOURCE_DDL = List.of(
            "CREATE SCHEMA " + SOURCE_SCHEMA,
            "CREATE SCHEMA " + TARGET_SCHEMA,
            """
            CREATE TABLE src.users (
                id bigint NOT NULL,
                email character varying(100),
                phone character varying(20) NOT NULL,
                status text,
                created_at timestamp without time zone DEFAULT now(),
                CONSTRAINT users_pkey PRIMARY KEY (id),
                CONSTRAINT users_email_not_empty CHECK ((length(email) > 0)),
                CONSTRAINT users_email_key UNIQUE (email),
                CONSTRAINT users_status_check CHECK ((length(status) > 0))
            )
            """,
            "CREATE INDEX users_status_idx ON src.users (status)",
            "COMMENT ON TABLE src.users IS 'legacy users'",
            "CREATE TABLE src.legacy_log (id bigint, note text)",
            "CREATE INDEX legacy_log_note_idx ON src.legacy_log (note)",
            "CREATE TABLE src.docs (id bigint NOT NULL, owner_id bigint, total integer,"
                    + " CONSTRAINT docs_pkey PRIMARY KEY (id),"
                    + " CONSTRAINT docs_total_check CHECK ((total >= 0)),"
                    + " CONSTRAINT docs_owner_fk FOREIGN KEY (owner_id) REFERENCES src.users (id))",
            "CREATE TABLE src.tags (name character varying(50) NOT NULL, slug character varying(50),"
                    + " CONSTRAINT tags_pkey PRIMARY KEY (name))",
            "CREATE SEQUENCE src.doc_seq START WITH 1 INCREMENT BY 1",
            "CREATE SEQUENCE src.orphan_seq START WITH 1 INCREMENT BY 1"
    );

    private static final List<String> TARGET_DDL = List.of(
            // column type widened, phone re-typed and relaxed, status dropped together with
            // its index and check constraint, new column, new check, unique constraint widened,
            // table comment replaced, column comment added
            """
            CREATE TABLE tgt.users (
                id bigint NOT NULL,
                email character varying(255),
                phone text,
                created_at timestamp without time zone DEFAULT now(),
                nickname character varying(30) NOT NULL DEFAULT 'anon'::character varying,
                CONSTRAINT users_pkey PRIMARY KEY (id),
                CONSTRAINT users_email_not_empty CHECK ((length(email) > 0)),
                CONSTRAINT users_email_domain CHECK ((position('@' in email) > 0)),
                CONSTRAINT users_email_key UNIQUE (email, phone) DEFERRABLE INITIALLY DEFERRED
            )
            """,
            "COMMENT ON TABLE tgt.users IS 'application users'",
            "COMMENT ON COLUMN tgt.users.id IS 'stable user identifier'",
            "CREATE INDEX users_created_at_idx ON tgt.users (created_at)",
            // docs gains an identity column, a stored generated column, an index,
            // and its FK gains ON DELETE CASCADE
            "CREATE TABLE tgt.docs (id bigint GENERATED BY DEFAULT AS IDENTITY,"
                    + " owner_id bigint, total integer,"
                    + " doubled integer GENERATED ALWAYS AS (total * 2) STORED,"
                    + " CONSTRAINT docs_pkey PRIMARY KEY (id),"
                    + " CONSTRAINT docs_total_check CHECK ((total >= 0)),"
                    + " CONSTRAINT docs_owner_fk FOREIGN KEY (owner_id) REFERENCES tgt.users (id)"
                    + " ON DELETE CASCADE)",
            "CREATE INDEX docs_owner_idx ON tgt.docs (owner_id)",
            // primary key of an unreferenced table changes shape: drop + recreate; new exclusion added
            "CREATE TABLE tgt.tags (name character varying(50) NOT NULL, slug character varying(50),"
                    + " CONSTRAINT tags_pkey PRIMARY KEY (name, slug),"
                    + " CONSTRAINT tags_slug_excl EXCLUDE USING btree (slug WITH =))",
            "CREATE SEQUENCE tgt.doc_seq START WITH 100 INCREMENT BY 5",
            "CREATE SEQUENCE tgt.report_seq START WITH 1 INCREMENT BY 1",
            // brand new table: identity PK, a nextval default pointing at a sequence created
            // in the same migration, a NOT VALID FK and an index
            """
            CREATE TABLE tgt.reports (
                id bigint GENERATED ALWAYS AS IDENTITY,
                doc_seq_id bigint DEFAULT nextval('tgt.report_seq'::regclass),
                title text,
                doc_id bigint,
                CONSTRAINT reports_pkey PRIMARY KEY (id),
                CONSTRAINT reports_doc_fk FOREIGN KEY (doc_id) REFERENCES tgt.docs (id)
                    ON DELETE SET NULL NOT VALID
            )
            """,
            "COMMENT ON TABLE tgt.reports IS 'generated reports'",
            "CREATE INDEX reports_title_idx ON tgt.reports (title)"
    );

    @Test
    void applyingGeneratedMigrationMakesSchemasIdentical() throws Exception {
        createSchemas();

        SchemaSnapshot source = extract(SOURCE_SCHEMA);
        SchemaSnapshot target = extract(TARGET_SCHEMA);
        SchemaDiff before = differ.diff(source, target);

        // the fixture must keep exercising the paths that were previously generated wrong
        assertThat(before.entries())
                .filteredOn(e -> e.objectType() == ObjectType.CONSTRAINT
                        && e.changeType() == ChangeType.MODIFIED)
                .extracting(DiffEntry::objectName)
                .containsExactlyInAnyOrder("docs.docs_owner_fk", "tags.tags_pkey", "users.users_email_key");

        List<DdlStatement> migration = generator.generate(before);
        assertThat(migration).isNotEmpty();
        executeAll(migration.stream().map(DdlStatement::sql).toList());

        SchemaSnapshot migrated = extract(SOURCE_SCHEMA);
        SchemaDiff residual = differ.diff(migrated, target);
        assertThat(residual.entries())
                .as("residual differences after applying the generated migration")
                .isEmpty();
    }

    @Test
    void generatedMigrationIsIdempotent() throws Exception {
        createSchemas();
        applyMigrationOnce();

        SchemaSnapshot migrated = extract(SOURCE_SCHEMA);
        SchemaDiff residual = differ.diff(migrated, extract(TARGET_SCHEMA));

        assertThat(residual.isEmpty()).isTrue();
        assertThat(generator.generate(residual)).isEmpty();
    }

    @Test
    void extractionFailsForMissingSchema() throws Exception {
        createSchemas();

        try (Connection connection = openConnection()) {
            assertThatThrownBy(() -> extractor.extract(connection, "no_such_schema"))
                    .hasMessageContaining("no_such_schema");
        }
    }

    private void applyMigrationOnce() throws SQLException {
        SchemaDiff before = differ.diff(extract(SOURCE_SCHEMA), extract(TARGET_SCHEMA));
        executeAll(generator.generate(before).stream().map(DdlStatement::sql).toList());
    }

    private void createSchemas() throws SQLException {
        executeAll(List.of(
                "DROP SCHEMA IF EXISTS " + SOURCE_SCHEMA + " CASCADE",
                "DROP SCHEMA IF EXISTS " + TARGET_SCHEMA + " CASCADE"));
        executeAll(List.of(SOURCE_DDL, TARGET_DDL).stream().flatMap(List::stream).toList());
    }

    private void executeAll(List<String> statements) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private SchemaSnapshot extract(String schema) throws SQLException {
        try (Connection connection = openConnection()) {
            return extractor.extract(connection, schema);
        }
    }

    private Connection openConnection() throws SQLException {
        String url = externalUrl();
        if (url != null) {
            return DriverManager.getConnection(url,
                    envOrDefault("PGCOMPARE_IT_USER", "postgres"),
                    envOrDefault("PGCOMPARE_IT_PASSWORD", "postgres"));
        }
        return DriverManager.getConnection(container.getJdbcUrl(),
                container.getUsername(), container.getPassword());
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
