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
 * Сквозной round-trip на реальном PostgreSQL: создаются две схемы, сравниваются,
 * сгенерированная миграция применяется к схеме-источнику, и повторное сравнение обязано
 * дать ноль различий. Это страховка для генерации DDL: оператор, молча потерянный,
 * искажённый или вставший в неверном порядке, оставляет остаточные различия — и последний
 * assertion падает.
 *
 * <p>По умолчанию прогоняется на Testcontainers-PostgreSQL; чтобы использовать уже
 * запущенный сервер, задайте {@code PGCOMPARE_IT_URL} (опционально
 * {@code PGCOMPARE_IT_USER} / {@code PGCOMPARE_IT_PASSWORD}) — удобно для CI-контейнера
 * сервиса или для Docker-сокета, до которого testcontainers не дотягивается. Если недоступно
 * и то и другое, тесты пропускаются.
 */
class SchemaRoundTripIT {

    /** Имя схемы-источника в тестовой базе. */
    private static final String SOURCE_SCHEMA = "src";

    /** Имя целевой схемы в тестовой базе. */
    private static final String TARGET_SCHEMA = "tgt";

    /** Запускаемый Postgres; {@code null}, когда используется внешняя база. */
    private static PostgreSQLContainer<?> container;

    /**
     * Поднимает контейнер, если не задана внешняя база.
     */
    @BeforeAll
    static void prepareDatabase() {
        if (externalUrl() != null) {
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker недоступен и PGCOMPARE_IT_URL не задан: round-trip IT пропускается");
        container = new PostgreSQLContainer<>("postgres:17");
        container.start();
    }

    /**
     * Останавливает контейнер, если он запускался.
     */
    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    /**
     * @return JDBC URL внешней тестовой базы из окружения либо {@code null}
     */
    private static String externalUrl() {
        return System.getenv("PGCOMPARE_IT_URL");
    }

    /** Настоящий экстрактор: IT проверяет чтение каталога, а не его имитацию. */
    private final SchemaExtractor extractor = new SchemaExtractor(
            new TableExtractor(), new ConstraintExtractor(), new IndexExtractor(),
            new SequenceExtractor());

    /** Сравнение снимков, собранных из реальной базы. */
    private final SchemaDiffer differ = new SchemaDiffer(new SeverityClassifier());

    /** Генератор миграции, применяемый затем к схеме-источнику. */
    private final DdlGenerator generator = new DdlGenerator();

    /**
     * Состояние «как есть»: устаревшая таблица, констрейнты и индексы, которые миграция должна
     * снять или пересобрать, две схемы-родители и несколько sequence.
     */
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

    /**
     * Желаемое состояние: набрано так, чтобы задеть все ветки генератора — изменение типа,
     * снятие колонки с её индексами и констрейнтами, новые объекты, пересборка констрейнтов,
     * identity и generated-колонки, комментарии и sequence.
     */
    private static final List<String> TARGET_DDL = List.of(
            // тип колонки расширен, phone перекрашен и ослаблен, status снята вместе со своим
            // индексом и CHECK-констрейнтом, новая колонка, новый CHECK, состав UNIQUE расширен,
            // комментарий таблицы заменён, у колонки появился комментарий
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
            // у docs появляются identity-колонка, stored generated-колонка и индекс,
            // а её FK получает ON DELETE CASCADE
            "CREATE TABLE tgt.docs (id bigint GENERATED BY DEFAULT AS IDENTITY,"
                    + " owner_id bigint, total integer,"
                    + " doubled integer GENERATED ALWAYS AS (total * 2) STORED,"
                    + " CONSTRAINT docs_pkey PRIMARY KEY (id),"
                    + " CONSTRAINT docs_total_check CHECK ((total >= 0)),"
                    + " CONSTRAINT docs_owner_fk FOREIGN KEY (owner_id) REFERENCES tgt.users (id)"
                    + " ON DELETE CASCADE)",
            "CREATE INDEX docs_owner_idx ON tgt.docs (owner_id)",
            // меняется состав PK никем не адресуемой таблицы: снятие + пересоздание; добавлен EXCLUDE
            "CREATE TABLE tgt.tags (name character varying(50) NOT NULL, slug character varying(50),"
                    + " CONSTRAINT tags_pkey PRIMARY KEY (name, slug),"
                    + " CONSTRAINT tags_slug_excl EXCLUDE USING btree (slug WITH =))",
            "CREATE SEQUENCE tgt.doc_seq START WITH 100 INCREMENT BY 5",
            "CREATE SEQUENCE tgt.report_seq START WITH 1 INCREMENT BY 1",
            // совершенно новая таблица: identity PK, дефолт nextval на sequence, созданный в этой же
            // миграции, NOT VALID FK и индекс
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

    /**
     * Главный сценарий: миграция, применённая к источнику, делает схемы идентичными.
     *
     * @throws Exception если база недоступна или оператор миграции отвалился
     */
    @Test
    void applyingGeneratedMigrationMakesSchemasIdentical() throws Exception {
        createSchemas();

        SchemaSnapshot source = extract(SOURCE_SCHEMA);
        SchemaSnapshot target = extract(TARGET_SCHEMA);
        SchemaDiff before = differ.diff(source, target);

        // фикстура обязана и дальше нагружать ветки, где генерация раньше ошиблась:
        // изменённые констрейнты пересоздаются, а не правятся на месте
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
                .as("остаточные различия после применения сгенерированной миграции")
                .isEmpty();
    }

    /**
     * Повторный прогон не должен найти ничего: иначе миграция неприменима дважды.
     *
     * @throws Exception если база недоступна или оператор миграции отвалился
     */
    @Test
    void generatedMigrationIsIdempotent() throws Exception {
        createSchemas();
        applyMigrationOnce();

        SchemaSnapshot migrated = extract(SOURCE_SCHEMA);
        SchemaDiff residual = differ.diff(migrated, extract(TARGET_SCHEMA));

        assertThat(residual.isEmpty()).isTrue();
        assertThat(generator.generate(residual)).isEmpty();
    }

    /**
     * Несуществующая схема — это ошибка, а не пустой снимок: иначе опечатка в имени схемы дала бы
     * ложное «схемы идентичны».
     *
     * @throws Exception если база недоступна
     */
    @Test
    void extractionFailsForMissingSchema() throws Exception {
        createSchemas();

        try (Connection connection = openConnection()) {
            assertThatThrownBy(() -> extractor.extract(connection, "no_such_schema"))
                    .hasMessageContaining("no_such_schema");
        }
    }

    /**
     * Строит дифф текущих схем и применяет полученную миграцию к источнику.
     *
     * @throws SQLException если база недоступна или оператор миграции отвалился
     */
    private void applyMigrationOnce() throws SQLException {
        SchemaDiff before = differ.diff(extract(SOURCE_SCHEMA), extract(TARGET_SCHEMA));
        executeAll(generator.generate(before).stream().map(DdlStatement::sql).toList());
    }

    /**
     * Пересоздаёт обе тестовые схемы с нуля: каждый тест стартует с известной фикстуры,
     * поэтому прогон в произвольном порядке и повторный прогон дают один и тот же результат.
     *
     * @throws SQLException если база недоступна или фикстура не применилась
     */
    private void createSchemas() throws SQLException {
        executeAll(List.of(
                "DROP SCHEMA IF EXISTS " + SOURCE_SCHEMA + " CASCADE",
                "DROP SCHEMA IF EXISTS " + TARGET_SCHEMA + " CASCADE"));
        executeAll(List.of(SOURCE_DDL, TARGET_DDL).stream().flatMap(List::stream).toList());
    }

    /**
     * Выполняет операторы одним соединением и по порядку.
     *
     * @param statements SQL-операторы без завершающих {@code ;}
     * @throws SQLException если первый же оператор отвалился — так потеря операторов не маскируется
     */
    private void executeAll(List<String> statements) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * @param schema имя схемы
     * @return снимок схемы, прочитанный из каталога
     * @throws SQLException если база недоступна
     */
    private SchemaSnapshot extract(String schema) throws SQLException {
        try (Connection connection = openConnection()) {
            return extractor.extract(connection, schema);
        }
    }

    /**
     * @return подключение к внешней базе из окружения либо к запущенному контейнеру
     * @throws SQLException если подключение не установилось
     */
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

    /**
     * @param name имя переменной окружения
     * @param fallback значение по умолчанию
     * @return содержимое переменной или {@code fallback}, если она пуста
     */
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
