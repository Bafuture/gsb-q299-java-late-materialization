package com.example.gsb.columnar;

import java.util.Objects;

/**
 * 一条过滤条件：列名 + 谓词。
 */
public record Filter(String column, ColumnPredicate predicate) {

    public Filter {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(predicate, "predicate");
    }

    public static Filter on(String column, ColumnPredicate predicate) {
        return new Filter(column, predicate);
    }
}
