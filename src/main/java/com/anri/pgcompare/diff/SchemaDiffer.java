package com.anri.pgcompare.diff;

import com.anri.pgcompare.model.ColumnDef;
import com.anri.pgcompare.model.ColumnGeneration;
import com.anri.pgcompare.model.ConstraintDef;
import com.anri.pgcompare.model.IdentityKind;
import com.anri.pgcompare.model.IndexDef;
import com.anri.pgcompare.model.SchemaSnapshot;
import com.anri.pgcompare.model.SequenceDef;
import com.anri.pgcompare.model.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Сравнивает два снимка схемы в памяти. Чистая логика: без I/O, полностью покрывается
 * unit-тестами. Объекты сопоставляются по нижнерегистровому имени — PostgreSQL хранит
 * неэкранированные идентификаторы в нижнем регистре, так что нормализация только
 * приводит к виду, в котором их уже привёл сервер.
 *
 * <p>Направление фиксированное: источник — мигрируемая сторона, цель — желаемое состояние,
 * поэтому {@code ADDED} означает «есть только в цели» (его надо создать), а {@code REMOVED} —
 * «есть только в источнике» (его надо удалить).
 *
 * <p>Тексты описаний различий попадают в JSON-отчёт и потому остаются английскими.
 */
public class SchemaDiffer {

    /** Назначает записьам severity. */
    private final SeverityClassifier severityClassifier;

    /**
     * @param severityClassifier классификатор severity для создаваемых записей
     */
    public SchemaDiffer(SeverityClassifier severityClassifier) {
        this.severityClassifier = severityClassifier;
    }

    /**
     * Строит полный дифф. Порядок секций фиксирован (таблицы и их колонки → констрейнты →
     * индексы → sequence), чтобы отчёт был стабильным между прогонами.
     *
     * @param source снимок базы-источника
     * @param target снимок целевой базы
     * @return неизменяемый список различий
     */
    public SchemaDiff diff(SchemaSnapshot source, SchemaSnapshot target) {
        List<DiffEntry> entries = new ArrayList<>();
        diffTables(source, target, entries);
        diffConstraints(source, target, entries);
        diffIndexes(source, target, entries);
        diffSequences(source, target, entries);
        return new SchemaDiff(source.schemaName(), target.schemaName(), List.copyOf(entries));
    }

    /**
     * Сопоставляет таблицы по именам: новые и удалённые дают записи целиком, общие
     * спускают в {@link #diffColumns} для сравнения колонок и комментария таблицы.
     *
     * @param source  снимок источника
     * @param target  снимок цели
     * @param entries аккумулятор записей диффа
     */
    private void diffTables(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, TableDef> sourceTables = byName(source.tables(), TableDef::name);
        Map<String, TableDef> targetTables = byName(target.tables(), TableDef::name);

        for (Map.Entry<String, TableDef> e : targetTables.entrySet()) {
            if (!sourceTables.containsKey(e.getKey())) {
                entries.add(add(ObjectType.TABLE, e.getKey(), e.getValue(),
                        "Table exists only in target"));
            }
        }
        for (Map.Entry<String, TableDef> e : sourceTables.entrySet()) {
            TableDef sourceTable = e.getValue();
            TableDef targetTable = targetTables.get(e.getKey());
            if (targetTable == null) {
                entries.add(remove(ObjectType.TABLE, e.getKey(), sourceTable,
                        "Table exists only in source"));
            } else {
                diffColumns(e.getKey(), sourceTable, targetTable, entries);
            }
        }
    }

