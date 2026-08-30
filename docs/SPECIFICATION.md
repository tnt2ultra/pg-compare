# Спецификация поведения pg-compare

Документ описывает **внешне наблюдаемое поведение** утилиты: что она сравнивает, по каким
правилам, что именно попадает в каждый из трёх выходов и как ведёт себя на ошибках.
Происхождение — постфактум, по реализации и тестам.

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — как это устроено внутри;
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — те же правила в форме проверяемых требований;
- [`USER_GUIDE.md`](USER_GUIDE.md) — как этим пользоваться.

---

## 1. Область сравнения

### 1.1 Что сравнивается

| Объект | Источник в каталоге | Сравниваемые атрибуты |
|---|---|---|
| Таблица | `pg_class` (`relkind = 'r'`) + `pg_description` (`objsubid = 0`) | наличие, комментарий |
| Колонка | `pg_attribute` (`attnum > 0`, `NOT attisdropped`), тип — `format_type` | тип, `NULL`/`NOT NULL`, выражение `DEFAULT`, identity, generated-выражение и способ/materialization, комментарий |
| Констрейнт | `pg_constraint`, определение — `pg_get_constraintdef` | каноническое определение, флаги `NOT VALID` / `DEFERRABLE` / `INITIALLY DEFERRED` |
| Индекс | `pg_index`, определение — `pg_get_indexdef` | каноническое определение целиком |
| Sequence | `pg_sequence` + `pg_class` | `START WITH`, `INCREMENT BY`, `MINVALUE`, `MAXVALUE` |

Виды констрейнтов: `PRIMARY_KEY` (`contype = 'p'`), `FOREIGN_KEY` (`'f'`), `UNIQUE` (`'u'`),
`CHECK` (`'c'`), `EXCLUSION` (`'x'`).

### 1.2 Что не сравнивается

| Не покрывается | Как система себя ведёт |
|---|---|
| Представления (`relkind = 'v'`), materialized views (`'m'`), секционированные таблицы (`'p'`), foreign-таблицы (`'f'`) | Количество по каждому типу выводится в `log.warn` при чтении схемы, чтобы пустой дифф не читался как «расхождений нет» |
| Индексы, обслуживающие PK/UNIQUE-констрейнты | Исключаются запросом (`NOT EXISTS` по `pg_constraint.conindid`): они уже представлены констрейнтом |
| Sequence за identity-колонками | Исключаются запросом (`pg_depend`, `deptype = 'i'`): они часть определения колонки |
| Текущее значение sequence (`last_value`) | В снимок не входит: сравниваются только параметры определения, а не наработка счётчика |
| Констрейнты `TRIGGER` (`contype = 't'`) и политик row-level security (`'r'`) | Помечаются в `log.warn` и **не попадают** в результат; сравнение остальной схемы продолжается |
| Комментарии уровня схемы/БД, домены, типа enum, функции, триггеры, гранты, tablespaces, collations, extensions | Не читаются вообще |
| Переименования | Не угадываются: дают пару `REMOVED` + `ADDED` |

---

## 2. Направление сравнения

Направление фиксировано и определяет смысл всех записей:

```
source (мигрируется, «как есть»)  ──diff──▶  target (желаемое состояние)
```

| Запись | Означает | Действие в миграции |
|---|---|---|
| `ADDED` | объект есть только в цели | создать |
| `REMOVED` | объект есть только в источнике | удалить |
| `MODIFIED` | объект есть с обеих сторон, определения расходятся | изменить (иногда через снятие + пересоздание) |

---

## 3. Правила сопоставления и равенства

### 3.1 Сопоставление «это тот же объект»

- Ключ — имя в **нижнем регистре**. `Users` и `users` считаются одним объектом, потому что
  PostgreSQL хранит неэкранированные идентификаторы в нижнем регистре.
