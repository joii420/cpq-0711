# saveDraft 全链路集合化（set-based）重构 — Phase 1 架构设计

> 立项 2026-06-25。架构师产出，仅设计 + 实现计划 + spec，不写业务代码、不提交。
>
> **目标（用户已拍板，硬约束）**：把 saveDraft 从「逐行 × 远程SQL/递归DB」改成「集合化批量」，使**上万行导入首存**：同步秒级 + 卡片值/Excel/snapshot 全部算好落库 + 与现状逐位等价（md5）；覆盖**全路径**（首存/导入、加产品、刷新基础数据、editQuoteCardValue、submit）× **全侧**（报价/核价/Excel）；全删全建改为批量/真增量。
>
> **逐位等价标尺**：`cpq-backend/.../quotation/service/GoldenCardValuesEquivTest.java`（8f0c37a4(170)=`3837c2bd35ada869ff09799739512d6e`、a8f17a74(77)=`2cc56fead05427c1a1c86ae15f417248`，含 determinism）。**重构每阶段必须命中 golden。**
>
> **非线程安全纪律**：[[cpq-expand-layer-not-threadsafe]]（2026-06-22 实证并行化 9/73 静默错价已回滚）→ **集合化必须单线程批量 SQL，严禁并行化。**

---

## 0. 术语与边界澄清（先统一，避免走回头路）

- **集合化（set-based）≠ 并行化**。本方案所有「一次多值」都是**单线程一条 SQL** 处理多行（`WHERE x = ANY(:array)` / `unnest` JOIN / 批量 `UPDATE…FROM (VALUES…)`），CPU 仍单线程。与被否的「后台异步线程预算核价」（踩非线程安全 + FK 违反）正交。
- **核价侧 ≠ 核价单**。核价侧 = 报价单内的核价视图（per line `costingCardValues`，`buildCostingCardValues`，在 saveDraft 算）；核价单 = 独立单据 `CostingSummary`（不在 saveDraft 范围）。本方案「全侧」指**报价卡 / 报价Excel / 核价卡 / 核价Excel 四份**，均 per-line。
- **已落地基线**（master `93c7768` 起，本设计的起点，**不重复造**）：
  - Phase 1 批量写：`ConfigureSnapshotService.writeSnapshotBatch` + `writeRowDataBatch`（两段式 `UPDATE…FROM(VALUES)…RETURNING` + 未命中多值 INSERT，逐行 UPSERT 语义 1:1）；
  - Phase 2 报价合桶：`precomputeQuoteDriverBuckets`（eligible 组件 `expandMulti(全部不同 partNo)` 一次取回，按 hf_part_no 回分）；
  - 核价合桶：`CardSnapshotService.precomputeCostingDriverUnion`（eligible recursive 组件对全单 partSet 并集一次 `expandForPartSet`）；
  - B1 memoize computeRows、B2 批量 EM（模板 parse 一次 + compdata 整单 IN）、A1/A2/A3 前端 gate batch-evaluate + AbortController（commit `0c9f6c0`..`d703c1f`）。
  - **现状 saveDraft 首存 ≈ 8.6s（170 行，报价侧 snapshotQuotation 108s→8.6s 已合并）**。本设计要把它推到「上万行同步秒级」并把**剩余 per-row 热点全部集合化**，同时把残留的「逐行 driver expand / 全删全建 / getById N+1」一并治理。

---

## 1. 现状代价模型（分阶段 × per-row 热点，落到真实文件:行号）

saveDraft 由 `QuotationResource.saveDraft`（`QuotationResource.java:113`）**非事务编排**，内部三个独立事务阶段：

### 阶段 ①：`QuotationService.saveDraft`（单大事务，`QuotationService.java:257`）

| 热点 | 位置 | per-row 代价 | set-based 化空间 |
|---|---|---|---|
| 逐行 `clearLineItemChildren`（DELETE 4 子表） | `:335` / `:1748` | N 行 × 4 DELETE（按 lineItemId 单删） | 复用行**真 diff**取代全删全建（见 §2.1） |
| 逐行 `li.persist()` | `:387` | N 次单 INSERT/UPDATE | Hibernate batch insert / 保持 UPSERT |
| 逐行 mat_customer_part_mapping 版本查 | `:395-400` | N 次 `SELECT current_version … LIMIT 1` | 整单一次 `WHERE (cpn,hf) IN (…)`（§2.5） |
| 逐行 derivedAttributeCalculatorV5 + `em.flush()` | `:421-432` | N 次公式计算 + N 次 flush | 仅 productId 行触发；批量化 + 去掉 per-row flush |
| 逐行 seedProcessesFromBase（INSERT…SELECT material_bom_item） | `:461-483` | 导入行 N 次 INSERT…SELECT + N 次 customer code 查 | 整单一次 INSERT…SELECT（§2.5） |
| 逐行 compositeProcesses INSERT | `:487-506` | per composite-proc 单 INSERT | 批量 INSERT（低频，次要） |
| 逐行 componentData persist | `:509-528` | N×M 次单 INSERT | Hibernate batch insert（结构落库，非分叉源） |
| V169 二阶段父子 UPDATE | `:543-556` | per-child 单 UPDATE | 批量 `UPDATE…FROM(VALUES)`（§2.4） |
| 末尾 `loadLineItems` 整单回读 | `:570` / `:1830` | getById N+1（见阶段后） | §2.6 |

### 阶段 ②：`snapshotService.snapshotQuotation(id, true)`（`QuotationResource.java:119` → `ConfigureSnapshotService.snapshotLines:211`）

- **已集合化**（Phase 1+2）：整单一次 `loadSnapshotRowsByLines`（`:253`）+ `precomputeQuoteDriverBuckets`（`:262`）+ `writeSnapshotBatch`（`:324`）+ `writeRowDataBatch`。
- **残留 per-row 热点**：`comps` 循环里 **不 eligible 的组件逐行 `componentDriverService.expand(...,lineItemId,compositeType)`**（`:303`）。这是 §2.3 多 line expand 的主战场（报价侧）。
- `materializeRowData`（`:522`）整行物化，computeRows 已 memoize（B1）。

### 阶段 ③：卡片值循环（`QuotationResource.java:133-185`）

