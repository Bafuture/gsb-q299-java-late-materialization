package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 要求 7：候选集大小、物化列数、读取列数与被跳过的列读取量统计。 */
class QueryStatsTest {

    private ColumnStore store() {
        String[] names = {"a", "b", "c", "d", "e"};
        Object[][] data = {
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {10, 9, 8, 7, 6, 5, 4, 3, 2, 1},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
                {2, 2, 2, 2, 2, 2, 2, 2, 2, 2},
        };
        return ColumnStore.inMemory("t", names, data);
    }

    @Test
    void lateStatsReportCandidateSizesMaterializedAndSkippedReads() {
        ColumnStore store = store();
        Query query = new Query(List.of(
                Filter.on("a", ColumnPredicate.greaterThan(3)),
                Filter.on("b", ColumnPredicate.greaterThan(4))),
                List.of("c", "d"));
        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
        QueryStats stats = result.stats();

        assertThat(stats.strategy()).isEqualTo("LATE");
        assertThat(stats.candidateSizes()).containsExactly(7, 3);
        assertThat(stats.finalCandidateCount()).isEqualTo(3);
        assertThat(stats.materializedColumns()).isEqualTo(2);
        assertThat(stats.columnsRead()).isEqualTo(4);           // a, b, c, d
        assertThat(stats.columnsFullyScanned()).isEqualTo(1);  // 仅 a 整列扫
        assertThat(stats.skippedColumns()).isEqualTo(1);       // e
        assertThat(stats.skippedCellReads()).isEqualTo(10L);
        assertThat(stats.fullReadUnits()).isEqualTo(50L);
        // 首筛顺序读 10；b 点取 7 候选；c/d 各点取最终 3 行
        assertThat(stats.sequentialCellsRead()).isEqualTo(10);
        assertThat(stats.randomCellsRead()).isEqualTo(7 + 3 + 3);
        assertThat(stats.totalReadUnits()).isEqualTo(10 + 13L * ColumnStore.RANDOM_PENALTY);
        // 小表上点取折算成本可能高于全读（负值合理，规划器据此切换提前物化）
        assertThat(stats.readSavingRatio()).isCloseTo(1.0 - 140.0 / 50,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.elapsedNanos()).isPositive();
    }

    @Test
    void earlyStatsReportFullColumnReads() {
        ColumnStore store = store();
        Query query = new Query(
                List.of(Filter.on("a", ColumnPredicate.greaterThan(3))),
                List.of("c", "d"));
        QueryStats stats = new QueryExecutor(store).execute(query, QueryExecutor.Mode.EARLY).stats();

        assertThat(stats.strategy()).isEqualTo("EARLY");
        assertThat(stats.columnsRead()).isEqualTo(3); // c, d 提前物化 + a 筛选
        assertThat(stats.columnsFullyScanned()).isEqualTo(3);
        assertThat(stats.skippedColumns()).isEqualTo(2);
        assertThat(stats.totalReadUnits()).isEqualTo(30L);
        assertThat(stats.skippedCellReads()).isEqualTo(20L);
    }

    @Test
    void emptyResultStillConsistentStats() {
        ColumnStore store = store();
        Query query = new Query(
                List.of(Filter.on("a", ColumnPredicate.greaterThan(1000))),
                List.of("b"));
        QueryStats stats = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE).stats();
        assertThat(stats.finalCandidateCount()).isZero();
        assertThat(stats.materializedColumns()).isEqualTo(1);
        // 无候选行，输出列点取量为 0；只剩首列整列扫描
        assertThat(stats.randomCellsRead()).isZero();
        assertThat(stats.totalReadUnits()).isEqualTo(10);
    }
}
