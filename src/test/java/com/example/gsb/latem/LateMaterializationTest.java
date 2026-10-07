package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.exec.QueryStats;
import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LateMaterializationTest {

    private static final int ROWS = 10_000;

    private static ColumnarTable buildWideTable() {
        int[] a = new int[ROWS];
        int[] b = new int[ROWS];
        String[] c = new String[ROWS];
        double[] d = new double[ROWS];
        int[] unused = new int[ROWS];
        for (int i = 0; i < ROWS; i++) {
            a[i] = i;
            b[i] = ROWS - 1 - i;
            c[i] = TestTables.WORDS[i % TestTables.WORDS.length];
            d[i] = i * 0.5;
            unused[i] = i * 7;
        }
        return ColumnarTable.builder()
                .addColumn(new IntVector("a", a))
                .addColumn(new IntVector("b", b))
                .addColumn(new StringVector("c", c))
                .addColumn(new DoubleVector("d", d))
                .addColumn(new IntVector("unused", unused))
                .build();
    }

    @Test
    void materializesOnlyOutputColumnsAfterFiltering() {
        ColumnarTable table = buildWideTable();
        Query query = Query.builder()
                .where("a", Predicates.intLessThan(100))
                .select("b", "c", "d")
                .build();

        QueryResult result = new QueryExecutor(table)
                .execute(query, ExecutionStrategy.LATE_MATERIALIZATION);

        QueryStats stats = result.stats();
        assertThat(result.rowCount()).isEqualTo(100);
        assertThat(result.rowIds()).isSorted();
        assertThat(stats.candidateSize()).isEqualTo(100);

        assertThat(stats.materializedColumnCount()).isEqualTo(3);
        assertThat(stats.materializedElements()).isEqualTo(300);
        assertThat(stats.filterElementsRead()).isEqualTo(ROWS);
        assertThat(stats.columnsRead()).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(stats.columnsRead()).doesNotContain("unused");
        assertThat(stats.elementsRead()).isEqualTo(ROWS + 300);

        assertThat(stats.fullReadBaselineElements()).isEqualTo(4L * ROWS);
        assertThat(stats.skippedElements()).isEqualTo(4L * ROWS - (ROWS + 300));

        for (int i = 0; i < 100; i++) {
            assertThat(result.intValue("b", i)).isEqualTo(ROWS - 1 - i);
            assertThat(result.stringValue("c", i))
                    .isEqualTo(TestTables.WORDS[i % TestTables.WORDS.length]);
            assertThat(result.doubleValue("d", i)).isEqualTo(i * 0.5);
        }
    }

    @Test
    void naiveBaselineReadsEveryInvolvedColumnFully() {
        ColumnarTable table = buildWideTable();
        Query query = Query.builder()
                .where("a", Predicates.intLessThan(100))
                .select("b", "c", "d")
                .build();

        QueryResult naive = new QueryExecutor(table).executeNaive(query);

        assertThat(naive.rowCount()).isEqualTo(100);
        assertThat(naive.stats().elementsRead()).isEqualTo(4L * ROWS);
        assertThat(naive.stats().skippedElements()).isZero();
        assertThat(naive.stats().materializedColumnCount()).isZero();
    }

    @Test
    void explainReportsComparisonMetrics() {
        ColumnarTable table = buildWideTable();
        Query query = Query.builder()
                .where("a", Predicates.intLessThan(100))
                .select("b")
                .build();

        QueryResult result = new QueryExecutor(table)
                .execute(query, ExecutionStrategy.LATE_MATERIALIZATION);

        assertThat(result.stats().explain())
                .contains("候选集大小: 100")
                .contains("物化列数: 1")
                .contains("全量读取基线")
                .contains("跳过的列读取量")
                .contains("耗时");
    }
}
