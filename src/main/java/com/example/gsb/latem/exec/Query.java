package com.example.gsb.latem.exec;

import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.ColumnPredicate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record Query(List<ColumnFilter> filters, List<String> outputColumns) {

    public Query {
        filters = List.copyOf(filters);
        outputColumns = List.copyOf(outputColumns);
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("query needs at least one filter");
        }
    }

    public List<String> involvedColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (ColumnFilter filter : filters) {
            columns.add(filter.column());
        }
        columns.addAll(outputColumns);
        return List.copyOf(columns);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<ColumnFilter> filters = new ArrayList<>();
        private final List<String> outputColumns = new ArrayList<>();

        public Builder where(String column, ColumnPredicate predicate) {
            filters.add(new ColumnFilter(column, predicate));
            return this;
        }

        public Builder select(String... columns) {
            outputColumns.addAll(List.of(columns));
            return this;
        }

        public Query build() {
            return new Query(filters, outputColumns);
        }
    }
}
