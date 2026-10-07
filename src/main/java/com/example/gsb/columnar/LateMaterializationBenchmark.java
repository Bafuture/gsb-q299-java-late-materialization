package com.example.gsb.columnar;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 延迟物化 vs 提前物化的耗时对比演示。
 *
 * <p>运行：{@code mvn -q compile exec:java} 或直接运行 main。</p>
 */
public final class LateMaterializationBenchmark {

    private LateMaterializationBenchmark() {
    }

    public static void main(String[] args) {
        int rows = 200_000;
        int columns = 64;

        System.out.println("=== 场景 1：高选择性（过滤条件区分度高，p≈1%），宽表 64 列，输出 2 列 ===");
        runScenario(rows, columns, 0.01, 2);

        System.out.println();
        System.out.println("=== 场景 2：低选择性（过滤条件区分度低，p≈60%），宽表 64 列，输出 60 列 ===");
        runScenario(rows, columns, 0.60, 60);
    }

    private static void runScenario(int rows, int columns, double hitRate, int outputColumns) {
        String[] names = new String[columns];
        Object[][] data = new Object[columns][];
        Random random = new Random(42);
        for (int c = 0; c < columns; c++) {
            names[c] = "c" + c;
            data[c] = new Object[rows];
            for (int r = 0; r < rows; r++) {
                data[c][r] = random.nextInt(10_000);
            }
        }
        ColumnStore store = ColumnStore.withSimulatedIo("bench", names, data);

        int threshold = (int) (hitRate * 10_000);
        Query query = new Query(
                List.of(Filter.on("c0", ColumnPredicate.lessThan(threshold))),
                outputList(outputColumns));

        QueryExecutor executor = new QueryExecutor(store);
        // JIT 预热
        for (int i = 0; i < 2; i++) {
            executor.execute(query, QueryExecutor.Mode.LATE);
            executor.execute(query, QueryExecutor.Mode.EARLY);
        }
        QueryResult late = bestOf(executor, query, QueryExecutor.Mode.LATE, 3);
        QueryResult early = bestOf(executor, query, QueryExecutor.Mode.EARLY, 3);

        System.out.printf("  延迟物化 LATE : %8.2f ms  %s%n", late.stats().elapsedNanos() / 1e6, late.stats());
        System.out.printf("  提前物化 EARLY: %8.2f ms  %s%n", early.stats().elapsedNanos() / 1e6, early.stats());
        System.out.printf("  规划器选择    : %s%n", new QueryPlanner(1024).plan(store, query));
        if (!java.util.Arrays.equals(late.rowIds(), early.rowIds())) {
            throw new IllegalStateException("results differ!");
        }
    }

    private static QueryResult bestOf(QueryExecutor executor, Query query, QueryExecutor.Mode mode, int reps) {
        QueryResult best = null;
        for (int i = 0; i < reps; i++) {
            QueryResult r = executor.execute(query, mode);
            if (best == null || r.stats().elapsedNanos() < best.stats().elapsedNanos()) {
                best = r;
            }
        }
        return best;
    }

    private static List<String> outputList(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add("c" + i);
        }
        return out;
    }
}
