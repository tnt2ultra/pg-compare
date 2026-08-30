# Требования pg-compare

Формулировки — постфактум, по реализации. Ключевые слова **должен** (обязательное),
**следует** (желательное), **может** (допустимое) трактуются как обязательные/рекомендуемые/
опциональные требования. Каждый номер используется в ссылках из других документов.

Формат таблицы: `№ | Требование | Реализация | Проверка`. «Проверка» — конкретный артефакт,
подтверждающий выполнение; `—` означает, что требование проверяется только на сборке/ревью кода.

---

## 1. Функциональные требования

### 1.1 Чтение схемы

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-01 | Утилита **должна** читать определение схемы из `pg_catalog`, а не из JDBC-метаданных | `extractor/*` (SQL-константы) | `SchemaRoundTripIT` |
| FR-02 | Для каждой стороны сравнения **должно** открываться отдельное подключение, немедленно закрываемое после чтения | `ConnectionProvider.open`, try-with-resources в `CompareCommand.extract` | — |
| FR-03 | Подключение **должно** переводиться в режим только для чтения | `Connection#setReadOnly(true)` | — |
| FR-04 | **Должны** читаться только обычные таблицы (`relkind = 'r'`) | `TableExtractor.TABLES_SQL` | `SchemaRoundTripIT` |
| FR-05 | Служебные и удалённые колонки (`attnum <= 0`, `attisdropped`) **должны** исключаться | `TableExtractor.COLUMNS_SQL` | — |
| FR-06 | Тип колонки **должен** браться из `format_type`, значение по умолчанию и выражение генерации — из `pg_attrdef` через `pg_get_expr` | `TableExtractor.COLUMNS_SQL` | `SchemaDifferTest`, `DdlGeneratorTest` |
| FR-07 | Комментарии таблиц и колонок **должны** читаться из `pg_description` | `TableExtractor` (`objsubid = 0` / `= attnum`) | `SchemaDifferTest` (4 теста на комментарии) |
| FR-08 | **Должны** читаться констрейнты видов PK, FK, UNIQUE, CHECK, EXCLUDE | `ConstraintExtractor.mapType` | `SchemaRoundTripIT` (EXCLUDE, NOT VALID FK) |
| FR-09 | Порядок колонок в констрейнте **должен** сохраняться | `unnest(...) WITH ORDINALITY` + `array_to_string` | `DdlGeneratorTest#addedForeignKeyKeepsMatchClauseAndColumnarDelete` |
| FR-10 | Определения констрейнтов и индексов **должны** браться каноническими текстами сервера (`pg_get_constraintdef`, `pg_get_indexdef`) | `ConstraintExtractor`, `IndexExtractor` | `DdlGeneratorTest` |
| FR-11 | Флаги `NOT VALID`, `DEFERRABLE`, `INITIALLY DEFERRED` **должны** читаться из каталога (`convalidated`, `condeferrable`, `condeferred`) | `ConstraintExtractor.SQL` | `SchemaDifferTest#constraintOptionChangeWithoutDefinitionChangeIsDetected` |
| FR-12 | Индексы, обслуживающие констрейнты, **не должны** попадать в снимок | `IndexExtractor` (`NOT EXISTS` по `conindid`) | `SchemaRoundTripIT` (остаточный дифф пуст) |
| FR-13 | Sequence за identity-колонками **не должны** попадать в снимок | `SequenceExtractor` (`pg_depend.deptype = 'i'`) | `SchemaRoundTripIT` |
| FR-14 | Из определений **должен** срезаться префикс собственной сравниваемой схемы, квалификация чужими схемами — сохраняться | `DefinitionNormalizer` | `DdlGeneratorTest#crossSchemaForeignKeyReferenceIsKeptQualified` |
| FR-15 | Несуществующая схема **должна** быть ошибкой, а не пустым снимком | `SchemaExtractor.validateSchemaExists` | `SchemaRoundTripIT#extractionFailsForMissingSchema` |
| FR-16 | Отношения, не покрываемые сравнением (`v`, `m`, `p`, `f`), **должны** отображаться в предупреждении лога с количеством | `SchemaExtractor.warnAboutIgnoredRelations` | ревью вывода |
| FR-17 | Констрейнт неподдерживаемого вида **должен** пропускаться с предупреждением, не прерывая сравнение | `ConstraintExtractor.extract` (ветка `type == null`) | — |

