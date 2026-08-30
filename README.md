# pg-compare

CLI-утилита для сравнения схем двух баз PostgreSQL. Строит JSON-отчёт с различиями,
генерирует миграционный DDL-скрипт и печатает сводку в консоль.

Стек: Java 25, Spring Boot 4, Lombok, picocli, JDBC (pg_catalog).

## Возможности

- Сравнение таблиц, колонок (тип, NULL/NOT NULL, DEFAULT, `GENERATED ... AS IDENTITY`,
  `GENERATED ALWAYS AS (...) STORED`), констрейнтов (PK/FK/UNIQUE/CHECK/EXCLUDE — включая
  referential actions `ON DELETE`/`ON UPDATE`, `MATCH`, `DEFERRABLE`, `INITIALLY DEFERRED`,
  `NOT VALID`), индексов, sequences и комментариев (COMMENT ON TABLE/COLUMN)
- JSON-отчёт (`report.json`) с классификацией каждого изменения: `ADDED` / `REMOVED` / `MODIFIED`
  и severity `BREAKING` / `NON_BREAKING` / `INFO`
- Генерация миграционного SQL с корректным порядком зависимостей
  (sequences → таблицы → колонки → констрейнты, FK в конце → индексы)
- Учёт каскадов PostgreSQL: при `DROP COLUMN` зависимые индексы/констрейнты не дропаются явно;
  при `DROP TABLE` её объекты пропускаются
- Комментарии таблиц и колонок попадают в отчёт отдельными `COMMENT`-записями и в миграцию
  как `COMMENT ON TABLE/COLUMN ... IS '...'` (снятие комментария — `IS NULL`)
- Exit codes для CI: `0` — схемы идентичны, `1` — есть различия, `2` — ошибка

## Сборка

```bash
mvn clean package
```

## Использование

```bash
java -jar target/pg-compare-0.1.0-SNAPSHOT.jar \
  --source-url=jdbc:postgresql://host1:5432/db1 --source-user=user1 \
  --target-url=jdbc:postgresql://host2:5432/db2 --target-user=user2 \
  --schema=public \
  --out=report.json \
  --ddl=migration.sql
```

Пароли запрашиваются интерактивно, либо передаются через переменные окружения:

```bash
export PGCOMPARE_SOURCE_PASSWORD=...
export PGCOMPARE_TARGET_PASSWORD=...
```

### Сравнение двух схем в одной базе

```bash
java -jar pg-compare.jar \
  --source-url=jdbc:postgresql://localhost:5432/mydb --source-user=postgres \
  --target-url=jdbc:postgresql://localhost:5432/mydb --target-user=postgres \
  --source-schema=app_v1 --target-schema=app_v2
```

### Опции

| Опция | Описание | По умолчанию |
|---|---|---|
| `--source-url`, `--source-user`, `--source-password` | Подключение к БД-источнику (состояние «как есть») | обязательны |
| `--target-url`, `--target-user`, `--target-password` | Подключение к целевой БД (желаемое состояние) | обязательны |
| `--schema` | Имя схемы на обеих сторонах | `public` |
| `--source-schema` / `--target-schema` | Переопределение схемы для одной стороны | — |
| `--out` | Файл JSON-отчёта | `report.json` |
| `--ddl` | Файл миграционного SQL-скрипта | не генерируется |
| `--transaction` / `--no-transaction` | Обернуть сгенерированный скрипт в `BEGIN`/`COMMIT` | `--transaction` |

## Миграционный DDL

Скрипт предназначен для применения к БД-источнику. Небезопасные операции помечены
комментариями:

```sql
-- BREAKING: drops all data in the table
DROP TABLE "app"."legacy_log";

-- review: implicit cast may fail, consider a USING clause
ALTER TABLE "app"."users" ALTER COLUMN "email" TYPE character varying(255);
```

Перед выполнением reviewing обязателен: смена типа колонки может требовать `USING`,
`SET NOT NULL` — предварительного бэкфилла данных.

По умолчанию весь скрипт обёрнут в одну транзакцию (`BEGIN`/`COMMIT`), поэтому упавшая на
полпути миграция не оставляет схему в частично изменённом состоянии. Для операций, которые
в транзакции невозможны, есть `--no-transaction` — в этом случае в шапке скрипта остаётся
предупреждение о частичном применении. Запускать скрипт рекомендуется через `psql`
с `--set ON_ERROR_STOP=1`, иначе `psql` пойдёт дальше после первой ошибки.

