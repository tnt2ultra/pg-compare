package com.anri.pgcompare.extractor;

/**
 * Strips the compared schema's own prefix from server-rendered definitions
 * (index defs, constraint defs, column defaults) so that comparing schemas with
 * different names does not report phantom differences. Qualification to other
 * schemas is preserved.
 */
final class DefinitionNormalizer {

    private DefinitionNormalizer() {
    }

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

    /** Strips the schema prefix inside 'schema.name'::regclass literals (e.g. nextval defaults). */
    static String normalizeDefault(String defaultValue, String schema) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return defaultValue;
        }
        return defaultValue
                .replaceAll("(?i)'" + java.util.regex.Pattern.quote(schema) + "\\.", "'")
                .replaceAll("(?i)'" + java.util.regex.Pattern.quote(schema) + "\"\\.", "'");
    }
}
