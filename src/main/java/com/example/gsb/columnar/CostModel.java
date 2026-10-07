package com.example.gsb.columnar;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取成本模型（与 {@link ColumnStore} 的计量口径一致）：
 *
 * <ul>
 *   <li>顺序读 1 个单元 = 1 单位；按行号点取 1 个单元 = {@link ColumnStore#RANDOM_PENALTY} 单位。</li>
 *   <li>延迟物化：首筛整列扫 N；第 i 条过滤只点取上一步候选 c(i-1) 行；
 *       物化输出列时对每个非筛选输出列点取 c(k) 行（筛选列已在过滤阶段读到，零成本复用）。</li>
 *   <li>提前物化：先整读全部输出列（O*N），再按过滤列整列扫描求值（筛选列若在输出列中则不重复读）。</li>
 * </ul>
 */
public final class CostModel {

    private CostModel() {
    }

    public record Estimate(long lateCost, long earlyCost) {
    }

    /**
     * @param rowCount       表行数 N
     * @param selectivities  每条过滤条件的通过率 p_i（0..1），与过滤顺序一致
     * @param outputColumns  输出列数 O
     * @param filterColumns  筛选列数 F（去重后）
     * @param overlap        同时出现在输出与筛选中的列数
     */
    public static Estimate estimate(int rowCount,
                                    List<Double> selectivities,
                                    int outputColumns,
                                    int filterColumns,
                                    int overlap) {
        long n = rowCount;
        long late = n; // 首筛整列扫描
        long candidates = n;
        for (int i = 1; i < selectivities.size(); i++) {
            candidates = Math.round(candidates * selectivities.get(i - 1));
            late += candidates * ColumnStore.RANDOM_PENALTY;
        }
        long finalCandidates = n;
        for (double p : selectivities) {
            finalCandidates = Math.round(finalCandidates * p);
        }
        late += (long) (outputColumns - overlap) * finalCandidates * ColumnStore.RANDOM_PENALTY;

        long early = (long) outputColumns * n + (long) (filterColumns - overlap) * n;
        return new Estimate(late, early);
    }

    /** 选择成本更低的策略；相等时偏向延迟物化（宽表常态更优）。 */
    public static QueryPlanner.Strategy choose(Estimate estimate) {
        return estimate.earlyCost() < estimate.lateCost()
                ? QueryPlanner.Strategy.EARLY
                : QueryPlanner.Strategy.LATE;
    }

    static int overlap(List<Filter> filters, List<String> outputColumns) {
        Set<String> filterCols = new LinkedHashSet<>();
        for (Filter f : filters) {
            filterCols.add(f.column());
        }
        int overlap = 0;
        for (String out : outputColumns) {
            if (filterCols.contains(out)) {
                overlap++;
            }
        }
        return overlap;
    }

    static int distinctFilterColumns(List<Filter> filters) {
        Set<String> filterCols = new LinkedHashSet<>();
        for (Filter f : filters) {
            filterCols.add(f.column());
        }
        return filterCols.size();
    }
}
