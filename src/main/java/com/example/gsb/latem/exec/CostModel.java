package com.example.gsb.latem.exec;

/**
 * 延迟物化 vs 提前物化的成本模型。
 *
 * <p>从列中读取 c 个元素的代价为 {@code min(c * randomElementCost, rows * sequentialElementCost)}:
 * 稀疏读取按随机访问计费, 但当存活行足够密集时, 顺序扫整列反而更便宜。
 *
 * <p>延迟物化: 第一个筛选列整列扫描, 后续筛选列只按候选行号稀疏读取,
 * 最后按最终候选集物化输出列:
 * {@code LATE = rows*S + sum(access(c[i-1])) + m * access(c[k])}
 *
 * <p>提前物化: 第一个筛选列整列扫描后立即按候选行号批量重建元组
 * (同一行的多列一起取, 享有 reconstructionBatchDiscount 的批量折扣),
 * 之后的筛选与投影都在已物化的元组上进行:
 * {@code EARLY = rows*S + beta * (k-1+m) * access(c[1])}
 *
 * <p>切换条件: 当筛选链存活率高(选择性低, 大多数行都会留下来)时,
 * access() 饱和为顺序扫描价, 此时 EARLY 的批量折扣使其总成本低于 LATE;
 * 当存活率低(选择性高)时, LATE 只需为极少数行读取后续列, 总成本更低。
 */
public record CostModel(double sequentialElementCost,
                        double randomElementCost,
                        double reconstructionBatchDiscount) {

    public CostModel {
        if (sequentialElementCost <= 0 || randomElementCost <= 0) {
            throw new IllegalArgumentException("element costs must be positive");
        }
        if (reconstructionBatchDiscount <= 0 || reconstructionBatchDiscount > 1) {
            throw new IllegalArgumentException("reconstructionBatchDiscount must be in (0, 1]");
        }
    }

    public static CostModel defaults() {
        return new CostModel(1.0, 8.0, 0.5);
    }

    public double accessCost(long elements, int columnRows) {
        return Math.min(elements * randomElementCost, columnRows * sequentialElementCost);
    }

    public double lateMaterializationCost(int rows, long[] survivorsAfterEachFilter,
                                          int outputColumnCount) {
        int filterCount = survivorsAfterEachFilter.length;
        double cost = rows * sequentialElementCost;
        for (int i = 1; i < filterCount; i++) {
            cost += accessCost(survivorsAfterEachFilter[i - 1], rows);
        }
        cost += outputColumnCount * accessCost(survivorsAfterEachFilter[filterCount - 1], rows);
        return cost;
    }

    public double earlyMaterializationCost(int rows, long[] survivorsAfterEachFilter,
                                           int outputColumnCount) {
        int filterCount = survivorsAfterEachFilter.length;
        long survivorsAfterFirst = survivorsAfterEachFilter[0];
        int reconstructedColumns = (filterCount - 1) + outputColumnCount;
        return rows * sequentialElementCost
                + reconstructionBatchDiscount * reconstructedColumns
                * accessCost(survivorsAfterFirst, rows);
    }
}
