package com.example.gsb.latem.exec;

public record ColumnAccess(String column, Kind kind, int elements) {

    public enum Kind {
        SCAN,
        SPARSE,
        MATERIALIZE,
        SAMPLING
    }

    public ColumnAccess {
        if (elements < 0) {
            throw new IllegalArgumentException("elements must be >= 0");
        }
    }
}
