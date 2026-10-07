package com.example.gsb.columnar;

import java.util.List;

/**
 * 查询结果：命中行号（升序）、按行对齐的输出列值、执行统计。
 */
public record QueryResult(int[] rowIds, List<String> columns, List<Object[]> values, QueryStats stats) {

    public int rowCount() {
        return rowIds.length;
    }

    public Object valueAt(int rowIndex, String column) {
        int colIdx = columns.indexOf(column);
        if (colIdx < 0) {
            throw new IllegalArgumentException("column not in result: " + column);
        }
        return values.get(colIdx)[rowIndex];
    }
}
