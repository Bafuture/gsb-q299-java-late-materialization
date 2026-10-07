package com.example.gsb.latem.column;

import java.util.Objects;

public final class DoubleVector implements ColumnVector {

    private final String name;
    private final double[] values;

    public DoubleVector(String name, double[] values) {
        this.name = Objects.requireNonNull(name, "name");
        this.values = Objects.requireNonNull(values, "values");
    }

    public double get(int rowId) {
        return values[rowId];
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ColumnType type() {
        return ColumnType.DOUBLE;
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
