package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 按行号区间并行执行延迟物化查询。
 *
 * <p>阶段一: 各线程在互不重叠的行号区间上构建候选集;
 * 阶段二: 对各区间的候选数做前缀和, 确定全局行序中的写入位置;
 * 阶段三: 各线程把本区间的输出列直接物化到结果数组的对应位置。
 * 区间按行号升序划分, 因此合并结果天然保持原有行序, 无需额外排序或拷贝。
 */
public final class ParallelQueryRunner implements AutoCloseable {

    private final ColumnarTable table;
    private final int threads;
    private final ExecutorService pool;

    public ParallelQueryRunner(ColumnarTable table, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        this.table = table;
        this.threads = threads;
        this.pool = Executors.newFixedThreadPool(threads);
    }

    @Override
    public void close() {
        pool.shutdown();
    }

    public QueryResult execute(Query query) {
        long start = System.nanoTime();
        int rows = table.rowCount();
        if (rows == 0) {
            QueryStats stats = new QueryStats();
            Map<String, ColumnVector> projected = QueryExecutor.materialize(
                    table, query.outputColumns(), new int[0], 0, stats);
            stats.fullReadBaselineElements(0);
            stats.elapsedNanos(System.nanoTime() - start);
            return new QueryResult(new int[0], query.outputColumns(), projected,
                    stats, ExecutionStrategy.LATE_MATERIALIZATION);
        }
        int parts = Math.min(threads, rows);
        int chunk = (rows + parts - 1) / parts;

        List<Future<CandidateSetBuilder.CandidateResult>> candidateFutures = new ArrayList<>();
        List<QueryStats> partStats = new ArrayList<>();
        for (int from = 0; from < rows; from += chunk) {
            int partFrom = from;
            int partTo = Math.min(rows, from + chunk);
            QueryStats stats = new QueryStats();
            partStats.add(stats);
            candidateFutures.add(pool.submit(
                    () -> CandidateSetBuilder.build(table, query.filters(), partFrom, partTo, stats)));
        }
        CandidateSetBuilder.CandidateResult[] candidates =
                new CandidateSetBuilder.CandidateResult[candidateFutures.size()];
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = getUnchecked(candidateFutures.get(i));
            partStats.get(i).candidateSize(candidates[i].count());
        }

        int[] offsets = new int[candidates.length];
        int total = 0;
        for (int i = 0; i < candidates.length; i++) {
            offsets[i] = total;
            total += candidates[i].count();
        }

        int[] rowIds = new int[total];
        Map<String, ColumnVector> projected = new LinkedHashMap<>();
        List<Future<?>> materializeFutures = new ArrayList<>();
        for (String name : query.outputColumns()) {
            ColumnVector source = table.column(name);
            Object target = newArray(source, total);
            for (int i = 0; i < candidates.length; i++) {
                CandidateSetBuilder.CandidateResult part = candidates[i];
                int offset = offsets[i];
                QueryStats stats = partStats.get(i);
                materializeFutures.add(pool.submit(() -> {
                    gatherInto(source, part.rowIds(), part.count(), target, offset);
                    stats.record(new ColumnAccess(name, ColumnAccess.Kind.MATERIALIZE, part.count()));
                }));
            }
            projected.put(name, toVector(name, source, target));
        }
        for (int i = 0; i < candidates.length; i++) {
            CandidateSetBuilder.CandidateResult part = candidates[i];
            System.arraycopy(part.rowIds(), 0, rowIds, offsets[i], part.count());
        }
        for (Future<?> future : materializeFutures) {
            getUnchecked(future);
        }

        QueryStats stats = QueryStats.merge(partStats);
        stats.fullReadBaselineElements((long) rows * query.involvedColumns().size());
        stats.elapsedNanos(System.nanoTime() - start);
        return new QueryResult(rowIds, query.outputColumns(), projected,
                stats, ExecutionStrategy.LATE_MATERIALIZATION);
    }

    private static Object newArray(ColumnVector source, int total) {
        return switch (source.type()) {
            case INT -> new int[total];
            case DOUBLE -> new double[total];
            case STRING -> new String[total];
        };
    }

    private static ColumnVector toVector(String name, ColumnVector source, Object array) {
        return switch (source.type()) {
            case INT -> new IntVector(name, (int[]) array);
            case DOUBLE -> new DoubleVector(name, (double[]) array);
            case STRING -> new StringVector(name, (String[]) array);
        };
    }

    private static void gatherInto(ColumnVector source, int[] rowIds, int count,
                                   Object target, int offset) {
        switch (source.type()) {
            case INT -> {
                IntVector intSource = (IntVector) source;
                int[] intTarget = (int[]) target;
                for (int i = 0; i < count; i++) {
                    intTarget[offset + i] = intSource.get(rowIds[i]);
                }
            }
            case DOUBLE -> {
                DoubleVector doubleSource = (DoubleVector) source;
                double[] doubleTarget = (double[]) target;
                for (int i = 0; i < count; i++) {
                    doubleTarget[offset + i] = doubleSource.get(rowIds[i]);
                }
            }
            case STRING -> {
                StringVector stringSource = (StringVector) source;
                String[] stringTarget = (String[]) target;
                for (int i = 0; i < count; i++) {
                    stringTarget[offset + i] = stringSource.get(rowIds[i]);
                }
            }
        }
    }

    private static <T> T getUnchecked(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parallel query interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("parallel query failed", cause);
        }
    }
}
