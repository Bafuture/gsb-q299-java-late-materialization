package com.example.gsb.columnar;

/**
 * 列数据的一段视图：底层列数组 + 起始偏移。
 * 用于按候选行号区间切片，避免拷贝。
 */
public final class ColumnSlice {
    private final Object[] values;
    private final int offset;
    private final int length;

    ColumnSlice(Object[] values, int offset, int length) {
        this.values = values;
        this.offset = offset;
        this.length = length;
    }

    public Object valueAt(int index) {
        return values[offset + index];
    }

    public int length() {
        return length;
    }
}
