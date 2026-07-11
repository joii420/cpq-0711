# 导入 & 进入报价单(首存快照)性能优化设计方案

> 立项 2026-06-23。目标:在**不并行化、不改变任何业务结果、不改 advisory lock 语义、不靠首存异步**的前提下,
> 把"V6 基础数据导入"和"导入后进入报价单(首存快照)"两条慢链路提速。
> 唯一手段 = **把对远程 DB 的逐行/逐组件 SQL 往返,合并为更少的批量/预取查询**。
>
> **修订 r1(2026-06-23,采纳一轮独立评审)**:新增 P0 配置项;P1-A 拆三类合并方向;P1-C3 补双路+复合 key NULL;P2-C4 风险 中→中低 + 可变共享专项;P2-Q05 补 VALUES NULL 陷阱;§4 加往返基线 + 导入侧对账表;file:line 精确化。
>
> **修订 r2(2026-06-23,采纳二轮独立评审 —— 修正 r1 的技术误判)**:① **P0 降级**——经代码核实 `statement-batch-size`(无实体 persist,全原生 `executeUpdate`)**完全不生效**,`reWriteBatchedInserts` 对"已是单条多值 INSERT"的 P1/Q19/Q05 语句**无增量**;P0 从 step0 免费基线降为收益≈0 的低优先附带项,**导入提速主体归 P1-A/Q19/Q05 改造本身**。② **P1-C3 NULL 处理拆两路**:KvTable 是单列 `key_id IN`(NULL 已归一,无需特判),仅 View 路径需元组 IN + NULL 回落。③ **Q18 归因订正**:"末值非空胜"源于非描述列无条件 `COALESCE(EXCLUDED, existing)`,与 `preserveDescriptive` 无关。④ C4 可变共享专项扩到整条分发链;C3 补 type 字面值逐 handler 校验;§4 往返计数改用 PG `pg_stat_statements.calls`;Q02 `preserveDescriptive`=true;§6/§7 去除 P0 收益依赖。

## 0. TL;DR

| 链路 | 现状墙钟 | 根因 | 本方案后(全做) |
|---|---|---|---|
| V6 报价导入 | ~50–75s | 5000–7500 次远程 SQL 往返 | ~20–30s |
| 进入报价单(首存快照) | ~47s+ | 两遍全量 expand,逐行逐组件远程 SQL | 预计削 30–50%(核价侧 ÷N 为主) |

两条链路**同根**:远程 DB(`10.177.152.12`,单次往返 ~8–13ms),代码对它发成千上万条**单行/单组件 SQL,一条一条串行来回**。CPU 不是瓶颈,**往返次数**才是。本方案全部是"减少往返次数"类**等价优化**(批量 SQL / 跨行预取 / 合并查询 / 缓存只读元数据)。

---

## 1. 硬约束(违反即作废)

1. 🚫 **禁止并行化 expand / 公式求值 / 快照求值层**。2026-06-22 已实证:给首存快照内部并行化虽 5.6x 提速,但 `ComponentDriverService.expand` 直接 `return` 进程级 Caffeine 缓存的**可变对象引用**,叠加共享公式求值/多个进程级缓存,**产出非确定性竞态**(连跑两次 md5 不一致,9/73 行算错),已 revert。属 CLAUDE.md 锁定的三大核心基线。
2. 🚫 **不改变任何业务落库/计算结果**:行数、列值、版本号、is_current、公式结果、料号生成/复用,逐位一致。
3. 🚫 **不改 `pg_advisory_xact_lock` 粒度**(改成 customer 级锁是语义变更,不在本方案范畴)。
4. 🚫 **首存不走异步**(用户 2026-06-22 已排除;本方案靠"批量减往返",非异步)。
5. ⚠️ 触碰 expand/snapshot 的改动,必须保留**防御性深拷贝**(不把同一可变 `Row` 对象挂到多行)和 **AP-37 / AP-45 / AP-51 / AP-52 / AP-53** 的既有保护。

---

## 2. 现状往返量化

