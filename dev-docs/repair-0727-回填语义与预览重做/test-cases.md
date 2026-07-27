# repair-0727 · 测试用例明细（逐条细化，可执行）

> 编写人：cpq-tester ｜ 编写日期：2026-07-27 ｜ 状态：**设计阶段，未执行**
> 依据：`需求说明.md`（§1 事故复盘 + §4 验收标准 AC-R1~R8）／`backtask.md`（B1~B6）／`fronttask.md`（F1~F3）／`api.md`（接口契约）／`test.md`（骨架 T1~T8，本文档是其细化）
> 反向对照方法论：每条用例标注「本用例在 master（`bf3822a3`，本次改动前）上跑，预期 **失败** 还是 **通过**」。凡是标"应失败"的用例，我已用当前代码（`QuoteBackfillCollector`/`QuotePendingRewriter`/`QuoteBackfillPreviewService` 源码通读）逐条推演过实际会产出的错误数字，不是照抄需求文档的断言。
> **本阶段不新建/修改 `src/test/` 任何文件**——以下用例是设计说明，执行阶段的类名见文末「附录 C」。

---

## 0. 基线事实（写用例前必须对齐，避免和实现脱节）

以下是我通读现有源码 + 真库查询后确认的事实，用例设计基于这些事实，不是猜测：

| # | 事实 | 来源 |
|---|---|---|
| F1 | `material_bom_item` 组轴 = `(system_type, customer_no, material_no)`；`characteristic`（RECIPE/ASSEMBLY/OUTSOURCED）是**内容列**，不是轴列，同组可以有不同 `characteristic` 的行并存 | `QuoteTableAxis.MATERIAL_BOM_ITEM` |
| F2 | `element_bom_item` 组轴 = `(system_type, customer_no, material_no, material_part_no)`；`characteristic` 在这张表是**版本列**（不是特征标签！存的是版本号字符串如 `"2010"`），千万别把它当内容列去 patch | `QuoteTableAxis.ELEMENT_BOM_ITEM`，真库核对 `element_bom_item.characteristic ∈ {'2000'..'2011'}` |
| F3 | `wg_view` 真实模板：`WHERE mbi.characteristic='OUTSOURCED'`，暴露列仅 `_销售料号(material_no)/_组成件名称/_组成数量(composition_qty)/_组成单位(issue_unit)`——**不暴露** `scrap_rate/net_weight/rough_weight/seq_no/characteristic` | 真库 `component_sql_view WHERE sql_view_name='wg_view'` |
| F4 | `mc_view` 真实模板暴露 `_毛重 = ebi.composition_qty`，**不暴露 `base_qty`**——这正是真实事故 D2 复现的确切列 | 同上 `sql_view_name='mc_view'` |
| F5 | `bom_view` 真实模板是两分支 `UNION ALL`：分支 1 `FROM material_bom_item mbi`（白名单，边行）／分支 2 `FROM material_master mm ... NOT EXISTS(...)`（非白名单，根行）——现状 `hasTopLevelSetOp=true` 直接让整个视图 `anchorInjected=false` | 同上 `sql_view_name='bom_view'`；`QuotePendingRewriter.java:268-279` |
| F6 | `QuotePendingRewriterTest.unionAll_safeDegrade_noAnchor_butTablesStillTouched`（真实 `qt_view` 形态，两分支都是 `unit_price`）**现状断言 `anchorInjected=false`**——B1 修复后这条断言必须翻转为 `true`，是本次改动**必然导致的既有测试破坏**，不是意外回归 | 现读 `QuotePendingRewriterTest.java:141-156` |
| F7 | `SqlViewExecutor` 调 `QuotePendingRewriter.rewrite` 前有门禁：`if (owner.quotationId == null \|\| owner.isQuotationFrozen()) return sqlTemplate;`——核价侧（PRICING）渲染从不设置 `quotationId`，因此 B1 改动**不会**触达 PRICING 侧任何调用路径，AC-17 风险等级下调，但仍需回归测试兜底 | `SqlViewExecutor.java:554-557` |
| F8 | `QuoteBackfillMasterChildAcceptanceTest` 现有两个 ADD 测试（`flatManualAdd_.../treeManualLeaf_...`）**都是"从零建组"**（基底为空），这类场景下 patch 语义与旧语义效果完全相同，是"侥幸通过"，不构成对 D1 的真实回归验证 | 现读该测试文件 |
| F9 | 真实事故组 `material_bom_item (QUOTE, CUST-0001, S-3120014539)`：4 行 `characteristic ∈ {RECIPE, RECIPE, ASSEMBLY, OUTSOURCED}`，`seq_no ∈ {1,2,3,5}`；`element_bom_item` 对应 `material_part_no=00137` 的 `Cu` 元素行 `base_qty=0.624610` | 真库查询（cpq_db_0724，只读） |
| F10 | `uq_material_bom_item` = `(system_type, customer_no, material_no, COALESCE(characteristic,''), COALESCE(bom_version,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))` | 真库 `pg_indexes` |
| F11 | Q1（`__manual` vs `_origin:'manual'` 双标记）/Q3（两阶段读取）/Q4（previewToken 幂等）/Q6（新料号补 stub）在 task-0721 test.md §6 列为待裁决，但**已被现有测试验证解决**（`QuoteBackfillMasterChildAcceptanceTest`/`QuoteBackfillFlatAcceptanceTest`）；Q2（两任务合并顺序）已因树任务先行合并（`bf3822a3`）而解除阻塞；**只有 Q5（有/无 snapshot 表征边界）被本次 B3.3 正式给出答案**（基底行被墓碑删空→OFFLINE） | 见 §7 回归矩阵 |

---

## 1. 新增测试基础设施（供执行阶段对齐，不在本阶段实现）

现有 `QuoteBackfillFlatAcceptanceTest`/`QuoteBackfillMasterChildAcceptanceTest` 已提供 `newQuotation`/`newLineItem`/`financeUserId`/`writeComponentData`/`insertUnitPrice` 等 helper。本次需要补充：

| 新 helper | 用途 | 参照 |
|---|---|---|
| `insertMaterialBomItem(customerNo, materialNo, characteristic, seqNo, componentNo, compositionQty, netWeight, roughWeight, weightUnit, scrapRate, issueUnit, versionNo, isCurrent, pendingQid, supersedes)` | 造 F9 型 4 行组基底数据 | 镜像 `insertUnitPrice` |
| `insertElementBomItem(customerNo, materialNo, materialPartNo, componentNo, baseQty, compositionQty, scrapRate, seqNo, issueUnit, characteristicAsVersion, isCurrent, pendingQid, supersedes)` | 造 D2 场景（注意 `characteristic` 是版本列，见 F2） | 同上 |
| `newFlatOutsourcedComponent(suffix)` | 建 `wg_view` 形态组件（`WHERE characteristic='OUTSOURCED'`，只暴露 F3 列） | 镜像 `newFlatUnitPriceComponent` |
| `newFlatElementComponent(suffix)` | 建 `mc_view` 形态组件（不暴露 `base_qty`，见 F4） | 同上 |
| `newTreeBomViewComponent(suffix)` | 建 `bom_view` 形态组件（两分支 UNION ALL，`tabType=BOM`） | 同上 |
| `currentMaterialBomItems(customerNo, materialNo)` | 查一组全部 `is_current=true` 行（`SELECT *`，非仅 3 列） | 扩展现有 `currentBomItems`（现只查 4 列，本次断言需要全部 content 列） |
| `pendingMaterialBomItems(quotationId, materialNo)` | 查本单 pending 基底行（用于"逐字段与基底相等"断言） | 新增 |

