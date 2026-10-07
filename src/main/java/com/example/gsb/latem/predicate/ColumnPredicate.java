package com.example.gsb.latem.predicate;

import com.example.gsb.latem.column.ColumnType;

public interface ColumnPredicate {

    ColumnType type();

    default boolean testInt(int value) {
        throw new UnsupportedOperationException("not an INT predicate");
    }

    default boolean testDouble(double value) {
        throw new UnsupportedOperationException("not a DOUBLE predicate");
    }

    default boolean testString(String value) {
        throw new UnsupportedOperationException("not a STRING predicate");
    }
}
