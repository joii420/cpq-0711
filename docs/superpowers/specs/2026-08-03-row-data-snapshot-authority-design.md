# 行数据即快照：默认值只烘一次，清空必须留住 — 设计

> 立项日期：2026-08-03
> 触发：用户报「报价单保存后重新打开，已经删除的数字值又恢复了」
> 类型：语义修正（协议级），命中 `docs/方案制定前必读.md` 决策树 **改动 2**（fallback 链 / 渲染分支，AP-50 两端同步）+ **改动 7**（autoSave / snapshotRows / buildDraftPayload）

---

## 1. 问题

用户在报价单卡片里清空一个数字输入格（如「损耗率%」「组成数量」「加工费」），保存草稿后重新打开，**数字又回来了**。

### 1.1 根因：`''`（用户清空）与「键不存在」（从未填过）被全链路折叠成同一个「空」

四处判空口径各自独立，但都犯同一个错：

| # | 位置 | 代码 | 后果 |
|---|---|---|---|
| 1 | `QuotationStep2.tsx:1870` 默认值烘焙(bake) effect | `if (!(cur === undefined \|\| cur === null \|\| cur === ''))` 才算「已填」 | `''` 被当成空格子 → 重新烘 `default_source` 值 |
| 2 | `QuotationWizard.tsx:1083` 保存回填 §1.6 | `if (enriched[k] === undefined \|\| null \|\| '') enriched[k] = f.content` | 无 `default_source` 但有静态 `content` 的列，**保存那一刻**就被填回 |
| 3 | `QuotationWizard.tsx:1069` 保存回填 §1.5（`FIXED_VALUE`） | 同上 | 同上 |
| 4 | `FormulaCalculator.java:1281` `resolveRowByFieldName` INPUT 分支 | `if (nonEmpty(v)) out.put(name, v)` | 显式清空的值**不写键** → 库里「用户清空」与「从来没这一列」物理上无法区分 |
| 5 | `QuotationWizard.tsx:519-521` `hasUserInput` | `r[k] != null && r[k] !== ''` | 某行若只剩空值，enrich 合并会把整行退回默认行 |
| 6 | `ComponentCell.tsx:596-607` 只读分支 | 值空 → 回退显示 `resolveInputDefault` 解析值 | 编辑页清空后是空的，详情页却仍显示数字（AP-50 双端不一致） |

`bakedRef` 是 `useRef`，**只活在当前挂载的卡片实例里**。所以同一次会话内清空不会被回弹（守卫已在加载时对该格子置位），但**刷新 / 重开 = 新挂载 = 守卫清零**，`''` 立刻被识别成空格子重新烘一遍。

### 1.2 实测证据（`cpq_db_0724`）

- ACTIVE 组件共 **91 个 `INPUT_NUMBER` 字段，其中 81 个配了 `default_source`、11 个配了静态 `content`** —— 几乎所有数字输入列都在这条路径上。
- 全库 `quotation_line_component_data.row_data` 只有 **1 条**记录含空串值（`QT-20260731-0037` / `COMP-0090 材料成本` 第 0 行 `"组成含量(%)": ""`）。它之所以能留住，正是因为该行 `basicDataValues` 里 `{$mc_view._组成含量}` 是 `null`（源解析不出值 → 烘不了）。
- 同一组件同一行的 `{$mc_view.元素单价}` = `216770.0000`（源有值）→ 该格一旦清空，重开必被烘回。
- 同组件第 1 行 `{"元素":"C",…,"材料成本":0}` 连 `元素单价` / `毛重` 键都没有 —— 即 #4 丢键的实证。

**判据总结**：清空**能留住** ⟺ 该行 `default_source` 解析不出值；清空**必被恢复** ⟺ 源能解析出值，或字段无源但有静态 `content`。

### 1.3 保存态与刷新态不一致的完整清单

| # | 情形 | 触发条件 | 后果 |
|---|---|---|---|
| 1 | 清空的输入列被回填默认值 | 该行源解析得出值 | **必然**。且下次保存会把回填值真正写进库（静默改数据，金额随之变） |
| 2 | 静态默认值列在保存瞬间被填回 | 字段无 `default_source` 且有 `content` | 必然，比 #1 更早发作 |
| 3 | 整行退回默认行 | 某行清空后只剩空值（`hasUserInput` 判假） | 该行整体被 enriched 默认行替换 |
| 4 | 整行用户输入被清空重算 | 该行有 driver 组件的 `snapshot_rows` 缺失/为空 | `computeRowDataFromSnap` 用**空 editRows** 重物化，刚保存的手工输入被覆盖 |
| 5 | `元素单价` 被价格策略覆盖 | 罗克韦尔(CUST-0001) + 元素 ∈ 调价清单 | task-0729 在建功能的既定设计，**不在本次范围**（见 §7） |

