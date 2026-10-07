package com.example.gsb.latem.predicate;

import com.example.gsb.latem.column.ColumnType;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public final class Predicates {

    private Predicates() {
    }

    public static ColumnPredicate intBetween(int minInclusive, int maxInclusive) {
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException("minInclusive > maxInclusive");
        }
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.INT;
            }

            @Override
            public boolean testInt(int value) {
                return value >= minInclusive && value <= maxInclusive;
            }

            @Override
            public String toString() {
                return "intBetween(" + minInclusive + ", " + maxInclusive + ")";
            }
        };
    }

    public static ColumnPredicate intEq(int expected) {
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.INT;
            }

            @Override
            public boolean testInt(int value) {
                return value == expected;
            }

            @Override
            public String toString() {
                return "intEq(" + expected + ")";
            }
        };
    }

    public static ColumnPredicate intLessThan(int upperExclusive) {
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.INT;
            }

            @Override
            public boolean testInt(int value) {
                return value < upperExclusive;
            }

            @Override
            public String toString() {
                return "intLessThan(" + upperExclusive + ")";
            }
        };
    }

    public static ColumnPredicate intGreaterOrEqual(int lowerInclusive) {
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.INT;
            }

            @Override
            public boolean testInt(int value) {
                return value >= lowerInclusive;
            }

            @Override
            public String toString() {
                return "intGreaterOrEqual(" + lowerInclusive + ")";
            }
        };
    }

    public static ColumnPredicate doubleBetween(double minInclusive, double maxInclusive) {
        if (Double.compare(minInclusive, maxInclusive) > 0) {
            throw new IllegalArgumentException("minInclusive > maxInclusive");
        }
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.DOUBLE;
            }

            @Override
            public boolean testDouble(double value) {
                return Double.compare(value, minInclusive) >= 0
                        && Double.compare(value, maxInclusive) <= 0;
            }

            @Override
            public String toString() {
                return "doubleBetween(" + minInclusive + ", " + maxInclusive + ")";
            }
        };
    }

    public static ColumnPredicate doubleLessThan(double upperExclusive) {
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.DOUBLE;
            }

            @Override
            public boolean testDouble(double value) {
                return Double.compare(value, upperExclusive) < 0;
            }

            @Override
            public String toString() {
                return "doubleLessThan(" + upperExclusive + ")";
            }
        };
    }

    public static ColumnPredicate stringEq(String expected) {
        Objects.requireNonNull(expected, "expected");
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.STRING;
            }

            @Override
            public boolean testString(String value) {
                return expected.equals(value);
            }

            @Override
            public String toString() {
                return "stringEq(" + expected + ")";
            }
        };
    }

    public static ColumnPredicate stringIn(Collection<String> values) {
        Set<String> allowed = Set.copyOf(values);
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.STRING;
            }

            @Override
            public boolean testString(String value) {
                return allowed.contains(value);
            }

            @Override
            public String toString() {
                return "stringIn(" + allowed + ")";
            }
        };
    }

    public static ColumnPredicate stringStartsWith(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new ColumnPredicate() {
            @Override
            public ColumnType type() {
                return ColumnType.STRING;
            }

            @Override
            public boolean testString(String value) {
                return value != null && value.startsWith(prefix);
            }

            @Override
            public String toString() {
                return "stringStartsWith(" + prefix + ")";
            }
        };
    }
}
