package com.anri.pgcompare.diff;

/**
 * Classifies how dangerous a change is for applications using the schema.
 * Comments are documentation-only and never break a running application; every removal takes
 * away something applications rely on — data, a uniqueness guarantee that {@code ON CONFLICT}
 * needs, a referential action, or a query plan — so removals are breaking.
 */
public class SeverityClassifier {

    public Severity classify(ObjectType objectType, ChangeType changeType) {
        if (objectType == ObjectType.COMMENT) {
            return Severity.INFO;
        }
        return switch (changeType) {
            case REMOVED -> Severity.BREAKING;
            case ADDED -> Severity.NON_BREAKING;
            // a changed column rewrites data applications already read; a redefined constraint
            // or index keeps the object in place
            case MODIFIED -> objectType == ObjectType.COLUMN
                    ? Severity.BREAKING
                    : Severity.NON_BREAKING;
        };
    }
}
