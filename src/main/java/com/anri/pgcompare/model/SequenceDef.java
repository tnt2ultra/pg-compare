package com.anri.pgcompare.model;

/**
 * Параметры самостоятельно существующего sequence. Sequence, стоящие за identity-колонками,
 * в снимок не входят: они являются частью определения колонки.
 *
 * @param name       имя sequence
 * @param startValue стартовое значение
 * @param increment  шаг изменения
 * @param minValue   нижняя граница
 * @param maxValue   верхняя граница
 */
public record SequenceDef(
        String name,
        long startValue,
        long increment,
        long minValue,
        long maxValue
) {
}
