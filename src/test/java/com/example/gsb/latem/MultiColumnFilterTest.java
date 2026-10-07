package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.exec.CandidateSetBuilder;
import com.example.gsb.latem.exec.ColumnAccess;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.exec.QueryStats;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.Predicates;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiColumnFilterTest {

    private static final int ROWS = 1000;

    private static ColumnarTable buildTable() {
        int[] a = new int[ROWS];
        int[] b = new int[ROWS];
        int[] c = new int[ROWS];
        for (int i = 0; i < ROWS; i++) {
            a[i] = i % 10;
            b[i] = i % 4;
            c[i] = i % 2;
        }
        return ColumnarTable.builder()
                .addColumn(new IntVector("a", a))
                .addColumn(new IntVector("b", b))
                .addColumn(new IntVector("c", c))
                .build();
    }

    @Test
    void filtersShrinkCandidateSetStepByStep() {
        ColumnarTable table = buildTable();
        QueryStats stats = new QueryStats();
        List<ColumnFilter> filters = List.of(
                new ColumnFilter("a", Predicates.intLessThan(5)),
                new ColumnFilter("b", Predicates.intLessThan(2)),
                new ColumnFilter("c", Predicates.intLessThan(1)));

        CandidateSetBuilder.CandidateResult result =
                CandidateSetBuilder.build(table, filters, 0, ROWS, stats);

        assertThat(result.count()).isEqualTo(150);
        List<ColumnAccess> accesses = stats.accesses();
        assertThat(accesses).hasSize(3);
        assertThat(accesses.get(0).kind()).isEqualTo(ColumnAccess.Kind.SCAN);
        assertThat(accesses.get(0).elements()).isEqualTo(ROWS);
        assertThat(accesses.get(1)).extracting(ColumnAccess::kind, ColumnAccess::elements)
                .containsExactly(ColumnAccess.Kind.SPARSE, 500);
        assertThat(accesses.get(2)).extracting(ColumnAccess::kind, ColumnAccess::elements)
                .containsExactly(ColumnAccess.Kind.SPARSE, 250);
    }

    @Test
    void multiFilterResultMatchesManualComputation() {
        ColumnarTable table = buildTable();
        Query query = Query.builder()
                .where("a", Predicates.intLessThan(5))
                .where("b", Predicates.intLessThan(2))
                .where("c", Predicates.intLessThan(1))
                .select("a", "b", "c")
                .build();

        QueryResult result = new QueryExecutor(table)
                .execute(query, ExecutionStrategy.LATE_MATERIALIZATION);

        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            if (i % 10 < 5 && i % 4 < 2 && i % 2 == 0) {
                expected.add(i);
            }
        }
        assertThat(result.rowCount()).isEqualTo(expected.size());
        assertThat(result.rowIds())
                .containsExactly(expected.stream().mapToInt(Integer::intValue).toArray());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(result.intValue("a", i)).isEqualTo(expected.get(i) % 10);
            assertThat(result.intValue("b", i)).isEqualTo(expected.get(i) % 4);
            assertThat(result.intValue("c", i)).isZero();
        }
    }
}
