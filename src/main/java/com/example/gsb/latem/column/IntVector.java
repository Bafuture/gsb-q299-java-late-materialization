package com.example.gsb.latem.column;

import java.util.Objects;

public final class IntVector implements ColumnVector {

    private final String name;
    private final int[] values;

    public IntVector(String name, int[] values) {
        this.name = Objects.requireNonNull(name, "name");
        this.values = Objects.requireNonNull(values, "values");
    }

    public int get(int rowId) {
        return values[rowId];
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ColumnType type() {
        return ColumnType.INT;
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
