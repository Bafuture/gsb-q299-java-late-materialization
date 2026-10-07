package com.example.gsb.columnar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单线程查询执行器：支持延迟物化 / 提前物化 / 自动选择三种模式。
 */
public final class QueryExecutor {

    public enum Mode {
        LATE, EARLY, AUTO
    }

    private final ColumnStore store;
    private final QueryPlanner planner;

    public QueryExecutor(ColumnStore store) {
        this(store, QueryPlanner.withSampleSize(1024));
    }

    public QueryExecutor(ColumnStore store, QueryPlanner planner) {
        this.store = store;
        this.planner = planner;
    }

    public QueryResult execute(Query query, Mode mode) {
        QueryPlanner.Strategy strategy = switch (mode) {
            case LATE -> QueryPlanner.Strategy.LATE;
            case EARLY -> QueryPlanner.Strategy.EARLY;
            case AUTO -> planner.plan(store, query).strategy();
        };
        store.resetStats();
        long start = System.nanoTime();
        QueryResult result = strategy == QueryPlanner.Strategy.LATE
                ? executeLate(query)
                : executeEarly(query);
        return result;
    }

    /** 延迟物化：先构造候选集（只读筛选列），最后按行号物化输出列。 */
    QueryResult executeLate(Query query) {
        long start = System.nanoTime();
        List<Integer> candidateSizes = new ArrayList<>();
        CandidateSet candidates = CandidateSet.scan(store, query.filters().get(0));
        candidateSizes.add(candidates.size());
        for (int i = 1; i < query.filters().size(); i++) {
            candidates = candidates.refine(store, query.filters().get(i));
            candidateSizes.add(candidates.size());
        }
        List<Object[]> values = new ArrayList<>();
        for (String out : query.outputColumns()) {
            values.add(candidates.materializeColumn(store, out));
        }
        long elapsed = System.nanoTime() - start;
        QueryStats stats = QueryStats.capture("LATE", store, candidateSizes,
                query.outputColumns().size(), elapsed);
        return new QueryResult(candidates.rows(), query.outputColumns(), values, stats);
    }

    /** 提前物化：先整读全部输出列，再按筛选列整列扫描过滤（对照组）。 */
    QueryResult executeEarly(Query query) {
        long start = System.nanoTime();
        List<String> outputs = query.outputColumns();
        Map<String, Object[]> fullColumns = new LinkedHashMap<>();
        for (String out : outputs) {
            fullColumns.put(out, store.readColumn(out));
        }
        List<Integer> candidateSizes = new ArrayList<>();
        int[] rows = null;
        for (Filter filter : query.filters()) {
            Object[] column = fullColumns.computeIfAbsent(filter.column(), store::readColumn);
            if (rows == null) {
                int[] matched = new int[store.rowCount()];
                int count = 0;
                for (int r = 0; r < store.rowCount(); r++) {
                    if (filter.predicate().test(column[r])) {
                        matched[count++] = r;
                    }
                }
                rows = new int[count];
                System.arraycopy(matched, 0, rows, 0, count);
            } else {
                int count = 0;
                for (int r : rows) {
                    if (filter.predicate().test(column[r])) {
                        rows[count++] = r;
                    }
                }
                int[] shrunk = new int[count];
                System.arraycopy(rows, 0, shrunk, 0, count);
                rows = shrunk;
            }
            candidateSizes.add(rows.length);
        }
        List<Object[]> values = new ArrayList<>();
        for (String out : outputs) {
            Object[] full = fullColumns.get(out);
            Object[] gathered = new Object[rows.length];
            for (int i = 0; i < rows.length; i++) {
                gathered[i] = full[rows[i]];
            }
            values.add(gathered);
        }
        long elapsed = System.nanoTime() - start;
        QueryStats stats = QueryStats.capture("EARLY", store, candidateSizes,
                outputs.size(), elapsed);
        return new QueryResult(rows, outputs, values, stats);
    }
}
