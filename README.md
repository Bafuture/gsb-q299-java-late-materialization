# 列存延迟物化组件

一个从零实现的列存查询执行组件：先只在筛选列上构建候选集（行号列表），筛选完成后再按行号
物化输出列，避免宽表场景下把不需要的列和不满足条件的行读出来。支持多列组合筛选、基于
成本模型的延迟/提前物化策略切换、行号区间并行执行与保序合并，并提供完整的读取统计。

## 运行方式

```bash
./mvnw -q verify          # 编译 + 全部测试（31 个）
./mvnw -q compile && java -cp target/classes com.example.gsb.latem.Benchmark   # 策略/耗时对比
```

## 模块结构

```
com.example.gsb.latem
├── column      列存层: ColumnarTable + IntVector/DoubleVector/StringVector
├── predicate   谓词层: ColumnPredicate / Predicates 工厂 / ColumnFilter
├── exec        执行层:
│   ├── CandidateSetBuilder   只读筛选列, 逐步过滤出候选行号
│   ├── QueryExecutor         三种执行策略 + 物化
│   ├── QueryPlanner          基于成本模型的策略选择
│   ├── SelectivityEstimator  固定种子采样估计各筛选阶段存活行数
│   ├── CostModel             顺序/随机访问成本模型
│   ├── ParallelQueryRunner   行号区间并行 + 保序合并
│   ├── QueryStats            候选集大小/读取列数/物化列数/跳过读取量等统计
│   └── QueryResult           行号 + 投影列 + 统计
└── Benchmark                 两种策略的耗时与模型成本对比
```

## 三种执行策略

- `LATE_MATERIALIZATION`（延迟物化）：第一个筛选列整列扫描，后续筛选列只按候选行号
  稀疏读取，候选集逐步缩小；全部筛选完成后才按最终行号物化输出列。
- `EARLY_MATERIALIZATION`（提前物化）：第一个筛选列扫描出候选后，立即按候选行号把其余
  涉及列批量物化成"元组"，后续筛选与投影都在已物化的数据上进行。
- `NAIVE_FULL_READ`（朴素基线，即"先取全行再过滤"）：把所有涉及列整列读入后再过滤，
  用于正确性对照与读取量对比基线。

`QueryExecutor.execute(query)` 会先采样估计选择性，由规划器自动选择前两种策略之一；
也可以用 `execute(query, strategy)` 强制指定。

## 成本模型与策略切换条件

从列中读取 c 个元素的代价（N 为总行数）：

```
access(c) = min(c * randomElementCost, N * sequentialElementCost)
```

即稀疏读取按随机访问计费，但当存活行足够密集时顺序扫整列反而更便宜。设筛选列 k 个、
输出列 m 个、各筛选阶段后存活行数 c1..ck（采样估计），批量重建元组的折扣为 β：

```
LATE  = N*S + Σ access(c[i-1]) + m * access(ck)
EARLY = N*S + β * (k-1+m) * access(c1)
```

**切换条件**：估计存活率高（选择性低，大多数行都会留下）时 `access()` 饱和为顺序价，
提前物化的批量折扣 β 使其总成本更低 → 选 `EARLY_MATERIALIZATION`；估计存活率低
（选择性高）时延迟物化只需为极少数行读取后续列 → 选 `LATE_MATERIALIZATION`。
默认参数 `S=1, R=8, β=0.5`（`CostModel.defaults()`），可通过构造函数调整。

> 注：模型刻画的是磁盘/远端列存中随机访问远贵于顺序访问的场景。本组件为内存列存，
> 实测耗时由读取元素总量主导，因此高选择性场景下延迟物化的实测优势最显著；
> 低选择性场景下两种策略都远优于朴素全读，规划器按模型成本给出切换决策。

## 统计指标（QueryStats）

每次执行返回 `QueryStats`，`explain()` 输出汇总：

- 候选集大小（`candidateSize`）
- 读取列数与列名（`columnsReadCount` / `columnsRead`）
- 读取元素总量，拆分筛选读取 / 物化读取 / 选择性采样
- 物化列数（`materializedColumnCount`）与物化元素量
- 全量读取基线（行数 × 涉及列数）与**被跳过的列读取量**（`skippedElements`）

