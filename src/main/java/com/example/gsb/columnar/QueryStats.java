package com.example.gsb.columnar;

import java.util.List;

/**
 * 一次查询执行的统计快照：候选集大小、物化列数、读取列数与被跳过的列读取量。
 */
public record QueryStats(
        String strategy,
        int rowCount,
        int totalColumns,
        List<Integer> candidateSizes,
        int finalCandidateCount,
        int materializedColumns,
        int columnsRead,
        int columnsFullyScanned,
        long sequentialCellsRead,
        long randomCellsRead,
        long totalReadUnits,
        int skippedColumns,
        long skippedCellReads,
        long fullReadUnits,
        long elapsedNanos) {

    public static QueryStats capture(String strategy,
                                     ColumnStore store,
                                     List<Integer> candidateSizes,
                                     int materializedColumns,
                                     long elapsedNanos) {
        return new QueryStats(
                strategy,
                store.rowCount(),
                store.columnCount(),
                List.copyOf(candidateSizes),
                candidateSizes.isEmpty() ? 0 : candidateSizes.get(candidateSizes.size() - 1),
                materializedColumns,
                store.touchedColumnCount(),
                store.fullyReadColumnCount(),
                store.sequentialCellsRead(),
                store.randomCellsRead(),
                store.totalReadUnits(),
                store.skippedColumnCount(),
                store.skippedCellReads(),
                store.fullReadUnits(),
                elapsedNanos);
    }

    /** 与全量读取（所有列整读）相比节省的读取单元比例，0..1。 */
    public double readSavingRatio() {
        return fullReadUnits == 0 ? 0.0 : 1.0 - (double) totalReadUnits / fullReadUnits;
    }

    @Override
    public String toString() {
        return "QueryStats{strategy=" + strategy
                + ", candidateSizes=" + candidateSizes
                + ", materializedColumns=" + materializedColumns
                + ", columnsRead=" + columnsRead + "/" + totalColumns
                + ", readUnits=" + totalReadUnits
                + ", fullReadUnits=" + fullReadUnits
                + ", skippedColumns=" + skippedColumns
                + ", skippedCellReads=" + skippedCellReads
                + ", readSaving=" + String.format("%.1f%%", readSavingRatio() * 100)
                + ", elapsedMs=" + String.format("%.2f", elapsedNanos / 1_000_000.0) + "}";
    }
}
