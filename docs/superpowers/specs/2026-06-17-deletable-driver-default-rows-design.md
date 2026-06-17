# 报价单 driver 默认行可永久删除 设计

> 日期：2026-06-17
> 模块：报价单编辑（Step2）/ driver 行渲染 + 快照 + 持久化
> 状态：设计待用户复审（v2，已吸收架构评审 + 用户决策）

## 1. 背景与现状

报价单编辑页（`QuotationStep2`）每个页签的行分三类（`QuotationStep2.tsx:2506-2527`）：
- `row._preset` 固定行 → 🔒 不可删；
- `isDriverBound` **基础数据自动展开行（driver 默认行）→ 🔗 不可删**；
- 手动行（`_origin='manual'`）→ ✕ 可删。

driver 默认行受「driver 行数权威」约束（`QuotationStep2.tsx:1467-1472` 对齐到 `rowCount`；后端 `expandTemplateDriverBaseRows`/`refreshQuoteCardValues` 每次重展开）。单纯放开删除会被 driver 重展开补回（AP-51/AP-38）。

**需求（已确认）**：所有报价单的 driver 默认行允许用户自由删除，且**永久删除——刷新/重算后不回来**。

## 2. 现状关键链路（已核实）

- **行键**：前端 `useCardSnapshots.computeRowKey`+`buildUniqueRowKeys`（`useCardSnapshots.ts:110/154`）；后端 `FormulaCalculator.computeRowKey`+`uniquifyRowKeys`（`FormulaCalculator.java:662/691-747`）。两份唯一化算法逐字节等价（**降级为假设，须双视图 E2E 验证**，AP-52 历史漂移点）。
- **关键事实（对永久删除致命）**：当 `rowKeyFields` 为空 / `["__seq_no__"]` / 全空时，`computeRowKey` 两端兜底成 **位置下标**（前端 `String(rowIndex)` `:133`；后端返 null → 调用方 `String.valueOf(idx)` `CardSnapshotService.java:941`）。即 effKey 可能 = **行位置**，而非稳定业务键。
- **driver 展开**：前端 `useDriverExpansions`（`driverExpansionKey` 含 partNo/componentId/customerId/dataDriverPath/fieldsHash，AP-37）；后端 `CardSnapshotService.expandTemplateDriverBaseRows`。
- **baseRows 多生产路径**：① `refreshQuoteCardValues`→`expandTemplateDriverBaseRows`（草稿重刷，重 expand）；② `snapshotLineValues`→`buildCardValues`→`buildBaseRowsFromSnapshotRows`（saveDraft 新行，从 `snapshot_rows` 反序列化，**不重 expand**）（`CardSnapshotService.java:389/468/641`）；③ `ConfigureSnapshotService.snapshotLines` 写 `snapshot_rows`（`:122`）。
- **唯一化重算点（过滤必须贴这些点）**：`buildResolvedRows:944`、`computeRows`(`FormulaCalculator`)、`filterEditRowsToNewBaseRows:1005`、前端 `buildUniqueRowKeys`。
- **editRows 对齐**：全部按 rowKey（=effKey）索引（`buildResolvedRows:952`、`filterEditRowsToNewBaseRows:1009`），非位置对齐。

## 3. 设计

### 3.0 头号不变量（所有落点必须遵守）

> **effKey 永远基于「完整 driver 展开集」的唯一化结果计算；过滤后的子集绝不再参与 `computeRowKey(idx)` 或 `uniquifyRowKeys`。过滤 = 在「完整集唯一化」之后，按墓碑剔除整行，不重算 key、不重排下标。**

违反此不变量 = AP-54（过滤后下标错位 + 受控 input 假死）+ editRows 串行错位。每个落点代码注释标注本不变量，PR 全工程 grep 自检。

### 3.1 核心机制：墓碑 `{effKey, fp}` + 先唯一化后过滤

