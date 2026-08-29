package com.anri.pgcompare.report;

import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.exception.CompareException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonReportWriter {

    private final JsonMapper mapper = JsonMapper.builder()            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public void write(SchemaDiff diff, Path outputFile) {
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, mapper.writeValueAsString(diff), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompareException("Cannot write JSON report to %s: %s".formatted(outputFile, e.getMessage()), e);
        }
    }
}