---

## 2. 核心不变式

> **`row_data[row][fieldName]` 这个键存在 = 该格已定值，任何路径不得写入；键不存在 = 从未定值，且仅此时允许烘一次默认值。**
>
> **清空 = 把值写成空串，不是删键。**

派生规则：

1. `''` / `null` **是值**，不是「空」。全链路禁止再用 `v == null || v === ''` 当「没定值」判据。
2. 默认值来源不变（`default_source` → 静态 `content`），只是触发条件从「值空」收紧为「键无」。
3. **值中性**：本次不改任何求值口径。`""` 与「键缺失」在列小计（非数字跳过）、公式引擎（`toNumber("")→null`）、cross_tab 匹配（`pickNonEmpty` 跳空）里行为相同，金额必须逐位不变。

---

## 3. 已裁定的设计决策

| 决策 | 结论 | 备选与否决理由 |
|---|---|---|
| **D1 权威判据** | **键存在即权威**。`row_data` 里键存在（哪怕值是空串）即视为已定值 | 否决「行级 `__baked` 标记」：判空口径仍模糊，且新元数据键要跟着行走遍所有物化/复制/快照路径。否决「完全不自动填充」：81 个配了源的数字列全变手填，与现有使用习惯冲突 |
| **D2 显式刷新语义** | 「刷新基础数据 / 重算卡片」**一律不动 INPUT 列**。只重取 driver 行 / BASIC_DATA / DATA_SOURCE / 重算 FORMULA | 否决「刷新=重新烘」：与不变式冲突，且需要二次确认弹窗。否决「另给恢复默认值入口」：本期 YAGNI，需要时再单独立项 |
| **D3 存量处置** | **不做任何数据迁移**。存量单下次打开时，键缺失的格子按新规则烘一次并固化，此后永不再碰 | 接受的代价：历史上被丢键的「已清空」格子会被填上一次，用户再清一次即可真正留住。否决全库迁移：触及全部报价单，风险大于收益 |
| **D4 架构（谁负责首次烘焙）** | **方案 A：前端 bake 保留，只改判据** | 方案 B（烘焙下沉后端、前端零填充能力）语义更纯，但手动新增行（manual rows）无后端物化链路需另补一套，且加产品在首次 saveDraft 前格子会是空的；风险与工作量不成比例。如需 B 单独立项 |
| **D5 范围加码** | 纳入①详情页/只读视图同步；②修「重展开冲掉用户输入」 | 两项均由不变式强制推出：①不修则 AP-50 双端不一致；②不修则不变式在该路径上被破坏 |

---

## 4. 改动清单

前端 `ProductCard` 报价/核价两侧共用，后端 `resolveRowByFieldName` 也被两条物化链共用，**两侧天然一起改**，无需分别处理。

| # | 位置 | 现状 | 改后 |
|---|---|---|---|
| 1 | `QuotationStep2.tsx:1870` bake effect | `cur === undefined \|\| null \|\| ''` 视为空 → 烘 | `!(key in curRow)` 才烘；`bakedRef` 保留作同帧去重 |
| 2 | `QuotationWizard.tsx:1077-1088` §1.6 保存回填 | 值为空就填回 `content` | 仅键不存在时填 |
| 3 | `QuotationWizard.tsx:1064-1072` §1.5 `FIXED_VALUE` | 同上 | 同上 |
| 4 | `FormulaCalculator.java:1251-1282` INPUT 分支 | `nonEmpty(v)` 假 → 不写键 | `editValues` 含该键 → 原样写入（含空串）；仅键缺失时才走 driverRow → default_source → content |
| 5 | `QuotationWizard.tsx:519-521` `hasUserInput` | `r[k] != null && r[k] !== ''` | 改为「有非 `row_index` 的键即算用户数据」 |
| 6 | `ComponentCell.tsx:596-607` 只读分支 | 值空 → 显示 `resolveInputDefault` 解析值 | 值空 → 显示 `—`（与编辑页一致，AP-50） |
| 7 | `ConfigureSnapshotService.java:993` `computeRowDataFromSnap` | `editRows=Map.of()` 重物化，冲掉用户输入 | **以库内现有 `row_data` 为基底做 patch**，沿用 repair-0727 已验证的 patch 语义（AP-60 同族），不新发明 |

