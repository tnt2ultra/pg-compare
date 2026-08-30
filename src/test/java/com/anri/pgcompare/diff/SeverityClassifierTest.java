package com.anri.pgcompare.diff;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты классификатора severity: правила заданы по типу объекта и типу изменения,
 * поэтому проверяются все комбинации, а комментарии вынесены отдельно — они единственные
 * не ломают приложение.
 */
class SeverityClassifierTest {

    private final SeverityClassifier classifier = new SeverityClassifier();

    @Test
    void everyRemovalBreaksSomethingApplicationsRelyOn() {
        for (ObjectType type : ObjectType.values()) {
            if (type == ObjectType.COMMENT) {
                continue;
            }
            assertThat(classifier.classify(type, ChangeType.REMOVED))
                    .as("удаление %s", type)
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
                    .as("добавление %s", type)
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
                    .as("комментарий: %s", changeType)
                    .isEqualTo(Severity.INFO);
        }
    }
}
