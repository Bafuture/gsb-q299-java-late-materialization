package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.ColumnPredicate;

import java.util.Arrays;
import java.util.List;

public final class CandidateSetBuilder {

    private CandidateSetBuilder() {
    }

    public record CandidateResult(int[] rowIds, int count) {

        public int[] toArray() {
            return Arrays.copyOf(rowIds, count);
        }
    }

    public static CandidateResult build(ColumnarTable table, List<ColumnFilter> filters,
                                        int fromRow, int toRow, QueryStats stats) {
        if (fromRow < 0 || toRow > table.rowCount() || fromRow > toRow) {
            throw new IllegalArgumentException("invalid row range [" + fromRow + ", " + toRow + ")");
        }
        int[] current = null;
        int count = 0;
        for (ColumnFilter filter : filters) {
            ColumnVector column = table.column(filter.column());
            ColumnPredicate predicate = filter.predicate();
            if (column.type() != predicate.type()) {
                throw new IllegalArgumentException("predicate on column " + column.name()
                        + " expects " + column.type() + " but got " + predicate.type());
            }
            if (current == null) {
                int[] next = new int[toRow - fromRow];
                int nextCount = 0;
                for (int row = fromRow; row < toRow; row++) {
                    if (matches(column, row, predicate)) {
                        next[nextCount++] = row;
                    }
                }
                stats.record(new ColumnAccess(column.name(), ColumnAccess.Kind.SCAN, toRow - fromRow));
                current = next;
                count = nextCount;
            } else {
                int[] next = new int[count];
                int nextCount = 0;
                for (int i = 0; i < count; i++) {
                    int row = current[i];
                    if (matches(column, row, predicate)) {
                        next[nextCount++] = row;
                    }
                }
                stats.record(new ColumnAccess(column.name(), ColumnAccess.Kind.SPARSE, count));
                current = next;
                count = nextCount;
            }
        }
        if (current == null) {
            int[] all = new int[toRow - fromRow];
            for (int i = 0; i < all.length; i++) {
                all[i] = fromRow + i;
            }
            return new CandidateResult(all, all.length);
        }
        return new CandidateResult(current, count);
    }

    static boolean matches(ColumnVector column, int row, ColumnPredicate predicate) {
        return switch (column.type()) {
            case INT -> predicate.testInt(((IntVector) column).get(row));
            case DOUBLE -> predicate.testDouble(((DoubleVector) column).get(row));
            case STRING -> predicate.testString(((StringVector) column).get(row));
        };
    }
}