---

## 2. AC-R1 · 投影页签不丢行（4 条，后端单测）

### BE-R1-01 · D1 核心复现（纯投影，无改值）

- **前置数据构造**：`material_bom_item` 组 `(QUOTE, <TAG>CUST, <TAG>ROOT)`，本单 pending 4 行（镜像 F9 真实结构）：
  | seq_no | component_no | characteristic | composition_qty | scrap_rate | issue_unit |
  |---|---|---|---|---|---|
  | 1 | 00137X | RECIPE | NULL | NULL | NULL |
  | 2 | 992X | RECIPE | NULL | NULL | NULL |
  | 3 | S-80011X | ASSEMBLY | 1.000000 | NULL | NULL |
  | 5 | W-1001X | OUTSOURCED | 2.000000 | NULL | EA |

  另建 4 行对应 official 行（`version_no=v_old`, `is_current=true`），pending 行 `pending_supersedes` 指回。组件：`newFlatOutsourcedComponent`（`wg_view` 形态，`WHERE characteristic='OUTSOURCED'`），该产品行的 `quotation_line_component_data.snapshot_rows` 只含 1 行（`driverRow.__v6_id` = W-1001X 的 pending 行 id），无改值。
- **执行动作**：`backfillService.execute(quotationId, finance)`
- **断言**：
  - `SELECT count(*) FROM material_bom_item WHERE customer_no='<TAG>CUST' AND material_no='<TAG>ROOT' AND is_current=true` = **4**
  - 00137X/992X/S-80011X 三行（未被任何 tab 表征）逐列（`seq_no, component_no, characteristic, composition_qty, scrap_rate, issue_unit, net_weight, rough_weight`）与各自 pending 基底行**完全相等**
  - W-1001X 行 `composition_qty=2.000000`（无 diff，值不变）
  - 4 条旧 official 行 `is_current=false`（`SELECT count(*)...WHERE version_no='<v_old>' AND is_current=false` = 4）
- **反向对照**：**master 上失败**——`QuoteBackfillCollector` 现状只把 1 个 CHANGE candidate（W-1001X）塞进 `effectiveNewRows`，`route=REBUILD` 用这 1 行重建整组，`is_current` 行数会变成 **1**，与真实事故（v2010 只剩 1 行空壳）完全同构。
- **归属**：后端单测（`@QuarkusTest @TestTransaction`）

### BE-R1-02 · 叠加真实改值，验证 patch 只影响命中行

- **前置数据构造**：同 BE-R1-01，另外 `row_data` 把 W-1001X 的 `composition_qty` 从 `2` 改成 `3`。
- **执行动作**：同上
- **断言**：
  - `is_current` 行数 = 4
  - W-1001X `composition_qty = 3.000000`
  - 00137X/992X/S-80011X 三行逐列与基底相等（同 BE-R1-01）
  - `QuoteBackfillService.Summary.changedRows = 1`
- **反向对照**：**master 上失败**（行数坍缩到 1，且即便"改值"本身算对，其余 3 行连带丢失）
- **归属**：后端单测

### BE-R1-03 · 显式墓碑对照（"未出现≠删除" vs "墓碑命中=删除"）

- **前置数据构造**：同 BE-R1-01，另外该 `wg_view` 页签对 W-1001X 下达删除（`deleted_row_keys` 命中其 `fp`，按 `DeletedRowKeys.rowFingerprint` 同口径构造）。
- **执行动作**：同上
- **断言**：
  - `is_current` 行数 = **3**（00137X/992X/S-80011X），W-1001X 不在其中
  - W-1001X 的旧 official 行 `is_current=false` 留存（`SELECT count(*) WHERE component_no='W-1001X' AND is_current=false` = 1，可审计）
  - 00137X/992X/S-80011X 三行内容与基底逐字段相等
- **反向对照**：**master 上失败**——注意不是"master 也会剩 3 行"这种巧合：master 下该表唯一的 candidate 是 1 条 `DELETE`（`op="DELETE"` 不进 `effectiveNewRows`），`effectiveNewRows.isEmpty()` 为真 → `route=OFFLINE` → **整组** `is_current` 行数变 **0**（不是 3）。这是本用例设计上最容易被误判"两种语义结果碰巧一致"的陷阱，必须点破。
- **归属**：后端单测

### BE-R1-04 · 多 tab 各表征组内不同子集，patch 合并覆盖全集

- **前置数据构造**：同 BE-R1-01 的 4 行基底。该产品配 2 个组件页签：
  - Tab A = `newFlatOutsourcedComponent`（`wg_view`，`characteristic='OUTSOURCED'`），渲染 W-1001X，`row_data` 把 `composition_qty` 从 2 改成 2.5
  - Tab B = 新建 `newFlatAssemblyComponent`（同构但 `WHERE characteristic='ASSEMBLY'`），渲染 S-80011X，**未改值**
- **执行动作**：同上
- **断言**：
  - `is_current` 行数 = 4
  - W-1001X `composition_qty = 2.500000`（Tab A 改值生效）
  - S-80011X `composition_qty = 1.000000`（与基底一致，Tab B 无 diff）
  - 00137X/992X（任何 tab 都未表征）逐列与基底相等
  - `Summary.changedRows = 1`（只有 W-1001X 真有 diff）
- **反向对照**：**master 上失败**——master 会把两个 tab 各自的 candidate（W-1001X 改值 + S-80011X 无 diff）合成 2 行 `effectiveNewRows`，`REBUILD` 只写 2 行，`is_current` 变 2，丢 00137X 和 992X。
- **归属**：后端单测

---

## 3. AC-R2 · 未暴露列不丢值（3 条，后端单测）

### BE-R2-01 · D2 核心复现（`element_bom_item.base_qty`）

- **前置数据构造**：`element_bom_item` 组 `(QUOTE, <TAG>CUST, <TAG>ROOT, 00137X)`：1 行 official（`characteristic='2000'`【版本列，见 F2】, `is_current=true`）+ 1 行本单 pending（`characteristic='2001'`）：`component_no=Cu, base_qty=0.624610, composition_qty=NULL, scrap_rate=1.0500, seq_no=1, issue_unit='g/PCS'`。组件：`newFlatElementComponent`（`mc_view` 形态，暴露 `_损耗率(scrap_rate)/_项次(seq_no)/_毛重(composition_qty)/_毛用量单位(issue_unit)/_元素(component_no)`，**不暴露 `base_qty`**）。`row_data` 把 `_损耗率` 从 `1.05` 改成 `1.20`。
- **执行动作**：`backfillService.execute`
- **断言**：`SELECT base_qty, scrap_rate, seq_no, issue_unit, component_no FROM element_bom_item WHERE customer_no='<TAG>CUST' AND material_no='<TAG>ROOT' AND material_part_no='00137X' AND is_current=true`：
  - `base_qty = 0.624610`（与基底逐字节相等，**非 NULL**）
  - `scrap_rate = 1.2000`（暴露列，新值生效）
  - `seq_no = 1, issue_unit = 'g/PCS', component_no = 'Cu'`（暴露但未改，取基底值）