对**无 `quoteCardValues` 的新行**：`snapshotLineValuesWithUnion` → `snapshotQuoteSideOnly`（`buildCardValues`+`buildExcelValues`）+ `snapshotCostingSideOnly`（`buildCostingCardValues`+`buildExcelValues(costingTree=true)`）。
- **已有 B2 prefetch**（`precomputeCardValuesPrefetch`，模板 parse 一次 + compdata IN）+ **P2-C4 核价 union**（`precomputeCostingDriverUnion`，`CardSnapshotService:497`）。
- **残留 per-row 热点**（two-track 实测 88s 分项，是「上万行」的真正天花板）：
  - **baseRows 25s**：`expandTemplateDriverBoseRows`（`CardSnapshotService:1316`）对每行每 driver 组件逐行 `expand(…,li.id,compositeType)`（`:1338`）——**带 lineItemId / spine 维度的组件无法进 union，逐行远程**（§2.3 核价侧 + spine 侧）。
  - assemble 33s（B1 已 memoize，剩 PASS1+PASS2 复用）、EM 15s（B2 已批量 0.19s）、Excel 11s。
  - 公式求值 0.1s、序列化 0.02s — **可忽略，慢在管道不在公式**。

### getById / loadLineItems N+1（读路径，`:1830`）

- `loadLineItems` 已批量查 mat_customer_part_mapping（`:1834`），但 `populateViewStructures`（`:161`）+ 4 份卡片值读取仍 per-quotation 多次。上万行打开时放大，**次要靶**（§2.6）。

### 关键障碍：Bug-B / 多 line expand（14s 来源的墙）

`ComponentDriverService.eligibleForBomUnion`（`:745`）+ `viewHasNoRowDimension`（`:722`）规定：仅 `bom_recursive_expand==true` 且视图**无 lineItemId / 无 spineKeys / 非 composite** 的组件能合桶。**带 lineItemId 维度的组件**（`v_composite_child_processes` 等、spine-keyed 视图）走 `expand` 单行远程（`:381` 注入 `quotation_line_item_id = :lid` 经 `lineItemHint` → `loadByPath`）——这就是逐行 14s 的根因，也是「全集合化」必须正面攻克的墙（§2.3）。

---

## 2. 目标架构

总原则：**「准备阶段集合化 → 分发阶段纯内存 → 落库阶段批量」三段式**。每个 per-row 远程 I/O / 递归 DB / 全删全建，映射成「一次集合 SQL + 内存按 key 回配」。落库走已落地的 `writeSnapshotBatch`/`writeRowDataBatch` 同款两段式批量。

### 2.1 批量持久化 + 真 diff 取代全删全建（顺带根治并发删数据）

**现状**：复用行也 `clearLineItemChildren`（全删 4 子表）再全量重建（`:335`）；删除行再删（`:533-537`）。Phase 0 实测「全删全建并发竞态会真实删数据」（939e072e 被抹成 0 行）。

**目标**：
- **行实体**：保持已落地的「按 id UPSERT」（`:322`，复用行就地 UPDATE id 不变）。
- **子表**：把「逐行 clearLineItemChildren + 逐行 persist」改为**整单批量 diff**：
  - `quotation_line_process` / `quotation_line_component_data` / `quotation_line_item_snapshot` / `quotation_line_composite_process` 各自：一次 `DELETE … WHERE line_item_id = ANY(:keptLineIds)`（仅复用行）+ 批量 INSERT（`UNNEST` 多行 VALUES）。
  - 删除行：一次 `DELETE … WHERE line_item_id = ANY(:removedLineIds)`。
- **并发删数据根治**：当前 saveDraft 阶段①已是单事务 + 按 id UPSERT，并发竞态主要来自「autosave 风暴下两个 saveDraft 交错全删全建」。在 `quotation` 行上加**乐观锁版本列 `version`（@Version）**或 saveDraft 入口 `SELECT … FOR UPDATE` 行锁，串行化同单 saveDraft，使「全删」不再与另一请求的「重建」交错。**这是数据安全修复，与性能正交，优先级最高。**

**等价论证**：diff 后子表最终内容 = 全删全建后内容（同 draft payload → 同 INSERT 集合）。golden 不读子表行号顺序（buildCardValues 按 componentsSnapshot 拓扑序 + snapshot_rows），但**必须保证 INSERT 顺序与 sortOrder 一致**（componentData.sortOrder 落库值参与渲染序）。

**取舍**：
- 方案 A（推荐）：保留「子表全删 + 批量重建」，只是把逐行 DELETE/INSERT 合成集合 SQL。**改动小、等价显然**（语义不变，只是 SQL 合批）。
- 方案 B：真行级 diff（按 row_key 比对，只 UPDATE 变动行）。更省 I/O，但引入 row_key 比对复杂度 + 撞 AP-51 行数纪律 + AP-54 下标错位风险。**不推荐**第一阶段做。
- 取 A：风险/收益最优，且子表落库非 golden 分叉源（分叉只在「值」）。

### 2.2 集合 BOM 闭包（对全部根料号一次）

**现状**：`precomputeCostingDriverUnion` 已对全单 partSet 并集合桶；`expandTemplateDriverBaseRows` 逐行重算闭包（Caffeine 缓存兜底）。Phase 0 实测「BOM 闭包递归 = 992ms，仅 7.5%，不是瓶颈」。

**目标**：保持现状（已足够快），仅做两点收口：
- 闭包结果整单一次预算（按根 partNo 去重），结果驻留请求级 Map，供阶段②③共享（消除 `expandTemplateDriverBaseRows` per-row 重算闭包）。
- 闭包 Caffeine key 必须含 customerId（V6 customer×material 共享，[[cpq-sqlview-cache-key-needs-component-dim]] 同源风险）。

**取舍**：闭包不是热点，**不重写算法**，只复用。避免过度工程。

### 2.3 多 line driver expand（攻 Bug-B 的核心 —— 可落地方案，非「待定」）

这是全集合化的墙。现状「带 lineItemId / spineKeys 维度的视图必须逐行远程」。目标：**一次接受全部 `(lineItemId, partNo)` 集合、返回按 lineItemId 打标行的 multi-line expand**，产出与逐行**逐位一致**。

#### 2.3.1 三类视图按维度归类（决定可合桶性）