- Родительские объекты (`TABLE`, `SEQUENCE`) — по имени.
- Дочерние — по паре «родитель.имя»: `COLUMN` → `таблица.колонка`, `CONSTRAINT` и `INDEX` →
  `таблица.имя`. Это существенно: имя констрейнта или индекса уникально в схеме, а не в таблице,
  поэтому без имени таблицы один и тот же объект на разных таблицах дал бы ложное совпадение.

### 3.2 Равенство атрибутов

| Атрибут | Нормализация перед сравнением | Следствие |
|---|---|---|
| Тип колонки | схлопывание последовательностей пробелов, `trim`, нижний регистр | `character varying(255)` ≡ `CHARACTER VARYING(255)  ` |
| `DEFAULT` | схлопывание пробелов и `trim`; `null` ≡ «нет дефолта», **регистр сохраняется** | разный перенос строк в выражении не даёт фантомного различия, а `now()` ≠ `NOW()` |
| Identity | сравнение enum-значения (`ALWAYS` / `BY_DEFAULT` / отсутствие) | смена вида — это `MODIFIED` |
| Generated | сравнение пары «выражение + вид» (`STORED` / `VIRTUAL`) | изменение выражения — `MODIFIED` |
| Констрейнт | каноническое определение **плюс** отдельно хвост флагов | различие только по `DEFERRABLE` тоже попадает в отчёт |
| Индекс | каноническое определение целиком | метод, состав, порядок, предикат — всё внутри одной строки |
| Sequence | равенство всей записи | любое из четырёх параметров |
| Комментарий | `trim`; `null` ≡ пустая строка | снятие комментария — обычное `MODIFIED` |

### 3.3 Нормализация определений (защита от фантомов)

Определения, отрендеренные сервером (`pg_get_constraintdef`, `pg_get_indexdef`, дефолты из
`pg_get_expr`), содержат имя схемы. Если его не убрать, сравнение `app_v1` и `app_v2`
считало бы отличающимся **каждый** объект. Поэтому:

- префикс **собственной** сравниваемой схемы срезается — в открытом виде (`schema.name`) и в
  кавычках (`"schema".name`), регистр имени схемы не учитывается;
- квалификация **чужими** схемами сохраняется: ссылка на `shared.status` остаётся видимой;
- внутри regclass-литералов дефолтов срезается префикс в одинарных кавычках:
  `nextval('tgt.report_seq'::regclass)` → `nextval('report_seq'::regclass)`.

Итоговое правило: **определение в отчёте не содержит имени сравниваемой схемы**. Генератор DDL
возвращает квалификацию на место (см. §6.4).

### 3.4 Состав записи `MODIFIED`

У колонки атрибуты сравниваются независимо, и одно изменение может перечислить несколько причин
через `; `:

```
type changed: character varying(100) -> character varying(255); became NOT NULL
```

Комментарии — **отдельные записи** (`objectType = COMMENT`), а не часть записи о колонке или
таблице. Причина: смена документации не должна смешиваться со структурным изменением при ревью.

---

## 4. Классификация severity

| Тип изменения | Таблица / констрейнт / индекс / sequence | Колонка | Комментарий |
|---|---|---|---|
| `ADDED` | `NON_BREAKING` | `NON_BREAKING` | `INFO` |
| `REMOVED` | `BREAKING` | `BREAKING` | `INFO` |
| `MODIFIED` | `NON_BREAKING` | `BREAKING` | `INFO` |

Обоснование:

- любое удаление забирает то, на что приложения опираются: данные, гарантию уникальности
  (нужна для `ON CONFLICT`), referential action, план запроса;
- изменение колонки переписывает данные, которые уже читают;
- переопределение констрейнта или индекса оставляет объект на месте;
- комментарий на работу приложения не влияет.

**`severity` — сигнал для ревью, а не разрешение применять автоматически.** Пример:
`ADD COLUMN ... NOT NULL` без `DEFAULT` получает `NON_BREAKING`, но на непустой таблице требует
отдельного решения (в скрипте — комментарий `review:`).

---

## 5. Формат JSON-отчёта

