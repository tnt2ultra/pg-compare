package com.anri.pgcompare.ddl;

import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.ObjectType;
import com.anri.pgcompare.diff.SchemaDiff;
import com.anri.pgcompare.exception.CompareException;
import com.anri.pgcompare.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.anri.pgcompare.diff.ChangeType.*;

/**
 * Превращает {@link SchemaDiff} (источник = мигрируемое состояние, цель = желаемое состояние)
 * в скрипт миграции, применяемый к базе-источнику. Операторы идут в безопасном по зависимостям
 * порядке: sequence → таблицы → колонки → комментарии → констрейнты
 * (снятия и не-FK первыми, FK последними) → индексы. Имена объектов квалифицируются схемой
 * источника: экстрактор срезает с определений префикс собственной схемы, поэтому генератор
 * возвращает квалификацию там, где она нужна.
 */
public class DdlGenerator {

    /** Совпадает с {@code FOREIGN KEY (колонки) REFERENCES таблица (колонки)}, останавливаясь перед MATCH / ON. */
    private static final Pattern FK_HEAD_PATTERN =
            Pattern.compile("(?i)^\\s*FOREIGN KEY\\s*\\(.*?\\)\\s*REFERENCES\\s+\\S+?\\s*\\(.*?\\)");

    /** Неквалифицированный regclass-литерал, например последовательность внутри {@code nextval('doc_seq'::regclass)}. */
    private static final Pattern REGCLASS_LITERAL_PATTERN =
            Pattern.compile("'([\\w$]+)'::(?:pg_catalog\\.)?regclass");