### Severity

Каждая запись отчёта получает severity по объекту и типу изменения:

| Severity | Когда |
|---|---|
| `BREAKING` | любое удаление объекта (данные, гарантия уникальности для `ON CONFLICT`, referential action, план запроса) и любое изменение колонки |
| `NON_BREAKING` | добавление объекта, переопределение констрейнта/индекса/sequence на месте |
| `INFO` | изменения комментариев — на работу приложения не влияют |

Severity — это сигнал для ревью, а не разрешение на автоматическое применение: `ADD COLUMN
... NOT NULL` без `DEFAULT` помечается `NON_BREAKING`, но на непустой таблице требует
отдельного решения (в скрипте — комментарий `review:`).

## Архитектура

```
cli/           — picocli-команда, опции, оркестрация
config/        — сборка чистого ядра (diff/ddl) бинами Spring
connection/    — одноразовые JDBC-подключения (без пула)
extractor/     — чтение pg_catalog → иммутабельный SchemaSnapshot
model/         — records: TableDef, ColumnDef (+ IdentityKind, ColumnGeneration),
                 ConstraintDef, IndexDef, SequenceDef
diff/          — чистое сравнение snapshot'ов в памяти + классификация severity
ddl/           — SchemaDiff → миграционный SQL (порядок зависимостей, каскады)
report/        — JSON-отчёт, SQL-скрипт, консольная сводка
```

Ключевые решения:

- **Snapshot-подход**: обе схемы полностью вычитываются в память, сравнение — чистая
  функция без I/O (unit-тестируется без БД)
- **pg_catalog вместо DatabaseMetaData**: полная картина (default-выражения, identity и
  generated колонки, canonical определения индексов через `pg_get_indexdef`). Отдельная
  часть — флаги констрейнтов (`DEFERRABLE`, `INITIALLY DEFERRED`, `NOT VALID`) и referential
  actions: `pg_get_constraintdef` их либо не печатает вовсе, либо печатает только в тексте
  определения, поэтому deferrability читается из каталога, а «хвост» FK переносится из
  определения дословно
- **Нормализация определений**: префикс собственной схемы срезается, поэтому сравнение
  схем с разными именами не даёт фантомных различий; DDL-генератор обратно
  квалифицирует имена схемой источника (таблицы констрейнтов и индексов, ссылки
  `nextval('seq'::regclass)` в дефолтах)
- **Identity-последовательности не сравниваются отдельно**: они помечены внутренней
  зависимостью в `pg_depend` и являются частью определения колонки

## Известные ограничения

- Представления, materialized views, секционированные и foreign таблицы в сравнение
  не входят; их количество в схеме выводится в `WARN` в лог, чтобы отсутствие различий
  не выглядело как «расхождений нет»
- Параметры sequence за identity-колонкой (`START WITH`, `INCREMENT BY`) не мигрируются:
  генерируется `GENERATED ... AS IDENTITY` со значениями по умолчанию
- У `serial`-колонок восстанавливается дефолт `nextval(...)`, но не связь `OWNED BY`
  sequence с колонкой
- Переименования не угадываются: `DROP` + `ADD` с соответствующим severity
- `ADD COLUMN ... NOT NULL` без `DEFAULT` на непустой таблице требует ручного решения
  (помечается `review:`)

## Разработка

```bash
mvn test          # unit-тесты differ, ddl-generator, классификатора severity, скрипт-райтера
mvn verify        # + интеграционные тесты (нужен Docker)
mvn package       # сборка jar
```

Интеграционные тесты (`*IT`) проверяют полный цикл на реальном PostgreSQL: две схемы
создаются из DDL-фикстур, генерируется миграция, применяется к схеме-источнику, и
повторный дифф обязан быть пустым. Если Docker недоступен (или testcontainers до него
дотянуться не может), можно указать внешнюю базу:

```bash
export PGCOMPARE_IT_URL=jdbc:postgresql://localhost:5432/test
export PGCOMPARE_IT_USER=postgres      # опционально, по умолчанию postgres
export PGCOMPARE_IT_PASSWORD=postgres  # опционально, по умолчанию postgres
mvn verify
```
