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

@Command(name = "pg-compare",
        mixinStandardHelpOptions = true,
        description = "Compares PostgreSQL schemas and emits a JSON diff report and a migration DDL script.")
@Component
@RequiredArgsConstructor
public class CompareCommand implements Callable<Integer> {

    @Option(names = {"--source-url"}, required = true, description = "JDBC URL of the source DB")
    private String sourceUrl;

    @Option(names = {"--source-user"}, required = true, description = "Source DB user")
    private String sourceUser;

    @Option(names = {"--source-password"}, interactive = true, arity = "0..1",
            defaultValue = "${env:PGCOMPARE_SOURCE_PASSWORD}",
            description = "Source DB password (prompt, env PGCOMPARE_SOURCE_PASSWORD)")
    private String sourcePassword;

    @Option(names = {"--target-url"}, required = true, description = "JDBC URL of the target DB")
    private String targetUrl;

    @Option(names = {"--target-user"}, required = true, description = "Target DB user")
    private String targetUser;

    @Option(names = {"--target-password"}, interactive = true, arity = "0..1",
            defaultValue = "${env:PGCOMPARE_TARGET_PASSWORD}",
            description = "Target DB password (prompt, env PGCOMPARE_TARGET_PASSWORD)")
    private String targetPassword;

    @Option(names = {"--schema"}, defaultValue = "public",
            description = "Schema name on both sides (default: public)")
    private String schema;

    @Option(names = {"--source-schema"}, description = "Source schema, overrides --schema for the source side")
    private String sourceSchema;

    @Option(names = {"--target-schema"}, description = "Target schema, overrides --schema for the target side")
    private String targetSchema;

    @Option(names = {"--out"}, defaultValue = "report.json", description = "JSON report file (default: report.json)")
    private Path outputFile;

    @Option(names = {"--ddl"}, description = "Optional migration DDL script to generate")
    private Path ddlFile;

    private final ConnectionProvider connectionProvider;
    private final SchemaExtractor schemaExtractor;
    private final SchemaDiffer schemaDiffer;
    private final ConsoleSummaryPrinter consoleSummaryPrinter;
    private final JsonReportWriter jsonReportWriter;
    private final SqlScriptWriter sqlScriptWriter;

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
                sqlScriptWriter.write(diff, ddlFile);
            }
            return diff.isEmpty() ? 0 : 1;
        } catch (CompareException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("UNEXPECTED ERROR: " + e);
            return 2;
        }
    }

    private SchemaSnapshot extract(String url, String user, String password, String schemaName) {
        try (Connection connection = connectionProvider.open(url, user, password)) {
            return schemaExtractor.extract(connection, schemaName);
        } catch (CompareException e) {
            throw e;
        } catch (Exception e) {
            throw new CompareException("Schema extraction failed for %s: %s".formatted(url, e.getMessage()), e);
        }
    }
}
