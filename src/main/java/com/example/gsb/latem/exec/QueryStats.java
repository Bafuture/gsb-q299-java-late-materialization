package com.example.gsb.latem.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class QueryStats {

    private final List<ColumnAccess> accesses = new ArrayList<>();
    private int candidateSize;
    private long fullReadBaselineElements = -1;
    private long elapsedNanos = -1;

    public synchronized void record(ColumnAccess access) {
        accesses.add(access);
    }

    public synchronized List<ColumnAccess> accesses() {
        return List.copyOf(accesses);
    }

    public synchronized void candidateSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
        this.candidateSize = size;
    }

    public synchronized int candidateSize() {
        return candidateSize;
    }

    public synchronized void fullReadBaselineElements(long elements) {
        this.fullReadBaselineElements = elements;
    }

    public synchronized long fullReadBaselineElements() {
        return fullReadBaselineElements;
    }

    public synchronized void elapsedNanos(long nanos) {
        this.elapsedNanos = nanos;
    }

    public synchronized long elapsedNanos() {
        return elapsedNanos;
    }

    public long elementsRead() {
        return accesses().stream().mapToLong(ColumnAccess::elements).sum();
    }

    public long filterElementsRead() {
        return elementsOf(ColumnAccess.Kind.SCAN) + elementsOf(ColumnAccess.Kind.SPARSE);
    }

    public long materializedElements() {
        return elementsOf(ColumnAccess.Kind.MATERIALIZE);
    }

    public long sampledElements() {
        return elementsOf(ColumnAccess.Kind.SAMPLING);
    }

    private long elementsOf(ColumnAccess.Kind kind) {
        return accesses().stream()
                .filter(access -> access.kind() == kind)
                .mapToLong(ColumnAccess::elements)
                .sum();
    }

    public Set<String> columnsRead() {
        Set<String> columns = new LinkedHashSet<>();
        for (ColumnAccess access : accesses()) {
            columns.add(access.column());
        }
        return columns;
    }

    public int columnsReadCount() {
        return columnsRead().size();
    }

    public int materializedColumnCount() {
        Set<String> columns = new LinkedHashSet<>();
        for (ColumnAccess access : accesses()) {
            if (access.kind() == ColumnAccess.Kind.MATERIALIZE) {
                columns.add(access.column());
            }
        }
        return columns.size();
    }

    public long skippedElements() {
        long baseline = fullReadBaselineElements();
        if (baseline < 0) {
            throw new IllegalStateException("full read baseline has not been recorded");
        }
        return baseline - elementsRead();
    }

    public synchronized String explain() {
        StringBuilder builder = new StringBuilder();
        builder.append("候选集大小: ").append(candidateSize).append('\n');
        builder.append("读取列数: ").append(columnsReadCount())
                .append(' ').append(columnsRead()).append('\n');
        builder.append("读取元素总量: ").append(elementsRead())
                .append(" (筛选读取: ").append(filterElementsRead())
                .append(", 物化读取: ").append(materializedElements());
        if (sampledElements() > 0) {
            builder.append(", 选择性采样: ").append(sampledElements());
        }
        builder.append(")\n");
        builder.append("物化列数: ").append(materializedColumnCount()).append('\n');
        if (fullReadBaselineElements >= 0) {
            long skipped = skippedElements();
            builder.append("全量读取基线(元素): ").append(fullReadBaselineElements).append('\n');
            builder.append("跳过的列读取量(元素): ").append(skipped);
            if (fullReadBaselineElements > 0) {
                builder.append(String.format(Locale.ROOT, " (%.2f%%)",
                        100.0 * skipped / fullReadBaselineElements));
            }
            builder.append('\n');
        }
        if (elapsedNanos >= 0) {
            builder.append(String.format(Locale.ROOT, "耗时: %.3f ms%n", elapsedNanos / 1_000_000.0));
        }
        return builder.toString();
    }

    public static QueryStats merge(List<QueryStats> parts) {
        QueryStats merged = new QueryStats();
        int candidates = 0;
        for (QueryStats part : parts) {
            synchronized (part) {
                merged.accesses.addAll(part.accesses);
            }
            candidates += part.candidateSize();
        }
        merged.candidateSize(candidates);
        return merged;
    }
}
