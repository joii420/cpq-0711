# 组件管理编辑体验三项优化 — 设计方案

> 日期：2026-06-12
> 分支：`worktree-feat+component-editor-ux`
> 状态：设计已与用户逐项确认，待写实现计划

## 背景与目标

组件管理（`cpq-frontend/src/pages/component/`）当前编辑体验有三处痛点，本方案一并解决：

1. **编辑易丢失**：组件详情态（字段 / 公式 / driver 路径 / 行键 / Excel 列）是纯 React state，只有点「保存」才落库。切换组件、误刷新、误关标签页都会静默丢弃未保存的编辑。
2. **行键资格不实时**：给字段配行键时，若字段名与 driver 列撞名会被禁用；改了字段名后，行键复选框的禁用状态不实时刷新，必须退出重进页签才更新。
3. **取数配置体验差**：默认值来源的「基础数据」类型只有手填输入框；字段路径选择各处不统一。需做成统一的「选视图 → 点字段」点选交互。

三项在**同一个隔离 worktree** 内一起开发。

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
- **自动恢复**：`handleSelectComponent` 加载组件后，检测该组件有草稿：
  - 草稿 `baselineUpdatedAt` == 服务端当前 `updatedAt` → **静默恢复**编辑态，顶部显示提示条「检测到未保存的修改，已自动恢复 · [放弃草稿]」。
  - 草稿 `baselineUpdatedAt` != 服务端 `updatedAt`（服务端被改过，草稿陈旧）→ 提示条改为「该组件在别处已更新，本地草稿可能过期 · [仍恢复草稿] / [放弃草稿]」，默认**加载服务端版本**，让用户显式选择。
- **草稿清除**：点「保存」成功落库后，清掉该组件草稿 key。「放弃草稿」按钮亦清除并重载服务端版本。
- **脏标记**：有草稿 / 有未保存改动时，组件详情头部组件名旁 + 左侧列表对应项显示小圆点徽标。

### 全局「保存全部草稿 (N)」
- 组件管理页**顶部工具栏**新增按钮，徽标显示有草稿的组件数；无草稿时**禁用**（置灰 + hover tooltip 原因，遵循《列表操作规范》——不用 `if return null` 隐藏）。
- 点击 → **弹确认 Modal**（遵循《列表操作规范》危险/批量动作走 Modal 列出所选项）：
  - 列出这 N 个有草稿的组件（名称 + code + 草稿时间），每项可勾选/取消（默认全选）。
  - 确认 → 对勾选项**逐个顺序**调原有保存接口落库（顺序而非并发，避免批量 snapshot 传播打爆后端）。
  - 用 `runBatch` 聚合「部分失败」，`message` 汇总「成功 X · 失败 Y」并列失败明细。
  - 每个成功落库的组件清掉其草稿 key + 徽标。

### 涉及文件
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（草稿读写 hook、恢复逻辑、脏标记、全局按钮、确认 Modal）
- 新增 `cpq-frontend/src/pages/component/useComponentDraft.ts`（localStorage 草稿读写 + 防抖 + 枚举所有草稿，单一职责、可单测）
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
- **决策：后端撞名约束不动。** 真正要修的是前端的实时刷新 bug。

### 要修的 bug
- 现象：改字段名（及任何影响行键资格的编辑）后，行键复选框禁用/可勾状态不实时刷新，须退出重进页签。
- 现状：`ComponentManagement.tsx:878-886` 已有 400ms 防抖刷新，依赖 `rowKeySignature`（含 `f.name`）。理论上应生效但实测不刷新。
- **实现阶段走系统化调试**实地复现根因，F12 验证。三个嫌疑：
  1. 候选 map 旧键名残留：`refreshRowKeyCandidates` 按 `c.fieldName` 建 map（line 864），改名后新名查不到旧 map → `eligible=false`；需确认刷新是否真正触发并以新名重建。
  2. 防抖被反复清：`rowKeySignature` 频繁变动导致 `setTimeout` 反复 clear，刷新一直不落地。
  3. 候选接口某条件返空 → `catch` 吞错 `setRowKeyCandidates({})` → 全禁用。
- 修复原则：字段名/`basic_data_path`/`field_type`/`dataDriverPath` 变动后，行键候选必须可靠地在防抖窗口内刷新并以**当前字段名**重建 map；复选框 disabled 状态随之更新，无需重进。

