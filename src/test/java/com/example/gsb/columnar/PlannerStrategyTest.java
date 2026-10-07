package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 要求 5：选择性评估与策略切换。
 *
 * <p>切换条件（成本模型，单过滤、筛选列与输出列不相交时可化简）：
 * 延迟物化成本 = N + O*p*N*RANDOM_PENALTY，提前物化成本 = O*N，
 * 当通过率 p &gt; 1/RANDOM_PENALTY（过滤条件区分度不足）时提前物化更划算。</p>
 */
class PlannerStrategyTest {

    @Test
    void costModelMatchesAnalyticThreshold() {
        int n = 100_000;
        // p = 5%（高选择性）：LATE = 100k + 2*5k*10 = 200k < EARLY = 2*100k + 100k = 300k
        CostModel.Estimate low = CostModel.estimate(n, List.of(0.05), 2, 1, 0);
        assertThat(low.lateCost()).isLessThan(low.earlyCost());
        assertThat(CostModel.choose(low)).isEqualTo(QueryPlanner.Strategy.LATE);

        // p = 50%（低选择性）：LATE = 100k + 2*50k*10 = 1.1M > EARLY = 300k
        CostModel.Estimate high = CostModel.estimate(n, List.of(0.50), 2, 1, 0);
        assertThat(high.earlyCost()).isLessThan(high.lateCost());
        assertThat(CostModel.choose(high)).isEqualTo(QueryPlanner.Strategy.EARLY);
    }

    @Test
    void plannerSwitchesToEarlyWhenSelectivityIsPoor() {
        int rows = 20_000;
        String[] names = {"f", "o1", "o2"};
        Object[][] data = new Object[3][];
        data[0] = new Integer[rows];
        data[1] = new Integer[rows];
        data[2] = new Integer[rows];
        Random random = new Random(7);
        for (int r = 0; r < rows; r++) {
            data[0][r] = random.nextInt(100);
            data[1][r] = r;
            data[2][r] = r * 2;
        }
        ColumnStore store = ColumnStore.inMemory("t", names, data);

        // 高选择性：p ≈ 1%
        Query selective = new Query(
                List.of(Filter.on("f", ColumnPredicate.equalTo(7))),
                List.of("o1", "o2"));
        QueryPlanner.Plan plan1 = new QueryPlanner(512).plan(store, selective);
        assertThat(plan1.strategy()).isEqualTo(QueryPlanner.Strategy.LATE);
        assertThat(plan1.sampledSelectivities().get(0)).isBetween(0.0, 0.05);

        // 低选择性：p ≈ 90%，几乎全表命中，点取放大成本不划算
        Query unselective = new Query(
                List.of(Filter.on("f", ColumnPredicate.lessThan(90))),
                List.of("o1", "o2"));
        QueryPlanner.Plan plan2 = new QueryPlanner(512).plan(store, unselective);
        assertThat(plan2.strategy()).isEqualTo(QueryPlanner.Strategy.EARLY);
        assertThat(plan2.sampledSelectivities().get(0)).isGreaterThan(0.8);

        // AUTO 模式按规划器决策执行
        QueryExecutor executor = new QueryExecutor(store, new QueryPlanner(512));
        assertThat(executor.execute(selective, QueryExecutor.Mode.AUTO).stats().strategy())
                .isEqualTo("LATE");
        assertThat(executor.execute(unselective, QueryExecutor.Mode.AUTO).stats().strategy())
                .isEqualTo("EARLY");
    }

    @Test
    void wallClockTimingConfirmsCostModelBothDirections() {
        int rows = 80_000;
        int cols = 64;
        String[] names = new String[cols];
        Object[][] data = new Object[cols][];
        Random random = new Random(42);
        for (int c = 0; c < cols; c++) {
            names[c] = "c" + c;
            data[c] = new Integer[rows];
            for (int r = 0; r < rows; r++) {
                data[c][r] = random.nextInt(10_000);
            }
        }
        ColumnStore store = ColumnStore.withSimulatedIo("bench", names, data);
        QueryExecutor executor = new QueryExecutor(store);

        // 场景 1：高选择性 p≈1%，输出 2 列 → 延迟物化应显著更快
        Query selective = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(100))),
                List.of("c1", "c2"));
        warmUp(executor, selective);
        long lateNanos = bestOf(executor, selective, QueryExecutor.Mode.LATE, 3);
        long earlyNanos = bestOf(executor, selective, QueryExecutor.Mode.EARLY, 3);
        System.out.printf("[高选择性 p≈1%%, 64列输出2列] LATE=%.2fms EARLY=%.2fms%n",
                lateNanos / 1e6, earlyNanos / 1e6);
        assertThat(lateNanos).isLessThan(earlyNanos);

        // 场景 2：低选择性 p≈60%，输出 60 列 → 提前物化应显著更快
        List<String> manyOutputs = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            manyOutputs.add("c" + i);
        }
        Query unselective = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(6_000))),
                manyOutputs);
        warmUp(executor, unselective);
        long lateNanos2 = bestOf(executor, unselective, QueryExecutor.Mode.LATE, 3);
        long earlyNanos2 = bestOf(executor, unselective, QueryExecutor.Mode.EARLY, 3);
        System.out.printf("[低选择性 p≈60%%, 64列输出60列] LATE=%.2fms EARLY=%.2fms%n",
                lateNanos2 / 1e6, earlyNanos2 / 1e6);
        assertThat(earlyNanos2).isLessThan(lateNanos2);
    }

    private void warmUp(QueryExecutor executor, Query query) {
        executor.execute(query, QueryExecutor.Mode.LATE);
        executor.execute(query, QueryExecutor.Mode.EARLY);
    }

    private long bestOf(QueryExecutor executor, Query query, QueryExecutor.Mode mode, int reps) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            best = Math.min(best, executor.execute(query, mode).stats().elapsedNanos());
        }
        return best;
    }
}