### 1.2 Сравнение

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-20 | Сравнение **должно** быть функцией двух снимков, без доступа к БД и файлам | `SchemaDiffer.diff` | весь `SchemaDifferTest` |
| FR-21 | Идентичность объектов **должна** определяться по имени в нижнем регистре; дочерних — по паре «родитель.имя» | `SchemaDiffer.byName`, ключи `diff*` | `SchemaDifferTest#sameNamesDifferentCaseAreMatched` |
| FR-22 | Направление **должно** быть фиксированным: `ADDED` — только в цели, `REMOVED` — только в источнике | константы описаний в `diff*` | `SchemaDifferTest#addedAndRemovedTablesAreDetected` |
| FR-23 | У колонки **должны** сравниваться тип, nullability, `DEFAULT`, identity, generated-выражение и вид | `SchemaDiffer.columnChanges` | 5 тестов `SchemaDifferTest` |
| FR-24 | Несколько отличий одной колонки **должны** быть одной записью с перечислением через `; ` | `String.join("; ", changes)` | `SchemaDifferTest#columnTypeChangeIsModifiedAndBreaking` |
| FR-25 | Тип и `DEFAULT` **должны** нормализоваться по пробелам; тип — ещё и по регистру | `normalizeType`, `normalizeDefault` | `SchemaDifferTest#identicalSnapshotsProduceNoEntries` |
| FR-26 | Констрейнт **должен** сравниваться по определению и отдельно по хвосту флагов | `constraintChanges`, `ConstraintDef.flagsClause` | `SchemaDifferTest#constraintOptionChangeWithoutDefinitionChangeIsDetected` |
| FR-27 | Sequence **должен** сравниваться по составу параметров целиком, без `last_value` | `diffSequences` (равенство записей), `SequenceDef` | `SchemaDifferTest#sequenceParameterChangeIsDetected` |
| FR-28 | Изменение комментария **должно** быть отдельной записью типа `COMMENT` | `diffComment` | 4 теста `SchemaDifferTest` |
| FR-29 | Отсутствие комментария и пустой комментарий **должны** считаться равными | `normalizeComment` | `SchemaDifferTest#identicalCommentsProduceNoEntries` |
| FR-30 | Состав записей отчёта **должен** быть детерминированным между прогонами | фиксированный порядок секций + `ORDER BY` в SQL + `LinkedHashMap` | `SchemaDifferTest#indexChangesAreDetected` (стабильность), IT |
| FR-31 | Результат **должен** быть неизменяемым | `List.copyOf(entries)` | — |

### 1.3 Классификация

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-40 | Каждая запись **должна** получать severity по типу объекта и типу изменения | `SeverityClassifier.classify` | весь `SeverityClassifierTest` |
| FR-41 | Любое удаление (кроме комментариев) **должно** быть `BREAKING` | `case REMOVED` | `SeverityClassifierTest#everyRemovalBreaksSomethingApplicationsRelyOn` |
| FR-42 | Любое добавление (кроме комментариев) **должно** быть `NON_BREAKING` | `case ADDED` | `#additionsAreNonBreaking` |
| FR-43 | Изменение колонки **должно** быть `BREAKING`, изменение констрейнта/индекса/sequence — `NON_BREAKING` | `case MODIFIED` | `#onlyColumnModificationIsBreaking` |
| FR-44 | Все изменения комментариев **должны** быть `INFO` | ветка `ObjectType.COMMENT` | `#commentChangesAreInformational` |