| 类 | 代表视图 | 行维度 | 现状 | 集合化方案 |
|---|---|---|---|---|
| **无行维度** | 核价 5 视图（customer×material 共享，无 lineItemId 无 spineKeys） | hf_part_no only | 已合桶（`expandMulti` ANY(:hfPartNos)） | 不变（已最优） |
| **spineKeys 维度** | 核价 BOM 树 recursive 视图（`:spineKeys(p,pp,v)` 宏） | (子件,父件,版本) 三元组 | 逐行 `expandForPartSet` + per-line `SpineKeysContext` | **§2.3.2 多 line spine expand** |
| **lineItemId 维度** | `v_composite_child_processes` / per-quote 工序 mirror（`:lineItemId` / `quotation_line_item_id`） | quotation_line_item_id | 逐行 `expand` 注入 `quotation_line_item_id = :lid`（`:381`） | **§2.3.3 多 line id expand** |

#### 2.3.2 spineKeys 维度的多 line 合桶

`SpineKeysContext`（ThreadLocal `Triples{partNos[],parentNos[],versions[]}`）+ `SpineKeysMacro` 把宏展开成 `EXISTS(SELECT 1 FROM unnest(:__skP,:__skPP,:__skV) AS k(p,pp,v) WHERE … IS NOT DISTINCT FROM …)`。**这个机制本身已是集合化的**——它一次接受一个三元组数组。现状之所以逐行，是因为**每行用自己那行的闭包 spine 设 Context**。

**多 line 方案**：
- 把**全单所有行的 spine 三元组取并集**（按 `子件|父件|版本` 复合键去重，复用 `SpineKeysContext.fromClosure` 的去重逻辑，扩成 `fromClosures(List)`），一次设 Context。
- 一次 `loadByPath(spineView, partSetUnion, customerId)`（`WHERE hf_part_no = ANY(:hfPartNos)` 外层 + `:spineKeys` EXISTS 内层），返回全单所有 spine 节点的行。
- **回配**：结果行带 `hf_part_no`（子件）+ 父件列 + 版本列，按 `(子件,父件,版本)` 三元组键回配到每行的 spine 节点（与逐行 `expandForPartSet` 的左关联键一致）。
- **逐位等价论证**：逐行版每行设 `SpineKeysContext.fromClosure(本行闭包)`；并集版设 `fromClosures(全单闭包)`。`unnest…IS NOT DISTINCT FROM` 是**集合成员判定**，「该三元组是否在数组里」对单行子集和全单并集**结果集相同**（行被选中当且仅当其三元组 ∈ 并集 ⊇ 本行子集，且本行只取属于本行 spine 的三元组）。**前提**：回配严格按三元组键，不靠数组下标——本行只领取键在本行 spine 里的行。**这是可证明逐位等价的。**

#### 2.3.3 lineItemId 维度的多 line 合桶（最难，给可落地机制）

现状 `expand` 对 lineItemId 维度视图：构造 `lineItemHint{quotation_line_item_id: lineItemId}` → `loadByPath` → `ImplicitJoinRewriter` 注入 `quotation_line_item_id = :lid`（单值等值谓词，`:381-401`）。**专属行不存在直接返 EMPTY（不 fallback 主数据）**（`:392-401`，Bug-B 隔离纪律）。

**多 line 方案 = 把单值等值谓词升级为 IN 谓词 + 按 lineItemId 列回配**：
1. **路径层**：新增 `appendLineItemInPredicate(path, List<lineItemId>)`（仿现有 `appendChildLineItemInPredicate`，`:1264`，已验证可生成 `quotation_line_item_id IN (…)`）。把全单（同一组件、同一 driverPath）所有 SIMPLE 行的 lineItemId 收集成 IN 列表，一次 `loadByPath(inPath, null, partSetUnion, customerId)`。
2. **结果必须带 `quotation_line_item_id` 列**：mirror SQL 已 SELECT 该列（V207/V209 注释，`:407`）。返回行按 `quotation_line_item_id` 回配到对应行。
3. **EMPTY 语义保持**：逐行版「专属行不存在 → EMPTY 不 fallback」。多 line 版：某 lineItemId 在结果里无行 → 该行得空响应（rowCount=0），**不补主数据**。等价。
4. **缓存**：多 line 入口**不写 `expandCache`**（与 `expandMulti` 同策略，`:521` 注释），避免 lineItemId-tagged key 串号。saveDraft 是写路径，缓存收益小。

**逐位等价论证（lineItemId 维度）**：逐行版 `WHERE quotation_line_item_id = :lid`（每行一查）；多 line 版 `WHERE quotation_line_item_id IN (:lids)`（一查）+ 按列回配。**关键不变量**：每行最终领取的行集 = `{r : r.quotation_line_item_id == thisLineId}`，与单值等值查结果**逐行相同**。**前提**：
- 回配按 `quotation_line_item_id` 列值，不靠下标（避免 AP-54 下标错位）；
- 视图对同一 lineItemId 的行**集合稳定**（IN 不改变单行子集的行集，只是一次取回多个 lineItemId 的并集）；
- **同 partNo 多 lineItem 不串**（Bug-B 核心）：因为回配键是 lineItemId 不是 partNo，天然隔离。✅

**风险点**：`v_composite_child_processes` 的 COMPOSITE 父级分支（`:402-459`）现用「childLineItemIds IN + 主数据 IS NULL 合并 + 去重」。COMPOSITE 父级是**少数行**（组合产品父卡），其逐行成本占比小。**第一阶段不合桶 COMPOSITE 父级**（保留逐行 `expand` 9-arg），只合桶 SIMPLE 行（占上万行导入的绝对多数）。COMPOSITE 父级合桶留作后续可选阶段。

#### 2.3.4 统一入口

新增 `ComponentDriverService.expandForLineItems(componentId, customerId, List<LineItemKey>, …)`，`LineItemKey{lineItemId, partNo, compositeType}`。内部按 §2.3.1 三类分派：
- 无行维度 → 委托 `expandMulti`（已存在）。
- spineKeys 维度 → §2.3.2（全单 spine 并集一次查 + 三元组回配）。
- lineItemId 维度（SIMPLE）→ §2.3.3（IN 谓词一次查 + lineItemId 列回配）。
- COMPOSITE 父级 / 不可合桶 → 逐行 `expand`（保留，少数行）。
返回 `Map<lineItemId, ExpandDriverResponse>`。**调用方（snapshotLines / expandTemplateDriverBaseRows）改为先 `expandForLineItems` 取全单，再内存按 lineItemId 取**，消除 per-row 远程。

