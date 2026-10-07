package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.exec.CandidateSetBuilder;
import com.example.gsb.latem.exec.ColumnAccess;
import com.example.gsb.latem.exec.QueryStats;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSetConstructionTest {

    private static ColumnarTable tableWithSequence() {
        int rows = 1000;
        int[] sequence = new int[rows];
        int[] extra = new int[rows];
        for (int i = 0; i < rows; i++) {
            sequence[i] = i;
            extra[i] = rows - i;
        }
        return ColumnarTable.builder()
                .addColumn(new IntVector("a", sequence))
                .addColumn(new IntVector("b", extra))
                .build();
    }

    @Test
    void candidatesContainExactlyQualifyingRows() {
        ColumnarTable table = tableWithSequence();
        QueryStats stats = new QueryStats();

        CandidateSetBuilder.CandidateResult result = CandidateSetBuilder.build(
                table, List.of(new ColumnFilter("a", Predicates.intGreaterOrEqual(900))),
                0, table.rowCount(), stats);

        assertThat(result.count()).isEqualTo(100);
        assertThat(result.toArray()).isSorted().containsExactly(
                java.util.stream.IntStream.range(900, 1000).toArray());
    }

    @Test
    void candidateConstructionReadsOnlyTheFilterColumn() {
        ColumnarTable table = tableWithSequence();
        QueryStats stats = new QueryStats();

        CandidateSetBuilder.build(
                table, List.of(new ColumnFilter("a", Predicates.intGreaterOrEqual(900))),
                0, table.rowCount(), stats);

        assertThat(stats.columnsRead()).containsExactly("a");
        assertThat(stats.elementsRead()).isEqualTo(table.rowCount());
        assertThat(stats.accesses())
                .allSatisfy(access -> assertThat(access.kind()).isEqualTo(ColumnAccess.Kind.SCAN));
    }

    @Test
    void emptyCandidateSetWhenNothingMatches() {
        ColumnarTable table = tableWithSequence();
        QueryStats stats = new QueryStats();

        CandidateSetBuilder.CandidateResult result = CandidateSetBuilder.build(
                table, List.of(new ColumnFilter("a", Predicates.intLessThan(0))),
                0, table.rowCount(), stats);

        assertThat(result.count()).isZero();
        assertThat(result.toArray()).isEmpty();
        assertThat(stats.candidateSize()).isZero();
    }

    @Test
    void candidateSetRestrictedToRowRange() {
        ColumnarTable table = tableWithSequence();
        QueryStats stats = new QueryStats();

        CandidateSetBuilder.CandidateResult result = CandidateSetBuilder.build(
                table, List.of(new ColumnFilter("a", Predicates.intGreaterOrEqual(0))),
                100, 200, stats);

        assertThat(result.count()).isEqualTo(100);
        assertThat(result.rowIds()[0]).isEqualTo(100);
        assertThat(result.rowIds()[99]).isEqualTo(199);
        assertThat(stats.elementsRead()).isEqualTo(100);
    }
}
