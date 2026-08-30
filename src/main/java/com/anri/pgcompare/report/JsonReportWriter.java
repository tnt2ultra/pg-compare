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

/**
 * Пишет различия в JSON-отчёт — машиночитаемую версию диффа для CI и дифф-инструментов.
 * Сериализуется {@link SchemaDiff} как есть, поэтому в отчёт попадают в том числе
 * определения объектов до/после; текст описаний остаётся на английском, так как отчёт
 * архивируется и сравнивается между прогонами.
 */
@Component
public class JsonReportWriter {

    /** Маппер Jackson 3; отступы включены, чтобы отчёт можно было читать глазами и бить на diff. */
    private final JsonMapper mapper = JsonMapper.builder()            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Перезаписывает файл отчёта, при необходимости создавая родительские каталоги.
     *
     * @param diff       результат сравнения
     * @param outputFile путь JSON-отчёта (аргумент {@code --out})
     * @throws CompareException если файл не удалось записать
     */
    public void write(SchemaDiff diff, Path outputFile) {
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, mapper.writeValueAsString(diff), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompareException("Не удалось записать JSON-отчёт в %s: %s"
                    .formatted(outputFile, e.getMessage()), e);
        }
    }
}