- **反向对照**：**master 上失败**——`mapColumns` 只按 `colToBase`（不含 `base_qty`）填充 `content`，`effectiveNewRows` 里没有 `base_qty` 键；`VersionedV6Writer` 对未提供的列写 NULL，新行 `base_qty=NULL`，与真实事故（v2010 `base_qty` 从 0.624610 变 NULL）逐位吻合。
- **归属**：后端单测

### BE-R2-02 · 纯列级验证（不掺行丢失，隔离变量）

- **前置数据构造**：`material_bom_item` **单行**组（避免行丢失掩盖列断言）：`net_weight=12.5, rough_weight=15.0, weight_unit='kg'` 均为本单 pending（`wg_view` 不暴露这三列）。`wg_view` 页签渲染出该行，**无任何改动**。
- **断言**：新 `is_current` 行 `net_weight=12.5000, rough_weight=15.0000, weight_unit='kg'`（与基底一致，非 NULL）
- **反向对照**：**master 上失败**（`colToBase` 不含这三列 → 写 NULL；本例组内只有 1 行，行数不会坍缩，纯粹隔离出"列丢失"这一个变量）
- **归属**：后端单测

### BE-R2-03 · 暴露列 vs 未暴露列混合改值（回归护栏）

- **前置数据构造**：同 BE-R2-01，另外 `_项次(seq_no，暴露列)` 保持 `row_data` 与 `driverRow` 一致（不产生 diff）。
- **断言**：`seq_no` 仍 = 1（暴露但未改），`scrap_rate = 1.2000`（暴露且改），`base_qty = 0.624610`（未暴露，取基底）——三种列的处理路径在同一断言里区分清楚，不能混淆。
- **反向对照**：**master 上失败**（`base_qty` 断言点同 BE-R2-01）
- **归属**：后端单测

---

## 4. AC-R3 · 树锚点 + 传导（10 条：6 条纯函数单测 + 4 条真库端到端）

### BE-R3-01 · 真实 `bom_view` 二分支锚点注入

- **前置数据构造**：用真库取得的 `bom_view` 确切 SQL 文本（F5，纯字符串常量，不依赖真实 component）作为 `QuotePendingRewriter.rewrite` 输入，风格对齐 `QuotePendingRewriterTest`。
- **执行动作**：`QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(BOM_VIEW_SQL, conn);`
- **断言**：
  - `r.anchorInjected = true`
  - 边行分支（`FROM material_bom_item mbi`）SELECT 列表含 `mbi.id AS __v6_id`
  - 根行分支（`FROM material_master mm ... NOT EXISTS(...)`）SELECT 列表含 `NULL::uuid AS __v6_id`（占位对齐）
  - `r.primaryTable = "material_bom_item"`
  - `LIMIT 0` 探测执行不抛 `each UNION query must have the same number of columns`
- **反向对照**：**master 上失败**——`hasTopLevelSetOp=true` 时现状直接放弃主位探测，`anchorInjected` 恒为 `false`（对应 F6）。
- **归属**：后端单测（纯函数，无需 `@TestTransaction`）

### BE-R3-02 · 三分支全白名单，`primaryTable` 取第一分支

- **前置数据构造**：合成 SQL：`FROM unit_price up1 ... UNION ALL FROM unit_price up2 ... UNION ALL FROM capacity cp ...`（三分支列对齐）。
- **断言**：`anchorInjected=true`；`primaryTable="unit_price"`；分支 1/2 分别注入 `up1.id AS __v6_id`/`up2.id AS __v6_id`；分支 3（本身也白名单）注入 `cp.id AS __v6_id`；`LIMIT 0` 可执行。
- **反向对照**：**master 上失败**（恒 `false`）
- **归属**：后端单测

### BE-R3-03 · 分支裸表名（无别名）

- **前置数据构造**：`SELECT ... FROM unit_price WHERE ... UNION ALL SELECT ... FROM material_master WHERE ...`（分支 1 `FROM unit_price` 不带别名）。
- **断言**：分支 1 注入 `unit_price.id AS __v6_id`（默认别名=表名，复用非 set-op 既有规则）；分支 2（`material_master` 非白名单）注入 `NULL::uuid AS __v6_id`。
- **反向对照**：**master 上失败**
- **归属**：后端单测

### BE-R3-04 · 分支内含 `GROUP BY`（该分支降级不阻断整视图）

- **前置数据构造**：分支 1 `FROM material_bom_item mbi ...`（可回填）`UNION ALL` 分支 2 `FROM material_bom_item mbi2 ... GROUP BY mbi2.material_no`（聚合分支）。
- **断言**：分支 1 正常注入 `mbi.id AS __v6_id`；分支 2 注入 `NULL::uuid AS __v6_id`（按 backtask B1 规则 2："分支内含 GROUP BY→注入 NULL::uuid"）；`LIMIT 0` 可执行，不报 `column must appear in GROUP BY`。
- **反向对照**：**master 上失败**
- **归属**：后端单测

### BE-R3-05 · 非 set-op 视图改写结果逐字节不变（回归护栏）

- **前置数据构造**：复用 `QuotePendingRewriterTest` 现有 `PF_VIEW`/`Z2_VIEW`/`ZH_VIEW` 三个非 set-op fixture。
- **执行动作**：直接复跑现有三个测试方法（`pfView_singleTable_anchorInjected_baseTableTracked`/`z2View_bareBooleanIsCurrent_multiJoin_anchorInjected`/`zhView_capacityTable_anchorInjected`）。
- **断言**：三方法全部保持 PASS，`primaryTable/primaryAlias/touchedTables/anchorInjected` 与改动前完全相同。
- **反向对照**：**master 上通过**（纯回归护栏，B1 不应影响非 set-op 路径，这条不是新缺陷验证）
- **归属**：后端单测（复跑既有测试）

### BE-R3-06 · `QuoteBackfillColumnMapper` 对 `bom_view` 的列映射

- **前置数据构造**：真实 `bom_view` SQL（F5）。
- **执行动作**：`QuoteBackfillColumnMapper.resolve(BOM_VIEW_SQL, conn)`
- **断言**：
  - `resolved.backfillable = true`
  - `resolved.primaryTable = "material_bom_item"`
  - `colToBase` 含：`_组成数量→(material_bom_item, composition_qty)`、`_净重→(material_bom_item, net_weight)`、`_损耗率→(material_bom_item, scrap_rate)`、`_毛重→(material_bom_item, rough_weight)`、`_用量单位→(material_bom_item, weight_unit)`、`_组成单位→(material_bom_item, issue_unit)`
  - `colToBase` **不含** `_料件名称`（`COALESCE(mm.material_name, mr.name)` 计算列，来自非白名单表，正确被过滤）
