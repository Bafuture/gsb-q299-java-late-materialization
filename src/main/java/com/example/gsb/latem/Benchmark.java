package com.example.gsb.latem;

import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.exec.ExecutionStrategy;
import com.example.gsb.latem.exec.ParallelQueryRunner;
import com.example.gsb.latem.exec.Query;
import com.example.gsb.latem.exec.QueryExecutor;
import com.example.gsb.latem.exec.QueryPlanner;
import com.example.gsb.latem.exec.QueryResult;
import com.example.gsb.latem.predicate.Predicates;

import java.util.Locale;
import java.util.Random;

public final class Benchmark {

    private static final int ROWS = 3_000_000;
    private static final int COLUMNS = 10;

    private Benchmark() {
    }

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "列存表: %d 行 x %d 列 (INT)%n%n", ROWS, COLUMNS);
        ColumnarTable table = buildTable();
        QueryExecutor executor = new QueryExecutor(table);

        Query strongFilter = Query.builder()
                .where("c0", Predicates.intLessThan(5))
                .where("c1", Predicates.intLessThan(100))
                .select("c2", "c3", "c4")
                .build();
        Query weakFilter = Query.builder()
                .where("c0", Predicates.intLessThan(950))
                .where("c1", Predicates.intLessThan(900))
                .select("c2", "c3", "c4")
                .build();

        runScenario("场景 A: 高选择性过滤 (存活约 0.05%)", executor, strongFilter);
        runScenario("场景 B: 低选择性过滤 (存活约 85%)", executor, weakFilter);
        runParallel(table, weakFilter);
    }

    private static void runScenario(String title, QueryExecutor executor, Query query) {
        System.out.println("== " + title + " ==");
        QueryPlanner.Plan plan = executor.plan(query);
        System.out.println("规划器: " + plan.rationale());
        for (ExecutionStrategy strategy : ExecutionStrategy.values()) {
            executor.execute(query, strategy);
            long best = Long.MAX_VALUE;
            QueryResult result = null;
            for (int i = 0; i < 3; i++) {
                result = executor.execute(query, strategy);
                best = Math.min(best, result.stats().elapsedNanos());
            }
            System.out.printf(Locale.ROOT,
                    "  %-22s 候选=%-8d 读取元素=%-10d 物化列数=%d 跳过读取=%-10d 耗时=%.2f ms%n",
                    strategy, result.rowCount(), result.stats().elementsRead(),
                    result.stats().materializedColumnCount(),
                    result.stats().skippedElements(), best / 1_000_000.0);
        }
        System.out.printf(Locale.ROOT, "  模型成本: 延迟物化=%.0f, 提前物化=%.0f%n%n",
                plan.lateCost(), plan.earlyCost());
    }

    private static void runParallel(ColumnarTable table, Query query) {
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.println("== 并发合并 (场景 B 查询, " + threads + " 线程) ==");
        QueryExecutor executor = new QueryExecutor(table);
        try (ParallelQueryRunner parallel = new ParallelQueryRunner(table, threads)) {
            executor.execute(query, ExecutionStrategy.LATE_MATERIALIZATION);
            parallel.execute(query);
            long sequentialBest = Long.MAX_VALUE;
            long parallelBest = Long.MAX_VALUE;
            QueryResult sequential = null;
            QueryResult merged = null;
            for (int i = 0; i < 3; i++) {
                sequential = executor.execute(query, ExecutionStrategy.LATE_MATERIALIZATION);
                sequentialBest = Math.min(sequentialBest, sequential.stats().elapsedNanos());
                merged = parallel.execute(query);
                parallelBest = Math.min(parallelBest, merged.stats().elapsedNanos());
            }
            boolean identical = java.util.Arrays.equals(sequential.rowIds(), merged.rowIds());
            System.out.printf(Locale.ROOT,
                    "  单线程: %.2f ms, %d 线程: %.2f ms, 加速比 %.2fx, 结果一致: %s%n",
                    sequentialBest / 1_000_000.0, threads, parallelBest / 1_000_000.0,
                    (double) sequentialBest / parallelBest, identical);
        }
    }

    private static ColumnarTable buildTable() {
        Random random = new Random(42);
        ColumnarTable.Builder builder = ColumnarTable.builder();
        for (int c = 0; c < COLUMNS; c++) {
            int[] values = new int[ROWS];
            for (int i = 0; i < ROWS; i++) {
                values[i] = random.nextInt(1000);
            }
            builder.addColumn(new IntVector("c" + c, values));
        }
        return builder.build();
    }
}