### 2.1 导入链路(每分组固定开销 + 残留 N+1)

`VersionedV6Writer` 每分组固定开销(各 1 次独立远程 SQL):
- `writeVersionedGroup`(单表)≈ `advisoryLock + loadCurrentGroup + nextVersionOf + flip + insert` = **~5 往返/组**
- `writeVersionedMasterDetail`(主从)≈ + flip child + insert master = **~7 往返/组**

| 写入入口 | 估分组数 | 往返/组 | 合计 |
|---|---|---|---|
| unit_price 族(12 个 writeVersionedGroup sheet,717 行) | 400–550 | ~5 | 2000–2750 |
| material_bom(+item,238 行) | 80–150 | ~7 | 560–1050 |
| element_bom(+item,328 行) | 100–180 | ~7 | 700–1260 |
| **仅分组固定开销小计** | | | **~3300–5000** |

叠加残留 N+1(见 §3):
- **8 个 handler 仍逐行 `upsertByMaterialNo`**(#2 只改了 MaterialBomMergeHandler):~800–1500 次。
- Q05/Q19 逐行 native SQL:~100–400 次。
- MaterialNoResolver 按名查 + 生成:~50–300 次。

**导入总往返 ≈ 5000–7500 次 × ~10ms ≈ 50–75s。**

### 2.2 进入报价单 = 导入后**首存快照**(两遍全量 expand)

> 渲染本身已"整份快照脱钩"(渲染期 batch-expand=0),**不是**瓶颈。慢的是**首存**:73 行全是新行、无 snapshot,触发两段全量计算。

```
saveDraft(首存)
├─[A] 报价侧 snapshot_rows  ConfigureSnapshotService.snapshotLines
│     for 每行(N≈73) → for 每报价 driver 组件(M_quote) → expand(单料号)   ← C1 逐行逐组件
│         └─ driver loadByPath 远程 SQL + 逐行 BASIC_DATA/gvar 求值        ← C2/C3
└─[B] 核价侧值快照  CardSnapshotService.buildCostingCardValues
      for 每行 → BomClosureService.compute(单条 WITH RECURSIVE CTE+缓存)   ← C5 非瓶颈
                → for 每核价 driver 组件(M_cost) → expandForPartSet(闭包)   ← C4 逐行(组件内已合)
```

| 类别 | 来源 file:line | 量级 | 现状 |
|---|---|---|---|
| **C1** 报价侧逐行逐组件单值 expand | `ConfigureSnapshotService.java:191–202` → `ComponentDriverService.expand:469` | N × M_quote | ❌ 逐行,跨行 resultCache 不命中(key 含 partNo) |
| **C2** 报价 BASIC_DATA 跨表求值 | `ComponentDriverService.evaluatePath:980/994` | N × M_quote × 跨表字段 | ✅ 自表短路 + 同请求 resultCache,已近最优 |
| **C3** 逐行逐 gvar 全局变量求值 | `ComponentDriverService:493 → :778 → GlobalVariableService.resolveValue` | N × M × gvar 字段 × 行 | ❌ 逐行逐 key 单查 |
| **C4** 核价侧逐行 expand | `CardSnapshotService.java:1178–1196` → `expandForPartSet:622 → expandMulti:526` | N × M_cost(每次多值 ANY) | ⚠️ 组件内 ANY 已合,**跨行未合**(expandMulti 不进 resultCache) |
| **C5** 核价 BOM 闭包递归 | `BomClosureService.compute:115` | N 次单 CTE | ✅ 单 `WITH RECURSIVE` + Caffeine 缓存,**非 N+1** |

**大头 = C1(报价侧逐行)+ C4(核价侧逐行)+ C3(逐行 gvar)。** 题面担心的"BOM 递归每层发 SQL"**不成立**。

---

## 3. 优化项(按"收益高 / 风险低 / 不碰核心基线"排序)

### 分期总览

| 阶段 | 项 | 链路 | 收益 | 风险 | 是否需 architect |
|---|---|---|---|---|---|
| ~~P0~~(降级) | `reWriteBatchedInserts`(可随手加,**收益≈0**);`statement-batch-size` **不加**(无实体 persist 不生效) | 导入 | ≈0(非免费基线) | 极低 | 否 |
| **P1** | A. 9 handler material_master 批量 upsert(name/type 首值 / Q18 末值 / Q02 仅 material_no,**三类分开**) | 导入 | ~8–15s | 低 | 否 |
| **P1** | C3. GlobalVariableService.resolveValue 批量(双路 kv+view) | 首存(两侧) | 中 | **中**(双路+复合 key NULL) | 否 |
| **P1** | Q19 逐行 INSERT 批量 | 导入 | ~1–2s | 低 | 否 |
| **P2** | C4. 核价侧跨行 partSet 并集 + 每组件一次 expandMulti | 首存 | **最大(÷N)** | **中低**(普通视图查询本不含行维度) | 走流程(非技术高危) |
| **P2** | D. MaterialNoResolver 按名预取 + dbMax 缓存 | 导入 | ~1–3s | 中低 | 否 |
| **P2** | Q05 逐行 UPDATE → 批量 `UPDATE ... FROM (VALUES ...)` | 导入 | ~1–2s | 中 | 否 |
| **P3** | B. VersionedV6Writer 跨组预取(loadCurrentGroup + nextVersionOf) | 导入 | ~8–14s | 中高 | **是** |
| **P3** | C1. 报价侧仅对"非 :lineItemId"组件合桶 | 首存 | 受限 | 高 | **是** |

> **评级修正(2026-06-23 独立评审采纳)**:① P2-C4 实际风险由"中"降为"**中低**"——`expandForPartSet:642` 普通 `$view` 分支调 `expandMulti` **根本没传 lineItemId**,查询本就不含行维度,跨行 union 按 partNo 分桶天然等价(仅 composite-agg 分支 `:633` 带 lineItemId 需保留);architect 是流程合规非技术高危。② P1-C3 由"低"升"**中**"(见该项)。

---

### P0 导入:JDBC 批量参数(低优先附带项 —— 对本方案多半不生效,**勿当免费基线**)

> ⚠️ **r2 重大订正(二轮独立评审采纳)**:r1 曾把 P0 列为"step0 免费基线、可吃掉一部分往返、据以校准后续增量"——**这是技术误判,已撤销**。经代码核实,这两个参数对本方案的写入路径**几乎不生效**:

- **现状**:`application.properties:24` JDBC URL 未带 `reWriteBatchedInserts`,也无 `quarkus.hibernate-orm.jdbc.statement-batch-size`。属实,但二者对本方案均够不到:
  - **`statement-batch-size` 当前完全不生效**:它只作用于 Hibernate **实体 persist/merge flush 时的 `PreparedStatement.addBatch()`**。导入/首存热路径**全部是原生 `em.createNativeQuery(sql).executeUpdate()`,不走实体 persist** → 该参数根本不介入。**不要配上去**(无效,且制造"已优化"假象)。
  - **`reWriteBatchedInserts` 对本方案语句无增量**:它优化的是"同一 PreparedStatement 上多次 `addBatch()` 的单行 INSERT"。而 `upsertBatchNameType:145` / `insertRowsBatched` / 批量 Q19/Q05 **本就是单条多值 `INSERT VALUES (..),(..)`**(无 addBatch,无重写空间);残留逐行 handler 又是各自独立 `createNativeQuery().executeUpdate()`(非 addBatch),**它也够不到**;叠加全带 `ON CONFLICT ... DO UPDATE`,重写更受限。
- **正确定位**:导入提速的**真正主体是 P1-A / Q19 / Q05 的"逐行 → 单条多值"改造本身**,与 P0 无关。P0 **降级为可随手一试的低优先附带项**:仅当未来引入"实体批 persist"路径时 `statement-batch-size` 才有意义;`reWriteBatchedInserts` 可加(无害),但**收益预期 ≈ 0,不得用于校准其它项的增量基线**。
- **风险**:加 `reWriteBatchedInserts` 极低(实测后端启动正常 + 既有测试全绿即可);`statement-batch-size` 不加(无效)。

### P1-A 导入:9 个 handler 的 material_master 逐行 upsert → 批量(name/type 首值 vs Q18 末值 vs Q02 单列,**三类分开**)

- **现状**:`Q04ElementBomHandler:60` / `Q06:61` / `Q07:58` / `Q08:57` / `Q09:55` / `Q10:56` / `Q13:59` / `Q02CustomerMapHandler:69` / `Q18UnitWeightHandler:32` 在行循环内逐行 `materialMasterRepo.upsertByMaterialNo(...)`,每行 1 次远程 INSERT...ON CONFLICT。
- **⚠️ 必须按合并方向分三类(独立评审采纳,等价性关键)**:批量化要在内存按 `material_no` 去重再一次写,**但去重的"合并方向"必须与各 handler 逐行 `upsertByMaterialNo` 的 COALESCE 链逐位一致**,不能笼统照搬:
  | 类 | handler | 写入业务列 | 逐行写库语义 | 批量去重合并方向 |
  |---|---|---|---|---|
  | ① name/type | Q04/06/07/08/09/10/13 | material_name + material_type(="组成件"等) | `preserveDescriptive=**true**` → name/type 走 `COALESCE(existing, new)` | **首个非空胜** |
  | ② 单重 | **Q18** | unit_weight(name/type 入参恒 null) | unit_weight(及其它非描述列)**无条件** `COALESCE(EXCLUDED, existing)`(`MaterialMasterRepository:83`,**不受 `preserveDescriptive` 控制**) | **末值非空胜**(与①相反!) |
  | ③ 仅 material_no | **Q02** | 仅 material_no(成品料号同步,无描述列) | `preserveDescriptive=true`,但无描述列参与 → 等效仅插入 | 去重即可 |
  - 类①:复用已验证的 `MaterialMasterRepository.upsertBatchNameType(rows, importedBy, true)`(已存在,分块 500),内存累积用 `accMaterialMaster` 的**首个非空合并**(对每行 `cur[i] = cur[i] != null ? cur[i] : new`,**绝不是 `putIfAbsent`**——否则"首行空、次行非空"会被丢)。**⚠️ type 字面值逐 handler 校验**:Q06/07/08/09/10/13 的 type 可能与 Q04("组成件")不同,内存去重的 type 合并须按各 handler 实际字面值对齐(本设计仅抽验 Q04,实现前逐个确认)。
  - 类②(Q18):**需新增 `upsertBatchWithWeight` 重载**——其合并方向是因为 `unit_weight` 等**非描述列恒 `COALESCE(EXCLUDED, existing)`**(与 `preserveDescriptive` 无关,Q18 也不写 name/type),故内存去重按**末值非空胜**(`cur[i] = new != null ? new : cur[i]`)。**不要在此重载里为 name/type 引入 preserveDescriptive 分支**(Q18 入参恒 null,多此一举且埋坑)。**不可复用类①的合并方向。**
  - 类③(Q02):**需新增 `upsertBatchMaterialNoOnly` 重载**,按 material_no 去重一次写。
- **等价性**:`resolve` 的 `nameToNo` 缓存 + `batchMaxGenerated` 保证 resolver 不依赖本批 upsert 可见性(#2 已论证),故延后批量不改料号生成/复用;各类合并方向严格对齐其逐行 COALESCE 链。每类配独立 A/B 等价单测(类① 已有 `MaterialMasterBatchUpsertEquivTest` 兜底,类②/③ 各补一个)。
- **收益**:省 ~800–1500 次 → ~9 次(每 handler 1 条批量)。**~8–15s。风险低(模式已验证,但三类合并方向必须分开实现)。**

### P1-C3 首存:全局变量求值批量化

- **现状**:`ComponentDriverService:493` 每 driver 行调 `resolveGvarForRow:778` → `GlobalVariableService.resolveValue` 单 key 单查。多行 × 多 gvar = 行级 N+1。
- **方案**:`GlobalVariableService` 加批量重载 `resolveValues(code, List<keyValues>)`,一次 `WHERE key_col IN (...)` 取回全部行的值,内存分发。**纯下沉到 gvar 服务层,不动 expand 的可变引用语义、不碰渲染基线。** 报价/核价两侧同时受益。
- **⚠️ 必须覆盖两条取数路径,且两路 key 结构不同、NULL 处理相反(r2 二轮评审订正)**:`resolveValue:241` 按 `def.isKvTable()` 分两条路,批量化两条都要做,但**不能套同一个 NULL 模型**:
  - **KvTable 路径**(`resolveValueFromKvTable:247`):复合 key 已被 `buildKeyIdForKvTable:298` **预编码成单个 `:`-join 字符串 `key_id`**(NULL 分量编码为空串 `""`,`:302/:308`),查询是 **单列 `var_code=:c AND key_id IN (...)`**。→ **没有元组、没有 NULL 不匹配问题**;批量用同一个 `buildKeyIdForKvTable` 生成 key_id,与逐行**逐位等价,无需任何 NULL 特判**。
  - **View 路径**(`resolveValueFromView:265`):真·多列谓词 `col1=:p0 AND col2=:p1 ...`。→ 复合 key IN 化才需元组 `(k1,k2) IN ((..),(..))`,且 **NULL 在元组里不匹配**——含 NULL 分量的行必须 **逐行回落** 或用 `IS NOT DISTINCT FROM` 改写。
- **等价性(定位订正)**:别名探测 `findAliasValue:818` 发生在 `resolveGvarForRow` **组装 keyValues 阶段、在调 resolveValue 之前**,不在 `resolveValue` 内 → 批量化 resolveValue **不会丢别名**(别名仍逐行做,只把"组装好 keyValues 后的查库"批量化)。保守策略:批量只做"主键全命中"快路径,**未命中 / 含 NULL key / 别名行 一律逐行回落老逻辑**,保证语义逐位不变(防单价漂移 AP-52)。
- **收益**:对元素/工序类多行组件可观,对单行组件无感。**中等收益,风险中**(双路 + 复合 key NULL + 回落分支,非纯"下沉")。

### P1-Q19 导入:年降系数逐行 INSERT → 批量

- **现状**:`Q19AnnualDiscountHandler:35` 逐行 `INSERT INTO annual_discount ... ON CONFLICT`。
- **方案**:照 `upsertBatchNameType` 模式,内存按冲突键 `(biz_type, material_no, discount_strategy, discount_order)` **去重**(PG 不允许同一 INSERT 命中同冲突键两次)后,多值 `INSERT ... VALUES (...),(...) ON CONFLICT DO UPDATE`,分块 500。
- **收益**:省 ~95%(~100–200 次)。**低风险。**

---

### P2-C4 首存:核价侧跨行 partSet 并集(本方案收益最大项)

- **现状**:`CardSnapshotService:1178–1196` 对**每核价行**调一次 `BomClosureService.compute`(便宜,已缓存)+ 每核价组件一次 `expandForPartSet`(闭包 partSet 的 `WHERE hf_part_no = ANY`)。`expandMulti` **不进 resultCache**(`DataLoader:240`),故行间 BOM 闭包高度重叠时**完全不复用**。量级 N × M_cost。
- **方案**:
  1. 先对全部 N 行各算 `BomClosureResult`(缓存,便宜),把所有行的 `partSet` **求并集**。
  2. 对每个核价 driver 组件,**一次** `expandMulti(componentId, customerId, union(partSet))`,得 `Map<partNo, resp>`。
  3. 再按各行 spine 逐节点(`buildSpineBaseRows:1214` 已是按 partNo map 取数)**深拷贝分发**回各行。
  把 C4 从 `N × M_cost` 压到 `M_cost` 次多值查询。
- **为何相对安全**:核价侧 **editRows 恒空、永久冻死、与报价侧物理隔离**(`CardSnapshotService` 全文强调),改它**不触碰报价渲染、不碰 AP-41 双视图对齐**;`expandMulti` 返回的 `Row` 经 `rowToNode`(`MAPPER.valueToTree` 深拷贝,`:686`)→ 满足硬约束①防御性拷贝。
- **必守等价性(高关注)**:
  - **AP-37 串卡**:union 后分发**必须用 (lineItemId, partNo) / task index 严格配对**,绝不能按 backend `hf_part_no` 直接配(同子料号跨行共享会串号)。`expandForPartSet` 注释已警告。
  - 保留 **composite 聚合视图逐料号回退**(`:633`)——union 方案不得吞掉该分支。
  - **AP-51 行数对账**:`buildSpineBaseRows` 行数 = Σ max(1, 节点业务行数)(`:1212`),union 查询的 ANY 谓词不得改变行数。核价 V6 视图无 `quotation_line_item_id` 维度(AP-53,customer×material 共享)→ union 安全,但**必须验证**。
  - DAG 重复子件**复制**业务数据(spec `:1149`)是期望语义,分发时每行各持深拷贝副本。
  - **🔑 可变共享面(独立评审采纳,r2 扩措辞)**:union 后 `Map<partNo, resp>` 是**全行共享的同一份**,各 li 的 `buildSpineBaseRows` 会从同一个 `resp.rows` 反复读。`expandMulti:584` 的 `row.driverRow = driverRow` **持有 `loadByPath` 返回的原始 Map 引用**;当前下游仅经 `spineRowNode → rowToNode`(`MAPPER.valueToTree`,每次新建 ObjectNode、只读)使用,**安全**。但 union 把"每 li 各查一份"变成"全 li 共享一份 `resp.rows`",**放大了"未来任何代码就地 mutate `Row.driverRow`/`Row.basicDataValues`"的跨 li 串污染影响面**。→ 落地必须审计**整条分发链** `resp.rows.Row.{driverRow,basicDataValues}` 全程**只读**(不仅 rowToNode 一处),并在 §4"连跑两次 md5"中**专项覆盖此项**。
- **风险订正**:实际**中低**——`expandForPartSet:642` 普通 `$view` 分支调 `expandMulti` 时**不传 lineItemId**(查询本就不含行维度),跨行 union 按 `hf_part_no` 分桶与逐行分别查**结果一致**;仅 composite-agg 分支(`:633`)带 lineItemId 需保留逐料号回退。走 architect 是流程合规,非技术高危。
- **收益**:核价侧 driver 往返 ≈ ÷N,核价是首存最重段(含 BOM 树),**保守削整体 30–50%**。**走 architect 评审 + 强制等价验证(见 §4,含上面的可变共享专项)。**

### P2-D 导入:MaterialNoResolver 按名预取 + dbMax 缓存

- **现状**:`MaterialNoResolver:51` 对每个"料号空+名称首现"的去重名 1 次 `findFirstByMaterialName`;需生成 9 字头时每次 `lockForMaterialNoGeneration:88` + `maxNineLeadingMaterialNo:89` = 2 次/新名。
- **方案**:① 每 handler 行循环前扫出"料号空+名称非空"的全部去重名,一次 `SELECT material_name, MIN(material_no) ... WHERE material_name IN (...) GROUP BY material_name` 预填 `nameToNo`;② `BatchState` 缓存 `dbMax`,生成路径只查一次 max,后续走 `batchMaxGenerated` 自增(代码已自增,只是每次重查 max,可省)。
- **收益**:省 ~50–300 次。**中低风险**(别名/复合 key 语义需保留)。

### P2-Q05 导入:元素回收折扣逐行 UPDATE → 批量

- **现状**:`Q05ElementRecoveryHandler:62` 逐行条件 `UPDATE element_bom_item SET recovery_discount ...`。
- **方案**:`UPDATE element_bom_item SET recovery_discount = v.rd FROM (VALUES (...),(...)) AS v(material_no, component_no, rd) WHERE ...` 一条批量(PG 支持)。中风险(值列表构造 + NULL 处理需对账)。
- **⚠️ PG 陷阱(独立评审采纳)**:`VALUES` 列表里的 NULL 必须**显式类型标注**(如 `(.., .., NULL::numeric)`),否则 PG 报 `could not determine data type of column`。首行各列给定类型 cast,或全行 NULL 列统一标注。
- **收益**:~1–2s。

---

### P3-B 导入:VersionedV6Writer 跨组预取(需 architect)

- **现状**:每组 `loadCurrentGroup`(方法定义 `VersionedV6Writer:276`,调用点在 `writeVersionedMasterDetail:201`/`writeVersionedGroup`)+ `nextVersionOf`(定义 `:329`,调用点 `:207`)+ `currentVersionOf:319` + `flip:340` + `advisoryLock:268` 各 1 次独立 SQL,合计该项最大(~3300–5000 次里的主体)。
- **方案**:给 writer 加"预取上下文"入口——handler 调 writer 前已知本 sheet 全部分组键:
  - 一条 `SELECT <groupKey>, <content> FROM tbl WHERE system_type=? AND customer_no=? AND is_current=TRUE` 按客户拉回 current 全集,内存分桶替代每组 `loadCurrentGroup`。
  - 一条 `SELECT <groupKey>, MAX(version::int) ... GROUP BY <groupKey>` 拿回所有组版本号,替代每组 `nextVersionOf`。
- **等价性**:同一事务内,本次写入前 current 全集是静态的 → 预取等价。
- **不动**:`advisoryLock` 逐组(PG 硬约束,~800–1100 次无法消除,除非改锁粒度=语义变更,排除)。
- **收益**:省 ~800–1400 次(~8–14s)。**风险中高:改 writer 内部路径 + 13 个 handler 调用契约,必须走 architect + 全量等价回归。**

### P3-C1 首存:报价侧合桶(仅非 :lineItemId 组件,谨慎/可选)

- **现状**:`ConfigureSnapshotService:191` 报价侧逐行逐组件单值 expand,跨行 resultCache 不命中。
- **方案**:用现成 `viewUsesLineItemId`(`:665`)做闸门,**只对"纯按 customerCode+hf_part_no 过滤"的组件**跨行 `expandMulti` 合桶;含 `:lineItemId` 的组件**保持逐行**(保 Bug B 竞态保护 `:358–401`)。
- **风险(最高)**:报价 snapshot_rows 是渲染权威数据源;Bug B(查不到返 EMPTY 不 fallback)是修 configure 竞态串号的保护,合桶易绕过 → 回归雷区。收益又受报价模板里非-lineItemId 组件占比限制。**建议 P1/P2 落地、实测仍不达标后再单独评估。**

---

## 4. 等价性验证策略(本方案成败关键)

> 教训(并行回滚事故):**正确性必须用"连跑两次比对哈希"验证,非确定性问题单跑看不到。** 本方案不并行(理论确定),但批量改动仍须逐项证明"与逐行逐位一致"。

每个优化项落地必带:
0. **往返数实测基线 + 下降验收门(独立评审采纳;r2 订正计数口径)**:落地**前**先对同一份 Excel 实测当前远程 SQL 往返数,把 §0/§2 的估算区间换成实测值;落地**后**复测,断言往返数确实从 N 降到预期。**计数口径用 PG 服务端真实往返**——`pg_stat_statements.calls` 的前后增量(或单连接语句计数),**不要用应用层 `em` counter**:导入/首存大量走 `REQUIRES_NEW` 子事务 + `componentDriverService.evictAll()` 清进程缓存,应用层计数会混入缓存命中/事务边界噪声,口径不准。**验收门不只验"值等价",还要验"往返数下降"**。
1. **A/B 等价单测**:同一组输入,逐行老路径 vs 批量新路径,断言落库行/值逐位相同(模板:`MaterialMasterBatchUpsertEquivTest`)。P1-A 三类(name/type 首值、Q18 末值、Q02 单列)各一个;P1-C3 双路各一个。
2. **前后置 DB 快照 diff**:对同一份 Excel,在改动前后各跑一次导入/首存,逐表 `md5(array_agg(... ORDER BY <稳定键>))` 全等。**目标表必须显式列入**:
   - 导入侧:`material_master` / `annual_discount` / `element_bom`+`element_bom_item` / `material_bom`+`material_bom_item` / `unit_price` / `capacity` / `plating_scheme`。
   - 首存侧:`quotation_line_item`(snapshot_rows + 四值列)/ `quote_card_values` / `costing_card_values` / `quotation_view_structure`。
3. **连跑两次自比对**:同输入跑两遍,结果 md5 必须一致(兜底防任何引入的非确定性)。**P2-C4 专项覆盖**:union 后 `Map<partNo,resp>` 全行共享读是唯一可变共享面,此项必须确认两遍 md5 一致。
4. **既有测试全绿**:`MaterialBomMergeHandlerTest` / `AssemblyBomMaterialSyncTest` / `MaterialNoImportIdempotencyTest` / `FormulaCalculatorTest` / `SnapshotReconcileTest` / Versioned 套件 / E2E `quotation-flow`。
5. **P2-C4 专项**:核价四视图(核价卡片/核价Excel)逐 Tab 行数 + 单元格值,改动前后截图/导出对账(AP-51/AP-45)。

任一项对账不过 = 该项回滚,不合入。

---

## 5. 明确不做(范围外)

- ❌ 并行化 expand/公式/快照层(已实证竞态,锁定核心基线)。
- ❌ 首存异步(用户已排除)。
- ❌ 改 `pg_advisory_xact_lock` 为 customer 级(语义变更,需独立架构决策)。
- ❌ 优化 BOM 递归(已是单 CTE + 缓存,非瓶颈)。
- ❌ 退役 row_data / snapshot_rows(load-bearing,见快照项目 Task6 结论)。

---

## 6. 预估总收益

| | 现状 | P1 后 | +P2 后 | +P3 后 |
|---|---|---|---|---|
| 导入墙钟 | ~50–75s | ~40–58s | ~37–53s | ~22–32s |
| 首存(进入报价单) | ~47s | ~40s(C3) | **~24–33s**(C4 ÷N 核价侧) | 同左(P3-C1 收益受限) |

> **P0 不计入收益**(r2):`reWriteBatchedInserts`/`statement-batch-size` 对本方案"原生单条多值 INSERT"路径不生效(见 §P0),收益预期 ≈ 0。导入提速主体来自 **P1-A/Q19/Q05 的逐行→多值改造**。

**最大不可消除残留**:导入侧 `advisoryLock` 每分组 1 次(~800–1100 次);首存侧报价 :lineItemId 组件逐行(Bug B 保护)。二者都需"改语义/碰核心基线"才能动,不在等价优化范畴。

## 7. 建议落地顺序

0. **先用 §4-(0) 测准当前往返基线**(PG `pg_stat_statements.calls`)——这是后续每项收益对账的锚点。(P0 配置不作为基线项;`reWriteBatchedInserts` 可随手加但收益≈0,`statement-batch-size` 不加。)
1. **P1 三项**为提速主体(全低风险、不需 architect、各自独立可验):A(导入 -8~15s,**三类合并方向分开实现**:name/type 首值 / Q18 末值 / Q02 单列)+ C3(首存 gvar,**KvTable 单列 IN / View 多列元组两路分开**)+ Q19。
2. **再 P2-C4**(首存最大杠杆,走 architect + §4 全套对账,含整条分发链可变共享专项)+ P2-D/Q05(导入)。
3. **P3 视实测决定**:若导入仍不达标做 B(architect);首存 C1 风险最高,最后评估。

每阶段独立 worktree 分支、subagent-driven、合入前过 §4 等价验证(含往返数下降验收门)。
