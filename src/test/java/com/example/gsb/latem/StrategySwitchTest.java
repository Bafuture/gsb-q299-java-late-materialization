package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.exec.CostModel;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryPlanner;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySwitchTest {

    private static final int ROWS = 200_000;

    private static ColumnarTable buildTable(int rows) {
        Random random = new Random(2024);
        ColumnarTable.Builder builder = ColumnarTable.builder();
        for (int c = 0; c < 4; c++) {
            int[] values = new int[rows];
            for (int i = 0; i < rows; i++) {
                values[i] = random.nextInt(1000);
            }
            builder.addColumn(new IntVector("c" + c, values));
        }
        return builder.build();
    }

    private static Query selectiveQuery() {
        return Query.builder()
                .where("c0", Predicates.intLessThan(5))
                .where("c1", Predicates.intLessThan(50))
                .select("c2", "c3")
                .build();
    }

    private static Query weakFilterQuery() {
        return Query.builder()
                .where("c0", Predicates.intLessThan(950))
                .where("c1", Predicates.intLessThan(900))
                .select("c2", "c3")
                .build();
    }

    @Test
    void costModelPrefersLateMaterializationForStrongFilters() {
        CostModel model = CostModel.defaults();
        long[] survivors = {5_000, 500};

        assertThat(model.lateMaterializationCost(1_000_000, survivors, 3))
                .isLessThan(model.earlyMaterializationCost(1_000_000, survivors, 3));
    }

    @Test
    void costModelPrefersEarlyMaterializationForWeakFilters() {
        CostModel model = CostModel.defaults();
        long[] survivors = {950_000, 850_000};

        assertThat(model.earlyMaterializationCost(1_000_000, survivors, 3))
                .isLessThan(model.lateMaterializationCost(1_000_000, survivors, 3));
    }

    @Test
    void plannerChoosesLateMaterializationWhenSelectivityIsHigh() {
        ColumnarTable table = buildTable(ROWS);
        QueryPlanner.Plan plan = new QueryExecutor(table).plan(selectiveQuery());

        assertThat(plan.strategy()).isEqualTo(ExecutionStrategy.LATE_MATERIALIZATION);
        assertThat(plan.lateCost()).isLessThanOrEqualTo(plan.earlyCost());
        assertThat(plan.estimatedSurvivorFraction()).isBetween(0.0, 0.01);
        assertThat(plan.rationale()).contains("存活率");
    }

    @Test
    void plannerChoosesEarlyMaterializationWhenSelectivityIsLow() {
        ColumnarTable table = buildTable(ROWS);
        QueryPlanner.Plan plan = new QueryExecutor(table).plan(weakFilterQuery());

        assertThat(plan.strategy()).isEqualTo(ExecutionStrategy.EARLY_MATERIALIZATION);
        assertThat(plan.earlyCost()).isLessThan(plan.lateCost());
        assertThat(plan.estimatedSurvivorFraction()).isGreaterThan(0.75);
    }

    @Test
    void autoExecutionFollowsPlannerDecision() {
        ColumnarTable table = buildTable(ROWS);
        QueryExecutor executor = new QueryExecutor(table);

        assertThat(executor.execute(selectiveQuery()).strategy())
                .isEqualTo(ExecutionStrategy.LATE_MATERIALIZATION);
        assertThat(executor.execute(weakFilterQuery()).strategy())
                .isEqualTo(ExecutionStrategy.EARLY_MATERIALIZATION);
    }

    @Test
    void lateMaterializationReadsFewerElementsWhenFilterIsStrong() {
        ColumnarTable table = buildTable(ROWS);
        QueryExecutor executor = new QueryExecutor(table);
        Query query = selectiveQuery();

        QueryResult late = executor.execute(query, ExecutionStrategy.LATE_MATERIALIZATION);
        QueryResult early = executor.execute(query, ExecutionStrategy.EARLY_MATERIALIZATION);
        QueryResult naive = executor.executeNaive(query);

        assertThat(late.stats().elementsRead()).isLessThan(early.stats().elementsRead());
        assertThat(early.stats().elementsRead()).isLessThan(naive.stats().elementsRead());
    }

    @Test
    void timingComparisonBetweenStrategiesIsReported() {
        ColumnarTable table = buildTable(ROWS);
        QueryExecutor executor = new QueryExecutor(table);

        for (Query query : new Query[] {selectiveQuery(), weakFilterQuery()}) {
            QueryResult late = executor.execute(query, ExecutionStrategy.LATE_MATERIALIZATION);
            QueryResult early = executor.execute(query, ExecutionStrategy.EARLY_MATERIALIZATION);
            QueryPlanner.Plan plan = executor.plan(query);

            assertThat(late.stats().elapsedNanos()).isPositive();
            assertThat(early.stats().elapsedNanos()).isPositive();
            System.out.printf("%s | 延迟物化 %.3f ms, 提前物化 %.3f ms%n",
                    plan.rationale(),
                    late.stats().elapsedNanos() / 1_000_000.0,
                    early.stats().elapsedNanos() / 1_000_000.0);
        }
    }
}