Корень — снимок результата сравнения:

```json
{
  "sourceSchema" : "src",
  "targetSchema" : "tgt",
  "entries" : [ /* см. ниже */ ]
}
```

Каждая запись (`entries[i]`):

| Поле | Тип | Содержание |
|---|---|---|
| `objectType` | строка-enum | `TABLE`, `COLUMN`, `CONSTRAINT`, `INDEX`, `SEQUENCE`, `COMMENT` |
| `objectName` | строка | имя объекта; у дочерних — `таблица.объект`; у `COMMENT` — владелец комментария |
| `changeType` | строка-enum | `ADDED`, `REMOVED`, `MODIFIED` |
| `severity` | строка-enum | `BREAKING`, `NON_BREAKING`, `INFO` |
| `description` | строка | человекочитаемое описание, английский (см. §5.1) |
| `before` | объект или `null` | определение со стороны источника; `null` при `ADDED` |
| `after` | объект или `null` | определение со стороны цели; `null` при `REMOVED` |

`before`/`after` — не плоские значения, а **полные определения** объектов ( records модели:
`TableDef`, `ColumnDef`, `ConstraintDef`, `IndexDef`, `SequenceDef`), поэтому запись самодостаточна:
по ней видно всё состояние объекта, а не только изменённый атрибут. Для `COMMENT`-записей это
определения таблицы/колонки-владельца.

```json
{
  "objectType" : "CONSTRAINT",
  "objectName" : "users.users_email_key",
  "changeType" : "MODIFIED",
  "severity" : "NON_BREAKING",
  "description" : "definition changed: UNIQUE (email) -> UNIQUE (email, phone); options changed: none -> DEFERRABLE INITIALLY DEFERRED",
  "before" : {
    "name" : "users_email_key", "type" : "UNIQUE", "table" : "users",
    "columns" : [ "email" ], "referencedTable" : null, "referencedColumns" : null,
    "definition" : "UNIQUE (email)",
    "notValid" : false, "deferrable" : false, "initiallyDeferred" : false
  },
  "after" : {
    "name" : "users_email_key", "type" : "UNIQUE", "table" : "users",
    "columns" : [ "email", "phone" ], "referencedTable" : null, "referencedColumns" : null,
    "definition" : "UNIQUE (email, phone)",
    "notValid" : false, "deferrable" : true, "initiallyDeferred" : true
  }
}
```

### 5.1 Словарь `description`

Тексты стабильны (их сверяют тесты и потребляют диффы отчётов):

| Вид | Шаблон |
|---|---|
| Объект только в цели | `Table exists only in target` / `Column …` / `Constraint …` / `Index …` / `Sequence …` |
| Объект только в источнике | `Table exists only in source` и т. д. |
| Тип колонки | `type changed: <до> -> <после>` |
| Nullability | `became NOT NULL` / `became nullable` |
| Дефолт | `default changed: <до> -> <после>`, где отсутствие — `none` |
| Identity | `identity changed: none -> GENERATED ALWAYS AS IDENTITY` |
| Generated | `generation changed: GENERATED ALWAYS AS ((total * 2)) STORED -> GENERATED ALWAYS AS ((total * 3)) STORED` |
| Флаги констрейнта | `options changed: none -> DEFERRABLE INITIALLY DEFERRED` |
| Определение | `definition changed: <до> -> <после>` |
| Sequence | `sequence parameters changed` (без деталей: определение — вся запись) |
| Комментарий | `comment changed: '(no comment)' -> 'Primary key'` |

Несколько причин в одной записи склеиваются через `; `.

Прочие свойства формата: кодировка UTF-8, отступы включены (`INDENT_OUTPUT`), файл перезаписывается
целиком, родительские каталоги создаются. Порядок записей детерминирован: секции в фиксированном
порядке (таблицы и их колонки/комментарии → констрейнты → индексы → sequence), внутри секции —
по имени, как пришло из `ORDER BY`.

---

## 6. Формат миграционного SQL-скрипта

