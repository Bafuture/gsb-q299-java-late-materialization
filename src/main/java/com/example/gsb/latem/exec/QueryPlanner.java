package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnarTable;

import java.util.Locale;

public final class QueryPlanner {

    private QueryPlanner() {
    }

    public record Plan(ExecutionStrategy strategy,
                       double lateCost,
                       double earlyCost,
                       double estimatedSurvivorFraction,
                       long[] estimatedSurvivors,
                       String rationale) {

        public Plan {
            if (strategy == ExecutionStrategy.NAIVE_FULL_READ) {
                throw new IllegalArgumentException("planner never picks the naive baseline");
            }
            estimatedSurvivors = estimatedSurvivors.clone();
        }

        @Override
        public long[] estimatedSurvivors() {
            return estimatedSurvivors.clone();
        }
    }

    public static Plan plan(ColumnarTable table, Query query, CostModel model,
                            int sampleSize, QueryStats stats) {
        long[] survivors = SelectivityEstimator.estimateSurvivors(
                table, query.filters(), sampleSize, stats);
        int rows = table.rowCount();
        double lateCost = model.lateMaterializationCost(rows, survivors, query.outputColumns().size());
        double earlyCost = model.earlyMaterializationCost(rows, survivors, query.outputColumns().size());
        ExecutionStrategy strategy = lateCost <= earlyCost
                ? ExecutionStrategy.LATE_MATERIALIZATION
                : ExecutionStrategy.EARLY_MATERIALIZATION;
        double fraction = rows == 0 ? 0.0
                : survivors[survivors.length - 1] / (double) rows;
        String rationale = String.format(Locale.ROOT,
                "估计存活率 %.4f%%, 延迟物化成本 %.0f, 提前物化成本 %.0f -> %s",
                fraction * 100, lateCost, earlyCost, strategy);
        return new Plan(strategy, lateCost, earlyCost, fraction, survivors, rationale);
    }
}