    /**
     * Строит полный набор операторов миграции.
     *
     * <p>Перед обходом вычисляются два «каскадных» множества, чтобы не эмитировать операторы,
     * которые PostgreSQL выполнит сам или которые на его фоне упадут: таблицы, снимаемые целиком,
     * и колонки, снимаемые по таблице.
     *
     * @param diff результат сравнения схем
     * @return операторы в порядке применения
     * @throws CompareException если для констрейнта нет канонического определения и эмитировать
     *                          нечего (CHECK/EXCLUDE)
     */
    public List<DdlStatement> generate(SchemaDiff diff) {
        String schema = diff.sourceSchema();
        // снимаемые этой миграцией таблицы делают её записи о колонках/констрейнтах/индексах избыточными
        Set<String> droppedTables = select(diff, ObjectType.TABLE).stream()
                .filter(e -> e.changeType() == REMOVED)
                .map(DiffEntry::objectName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        // снятие колонки каскадно забирает констрейнты и индексы, которые её используют,
        // поэтому явные снятия таких объектов упадут во время выполнения
        Map<String, Set<String>> droppedColumnsByTable = select(diff, ObjectType.COLUMN).stream()
                .filter(e -> e.changeType() == REMOVED)
                .collect(Collectors.groupingBy(
                        e -> splitName(e.objectName())[0].toLowerCase(),
                        Collectors.mapping(e -> splitName(e.objectName())[1], Collectors.toSet())));
        List<DdlStatement> statements = new ArrayList<>();
        sequences(diff, schema, statements);
        tables(diff, schema, statements);
        columns(diff, schema, droppedTables, statements);
        comments(diff, schema, droppedTables, statements);
        constraints(diff, schema, droppedTables, droppedColumnsByTable, statements);
        indexes(diff, schema, droppedTables, droppedColumnsByTable, statements);
        return statements;
    }

    /**
     * Снимает/создаёт sequence и правляет их параметры. {@code ALTER SEQUENCE} допускает только
     * те значения, которые изменились, — иначе он переписал бы остаток на дефолтный.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param out аккумулятор операторов
     */
    private void sequences(SchemaDiff diff, String schema, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.SEQUENCE)) {
            if (e.changeType() == ADDED) {
                SequenceDef s = (SequenceDef) e.after();
                out.add(DdlStatement.of("CREATE SEQUENCE %s START WITH %d INCREMENT BY %d MINVALUE %d MAXVALUE %d"
                        .formatted(qualify(schema, e.objectName()), s.startValue(), s.increment(),
                                s.minValue(), s.maxValue())));
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented("DROP SEQUENCE %s".formatted(qualify(schema, e.objectName())),
                        "BREAKING: sequence removal"));
            } else if (e.changeType() == MODIFIED) {
                SequenceDef before = (SequenceDef) e.before();
                SequenceDef after = (SequenceDef) e.after();
                StringBuilder sql = new StringBuilder("ALTER SEQUENCE ")
                        .append(qualify(schema, e.objectName()));
                if (before.startValue() != after.startValue()) {
                    sql.append(" START WITH ").append(after.startValue());
                }
                if (before.increment() != after.increment()) {
                    sql.append(" INCREMENT BY ").append(after.increment());
                }
                if (before.minValue() != after.minValue()) {
                    sql.append(" MINVALUE ").append(after.minValue());
                }
                if (before.maxValue() != after.maxValue()) {
                    sql.append(" MAXVALUE ").append(after.maxValue());
                }
                out.add(DdlStatement.of(sql.toString()));
            }
        }
    }

    /**
     * Создаёт и снимает таблицы. Комментарий новой таблицы эмитится отдельным оператором:
     * {@code CREATE TABLE} его не принимает, и то же тело колонки используется в {@code ADD COLUMN}.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param out аккумулятор операторов
     */
    private void tables(SchemaDiff diff, String schema, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.TABLE)) {
            if (e.changeType() == ADDED) {
                TableDef t = (TableDef) e.after();
                out.add(DdlStatement.of(createTable(schema, t)));
                if (t.comment() != null) {
                    out.add(DdlStatement.of("COMMENT ON TABLE %s IS %s"
                            .formatted(qualify(schema, t.name()), commentLiteral(t.comment()))));
                }
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented("DROP TABLE %s".formatted(qualify(schema, e.objectName())),
                        "BREAKING: drops all data in the table"));
            }
        }
    }

    /**
     * @param schema схема источника
     * @param table определение таблицы из цели
     * @return тело {@code CREATE TABLE} со всеми колонками, но без констрейнтов и комментариев:
     *         они добавляются отдельными операторами, чтобы порядок зависел от секций генератора
     */
    private String createTable(String schema, TableDef table) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ")
                .append(qualify(schema, table.name())).append(" (\n");
        List<ColumnDef> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append("    ").append(columnBody(schema, columns.get(i)));
            if (i < columns.size() - 1) {
                sql.append(',');
            }
            sql.append('\n');
        }
        return sql.append(")").toString();
    }

    /**
     * Описание колонки, общее для {@code CREATE TABLE} и {@code ADD COLUMN}: тип,
     * identity/generation, NOT NULL, DEFAULT.
     *
     * @param schema схема источника (нужна для квалификации regclass-дефолтов)
     * @param c определение колонки
     * @return фрагмент SQL без ведущего имени таблицы
     */
    private String columnBody(String schema, ColumnDef c) {
        StringBuilder sql = new StringBuilder(q(c.name())).append(' ').append(c.dataType());
        if (c.identity() != null) {
            sql.append(" GENERATED ").append(c.identity().sql()).append(" AS IDENTITY");
        }
        if (c.generated() != null) {
            sql.append(" GENERATED ALWAYS AS (").append(c.generated().expression()).append(") ")
                    .append(c.generated().kind().sql());
        }
        if (!c.nullable()) {
            sql.append(" NOT NULL");
        }
        if (c.defaultValue() != null) {
            sql.append(" DEFAULT ").append(qualifyDefault(schema, c.defaultValue()));
        }
        return sql.toString();
    }

    /**
     * Экстрактор срезает сравниваемую схему из литералов {@code 'seq'::regclass} ради сравнения;
     * здесь она возвращается, чтобы дефолт разрешался без опоры на search_path.
     * Уже квалифицированные (межсхемные) ссылки остаются как есть.
     *
     * @param schema схема источника
     * @param defaultValue выражение DEFAULT из каталога
     * @return выражение DEFAULT с возвращённой квалификацией
     */
    private String qualifyDefault(String schema, String defaultValue) {
        Matcher literal = REGCLASS_LITERAL_PATTERN.matcher(defaultValue);
        if (!literal.find()) {
            return defaultValue;
        }
        return literal.replaceFirst(
                Matcher.quoteReplacement("'" + q(schema) + "." + q(literal.group(1)) + "'::regclass"));
    }

    /**
     * Добавляет, снимает и изменяет колонки. Записи колонок снимаемых таблиц пропускаются —
     * {@code DROP TABLE} забирает их с собой.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param droppedTables имена снимаемых таблиц (нижний регистр)
     * @param out аккумулятор операторов
     */
    private void columns(SchemaDiff diff, String schema, Set<String> droppedTables, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.COLUMN)) {
            String[] parts = splitName(e.objectName());
            if (droppedTables.contains(parts[0].toLowerCase())) {
                continue;
            }
            String table = qualify(schema, parts[0]);
            String column = q(parts[1]);
            switch (e.changeType()) {
                case ADDED -> {
                    ColumnDef c = (ColumnDef) e.after();
                    out.add(DdlStatement.commented(
                            "ALTER TABLE %s ADD COLUMN %s".formatted(table, columnBody(schema, c)),
                            c.nullable() || c.identity() != null || c.generated() != null
                                    ? null
                                    : "review: NOT NULL on existing rows requires a default"));
                    if (c.comment() != null) {
                        out.add(DdlStatement.of("COMMENT ON COLUMN %s.%s IS %s"
                                .formatted(table, column, commentLiteral(c.comment()))));
                    }
                }
                case REMOVED -> out.add(DdlStatement.commented(
                        "ALTER TABLE %s DROP COLUMN %s".formatted(table, column),
                        "BREAKING: drops column data"));
                case MODIFIED ->
                        emitColumnAlter(schema, table, column, (ColumnDef) e.before(), (ColumnDef) e.after(), out);
            }
        }
    }

    /**
     * Разворачивает изменение одной колонки в операторы {@code ALTER TABLE}.
     *
     * <p>Порядок внутри колонки важен: смена generated-колонки обрабатывается раньше остальных и
     * завершает метод, потому что {@code DROP}/{@code ADD COLUMN} уже пересоздаёт колонку со всеми
     * её атрибутами из целевого определения.
     *
     * @param schema схема источника
     * @param table квалифицированное имя таблицы
     * @param column экранированное имя колонки
     * @param before колонка источника
     * @param after колонка цели
     * @param out аккумулятор операторов
     */
    private void emitColumnAlter(String schema, String table, String column,
                                 ColumnDef before, ColumnDef after, List<DdlStatement> out) {
        if (!Objects.equals(before.generated(), after.generated())) {
            // выражение генерации нельзя изменить на месте; значение выводится из строки,
            // поэтому пересоздание колонки не теряет пользовательские данные
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP COLUMN %s".formatted(table, column),
                    "review: generated column is recreated from its new expression,"
                            + " dependent indexes and constraints are dropped by PostgreSQL"));
            out.add(DdlStatement.of("ALTER TABLE %s ADD COLUMN %s"
                    .formatted(table, columnBody(schema, after))));
            return;
        }
        if (!before.dataType().equals(after.dataType())) {
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s ALTER COLUMN %s TYPE %s".formatted(table, column, after.dataType()),
                    "review: implicit cast may fail, consider a USING clause"));
        }
        if (before.nullable() != after.nullable()) {
            out.add(DdlStatement.of(after.nullable()
                    ? "ALTER TABLE %s ALTER COLUMN %s DROP NOT NULL".formatted(table, column)
                    : "ALTER TABLE %s ALTER COLUMN %s SET NOT NULL".formatted(table, column)));
        }
        if (!Objects.equals(before.defaultValue(), after.defaultValue())) {
            out.add(DdlStatement.of(after.defaultValue() == null
                    ? "ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT".formatted(table, column)
                    : "ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s".formatted(
                            table, column, qualifyDefault(schema, after.defaultValue()))));
        }
        if (!Objects.equals(before.identity(), after.identity())) {
            if (after.identity() == null) {
                out.add(DdlStatement.commented(
                        "ALTER TABLE %s ALTER COLUMN %s DROP IDENTITY IF EXISTS".formatted(table, column),
                        "identity removed; the backing sequence is left behind, review whether to drop it"));
            } else if (before.identity() == null) {
                out.add(DdlStatement.commented(
                        "ALTER TABLE %s ALTER COLUMN %s ADD GENERATED %s AS IDENTITY".formatted(
                                table, column, after.identity().sql()),
                        "review: existing rows need values before an identity can take over"));
            } else {
                out.add(DdlStatement.of("ALTER TABLE %s ALTER COLUMN %s SET GENERATED %s".formatted(
                        table, column, after.identity().sql())));
            }
        }
    }

    /**
     * Эмитит {@code COMMENT ON} для изменённых комментариев таблиц и колонок (побеждает цель).
     * Имя в записи диффа — «таблица» или «таблица.колонка», по количеству частей определяется
     * вид объекта.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param droppedTables имена снимаемых таблиц (нижний регистр)
     * @param out аккумулятор операторов
     */
    private void comments(SchemaDiff diff, String schema, Set<String> droppedTables,
                          List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.COMMENT)) {
            String[] parts = splitName(e.objectName());
            if (droppedTables.contains(parts[0].toLowerCase())) {
                continue;
            }
            String targetComment = commentOf(e.after());
            if (parts.length == 1) {
                out.add(DdlStatement.of("COMMENT ON TABLE %s IS %s"
                        .formatted(qualify(schema, parts[0]), commentLiteral(targetComment))));
            } else {
                out.add(DdlStatement.of("COMMENT ON COLUMN %s.%s IS %s"
                        .formatted(qualify(schema, parts[0]), q(parts[1]), commentLiteral(targetComment))));
            }
        }
    }

    /**
     * @param owner определение владельца комментария со стороны цели (таблица или колонка)
     * @return комментарий владельца либо {@code null}, если тип владельца не распознался
     */
    private String commentOf(Object owner) {
        if (owner instanceof TableDef t) {
            return t.comment();
        }
        if (owner instanceof ColumnDef c) {
            return c.comment();
        }
        return null;
    }

    /**
     * @param comment текст комментария
     * @return SQL-строковый литерал; {@code null} превращается в {@code NULL}, то есть
     *         {@code COMMENT ... IS NULL} снимает комментарий
     */
    private String commentLiteral(String comment) {
        return comment == null ? "NULL" : "'" + comment.replace("'", "''") + "'";
    }

    /**
     * Пересоздаёт констрейнты: сначала все снятия, затем добавления — иначе новый констрейнт
     * упадёт на ещё существующем старом. Внутри добавлений не-FK идут раньше FK, чтобы опорные
     * таблицы успели стать валидными до проверки ссылок.
     *
     * <p>Изменённый констрейнт нельзя починить на месте в общем случае (меняется состав колонок),
     * поэтому он снимается и создаётся заново из целевого определения.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param droppedTables имена снимаемых таблиц (нижний регистр)
     * @param droppedColumnsByTable снимаемые колонки по таблицам (в исходном регистре)
     * @param out аккумулятор операторов
     */
    private void constraints(SchemaDiff diff, String schema, Set<String> droppedTables,
                             Map<String, Set<String>> droppedColumnsByTable, List<DdlStatement> out) {
        List<DiffEntry> added = new ArrayList<>();
        List<DiffEntry> removed = new ArrayList<>();
        List<DiffEntry> modified = new ArrayList<>();
        for (DiffEntry e : select(diff, ObjectType.CONSTRAINT)) {
            String[] parts = splitName(e.objectName());
            String table = parts[0].toLowerCase();
            if (droppedTables.contains(table)) {
                continue;
            }
            if (e.changeType() != ADDED
                    && ((ConstraintDef) e.before()).columns().stream()
                            .anyMatch(col -> droppedColumnsByTable.getOrDefault(table, Set.of())
                                    .contains(col))) {
                continue; // констрейнт уйдёт каскадно вместе со своей колонкой
            }
            switch (e.changeType()) {
                case ADDED -> added.add(e);
                case REMOVED -> removed.add(e);
                case MODIFIED -> modified.add(e);
            }
        }
        for (DiffEntry e : removed) {
            String[] parts = splitName(e.objectName());
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP CONSTRAINT %s".formatted(qualify(schema, parts[0]), q(parts[1])),
                    "constraint no longer present in target"));
        }
        for (DiffEntry e : modified) {
            String[] parts = splitName(e.objectName());
            out.add(DdlStatement.commented(
                    "ALTER TABLE %s DROP CONSTRAINT %s".formatted(qualify(schema, parts[0]), q(parts[1])),
                    "definition changed, re-added below; the drop fails if a foreign key"
                            + " or a view depends on this constraint"));
        }
        // изменённый констрейнт снят выше и пересоздан здесь из целевого определения;
        // не-FK раньше FK, чтобы опорные таблицы были готовы до валидации
        List<DiffEntry> toCreate = new ArrayList<>(added);
        toCreate.addAll(modified);
        emitAddConstraints(toCreate, schema, out, false);
        emitAddConstraints(toCreate, schema, out, true);
    }

    /**
     * Второй проход над констрейнтами к созданию: один вызов эмитит только не-FK, второй — только FK.
     *
     * @param toCreate констрейнты к добавлению (новые и изменённые)
     * @param schema схема источника
     * @param out аккумулятор операторов
     * @param foreignKeys {@code true} — обрабатывать только FK, {@code false} — только не-FK
     */
    private void emitAddConstraints(List<DiffEntry> toCreate, String schema, List<DdlStatement> out,
                                    boolean foreignKeys) {
        for (DiffEntry e : toCreate) {
            ConstraintDef c = (ConstraintDef) e.after();
            boolean isFk = c.type() == ConstraintType.FOREIGN_KEY;
            if (isFk != foreignKeys) {
                continue;
            }
            String[] parts = splitName(e.objectName());
            String sql = "ALTER TABLE %s ADD CONSTRAINT %s %s%s".formatted(
                    qualify(schema, parts[0]), q(parts[1]), inlineDefinition(schema, c), c.flagsClause());
            out.add(DdlStatement.of(sql));
        }
    }

    /**
     * Собирает тело констрейнта для {@code ADD CONSTRAINT} — инлайн-форму, а не
     * {@code USING INDEX}: снимая констрейнт, PostgreSQL снимает и его индекс, так что
     * переиспользовать старый индекс нельзя.
     *
     * @param schema схема источника
     * @param c определение констрейнта из цели
     * @return фрагмент SQL после {@code ADD CONSTRAINT "имя" }
     * @throws CompareException если CHECK/EXCLUDE пришли без канонического определения
     */
    private String inlineDefinition(String schema, ConstraintDef c) {
        return switch (c.type()) {
            case PRIMARY_KEY -> "PRIMARY KEY (" + columns(c.columns()) + ")";
            case UNIQUE -> "UNIQUE (" + columns(c.columns()) + ")";
            case CHECK, EXCLUSION -> requireDefinition(c);
            case FOREIGN_KEY -> "FOREIGN KEY (" + columns(c.columns()) + ") REFERENCES "
                    + qualify(schema, c.referencedTable()) + " (" + columns(c.referencedColumns()) + ")"
                    + foreignKeyTail(c.definition());
        };
    }

    /**
     * CHECK и EXCLUDE восстанавливаются только из канонического определения: без него
     * эмитировать нечего, а молча пропустить констрейнт — значит оставить схему расходящейся.
     *
     * @param c определение констрейнта
     * @return каноническое определение
     * @throws CompareException если определения нет
     */
    private String requireDefinition(ConstraintDef c) {
        if (c.definition() == null) {
            throw new CompareException(
                    "Не удалось сгенерировать DDL для констрейнта %s '%s': нет определения".formatted(c.type(), c.name()));
        }
        return c.definition();
    }

    /**
     * {@code pg_get_constraintdef} печатает MATCH / ON DELETE / ON UPDATE только в тексте
     * определения, после списка опорных колонок, поэтому «хвост» переносится дословно —
     * пересобирать его из (отсутствующих) структурных полей не из чего.
     *
     * @param definition каноническое определение FK
     * @return всё, что идёт за головой {@code FOREIGN KEY ... REFERENCES ... (...)}, с ведущим пробелом
     *         либо пустая строка
     */
    private String foreignKeyTail(String definition) {
        if (definition == null) {
            return "";
        }
        Matcher head = FK_HEAD_PATTERN.matcher(definition);
        if (!head.find()) {
            return "";
        }
        String tail = definition.substring(head.end()).trim();
        return tail.isEmpty() ? "" : " " + tail;
    }

    /**
     * @param columns имена колонок в исходном порядке констрейнта
     * @return экранированный список колонок для SQL
     */
    private String columns(List<String> columns) {
        return columns.stream().map(this::q).collect(Collectors.joining(", "));
    }

    /**
     * Снимает и пересоздаёт индексы. Индекс нельзя изменить на месте, поэтому {@code MODIFIED}
     * даёт пару {@code DROP INDEX} + создание. Индексы, накрывающие снимаемую колонку,
     * не эмитятся: PostgreSQL уберёт их каскадно, а явное снятие упадёт.
     *
     * @param diff результат сравнения схем
     * @param schema схема источника
     * @param droppedTables имена снимаемых таблиц (нижний регистр)
     * @param droppedColumnsByTable снимаемые колонки по таблицам (в исходном регистре)
     * @param out аккумулятор операторов
     */
    private void indexes(SchemaDiff diff, String schema, Set<String> droppedTables,
                         Map<String, Set<String>> droppedColumnsByTable, List<DdlStatement> out) {
        for (DiffEntry e : select(diff, ObjectType.INDEX)) {
            String[] parts = splitName(e.objectName());
            String table = parts[0].toLowerCase();
            if (droppedTables.contains(table)) {
                continue;
            }
            if (e.changeType() == REMOVED
                    && mentionsAnyColumn(((IndexDef) e.before()).definition(),
                            droppedColumnsByTable.getOrDefault(table, Set.of()))) {
                continue; // индекс уйдёт каскадно вместе со своей колонкой
            }
            if (e.changeType() == ADDED) {
                IndexDef i = (IndexDef) e.after();
                out.add(DdlStatement.of(qualifyIndexTable(i.definition(), schema)));
            } else if (e.changeType() == REMOVED) {
                out.add(DdlStatement.commented(
                        "DROP INDEX %s.%s".formatted(q(schema), q(parts[1])),
                        "index no longer present in target"));
            } else if (e.changeType() == MODIFIED) {
                IndexDef after = (IndexDef) e.after();
                out.add(DdlStatement.commented(
                        "DROP INDEX %s.%s".formatted(q(schema), q(parts[1])),
                        "definition changed, recreated next"));
                out.add(DdlStatement.of(qualifyIndexTable(after.definition(), schema)));
            }
        }
    }

    /**
     * Определения индексов нормализованы (префикс схемы срезан) — возвращаем квалификацию таблицы.
     * Правится только позиция после {@code ON}, поэтому method/колонки/predicate не затрагиваются.
     *
     * @param definition каноническое определение {@code CREATE INDEX}
     * @param schema схема источника
     * @return определение с квалифицированным именем таблицы
     */
    private String qualifyIndexTable(String definition, String schema) {
        return definition.replaceFirst("(?i)(\\sON\\s)(\"?[\\w]+\"?)(\\s|\\()",
                "$1" + Matcher.quoteReplacement(q(schema)) + ".$2$3");
    }

    /**
     * @param diff результат сравнения схем
     * @param objectType нужный тип объекта
     * @return записи только этого типа, в исходном порядке диффа
     */
    private List<DiffEntry> select(SchemaDiff diff, ObjectType objectType) {
        return diff.entries().stream()
                .filter(e -> e.objectType() == objectType)
                .toList();
    }

    /**
     * Проверяет, упоминается ли хоть одна из колонок в каноническом определении индекса.
     * Совпадение — по границам слова, чтобы {@code id} не «нашёл» {@code user_id}.
     *
     * @param definition определение индекса
     * @param columnNames имена снимаемых колонок этой таблицы
     * @return {@code true}, если индекс зависит от снимаемой колонки
     */
    private boolean mentionsAnyColumn(String definition, Set<String> columnNames) {
        for (String column : columnNames) {
            if (definition.matches("(?i).*\\b" + Pattern.quote(column) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Режет квалифицированное имя записи диффа.
     *
     * @param qualifiedName имя вида «таблица.объект» или одиночное имя таблицы/sequence
     * @return одна часть для родителя и две — для дочернего объекта
     */
    private String[] splitName(String qualifiedName) {
        int dot = qualifiedName.indexOf('.');
        if (dot < 0) {
            return new String[]{qualifiedName};
        }
        return new String[]{qualifiedName.substring(0, dot), qualifiedName.substring(dot + 1)};
    }

    /**
     * Квалифицированное имя {@code схема.таблица}; межсхемные ссылки (уже с точкой) сохраняются как есть.
     *
     * @param schema схема источника
     * @param tableName имя таблицы, возможно уже с префиксом схемы
     * @return экранированное квалифицированное имя
     */
    private String qualify(String schema, String tableName) {
        if (tableName.contains(".")) {
            return Arrays.stream(tableName.split("\\.")).map(this::q).collect(Collectors.joining("."));
        }
        return q(schema) + "." + q(tableName);
    }

    /**
     * Экранирует идентификатор двойными кавычками; имена приходят из pg_catalog в том виде,
     * в каком сохранены, поэтому кавычки обязательны — иначе регистр и ключевые слова «поплывут».
     *
     * @param identifier имя объекта
     * @return экранированный идентификатор
     */
    private String q(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
