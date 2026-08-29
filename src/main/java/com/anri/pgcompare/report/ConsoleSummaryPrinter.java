package com.anri.pgcompare.report;

import com.anri.pgcompare.diff.ChangeType;
import com.anri.pgcompare.diff.DiffEntry;
import com.anri.pgcompare.diff.SchemaDiff;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Human-readable summary on stdout.
 */
@Component
public class ConsoleSummaryPrinter {

    public void print(SchemaDiff diff) {
        System.out.printf("Comparing schema '%s' -> '%s'%n", diff.sourceSchema(), diff.targetSchema());
        if (diff.isEmpty()) {
            System.out.println("Schemas are identical.");
            return;
        }
        Map<ChangeType, Long> byType = diff.entries().stream()
                .collect(Collectors.groupingBy(DiffEntry::changeType, Collectors.counting()));
        System.out.printf("Found %d difference(s): %d added, %d removed, %d modified%n%n",
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
