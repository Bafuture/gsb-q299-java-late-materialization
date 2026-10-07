package com.example.gsb.latem.predicate;

import java.util.Objects;

public record ColumnFilter(String column, ColumnPredicate predicate) {

    public ColumnFilter {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(predicate, "predicate");
    }
}
