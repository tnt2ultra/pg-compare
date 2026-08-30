package com.anri.pgcompare.extractor;

/**
 * Срезает префикс собственной сравниваемой схемы из определений, отрендеренных сервером
 * (определения индексов, констрейнтов, дефолты колонок), чтобы сравнение схем с разными
 * именами не выдавало фантомных различий. Квалификация чужими схемами сохраняется.
 */
final class DefinitionNormalizer {

    /** Утилитный класс: экземпляр не создаётся. */
    private DefinitionNormalizer() {
    }

    /**
     * Убирает из определения префикс сравниваемой схемы — и в открытом виде
     * ({@code schema.name}), и в кавычках ({@code "schema".name}); регистр имени схемы при
     * этом не учитывается.
     *
     * @param definition определение, отрендеренное сервером; может быть {@code null}
     * @param schema     имя сравниваемой схемы, чей префикс срезается
     * @return то же определение без собственного префикса схемы; {@code null} и пустая строка
     *         возвращаются без изменений
     */
    static String normalize(String definition, String schema) {
        if (definition == null || definition.isBlank()) {
            return definition;
        }
        String unquoted = "(?i)" + java.util.regex.Pattern.quote(schema) + "\\.";
        String quoted = "(?i)\"" + java.util.regex.Pattern.quote(schema) + "\"\\.";
        return definition
                .replaceAll(unquoted, "")
                .replaceAll(quoted, "");
    }

    /**
     * Срезает префикс схемы внутри литералов {@code 'schema.name'::regclass} (например, в
     * дефолтах {@code nextval}), где имя схемы находится внутри одинарных кавычек — обычный
     * {@link #normalize(String, String)} там не срабатывает.
     *
     * @param defaultValue выражение дефолта, отрендеренное сервером; может быть {@code null}
     * @param schema       имя сравниваемой схемы, чей префикс срезается
     * @return то же выражение без префикса собственной схемы; {@code null} и пустая строка
     *         возвращаются без изменений
     */
    static String normalizeDefault(String defaultValue, String schema) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return defaultValue;
        }
        return defaultValue
                .replaceAll("(?i)'" + java.util.regex.Pattern.quote(schema) + "\\.", "'")
                .replaceAll("(?i)'" + java.util.regex.Pattern.quote(schema) + "\"\\.", "'");
    }
}