- **反向对照**：**master 上失败**——`resolveUncached` 里 `if (!rw.anchorInjected) return NOT_BACKFILLABLE;`，因 `rewrite` 本身 `anchorInjected=false`（B1 修复前），直接返回空映射，`backfillable=false`。
- **归属**：后端单测

### BE-R3-07 · 真库端到端：树页签 `snapshot_rows` 携带锚点

- **前置数据构造**：`newTreeBomViewComponent`（`tabType=BOM`，真实 `bom_view` 形态，隔离 `<TAG>CUST`/`<TAG>ROOT`）；`material_bom_item` 组 4 行（同 BE-R1-01 结构）全部本单 pending；触发该产品真实物化/渲染入口（走 `QuotationService` 建单/刷新，非 mock）。
- **断言**：该组件 `snapshot_rows` 数组中，4 行（RECIPE×2/ASSEMBLY/OUTSOURCED）对应 `driverRow` 均含**非空** `__v6_id`；若另构造一个"该料号无 BOM 分解、走 `bom_view` 第二分支根行"的场景，其 `driverRow.__v6_id` 应为 `null`（根行分支占位，符合设计）。
- **反向对照**：**master 上失败**（`__v6_id` 全部为 null，等同"从不存在"，回填 Phase 判定 `hasV6Id=false` 全部落入 ADD 分支，产生新增行而非 CHANGE，比 D1 更严重）
- **归属**：真库端到端

### BE-R3-08 · 删行传导

- **前置数据构造**：承接 BE-R3-07 的 4 行 fixture，树页签对 992X（RECIPE 行）下达删除（`deleted_tree_nodes` 命中其 `__nodeId`，格式 `<TAG>ROOT/992X`，或 `deleted_row_keys` 命中其 fp）。
- **执行动作**：`backfillService.execute`
- **断言**：
  - `is_current` 行数 = 3（00137X/S-80011X/W-1001X 保留）
  - 992X 旧 official 行 `is_current=false` 留存
  - 其余 3 行内容与基底逐字段一致
- **反向对照**：**master 上失败**（锚点缺失下树页签根本采不到有效 candidate，不是"剩 3 行错成 1 行"这种量级问题，而是整条链路失效——要么全组走 FLIP 原样转正不应用删除，要么锚点全 null 被误判 ADD 产生重复插入，两者都得不到"3 行且 992X 缺失"的正确结果）
- **归属**：真库端到端

### BE-R3-09 · 改值传导

- **前置数据构造**：同 BE-R3-07，树页签把 S-80011X 的「组成数量」从 `1.000000` 改成 `1.500000`。
- **断言**：`is_current=4`；S-80011X `composition_qty=1.500000`；其余 3 行不变。
- **反向对照**：**master 上失败**（同 BE-R3-08 原因）
- **归属**：真库端到端

### BE-R3-10 · spine 不回归（护栏）

- **前置数据构造**：`BomTreeRenderService` 递归 CTE spine SQL（`injectAnchor=false` 显式调用点，`BomTreeRenderService.java:384`）。
- **断言**：`QuotePendingRewriter.rewrite(expandedSpineSql, conn, false)` 返回 `anchorInjected=false`；SQL 结构（`root_no/material_no/bom_version/parent_no/node_path` 5 列顺序）与 B1 改动前逐字节一致；用已知 fixture 跑一次树渲染，行数/节点结构不变。
- **反向对照**：**master 上通过**（B1 明确不动 `injectAnchor=false` 路径，本用例验证没有误伤）
- **归属**：真库端到端

---

## 5. AC-R4 · 预览 ≡ 执行（★最高优先级，5 条后端单测 + 2 条真库端到端）

### BE-R4-01 ·【黄金用例】D1 场景下预览必须如实，不能"0 变更"却删数据

- **前置数据构造**：复用 BE-R1-01 fixture（4 行组，`wg_view` 只表征 1 行 OUTSOURCED，**无任何用户改值**）。
- **执行动作**：
  1. `BackfillPreviewDTO preview = previewService.preview(quotationId)`
  2. `backfillService.execute(quotationId, finance)`
- **断言**（先 preview 后 execute，两阶段比对）：
  - `preview.summary.changedRows == 0`，`deletedRows == 0`，`addedRows == 0`（该组唯一被表征的 W-1001X 内容与基底一致，无 diff）
  - `execute` 后 `SELECT * FROM material_bom_item WHERE ... is_current=true` 与执行前 4 行 pending 基底逐字段（除 `id/created_at/updated_at/is_current/pending_*` 系统列外）**完全相等**——即预览说"0 变更"，执行后真的 0 变更
- **反向对照**：**master 上失败**（这是全文档"必须在 master 失败"的黄金用例，直接对应事故复盘）——两个维度同时失败：① preview 阶段，W-1001X 无 diff → `rd.changes.isEmpty()→continue`，该组 `gd.rows` 为空且 `route` 恒为 `REBUILD`（当前实现无 FLIP 判定分支覆盖这种情况）→ `if (!gd.rows.isEmpty() || g.route != REBUILD)` 为 false → **整组不出现在 `dto.groups` 里**，即 UI 上看到"0 变更"；② 但 `execute` 阶段 `effectiveNewRows` 只有 W-1001X 一行，REBUILD 后 `is_current` 从 4 坍缩到 1——**预览说 0 变更，执行删了 3 行，逐字复刻真实事故**。
- **归属**：后端单测

### BE-R4-02 · 预览"改 N 行删 M 行"与执行精确对齐

- **前置数据构造**：同 BE-R1-01 4 行组，`wg_view` 页签把 W-1001X 的 `composition_qty` 从 2 改成 2.5；另建 `newFlatAssemblyComponent` 对 S-80011X 下达删除。
- **执行动作**：preview → execute
- **断言**：
  - `preview.summary.changedRows=1, deletedRows=1, addedRows=0`
  - `preview.groups[该组].rows` 恰好 2 条：1 条 CHANGE（W-1001X，`composition_qty: "2"→"2.5"`），1 条 DELETE（S-80011X，`values` 列出其原值）
  - `execute` 后 `is_current=3`（00137X/992X/W-1001X）；S-80011X 不在其中；W-1001X `composition_qty=2.500000`
  - 与 preview 数字逐一核对：实际减少行数=1（预览 deletedRows=1）、实际值变化行数=1（预览 changedRows=1）
- **反向对照**：**master 上失败**——预览层面 changedRows=1/deletedRows=1"看起来对"，但这是假象：`execute` 阶段 `effectiveNewRows` 只含 W-1001X 一行（S-80011X 是 DELETE 不进 newRows），REBUILD 后 `is_current=1`，而非 3——从未被任何 tab 提及的 00137X/992X 也一并消失，预览暗示的"其余 2 行应保留"与实际执行不符。
- **归属**：后端单测

### BE-R4-03 · previewToken 幂等性不受 patch 语义影响（护栏）

- **前置数据构造**：沿用 BE-R4-01 fixture（0-diff 场景）。
- **执行动作**：连续调用 `previewService.preview(quotationId)` 两次。
- **断言**：两次 `previewToken` 相同。
- **反向对照**：**master 上通过**（幂等性机制本身不受本次改动影响，纯护栏）
- **归属**：后端单测

