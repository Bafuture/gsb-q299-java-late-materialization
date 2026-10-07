package com.example.gsb.columnar;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于采样的选择性评估与策略选择。
 *
 * <p>切换条件：对每条过滤条件采样估算通过率 p_i，代入 {@link CostModel} 得到
 * 两种策略的读取成本；当延迟物化成本 &gt; 提前物化成本时切换为提前物化。
 * 单过滤、筛选列与输出列不相交时可化简为：通过率 p &gt; 1 / {@link ColumnStore#RANDOM_PENALTY}
 * （即过滤条件区分度不足、约一成以上行都会命中）时，提前物化更划算。</p>
 */
public final class QueryPlanner {

    public enum Strategy {
        /** 延迟物化：先过滤出候选行号，再按行号点取输出列。 */
        LATE,
        /** 提前物化：先整读全部输出列，再过滤。 */
        EARLY
    }

    public record Plan(Strategy strategy,
                       CostModel.Estimate estimate,
                       List<Double> sampledSelectivities) {
        @Override
        public String toString() {
            return "Plan{strategy=" + strategy
                    + ", lateCost=" + estimate.lateCost()
                    + ", earlyCost=" + estimate.earlyCost()
                    + ", selectivities=" + sampledSelectivities + "}";
        }
    }

    private final int sampleSize;

    public QueryPlanner(int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive");
        }
        this.sampleSize = sampleSize;
    }

    public static QueryPlanner withSampleSize(int sampleSize) {
        return new QueryPlanner(sampleSize);
    }

    /** 均匀采样估算每条过滤条件的通过率。 */
    public List<Double> estimateSelectivities(ColumnStore store, Query query) {
        int n = store.rowCount();
        int samples = Math.min(sampleSize, n);
        List<Double> result = new ArrayList<>(query.filters().size());
        for (Filter filter : query.filters()) {
            int hits = 0;
            for (int s = 0; s < samples; s++) {
                int row = (int) ((long) s * n / samples);
                if (filter.predicate().test(store.peek(filter.column(), row))) {
                    hits++;
                }
            }
            result.add((double) hits / samples);
        }
        return result;
    }

    public Plan plan(ColumnStore store, Query query) {
        List<Double> selectivities = estimateSelectivities(store, query);
        CostModel.Estimate estimate = CostModel.estimate(
                store.rowCount(),
                selectivities,
                query.outputColumns().size(),
                CostModel.distinctFilterColumns(query.filters()),
                CostModel.overlap(query.filters(), query.outputColumns()));
        return new Plan(CostModel.choose(estimate), estimate, selectivities);
    }
}
