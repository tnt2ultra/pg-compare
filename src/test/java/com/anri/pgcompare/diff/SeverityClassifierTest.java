package com.anri.pgcompare.diff;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeverityClassifierTest {

    private final SeverityClassifier classifier = new SeverityClassifier();

    @Test
    void everyRemovalBreaksSomethingApplicationsRelyOn() {
        for (ObjectType type : ObjectType.values()) {
            if (type == ObjectType.COMMENT) {
                continue;
            }
            assertThat(classifier.classify(type, ChangeType.REMOVED))
                    .as("removing a %s", type)
                    .isEqualTo(Severity.BREAKING);
        }
    }

    @Test
    void additionsAreNonBreaking() {
        for (ObjectType type : ObjectType.values()) {
            if (type == ObjectType.COMMENT) {
                continue;
            }
            assertThat(classifier.classify(type, ChangeType.ADDED))
                    .as("adding a %s", type)
                    .isEqualTo(Severity.NON_BREAKING);
        }
    }

    @Test
    void onlyColumnModificationIsBreaking() {
        assertThat(classifier.classify(ObjectType.COLUMN, ChangeType.MODIFIED)).isEqualTo(Severity.BREAKING);
        assertThat(classifier.classify(ObjectType.CONSTRAINT, ChangeType.MODIFIED))
                .isEqualTo(Severity.NON_BREAKING);
        assertThat(classifier.classify(ObjectType.INDEX, ChangeType.MODIFIED))
                .isEqualTo(Severity.NON_BREAKING);
        assertThat(classifier.classify(ObjectType.SEQUENCE, ChangeType.MODIFIED))
                .isEqualTo(Severity.NON_BREAKING);
    }

    @Test
    void commentChangesAreInformational() {
        for (ChangeType changeType : ChangeType.values()) {
            assertThat(classifier.classify(ObjectType.COMMENT, changeType))
                    .as("comment %s", changeType)
                    .isEqualTo(Severity.INFO);
        }
    }
}