### 6.1 Шапка

```sql
-- Migration generated by pg-compare
-- Transforms schema 'src' into 'tgt'
-- Generated: 2026-08-30T18:19:47.390443800+03:00
-- Review BREAKING statements before executing.
-- Run with: psql --set ON_ERROR_STOP=1 -f <this file>
```

### 6.2 Порядок секций

```
1. sequence          CREATE SEQUENCE / ALTER SEQUENCE / DROP SEQUENCE
2. таблицы           CREATE TABLE (+ COMMENT ON TABLE), DROP TABLE
3. колонки           ADD COLUMN (+ COMMENT ON COLUMN), ALTER ..., DROP COLUMN
4. комментарии        COMMENT ON TABLE / COLUMN (изменённые)
5. констрейнты        сначала все DROP CONSTRAINT, затем ADD CONSTRAINT:
                       5a. не-FK (PRIMARY KEY, UNIQUE, CHECK, EXCLUDE)
                       5b. FOREIGN KEY
6. индексы           DROP INDEX, CREATE INDEX
```

Правила порядка и их причина:

- **сначала снятие, потом добавление** — новый констрейнт не должен падать на ещё живом старом;
- **не-FK раньше FK** — к моменту проверки ссылок опорные таблицы уже валидны;
- **sequence перед таблицами** — `nextval` в определении новой колонки должен находить
  последовательность;
- **таблицы перед колонками, всё кроме sequence — после таблиц** — объекты не могут появиться
  раньше владельца;
- **`COMMENT` после колонок** — комментировать можно только существующее.

### 6.3 Тело оператора

После каждого оператора — `;` и пустая строка. Служебный комментарий (если есть) стоит строкой
выше, непосредственно над своим оператором:

```sql
-- review: implicit cast may fail, consider a USING clause
ALTER TABLE "src"."users" ALTER COLUMN "email" TYPE character varying(255);
```

### 6.4 Как восстанавливаются имена

Из-за нормализации (§3.3) генератор возвращает квалификацию:

| Место | Правило |
|---|---|
| Таблица, констрейнт, `DROP INDEX` | `"схема"."объект"` — оба сегмента в двойных кавычках |
| Ссылка `REFERENCES` | если опорная таблица уже с префиксом схемы (чужая схема) — она **сохраняется** как есть и экранируется по сегментам; иначе добавляется схема источника |
| Дефолт `nextval('seq'::regclass)` | литерал получает `"<схема>"."<seq>"`; уже квалифицированный (`shared.seq`) не трогается |
| `CREATE INDEX` | после `ON` добавляется кавычка-имя схемы; **имя таблицы сохраняет то экранирование, которое дал `pg_get_indexdef`** (`ON "src".docs`) |
| Проверка/выражение (`CHECK`, generated) | переносятся дословно из каталога |
| Хвост FK (`MATCH`, `ON DELETE`, `ON UPDATE`) | переносится **дословно** из определения: `pg_get_constraintdef` печатает их только в тексте |
| Флаги констрейнта | `c.flagsClause()` дописывается в конец `ADD CONSTRAINT` |

Имена идентификаторов всегда экранируются; внутренняя двойная кавычка удваивается. Значение
комментария — SQL-строка с удвоением одинарных кавычек; снятие комментария — `IS NULL`.

### 6.5 Правила каскадов (защита от падающих операторов)

| Ситуация | Что эмитится |
|---|---|
| Таблица снимается целиком | её `COLUMN`/`CONSTRAINT`/`INDEX`/`COMMENT`-записи **пропускаются** |
| Колонка снимается | констрейнты, в список колонок которых она входит, и индексы, которые её упоминают (совпадение по границам слова), **не дропаются явно** |
| Меняется generated-колонка | `DROP COLUMN` + `ADD COLUMN` — выражение нельзя изменить на месте; данные не теряются, т. к. значение выводится из строки |
| Меняется состав констрейнта | `DROP CONSTRAINT` + `ADD CONSTRAINT` из целевого определения |
| Снимается identity | `DROP IDENTITY IF EXISTS`, с пометкой, что обслуживающая sequence остаётся в базе |
| `MODIFIED` sequence | `ALTER SEQUENCE` только с теми параметрами, которые действительно изменились |
| `ADD COLUMN ... NOT NULL` без `DEFAULT`, без identity и без generated | оператор эмитится, но с пометкой `review:` |

