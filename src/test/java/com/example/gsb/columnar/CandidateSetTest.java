package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 要求 1：候选集只读筛选列，不触碰其它列。 */
class CandidateSetTest {

    private ColumnStore newStore() {
        String[] names = {"a", "b", "c", "d"};
        Object[][] data = {
                {1, 2, 3, 4, 5, 6, 7, 8},
                {10, 20, 30, 40, 50, 60, 70, 80},
                {5, 5, 5, 5, 5, 5, 5, 5},
                {0, 1, 0, 1, 0, 1, 0, 1},
        };
        return ColumnStore.inMemory("t", names, data);
    }

    @Test
    void scanBuildsCandidatesFromFilterColumnOnly() {
        ColumnStore store = newStore();
        store.resetStats();
        CandidateSet candidates = CandidateSet.scan(store, Filter.on("a", ColumnPredicate.greaterThan(4)));

        assertThat(candidates.rows()).containsExactly(4, 5, 6, 7);
        assertThat(candidates.size()).isEqualTo(4);
        // 只读了筛选列 a，其余列完全未触碰
        assertThat(store.touchedColumnCount()).isEqualTo(1);
        assertThat(store.fullyReadColumnCount()).isEqualTo(1);
        assertThat(store.skippedColumnCount()).isEqualTo(3);
        assertThat(store.skippedCellReads()).isEqualTo(3L * 8);
        assertThat(store.randomCellsRead()).isZero();
    }

    @Test
    void scanEmptyMatchProducesEmptyCandidateSet() {
        ColumnStore store = newStore();
        store.resetStats();
        CandidateSet candidates = CandidateSet.scan(store, Filter.on("a", ColumnPredicate.greaterThan(1000)));
        assertThat(candidates.size()).isZero();
        assertThat(candidates.rows()).isEmpty();
    }

    @Test
    void scanOnRowRangeOnlyTouchesThatRange() {
        ColumnStore store = newStore();
        store.resetStats();
        CandidateSet candidates = CandidateSet.scan(store, Filter.on("a", ColumnPredicate.greaterThan(4)), 4, 4);
        assertThat(candidates.rows()).containsExactly(4, 5, 6, 7);
        assertThat(store.sequentialCellsRead()).isEqualTo(4);
    }

    @Test
    void refineShrinksCandidateSetMonotonically() {
        ColumnStore store = newStore();
        store.resetStats();
        CandidateSet first = CandidateSet.scan(store, Filter.on("a", ColumnPredicate.greaterOrEqual(2)));
        assertThat(first.rows()).containsExactly(1, 2, 3, 4, 5, 6, 7);

        CandidateSet second = first.refine(store, Filter.on("b", ColumnPredicate.lessThan(60)));
        assertThat(second.rows()).containsExactly(1, 2, 3, 4);
        assertThat(second.size()).isLessThan(first.size());
        // refine 只对候选行做点取：首筛后 7 个候选行，逐行点取 b
        assertThat(store.randomCellsRead()).isEqualTo(7);
        assertThat(store.touchedColumnCount()).isEqualTo(2);
    }

    @Test
    void materializeUsesCachedFilterColumnWithoutExtraReads() {
        ColumnStore store = newStore();
        store.resetStats();
        CandidateSet candidates = CandidateSet.scan(store, Filter.on("a", ColumnPredicate.greaterThan(4)));
        long before = store.totalReadUnits();
        Object[] values = candidates.materializeColumn(store, "a");
        assertThat(values).containsExactly(5, 6, 7, 8);
        assertThat(store.totalReadUnits()).isEqualTo(before); // 缓存命中，零额外 IO
    }
}