    /**
     * Сопоставляет колонки таблицы по именам и сравнивает содержательно совпадающие пары
     * по атрибутам одной записью {@code MODIFIED}. Комментарии таблицы и колонок —
     * отдельные записи, чтобы смена документа не мешала ревью реальных изменений схемы.
     *
     * @param tableName имя таблицы (префикс имён колонок в записях)
     * @param sourceTable таблица источника
     * @param targetTable одноимённая таблица цели
     * @param entries аккумулятор записей диффа
     */
    private void diffColumns(String tableName, TableDef sourceTable, TableDef targetTable,
                             List<DiffEntry> entries) {
        Map<String, ColumnDef> sourceColumns = byName(sourceTable.columns(), ColumnDef::name);
        Map<String, ColumnDef> targetColumns = byName(targetTable.columns(), ColumnDef::name);

        for (Map.Entry<String, ColumnDef> e : targetColumns.entrySet()) {
            if (!sourceColumns.containsKey(e.getKey())) {
                entries.add(add(ObjectType.COLUMN, tableName + "." + e.getKey(), e.getValue(),
                        "Column exists only in target"));
            }
        }
        for (Map.Entry<String, ColumnDef> e : sourceColumns.entrySet()) {
            String columnName = e.getKey();
            ColumnDef before = e.getValue();
            ColumnDef after = targetColumns.get(columnName);
            if (after == null) {
                entries.add(remove(ObjectType.COLUMN, tableName + "." + columnName, before,
                        "Column exists only in source"));
                continue;
            }
            List<String> changes = columnChanges(before, after);
            if (!changes.isEmpty()) {
                entries.add(new DiffEntry(ObjectType.COLUMN, tableName + "." + columnName,
                        ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.COLUMN, ChangeType.MODIFIED),
                        String.join("; ", changes), before, after));
            }
            diffComment(before.comment(), after.comment(),
                    tableName + "." + columnName, before, after, entries);
        }
        diffComment(sourceTable.comment(), targetTable.comment(),
                tableName, sourceTable, targetTable, entries);
    }

    /**
     * Собирает список отличий одной колонки; пустой список означает «колонки совпали».
     * Атрибуты сравниваются независимо, поэтому одно изменение может перечислить сразу
     * несколько причин через {@code ; }.
     *
     * @param before колонка источника
     * @param after колонка цели
     * @return фрагменты описания различий
     */
    private List<String> columnChanges(ColumnDef before, ColumnDef after) {
        List<String> changes = new ArrayList<>();
        if (!normalizeType(before.dataType()).equals(normalizeType(after.dataType()))) {
            changes.add("type changed: %s -> %s".formatted(before.dataType(), after.dataType()));
        }
        if (before.nullable() != after.nullable()) {
            changes.add(before.nullable()
                    ? "became NOT NULL"
                    : "became nullable");
        }
        if (!normalizeDefault(before.defaultValue()).equals(normalizeDefault(after.defaultValue()))) {
            changes.add("default changed: %s -> %s".formatted(
                    display(before.defaultValue()), display(after.defaultValue())));
        }
        if (!Objects.equals(before.identity(), after.identity())) {
            changes.add("identity changed: %s -> %s".formatted(
                    displayIdentity(before.identity()), displayIdentity(after.identity())));
        }
        if (!Objects.equals(before.generated(), after.generated())) {
            changes.add("generation changed: %s -> %s".formatted(
                    displayGeneration(before.generated()), displayGeneration(after.generated())));
        }
        return changes;
    }

    /**
     * @param identity вид identity или {@code null}
     * @return читаемое представление для описания различия
     */
    private String displayIdentity(IdentityKind identity) {
        return identity == null ? "none" : "GENERATED " + identity.sql() + " AS IDENTITY";
    }

    /**
     * @param generated выражение generated-колонки или {@code null}
     * @return читаемое представление для описания различия
     */
    private String displayGeneration(ColumnGeneration generated) {
        return generated == null ? "none"
                : "GENERATED ALWAYS AS (%s) %s".formatted(generated.expression(), generated.kind().sql());
    }

    /**
     * Добавляет запись {@code COMMENT}, если тексты комментариев различаются. Отсутствие
     * комментария — это {@code null}, поэтому добавленный на одной стороне и снятый на другой
     * остаётся обычным изменением. beforeOwner/afterOwner — определения таблицы/колонки-
     * владельца, они попадают в отчёт как before/after записи.
     *
     * @param before комментарий источника
     * @param after комментарий цели
     * @param entryName имя объекта-владельца в записи диффа
     * @param beforeOwner определение владельца со стороны источника
     * @param afterOwner определение владельца со стороны цели
     * @param entries аккумулятор записей диффа
     */
    private void diffComment(String before, String after, String entryName,
                             Object beforeOwner, Object afterOwner, List<DiffEntry> entries) {
        if (Objects.equals(normalizeComment(before), normalizeComment(after))) {
            return;
        }
        String description = "comment changed: %s -> %s".formatted(
                displayComment(before), displayComment(after));
        entries.add(new DiffEntry(ObjectType.COMMENT, entryName, ChangeType.MODIFIED,
                severityClassifier.classify(ObjectType.COMMENT, ChangeType.MODIFIED),
                description, beforeOwner, afterOwner));
    }

    /**
     * Сопоставляет констрейнты по паре «таблица.имя»: имя констрейнта уникально только
     * внутри схемы-владельца, поэтому ключ должен включать таблицу.
     *
     * @param source  снимок источника
     * @param target  снимок цели
     * @param entries аккумулятор записей диффа
     */
    private void diffConstraints(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, ConstraintDef> sourceConstraints = byName(source.constraints(), c -> c.table() + "." + c.name());
        Map<String, ConstraintDef> targetConstraints = byName(target.constraints(), c -> c.table() + "." + c.name());

        for (Map.Entry<String, ConstraintDef> e : targetConstraints.entrySet()) {
            if (!sourceConstraints.containsKey(e.getKey())) {
                entries.add(add(ObjectType.CONSTRAINT, e.getKey(), e.getValue(),
                        "Constraint exists only in target"));
            }
        }
        for (Map.Entry<String, ConstraintDef> e : sourceConstraints.entrySet()) {
            ConstraintDef before = e.getValue();
            ConstraintDef after = targetConstraints.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.CONSTRAINT, e.getKey(), before,
                        "Constraint exists only in source"));
            } else {
                List<String> changes = constraintChanges(before, after);
                if (!changes.isEmpty()) {
                    entries.add(new DiffEntry(ObjectType.CONSTRAINT, e.getKey(), ChangeType.MODIFIED,
                            severityClassifier.classify(ObjectType.CONSTRAINT, ChangeType.MODIFIED),
                            String.join("; ", changes), before, after));
                }
            }
        }
    }

    /**
     * Сравнивает констрейнты: каноническое определение из {@code pg_get_constraintdef}
     * и отдельно флаги, которые сервер в это определение не печатает.
     *
     * @param before констрейнт источника
     * @param after констрейнт цели
     * @return фрагменты описания различий
     */
    private List<String> constraintChanges(ConstraintDef before, ConstraintDef after) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(before.definition(), after.definition())) {
            changes.add("definition changed: %s -> %s".formatted(before.definition(), after.definition()));
        }
        if (!before.flagsClause().equals(after.flagsClause())) {
            // pg_get_constraintdef не отображает ни deferrability, ни NOT VALID,
            // поэтому опции сравниваются через структурные флаги, которые их порождают
            changes.add("options changed: %s -> %s".formatted(
                    displayFlags(before.flagsClause()), displayFlags(after.flagsClause())));
        }
        return changes;
    }

    /**
     * @param flagsClause хвост флагов из {@link ConstraintDef#flagsClause()}
     * @return читаемое представление флагов для описания различия
     */
    private String displayFlags(String flagsClause) {
        return flagsClause.isEmpty() ? "none" : flagsClause.trim();
    }

    /**
     * Сопоставляет индексы по паре «таблица.имя» и сравнивает их целиком по каноническому
     * определению: любое различие метода, состава колонок или предиката требует пересоздания.
     *
     * @param source  снимок источника
     * @param target  снимок цели
     * @param entries аккумулятор записей диффа
     */
    private void diffIndexes(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, IndexDef> sourceIndexes = byName(source.indexes(), i -> i.table() + "." + i.name());
        Map<String, IndexDef> targetIndexes = byName(target.indexes(), i -> i.table() + "." + i.name());

        for (Map.Entry<String, IndexDef> e : targetIndexes.entrySet()) {
            if (!sourceIndexes.containsKey(e.getKey())) {
                entries.add(add(ObjectType.INDEX, e.getKey(), e.getValue(),
                        "Index exists only in target"));
            }
        }
        for (Map.Entry<String, IndexDef> e : sourceIndexes.entrySet()) {
            IndexDef before = e.getValue();
            IndexDef after = targetIndexes.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.INDEX, e.getKey(), before,
                        "Index exists only in source"));
            } else if (!Objects.equals(before.definition(), after.definition())) {
                entries.add(new DiffEntry(ObjectType.INDEX, e.getKey(), ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.INDEX, ChangeType.MODIFIED),
                        "definition changed: %s -> %s".formatted(before.definition(), after.definition()),
                        before, after));
            }
        }
    }

    /**
     * Сопоставляет sequence по имени и сравнивает их как единое целое: набор параметров мал,
     * а {@code ALTER SEQUENCE} всегда переписывает его целиком.
     *
     * @param source  снимок источника
     * @param target  снимок цели
     * @param entries аккумулятор записей диффа
     */
    private void diffSequences(SchemaSnapshot source, SchemaSnapshot target, List<DiffEntry> entries) {
        Map<String, SequenceDef> sourceSequences = byName(source.sequences(), SequenceDef::name);
        Map<String, SequenceDef> targetSequences = byName(target.sequences(), SequenceDef::name);

        for (Map.Entry<String, SequenceDef> e : targetSequences.entrySet()) {
            if (!sourceSequences.containsKey(e.getKey())) {
                entries.add(add(ObjectType.SEQUENCE, e.getKey(), e.getValue(),
                        "Sequence exists only in target"));
            }
        }
        for (Map.Entry<String, SequenceDef> e : sourceSequences.entrySet()) {
            SequenceDef before = e.getValue();
            SequenceDef after = targetSequences.get(e.getKey());
            if (after == null) {
                entries.add(remove(ObjectType.SEQUENCE, e.getKey(), before,
                        "Sequence exists only in source"));
            } else if (!Objects.equals(before, after)) {
                entries.add(new DiffEntry(ObjectType.SEQUENCE, e.getKey(), ChangeType.MODIFIED,
                        severityClassifier.classify(ObjectType.SEQUENCE, ChangeType.MODIFIED),
                        "sequence parameters changed", before, after));
            }
        }
    }

    /**
     * Индексирует список по нижнерегистровому ключу. {@link LinkedHashMap} сохраняет порядок
     * обхода, пришедший из SQL ({@code ORDER BY}), — так повторные прогоны дают идентичный отчёт.
     * Различающиеся только регистром имена в одном каталоге встречаются лишь при экранированных
     * идентификаторах; для них остаётся последний объект, как и в сопоставлении сторон.
     *
     * @param items объекты одного типа
     * @param keyFn функция, дающая имя-ключ (для дочерних объектов — «родитель.имя»)
     * @return ключ → объект
     */
    private <T> Map<String, T> byName(List<T> items, Function<T, String> keyFn) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(keyFn.apply(item).toLowerCase(), item);
        }
        return map;
    }

    /**
     * @param type тип объекта
     * @param name полностью квалифицированное имя
     * @param after определение со стороны цели
     * @param description готовое текстовое описание различия
     * @return запись {@code ADDED} с вычисленным severity
     */
    private DiffEntry add(ObjectType type, String name, Object after, String description) {
        return new DiffEntry(type, name, ChangeType.ADDED,
                severityClassifier.classify(type, ChangeType.ADDED), description, null, after);
    }

    /**
     * @param type тип объекта
     * @param name полностью квалифицированное имя
     * @param before определение со стороны источника
     * @param description готовое текстовое описание различия
     * @return запись {@code REMOVED} с вычисленным severity
     */
    private DiffEntry remove(ObjectType type, String name, Object before, String description) {
        return new DiffEntry(type, name, ChangeType.REMOVED,
                severityClassifier.classify(type, ChangeType.REMOVED), description, before, null);
    }

    /**
     * @param type SQL-тип колонки из {@code format_type} или {@code null}
     * @return ключ сравнения: регистр и внутренние пробелы нормализованы
     */
    private String normalizeType(String type) {
        return type == null ? "" : type.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /**
     * @param defaultValue выражение DEFAULT или {@code null}
     * @return ключ сравнения: {@code null} приравнен к «нет дефолта», пробелы схлопнуты —
     *         сервер оформляет выражения по-разному в разных версиях
     */
    private String normalizeDefault(String defaultValue) {
        return defaultValue == null ? "" : defaultValue.replaceAll("\\s+", " ").trim();
    }

    /**
     * @param comment текст комментария или {@code null}
     * @return ключ сравнения: {@code null} и пустая строка эквивалентны, отступы не значим
     */
    private String normalizeComment(String comment) {
        return comment == null ? "" : comment.trim();
    }

    /**
     * @param value сравниваемое значение
     * @return представление «нет значения» для текстового описания различия
     */
    private String display(String value) {
        return value == null ? "none" : value;
    }

    /**
     * @param comment текст комментария или {@code null}
     * @return кавычки вокруг комментария, чтобы в одну кавычку с текстом отличалась от отсутствия
     */
    private String displayComment(String comment) {
        return comment == null ? "(no comment)" : "'" + comment + "'";
    }
}
