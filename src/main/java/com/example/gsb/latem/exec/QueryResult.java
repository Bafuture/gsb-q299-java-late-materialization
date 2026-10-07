package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QueryResult(int[] rowIds,
                          List<String> columns,
                          Map<String, ColumnVector> projected,
                          QueryStats stats,
                          ExecutionStrategy strategy) {

    public QueryResult {
        rowIds = Arrays.copyOf(rowIds, rowIds.length);
        columns = List.copyOf(columns);
        projected = Collections.unmodifiableMap(new LinkedHashMap<>(projected));
    }

    public int rowCount() {
        return rowIds.length;
    }

    public ColumnVector column(String name) {
        ColumnVector vector = projected.get(name);
        if (vector == null) {
            throw new IllegalArgumentException("column not projected: " + name);
        }
        return vector;
    }

    public Object value(String column, int index) {
        return column(column).valueAt(index);
    }

    public int intValue(String column, int index) {
        return ((IntVector) column(column)).get(index);
    }

    public double doubleValue(String column, int index) {
        return ((DoubleVector) column(column)).get(index);
    }

    public String stringValue(String column, int index) {
        return ((StringVector) column(column)).get(index);
    }
}
