package com.anri.pgcompare.diff;

import org.springframework.stereotype.Component;

/**
 * Classifies how dangerous a change is for applications using the schema:
 * DROP TABLE/DROP COLUMN breaks code reading that data, ADDs are safe.
 */
@Component
public class SeverityClassifier {

    public Severity classify(ObjectType objectType, ChangeType changeType) {
        return switch (changeType) {
            case REMOVED -> switch (objectType) {
                case TABLE, COLUMN, SEQUENCE -> Severity.BREAKING;
                case CONSTRAINT, INDEX, COMMENT -> Severity.NON_BREAKING;
            };
            case ADDED -> switch (objectType) {
                case TABLE, COLUMN, SEQUENCE, INDEX, CONSTRAINT, COMMENT -> Severity.NON_BREAKING;
            };
            case MODIFIED -> switch (objectType) {
                case COLUMN -> Severity.BREAKING;
                case TABLE, CONSTRAINT, INDEX, SEQUENCE, COMMENT -> Severity.NON_BREAKING;
            };
        };
    }
}