### BE-R4-04 · TOCTOU 409 机制护栏

- **前置数据构造**：material_bom_item 多行组场景，参照现有 `QuoteBackfillFlatAcceptanceTest.previewToken_idempotentWhenUnchanged_and409OnDrift` 模式改造。
- **断言**：预览与提交之间数据漂移，旧 token 提交返回 409；重新预览拿新 token 后提交成功。
- **反向对照**：**master 上通过**（护栏，验证 patch 语义改动没有破坏既有 409 机制）
- **归属**：后端单测

### BE-R4-05 ·「REBUILD 但零净 diff」场景的可观测性（待实现方式确认，见文末未决问题）

- **前置数据构造**：一个"从已有产品添加"的 line item，纯只读引用一个已存在 `is_current` 组（无任何 pending），tab 渲染出该组唯一 1 行且完全未改（`baseSource=CURRENT`）。
- **断言**：backtask B4 明确"若某组 `route=REBUILD` 且 `rowChanges` 为空，记 ERROR 日志（patch 语义下该状态不应出现）"——本用例应验证该日志确实产生，**或**（若实现选择自动降级）该组 `route` 记录为 `FLIP`。两种实现路径任选其一，测试按实现阶段确认的方案二选一断言。
- **反向对照**：不适用（master 无 `baseSource` 概念，无法比较；这是 patch 语义下的新边界）
- **归属**：后端单测

### E2E-R4-01 · 真库端到端：完整复刻 QT-20260726-0016 事故结构

- **前置数据构造**（隔离 TAG，不碰真实脏数据）：
  - `material_bom_item` 组：4 行（RECIPE×2/ASSEMBLY×1/OUTSOURCED×1），pending
  - `element_bom_item` 组：`material_part_no=00137X` 的 `Cu` 元素行，`base_qty=0.624610, scrap_rate=1.05`，pending
  - 该产品挂 3 个 tab：BOM 组成树（`bom_view` 形态，全表征）、外购件成本（`wg_view`，仅 OUTSOURCED）、材料成本（`mc_view`，仅暴露部分列）
  - 用户在 `wg_view` 改 W-1001X 数量，在 `mc_view` 改 Cu 损耗率
- **执行动作**：`GET preview` → `POST costing-approve`（带 token）
- **断言**（逐字段 DB 快照前后对比）：
  - `material_bom_item is_current=4`，仅 W-1001X `composition_qty` 变化，其余 3 行逐字段与 pending 基底相等
  - `element_bom_item is_current=1`（该轴），`base_qty=0.624610`（保留），`scrap_rate`=新值
  - preview 的 `summary` 与 execute 后实际 diff 逐数字相等
  - `affectedProducts=1`（若 F1/api.md 的 `products` 聚合字段已实现）
- **反向对照**：**master 上失败**——本条是"逐字段复刻事故"的终极验收用例，master 下会重演事故（4 行坍缩为 1 行、`base_qty` 变 NULL）。
- **归属**：真库端到端

### E2E-R4-02 · 预览"0 变更"→执行后真的 0 变更（诚实标注：本用例在特定条件下 master 也可能通过）

- **前置数据构造**：SUBMITTED 状态报价单，产品的 `material_bom_item` 组为纯 pending（导入未编辑），**唯一 1 个 tab（BOM 树，`bom_view` 形态）完整表征全部 4 行**，用户未做任何手工编辑。
- **执行动作**：preview → execute
- **断言**：`preview.summary` 全 0；`execute` 后 DB 快照与执行前逐字节相同（除系统语义列外）。
- **反向对照**：**master 上通过**——刻意标注：这是因为本用例的 tab **恰好完整表征了整组**（4/4），master 的"tab 行集=全部"假设在这种特殊情况下不会暴露 bug。**此用例的价值是划出 bug 触发边界**：只有当 tab 是组的真子集时 master 才会出错（对照 E2E-R4-01 是真子集场景）。审核时不能把这条当作"master 也能过、所以不算修复验证"的反例——它是有意设计的边界对照，用来防止实现阶段用"tab 完整覆盖"的简单 fixture 自我欺骗性验收。
- **归属**：真库端到端

---

## 6. AC-R5 · 预览可读性（6 条，前端 UI 手测）

### UI-R5-01 · 产品卡片聚合展示

- **步骤**：用 E2E-R4-01 同构的报价单，财务点击「核价通过」
- **预期**：抽屉顶部摘要"影响 1 个产品·改值 2 行"；卡片头"S-3120014539... 客户 XXX"（测试用 TAG 替换）；卡内按 `categoryLabel` 分节"BOM 组成"/"材质元素构成"，分别列出改动行
- **反向对照**：不适用（api.md 明确 `changes`/`values` 结构是破坏性变更，master 上前端类型不兼容，无法直接对照运行）
- **归属**：前端 UI

### UI-R5-02 · 行级中文展示

- **预期**：CHANGE 行显示"组成数量：1→2"（旧值删除线、新值加粗）；ADD 绿色；DELETE 红色删除线
- **归属**：前端 UI

### UI-R5-03 · `plating_scheme` 全局共享分区

- **前置**：报价单同时命中一个 `plating_scheme` 改动
- **预期**：单独"全局共享变更"分区 + 红色 Tag「全局共享，影响所有客户」，且不出现在任何产品卡片内
- **归属**：前端 UI

### UI-R5-04 · 0 变更场景

- **前置**：复用 E2E-R4-02（纯完整表征、无 diff）
- **预期**：显示既有 Alert"本次通过无基础数据变更，仅完成审核状态流转"
- **归属**：前端 UI

### UI-R5-05 · 冲突标注

- **前置**：构造 BND-02（多页签同列冲突）场景
- **预期**：冲突行显示橙色 Tag「多页签冲突，取先到值」
- **归属**：前端 UI

### UI-R5-06 · 三态截图存档汇总

- **要求**：有变更 / 无变更 / 含全局共享 三态各存档一张，作为 PR 附件（对齐 fronttask F3 自检项）
- **归属**：前端 UI

---

## 7. AC-R6 · 闭包跨组精确（3 条，真库端到端）

### BE-R6-01 · 闭包页签跨组各表征 1 行

- **前置数据构造**：两个 V6 组：组 A `(<TAG>CUST, <TAG>AROOT)` 2 行；组 B `(<TAG>CUST, <TAG>BROOT)` 2 行。闭包页签（`bom_closure` 机制，task-0726 落地）同时展示 A 组 1 行 + B 组 1 行。用户改 A 组那行的值，B 组那行不改。
- **执行动作**：`backfillService.execute`
- **断言**：
  - A 组 `is_current=2`（与基底行数相同），改动行值更新，另 1 行保留
  - B 组 `is_current=2`，全部行内容与基底一致（未改）
  - A 组的改动不出现在 B 组任何行（交叉污染检测：B 组行全部 content 列值都应与 B 组独立基底相等）
