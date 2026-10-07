package com.example.gsb.columnar;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 列存表：每列一个独立数组，读取按列计量。
 *
 * <p>所有读操作都会累加到本对象的 {@link Stats} 计数器中，统计口径以“单元（cell）”
 * 为单位：全列顺序读 cost = 行数，按行号点取 cost = 行数 * {@link #RANDOM_PENALTY}，
 * 用来模拟列存扫描（顺序大块 IO）与点取（随机 IO）的真实成本差异。成本通过忙等
 * 实际消耗 CPU 时间，因此既可以看计数器，也可以做墙钟耗时对比。</p>
 */
public final class ColumnStore {

    /** 点取单个单元相对顺序读单个单元的成本倍数（模拟随机 IO 放大）。 */
    public static final int RANDOM_PENALTY = 10;

    /** 顺序读单个单元的耗时（纳秒，忙等）。 */
    private static final long SPIN_PER_SEQ_UNIT = 64L;
    /** 点取单个单元的耗时（纳秒，忙等）= 顺序读 * RANDOM_PENALTY。 */
    private static final long SPIN_PER_RANDOM_UNIT = SPIN_PER_SEQ_UNIT * RANDOM_PENALTY;

    private final String name;
    private final String[] columnNames;
    private final Map<String, Integer> columnIndex;
    private final Object[][] columns;
    private final int rowCount;
    private final boolean spin;

    // 每次查询开始时 reset；并发分片共享同一组计数器。
    private final Set<String> fullyReadColumns = ConcurrentHashMap.newKeySet();
    private final Set<String> touchedColumns = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequentialCells = new AtomicLong();
    private final AtomicLong randomCells = new AtomicLong();

    private static volatile long blackHole;

    private ColumnStore(String name, String[] columnNames, Object[][] columns, boolean spin) {
        this.name = name;
        this.columnNames = columnNames;
        this.columnIndex = new TreeMap<>();
        for (int i = 0; i < columnNames.length; i++) {
            if (this.columnIndex.put(columnNames[i], i) != null) {
                throw new IllegalArgumentException("duplicate column: " + columnNames[i]);
            }
        }
        this.columns = columns;
        this.rowCount = columns.length == 0 ? 0 : columns[0].length;
        for (Object[] col : columns) {
            if (col.length != this.rowCount) {
                throw new IllegalArgumentException("columns must have equal length");
            }
        }
        this.spin = spin;
    }

    /** 无 IO 延迟的内存列存（用于纯正确性/并发测试，跑得快）。 */
    public static ColumnStore inMemory(String name, String[] columnNames, Object[][] columns) {
        return new ColumnStore(name, columnNames, columns, false);
    }

    /** 带模拟 IO 延迟的列存（用于两种策略的墙钟耗时对比）。 */
    public static ColumnStore withSimulatedIo(String name, String[] columnNames, Object[][] columns) {
        return new ColumnStore(name, columnNames, columns, true);
    }

    public String name() {
        return name;
    }

    public int rowCount() {
        return rowCount;
    }

    public int columnCount() {
        return columnNames.length;
    }

    public Set<String> columnNames() {
        return new TreeSet<>(Arrays.asList(columnNames));
    }

    private int indexOf(String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null) {
            throw new IllegalArgumentException("unknown column: " + column);
        }
        return idx;
    }

    // ------------------------------------------------------------------
    // 读取原语
    // ------------------------------------------------------------------

    /**
     * 全列顺序读：取出 [from, from+length) 行，计 {@code length} 个顺序单元。
     * 返回底层数组的切片视图，不拷贝数据。
     */
    public ColumnSlice readSlice(String column, int from, int length) {
        int idx = indexOf(column);
        Objects.checkFromIndexSize(from, length, rowCount);
        touch(column, length, 0);
        fullyReadColumns.add(column);
        if (spin) {
            spin(length * SPIN_PER_SEQ_UNIT);
        }
        return new ColumnSlice(columns[idx], from, length);
    }

    /** 整列顺序读（首列过滤 / 提前物化用）。 */
    public Object[] readColumn(String column) {
        int idx = indexOf(column);
        touch(column, rowCount, 0);
        fullyReadColumns.add(column);
        if (spin) {
            spin((long) rowCount * SPIN_PER_SEQ_UNIT);
        }
        return columns[idx];
    }

    /** 按行号点取单个单元（延迟物化用），计 {@link #RANDOM_PENALTY} 个随机单元。 */
    public Object readValue(String column, int row) {
        int idx = indexOf(column);
        touch(column, 0, 1);
        if (spin) {
            spin(SPIN_PER_RANDOM_UNIT);
        }
        return columns[idx][row];
    }

    /**
     * 不计量的窥探读取：仅供查询规划器采样估算选择性，不算作查询的列读取。
     */
    public Object peek(String column, int row) {
        return columns[indexOf(column)][row];
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    private void touch(String column, long seqCells, long rndCells) {
        touchedColumns.add(column);
        if (seqCells > 0) {
            sequentialCells.addAndGet(seqCells);
        }
        if (rndCells > 0) {
            randomCells.addAndGet(rndCells);
        }
    }

    /** 查询开始前清空本对象的读取计数。 */
    public void resetStats() {
        fullyReadColumns.clear();
        touchedColumns.clear();
        sequentialCells.set(0);
        randomCells.set(0);
    }

    /** 截至目前实际发生读取的列数。 */
    public int touchedColumnCount() {
        return touchedColumns.size();
    }

    /** 截至目前顺序整列读取过的列数。 */
    public int fullyReadColumnCount() {
        return fullyReadColumns.size();
    }

    public long sequentialCellsRead() {
        return sequentialCells.get();
    }

    public long randomCellsRead() {
        return randomCells.get();
    }

    /** 顺序 + 点取折算后的总读取单元数（点取按 {@link #RANDOM_PENALTY} 折算）。 */
    public long totalReadUnits() {
        return sequentialCells.get() + randomCells.get() * RANDOM_PENALTY;
    }

    /** 完全没有被读取过的列数。 */
    public int skippedColumnCount() {
        return columnNames.length - touchedColumns.size();
    }

    /** 被跳过列若整列读取本应产生的单元数 = 跳过列数 * 行数。 */
    public long skippedCellReads() {
        return (long) skippedColumnCount() * rowCount;
    }

    /** 全量读取（先取全行再过滤）本会产生的读取单元数 = 总列数 * 行数。 */
    public long fullReadUnits() {
        return (long) columnNames.length * rowCount;
    }

    private static void spin(long nanos) {
        if (nanos <= 0) {
            return;
        }
        long deadline = System.nanoTime() + nanos;
        long acc = 0;
        while (System.nanoTime() < deadline) {
            acc += deadline ^ System.nanoTime();
        }
        blackHole = acc;
    }

    /** 只用于测试断言：列名是否已被整体读过。 */
    Set<String> touchedColumnNames() {
        return Collections.unmodifiableSet(new HashSet<>(touchedColumns));
    }
}
