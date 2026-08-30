package com.anri.pgcompare.cli;

import com.anri.pgcompare.connection.ConnectionProvider;
import com.anri.pgcompare.diff.SchemaDiffer;
import com.anri.pgcompare.exception.CompareException;
import com.anri.pgcompare.extractor.SchemaExtractor;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.report.ConsoleSummaryPrinter;
import com.anri.pgcompare.report.JsonReportWriter;
import com.anri.pgcompare.report.SqlScriptWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Callable;

/**
 * picocli-команда сравнения: читает обе схемы и раздаёт результат приёмщикам отчётов.
 * Порядок фиксированный — вычитать источник, вычитать цель, построить дифф, напечатать
 * сводку, записать JSON и (если запрошено) миграционный SQL. Код возврата становится
 * кодом процесса: {@code 0} — схемы совпали, {@code 1} — есть различия, {@code 2} — ошибка.
 */
@Command(name = "pg-compare",
        mixinStandardHelpOptions = true,
        description = "Сравнивает схемы PostgreSQL и выдаёт JSON-отчёт с различиями и миграционный DDL-скрипт.")
@Component
@RequiredArgsConstructor
public class CompareCommand implements Callable<Integer> {

    /** JDBC URL базы-источника — состояние «как есть», к нему и применяется миграция. */
    @Option(names = {"--source-url"}, required = true, description = "JDBC URL базы-источника")
    private String sourceUrl;

    /** Пользователь базы-источника. */
    @Option(names = {"--source-user"}, required = true, description = "Пользователь базы-источника")
    private String sourceUser;

    /** Пароль базы-источника: берётся из окружения, иначе запрашивается интерактивно. */
    @Option(names = {"--source-password"}, interactive = true, arity = "0..1",
            defaultValue = "${env:PGCOMPARE_SOURCE_PASSWORD}",
            description = "Пароль базы-источника (запрос в терминале или переменная окружения PGCOMPARE_SOURCE_PASSWORD)")
    private String sourcePassword;

    /** JDBC URL целевой базы — желаемое состояние схемы. */
    @Option(names = {"--target-url"}, required = true, description = "JDBC URL целевой базы")
    private String targetUrl;

    /** Пользователь целевой базы. */
    @Option(names = {"--target-user"}, required = true, description = "Пользователь целевой базы")
    private String targetUser;

    /** Пароль целевой базы: берётся из окружения, иначе запрашивается интерактивно. */
    @Option(names = {"--target-password"}, interactive = true, arity = "0..1",
            defaultValue = "${env:PGCOMPARE_TARGET_PASSWORD}",
            description = "Пароль целевой базы (запрос в терминале или переменная окружения PGCOMPARE_TARGET_PASSWORD)")
    private String targetPassword;

    /** Имя схемы по умолчанию для обеих сторон, если не заданы раздельные опции. */
    @Option(names = {"--schema"}, defaultValue = "public",
            description = "Имя схемы на обеих сторонах (по умолчанию: public)")
    private String schema;

    /** Переопределение схемы только для стороны источника. */
    @Option(names = {"--source-schema"}, description = "Схема источника; переопределяет --schema для этой стороны")
    private String sourceSchema;

    /** Переопределение схемы только для целевой стороны. */
    @Option(names = {"--target-schema"}, description = "Целевая схема; переопределяет --schema для этой стороны")
    private String targetSchema;

    /** Файл JSON-отчёта; родительские каталоги создаются при записи. */
    @Option(names = {"--out"}, defaultValue = "report.json", description = "Файл JSON-отчёта (по умолчанию: report.json)")
    private Path outputFile;

    /** Файл миграционного DDL; {@code null} — скрипт не генерируется. */
    @Option(names = {"--ddl"}, description = "Необязательный файл миграционного DDL-скрипта")
    private Path ddlFile;

    /** Оборачивать ли сгенерированный скрипт в BEGIN/COMMIT (снимается через {@code --no-transaction}). */
    @Option(names = {"--transaction"}, negatable = true, defaultValue = "true",
            description = "Обернуть сгенерированный DDL-скрипт в BEGIN/COMMIT (по умолчанию: true; "
                    + "--no-transaction — для операторов, которые нельзя выполнить внутри транзакции)")
    private boolean transactional;

    /** Открытие одноразовых JDBC-подключений для обеих баз. */
    private final ConnectionProvider connectionProvider;

    /** Чтение {@code pg_catalog} в снимок схемы. */
    private final SchemaExtractor schemaExtractor;

    /** Сравнение двух снимков в памяти. */
    private final SchemaDiffer schemaDiffer;

    /** Сводка различий в stdout. */
    private final ConsoleSummaryPrinter consoleSummaryPrinter;

    /** Машиночитаемый JSON-отчёт. */
    private final JsonReportWriter jsonReportWriter;

    /** Миграционный SQL-скрипт. */
    private final SqlScriptWriter sqlScriptWriter;

    /**
     * Основной сценарий команды.
     *
     * @return код возврата процесса: 0 — различий нет, 1 — различия есть, 2 — ошибка
     */
    @Override
    public Integer call() {
        String effectiveSourceSchema = sourceSchema != null ? sourceSchema : schema;
        String effectiveTargetSchema = targetSchema != null ? targetSchema : schema;
        try {
            SchemaSnapshot source = extract(sourceUrl, sourceUser, sourcePassword, effectiveSourceSchema);
            SchemaSnapshot target = extract(targetUrl, targetUser, targetPassword, effectiveTargetSchema);

            var diff = schemaDiffer.diff(source, target);
            consoleSummaryPrinter.print(diff);
            jsonReportWriter.write(diff, outputFile);
            if (ddlFile != null) {
                sqlScriptWriter.write(diff, ddlFile, transactional);
            }
            return diff.isEmpty() ? 0 : 1;
        } catch (CompareException e) {
            System.err.println("ОШИБКА: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("НЕОЖИДАННАЯ ОШИБКА: " + e);
            return 2;
        }
    }

    /**
     * Вычитывает одну сторону сравнения, гарантируя, что подключение закрыто даже при ошибке.
     *
     * @param url       JDBC URL базы
     * @param user      пользователь
     * @param password  пароль (может быть {@code null})
     * @param schemaName имя схемы на этой стороне
     * @return снимок схемы
     * @throws CompareException если чтение не удалось; {@link CompareException} пробрасывается как есть,
     *                          чтобы не терять исходное сообщение об ошибке подключения
     */
    private SchemaSnapshot extract(String url, String user, String password, String schemaName) {
        try (Connection connection = connectionProvider.open(url, user, password)) {
            return schemaExtractor.extract(connection, schemaName);
        } catch (CompareException e) {
            throw e;
        } catch (Exception e) {
            throw new CompareException("Не удалось вычитать схему из %s: %s".formatted(url, e.getMessage()), e);
        }
    }
}