- **反向对照**：**master 上失败**——A、B 两组各自都会被闭包页签的单行子集 REBUILD 坍缩（A 组 `is_current` 2→1，B 组 2→1）。这是 D1 的"跨组放大版"，需求说明 §1.3 明确点出"D1 的放大器"就是这个闭包场景。注意：master 不会发生"跨组串值"（因为 axis 分组按查出的真实 `material_no` 走，本就正确隔离），bug 表现是"两组各自丢行"而非"两组互相污染"，措辞要准确。
- **归属**：真库端到端

### BE-R6-02 · 多个闭包页签共同表征同一组，不重复/不遗漏

- **前置数据构造**：组 A 3 行，两个不同 tab（`jg_view` 形态闭包 1 + `wg_view` 形态闭包 2）各自表征 A 组内不同的 1 行（互不重叠）。
- **断言**：`is_current=3`（不多不少），两个 tab 各自 patch 的行生效，第 3 行（两个 tab 都未表征）保留基底值。
- **反向对照**：**master 上失败**（两 tab 的 candidate 合并成 2 行子集，REBUILD → `is_current=2`，丢第 3 行）
- **归属**：真库端到端

### BE-R6-03 · 闭包页签墓碑跨组精确删除

- **前置数据构造**：同 BE-R6-01，另外用户通过闭包页签把 B 组那 1 行标记删除。
- **断言**：B 组 `is_current` 减 1（基底 2 行→剩 1 行）；A 组不受影响（`is_current` 仍等于 A 组基底数，行不受牵连）。
- **反向对照**：**master 上失败**——该 tab 对 B 组只有 1 条 candidate 且是 DELETE，不进 `effectiveNewRows`，B 组 `effectiveNewRows=[]` → `route=OFFLINE`，B 组 `is_current` 从 2 变 **0**（不是期望的 1）。
- **归属**：真库端到端

---

## 8. AC-R7 · 既有语义零回归（回归矩阵，非新用例，是复跑指引）

逐条核对 task-0721 `test.md` 的 AC-1~AC-18，标注处理方式：

| AC | 原用例 | 是否受 patch 语义影响 | 处理方式 | 备注 |
|---|---|---|---|---|
| AC-1 延迟生效 | UT-B2-1/2/4 | 否（B2 导入阶段不涉及） | 原样复跑 | |
| AC-2 本单可见 | UT-B3-1/UT-B4-1 | 否（非 set-op 场景逐字节不变） | 原样复跑 | set-op 场景新增覆盖见 BE-R3-07 |
| AC-3 他单隔离 | UT-B3-2/3/9 | 否 | 原样复跑 | |
| AC-4 闸门 | UT-B7-1~5 | 否（闸门逻辑在 `ExistingProductService`，不在 collector） | 原样复跑 | |
| AC-5 回填-改值 | UT-B5-CHG-1/2 | 部分 | 原样复跑 + **建议补充断言**"组内其余未提及行仍保留" | 原断言没覆盖这点，见 §9 骨架缺口 |
| AC-6 回填-新增 | UT-B5-ADD-1/2/3 | 否（合成逻辑不受影响） | 原样复跑 | |
| AC-7 回填-删除 | UT-B5-DEL-1/2 | **是** | 需要 BE-R1-03 补强（"tab 只是真子集+墓碑删除+组内还有未表征第三行"），原有 fixture 是"tab=全集"的侥幸场景 | 见 §9 骨架缺口 |
| AC-8 一致性总纲 | UT-B5-CONSIST-1 | **是（关键）** | **必须重新设计 fixture**，含"组内未表征行 + 未暴露列"，否则该用例在 master 和修复后代码上都会通过，是假阳性 | 见 §9 骨架缺口，本项优先级最高 |
| AC-9 有历史 | UT-B5-HIST-1/2 | 否 | 原样复跑 | |
| AC-10 已建单不受影响 | UT-FREEZE-1/2 | 否 | 原样复跑 | |
| AC-11 预览 | UT-B6-1/2/3/5/6/IDEMPOTENT | UT-B6-5 **是** | UT-B6-5（空影响）需用"组含未表征行"fixture 重验，防假阳性；其余原样复跑 | |
| AC-12 预览不可挑选 | UT-B6-4 | 否 | 原样复跑 | |
| AC-13 状态机 | UT-B8-1~5 | 否 | 原样复跑 | |
| AC-14 主子同步 | UT-B5-MC-1/2/3 | **是** | 现有 `QuoteBackfillMasterChildAcceptanceTest` 全部是"从零建组"（F8），需新增"已有基底+追加新增"场景，见 BND-04/BND-08 | 见 §9 骨架缺口 |
| AC-15 启动校验 | UT-B3-7/8 | 是（含义变化） | 原样复跑 `allActiveViews_passStartupStyleValidation`，但"通过"的含义从"set-op 安全降级也算过"变为"`bom_view` 必须被正确处理" | |
| AC-16 无 N+1 | UT-B7-4 | 否 | 原样复跑；基底装载层 N+1 见 AC-R8 | |
| AC-17 核价侧零回归 | REG-COST-1~4 | 否（F7 门禁确认 PRICING 侧从不调用 `rewrite`） | 原样复跑（护栏，非高风险） | 风险等级下调，见 F7 |
| AC-18 `plating_scheme` 全局升版 | UT-B5-FLIP-1/2, UT-SCHEME-* | 否（FLIP 路径与 REBUILD 修改正交） | 原样复跑 | |

**task-0721 §6 Q1~Q6 现状**（见 F11）：Q1/Q3/Q4/Q6 已被现有测试实证解决；Q2 已因树任务合并解除阻塞；**Q5 由本次 B3.3 正式给出答案**（OFFLINE 判据=基底行被墓碑删空），不再是待裁决项。

---

## 9. AC-R8 · 无 N+1（3 条，后端单测）

### BE-R8-01 · 基底行集装载 SQL 计数

- **前置数据构造**：报价单涉及 3 张不同 V6 表（`material_bom_item`/`element_bom_item`/`unit_price`），各 2 个组、每组 4 行 pending。
- **执行动作**：统计基底行集装载阶段的 SQL 条数（实现阶段确认具体计数机制——现有测试代码未见 SQL 拦截器，需要新增或用 pgjdbc 日志/`log_statement` 断言）。
- **断言**：每张受触达的表最多产生 2 条基底装载 SQL（1 条 pending + 1 条 current，按 backtask B3.1"每表最多 2 条 SQL"约束），不随组数（本例 2/表）线性增长；3 张表总计 ≤ 6 条基底装载 SQL（不含既有 Phase B `loadRowsByIds`/Phase C `distinctPendingAxis` 等已有 SQL）。
- **反向对照**：不适用（master 没有基底装载这个概念，这是新增能力的正向验证，不是缺陷回归）
- **归属**：后端单测

### BE-R8-02 · 品名/客户名批量解析（对应 B4）

- **断言**：preview 中料号→品名解析用 1 条 `material_no IN (...)` 批量 SQL；客户号→客户名解析用 1 条批量 SQL；不随涉及料号/客户数线性增长（造 10 个不同料号验证仍是 1 条 SQL）。
- **归属**：后端单测

### BE-R8-03 · 20 组场景对比 SQL 条数

