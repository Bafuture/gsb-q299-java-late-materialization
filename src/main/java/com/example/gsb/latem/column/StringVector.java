package com.example.gsb.latem.column;

import java.util.Objects;

public final class StringVector implements ColumnVector {

    private final String name;
    private final String[] values;

    public StringVector(String name, String[] values) {
        this.name = Objects.requireNonNull(name, "name");
        this.values = Objects.requireNonNull(values, "values");
    }

    public String get(int rowId) {
        return values[rowId];
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ColumnType type() {
        return ColumnType.STRING;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Object valueAt(int rowId) {
        return values[rowId];
    }
}