### 1.4 Генерация миграционного DDL

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-50 | Генератор **должен** выдавать операторы в порядке: sequence → таблицы → колонки → комментарии → констрейнты → индексы | `DdlGenerator.generate` | `#orderIsSequencesTablesColumnsConstraintsIndexes` |
| FR-51 | Все снятия констрейнтов **должны** предшествовать всем добавлениям | `constraints` (списки `removed`/`modified` эмитятся до `emitAddConstraints`) | `#allConstraintDropsPrecedeAllConstraintRecreates` |
| FR-52 | Внутри добавлений не-FK **должны** идти раньше FK | два вызова `emitAddConstraints(…, false/true)` | IT (`reports_doc_fk` ссылается на пересозданный `docs_pkey`) |
| FR-53 | Снятие таблицы **должно** подавлять её колонки, констрейнты, индексы и комментарии | `droppedTables` | `#droppedTableSkipsItsConstraintsIndexesAndColumns`, `#commentOnDroppedTableIsSkipped` |
| FR-54 | Снятие колонки **должно** подавлять явно дропаемые констрейнты и индексы, которые её используют | `droppedColumnsByTable`, `mentionsAnyColumn` | `#droppedColumnSkipsDependentConstraintAndIndexDrops` |
| FR-55 | `CREATE TABLE` **должен** переносить тип, identity, generated, NOT NULL и `DEFAULT` колонок | `createTable`, `columnBody` | `#addedTableRendersIdentityGenerationAndQualifiedSequenceDefault` |
| FR-56 | Изменение generated-колонки **должно** реализовываться как `DROP COLUMN` + `ADD COLUMN` | `emitColumnAlter` (ранний `return`) | `#changedGenerationRecreatesTheDerivedColumn` |
| FR-57 | Изменение типа / nullability / дефолта / identity **должны** эмититься отдельными `ALTER COLUMN` | `emitColumnAlter` | `#modifiedColumnGeneratesAlterStatements`, `#identityKindChangeOnlyReissuesSetGenerated` |
| FR-58 | Снятый identity **должен** сопровождаться предупреждением о оставшейся sequence | комментарий `DROP IDENTITY IF EXISTS` | `#removedIdentityDropsIdentityFromColumn` |
| FR-59 | Изменённый констрейнт **должен** пересоздаваться из целевого определения | `constraints` + `inlineDefinition` | `#modifiedConstraintIsDroppedAndRecreatedFromTargetDefinition` |
| FR-60 | Тело констрейнта **должно** генерироваться инлайн-формой, а не `USING INDEX` | `inlineDefinition` | `#addedPrimaryKeyGeneratesInlineDefinition` |
| FR-61 | `MATCH` / `ON DELETE` / `ON UPDATE` **должны** переноситься дословно из определения | `foreignKeyTail` + `FK_HEAD_PATTERN` | `#addedForeignKeyKeepsMatchClauseAndColumnarDelete` |
| FR-62 | Флаги констрейнта **должны** дописываться в `ADD CONSTRAINT` | `c.flagsClause()` в `emitAddConstraints` | `#constraintOptionsAreRenderedOnAdd` |
| FR-63 | CHECK и EXCLUDE **должны** восстанавливаться из канонического определения, а при его отсутствии — останавливать генерацию с явной ошибкой | `requireDefinition` | `#addedCheckConstraintKeepsCanonicalDefinition`, `#exclusionConstraintIsRenderedFromItsCanonicalDefinition` |
| FR-64 | Имена идентификаторов **должны** экранироваться; имена схем и таблиц — квалифицироваться схемой источника | `q`, `qualify` | почти все тесты генератора |
| FR-65 | `regclass`-ссылки в дефолтах **должны** получать квалификацию схемы источника; уже квалифицированные — оставаться как есть | `qualifyDefault`, `REGCLASS_LITERAL_PATTERN` | `#newSequenceDefaultIsQualifiedWithTheSourceSchema`, `#crossSchemaSequenceDefaultIsLeftUntouched` |
| FR-66 | В `CREATE INDEX` **должно** возвращаться имя схемы, остальная часть определения — не меняться | `qualifyIndexTable` | `#addedIndexUsesCanonicalDefinitionWithSourceSchema` |
| FR-67 | Изменение индекса **должно** давать пару `DROP INDEX` + `CREATE INDEX` | `indexes` (ветка `MODIFIED`) | `#modifiedIndexGeneratesDropAndRecreate` |
| FR-68 | `ALTER SEQUENCE` **должен** содержать только реально изменившиеся параметры | `sequences` | `#addedSequenceGeneratesCreate` + IT (`doc_seq`) |
| FR-69 | Изменённые комментарии **должны** давать `COMMENT ON … IS '…'`, снятые — `IS NULL` | `comments`, `commentLiteral` | `#commentChangesGenerateCommentOnStatements`, `#removedCommentGeneratesCommentIsNull` |
| FR-70 | Одинарные кавычки в комментарии **должны** экранироваться удвоением | `commentLiteral` | `#commentLiteralsEscapeSingleQuotes` |
| FR-71 | Небезопасные или требующие решения оператора **должны** сопровождаться служебным комментарием (`BREAKING:` / `review:`) | `DdlStatement.commented` | `#removedTableGeneratesBreakingDrop`, `#addedNotNullColumnGetsReviewComment` |
| FR-72 | Миграция, применённая к источнику, **должна** делать схемы идентичными | весь `DdlGenerator` | `SchemaRoundTripIT#applyingGeneratedMigrationMakesSchemasIdentical` |
| FR-73 | После применения миграции повторный дифф **должен** быть пуст, а повторная генерация — не давать операторов | идемпотентность | `SchemaRoundTripIT#generatedMigrationIsIdempotent` |

