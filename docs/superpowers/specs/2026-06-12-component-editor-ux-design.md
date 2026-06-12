# 组件管理编辑体验三项优化 — 设计方案

> 日期：2026-06-12
> 分支：`worktree-feat+component-editor-ux`
> 状态：设计已与用户逐项确认 + 经独立架构评审修订，待写实现计划
> 修订记录：v2（2026-06-12）按架构评审采纳 R1/R2/R3/R4 + Y1~Y4 修订——请求3 改为复用/扩展 PathPickerDrawer（非新建组件）、默认值来源放宽为任意视图列、driver 路径本期不动、明确分三阶段、后端非保证零改动。

## 背景与目标

组件管理（`cpq-frontend/src/pages/component/`）当前编辑体验有三处痛点，本方案一并解决：

1. **编辑易丢失**：组件详情态（字段 / 公式 / driver 路径 / 行键 / Excel 列）是纯 React state，只有点「保存」才落库。切换组件、误刷新、误关标签页都会静默丢弃未保存的编辑。
2. **行键资格不实时**：给字段配行键时，若字段名与 driver 列撞名会被禁用；改了字段名后，行键复选框的禁用状态不实时刷新，必须退出重进页签才更新。
3. **取数配置体验差**：默认值来源的「基础数据」类型只有手填输入框；字段路径选择各处列用下拉框、不够直观。需做成统一的「选视图 → 点字段（可点列表）」点选交互。

三项在**同一个隔离 worktree** 内开发，但按隐藏依赖**分三阶段**推进（见末尾「实施阶段」）。

---

## 请求 1 — 组件编辑自动保存（本地草稿 + 自动恢复）

### 决策
- **路线 B（本地草稿）**，非真存库。理由：组件「保存」会触发 `TemplateService#refreshSnapshotsByComponent`，把改动传播到所有引用该组件的模板 snapshot（报价单/核价单）。若自动提交中间状态（公式写一半、字段类型刚切换没配完），会污染线上模板和进行中的报价（AP-37/40/44 族风险）。本地草稿不碰后端、不触发传播，落库仍由用户显式「保存」控制。
- **存储 B1（localStorage）**：覆盖"同人同机误操作"全部场景（切组件 / 刷新 / 关标签重开），零后端改动、零 DB 迁移。不跨设备（用户已确认无此需求）。

### 机制
- **草稿写入**：组件详情任何编辑（`fields` / `formulas` / `dataDriverPath` / `rowKeyFields` / `excelColumns` / `bomRecursiveExpand`）→ 防抖 **800ms** → 写 `localStorage`。
  - key：`cpq:component-draft:{componentId}`
  - value：`{ savedAt: number, baselineUpdatedAt: string, snapshot: { fields, formulas, dataDriverPath, rowKeyFields, excelColumns, bomRecursiveExpand } }`
  - `baselineUpdatedAt` 记录草稿基于的服务端 `updatedAt`，用于陈旧检测。
  - **快照字段集已核对完整**（对照 `handleSave` payload `ComponentManagement.tsx:941-962`）：`name` 详情页不可编辑无需存；`datasource_binding`/`default_source`/`list_formula_config`/`global_variable_code`/`basic_data_path` 都是 `fields[]` 子属性，随 `fields` 整体快照覆盖，不漏。
  - **临时 key 处理（Y4-a）**：`fields` 元素含运行期临时 `key`（`:906` `field-${i}-${Date.now()}`）。写 localStorage 前**剥离 `key`**，恢复时按现有规则**重建** `key`，避免 key 冲突/React 列表错乱。
- **自动恢复**：`handleSelectComponent` 加载组件后，检测该组件有草稿：
  - 草稿 `baselineUpdatedAt` == 服务端当前 `updatedAt` → **静默恢复**编辑态，顶部显示提示条「检测到未保存的修改，已自动恢复 · [放弃草稿]」。
  - 草稿 `baselineUpdatedAt` != 服务端 `updatedAt`（服务端被改过，草稿陈旧）→ 提示条改为「该组件在别处已更新，本地草稿可能过期 · [仍恢复草稿] / [放弃草稿]」，默认**加载服务端版本**，让用户显式选择。
  - **恢复后保存时序（Y4-b）**：行键保存逻辑（`handleSave:949-958`）依赖实时的 `rowKeyCandidates`，而候选是恢复后异步重算的。恢复后**等候选刷新完成再允许「保存」**（或保存时强制同步刷新一次候选），避免用旧候选写错 `rowKeyFields`。