**AP-37 可变共享面**：返回 Map 的 resp.rows 被回配后**每行落库前必须 `MAPPER.writeValueAsString` 深拷贝**（已有先例 `:308`）。

### 2.4 批量公式 assemble + 批量序列化

- **assemble**：B1 已 memoize computeRows（PASS1/PASS2 复用）。剩余：`buildCardValues`/`buildCostingCardValues` 仍 per-line 调用。集合化方案：**模板 parse 一次（B2 已做）+ 把 per-line assemble 收敛成一个循环内纯内存计算**（输入已是内存 baseRows，不再触发 I/O）。公式求值实测 0.1s，**不是靶**——只要把它前面的「取数 I/O」集合化（§2.3），assemble 自然秒级。
- **序列化**：实测 0.02s，可忽略。`valueToTree` 逐行建节点（baseRows 25s 的一部分）在 §2.3 集合化取数后大幅下降。

### 2.5 阶段① per-row SQL 集合化

- **mat_customer_part_mapping 版本查**（`:395`）：整单一次 `SELECT customer_product_no, hf_part_no, current_version FROM mat_customer_part_mapping WHERE (customer_product_no, hf_part_no) IN (…)`，内存按 (cpn,hf) 回填 `partVersionLocked`。等价（同 LIMIT 1 语义：(cpn,hf) 应唯一；若不唯一，用 `DISTINCT ON` 取确定一行，与现状 `LIMIT 1` 对齐——需核对现状 LIMIT 1 的确定性，见 §6 开放问题）。
- **derivedAttributeCalculatorV5**（`:421`）：仅 productId 行触发，去掉 per-row `em.flush()`（`:431`），循环结束统一 flush。批量加载 derivedAttrs（一次 IN）。
- **seedProcessesFromBase**（`:461`）：整单一次 INSERT…SELECT，把 `WHERE material_no = :part` 升级为 `material_no = ANY(:parts)` + JOIN line_item 映射回 lineId。customer code 一次查。等价（INSERT 集合相同）。

### 2.6 getById N+1 治理（次要靶，一并做）

- `loadLineItems`（`:1830`）：mat_customer_part_mapping 已批量。`populateViewStructures`（`:161`）整单一次 `WHERE quotationId = ?` 已批量。剩余 per-line 读 4 份卡片值在 `QuotationDTO.from` / loadLineItems —— 确认是否 per-line 查实体（Panache lazy）。**上万行打开**：改为一次 `QuotationLineItem.list` + 投影需要的列，避免逐行 findById。
- 优先级：低于阶段①②③，但「上万行打开」体验需要。放最后阶段。

### 全路径全侧覆盖矩阵

| 路径 | 入口 | 触及阶段 | 集合化要点 |
|---|---|---|---|
| 首存 / 导入 | saveDraft（新行全部） | ①②③ | 全量；§2.1~2.5 主战场 |
| 加产品 | saveDraft（部分新行）+ `snapshotQuotation(false)` | ②③ | 新行集合化；复用行 skip |
| 刷新基础数据 | `refreshDraftQuoteCards` / `refreshQuoteCardValues` | ②③（强制重 expand） | §2.3 多 line expand 同样适用 |
| editQuoteCardValue | 单卡端点 | 单卡（0.24s 可接受） | **不改**（单卡集合化无收益） |
| submit | `submit` | 冻结现有快照 | 不重算（已冻结）；不在热点 |
| 报价卡 / 报价Excel / 核价卡 / 核价Excel | buildCardValues / buildExcelValues(×2) / buildCostingCardValues | ③ | 四份均经 §2.3 集合化取数 + §2.4 assemble |

---

## 3. 逐位等价策略（每个 per-row → set-based 映射的等价论证 + golden 卡口）

| # | per-row 操作 | set-based 等价物 | 等价依据 | 能否逐位等价 |
|---|---|---|---|---|
| E1 | 逐行 clearChildren + persist | 集合 DELETE ANY + 批量 INSERT | 同 draft → 同行集合；保 sortOrder 序 | ✅（§2.1 方案 A） |
| E2 | 逐行 mat_customer_part_mapping LIMIT 1 | (cpn,hf) IN + DISTINCT ON 回填 | (cpn,hf) 唯一性下结果相同 | ✅（需核对 LIMIT 1 确定性，§6-Q1） |
| E3 | 逐行 seedProcessesFromBase | 一次 INSERT…SELECT ANY(:parts) | INSERT 集合相同 | ✅ |
| E4 | 逐行 derivedAttr + flush | 批量计算 + 末尾 flush | 公式纯函数；flush 时机不影响结果 | ✅ |
| E5 | 逐行 V169 父子 UPDATE | 批量 UPDATE…FROM(VALUES) | 同 (child,parent) 对 | ✅ |
| E6 | 无行维度 driver 逐行 expand | expandMulti ANY(:hfPartNos) | 已落地，md5 已铁证（108s→8.6s 等价） | ✅（已验证） |
| E7 | spineKeys 逐行 expand | 全单 spine 并集 + 三元组回配 | `IS NOT DISTINCT FROM` 成员判定，回配按键 | ✅（§2.3.2 论证） |
| E8 | lineItemId 逐行 expand（SIMPLE） | IN 谓词 + lineItemId 列回配 | 等值 vs IN 行集相同，回配按列 | ✅（§2.3.3 论证，前提:回配按列不靠下标） |
| E9 | COMPOSITE 父级逐行 expand | **保留逐行**（第一阶段不合桶） | 不改 = 天然等价 | ✅（不改） |
| E10 | assemble / 序列化 | memoize + 内存 | 公式纯函数 | ✅（B1 已验证） |

**golden 卡口纪律**：每个阶段（独立 PR）必须：
1. 跑 `GoldenCardValuesEquivTest`（rockwell 170 + small 77）→ md5 命中两个常量 + determinism（连跑两次一致）。
2. 真实复现单（罗克韦尔 8f0c37a4）清快照后首存，A/B 对比新旧路径 md5（kill switch 切换）。
3. 上万行 perf 单（**需用户提供或构造**，§6-Q4）测同步耗时目标。

**做不到逐位等价的地方**：暂无识别出**无法**逐位等价的映射。**风险最高的是 E8（lineItemId IN 回配）**——若某 mirror SQL 在 `quotation_line_item_id IS NULL` 主数据行上有特殊语义（SIMPLE 行也可能领取 IS NULL 主数据 fallback？需核对，§6-Q2）。**这是必须先用单测证伪/证实的前置条件。**

