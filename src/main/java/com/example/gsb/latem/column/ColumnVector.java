package com.example.gsb.latem.column;

public sealed interface ColumnVector permits IntVector, DoubleVector, StringVector {

    String name();

    ColumnType type();

    int size();

    Object valueAt(int rowId);
}