- **草稿清除**：点「保存」成功落库后，清掉该组件草稿 key。「放弃草稿」按钮亦清除并重载服务端版本。
- **脏标记**：有草稿 / 有未保存改动时，组件详情头部组件名旁 + 左侧列表对应项显示小圆点徽标。

### 全局「保存全部草稿 (N)」
- 组件管理页**顶部工具栏**新增按钮，徽标显示有草稿的组件数；无草稿时**禁用**（置灰 + hover tooltip 原因，遵循《列表操作规范》——不用 `if return null` 隐藏）。
- 点击 → **弹确认 Modal**（遵循《列表操作规范》危险/批量动作走 Modal 列出所选项）：
  - 列出这 N 个有草稿的组件（名称 + code + 草稿时间），每项可勾选/取消（默认全选）。
  - 确认 → 对勾选项**逐个顺序**调原有保存接口落库（顺序而非并发，避免批量 snapshot 传播打爆后端）。
  - **逐个落库前复检陈旧（Y3）**：每个组件落库前重新拉服务端 `updatedAt` 与草稿 `baselineUpdatedAt` 比对；被他人改过的**跳过**并计入失败明细，绝不静默覆盖（并发纪律，见记忆 `cpq-concurrent-sessions-and-worktree`）。
  - 用 `runBatch` 聚合「部分失败」，`message` 汇总「成功 X · 失败 Y · 跳过 Z（陈旧）」并列明细。
  - 每个成功落库的组件清掉其草稿 key + 徽标。

### 涉及文件
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（草稿读写挂载、恢复逻辑、脏标记、全局按钮、确认 Modal、保存时序门控）
- 新增 `cpq-frontend/src/pages/component/useComponentDraft.ts`（localStorage 草稿读写 + 防抖 + 枚举所有草稿 + 陈旧检测 + key 剥离/重建，单一职责、可单测）
- 复用 `cpq-frontend/src/services/componentService.ts` 现有保存接口；复用《列表操作规范》的 `runBatch`。
- 可能涉及 `MasterList`（列表项脏标记徽标）。

### 不做（YAGNI）
- 不做后端草稿表 / 跨设备同步。
- 不做草稿版本历史 / 多份草稿。
- 不改 `refreshSnapshotsByComponent` 任何传播逻辑。

---

## 请求 2 — 行键资格实时刷新（撞名约束保留）

### 分析结论（已与用户确认）
- 撞名约束位于 `ComponentDriverService.java:1065-1068`，**只**作用于"无 `basic_data_path` 的 INPUT_TEXT/INPUT_NUMBER 手填字段"：当字段名 == 某 driver 列名时排除作行键。
- 运行时行键取值 `FormulaCalculator.computeDedupKey`（line 473-479）规则是 **driver 列优先，用户输入兜底**。撞名时手填值会被同名 driver 列静默顶替 → 行对齐错且不报错（AP-22/52 族坑）。约束是**正确的防御护栏**。
- 用户场景是**巧合重名**（换字段名即可绕过），无"手填值覆盖同名列"的真实需求。
- **决策：后端撞名约束（取值优先级）不动。** 真正要修的是前端的实时刷新 bug。

### 要修的 bug
- 现象：改字段名（及任何影响行键资格的编辑）后，行键复选框禁用/可勾状态不实时刷新，须退出重进页签。
- 现状：`ComponentManagement.tsx:878-886` 已有 400ms 防抖刷新，依赖 `rowKeySignature`（含 `f.name`，`:872-874`）。链路**看似自洽**，但实测不刷新。

