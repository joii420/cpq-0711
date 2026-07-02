# 驱动视图指定改由 SQL 视图列表工具栏完成 — 设计方案

- 日期：2026-07-01
- 状态：已评审通过，待写实现计划
- 相关代码：`ComponentManagement.tsx` / `SqlViewListPanel.tsx` / `SqlViewConfigDrawer.tsx` / `PathPickerDrawer.tsx` / `Component`(实体) / `ComponentResource` / `ComponentService`

## 1. 背景与目标

组件管理详情页现有一个自由输入框「数据驱动路径(可选)：」，用户在里面填 `$视图名`（driver expansion 的行来源）。该字段落在 `component.data_driver_path`（TEXT），语义上可填 `$view` / `$view[谓词].列` / 跨组件 `$$组件code.视图名`。

现状事实（已核对代码 + 生产 DB）：

- **存量数据全部是干净的 `$视图名`**：25 条非空 `data_driver_path` 均为 `$xxx_view` 形态，无谓词、无列后缀、无 `$$` 跨组件形式；每条都 1:1 命中本组件 `component_sql_view` 里同名的一张视图记录。
- `component_sql_view` 表已有 `status`(ACTIVE/INACTIVE) 字段，且 **26 条视图全部 ACTIVE**；新建/编辑抽屉 `SqlViewConfigDrawer` **本来就没有**让用户改 status 的控件（代码里默认写 ACTIVE）。
- `SqlViewListPanel` 完全自包含，仅接收 `componentId`，视图增删改/Dry-Run 均即时调 `componentSqlViewService`；它拿不到组件草稿里的 `dataDriverPath`（该 state 在父组件 `ComponentManagement.tsx`，随「保存组件」落库）。
- 大量下游读 `component.dataDriverPath`：模板 snapshot 拷贝、`ComponentDriverService`、`DataLoader`、导入导出等。

**目标**：移除自由输入框，改为在 SQL 视图列表里指定「哪张视图作为本组件的驱动」，全组件有且只能有一张驱动。

## 2. 已确认的关键决策

1. **「驱动」是与 `status` 并列的新维度**，不是复用/改造 `status`。多张视图可同时 ACTIVE 并被字段 `basic_data_path` 引用；「是否驱动」与「是否 ACTIVE / 能否被字段引用」互不影响。
2. **驱动只能是本组件自己的视图**，不支持跨组件 GLOBAL 视图当驱动（存量无 `$$` 形式，安全）。
3. **交互走「工具栏动作 + 只读标签列」（方案 B）**，不用行内勾选框——遵守 CLAUDE.md 列表操作规范「行内不放变更控件」（PR 评审强制项）。
4. **移除 status 的一切用户可改路径**：全部视图默认且恒为「启用」，用户不可禁用，只能删除；列表**不再展示**「状态」列。
5. **首个视图默认驱动**：新建视图时，仅当组件当前无驱动才自动设为驱动。
6. **删除当前驱动视图 → 自动清空组件驱动。**

## 3. 交互设计（前端）

### 3.1 组件详情页 `ComponentManagement.tsx`

- **移除**「数据驱动路径(可选)：」输入框、「选择路径」按钮，以及该处配套的 driver 用途 `PathPickerDrawer`（`driverPickerOpen` 相关状态与调用）。
- 同一配置行里的「核价 BOM 递归展开」开关**保留**。
- `dataDriverPath` state **保留**（父组件仍需读它，用于字段路径按 driver 视图过滤 `driverViewPath` 等只读用途）；但**不再随「保存组件」写库**（见 §4 写者唯一性）。
- 面板切换驱动后，通过回调同步父组件的 `dataDriverPath` state（供上述只读用途实时刷新，并驱动 `refreshRowKeyCandidates`）。

### 3.2 SQL 视图列表 `SqlViewListPanel.tsx`

列变化：

- **移除**「状态」列。
- **新增**只读「驱动」列：当前驱动视图显示 🟢`驱动` 标签，其余显示 `—`。判断依据 `'$' + record.sqlViewName === currentDriverPath`。

工具栏新增两个动作（`SelectableTable` actions）：

- **设为驱动**：`enabledWhen` = 恰好选中 1 行且该行当前不是驱动；否则返回禁用原因字符串。点击调即时端点把该视图设为驱动（原驱动自动降级），成功后 `loadViews()` + 回调通知父组件。
- **取消驱动**：`enabledWhen` = 恰好选中 1 行且该行当前就是驱动。点击置空组件驱动。

