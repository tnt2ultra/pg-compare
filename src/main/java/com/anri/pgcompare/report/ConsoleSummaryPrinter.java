package com.anri.pgcompare.report;

import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.SchemaDiff;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Удобочитаемая сводка в stdout — краткий вид того же диффа, что уходит в JSON-отчёт.
 * Группировка по таблице делает лог читаемым на схемах с сотнями колонок: сначала видно,
 * каких объектов касаются изменения, а внутри группы — тип, объект, severity и описание.
 */
@Component
public class ConsoleSummaryPrinter {

    /**
     * Печатает сводку: заголовок с сопоставляемыми схемами, счётчики по типам изменений
     * и записи, сгруппированные по таблице.
     *
     * @param diff результат сравнения схем
     */
    public void print(SchemaDiff diff) {
        System.out.printf("Сравниваем схемы '%s' -> '%s'%n", diff.sourceSchema(), diff.targetSchema());
        if (diff.isEmpty()) {
            System.out.println("Схемы идентичны.");
            return;
        }
        Map<ChangeType, Long> byType = diff.entries().stream()
                .collect(Collectors.groupingBy(DiffEntry::changeType, Collectors.counting()));
        System.out.printf("Найдено различий: %d (добавлено %d, удалено %d, изменено %d)%n%n",
                diff.entries().size(),
                byType.getOrDefault(ChangeType.ADDED, 0L),
                byType.getOrDefault(ChangeType.REMOVED, 0L),
                byType.getOrDefault(ChangeType.MODIFIED, 0L));

        Map<String, List<DiffEntry>> byTable = diff.entries().stream()
                .collect(Collectors.groupingBy(e -> e.objectName().contains(".")
                        ? e.objectName().substring(0, e.objectName().indexOf('.'))
                        : e.objectName()));
        for (var group : byTable.entrySet()) {
            System.out.println("[" + group.getKey() + "]");
            for (DiffEntry e : group.getValue()) {
                System.out.printf("  %-8s %-10s %-6s %s%n",
                        e.changeType(), e.objectType(), "[" + e.severity() + "]", e.description());
            }
            System.out.println();
        }
    }
}