### 排查清单（实现阶段走 systematic-debugging，先复现再改）
对照真实代码，按以下嫌疑**实地复现根因**（F12 + `codegraph_trace` 跑 `row-key-candidates` 端点→`computeRowKeyCandidates` 全链），命中哪个改哪个，不盲改：
1. **driver 未配/非 `$视图` → `haveColumns=false`**：`loadDriverColumnNames` 返空集（`ComponentDriverService.java:1122`），撞名分支 `haveColumns &&`（`:1066`）根本不进 → 改名也不翻转 eligible。
2. **刷新失败 map 被置空**：`refreshRowKeyCandidates` 的 `catch` 吞错 `setRowKeyCandidates({})`（`:867`），或 `handleSelectComponent` 非 NORMAL 分支 `setRowKeyCandidates({})`（`:926`）→ 全字段 disabled，表现为"重进就好"。**最贴合现象，优先排查**。
3. **跨组件 fallback 串号**：`loadDriverColumnNames` 的 `find("sqlViewName", viewName).firstResult()`（`:1127`）无 componentId 维度 → 导入副本同名视图取错列集（命中记忆 `cpq-sqlview-cache-key-needs-component-dim`）。
4. **防抖反复 clear**：连续编辑每次 `clearTimeout`，停手后才落地一次；非"永久不刷新"根因，但需确认停手后确实刷新。
- 修复原则：字段名/`basic_data_path`/`field_type`/`dataDriverPath` 变动后，行键候选必须可靠地在防抖窗口内刷新并以**当前字段名**重建 map；复选框 disabled 状态随之更新，无需重进。

### 后端是否改动
- **非保证零改动**：若根因落在嫌疑 3（跨组件 fallback 串号），需后端给 `loadDriverColumnNames` 的 fallback **加 componentId 维度**（对齐记忆 `cpq-sqlview-cache-key-needs-component-dim`）。其余嫌疑为纯前端修复。最终以复现结论为准。