- **前置数据构造**：20 个不同 `material_no` 的 `material_bom_item` 组，各自本单 pending 2 行。
- **断言**：基底装载相关 SQL 条数为 O(1)（≤2，轴集合一次性 `IN` 带出 20 组），不是 O(20)。
- **反向对照**：不适用（新增能力验证）
- **归属**：后端单测

---

## 10. 边界场景（8 条）

### BND-01 · 整组删空→OFFLINE（主从表版本，对照现有 `unit_price` 用例）

- **BND-01a**（tab 完整表征且全删，护栏）：`material_bom_item` 组 2 行 pending，1 个 tab 完整表征全部 2 行且全部墓碑删除。
  - 断言：`is_current=0`；不产生新 `bom_version`；`material_bom` 主表同步无新行（不残留孤儿主表行）。
  - 反向对照：**master 上通过**（两种语义在"tab=全集"这一特殊场景下结果一致，纯回归护栏）。
- **BND-01b**：与 BE-R1-03 相同场景（tab 只表征 1/2 行且被删，组内另 1 行从未被任何 tab 提及），交叉引用不重复设计。期望 `is_current=1`（保留未提及行）。反向对照：**master 上失败**。
- **归属**：后端单测

### BND-02 · 多页签同列冲突

- **前置数据构造**：`material_bom_item` 同一行（W-1001X）被两个不同 tab（不同 `sortOrder`）都 patch 了 `composition_qty`，但值不同（低 `sortOrder` 的 tab 改成 2.5，高 `sortOrder` 的 tab 改成 3.0）。
- **断言**：按 backtask B3.2"先到先得（按组件 `sortOrder`）"，最终值 = `sortOrder` 较小的 tab 值（2.5）；产生 WARN 日志；`RowChange.conflict=true`；预览 DTO 对应行 `rd.conflict=true`。
- **反向对照**：不适用（master 无 `conflict` 概念，新增能力验证）
- **归属**：后端单测

### BND-03 · 轴列含 NULL 的组（`unit_price.supplier_no`）

- **前置数据构造**：`unit_price` 一组，轴含 `supplier_no=NULL`（`price_type=PROCESS` 本身没有供应商概念），组内 3 行都是 `supplier_no=NULL` 但 `code` 不同；1 个 tab 只表征其中 1 行。
- **断言**：基底行集装载 SQL 能正确匹配 NULL 轴值（用 `IS NOT DISTINCT FROM` 或等价展开，不是简单 `IN` 导致漏配）；backfill 后 3 行都保留，不因 NULL 轴匹配失败而误判"新组"产生撞键/丢行。
- **反向对照**：**master 上失败**（同 D1 坍缩逻辑，只表征 1 行则丢另 2 行；本用例额外验证的是"NULL 轴值不会让基底装载查询本身失效"，是修复方案自身的正确性护栏）
- **归属**：后端单测

### BND-04 · 手工新增行两套标记，非从零建组场景复测

- **前置数据构造**：改造现有 `QuoteBackfillMasterChildAcceptanceTest` 的两个 ADD 场景（`flatManualAdd_.../treeManualLeaf_...`），把"从零建组"改为"组内已有 2 行基底 + 追加 1 行手工新增"。
- **断言**：`is_current` 行数 = 基底行数(2) + 新增行数(1) = 3；基底 2 行未受影响；新增行正确落库。
- **反向对照**：**master 上失败**——D1 同源：非空基底组，只要 tab 不是 100% 覆盖基底，master 就会丢未表征的基底行。现有 MC 测试因"从零建组"（F8）侥幸绕过这个 bug，这是 test.md 骨架和现有实现测试共同的盲区。
- **归属**：后端单测

### BND-05 · 多 tab 重叠表征同一行

- **前置数据构造**：树 tab 完整表征组内 4 行（含 W-1001X），另一个平铺 tab（`wg_view`）也表征 W-1001X 这 1 行（内容一致，无冲突）。
- **断言**：`is_current` 仍 = 4（不因 W-1001X 被两个来源各贡献一次 candidate 而重复插入/撞键）。若两个 tab 对 W-1001X 的 patch 值冲突，归入 BND-02 冲突逻辑。
- **反向对照**：**需要实现阶段确认** `VersionedV6Writer` 对 `effectiveNewRows` 内容重复行的去重/合并策略后才能定论——本条标注为待实现方式确认，见文末未决问题。
- **归属**：后端单测

### BND-06 ·`element_bom_item.characteristic` 版本列陷阱（说明性）

- **目的**：防止实现/测试把 `element_bom_item.characteristic`（版本列，见 F2）误当内容列去 patch。
- **断言**：`QuoteTableAxis.ELEMENT_BOM_ITEM.contentColumns` 不含 `"characteristic"`；若 collector 遇到页签映射里出现该列，按 B3.2"轴列与版本列不参与 patch，若出现在页签映射里则忽略并 WARN"规则拦截，不写入 `versionColumn`。
- **归属**：后端单测（纯配置断言 + collector 行为验证）

### BND-07 ·`baseSource=CURRENT` 场景的 REBUILD 零 diff

- 与 BE-R4-05 为同一场景，交叉引用不重复设计。

### BND-08 · 主从表 fixed 列联动（`bom_type` 升级）

- **前置数据构造**：基底组 3 行全 RECIPE（`bom_type` 应为 `MATERIAL`）；本单新增 1 行 ASSEMBLY 特征的手工新增行（patch 追加，非从零建组）。
- **断言**：回填后 `material_bom` 主表对应行 `bom_type='ASSEMBLY'`（因为 `deriveMasterFixedColumns` 应按 `effectiveNewRows` **全集**判断"任意行是 ASSEMBLY/OUTSOURCED 则整组 `bom_type=ASSEMBLY`"），同时 `is_current` 行数 = 4（3 基底 + 1 新增）。
- **反向对照**：**master 上失败**——真正的判据是行数：master 下 `effectiveNewRows` 只含被表征的 ADD 这 1 行，基底 3 行 RECIPE 完全消失，`is_current` 从 4 坍缩到 1（`bom_type` 巧合也可能算出 ASSEMBLY，分辨不出行数丢失，所以本用例断言必须以行数为准，`bom_type` 是辅助断言）。
- **归属**：后端单测

---

## 11. 反向对照总表（审核速查）

| 用例 | Master 预期 | 用例 | Master 预期 | 用例 | Master 预期 |
|---|---|---|---|---|---|
| BE-R1-01 | 失败 | BE-R3-08 | 失败 | UI-R5-01~06 | 不适用（结构破坏性变更） |
| BE-R1-02 | 失败 | BE-R3-09 | 失败 | BE-R6-01 | 失败 |
| BE-R1-03 | **失败**（注意坍缩到 0 非 3，见陷阱说明） | BE-R3-10 | 通过（护栏） | BE-R6-02 | 失败 |
| BE-R1-04 | 失败 | BE-R4-01 | **失败**（黄金用例） | BE-R6-03 | 失败 |
| BE-R2-01 | 失败 | BE-R4-02 | 失败 | BE-R8-01~03 | 不适用（新增能力） |
| BE-R2-02 | 失败 | BE-R4-03 | 通过（护栏） | BND-01a | 通过（护栏） |
| BE-R2-03 | 失败 | BE-R4-04 | 通过（护栏） | BND-01b | 失败（=BE-R1-03） |
| BE-R3-01 | 失败 | BE-R4-05 | 不适用 | BND-02 | 不适用（新增能力） |
| BE-R3-02 | 失败 | E2E-R4-01 | **失败**（黄金用例） | BND-03 | 失败 |
| BE-R3-03 | 失败 | E2E-R4-02 | **通过**（刻意边界对照，见说明） | BND-04 | 失败 |
| BE-R3-04 | 失败 | | | BND-05 | 待确认 |
| BE-R3-05 | 通过（护栏） | | | BND-06 | 不适用（配置断言） |
| BE-R3-06 | 失败 | | | BND-07 | =BE-R4-05 |
| BE-R3-07 | 失败 | | | BND-08 | 失败 |

