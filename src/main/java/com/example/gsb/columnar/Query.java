package com.example.gsb.columnar;

import java.util.List;

/**
 * 一条查询：按顺序的过滤条件 + 输出列。
 */
public record Query(List<Filter> filters, List<String> outputColumns) {

    public Query {
        filters = List.copyOf(filters);
        outputColumns = List.copyOf(outputColumns);
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("at least one filter is required");
        }
        if (outputColumns.isEmpty()) {
            throw new IllegalArgumentException("at least one output column is required");
        }
    }
}
