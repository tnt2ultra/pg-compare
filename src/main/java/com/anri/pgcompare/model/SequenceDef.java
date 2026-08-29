package com.anri.pgcompare.model;

public record SequenceDef(
        String name,
        long startValue,
        long increment,
        long minValue,
        long maxValue
) {
}