**统计**：43 条独立设计用例（不含 AC-R7 回归矩阵的 18 项复跑指引）。其中明确标注"master 上应失败"的有 **29 条**（有效性证明主体），"master 上应通过"的护栏用例 **7 条**，"不适用/待确认"**7 条**。两条黄金用例（**BE-R4-01**、**E2E-R4-01**）直接对应事故复盘 D1+D4 组合场景，是审核重点。

---

## 12. 未决问题（需要技术总监裁决，不影响主体用例执行）

1. **BE-R4-05 / BND-07**（`baseSource=CURRENT` 且零净 diff 的 REBUILD）：backtask B4 只说"记 ERROR 日志"，未定义是否应该优化为不写入无意义新版本。建议裁决：是否要求实现方在此场景下降级为等效 FLIP（不消耗版本号）,还是接受"写一个内容相同的新版本"这种版本号浪费。
2. **BND-05**（多 tab 重叠表征同一行）：`VersionedV6Writer` 对 `effectiveNewRows` 内部重复行（相同 `uq_material_bom_item` 键）的合并策略未在 backtask 明确，需要实现阶段确认是"后写覆盖"还是直接抛撞键异常。
3. **AC-R8 计数机制**：现有测试代码库未见通用的"SQL 语句计数"基础设施，`BE-R8-*` 系列执行前需要先确认用什么手段计数（pgjdbc 日志 / 自定义 `DataSource` 代理 / `log_min_duration_statement`），这属于测试基础设施缺口，不是用例设计问题。

---

## 附录 A：真实事故 SQL 快照（供 fixture 参照，只读，不改真库）

```sql
-- bom_view（真实模板，component_sql_view.sql_view_name='bom_view'）
SELECT
  mbi.component_no AS material_no, mbi.material_no AS parent_no,
  COALESCE(mm.material_name, mr.name) AS _料件名称,
  mbi.scrap_rate AS _损耗率, mbi.net_weight AS _净重, mbi.rough_weight AS _毛重,
  mbi.weight_unit AS _用量单位, mbi.composition_qty AS _组成数量, mbi.issue_unit AS _组成单位
FROM material_bom_item mbi
  LEFT JOIN material_master mm ON mm.material_no = mbi.component_no
  LEFT JOIN material_recipe mr ON mr.code = mbi.component_no
WHERE mbi.system_type = 'QUOTE' AND mbi.is_current AND mbi.component_no = ANY(:total_material_no)
UNION ALL
SELECT mm.material_no, NULL::text, mm.material_name, NULL::numeric, NULL::numeric, NULL::numeric,
  NULL::varchar, NULL::numeric, mm.standard_unit
FROM material_master mm
WHERE mm.material_no = ANY(:total_material_no)
  AND NOT EXISTS (SELECT 1 FROM material_bom_item x WHERE x.component_no = mm.material_no
                  AND x.system_type = 'QUOTE' AND x.is_current)

-- wg_view（外购件成本，D1 复现用）
SELECT mbi.material_no AS hf_part_no, mbi.material_no AS _销售料号,
  COALESCE(mm.material_name, mr.name) AS _组成件名称,
  mbi.composition_qty AS _组成数量, mbi.issue_unit AS _组成单位
FROM material_bom_item mbi
  LEFT JOIN material_master mm ON mm.material_no = mbi.component_no
  LEFT JOIN material_recipe mr ON mr.code = mbi.component_no
WHERE mbi.system_type = 'QUOTE' AND mbi.is_current AND mbi.characteristic = 'OUTSOURCED'
  AND mbi.customer_no = :customerCode
ORDER BY mbi.material_no, mbi.seq_no

-- mc_view（材料成本，D2 复现用，不暴露 base_qty）
SELECT ebi.material_no AS hf_part_no, ebi.material_no AS _销售料号,
  COALESCE(mr.name, mm2.material_name) AS _材质, ebi.seq_no AS _项次, ebi.component_no AS _元素,
  ebi.content AS _组成含量, ebi.scrap_rate AS _损耗率, ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位, cep.unit_price AS 元素单价
FROM element_bom_item ebi
  LEFT JOIN material_recipe mr ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = ebi.component_no
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY ebi.material_no, ebi.material_part_no, ebi.seq_no
```

## 附录 B：真实事故行数据快照（cpq_db_0724，只读，供 fixture 数值参照）

```
material_bom_item (QUOTE, CUST-0001, S-3120014539) v2009（通过前 pending）：
  seq=1 00137    RECIPE      composition_qty=NULL
  seq=2 992      RECIPE      composition_qty=NULL
  seq=3 S-80011  ASSEMBLY    composition_qty=1.000000
  seq=5 W-1001   OUTSOURCED  composition_qty=2.000000

element_bom_item (QUOTE, S-3120014539, material_part_no=00137)：
  v2008(通过前): component_no=Cu, base_qty=0.624610, scrap_rate=1.0500, seq_no=1, issue_unit=g/PCS
  v2010(通过后): component_no=Cu, base_qty=NULL(!), scrap_rate=1.0500, seq_no=1, issue_unit=g/PCS
```

## 附录 C：执行阶段测试类命名映射（供后端 agent 对齐，避免撞车既有类）

| 新测试类 | 覆盖范围 |
|---|---|
| `QuoteBackfillPatchSemanticsTest` | AC-R1、AC-R2、BND-01~08（patch 语义核心） |
| `QuotePendingRewriterSetOpTest` | AC-R3 §4 的 BE-R3-01~06（纯函数，set-op 锚点注入） |
| `QuoteBackfillTreeAnchorPropagationTest` | AC-R3 §4 的 BE-R3-07~10（真库端到端，树锚点传导） |
| `BackfillPreviewFidelityTest` | AC-R4 全部（预览≡执行，含两条黄金用例） |
| `QuoteBackfillClosureCrossGroupTest` | AC-R6（闭包跨组精确） |
| `QuoteBackfillNoNPlusOneTest` | AC-R8 |
| （既有）`QuoteBackfillFlatAcceptanceTest`/`QuoteBackfillMasterChildAcceptanceTest`/`QuoteBackfillFlipRouteTest`/`PlatingSchemeGlobalVersioningAcceptanceTest`/`QuotePendingRewriterTest` | AC-R7 回归矩阵，由**后端 agent 在原文件基础上按 §8 表格调整**，不新建文件 |

前端不新建测试文件（手测截图存档，见 §6）。