---

## 4. 风险与基线影响

### 4.1 对三大核心基线（🔒锁定）的冲击点

- **§4.5 ComponentDriverService.expand 三分支策略**：§2.3.3 在 SIMPLE 分支引入 IN 谓词的 multi-line 变体。**不改 expand 本身**（保留 9-arg 逐行入口作 COMPOSITE + fallback），新增 `expandForLineItems` 旁路。基线 §10.1.3「Bug B 链路改动必须验证 mat_process UNIQUE index 仍约束 + V211 诊断 PASS」→ **本方案不写 mat_process（只读 expand），但仍跑 V211 诊断确认 lineItemId 隔离未破。**
- **§4.7 报价 Excel 前端单引擎权威**：集合化只动**后端 saveDraft 写路径**的取数+落库，`quote_excel_values` 唯一写入源不变（saveDraft 前端值 + snapshotLineValues bootstrap）。**核价 Excel（costingExcelValues）后端重算路径在范围内**（§2.3 核价侧），需确认不破坏「报价侧前端权威」隔离。
- **§5.1 V202 智能视图自适应**：不改视图 SQL。§2.3.2/2.3.3 只改**谓词注入方式**（等值→IN / 单 spine→并集 spine），视图 UNION ALL 分支逻辑不动（基线 §10.1.1 禁改）。

### 4.2 对 AP-44（字段类型协议 17 处）的冲击

- 本方案**不新增/不改 field_type**，不触发 AP-44 全矩阵。但 §2.3 改 driver expand 链路 → **触发「改动 3：driver expansion / batchExpand」决策树**（方案制定前必读 §二）：
  - cache key 6 维不省（多 line 入口不写 cache，规避）；
  - **强制跑 E2E 三 spec**（quotation-flow SIMPLE + composite-product-flow COMPOSITE + multi-product-flow Bug-B 隔离）——multi-product 专测「同 partNo 双独立产品工序隔离」，正是 §2.3.3 lineItemId 回配的回归门槛。

### 4.3 非线程安全纪律（[[cpq-expand-layer-not-threadsafe]]）

- **全程单线程批量 SQL，零并行 stream / 零 parallelStream / 零线程池**。
- expand/expandMulti/expandForPartSet 返回的是**缓存可变对象引用** → 回配后落库前**必须深拷贝**（`MAPPER.writeValueAsString`）。这是 AP-37 + 非线程安全的双重约束，§2.3.4 已纳入。
- ThreadLocal（`SpineKeysContext` / `PartVersionContext` / `QuotationIdContext`）**必须 finally clear**（现状已遵守）；多 line 一次设一次清，比逐行设清更安全（少了 N×set/clear 的窗口）。

### 4.4 数据安全（最高优先级，独立于性能）

- §2.1 乐观锁 / 行锁修「全删全建并发竞态真实删数据」（939e072e 案例）。**这一项即使不做性能也该做**，建议 Phase 2 第一个 PR。

---

## 5. 分阶段实现计划（每阶段独立 PR + golden 卡口 + subagent-driven）

> 落地顺序按「风险递增 + 收益递增」。每阶段：worktree 隔离分支 → TDD（先写等价测试）→ 实现 → golden + 复现 + perf 三验收 → 安全合并清理。
> kill switch 模式统一仿 `cpq.firstsave-batch-write`（System property + env，默认先 false 灰度、铁证等价后转 true）。

### Phase 2-0：数据安全闸（并发删数据根治）— 最高优先级
- 内容：§2.1 quotation 乐观锁 `@Version` 或 saveDraft `SELECT FOR UPDATE` 串行化同单。
- 验收：构造并发 saveDraft（两请求交错）复现 939e072e 式删空 → 修复后行数不丢；golden 不回归。
- 风险：低（不碰计算）。**先做，与后续解耦。**

### Phase 2-1：阶段① per-row SQL 集合化（E2/E3/E4/E5）
- 内容：§2.5（mat_customer_part_mapping IN / seedProcessesFromBase 一次 INSERT…SELECT / derivedAttr 批量+末尾flush）+ §2.1 子表批量 DELETE ANY + 批量 INSERT（方案 A）+ V169 批量父子 UPDATE。
- 验收：golden 命中；阶段① 耗时下降；E2E 三 spec passed。
- 风险：中（E2 LIMIT 1 确定性需先核对 §6-Q1）。

### Phase 2-2：spineKeys 维度多 line 合桶（E7）— 核价 BOM 树侧
- 内容：§2.3.2 `SpineKeysContext.fromClosures(全单)` + 一次查 + 三元组回配；接 `expandTemplateDriverBaseRows` / `precomputeCostingDriverUnion` 的 spine 回退分支。
- 验收：golden 命中（核价侧 C/CE 两份是主战场）；核价 baseRows 段耗时骤降；A/B md5 等价。
- 风险：中高（三元组回配正确性）。先 TDD 写「逐行 vs 并集」等价单测。

### Phase 2-3：lineItemId 维度多 line 合桶（E8）— 报价工序侧（SIMPLE）
- **前置硬门槛**：先单测证实/证伪 §6-Q2（SIMPLE 行是否领取 IS NULL 主数据 fallback）。证伪前不动。
- 内容：§2.3.3 `appendLineItemInPredicate` + IN 一次查 + lineItemId 列回配；§2.3.4 `expandForLineItems` 统一入口；接 snapshotLines（`:303` 不 eligible 逐行回落）+ expandTemplateDriverBaseRows（`:1338`）。**只合桶 SIMPLE，COMPOSITE 父级保留逐行（E9）。**
- 验收：golden 命中；**multi-product-flow.spec.ts 必须 passed**（Bug-B 同 partNo 双产品隔离）；V211 诊断 PASS；baseRows 段骤降。
- 风险：**最高**（Bug-B 隔离 + 回配按列）。

### Phase 2-4：assemble / 序列化收口 + 全路径接线（E10）
- 内容：把 §2.3 集合化入口接到全四份（buildCardValues / buildExcelValues×2 / buildCostingCardValues）+ refreshDraftQuoteCards 路径；确认 editQuoteCardValue 不改。
- 验收：golden 命中；上万行 perf 单**同步秒级**目标达成；四份 md5 全等。