- 每页签持久化一份 **`deleted_row_keys`** = 墓碑数组，每项 `{ "effKey": "<完整集唯一化键>", "fp": "<字段值指纹>" }`。
- **指纹 fp**（§3.8）：删除时该行 rowKeyFields 值 + 关键 driverRow 字段值的稳定拼接哈希。用于位置型 effKey（无业务行键）下的二次校验，避免源集漂移误删。
- **匹配规则（前后端统一）**：一条 fresh 展开行被判定为"已删" ⟺ 存在墓碑 `t` 使得 `t.effKey == 该行完整集唯一化 effKey` **且** `t.fp == 该行指纹`。effKey 与 fp **双命中**才删（业务键行 effKey 稳定，fp 是加固；位置型行 effKey 易漂，fp 兜底）。
- **过滤纪律**：① 对完整 driver 展开集做唯一化（同现状口径）；② 计算每行 fp；③ 剔除双命中墓碑的行；④ 用过滤后集渲染/求值/落快照。

### 3.2 数据模型 & 复制
- Flyway 新迁移：`ALTER TABLE quotation_line_component_data ADD COLUMN deleted_row_keys jsonb NOT NULL DEFAULT '[]'`。
- 实体 `QuotationLineComponentData` 加 `@JdbcTypeCode(JSON) public String deletedRowKeys = "[]";`。
- 复制（`QuotationService.copy` `:1208`/`migrateAndCreateComponentData` `:1293`）：
  - **同模板复制**：`deleted_row_keys` 按 componentId 原样拷贝。
  - **换模板复制**：一律 **清空为 `[]`**（换模板后 driver/源集/effKey 全变，旧墓碑必失配 → 误删新行）。**不**套 row_data 的字段名配对口径。

### 3.3 删除动作（前端 Step2）+ 持久化端点
- 渲染层：driver 行（`isDriverBound`）🔗 → ✕「删除行」。`_preset` 仍 🔒。
- 点 ✕ → 取该行**完整集唯一化 effKey**（复用渲染层已算 rowKey，AP-54 对象引用映射真实行）+ 计算 fp → 调**专用「追加墓碑」端点**：
  - `POST /api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row`，body `{ componentId, effKey, fp }`。
  - 后端 append `{effKey, fp}` 进该 (lineItem, component) 的 `deleted_row_keys`（去重），并**就地重刷该行报价快照**（`refreshQuoteCardValues` 单行）使渲染立即生效。
  - **不混进高频防抖 `saveDraft`**（避免全量覆盖丢墓碑 + 竞态；评审 AP-2 风险）。
- 手动行删除：维持现状（物理删 `row_data`，不进墓碑）。
- 撤销逃生口：端点支持 `op=restore-all`（清空 deleted_row_keys）作为数据兜底；本期不做单行恢复 UI。

### 3.4 统一过滤函数（前后端各一份，逐字节对照）
- 抽纯函数 `applyDeletedRowKeys(uniqEffKeys, fps, deletedTombstones) → keepMask`（前端 TS + 后端 Java 双副本 + 跨端对拍测试，与 `uniquifyRowKeys` 同等纪律）。
- **紧贴每个唯一化点调用**（不散落到 expand 调用点）：
  - 后端：`buildResolvedRows:944`、`computeRows`、`filterEditRowsToNewBaseRows:1005`、`buildCardValues`/`buildBaseRowsFromSnapshotRows`（saveDraft 新行路径）、`buildExcelValues`(`:575/583/609`) 取数。
  - 前端：`buildUniqueRowKeys` 之后于 `useCardSnapshots` 组装 baseRows/editRows/formulaResults；`useDriverExpansions` 消费侧；`QuotationStep2` 行对齐（1467）+ 渲染迭代。

### 3.5 snapshot_rows 存全量、渲染期过滤（决策）
- `ConfigureSnapshotService.snapshotLines` / `snapshot_rows` **存完整全量行**（不在冻结层物理删），墓碑是唯一权威。
- 所有读 baseRows/snapshot_rows 的下游一律按 §3.4 过滤。完整下游清单（≥7 处，PR 逐项勾）：`buildResolvedRows` / `computeRows` / `filterEditRowsToNewBaseRows` / `buildBaseRowsFromSnapshotRows` / `buildExcelValues`(Excel) / 前端 `useDriverExpansions`+`QuotationStep2` 渲染 / 详情页 `ReadonlyProductCard`（确认其经 ComponentCell 共享读路径而非直读 snapshot_rows，守 AP-50）。

