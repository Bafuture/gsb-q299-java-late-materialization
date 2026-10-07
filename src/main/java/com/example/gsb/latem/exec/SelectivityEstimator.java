package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.ColumnPredicate;

import java.util.List;
import java.util.Random;

public final class SelectivityEstimator {

    public static final int DEFAULT_SAMPLE_SIZE = 10_000;

    private static final long SAMPLING_SEED = 0x5EEDL;

    private SelectivityEstimator() {
    }

    public static long[] estimateSurvivors(ColumnarTable table, List<ColumnFilter> filters,
                                           int sampleSize, QueryStats stats) {
        int rows = table.rowCount();
        int filterCount = filters.size();
        long[] survivors = new long[filterCount];
        if (rows == 0) {
            return survivors;
        }
        ColumnVector[] columns = new ColumnVector[filterCount];
        ColumnPredicate[] predicates = new ColumnPredicate[filterCount];
        for (int i = 0; i < filterCount; i++) {
            columns[i] = table.column(filters.get(i).column());
            predicates[i] = filters.get(i).predicate();
            if (columns[i].type() != predicates[i].type()) {
                throw new IllegalArgumentException("predicate on column " + columns[i].name()
                        + " expects " + columns[i].type() + " but got " + predicates[i].type());
            }
        }
        long[] evaluations = new long[filterCount];
        int samples = Math.min(sampleSize, rows);
        Random random = new Random(SAMPLING_SEED);
        for (int s = 0; s < samples; s++) {
            int row = random.nextInt(rows);
            for (int i = 0; i < filterCount; i++) {
                evaluations[i]++;
                if (!CandidateSetBuilder.matches(columns[i], row, predicates[i])) {
                    break;
                }
                survivors[i]++;
            }
        }
        for (int i = 0; i < filterCount; i++) {
            stats.record(new ColumnAccess(columns[i].name(), ColumnAccess.Kind.SAMPLING,
                    (int) evaluations[i]));
            survivors[i] = Math.round(survivors[i] * (rows / (double) samples));
        }
        return survivors;
    }
}
