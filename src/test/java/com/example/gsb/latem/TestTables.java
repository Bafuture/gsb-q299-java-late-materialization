package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.Predicates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

final class TestTables {

    static final String[] WORDS = {
            "red", "green", "blue", "yellow", "black", "white", "gray", "pink"
    };

    private TestTables() {
    }

    static ColumnarTable randomTable(Random random, int rows,
                                     int intColumns, int doubleColumns, int stringColumns) {
        ColumnarTable.Builder builder = ColumnarTable.builder();
        for (int c = 0; c < intColumns; c++) {
            int[] values = new int[rows];
            for (int i = 0; i < rows; i++) {
                values[i] = random.nextInt(100);
            }
            builder.addColumn(new IntVector("i" + c, values));
        }
        for (int c = 0; c < doubleColumns; c++) {
            double[] values = new double[rows];
            for (int i = 0; i < rows; i++) {
                values[i] = random.nextDouble();
            }
            builder.addColumn(new DoubleVector("d" + c, values));
        }
        for (int c = 0; c < stringColumns; c++) {
            String[] values = new String[rows];
            for (int i = 0; i < rows; i++) {
                values[i] = WORDS[random.nextInt(WORDS.length)];
            }
            builder.addColumn(new StringVector("s" + c, values));
        }
        return builder.build();
    }

    static ColumnFilter randomFilter(Random random, ColumnarTable table) {
        List<String> names = table.columnNames();
        String name = names.get(random.nextInt(names.size()));
        ColumnVector column = table.column(name);
        return switch (column.type()) {
            case INT -> {
                int low = random.nextInt(100);
                int high = random.nextInt(100);
                yield new ColumnFilter(name,
                        Predicates.intBetween(Math.min(low, high), Math.max(low, high)));
            }
            case DOUBLE -> new ColumnFilter(name, Predicates.doubleLessThan(random.nextDouble()));
            case STRING -> new ColumnFilter(name,
                    Predicates.stringEq(WORDS[random.nextInt(WORDS.length)]));
        };
    }

    static Query randomQuery(Random random, ColumnarTable table) {
        Query.Builder builder = Query.builder();
        int filterCount = 1 + random.nextInt(3);
        for (int i = 0; i < filterCount; i++) {
            ColumnFilter filter = randomFilter(random, table);
            builder.where(filter.column(), filter.predicate());
        }
        List<String> names = new ArrayList<>(table.columnNames());
        Collections.shuffle(names, random);
        int outputCount = 1 + random.nextInt(names.size());
        builder.select(names.subList(0, outputCount).toArray(new String[0]));
        return builder.build();
    }

    static void assertResultsEqual(QueryResult expected, QueryResult actual) {
        assertThat(actual.rowIds()).containsExactly(expected.rowIds());
        assertThat(actual.columns()).containsExactlyElementsOf(expected.columns());
        for (String name : expected.columns()) {
            ColumnVector expectedColumn = expected.column(name);
            ColumnVector actualColumn = actual.column(name);
            assertThat(actualColumn.size()).isEqualTo(expectedColumn.size());
            for (int i = 0; i < expectedColumn.size(); i++) {
                assertThat(actualColumn.valueAt(i)).isEqualTo(expectedColumn.valueAt(i));
            }
        }
    }
}