**#7 是本次风险最高的一处**（触碰 saveDraft 热路径），必须单独拆 Task + 单独对拍。

**空值的物理表示统一为空串 `""`，不用 `null`**：`CardSnapshotService.mergeRowDataInputsIntoEdits:3019` 的合并条件是
`!v.isMissingNode() && !v.isNull()`，`null` 会被跳过 → 键传不到 `editValues` → 在 #4 处等价于「键缺失」→ 又被烘。
故前端清空一律写 `""`；若存量 `row_data` 中出现 `null`，按「键缺失」处理（不额外兼容，避免引入第三种空语义）。

### 4.1 关键链路自检（改后闭环）

1. 前端 bake 首次写入键 → React state
2. 用户清空 → 键仍在，值 `''`
3. `saveDraft` → `snapshotRows()` 的 `{...baseRow}` 保留 `key:''` → payload
4. 后端 `QuotationService:2297` `cd.rowData = cdDraft.rowData` 原样落库 ✅
5. `snapshotQuotation(id, true)`：该行已有完整 `snapshot_rows` → `lineNeedsExpand` 假 → 整行跳过 ✅
6. 若该行确需重展开 → `computeRowDataFromSnap` 按 #7 patch 保留 ✅
7. 重开 → `row_data` 有键 → bake 判 `key in row` 为真 → 不烘 ✅

已知且可接受的缺口：**清空后未保存就刷新**，键状态本就未落库，重开后按「从未定值」烘一次 —— 与任何未保存编辑的行为一致。

---

## 5. 验证策略

E2E 夹具已随迁库 `cpq_db_0724` 整体失效（[[BL-0078]]），本次按 task-0801 先例用等价证据替代，并**如实标注不会有 E2E 绿灯**。

1. **值中性硬证据**：`GoldenCardValuesEquivTest` / `FormulaGoldenTest` 改动前后逐位对拍，必须完全相同 —— 证明「只改回填、不改算钱」。
2. **新增单测**（三组）：
   - `resolveRowByFieldName` 显式空值写键 / 键缺失走 default_source 链；
   - bake 判据（`key in row` vs 值为空）；
   - patch 物化保留既有 `row_data` 用户输入。
3. **人工三视图复测**：报价单编辑页 / 核价卡片 / 详情页，用真实单据（`QT-20260731-0037` / `COMP-0090`）走闭环：
   `清空 → 保存 → 重开仍空 → 再保存 → 仍空`；并验证同页签其它未动过的格子默认值照常烘出。
4. **前端 tsc 0 错 + 每个改动 `.tsx` 经 Vite transform 返 200**（CLAUDE.md 强制自检）。
5. **后端**：`touch` 触发 Quarkus 重启后业务端点返 401（应用在跑、鉴权正常）。

---

## 6. 明确不在范围内

- **不做任何数据迁移**（D3）。
- **不改任何公式 / 求值口径**（§2 规则 3）。
- **不改默认值的来源与优先级**（`default_source` → `content` 不变）。
- **不动 `PriceReconciler`**（见 §7）。
- 「恢复默认值」右键入口 —— 本期 YAGNI。

## 7. 需登记 BACKLOG 的相邻问题

`PriceReconciler`（task-0729，当前未提交但已在 8081 热加载生效）对 `元素单价` 的强制归位与本不变式存在**语义冲突**：

- 实测 `cpq_db_0724`：`CUST-0001 罗克韦尔` 有 1 条 `enabled` 调价策略（2026-08-03 07:23 建），`material_scope_mode=ALL`，元素清单 `Ag`/`Cu`；8 个组件配了 `element_price_field=元素单价`。
- `PriceReconciler.java:204-233`：只要「元素 ∈ 清单」就无条件覆盖 `row_data` 价格列，解不出价时直接 `remove` 该键。
- 即：价格列被系统接管后用户**仍可编辑**，改完保存会被悄悄改回 —— 是「删了又回来」的另一个独立来源。

**建议**：产品上确认价格列是否应置灰 + 提示「由价格策略管控」，而不是留一个改了不生效的输入框。本次不处理，登记为独立条目。
