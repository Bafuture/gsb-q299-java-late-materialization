package com.example.gsb.columnar;

/**
 * 作用在单列上的过滤条件。列存过滤只读取该列，逐行求值得到候选行号。
 */
@FunctionalInterface
public interface ColumnPredicate {

    boolean test(Object value);

    static ColumnPredicate equalTo(Object expected) {
        return value -> java.util.Objects.equals(value, expected);
    }

    static ColumnPredicate notEqualTo(Object expected) {
        return value -> !java.util.Objects.equals(value, expected);
    }

    static ColumnPredicate greaterThan(int threshold) {
        return value -> ((Number) value).intValue() > threshold;
    }

    static ColumnPredicate greaterOrEqual(int threshold) {
        return value -> ((Number) value).intValue() >= threshold;
    }

    static ColumnPredicate lessThan(int threshold) {
        return value -> ((Number) value).intValue() < threshold;
    }

    static ColumnPredicate lessOrEqual(int threshold) {
        return value -> ((Number) value).intValue() <= threshold;
    }

    static ColumnPredicate between(int lowInclusive, int highInclusive) {
        return value -> {
            int v = ((Number) value).intValue();
            return v >= lowInclusive && v <= highInclusive;
        };
    }
}
