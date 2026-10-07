package com.example.gsb.latem.column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ColumnarTable {

    private final Map<String, ColumnVector> columns;
    private final int rowCount;

    private ColumnarTable(Map<String, ColumnVector> columns, int rowCount) {
        this.columns = columns;
        this.rowCount = rowCount;
    }

    public ColumnVector column(String name) {
        ColumnVector column = columns.get(name);
        if (column == null) {
            throw new IllegalArgumentException("unknown column: " + name);
        }
        return column;
    }

    public boolean hasColumn(String name) {
        return columns.containsKey(name);
    }

    public List<String> columnNames() {
        return new ArrayList<>(columns.keySet());
    }

    public int rowCount() {
        return rowCount;
    }

    public int columnCount() {
        return columns.size();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, ColumnVector> columns = new LinkedHashMap<>();
        private int rowCount = -1;

        public Builder addColumn(ColumnVector column) {
            if (columns.putIfAbsent(column.name(), column) != null) {
                throw new IllegalArgumentException("duplicate column: " + column.name());
            }
            if (rowCount < 0) {
                rowCount = column.size();
            } else if (column.size() != rowCount) {
                throw new IllegalArgumentException("column " + column.name() + " has " + column.size()
                        + " rows, expected " + rowCount);
            }
            return this;
        }

        public ColumnarTable build() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("table needs at least one column");
            }
            return new ColumnarTable(Collections.unmodifiableMap(new LinkedHashMap<>(columns)), rowCount);
        }
    }
}