### 6.6 Транзакционная обёртка

По умолчанию (`--transaction`) всё между шапкой и `COMMIT;` обёрнуто:

```sql
-- One transaction: a failing statement leaves the schema untouched.
BEGIN;
...
COMMIT;
```

С `--no-transaction` в шапке остаётся предупреждение:

```sql
-- Not wrapped: a failing statement leaves a partially migrated schema.
```

При пустом диффе файл всё равно создаётся — с шапкой и строкой
`-- Schemas are identical, nothing to migrate.` без `BEGIN`/`COMMIT`.

### 6.7 Полный пример (фрагмент реального прогона)

```sql
-- Migration generated by pg-compare
-- Transforms schema 'src' into 'tgt'
-- Generated: 2026-08-30T18:19:47.390443800+03:00
-- Review BREAKING statements before executing.
-- Run with: psql --set ON_ERROR_STOP=1 -f <this file>

-- One transaction: a failing statement leaves the schema untouched.
BEGIN;

CREATE SEQUENCE "src"."report_seq" START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807;

ALTER SEQUENCE "src"."doc_seq" START WITH 100 INCREMENT BY 5;

-- BREAKING: sequence removal
DROP SEQUENCE "src"."orphan_seq";

CREATE TABLE "src"."reports" (
    "id" bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    "doc_seq_id" bigint DEFAULT nextval('"src"."report_seq"'::regclass),
    "title" text,
    "doc_id" bigint
);

COMMENT ON TABLE "src"."reports" IS 'generated reports';

-- BREAKING: drops all data in the table
DROP TABLE "src"."legacy_log";

-- BREAKING: drops column data
ALTER TABLE "src"."users" DROP COLUMN "status";

-- definition changed, re-added below; the drop fails if a foreign key or a view depends on this constraint
ALTER TABLE "src"."tags" DROP CONSTRAINT "tags_pkey";

ALTER TABLE "src"."tags" ADD CONSTRAINT "tags_pkey" PRIMARY KEY ("name", "slug");

ALTER TABLE "src"."reports" ADD CONSTRAINT "reports_doc_fk" FOREIGN KEY ("doc_id") REFERENCES "src"."docs" ("id") ON DELETE SET NULL;

CREATE INDEX docs_owner_idx ON "src".docs USING btree (owner_id);

COMMIT;
```

---

## 7. Консольный вывод

```
Сравниваем схемы 'src' -> 'tgt'
Найдено различий: 24 (добавлено 12, удалено 5, изменено 7)

[users]
  MODIFIED COLUMN     [BREAKING] type changed: character varying(100) -> character varying(255)
  REMOVED  COLUMN     [BREAKING] Column exists only in source
  ADDED    COLUMN     [NON_BREAKING] Column exists only in target
  MODIFIED COMMENT    [INFO]     comment changed: 'legacy users' -> 'application users'

[reports]
  ADDED    TABLE      [NON_BREAKING] Table exists only in target
```

- Первая строка — какие схемы сопоставляются (имена могут различаться).
- `Схемы идентичны.` — вместо счётчиков, если записей нет.
- Группировка по таблице-владельцу; для самостоятельных объектов (таблица, sequence) — по своему
  имени.
- Колонки строки: `изменение`, `тип объекта`, `[severity]`, `описание`. Выравнивание по ширине —
  из английских имён enum'ов, поэтому имена не локализованы.
- Ошибки пишутся в stderr: `ОШИБКА: <сообщение>` (ожидаемая) или
  `НЕОЖИДАННАЯ ОШИБКА: <класс и сообщение>` (неожиданная), после чего код возврата — 2.