### 涉及文件
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（`refreshRowKeyCandidates` / `rowKeySignature` / 防抖 useEffect）
- 可能 `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`candidatesByField?.[record.name]` 查找口径）
- **不改**后端 `ComponentDriverService.java` 撞名逻辑。

### 协议自检
- 此区域涉及 `FieldConfigTable.tsx`，属 CLAUDE.md 强制 E2E 清单文件 → 改动后跑 `quotation-flow.spec.ts`。

---

## 请求 3 — 全站统一「点选取数」（C2：纯点选，只列 driver 视图列）

### 决策（已与用户确认）
- **C2**：纯点选，**无自由文本手填、无谓词**。点选 UI **只列出组件 driver 视图（`$V`）的列**。
- 工具层强制"字段路径只能点 driver 视图自己的列"这条配置纪律 → 字段视图粒度恒等于 driver 粒度 → 字段读的是当前 driver 行自己的列，不发生隐式 JOIN 扇出 → **从根上杜绝"X（共N项）"**。
- 理论依据：driver 定行轴，字段读 driver 行自身列时永远单值；"共N项"只在字段够到比 driver 更细的别的表时发生。只列 driver 列即从 UI 杜绝该可能。

### 新增可复用组件
- `cpq-frontend/src/pages/component/ViewColumnPicker.tsx`（暂名）：
  - 输入：driver 视图标识（`$V`，或 owner 上下文）。
  - UI：顶部显示当前 driver 视图名（通常不可改，因为字段只能选 driver 列）；下方把该视图所有列**铺成可点列表/标签**，点一下即选中。
  - 输出：`$V.列名` 路径 + 展示 label。
  - 数据来源复用 `componentSqlViewService`（取视图列 `declaredColumns`），与 `PathPickerDrawer` 同源。
  - **无手填输入框、无谓词构造、无 manual tab。**
  - **边界**：若组件 `dataDriverPath` 未配置 / 非 `$视图` 形态（无列可列），点选器显示空态提示「请先把组件的数据驱动路径配为 SQL 视图，再选字段列」，不报错。

### 落地 4 处（全站一致）
1. **默认值来源 BASIC_DATA**（`DefaultSourceEditor.tsx`）：BASIC_DATA 分支的 `<Input>` 换成 `ViewColumnPicker` **内联**（贴用户最初描述：选视图 → 下方字段点选）。
2. **字段 `basic_data_path`**（`FieldConfigTable.tsx`）：表格行内塞不下整块 UI，故从行点开一个 **Drawer**（遵循 Drawer-over-Modal 规范），Drawer 内嵌 `ViewColumnPicker`；替换该处对 `PathPickerDrawer` 的调用（line 522 附近）。
3. **组件 driver 路径**（`ComponentManagement.tsx` line 1124）：driver 是"选一张视图"，点选"选视图"形态统一。
4. **核价 Excel 模板列**（`CostingTemplateConfig.tsx` line 548）：VARIABLE 来源选 `$视图.列`（模板视图上下文），并入点选。

> 注：driver 路径（③形态=选视图）与字段/默认值/模板列（=选 `$视图.列`）形态略不同，但都走"点选、不手填"的统一规范。`ViewColumnPicker` 设计上支持"只选视图"与"选视图+列"两种模式（由调用方传 `mode`）。

### 历史兼容（已与用户确认：保留 + 标注提醒）
- 已存在的、含谓词（如 `元素BOM[元素='Ag'].组成含量`）或引用 driver 之外别的表的旧 `basic_data_path` / 模板列路径：
  - 新点选器只提供 driver 列，**无法用新 UI 重选**这类旧路径。
  - 处理：**原样保留旧路径值**（不静默丢弃、运行时照常工作），在该字段/列处**标注醒目提示**「该路径非 driver 视图列，建议重新配置」（黄色 Tag / 图标 + tooltip）。
  - 用户点开点选器若想改，则只能改成 driver 列（即重配为合规形态）；不改则旧路径保持。

### 涉及文件
- 新增 `cpq-frontend/src/pages/component/ViewColumnPicker.tsx`
- `cpq-frontend/src/pages/component/DefaultSourceEditor.tsx`
- `cpq-frontend/src/pages/component/FieldConfigTable.tsx`
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`
- `cpq-frontend/src/pages/costing/CostingTemplateConfig.tsx`
- 复用 `cpq-frontend/src/services/componentSqlViewService.ts`
- **不动** `PathPickerDrawer.tsx` 本体（其谓词/manual/legacy 能力仍被保留，但上述 4 处改用 `ViewColumnPicker`）；若 4 处改完 `PathPickerDrawer` 再无引用，作为收尾可评估是否移除（本期默认保留，避免波及）。

### 协议自检
- 涉及 `FieldConfigTable.tsx` → 强制 E2E `quotation-flow.spec.ts`。
- 涉及取数路径渲染 → 复测报价单视图 + 核价单视图关键 Tab。

---

## 测试策略

- **请求 1**：`useComponentDraft.ts` 单测（写/读/防抖/枚举/陈旧检测/清除）；手动验证切组件、刷新、关标签重开三场景草稿恢复；全局保存全部草稿的确认 Modal + 部分失败汇总。
- **请求 2**：复现脚本（改名后 ≤1s 内复选框自动放开，不重进）；E2E `quotation-flow.spec.ts`（FieldConfigTable 协议文件）。
- **请求 3**：`ViewColumnPicker` 单测（只列 driver 列、点选返 `$V.列`）；4 处落地点手动验证；历史非合规路径"保留+标注"验证；E2E `quotation-flow.spec.ts` + 报价单/核价单视图复测。
- **强制自检**（CLAUDE.md）：前端 `tsc --noEmit` 0 错误 + 每个改动 `.tsx` 走 Vite 200；协议级改动跑 E2E。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 全局保存全部草稿批量触发 snapshot 传播打爆后端 | 逐个顺序落库，非并发；`runBatch` 聚合失败 |
| localStorage 草稿与服务端版本漂移 | `baselineUpdatedAt` 陈旧检测 + 显式选择「仍恢复 / 放弃」 |
| 请求2 改不到真正根因（现有防抖看似已存在） | 实现走系统化调试，先实地复现 + F12 确认根因再改，不盲改 |
| C2 收紧后历史路径配不出 | 保留+标注，不丢数据；运行时不受影响 |
| `ViewColumnPicker` 替换 `PathPickerDrawer` 影响 4 处不同上下文 | 单一组件支持 `mode`（选视图 / 选视图+列）；逐处验证；E2E 兜底 |

## 关联文档
- `docs/配置方法论-合并版.md` §3.1（default_source）/ §6 症状速查 / §9 隐式 JOIN / §11 字段类型联动 / §12 多行展开
- `docs/反模式.md` AP-22 / AP-37 / AP-40~44 / AP-52
- `docs/列表操作规范.md`（工具栏动作 + runBatch + 危险动作 Modal）
- `docs/E2E测试方法.md`
