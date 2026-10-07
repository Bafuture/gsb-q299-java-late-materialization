package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.exec.CandidateSetBuilder;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryStats;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnarTableTest {

    @Test
    void builderRejectsMismatchedColumnSizes() {
        ColumnarTable.Builder builder = ColumnarTable.builder()
                .addColumn(new IntVector("a", new int[10]));

        assertThatThrownBy(() -> builder.addColumn(new IntVector("b", new int[5])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b");
    }

    @Test
    void builderRejectsDuplicateColumnNames() {
        ColumnarTable.Builder builder = ColumnarTable.builder()
                .addColumn(new IntVector("a", new int[10]));

        assertThatThrownBy(() -> builder.addColumn(new IntVector("a", new int[10])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownColumnLookupFails() {
        ColumnarTable table = ColumnarTable.builder()
                .addColumn(new IntVector("a", new int[3]))
                .build();

        assertThat(table.rowCount()).isEqualTo(3);
        assertThat(table.columnNames()).containsExactly("a");
        assertThatThrownBy(() -> table.column("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRequiresAtLeastOneFilter() {
        assertThatThrownBy(() -> Query.builder().select("a").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void predicateTypeMustMatchColumnType() {
        ColumnarTable table = ColumnarTable.builder()
                .addColumn(new IntVector("a", new int[] {1, 2, 3}))
                .build();
        List<ColumnFilter> filters = List.of(
                new ColumnFilter("a", Predicates.stringEq("x")));

        assertThatThrownBy(() -> CandidateSetBuilder.build(
                table, filters, 0, table.rowCount(), new QueryStats()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STRING");
    }
}