### 1.5 Вывод

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-80 | **Должен** печататься человекочитаемый итог: сравниваемые схемы, счётчики, записями по таблицам | `ConsoleSummaryPrinter` | ревью вывода |
| FR-81 | **Должен** писаться машиночитаемый JSON-отчёт с severity и определениями до/после | `JsonReportWriter` | `SchemaRoundTripIT`-фикстуры + артефакт прогона |
| FR-82 | По запросу (`--ddl`) **должен** писаться исполняемый `.sql`-скрипт с шапкой | `SqlScriptWriter` | `#wrapsStatementsInOneTransaction` |
| FR-83 | Родительские каталоги выходных файлов **должны** создаваться автоматически | `Files.createDirectories(parent)` в обоих райтерах | — |
| FR-84 | Файлы **должны** писаться в UTF-8 и перезаписываться без предупреждения | `StandardCharsets.UTF_8`, `Files.writeString` | — |
| FR-85 | Пустой дифф **должен** давать валидный скрипт с пояснением и без `BEGIN`/`COMMIT` | ветка `statements.isEmpty()` | `#identicalSchemasProduceAnEmptyNonTransactionalScript` |
| FR-86 | Скрипт по умолчанию **должен** быть обёрнут в одну транзакцию; `--no-transaction` **должен** добавлять в шапку предупреждение о частичном применении | `transactional` | `#wrapsStatementsInOneTransaction`, `#warnsWhenTheTransactionWrapperIsDisabled` |
| FR-87 | Служебный комментарий **должен** стоять строкой над своим оператором | цикл записи в `SqlScriptWriter` | `#perStatementReviewNotesPrecedeTheirStatement` |

### 1.6 Интерфейс командной строки

| № | Требование | Реализация | Проверка |
|---|---|---|---|
| FR-90 | **Должны** задаваться URL, пользователь и пароль обеих сторон; без них запуск не имеет смысла (`required = true` для URL и пользователя) | `CompareCommand` | `--help` |
| FR-91 | Пароль **должен** браться из переменной окружения либо запрашиваться интерактивно; обязательным не быть | `interactive`, `arity = "0..1"`, `defaultValue = "${env:…}"` | `--help`, прогон |
| FR-92 | Имя схемы **должно** задаваться одним флагом для обеих сторон и переопределяться раздельно | `--schema`, `--source-schema`, `--target-schema` | `--help`, прогон `run_compare.cmd` |
| FR-93 | Стандартные `--help` и `--version` **должны** быть доступны | `mixinStandardHelpOptions` | `java -jar … --help` |
| FR-94 | Код возврата **должен** различать: нет различий (0), различия есть (1), ошибка (2) | `call()` + `ExitCodeGenerator` | прогон, CI |
| FR-95 | Сообщения об ошибках **должны** попадать в stderr, сводка — в stdout | `System.err` / `System.out` | прогон |

---

## 2. Нефункциональные требования

| № | Категория | Требование | Реализация / подтверждение |
|---|---|---|---|
| NFR-01 | Производительность | Сравнение и генерация для схем порядка тысяч объектов **должны** занимать доли секунды; время определяется в основном чтением каталога | один запрос на вид объектов (FR-01…FR-14), O(n) сравнение |
| NFR-02 | Ресурсы | Утилита **не должна** использовать пул соединений и держать соединения открытыми дольше вычитывания | `ConnectionProvider` (без пула), try-with-resources |
| NFR-03 | Память | Потребление **должно** быть линейным по размеру двух схем | два снимка в памяти (§5.1 архитектуры) |
| NFR-04 | Безопасность | Работа с базами **должна** быть только читающей; изменение схемы — исключительно ручным запуском скрипта | `setReadOnly(true)`, скрипт не исполняется приложением |
| NFR-05 | Безопасность | Пароли **не должны** обязываться быть аргументом командной строки (видимость в истории и списке процессов) | env-переменные и интерактивный запрос (FR-91) |
| NFR-06 | Надёжность | Сбой на одной стороне или на записи файла **не должен** оставлять полузаписанный отчёт без кода ошибки | единый `CompareException` → код 2 |
| NFR-07 | Воспроизводимость | Повторный запуск на неизменённых базах **должен** давать идентичный набор записей (кроме штампа времени в шапке `.sql`) | фиксированные `ORDER BY`, детерминированный порядок секций |
| NFR-08 | Переносимость | Продукт — один исполняемый jar, без установки и внешних рантаймов, кроме JRE 25 | `spring-boot-maven-plugin`, `repackage` |
| NFR-09 | Портируемость БД | Поддерживаться должен PostgreSQL ≥ 12; проверено на 17 | §8 архитектуры |
| NFR-10 | Локализуемость | Вывод для человека (консоль, `--help`, ошибки) — русский; содержимое артефактов — английский, без переключения языковых пакетов | `docs/README.md`, §5.9 архитектуры |
| NFR-11 | Наблюдаемость | Непокрытые сравнением объекты и пропущенные констрейнты **должны** быть видны в логе `WARN` при `logging.level.root = warn` | FR-16, FR-17 |
| NFR-12 | Тестируемость | Ядро (`diff`, `ddl`) **должно** тестироваться без БД, контейнеров и контекста Spring | `config.AppConfig` собирает бины; 65 unit-тестов |
| NFR-13 | Покрытие изменений тестами | Каждая ветка генерации **должна** иметь unit-тест, а сквозной сценарий — интеграционный round-trip | 39 тестов генератора + `SchemaRoundTripIT` |
| NFR-14 | Сопровождаемость | Неочевидные решения каталога и порядка **должны** быть объяснены в javadoc на русском | весь основной код |
| NFR-15 | Простота расширений | Добавление нового типа объекта **должно** требовать правки в одном месте на слой (модель → экстрактор → дифф → генератор → тесты) | §10 архитектуры |
| NFR-16 | Обратная совместимость вывода | Изменение текстов в артефактах — только осознанно: на них завязаны сравнения отчётов и тесты | политика локализации (NFR-10) |

