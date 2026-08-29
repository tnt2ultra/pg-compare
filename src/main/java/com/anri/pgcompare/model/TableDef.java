package com.anri.pgcompare.model;

import java.util.List;

public record TableDef(
        String name,
        List<ColumnDef> columns
) {
}
