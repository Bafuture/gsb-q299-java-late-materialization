package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 要求 2：延迟物化先过滤后取列，并与全量读取对比。 */
class LateMaterializationTest {

    private ColumnStore wideStore(int rows, int cols) {
        String[] names = new String[cols];
        Object[][] data = new Object[cols][];
        for (int c = 0; c < cols; c++) {
            names[c] = "c" + c;
            data[c] = new Object[rows];
            for (int r = 0; r < rows; r++) {
                data[c][r] = r * cols + c;
            }
        }
        return ColumnStore.inMemory("wide", names, data);
    }

    @Test
    void lateMaterializationReadsOnlyFilterAndOutputColumns() {
        int rows = 1_000, cols = 50;
        ColumnStore store = wideStore(rows, cols);
        Query query = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(100 * cols))),
                List.of("c1", "c2"));

        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);

        assertThat(result.rowCount()).isEqualTo(100);
        assertThat(result.rowIds()[0]).isEqualTo(0);
        assertThat(result.valueAt(0, "c1")).isEqualTo(1);
        assertThat(result.valueAt(99, "c2")).isEqualTo(99 * cols + 2);

        QueryStats stats = result.stats();
        // 物化列数 = 输出列数；全量读取需要物化全部 50 列
        assertThat(stats.materializedColumns()).isEqualTo(2);
        assertThat(stats.columnsRead()).isEqualTo(3); // c0(筛选) + c1,c2(输出)
        assertThat(stats.skippedColumns()).isEqualTo(47);
        assertThat(stats.skippedCellReads()).isEqualTo(47L * rows);
        assertThat(stats.fullReadUnits()).isEqualTo(50L * rows);
        // 实际读取：c0 全列 1000 + 2 列各点取 100 行 * RANDOM_PENALTY
        assertThat(stats.totalReadUnits())
                .isEqualTo(1000 + 2L * 100 * ColumnStore.RANDOM_PENALTY);
        assertThat(stats.totalReadUnits()).isLessThan(stats.fullReadUnits());
        assertThat(stats.readSavingRatio()).isGreaterThan(0.9);
    }

    @Test
    void materializedColumnCountContrastsWithFullRead() {
        ColumnStore store = wideStore(500, 40);
        Query query = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(50 * 40))),
                List.of("c5", "c6", "c7"));
        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
        // 物化 3 列 vs 全量 40 列
        assertThat(result.stats().materializedColumns()).isEqualTo(3);
        assertThat(store.columnCount()).isEqualTo(40);
        assertThat(result.stats().columnsFullyScanned()).isEqualTo(1); // 只有筛选列整列扫描
    }

    @Test
    void filterColumnInOutputIsReusedFromCache() {
        ColumnStore store = wideStore(200, 10);
        Query query = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(10 * 10))),
                List.of("c0", "c1"));
        QueryResult result = new QueryExecutor(store).execute(query, QueryExecutor.Mode.LATE);
        // c0 已在过滤阶段读到，物化只点取 c1 的 10 行
        assertThat(result.stats().randomCellsRead()).isEqualTo(10);
        assertThat(result.valueAt(3, "c0")).isEqualTo(3 * 10);
    }
}