- Служебные предупреждения сравнения (`log.warn`) идут через логгер Spring; по умолчанию уровень
  `warn`, то есть они видны в консоли.

---

## 8. Коды возврата

| Код | Смысл | Когда |
|---|---|---|
| `0` | схемы идентичны | записей диффа нет; отчёт и скрипт при этом всё равно записываются |
| `1` | различия есть | хотя бы одна запись; скрипт сгенерирован |
| `2` | ошибка | `CompareException` или неожиданное исключение; файлы могут не записаться |

Код возвращает picocli-команда, Spring переносит его в код процесса через `ExitCodeGenerator`.
`1` — штатный результат для CI («найти различия» — успех проверки), а не сбой.

---

## 9. Поведение при ошибках и предупреждениях

| Условие | Реакция | Выход |
|---|---|---|
| Указанной схемы нет в подключении | `CompareException: Схема '<имя>' не существует в этом подключении` | код 2, stderr `ОШИБКА: …` |
| База недоступна / неверные учётные данные | `CompareException: Не удалось подключиться к <url> под пользователем <user>: <причина>` | код 2 |
| Ошибка чтения каталога после успешного подключения | `CompareException: Не удалось вычитать схему из <url>: <причина>` | код 2 |
| Не удалось создать/записать JSON-отчёт | `CompareException: Не удалось записать JSON-отчёт в <путь>: <причина>` | код 2 |
| Не удалось записать `.sql` | `CompareException: Не удалось записать DDL-скрипт в <путь>: <причина>` | код 2 |
| `CHECK`/`EXCLUDE` без канонического определения | `CompareException: Не удалось сгенерировать DDL для констрейнта <вид> '<имя>': нет определения` | код 2; молча пропустить констрейнт = оставить схему расходящейся |
| Неизвестный `contype` | `log.warn: Пропускаем констрейнт '<имя>' в таблице '<таблица>' схемы <схема>: неподдерживаемый вид`, объект исключается из результата | сравнение продолжается |
| В схеме есть непокрытые отношения | `log.warn: Схема '<схема>': <подпись> вне зоны сравнения — <n> шт.` | сравнение продолжается |
| Неожиданное исключение (не `CompareException`) | stderr `НЕОЖИДАННАЯ ОШИБКА: …` | код 2 |

Порядок побочного вывода: консольная сводка пишется **до** отчётов; JSON пишется всегда, `.sql` —
только если задан `--ddl`.

---

## 10. Пограничные трактовки (зафиксированное поведение)

1. **Пустой комментарий ≡ отсутствие комментария.** Различия не будет.
2. **Разный перенос/пробелы в дефолте и типе — не различие**; разный регистр в дефолте — различие.
3. **`--schema` задаёт обе стороны**, `--source-schema` / `--target-schema` переопределяют
   свою сторону независимо; их можно задать только с одной стороны (`--schema` + `--source-schema`).
4. **Пароль можно не передавать**: тогда `arity = "0..1"` + `interactive` дают запрос в
   терминале, а `defaultValue` подхватывает переменную окружения.
5. **Подключения открываются только для чтения** (`setReadOnly(true)`), повторное использование —
   по одному соединению на сторону, закрываются сразу после вычитывания.
6. **Отсутствие таблицы в снимке ≠ ошибка**: пустая существующая схема даёт пустой дифф.
7. **`--ddl` не влияет на код возврата**: «есть различия» — это 1 даже без скрипта.
8. **Имя схемы в отчёте — две разные строки** (`sourceSchema` и `targetSchema`): сравнение схем с
   разными именами — штатный режим, а не частный случай.
9. **Скрипт идемпотентен по результату сравнения**: применённый, он даёт пустой повторный дифф
   (проверяется интеграционным тестом `generatedMigrationIsIdempotent`), но это не значит, что его
   можно безопасно применять к другой базе или повторно к изменённой.
