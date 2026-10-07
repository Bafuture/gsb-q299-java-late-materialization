package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.ParallelQueryRunner;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelMergeTest {

    private static final int ROWS = 50_000;

    private static ColumnarTable buildTable() {
        return TestTables.randomTable(new Random(7), ROWS, 3, 1, 1);
    }

    private static Query query() {
        return Query.builder()
                .where("i0", Predicates.intBetween(10, 50))
                .where("d0", Predicates.doubleLessThan(0.5))
                .select("i1", "s0", "i2")
                .build();
    }

    @Test
    void mergedResultPreservesRowOrderAndMatchesSequential() {
        ColumnarTable table = buildTable();
        Query query = query();
        QueryResult sequential = new QueryExecutor(table)
                .execute(query, ExecutionStrategy.LATE_MATERIALIZATION);

        for (int threads : new int[] {1, 2, 3, 8}) {
            try (ParallelQueryRunner runner = new ParallelQueryRunner(table, threads)) {
                QueryResult merged = runner.execute(query);

                assertThat(merged.rowIds()).isSorted();
                TestTables.assertResultsEqual(sequential, merged);
                assertThat(merged.stats().candidateSize())
                        .isEqualTo(sequential.stats().candidateSize());
                assertThat(merged.stats().elementsRead())
                        .isEqualTo(sequential.stats().elementsRead());
                assertThat(merged.stats().materializedElements())
                        .isEqualTo(sequential.stats().materializedElements());
                assertThat(merged.stats().materializedColumnCount())
                        .isEqualTo(sequential.stats().materializedColumnCount());
            }
        }
    }

    @Test
    void parallelResultIsSortedAcrossPartitionBoundaries() {
        ColumnarTable table = buildTable();
        try (ParallelQueryRunner runner = new ParallelQueryRunner(table, 7)) {
            QueryResult merged = runner.execute(query());

            int[] rowIds = merged.rowIds();
            for (int i = 1; i < rowIds.length; i++) {
                assertThat(rowIds[i]).isGreaterThan(rowIds[i - 1]);
            }
        }
    }

    @Test
    void emptyResultMergesCleanly() {
        ColumnarTable table = buildTable();
        Query query = Query.builder()
                .where("i0", Predicates.intLessThan(0))
                .select("i1")
                .build();

        try (ParallelQueryRunner runner = new ParallelQueryRunner(table, 4)) {
            QueryResult merged = runner.execute(query);

            assertThat(merged.rowCount()).isZero();
            assertThat(merged.rowIds()).isEmpty();
            assertThat(merged.column("i1").size()).isZero();
            assertThat(merged.stats().candidateSize()).isZero();
        }
    }

    @Test
    void moreThreadsThanRowsStillWorks() {
        ColumnarTable table = TestTables.randomTable(new Random(11), 5, 2, 0, 0);
        Query query = Query.builder()
                .where("i0", Predicates.intGreaterOrEqual(0))
                .select("i1")
                .build();

        try (ParallelQueryRunner runner = new ParallelQueryRunner(table, 16)) {
            QueryResult merged = runner.execute(query);
            QueryResult sequential = new QueryExecutor(table)
                    .execute(query, ExecutionStrategy.LATE_MATERIALIZATION);

            TestTables.assertResultsEqual(sequential, merged);
        }
    }
}
