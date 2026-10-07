package com.example.gsb.columnar;

import java.util.HashMap;
import java.util.Map;

/**
 * 候选集：一组有序行号 + 过滤过程中已经读取过的列值缓存。
 *
 * <p>延迟物化的关键：构造候选集时只读“当前筛选列”，其它输出列一律不读。
 * 缓存使得筛选列若同时出现在输出列中，物化时无需二次点取。</p>
 */
public final class CandidateSet {

    private final int[] rows;
    private final Map<String, Object[]> cachedValues;

    CandidateSet(int[] rows, Map<String, Object[]> cachedValues) {
        this.rows = rows;
        this.cachedValues = cachedValues;
    }

    /**
     * 全表（或行号区间 [from, from+length)）候选集：只顺序读首条筛选列，
     * 逐行求值，命中行进入候选集。其它列完全不读。
     */
    public static CandidateSet scan(ColumnStore store, Filter filter, int from, int length) {
        ColumnSlice slice = store.readSlice(filter.column(), from, length);
        int[] matched = new int[length];
        Object[] matchedValues = new Object[length];
        int count = 0;
        for (int i = 0; i < length; i++) {
            Object value = slice.valueAt(i);
            if (filter.predicate().test(value)) {
                matched[count] = from + i;
                matchedValues[count] = value;
                count++;
            }
        }
        Map<String, Object[]> cache = new HashMap<>();
        cache.put(filter.column(), shrink(matchedValues, count));
        return new CandidateSet(shrink(matched, count), cache);
    }

    /** 全表首筛的便捷形式。 */
    public static CandidateSet scan(ColumnStore store, Filter filter) {
        return scan(store, filter, 0, store.rowCount());
    }

    /**
     * 在下一条筛选列上继续收窄：只对当前候选行求值，未命中行被剔除，
     * 候选集单调不增。读到的值按新候选顺序缓存；对同一列重复筛选时走缓存、零额外 IO。
     */
    public CandidateSet refine(ColumnStore store, Filter filter) {
        int n = rows.length;
        boolean[] keep = new boolean[n];
        Object[] probed = cachedValues.get(filter.column());
        boolean reused = probed != null && probed.length == n;
        if (!reused) {
            probed = new Object[n];
            for (int i = 0; i < n; i++) {
                probed[i] = store.readValue(filter.column(), rows[i]);
            }
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            keep[i] = filter.predicate().test(probed[i]);
            if (keep[i]) {
                count++;
            }
        }
        int[] newRows = new int[count];
        Object[] keptValues = new Object[count];
        for (int i = 0, k = 0; i < n; i++) {
            if (keep[i]) {
                newRows[k] = rows[i];
                keptValues[k] = probed[i];
                k++;
            }
        }
        Map<String, Object[]> nextCache = new HashMap<>();
        nextCache.put(filter.column(), keptValues);
        for (Map.Entry<String, Object[]> entry : cachedValues.entrySet()) {
            if (entry.getKey().equals(filter.column())) {
                continue;
            }
            Object[] old = entry.getValue();
            Object[] arr = new Object[count];
            for (int i = 0, k = 0; i < n; i++) {
                if (keep[i]) {
                    arr[k++] = old[i];
                }
            }
            nextCache.put(entry.getKey(), arr);
        }
        return new CandidateSet(newRows, nextCache);
    }

    /**
     * 物化输出列：缓存命中的筛选列零 IO；其余列按最终行号点取。
     * 返回与 {@link #rawRows()} 对齐的列值数组。
     */
    public Object[] materializeColumn(ColumnStore store, String column) {
        Object[] cached = cachedValues.get(column);
        if (cached != null && cached.length == rows.length) {
            return cached;
        }
        Object[] values = new Object[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = store.readValue(column, rows[i]);
        }
        return values;
    }

    public int size() {
        return rows.length;
    }

    /** 返回候选行号的拷贝（始终升序）。 */
    public int[] rows() {
        return rows.clone();
    }

    int[] rawRows() {
        return rows;
    }

    private static int[] shrink(int[] src, int count) {
        if (count == src.length) {
            return src;
        }
        int[] out = new int[count];
        System.arraycopy(src, 0, out, 0, count);
        return out;
    }

    private static Object[] shrink(Object[] src, int count) {
        if (count == src.length) {
            return src;
        }
        Object[] out = new Object[count];
        System.arraycopy(src, 0, out, 0, count);
        return out;
    }
}