### 涉及文件
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（`refreshRowKeyCandidates` / `rowKeySignature` / 防抖 useEffect）
- 可能 `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`candidatesByField?.[record.name]` 查找口径，`:423`）
- 可能 `cpq-backend/.../ComponentDriverService.java`（仅当根因=嫌疑3，fallback 加 componentId；**不动**撞名取值优先级逻辑）

### 协议自检
- 涉及 `FieldConfigTable.tsx`（必要时后端 `ComponentDriverService.java`），属 CLAUDE.md 强制 E2E 清单 → 改动后跑**双 spec** `quotation-flow.spec.ts` + `composite-product-flow.spec.ts`。

---

## 请求 3 — 统一「点选取数」（复用并扩展 PathPickerDrawer）

### 决策（已与用户确认 + 评审修订）
- **R1：复用并扩展现有 `PathPickerDrawer`，不新建独立 picker 组件。** 评审证据：`PathPickerDrawer.tsx:69-70` 标注"Task 6.1 手动 Tab 已移除"，它**当前已是"选 SQL 视图 → 选列"点选器**（`selectedSqlViewId` + `selectedSqlColumn`，`:85-86, :175-182`）。新建组件会与之 90% 重复并分叉。
- **抽共享主体（picker body）+ 两种宿主**：把"视图选择 + 列以可点列表/标签铺开点选"抽成纯展示子组件 `ViewColumnPickerBody`，被两类宿主复用：
  - **抽屉宿主**：现有 `PathPickerDrawer`（字段 `basic_data_path` ②、核价模板列 ④）——顺手把列的**下拉框改成可点列表**。
  - **内联宿主**：`DefaultSourceEditor`（默认值来源 ①）——内联渲染 body，满足"下方铺出字段点选"，避免抽屉套抽屉。
- **C2：纯点选，无自由文本手填、无谓词**（给 PathPickerDrawer 加 `disablePredicate` prop，关掉现有 `sqlExtraPredicate` 谓词构造 `:181/:190`）。

### "只列 driver 视图列"纪律——仅用于字段自身 basic_data_path（R2 + R3）
- **R2 数据通路（请求3 的关键前置）**：选择器要"只列 driver 视图列"，必须知道哪张是 driver 视图。`dataDriverPath` 现只在 `ComponentManagement.tsx` 顶层 state，未传到子选择器。**必须显式补 prop 传递链**：`dataDriverPath → FieldConfigTable → PathPickerDrawer/body`，选择器内按 `extractSqlViewName(dataDriverPath)` **过滤到单张 driver 视图**再列其列。此通路是请求2/3 共享前置（见「实施阶段」阶段二）。
- **理论范围（R2 + Y1，已收窄）**：driver 定行轴；字段读 driver 行**自身列**时不发生隐式 JOIN 扇出。"只列 driver 列"**消除的是"字段 basic_data_path 指向 driver 之外别的表导致的 UI 层扇出"那一类"共N项"**。
  - ⚠️ **不宣称"从根杜绝所有共N项"**：AP-22 另有"视图 SQL 内部 JOIN 扇出 / COALESCE 遮蔽 NULL"等共因，发生在**视图定义层**，与 UI 选哪列无关，本方案不覆盖。
- **字段类型豁免（Y1）**：`LIST_FORMULA`（走 `list_formula_config`）/ `cross_tab_ref`（走连表）/ `DATA_SOURCE`（走 binding）等**不经单列 `basic_data_path` 取值**的字段类型，**不受"只列 driver 列"约束**，保留各自现有配置入口，不被新点选纪律误伤。
- **边界**：若组件 `dataDriverPath` 未配置 / 非 `$视图` 形态（无列可列），字段路径点选器显示空态「请先把组件的数据驱动路径配为 SQL 视图，再选字段列」，不报错。

### 默认值来源（①）约束放宽——任意视图列，非只 driver（R3）
- `DefaultSourceEditor` 的 BASIC_DATA 是 `default_source.type` 四选一之一（`:54-55, :299-312`），语义是"INPUT 字段取不到值时**从别处兜底捞值**"，**与字段自身 `basic_data_path` 是两回事**，路径可合法指向非 driver 的查表视图（placeholder `$cp_view.品名`）。
- **决策**：① 处只做**可用性升级**（文本框 → `ViewColumnPickerBody` 内联点选「选视图 → 点列」），**允许选组件的任意 SQL 视图列，不套"只 driver 列"约束**；保持点选、无自由文本/谓词，与全站点选规范一致。
- **prop 通路**：`DefaultSourceEditor` 现无 `componentId`（`:24-32`），需新增 `componentId` prop（从 `FieldConfigTable` 传入，`:627` callsite），供 body 拉该组件的视图列表。

### 落地点（本期做 ①②④，③ 不动）
1. **默认值来源 BASIC_DATA**（`DefaultSourceEditor.tsx:299`）：内联 `ViewColumnPickerBody`，任意视图列。
2. **字段 `basic_data_path`**（`FieldConfigTable.tsx:522` 现有 PathPickerDrawer 调用）：**增强现有 PathPickerDrawer**（列改可点列表 + 传 `dataDriverPath` 做 driver 列过滤 + `disablePredicate`），**不新增抽屉**。
3. ~~组件 driver 路径~~ **本期不动（G1）**：`ComponentManagement.tsx:1124` 已是 PathPickerDrawer=已是"选视图"点选形态，它驱动整页签展开、是高风险入口，改动收益≈0、风险不低；"统一点选"目标它已满足。
4. **核价 Excel 模板列**（`CostingTemplateConfig.tsx:548`）：与②同为 `$视图.列` 形态，复用同一 `ViewColumnPickerBody`（TEMPLATE ownerContext，列**不**做 driver 过滤——模板列本就跨视图取数，沿用现有视图集合）。

### 历史兼容（已与用户确认：保留 + 标注提醒）
- 已存在的、含谓词（如 `元素BOM[元素='Ag'].组成含量`）或字段 `basic_data_path` 指向 driver 之外别的表的旧路径：
  - 新点选器（字段②场景）只提供 driver 列，**无法用新 UI 重选**这类旧路径。
  - 处理：**原样保留旧路径值**（不静默丢弃、运行时照常工作），在该字段处**标注醒目提示**「该路径非 driver 视图列，建议重新配置」（黄色 Tag/图标 + tooltip）。判定需前端拿到 driver 视图列集合做比对（依赖 R2 的 driverPath 通路）。
  - 用户点开点选器若想改，只能改成 driver 列（即重配为合规形态）；不改则旧路径保持。
  - ①（默认值来源，任意视图列）与④（模板列，跨视图）**不**做此收紧，故无此类兼容提示。

### 涉及文件
- 新增 `cpq-frontend/src/pages/component/ViewColumnPickerBody.tsx`（共享展示主体：选视图 + 可点列列表）
- 增强 `cpq-frontend/src/pages/component/PathPickerDrawer.tsx`（列改可点列表、新增 `driverViewPath?` 过滤 + `disablePredicate?` prop；保留谓词能力供其它调用方，**不破坏现有 props**）
- `cpq-frontend/src/pages/component/DefaultSourceEditor.tsx`（新增 `componentId` prop；BASIC_DATA 分支内联 body）
- `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（向 PathPickerDrawer 传 `dataDriverPath`、向 DefaultSourceEditor 传 `componentId`；历史路径标注）
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（把 `dataDriverPath` 传入 FieldConfigTable）
- `cpq-frontend/src/pages/costing/CostingTemplateConfig.tsx`（PathPickerDrawer 列改可点列表，沿用 TEMPLATE 上下文）
- 复用 `cpq-frontend/src/services/componentSqlViewService.ts`

