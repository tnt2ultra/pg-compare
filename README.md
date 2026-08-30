# pg-compare

CLI-утилита для сравнения схем двух баз PostgreSQL. Строит JSON-отчёт с различиями,
генерирует миграционный DDL-скрипт и печатает сводку в консоль.

Стек: Java 25, Spring Boot 4, Lombok, picocli, JDBC (pg_catalog).

## Возможности

- Сравнение таблиц, колонок (тип, NULL/NOT NULL, DEFAULT), констрейнтов (PK/FK/UNIQUE/CHECK),
  индексов, sequences и комментариев (COMMENT ON TABLE/COLUMN)
- JSON-отчёт (`report.json`) с классификацией каждого изменения: `ADDED` / `REMOVED` / `MODIFIED`
  и severity `BREAKING` / `NON_BREAKING`
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

## Архитектура

```
cli/           — picocli-команда, опции, оркестрация
connection/    — одноразовые JDBC-подключения (без пула)
extractor/     — чтение pg_catalog → иммутабельный SchemaSnapshot
model/         — records: TableDef, ColumnDef, ConstraintDef, IndexDef, SequenceDef
diff/          — чистое сравнение snapshot'ов в памяти + классификация severity
ddl/           — SchemaDiff → миграционный SQL (порядок зависимостей, каскады)
report/        — JSON-отчёт, SQL-скрипт, консольная сводка
```

Ключевые решения:

- **Snapshot-подход**: обе схемы полностью вычитываются в память, сравнение — чистая
  функция без I/O (unit-тестируется без БД)
- **pg_catalog вместо DatabaseMetaData**: полная картина (default-выражения, deferrable FK,
  canonical определения индексов через `pg_get_indexdef`)
- **Нормализация определений**: префикс собственной схемы срезается, поэтому сравнение
  схем с разными именами не даёт фантомных различий; DDL-генератор обратно
  квалифицирует имена схемой источника

## Разработка

```bash
mvn test          # unit-тесты differ и ddl-generator
mvn package       # сборка jar
```