### Phase 2-5：getById / loadLineItems N+1（§2.6）
- 内容：上万行打开读路径批量化。
- 验收：上万行打开 GET 耗时目标；DTO 内容不变。
- 风险：低（读路径）。

### 每阶段统一验收门
1. `GoldenCardValuesEquivTest`：rockwell `3837c2bd35ada869ff09799739512d6e` + small `2cc56fead05427c1a1c86ae15f417248` + determinism。
2. 罗克韦尔 8f0c37a4 清快照首存 A/B（kill switch）md5 逐位等价。
3. E2E 三 spec passed + '加载中' final count=0。
4. 上万行 perf 单同步耗时（Phase 2-4 起强制）。
5. 主线亲验（[[cpq-deliver-agents-overreport]]）+ `git branch --contains` 查子代理误提交（[[cpq-worktree-subagent-commits-to-master]]）；worktree 测试在 worktree 的 cpq-backend 跑（[[cpq-worktree-maven-test-tree]]）。

---

## 6. 未决 / 需架构师（用户）拍板的关键选择

| # | 开放问题 | 为何需拍板 | 建议默认 |
|---|---|---|---|
| **Q1** | mat_customer_part_mapping `LIMIT 1`（`:397`）是否依赖某确定顺序？(cpn,hf) 是否唯一？ | E2 集合化用 DISTINCT ON 必须对齐现状取的「那一行」，否则 partVersionLocked 漂移破 golden | 假设 (cpn,hf) 唯一；先 DB 验证唯一性 |
| **Q2** | lineItemId 维度视图（`v_composite_child_processes` mirror）对 **SIMPLE 行**是否会领取 `quotation_line_item_id IS NULL` 主数据 fallback 行？ | 决定 E8 IN 回配是否需补 IS NULL 主数据（现状 SIMPLE 分支 `:381` 不补，但 COMPOSITE 分支 `:424` 补）。证伪是 Phase 2-3 前置门槛 | 需单测证实；按现状 SIMPLE 不补 |
| **Q3** | 「上万行」的真实模板形态：是否仍是 v_composite_child_* + 核价 spine 树双重 lineItemId/spine 维度？还是纯导入 SIMPLE 行（无 composite、无 spine）？ | 决定 §2.3 三类哪类是上万行主导。若上万行全是「无行维度」则 E6 已解决，§2.3.2/2.3.3 收益有限 | 假设上万行=SIMPLE 导入行（lineItemId 维度工序为主）→ E8 是关键 |
| **Q4** | 上万行 perf 基准单从哪来？（现有最大单 170 行） | 没有上万行单无法验证「同步秒级」目标，也无法定 perf 回归门槛 | 需用户提供导入文件或授权构造合成单 |
| **Q5** | 「同步秒级」的精确 SLA：上万行首存目标几秒？（如 ≤5s / ≤10s） | 决定是否需要 §2.6 之外的进一步优化（如 COPY 替代批量 INSERT、Excel 渲染集合化深度） | 建议先定 ≤10s（万行），达不到再深挖 Excel 11s 段 |
| **Q6** | COMPOSITE 父级是否需要在本轮合桶？ | §2.3.3 第一阶段保留逐行（E9）。若上万行含大量组合产品父卡，需追加阶段 | 建议本轮不做，实测占比后再定 |
| **Q7** | kill switch 灰度策略：每阶段默认 false 灰度，还是铁证后直接 true？ | 影响交付节奏与回滚成本 | 仿现状：先 false，A/B 铁证 + 灰度后转 true |

### 6.1 决议(2026-06-25 用户拍板 + 主线核实)

- **Q1 ✓ 已解(主线 DB 核实)**:`mat_customer_part_mapping` 有唯一约束 `uq_mat_cust_part_per_hf`、`(cpn,hf)` 无重复、`current_version` 单列非多版本 → `LIMIT 1` 确定 → E2 集合化(IN + 回填)逐位等价安全。
- **Q2 ✓ 已解(主线源码核实)**:`ComponentDriverService:361/392-401` SIMPLE 分支**不**领取 `quotation_line_item_id IS NULL` 主数据 fallback(显式注释 + 返 EMPTY);COMPOSITE 分支(`:424`)才合并主数据。→ **E8 的 IN 回配前提成立**(SIMPLE 不会误领主数据)。Phase 2-3 仍以单测正式钉死此不变量。
- **Q3 → 含大量组合/BOM树双维度**:主战场是 E7(spineKeys)+ E8(lineItemId)+ E9(COMPOSITE)都重,非单一 SIMPLE。
- **Q4 → 暂不验 perf,等价优先**:本轮以 golden 逐位等价为交付门;「上万行同步秒级」的 perf 实测顺延到有真实/合成大单时。每阶段用 170 行 `SaveDraftProfileTest` 看分段耗时是否下降作过程指标。
- **Q5 → SLA ≤10s(万行)**:留作 perf 验证阶段目标。
- **Q6 → COMPOSITE 父级合桶顺延**:纯 perf 优化,与 Q4(暂不验 perf)一致 → 本轮 COMPOSITE 保持逐行(E9,天然等价);待 perf 阶段按实测占比再合桶。
- **Q7 → kill switch 灰度**:每阶段默认 false,A/B 铁证等价 + 灰度后转 true。
- **Phase 2-0 时机**:用户要求「问题都定了再统一开」;现已全定 → 进入 Phase 2,2-0 数据安全闸为第一个。注:Plan A 已止住「打开风暴」这一并发删数据的主诱因,故 2-0 现为**纵深加固**(防多 tab/多用户/快速编辑仍可能的同单并发),非仍在流血。

---

## 7. 关联文档与基线索引

- 🔒 `docs/三大核心模块基线.md`（§4.5 三分支 / §4.7 Excel 权威 / §5.1 V202 / §10 变更红线）
- `docs/方案制定前必读.md`（§二 改动 3 driver expansion 决策树 / V6 表规则）
- `docs/反模式.md` AP-37（可变共享面深拷贝）/ AP-51（行数纪律）/ AP-54（回配按引用/ID 不靠下标）/ AP-53（V6 表）
- `docs/handover/2026-06-24-open-quote-perf-two-track-handover.md`（已落地 Phase 1+2 + 实测分项 + 被否方案）
- 记忆：[[cpq-expand-layer-not-threadsafe]] / [[cpq-firstsave-real-perf-measurement]] / [[cpq-savedraft-incremental-snapshot]] / [[cpq-sqlview-cache-key-needs-component-dim]] / [[cpq-decimal-display-policy]] / [[cpq-deliver-agents-overreport]] / [[cpq-worktree-maven-test-tree]]
- golden harness：`cpq-backend/src/test/java/com/cpq/quotation/service/GoldenCardValuesEquivTest.java` + `SaveDraftProfileTest.java`

