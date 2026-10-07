package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 要求 6：多线程处理行号区间，合并后保持原有行序。 */
class ParallelExecutionTest {

    private ColumnStore randomStore(long seed, int rows, int cols) {
        Random random = new Random(seed);
        String[] names = new String[cols];
        Object[][] data = new Object[cols][];
        for (int c = 0; c < cols; c++) {
            names[c] = "c" + c;
            data[c] = new Integer[rows];
            for (int r = 0; r < rows; r++) {
                data[c][r] = random.nextInt(100);
            }
        }
        return ColumnStore.inMemory("p", names, data);
    }

    private QueryResult sequentialLate(ColumnStore store, Query query) {
        return new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
    }

    @Test
    void parallelResultMatchesSequentialAndKeepsRowOrder() {
        int rows = 5_003; // 不能被线程数整除，检验分片余量
        ColumnStore store = randomStore(11, rows, 12);
        Query query = new Query(List.of(
                Filter.on("c0", ColumnPredicate.greaterThan(20)),
                Filter.on("c1", ColumnPredicate.lessThan(70))),
                List.of("c0", "c5", "c11"));

        QueryResult expected = sequentialLate(store, query);
        QueryResult parallel = new ParallelQueryExecutor(store, 4).execute(query);

        assertThat(parallel.rowIds()).containsExactly(expected.rowIds());
        assertThat(parallel.rowIds()).isSorted();
        for (int c = 0; c < expected.columns().size(); c++) {
            assertThat(parallel.values().get(c)).containsExactly(expected.values().get(c));
        }
    }

    @Test
    void differentThreadCountsAllProduceIdenticalResults() {
        int rows = 10_007;
        ColumnStore store = randomStore(23, rows, 8);
        Query query = new Query(List.of(
                Filter.on("c0", ColumnPredicate.lessOrEqual(60)),
                Filter.on("c2", ColumnPredicate.greaterOrEqual(10))),
                List.of("c0", "c3", "c7"));

        QueryResult single = new ParallelQueryExecutor(store, 1).execute(query);
        for (int threads : new int[] {2, 3, 7, 16}) {
            QueryResult result = new ParallelQueryExecutor(store, threads).execute(query);
            assertThat(result.rowIds()).as("threads=%d", threads).containsExactly(single.rowIds());
            for (int c = 0; c < single.columns().size(); c++) {
                assertThat(result.values().get(c)).containsExactly(single.values().get(c));
            }
        }
    }

    @Test
    void parallelStatsAreAggregatedAcrossShards() {
        int rows = 1_000;
        ColumnStore store = randomStore(31, rows, 20);
        Query query = new Query(
                List.of(Filter.on("c0", ColumnPredicate.greaterThan(40))),
                List.of("c1", "c2"));
        QueryResult expected = sequentialLate(store, query);
        QueryResult parallel = new ParallelQueryExecutor(store, 4).execute(query);

        // 跨分片汇总的读取量应与单线程一致
        assertThat(parallel.stats().totalReadUnits()).isEqualTo(expected.stats().totalReadUnits());
        assertThat(parallel.stats().candidateSizes()).isEqualTo(expected.stats().candidateSizes());
        assertThat(parallel.stats().columnsRead()).isEqualTo(expected.stats().columnsRead());
        assertThat(parallel.stats().materializedColumns()).isEqualTo(2);
        assertThat(parallel.stats().skippedColumns()).isEqualTo(17);
        assertThat(parallel.stats().skippedCellReads()).isEqualTo(17L * rows);
    }

    @Test
    void parallelAndSingleReadSamePhysicalDataOnRandomQueries() {
        for (long seed = 1; seed <= 15; seed++) {
            int rows = 3_000 + (int) seed * 37;
            ColumnStore store = randomStore(seed, rows, 10);
            List<Filter> filters = new ArrayList<>();
            filters.add(Filter.on("c0", ColumnPredicate.greaterThan((int) (seed * 3) % 90)));
            filters.add(Filter.on("c1", ColumnPredicate.lessThan(95 - (int) seed)));
            Query query = new Query(filters, List.of("c0", "c2", "c9"));
            QueryResult seq = sequentialLate(store, query);
            QueryResult par = new ParallelQueryExecutor(store, 5).execute(query);
            assertThat(par.rowIds()).containsExactly(seq.rowIds());
            for (int c = 0; c < seq.columns().size(); c++) {
                assertThat(par.values().get(c)).containsExactly(seq.values().get(c));
            }
        }
    }
}
