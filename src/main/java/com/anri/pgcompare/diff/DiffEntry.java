package com.anri.pgcompare.diff;

/**
 * @param objectName fully qualified object name, e.g. "users.email" for a column
 * @param before     value on the source side (null for ADDED)
 * @param after      value on the target side (null for REMOVED); before/after are Maps so
 *                   Jackson can render them without polymorphic typing
 */
public record DiffEntry(
        ObjectType objectType,
        String objectName,
        ChangeType changeType,
        Severity severity,
        String description,
        Object before,
        Object after
) {
}
