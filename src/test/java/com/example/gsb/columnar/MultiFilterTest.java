package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 要求 3：多列组合筛选，候选集逐步缩小。 */
class MultiFilterTest {

    private ColumnStore store() {
        String[] names = {"a", "b", "c"};
        Object[][] data = {
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {10, 9, 8, 7, 6, 5, 4, 3, 2, 1},
                {0, 0, 1, 1, 0, 0, 1, 1, 0, 0},
        };
        return ColumnStore.inMemory("t", names, data);
    }

    @Test
    void multipleFiltersNarrowCandidatesStepByStep() {
        ColumnStore store = store();
        Query query = new Query(List.of(
                Filter.on("a", ColumnPredicate.greaterOrEqual(3)),   // 8 行
                Filter.on("b", ColumnPredicate.lessOrEqual(7)),      // 交集 7 行
                Filter.on("c", ColumnPredicate.equalTo(1))),         // 交集 3 行
                List.of("a", "b"));

        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);

        assertThat(result.stats().candidateSizes()).containsExactly(8, 7, 3);
        // a>=3 -> 行 2..9；b<=7 -> 行 3..9；c==1 -> 行 3,6,7
        assertThat(result.rowIds()).containsExactly(3, 6, 7);
        assertThat(result.columns()).containsExactly("a", "b");
        assertThat(result.valueAt(0, "a")).isEqualTo(4);
        assertThat(result.valueAt(1, "a")).isEqualTo(7);
        assertThat(result.valueAt(2, "a")).isEqualTo(8);
        assertThat(result.valueAt(0, "b")).isEqualTo(7);
        assertThat(result.valueAt(1, "b")).isEqualTo(4);
        assertThat(result.valueAt(2, "b")).isEqualTo(3);
    }

    @Test
    void laterFiltersOnlyProbeCurrentCandidates() {
        ColumnStore store = store();
        Query query = new Query(List.of(
                Filter.on("a", ColumnPredicate.greaterOrEqual(3)),  // 8 候选
                Filter.on("b", ColumnPredicate.lessOrEqual(7))),    // 只对 8 行点取
                List.of("a"));
        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
        // 首筛顺序读 10 个单元；第二次过滤只点取 8 个候选行
        assertThat(result.stats().sequentialCellsRead()).isEqualTo(10);
        assertThat(result.stats().randomCellsRead()).isEqualTo(8);
    }

    @Test
    void contradictoryFiltersYieldEmptyResult() {
        ColumnStore store = store();
        Query query = new Query(List.of(
                Filter.on("a", ColumnPredicate.lessThan(3)),
                Filter.on("a", ColumnPredicate.greaterThan(8))),
                List.of("b"));
        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
        assertThat(result.rowCount()).isZero();
        assertThat(result.stats().candidateSizes()).containsExactly(2, 0);
    }

    @Test
    void earlyAndLateAgreeOnMultiFilter() {
        ColumnStore store = store();
        Query query = new Query(List.of(
                Filter.on("a", ColumnPredicate.greaterOrEqual(2)),
                Filter.on("b", ColumnPredicate.greaterOrEqual(3)),
                Filter.on("c", ColumnPredicate.equalTo(0))),
                List.of("a", "c"));
        QueryExecutor executor = new QueryExecutor(store);
        QueryResult late = executor.execute(query, QueryExecutor.Mode.LATE);
        QueryResult early = executor.execute(query, QueryExecutor.Mode.EARLY);
        assertThat(late.rowIds()).containsExactly(early.rowIds());
        for (int i = 0; i < late.rowCount(); i++) {
            assertThat(late.valueAt(i, "a")).isEqualTo(early.valueAt(i, "a"));
            assertThat(late.valueAt(i, "c")).isEqualTo(early.valueAt(i, "c"));
        }
    }
}