## 并发设计

`ParallelQueryRunner` 把 `[0, N)` 切成连续的、互不重叠的行号区间：

1. 各线程在本区间上构建候选集（只读筛选列）；
2. 对各区间的候选数做前缀和，确定全局行序中的写入位置；
3. 各线程把本区间的输出列直接物化到结果数组的对应位置。

区间按行号升序划分，合并结果**天然保持原有行序**，无需排序或二次拷贝；各分区的统计
合并为全局 `QueryStats`。线程池随 `ParallelQueryRunner` 复用（`AutoCloseable`）。

## 使用示例

```java
ColumnarTable table = ColumnarTable.builder()
        .addColumn(new IntVector("age", ages))
        .addColumn(new StringVector("city", cities))
        .addColumn(new DoubleVector("score", scores))
        .build();

Query query = Query.builder()
        .where("age", Predicates.intBetween(18, 30))
        .where("city", Predicates.stringEq("beijing"))
        .select("score")
        .build();

QueryExecutor executor = new QueryExecutor(table);
QueryResult result = executor.execute(query);   // 自动选择策略
System.out.println(result.stats().explain());
```

## Benchmark 实测（3,000,000 行 × 10 列，16 核，JDK 17）

```
== 场景 A: 高选择性过滤 (存活约 0.05%) ==
规划器: 估计存活率 0.0500%, 延迟物化成本 3156000, 提前物化成本 3240000 -> LATE_MATERIALIZATION
  LATE_MATERIALIZATION   候选=1510    读取元素=3019396   物化列数=3 跳过读取=11980604  耗时=4.25 ms
  EARLY_MATERIALIZATION  候选=1510    读取元素=3059464   物化列数=4 跳过读取=11940536  耗时=5.38 ms
  NAIVE_FULL_READ        候选=1510    读取元素=15000000  物化列数=0 跳过读取=0         耗时=25.12 ms

== 场景 B: 低选择性过滤 (存活约 85%) ==
规划器: 估计存活率 85.6600%, 延迟物化成本 15000000, 提前物化成本 9000000 -> EARLY_MATERIALIZATION
  LATE_MATERIALIZATION   候选=2564154 读取元素=13542210  物化列数=3 跳过读取=1457790   耗时=16.31 ms
  EARLY_MATERIALIZATION  候选=2564154 读取元素=14398992  物化列数=4 跳过读取=601008    耗时=26.80 ms
  NAIVE_FULL_READ        候选=2564154 读取元素=15000000  物化列数=0 跳过读取=0         耗时=52.74 ms

== 并发合并 (场景 B 查询, 16 线程) ==
  单线程: 14.53 ms, 16 线程: 12.66 ms, 加速比 1.15x, 结果一致: true
```

要点：高选择性场景延迟物化比全量读取快约 6 倍（跳过了 80% 的列读取量）；低选择性场景
规划器按模型成本切换为提前物化；并发合并结果与单线程完全一致且保持行序。

## 测试覆盖（31 个用例）

| 测试类 | 覆盖点 |
|--------|--------|
| `CandidateSetConstructionTest` | 候选集只含满足条件的行号、只读筛选列、空候选、行号区间 |
| `LateMaterializationTest` | 物化列数/读取列数/跳过读取量统计、与全量基线对比 |
| `MultiColumnFilterTest` | 多列组合筛选逐步缩小候选集（SCAN→SPARSE 元素数递减） |
| `CorrectnessPropertyTest` | 40 组随机数据上 延迟/提前/自动/并行 结果与朴素全读完全一致 |
| `StrategySwitchTest` | 成本模型不等式、规划器双向切换、自动执行跟随规划、耗时对比 |
| `ParallelMergeTest` | 1/2/3/8 线程合并保序且与单线程一致、统计合并、空结果、线程数>行数 |
| `ColumnarTableTest` / `PredicatesTest` | 建表校验、谓词语义、类型不匹配报错 |