---

## 8. 查询融合（F 系列）整合 + 当前 master 实测校准（2026-06-25 追加）

> 背景：另一条线（`docs/superpowers/plans/2026-06-25-savedraft-query-fusion-plan.md`）从「查询往返」视角把 saveDraft 逐行查询归成 F1–F11。经核对，**F9/F10/F11 与本设计 §2.3/§2.1 是同一批工作的两个命名**，F7 映射 §2.6。本节做整合 + 用当前 master 深剖实测校准 §1 的 two-track 旧分项，避免两条线分叉。

### 8.1 F 系列 ↔ E 系列 / Phase 映射（同一工作，勿重复造）

| F | 内容 | 对应本设计 | 状态 |
|---|---|---|---|
| **F1** | rowKeyFields 整单预取 | 本设计未列的**额外静态预取** | ✅ 已合 master `f79e45e`（2550→1，−14.7s，golden 等价） |
| **F4** | driver 组件清单整单预取 | 本设计未列的额外静态预取 | ✅ 已合 master `3a97256`（170→0，−0.9s，golden 等价） |
| F2/F5 | 模板 / Component 读 | B2 prefetch + Hibernate L1 缓存已覆盖（实测 Excel 静态读仅 42ms） | ✅ 无需改 |
| F6 | snapshot_rows 读 | §2.3 + 已落地 `loadSnapshotRowsByLines` | 部分（Phase 1） |
| **F7** | Quotation.findById per-line（写路径 N+1） | §2.6 延伸，见 §8.3 | Phase 2-4/2-5 |
| **F9** | expand 内存回配 | **§2.3 = E7（Phase 2-2 spineKeys）/ E8（Phase 2-3 lineItemId）/ E9（COMPOSITE 顺延）** | Phase 2-2/2-3 |
| **F10** | 落库批量 | **§2.1 = E1（Phase 2-1）** + 已落地 Phase 1 `writeSnapshotBatch`/`writeRowDataBatch` | Phase 2-1（部分已落） |
| F11 | 删建行批量 diff | §2.1 E1（方案 A 子表批量 DELETE ANY + 批量 INSERT） | Phase 2-1 |

→ **F9/F10/F11 由本设计 §2.3/§2.1 完整覆盖，不另立计划**；F1/F4 是已落地的额外静态预取（顺带减少 prefetch 阶段往返，与本设计正交不冲突）；F7 见 §8.3。

### 8.2 当前 master 实测校准（纠正 §1 stage③ 的 two-track 旧分项）

§1 stage③ 引用 two-track handover 的「baseRows 25s / assemble 33s / EM 15s / Excel 11s」是 **B1/B2 之前**的旧数。2026-06-25 在当前 master（含 B1/B2/F1/F4）对罗克韦尔 170 行逐行构建深剖实测：

| 块 | 旧数(two-track) | 当前 master 实测 | 说明 |
|---|---|---|---|
| rkf 加载 | （混在 assemble 33s 里） | 14.7s → **F1 已消** | 旧「assemble 33s」的大头其实是 rkf 静态查，不是公式 |
| driver 组件清单 | — | 0.9s → **F4 已消** | |
| **核价 driver expand** | baseRows 25s | **10.3s**（EXP_b，850 次，RTT 主导；视图执行 EXPLAIN 仅 0.17ms/条，850 条服务端循环 10ms） | = §2.3 **E7/E8 主战场** |
| 真公式求值 + 组装 | assemble 33s | **~0.5s**（CR_4 公式求值=101ms；B1 已 memoize） | **慢的从来不是公式/CPU** |
| EM | 15s | ~0（B2 已批量 0.19s） | |
| Excel | 11s | **1.4s**（per-line compData 查 1.0s 可融 + 列求值 CPU 0.3s + 静态 42ms） | 远小于旧数 |
| 落库 snapshotQuotation | — | 9.1s（写 snap 4.2 + 物化 row_data 4.4） | = §2.1 / Phase 1 |

**校准结论（喂给 §6-Q5 SLA 判断）**：
- **真 CPU 地板仅 ~0.8s**（B1 已合，assemble 不是 33s；Excel 实测 1.4s 不是 11s）→ §2.4「assemble 不是靶」结论被实测证实。
- **剩余墙钟 = §2.3 多 line expand（10.3s）+ §2.1 落库（9.1s）两块远程 I/O** → 即 **Phase 2-2/2-3（E7/E8）+ Phase 2-1（E1 落库批量）是决定项**。
- EXPLAIN 实证**视图 JOIN 近免费（0.17ms），地板 = RTT × 往返数**（实测空闲 RTT 1.6–4.5ms，负载 ~6ms）→ 集合化把往返压到几十次后 **DB 亚秒**。**§6-Q5 的「万行 ≤10s」乐观可达，5s 亦有望**——前提是 E8（lineItemId IN 回配）把 expand 压到一打次以内（这正是 Phase 2-3 的硬骨头）。

### 8.3 F7 细化（Quotation per-line，补 §2.6 的写路径同类）

§2.6 把 getById N+1 列为**读**路径次要靶。补一个**写**路径的同类 N+1：
- saveDraft 端点循环（`QuotationResource:160`）**非事务编排**，每行 `snapshotLineValuesWithUnion`（`@Transactional`）是**独立事务** → 内部 `Quotation.findById(managed.quotationId)`（`CardSnapshotService:425`）每行重查、L1 不跨事务复用（170× ≈ 0.85s）。
- **归入 Phase 2-4 接线**：把逐行循环改成集合化批处理后，Quotation 一次取、循环内内存复用即自然消除。
- ⚠️ **不能跨事务传 Quotation 实体**（detached 风险，现状 `managed=findById` 重载正是为此）；传不可变字段（templateId/customerId）或在集合化后整体单事务内处理。