### 协议自检
- 涉及 `FieldConfigTable.tsx` → 强制**双 spec** E2E（`quotation-flow` + `composite-product-flow`）。
- 实现前用 `codegraph_impact` 跑 `basic_data_path` 读取面，确认请求3 只动配置入口、不触碰渲染分支（不踩 AP-44 字段类型联动）。
- 复测报价单视图 + 核价单视图 + 详情页关键 Tab。

---

## 实施阶段（按隐藏依赖拆分）

- **阶段一 · 请求1（自动保存）独立先行**：纯前端、可单测、与请求2/3 无耦合。先交付"防丢失"这个最痛点。
- **阶段二 · 铺 `dataDriverPath → 子组件` 数据通路**：请求2（撞名判定可能依赖 driverPath 状态）与请求3（driver 列过滤 + 历史路径标注）的共同前置，先建一次。
- **阶段三 · 请求2 + 请求3（①②④）同批**：共用阶段二通路，一起做、一起跑双 E2E。请求2 先 systematic-debugging 复现根因再定后端是否改动。

---

## 测试策略

- **请求 1**：`useComponentDraft.ts` 单测（写/读/防抖/枚举/陈旧检测/key 剥离重建/清除）；手动验证切组件、刷新、关标签重开三场景草稿恢复；全局保存全部草稿的确认 Modal + 逐个陈旧复检 + 部分失败/跳过汇总。
- **请求 2**：先复现脚本（改名后 ≤1s 内复选框自动放开，不重进）→ 定根因 → 修；E2E 双 spec。
- **请求 3**：`ViewColumnPickerBody` 单测（列以可点列表呈现、点选返 `$V.列`；字段场景只列 driver 列、默认值/模板列场景列全部视图）；4→3 处落地点手动验证；历史非合规路径"保留+标注"验证；E2E 双 spec + 报价/核价/详情三视图复测。
- **强制自检**（CLAUDE.md）：前端 `tsc --noEmit` 0 错误 + 每个改动 `.tsx` 走 Vite 200；协议级改动跑 E2E。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 全局保存全部草稿批量触发 snapshot 传播打爆后端 | 逐个顺序落库，非并发；`runBatch` 聚合失败 |
| 批量落库静默覆盖他人改动 | 逐个落库前复检 `updatedAt` 陈旧，跳过+计入失败（Y3）|
| localStorage 草稿与服务端版本漂移 | `baselineUpdatedAt` 陈旧检测 + 显式选择「仍恢复 / 放弃」 |
| 恢复后用旧 rowKeyCandidates 写错行键 | 恢复后等候选刷新完再允许保存（Y4-b）|
| 请求2 改不到真根因 | 先 systematic-debugging 复现 + F12/codegraph_trace 确认，4 条嫌疑清单逐一排除；后端非保证零改动 |
| "只列 driver 列"被误当成"杜绝所有共N项" | 范围收窄为"消除字段够别的表的 UI 扇出"；视图 SQL 层共因不在范围（Y1）|
| 特殊字段类型被点选纪律误伤 | LIST_FORMULA/cross_tab_ref/DATA_SOURCE 豁免，保留各自入口（Y1）|
| 默认值来源被过度收紧砍掉合法用法 | ① 允许任意视图列，不套 driver 约束（R3）|
| 扩展 PathPickerDrawer 影响其它调用方 | 新增 prop 全部可选、默认行为不变；谓词能力保留；逐处验证 + 双 spec 兜底 |

## 关联文档
- `docs/配置方法论-合并版.md` §3.1（default_source）/ §6 症状速查 / §9 隐式 JOIN / §11 字段类型联动 / §12 多行展开
- `docs/反模式.md` AP-22 / AP-37 / AP-40~44 / AP-52 / AP-54
- `docs/列表操作规范.md`（工具栏动作 + runBatch + 危险动作 Modal）
- `docs/E2E测试方法.md`
- 记忆 `cpq-sqlview-cache-key-needs-component-dim` / `cpq-concurrent-sessions-and-worktree`