面板需要知道「当前驱动」：由父组件把 `currentDriverPath`（= `component.dataDriverPath`）作为 prop 传入，并提供 `onDriverChange(newPath)` 回调。

### 3.3 新建/编辑抽屉 `SqlViewConfigDrawer.tsx`

- 交互不变（本就无 status 控件）。创建恒写 `status='ACTIVE'`；编辑保持 ACTIVE（保险起见显式固定为 ACTIVE，不依赖 editingView 回填）。

## 4. 存储与后端（推荐方案：不加新列）

- **`component.data_driver_path` 仍是唯一真源**，值形态维持 `$视图名`。**存量零迁移**。
- 「谁是驱动」= 面板内判断 `'$' + sqlViewName === component.data_driver_path`。
- 新增**即时端点** `PUT /api/cpq/components/{id}/driver-view`，body `{ "sqlViewName": string | null }`：
  - 非 null：校验该视图存在且属于本组件，写 `data_driver_path = '$' + sqlViewName`。
  - null：清空 `data_driver_path`。
  - 返回更新后的组件（或至少新的 `dataDriverPath`）供前端同步。
- **单选天然成立**（就一个字符串字段），无需额外唯一约束或索引。
- **下游全部零改动**（snapshot 拷贝 / `ComponentDriverService` / `DataLoader` / 导入导出继续读 `data_driver_path`）。
- `status` 列**保留在 DB**（恒 ACTIVE），使 `PathPickerDrawer` 等处既有 `.filter(v => v.status === 'ACTIVE')` 继续通过。
- **写者唯一性**：`data_driver_path` 不再随「保存组件」落库——从组件 update payload 中移除该字段的写入（后端 update 对该字段不再覆盖，或前端不再发送）。仅由上面的即时端点写，避免双写覆盖导致驱动丢失。

> **备选（已否决）**：给 `component_sql_view` 加 `is_driver` 布尔列 + 每组件一个 `true` 的部分唯一索引。否决理由：引入与 `data_driver_path` 并存的第二真源、需改造下游读取或维护同步、且要迁移；收益不抵一致性风险。

## 5. 驱动生命周期规则

1. **首个视图默认驱动**：`POST` 新建视图成功后，若 `component.data_driver_path` 当前为空，则自动把新视图设为驱动（服务端在创建事务内设置，或前端创建成功后调 driver-view 端点）。已有驱动则不抢。
2. **删除当前驱动视图**：删除成功后若被删视图正是当前驱动，自动清空 `data_driver_path`；组件回到「无驱动 = 产品级单行」。删除确认 Modal 在原有「字段引用」提示之外，追加一句「这是当前驱动视图，删除后本组件将无驱动」。
3. 无停用路径（用户不能禁用视图），不涉及「停用当前驱动视图」分支。

## 6. 自检 / 影响面

- 触及 `ComponentManagement.tsx` 组件保存链路 → 协议级改动，**跑 E2E `quotation-flow.spec.ts`**，须 `1 passed` + `'加载中' final count = 0` + 8 Tab `加载中=0`（验证驱动展开不受影响）。
- 前端每个改动 `.tsx`：`tsc --noEmit` 0 错误 + `curl` Vite 200。
- 后端加端点 + 调整 update：touch 重启 → `curl` 端点期望 200/401（非 500）。
- **存量验证**：现有 25 条 `$view` driver 在新列表里正确显示为「驱动」标签；随机挑一个组件在报价单里驱动展开行数正常。
- 反模式对照：本改动不新增/改动 `field_type`，不触发 AP-44 的 17 检查点；但改了组件保存链路，按 E2E 纪律执行。

## 7. 验收标准

- [ ] 组件详情页不再有「数据驱动路径」输入框与「选择路径」按钮；「核价 BOM 递归展开」开关仍在。
- [ ] SQL 视图列表无「状态」列，有只读「驱动」列，当前驱动视图显示驱动标签。
- [ ] 工具栏「设为驱动 / 取消驱动」按选中态正确启用/禁用（含禁用原因 tooltip），点击即时生效并刷新列表。
- [ ] 设为某视图驱动后，原驱动自动降级；组件只有一张驱动。
- [ ] 新建首个视图（组件原无驱动）自动成为驱动；组件已有驱动时新建视图不抢。
- [ ] 删除当前驱动视图后组件驱动被清空，且删除确认有对应提示。
- [ ] 存量 25 条 driver 显示与展开均正确，`data_driver_path` 值不变。
- [ ] E2E `quotation-flow.spec.ts` 通过，`加载中 = 0`。