### 3.6 行数权威（AP-51，不改 rowCount 语义）
- **保留** `expansion.rowCount` = driver 展开**原始**行数（AP-51 监控点 + "刷新 3 次稳定" 自检基准语义不变）。
- **另立** `effectiveRowCount = uniquify(展开).length − 命中墓碑数`。渲染/小计/`snapshot_rows` 行迭代/持久化行数显式用 `effectiveRowCount`；禁止 `Math.max`。
- **`deletedRowKeys` 不进 `driverExpansionKey`**（删除是展开后的纯函数过滤；进 key 会每删一行击穿 driver 缓存重 expand）。

### 3.7 核价侧隔离
- 仅**报价侧**（QUOTE）driver 行可删。核价侧 `buildCostingCardValues`(`:526`) 复用 `assembleTabsWithFormulaResults` 公共出口——过滤须按 `side==QUOTE` 守护，核价路径显式传 `deletedRowKeys=空`，绝不误伤核价行。

### 3.8 指纹 fp 定义
- `fp = stableHash(join("|", [rowKeyFields 各字段值] + [driverRow 排序后 (k=v) 全字段值]))`。前后端同算法、同字段序、同哈希（双副本 + 对拍测试）。
- 业务键行：effKey 已稳定，fp 为冗余加固；位置型行：fp 是实际判别依据。
- 漂移仍有理论残余（源行字段值整体相同且顺序变化）——本期接受，软上限 + restore-all 兜底。

## 4. 边界与决策
- **永久性**：墓碑跨刷新/重算/重进编辑/加产品路径都不复活。
- **位置型行键组件**：允许删，靠 fp 二次校验降低误删（用户决策）。
- **换模板复制**：清空墓碑（§3.2）。
- **全删空页签**：允许，0 行兜底 "—"（AP-38）。
- **范围**：所有报价单；仅报价侧；手动行行为不变。
- **墓碑膨胀**：只增不减 → 软上限（如每组件 ≤500 条，超则日志告警）+ `restore-all` 逃生口。

## 5. 影响面 & 强制自检（协议级）
- Flyway 迁移 `success=t` + 实体加列编译。
- 不改 `field_type` 枚举 → **不触发 AP-44 矩阵**；但触及 `QuotationStep2.tsx`/`useDriverExpansions.ts`/`useCardSnapshots.ts`/`CardSnapshotService.java`/`FormulaCalculator.java`/`QuotationService.copy` → CLAUDE.md §5 强制双 E2E（`quotation-flow.spec.ts` SIMPLE + `composite-product-flow.spec.ts` COMPOSITE）。
- **effKey 稳定性单测**（前后端 + 对拍）：三夹具 —— ① 5 行 3 撞键删中间一个，断言剩余 #N 键不变；② 8 行全空行键（位置型）删第 3 行 + fp 校验命中正确行；③ 删行后基础数据增 1 行，断言墓碑不误命中。
- **E2E 必验**：删 driver 行后 ① 该行消失 ② 刷新 3 次不回弹（行数稳定，AP-51）③ 列小计/金额按剩余行重算正确 ④ **剩余行的输入值不串行**（editRows 按 effKey 对齐，AP-54）⑤ 全 Tab `'加载中'=0`（AP-31/38）⑥ 详情页同样跳过被删行（AP-50）⑦ 加产品/保存路径（buildCardValues）与重刷路径行为一致（被删行两路径都不复活）。
- PR 附：删除前后该 Tab 截图 + 刷新 3 次行数证据 + 双 spec `1 passed` + `'加载中' final=0`。

## 6. 已决（原未决项收敛）
- 持久化：专用端点（§3.3）。
- snapshot_rows：存全量、渲染期过滤（§3.5）。
- rowCount：不改语义，另立 effectiveRowCount（§3.6）。
- 换模板复制：清空墓碑（§3.2）。
- 位置型行键：允许删 + fp 二次校验（§3.8）。
- 核价：显式隔离（§3.7）。
- 膨胀：软上限 + restore-all（§4）。