### 8.5 ⚠️ Phase 2-2 实测再定向（2026-06-25，进入实现前的探针发现，**改变阶段优先级**）

进入 Phase 2-2（spineKeys 多 line 合桶 E7）实现前，对基准单做了两个只读探针，发现 **Phase 2-2 在现有数据上既不适用也无法验证**，需再定向：

1. **核价 spine 是「平的」**：`SpineTripleUniquenessProbeTest` 实测 8f0c37a4(170 行)/a8f17a74(77 行)——**spineNodes == lines（每行 spine 恰好 1 个节点 = 根料号本身，无 BOM 树深度）**；每 partNo 恰好 1 个 `(父件,版本)` 三元组（maxTriples=1）。→ ① partNo 并集回配对现有数据**逐位安全**；但 ② **golden 两单不含多节点 BOM 树 → golden 无法验证 E7 的三元组回配正确性**（设计 §2.3.2 担心的「同 partNo 多三元组」在测试数据里根本不出现）。
2. **核价模板无 `recursive AND spineKeys` 组件**：DB 实测 8f0c37a4 核价 5 driver 组件——COMP-0019(recursive=t, **spineKeys=f** → 已是 C4 union eligible `viewHasNoRowDimension=true`，已优化)；COMP-0020(spineKeys=t, **recursive=f** → 走 `expand(…,li.id,…)` 单值路径,非 E7 的 `expandForPartSet`+spine 路径)；COMP-0021/22/23(无任何行维度)。→ **E7 目标集（recursive BOM 树 + spineKeys 视图）= 空**。

**结论与再定向**：
- **Phase 2-2（E7）对现有数据是 dead code + 不可 golden 验证 → 暂缓**，待有真实/合成的「多节点 BOM 树 + recursive+spineKeys 组件」单据时再做（且届时必须按**三元组回配**实现 + 合成多三元组单测，partNo 捷径不可用于一般情形）。
- **真实核价 per-line expand 热点（earlier 实测 EXP_b 10.3s / 680 单值 expand）= 4 个非递归组件（COMP-0020/21/22/23）× 170 行**走 `expandTemplateDriverBaseRows` 的 `recursive=false` 分支（`expand(…,li.id,…)` 逐行）。其中 COMP-0021/22/23 **无行维度** → 可按 partNo 直接合桶（170→77，类 C4 但作用于非递归单值分支）；COMP-0020 含 spineKeys → 需行维度感知。**这是设计 §2.3 三分类之外的「非递归 driver 单值逐行」第 4 类**，是现有数据的真实大头。
- **建议新优先级**：把「非递归 driver 单值合桶（COMP-0021/22/23 类，无行维度，partNo 并集，可立即做、golden 可验、低风险）」提到 Phase 2-3 之前作为 **Phase 2-2'**；原 spineKeys E7 顺延到有 BOM 树数据。Phase 2-3（lineItemId/SIMPLE）仍是 composite/工序侧的最大杠杆。

#### 8.5.1 Phase 2-2' 已落地 + Phase 2-3 同样无基准目标（2026-06-26 探针续）

- **✅ Phase 2-2' 已合并 master `4d73c35`**（非递归无行维度组件 partNo 合桶；非递归 expand 680→170/308→77，golden 逐位等价）。
- **⚠️ Phase 2-3（lineItemId E8）经探针发现也无基准目标**：DB 实测两模板**全部 13 个 driver 组件 `lineitem=f`**（无 `:lineItemId`/`quotation_line_item_id`）——基准单是纯 SIMPLE（组合/选配数据 2026-06-11 已清理，见记忆 `cpq-e2e-quotation-flow-test-data`），而 lineItemId 维度视图（`v_composite_child_processes`）专为 COMPOSITE 产品。→ **E8 在基准单 dead code + golden 不可验，同 Phase 2-2 暂缓**。生产含 composite 产品时才需要，须先有 composite/multi-product 测试单（Q4 合成单）方能验证。
- **基准单经 F1/F4/2-2' 后的真实剩余热点（实测口径）**：
  - 报价侧 snapshotQuotation：组件全 `无行维度` → 已被 `precomputeQuoteDriverBuckets`（C1）合桶（SNAP_1 expand 仅 0.3s，无 per-line 兜底）。
  - 核价侧 expand：COMP-0019(C4)+COMP-0021/22/23(2-2') 已合桶；**仅剩 COMP-0020（spineKeys 非递归，170 次/~2s）逐行**——它 spine 是平的(maxTriples=1)，可安全做 flat-spine spineKeys 合桶（**Phase 2-2''**，带 maxTriples==1 闸门兜底）。
  - **落库 ~8.6s（writeSnapshotBatch 4.2 + materializeRowData 4.4，per-line）= 基准单最大剩余可验杠杆**（E1 落库批量深化，纯 SIMPLE 即可 golden 验）。
- **再定向结论**：基准单(纯 SIMPLE flat)只能验「无行维度合桶 + 落库批量」两类；spineKeys(E7)/lineItemId(E8)/composite(E9) 三类**都需 composite/BOM 树测试单才能验**。→ **建议下一步在基准单上做可验的最大杠杆：① 落库批量（~8.6s）；② COMP-0020 flat-spine 合桶（~2s）**；E8/E7 待 composite 合成单。

### 8.4 整合后的下一步（不变更 §5 阶段顺序，只补优先级理由）

§5 Phase 顺序不变（2-0 数据安全 ✅ 已合 → 2-1 per-row SQL ✅ 已合 → 2-2 spineKeys → 2-3 lineItemId → 2-4 接线 → 2-5 getById）。**§8.2 实测进一步确认**：消掉 rkf/driver(F1/F4 已合)后，**Phase 2-3（E8 lineItemId expand 回配）是逼近 5–10s 的单一最大杠杆**（核价 expand 10.3s 的主体），其次是 Phase 2-1 落库批量（9.1s）。assemble/Excel/CPU 均非靶（已 <1s）。

---

**状态**：Phase 1 架构设计完成；§6 已拍板；Phase 2-0 数据安全闸 + 2-1 per-row SQL 集合化已合并 master（`b0fc78c`）；查询融合 F1/F4 静态预取已合并（`f79e45e`/`3a97256`）。**下一步 = Phase 2-2（spineKeys E7）→ 2-3（lineItemId E8，最大杠杆 + 最高风险）**，subagent-driven + 每阶段 golden 卡口。
