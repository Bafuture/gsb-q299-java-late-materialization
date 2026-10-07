package com.example.gsb.latem.exec;

import com.example.gsb.latem.column.ColumnVector;
import com.example.gsb.latem.column.ColumnarTable;
import com.example.gsb.latem.column.DoubleVector;
import com.example.gsb.latem.column.IntVector;
import com.example.gsb.latem.column.StringVector;
import com.example.gsb.latem.predicate.ColumnFilter;
import com.example.gsb.latem.predicate.ColumnPredicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QueryExecutor {

    private final ColumnarTable table;
    private final CostModel costModel;

    public QueryExecutor(ColumnarTable table) {
        this(table, CostModel.defaults());
    }

    public QueryExecutor(ColumnarTable table, CostModel costModel) {
        this.table = table;
        this.costModel = costModel;
    }

    public ColumnarTable table() {
        return table;
    }

    public CostModel costModel() {
        return costModel;
    }

    public QueryPlanner.Plan plan(Query query) {
        return QueryPlanner.plan(table, query, costModel,
                SelectivityEstimator.DEFAULT_SAMPLE_SIZE, new QueryStats());
    }

    public QueryResult execute(Query query) {
        QueryStats stats = new QueryStats();
        QueryPlanner.Plan plan = QueryPlanner.plan(table, query, costModel,
                SelectivityEstimator.DEFAULT_SAMPLE_SIZE, stats);
        return finish(query, run(query, plan.strategy(), stats), stats);
    }

    public QueryResult execute(Query query, ExecutionStrategy strategy) {
        QueryStats stats = new QueryStats();
        return finish(query, run(query, strategy, stats), stats);
    }

    public QueryResult executeNaive(Query query) {
        QueryStats stats = new QueryStats();
        return finish(query, run(query, ExecutionStrategy.NAIVE_FULL_READ, stats), stats);
    }

    private QueryResult finish(Query query, QueryResult result, QueryStats stats) {
        stats.fullReadBaselineElements((long) table.rowCount() * query.involvedColumns().size());
        return result;
    }

    private QueryResult run(Query query, ExecutionStrategy strategy, QueryStats stats) {
        long start = System.nanoTime();
        QueryResult result = switch (strategy) {
            case LATE_MATERIALIZATION -> runLate(query, stats);
            case EARLY_MATERIALIZATION -> runEarly(query, stats);
            case NAIVE_FULL_READ -> runNaive(query, stats);
        };
        stats.elapsedNanos(System.nanoTime() - start);
        return result;
    }

    private QueryResult runLate(Query query, QueryStats stats) {
        CandidateSetBuilder.CandidateResult candidates = CandidateSetBuilder.build(
                table, query.filters(), 0, table.rowCount(), stats);
        stats.candidateSize(candidates.count());
        Map<String, ColumnVector> projected = materialize(
                table, query.outputColumns(), candidates.rowIds(), candidates.count(), stats);
        return new QueryResult(candidates.toArray(), query.outputColumns(), projected,
                stats, ExecutionStrategy.LATE_MATERIALIZATION);
    }

    private QueryResult runEarly(Query query, QueryStats stats) {
        List<ColumnFilter> filters = query.filters();
        CandidateSetBuilder.CandidateResult first = CandidateSetBuilder.build(
                table, filters.subList(0, 1), 0, table.rowCount(), stats);

        Set<String> toMaterialize = new LinkedHashSet<>();
        for (int i = 1; i < filters.size(); i++) {
            toMaterialize.add(filters.get(i).column());
        }
        toMaterialize.addAll(query.outputColumns());
        Map<String, ColumnVector> tuples = materialize(
                table, List.copyOf(toMaterialize), first.rowIds(), first.count(), stats);

        int[] keep = new int[first.count()];
        for (int i = 0; i < first.count(); i++) {
            keep[i] = i;
        }
        int kept = first.count();
        for (int i = 1; i < filters.size(); i++) {
            ColumnVector column = tuples.get(filters.get(i).column());
            ColumnPredicate predicate = filters.get(i).predicate();
            int next = 0;
            for (int j = 0; j < kept; j++) {
                if (CandidateSetBuilder.matches(column, keep[j], predicate)) {
                    keep[next++] = keep[j];
                }
            }
            kept = next;
        }
        stats.candidateSize(kept);

        int[] rowIds = new int[kept];
        for (int i = 0; i < kept; i++) {
            rowIds[i] = first.rowIds()[keep[i]];
        }
        Map<String, ColumnVector> projected = new LinkedHashMap<>();
        for (String name : query.outputColumns()) {
            projected.put(name, gather(tuples.get(name), keep, kept));
        }
        return new QueryResult(rowIds, query.outputColumns(), projected,
                stats, ExecutionStrategy.EARLY_MATERIALIZATION);
    }

    private QueryResult runNaive(Query query, QueryStats stats) {
        int rows = table.rowCount();
        Map<String, ColumnVector> fullColumns = new LinkedHashMap<>();
        int[] allRows = new int[rows];
        for (int i = 0; i < rows; i++) {
            allRows[i] = i;
        }
        for (String name : query.involvedColumns()) {
            ColumnVector source = table.column(name);
            fullColumns.put(name, gather(source, allRows, rows));
            stats.record(new ColumnAccess(name, ColumnAccess.Kind.SCAN, rows));
        }

        int[] keep = Arrays.copyOf(allRows, rows);
        int kept = rows;
        for (ColumnFilter filter : query.filters()) {
            ColumnVector column = fullColumns.get(filter.column());
            int next = 0;
            for (int j = 0; j < kept; j++) {
                if (CandidateSetBuilder.matches(column, keep[j], filter.predicate())) {
                    keep[next++] = keep[j];
                }
            }
            kept = next;
        }
        stats.candidateSize(kept);

        int[] rowIds = new int[kept];
        System.arraycopy(keep, 0, rowIds, 0, kept);
        Map<String, ColumnVector> projected = new LinkedHashMap<>();
        for (String name : query.outputColumns()) {
            projected.put(name, gather(fullColumns.get(name), keep, kept));
        }
        return new QueryResult(rowIds, query.outputColumns(), projected,
                stats, ExecutionStrategy.NAIVE_FULL_READ);
    }

    static Map<String, ColumnVector> materialize(ColumnarTable table, List<String> columns,
                                                 int[] rowIds, int count, QueryStats stats) {
        Map<String, ColumnVector> projected = new LinkedHashMap<>();
        for (String name : columns) {
            projected.put(name, gather(table.column(name), rowIds, count));
            stats.record(new ColumnAccess(name, ColumnAccess.Kind.MATERIALIZE, count));
        }
        return projected;
    }

    static ColumnVector gather(ColumnVector source, int[] rowIds, int count) {
        return switch (source.type()) {
            case INT -> {
                IntVector intSource = (IntVector) source;
                int[] values = new int[count];
                for (int i = 0; i < count; i++) {
                    values[i] = intSource.get(rowIds[i]);
                }
                yield new IntVector(source.name(), values);
            }
            case DOUBLE -> {
                DoubleVector doubleSource = (DoubleVector) source;
                double[] values = new double[count];
                for (int i = 0; i < count; i++) {
                    values[i] = doubleSource.get(rowIds[i]);
                }
                yield new DoubleVector(source.name(), values);
            }
            case STRING -> {
                StringVector stringSource = (StringVector) source;
                String[] values = new String[count];
                for (int i = 0; i < count; i++) {
                    values[i] = stringSource.get(rowIds[i]);
                }
                yield new StringVector(source.name(), values);
            }
        };
    }
}
