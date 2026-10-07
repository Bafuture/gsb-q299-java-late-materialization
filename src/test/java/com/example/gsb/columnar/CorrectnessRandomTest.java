package com.example.gsb.columnar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** 要求 4：随机数据下延迟物化结果与全列读取再筛选逐行逐列完全一致。 */
class CorrectnessRandomTest {

    private record TestCase(ColumnStore store, Query query, QueryPlanner.Strategy autoStrategy) {
    }

    private TestCase randomCase(long seed, int rows, int cols) {
        Random random = new Random(seed);
        String[] names = new String[cols];
        Object[][] data = new Object[cols][];
        for (int c = 0; c < cols; c++) {
            names[c] = "col_" + c;
            data[c] = new Integer[rows];
            for (int r = 0; r < rows; r++) {
                data[c][r] = random.nextInt(100);
            }
        }
        ColumnStore store = ColumnStore.inMemory("rand", names, data);

        int filterCount = 1 + random.nextInt(3); // 1..3 个过滤条件
        List<Filter> filters = new ArrayList<>();
        List<String> usedColumns = new ArrayList<>();
        for (int f = 0; f < filterCount; f++) {
            String col = names[random.nextInt(Math.min(cols, 6))]; // 过滤多集中在前几列
            usedColumns.add(col);
            int threshold = random.nextInt(100);
            ColumnPredicate predicate = switch (random.nextInt(4)) {
                case 0 -> ColumnPredicate.greaterThan(threshold);
                case 1 -> ColumnPredicate.lessThan(threshold);
                case 2 -> ColumnPredicate.greaterOrEqual(threshold);
                default -> ColumnPredicate.equalTo(threshold);
            };
            filters.add(Filter.on(col, predicate));
        }
        int outputCount = 1 + random.nextInt(cols);
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < outputCount; i++) {
            String col = names[random.nextInt(cols)];
            if (!outputs.contains(col)) {
                outputs.add(col);
            }
        }
        // 确保输出包含至少一个筛选列，覆盖缓存复用路径
        if (Math.random() < 0.5 && !outputs.contains(usedColumns.get(0))) {
            outputs.add(usedColumns.get(0));
        }
        Query query = new Query(filters, outputs);
        QueryPlanner.Plan plan = new QueryPlanner(128).plan(store, query);
        return new TestCase(store, query, plan.strategy());
    }

    /** 朴素参考实现：直接遍历全行，逐条件判断后取输出列。 */
    private QueryResult naiveReference(ColumnStore store, Query query) {
        int rows = store.rowCount();
        List<Integer> matched = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            boolean pass = true;
            for (Filter filter : query.filters()) {
                if (!filter.predicate().test(store.peek(filter.column(), r))) {
                    pass = false;
                    break;
                }
            }
            if (pass) {
                matched.add(r);
            }
        }
        int[] rowIds = matched.stream().mapToInt(Integer::intValue).toArray();
        List<Object[]> values = new ArrayList<>();
        for (String col : query.outputColumns()) {
            Object[] arr = new Object[rowIds.length];
            for (int i = 0; i < rowIds.length; i++) {
                arr[i] = store.peek(col, rowIds[i]);
            }
            values.add(arr);
        }
        return new QueryResult(rowIds, query.outputColumns(), values, null);
    }

    private void assertResultsEqual(QueryResult expected, QueryResult actual) {
        assertThat(actual.rowIds()).containsExactly(expected.rowIds());
        assertThat(actual.columns()).containsExactlyElementsOf(expected.columns());
        for (int c = 0; c < expected.columns().size(); c++) {
            assertThat(actual.values().get(c))
                    .as("output column %s", expected.columns().get(c))
                    .containsExactly(expected.values().get(c));
        }
    }

    @RepeatedTest(50)
    void lateMatchesNaiveReferenceOnRandomData() {
        long seed = 1000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        TestCase tc = randomCase(seed, 500, 1 + new Random(seed).nextInt(20));
        QueryResult expected = naiveReference(tc.store(), tc.query());
        QueryResult late = new QueryExecutor(tc.store()).execute(tc.query(), QueryExecutor.Mode.LATE);
        assertResultsEqual(expected, late);
        assertThat(late.rowIds()).isSorted();
    }

    @RepeatedTest(50)
    void earlyAndLateAndAutoAllAgreeOnRandomData() {
        long seed = 2000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        TestCase tc = randomCase(seed, 800, 1 + new Random(seed).nextInt(24));
        QueryExecutor executor = new QueryExecutor(tc.store(), new QueryPlanner(128));
        QueryResult expected = naiveReference(tc.store(), tc.query());
        QueryResult late = executor.execute(tc.query(), QueryExecutor.Mode.LATE);
        QueryResult early = executor.execute(tc.query(), QueryExecutor.Mode.EARLY);
        QueryResult auto = executor.execute(tc.query(), QueryExecutor.Mode.AUTO);
        assertResultsEqual(expected, late);
        assertResultsEqual(expected, early);
        assertResultsEqual(expected, auto);
        // 自动选择的结果也正确，且与规划器声明的策略一致
        assertThat(auto.stats().strategy()).isEqualTo(tc.autoStrategy().name());
    }

    @Test
    void parallelAlsoMatchesNaiveReferenceOnRandomData() {
        for (long seed = 1; seed <= 20; seed++) {
            TestCase tc = randomCase(seed, 777, 1 + new Random(seed).nextInt(16));
            QueryResult expected = naiveReference(tc.store(), tc.query());
            QueryResult parallel = new ParallelQueryExecutor(tc.store(), 4)
                    .execute(tc.query());
            assertResultsEqual(expected, parallel);
            assertThat(parallel.rowIds()).isSorted();
        }
    }
}