---

## 3. Ограничения и допущения

| № | Ограничение / допущение |
|---|---|
| C-01 | Представления, materialized views, секционированные и foreign-таблицы в сравнение не входят (только сигнал в логе) |
| C-02 | Параметры sequence за identity-колонкой (`START WITH`, `INCREMENT BY`) не мигрируются: генерируется `GENERATED … AS IDENTITY` со значениями по умолчанию |
| C-03 | Для `serial`-колонок восстанавливается `nextval(…)`, но не связь `OWNED BY` |
| C-04 | Переименования не угадываются: это `DROP` + `ADD` с severity по таблице/колонке |
| C-05 | `ADD COLUMN … NOT NULL` без `DEFAULT` на непустой таблице требует ручного решения — только пометка `review:` |
| C-06 | Смена типа колонки может требовать `USING` — генератор выдаёт оператор без `USING` и просит ревью |
| C-07 | `SET NOT NULL` требует предварительного бэкфилла данных; автогенерация бэкфилла не предусмотрена |
| C-08 | `DROP CONSTRAINT` не выполнится, если на констрейнт ссылается FK или представление; в скрипте это помечено, обход — вручную |
| C-09 | Хранимые процедуры, триггеры, гранты/роли, tablespaces, collations, domains, types, extensions, row-level security — вне области |
| C-10 | Утилита не исполняет миграцию: она только пишет `.sql` |
| C-11 | Одна схема на сторону: сравнение сразу нескольких схем одной базы за прогон не поддерживается (нужно несколько запусков) |
| C-12 | Обе стороны предполагаются на одном сервере или доступными из одного процесса по JDBC; распределённых транзакций нет (и не нужно — чтение) |
| A-01 | Допущение: имена объектов различаются только регистром — редкость, и они сознательно считаются совпадающими |
| A-02 | Допущение: состояние баз не меняется между двумя вычитываниями одного прогона |

---

## 4. Матрица трассировки (сводно)

| Требование | Место в коде | Основной тест |
|---|---|---|
| FR-01…FR-17 (чтение) | `extractor/` | `SchemaRoundTripIT` |
| FR-20…FR-31 (сравнение) | `diff/SchemaDiffer` | `SchemaDifferTest` |
| FR-40…FR-44 (классификация) | `diff/SeverityClassifier` | `SeverityClassifierTest` |
| FR-50…FR-73 (генерация) | `ddl/DdlGenerator` | `DdlGeneratorTest`, `SchemaRoundTripIT` |
| FR-80…FR-87 (вывод) | `report/` | `SqlScriptWriterTest`, фактические артефакты прогона |
| FR-90…FR-95 (CLI) | `cli/CompareCommand`, `PgCompareApplication` | `--help`, коды возврата в CI |
| NFR-12, NFR-13 | `config/AppConfig`, чистое ядро | `mvn test` без Docker |
| C-02…C-08 | задокументированы здесь и в `SPECIFICATION.md` §6.5, §10 | пометки `review:` в скрипте |
