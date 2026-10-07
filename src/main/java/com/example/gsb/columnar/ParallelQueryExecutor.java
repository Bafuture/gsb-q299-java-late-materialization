package com.example.gsb.columnar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并发延迟物化执行器：把行号区间切成若干连续分片，每个线程在自己的分片上
 * 独立完成“过滤 → 物化”，最后按分片顺序拼接，保证结果保持原有行序。
 *
 * <p>读取统计通过 {@link ColumnStore} 的共享原子计数器自动汇总。</p>
 */
public final class ParallelQueryExecutor {

    private final ColumnStore store;
    private final int threads;

    public ParallelQueryExecutor(ColumnStore store, int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive");
        }
        this.store = store;
        this.threads = threads;
    }

    private record ShardResult(int[] rows, List<Object[]> values, List<Integer> candidateSizes) {
    }

    public QueryResult execute(Query query) {
        store.resetStats();
        long start = System.nanoTime();
        int n = store.rowCount();
        int shards = Math.min(threads, Math.max(1, n));
        int base = n / shards;
        int extra = n % shards;

        ExecutorService pool = Executors.newFixedThreadPool(shards);
        try {
            List<Future<ShardResult>> futures = new ArrayList<>();
            int from = 0;
            for (int s = 0; s < shards; s++) {
                int length = base + (s < extra ? 1 : 0);
                int shardFrom = from;
                from += length;
                futures.add(pool.submit(new ShardTask(query, shardFrom, length)));
            }
            List<ShardResult> parts = new ArrayList<>();
            for (Future<ShardResult> future : futures) {
                parts.add(getUnchecked(future));
            }
            return merge(query, parts, System.nanoTime() - start);
        } finally {
            pool.shutdown();
        }
    }

    private ShardResult getUnchecked(Future<ShardResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for shard", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("shard failed", e.getCause());
        }
    }

    /** 单个分片：完整的延迟物化流水线，只触碰 [from, from+length) 行。 */
    private final class ShardTask implements Callable<ShardResult> {
        private final Query query;
        private final int from;
        private final int length;

        ShardTask(Query query, int from, int length) {
            this.query = query;
            this.from = from;
            this.length = length;
        }

        @Override
        public ShardResult call() {
            List<Integer> candidateSizes = new ArrayList<>();
            CandidateSet candidates = CandidateSet.scan(store, query.filters().get(0), from, length);
            candidateSizes.add(candidates.size());
            for (int i = 1; i < query.filters().size(); i++) {
                candidates = candidates.refine(store, query.filters().get(i));
                candidateSizes.add(candidates.size());
            }
            List<Object[]> values = new ArrayList<>();
            for (String out : query.outputColumns()) {
                values.add(candidates.materializeColumn(store, out));
            }
            return new ShardResult(candidates.rawRows(), values, candidateSizes);
        }
    }

    /** 按分片顺序拼接：分片是连续行号区间，顺序拼接即保持原有行序。 */
    private QueryResult merge(Query query, List<ShardResult> parts, long elapsed) {
        int total = 0;
        for (ShardResult part : parts) {
            total += part.rows().length;
        }
        int[] rows = new int[total];
        int offset = 0;
        for (ShardResult part : parts) {
            System.arraycopy(part.rows(), 0, rows, offset, part.rows().length);
            offset += part.rows().length;
        }
        List<Object[]> values = new ArrayList<>();
        for (int c = 0; c < query.outputColumns().size(); c++) {
            Object[] merged = new Object[total];
            int pos = 0;
            for (ShardResult part : parts) {
                Object[] column = part.values().get(c);
                System.arraycopy(column, 0, merged, pos, column.length);
                pos += column.length;
            }
            values.add(merged);
        }
        // 候选集大小按过滤阶段跨分片求和。
        int stages = query.filters().size();
        List<Integer> candidateSizes = new ArrayList<>(stages);
        for (int stage = 0; stage < stages; stage++) {
            int sum = 0;
            for (ShardResult part : parts) {
                sum += part.candidateSizes().get(stage);
            }
            candidateSizes.add(sum);
        }
        QueryStats stats = QueryStats.capture("PARALLEL_LATE", store, candidateSizes,
                query.outputColumns().size(), elapsed);
        return new QueryResult(rows, query.outputColumns(), values, stats);
    }
}
