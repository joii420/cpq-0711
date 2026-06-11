# 数据清理 + 组件导入导出按钮补回 + 停用红色背景 — 设计方案

> 日期：2026-06-11 ｜ 状态：已与用户确认，待实施
> 来源：用户 5 项需求 + 10 轮澄清（详见本文「决策记录」）

## 背景与目标

用户要对 CPQ 系统做一次「测试数据收敛 + 两处 UI 修复」：

1. 组件管理只保留 3 个目录及其组件，其余目录/组件/引用模板全删
2. 清空历史报价单 + 核价单实例（保留 V6 基础资料），用户重新导入
3. 客户管理只保留 3 个真实客户，删除测试客户
4. 补回组件管理「导入/导出」按钮（Master-Detail 重构后丢失）
5. 停用状态的组件卡片改为红色背景

## 决策记录（10 轮澄清结论）

| # | 议题 | 决策 |
|---|------|------|
| 1 | 删除执行方式 | A — 直接在线上库跑 SQL 硬删除 |
| 2 | 删除前备份 | B — 不备份 |
| 3 | 「引用的模板」范围 | A — 只删引用了被删组件的模板 |
| 4 | 混合引用模板 | A — 引用到任意被删组件即删整张模板 |
| 5 | 报价单清理程度 | B — 报价单 + 核价单两条线 |
| 6 | 客户连带数据 | A — 删定价策略；保留模板若绑被删客户则 customer_id 置空 |
| 7 | 导入导出按钮放哪 | A — 每个目录标题行右侧 |
| 8 | 停用视觉样式 | A — 淡红底 + 左侧红色竖条 + 保留「（已停用）」小字 |
| 9 | 「…组件」目录去留 | B — 严格按字面只留「报价模板/报价模板V2/核价模板」3 个 |
| 10 | 是否清 V6 基础资料 | A — 不清，保留 |

## 数据库真实状态勘探（2026-06-11 只读）

- 组件目录：14 个；组件总数 162
- 客户：31 个
- `quotation`：574 条；`costing_sheet`：0；`costing_summary`：4
- V6 基础资料：`costing_part_material_bom` 6、`costing_material_price` 2 等少量
- 模板：122 张；`template_component` 关联 787 行
- 指向 `component.id` 的 FK：`template_component.component_id`、`component_sql_view.component_id`

---

## 任务 1 — 组件 / 目录 / 模板清理

**保留目录（按字面严格匹配，决策 9-B）**
- 报价模板（7 组件）`79be0132-88ba-4afe-a1c9-fa294c62642c`
- 报价模板V2（9 组件）`4007d593-8ba8-4ecb-b134-cb1efbce3722`
- 核价模板（7 组件）`aa6f8ee7-a1cf-4d99-9fcb-42fc32268076`

**删除目录（11 个 / 约 139 组件）**：报价单V0529、报价单模板、报价模板组件、报价模板组件V2、报价模板组件V3-Excel结构、核价模板组件、核价模板组件V5-Excel结构、施耐德、施耐德2、核价组件、选配模板组件

**模板删除规则（决策 3-A / 4-A）**：模板在 `template_component` 中有**任意一行** `component_id` 指向被删组件 → 整张模板删除（含混合引用）。

**连带清理顺序（FK 安全）**：
1. 先删被删组件相关 `component_sql_view`
2. 计算「待删模板集」= `template_component` 中引用被删组件的 `template_id` 去重
3. 删待删模板的子表：`template_component`、`template_sql_view`、`template_global_variable_binding`、`product_template_binding`
4. 删待删模板本体（`template`，含其 snapshot 列）
5. 删被删组件的剩余 `template_component`（保留模板里若引用了被删组件——按规则该保留模板也会进待删集，故此步主要兜底）
6. 删被删组件（`component`）
7. 删 11 个空目录（`component_directory`）

> 执行前 `SELECT count` 打印：待删组件数、待删模板数、各连带子表行数，确认后再 COMMIT。

---

## 任务 2 — 清空历史报价单 + 核价单实例

**删除（子表 → 主表顺序）**
- `quotation_line_component_data`、`quotation_line_composite_process`、`quotation_line_process`、`quotation_line_item_snapshot`、`quotation_component_sql_snapshot`、`quotation_line_item`
- `quotation_approval`、`quotation_withdraw_request`、`quotation_view_structure`
- `quotation`（574）
- `costing_summary_result`、`costing_summary_override`、`costing_summary`、`costing_sheet`

**保留（V6 基础资料，决策 10-A）**：`costing_part_*` 8 张零件表 + `costing_material_price` / `costing_element_price` / `costing_exchange_rate` / `costing_price_version`

> 实际子表清单以执行时 `information_schema` FK 查询为准，避免漏表。

---

## 任务 3 — 客户清理

**保留（3 个）**：苏州西门子 `9aee3d9d`、罗克韦尔 `3027d83b`、施耐德 `8de8f8b0`
**删除（28 个）**：CLR / DDL / E2E / MATCH-* / Test* / 各 *Test Customer 等测试客户

**连带（决策 6-A）**
1. 删被删客户的 `pricing_rule`、`pricing_strategy`
2. 保留模板若 `customer_id` 指向被删客户 → `UPDATE template SET customer_id = NULL`（保留模板本体）
3. 删 `customer`

> 任务 2 已先清空所有 `quotation`（其 customer_id/template_id 引用随之消失），故删客户/模板无悬空报价引用。**执行顺序：任务 2 → 任务 1 → 任务 3。**

---

## 任务 4 — 导入/导出按钮补回

**根因**：commit `0722079`（组件管理改 Master-Detail 双栏）弃用 `ComponentTree.tsx` 不再挂载，导出按钮 + 导入抽屉原长在其中，随之消失。后端与 service 层完好：
- 后端 `ComponentDirectoryResource`：`/{id}/export`、`/{id}/import`、`/{id}/import/commit`
- 前端 `componentService.exportDirectory / importPreview / importCommit`
- 抽屉 `ComponentImportDrawer.tsx`

**方案（决策 7-A，按目录粒度）**：在 `ComponentManagement.tsx` 的 `MasterList.renderDir` 中，目录标题行 `cmm-dir-head` 右侧加两个 small 按钮：
- 「导出」`onClick={() => componentService.exportDirectory(dir.id)}`（阻止冒泡，勿触发目录折叠）
- 「导入」打开 `ComponentImportDrawer`，`targetDirId = dir.id` / `targetDirName = dir.name`；导入成功后 `onRefresh()` 刷新列表
- 仅前端布线；不动后端 / service / 抽屉本体

**自检**：`tsc --noEmit` 0 错；`curl /src/pages/component/ComponentManagement.tsx` → 200；手动验导出下载 + 导入抽屉打开。

---

## 任务 5 — 停用组件红色背景

`ComponentManagement.tsx` 的 `MasterList.renderCard`：当 `comp.status === 'DISABLED'` 时给卡片附加 `cmm-card-disabled` class，保留现有「（已停用）」小字。

`styles.css` 新增：
```css
.cmm-card-disabled { background: #fff1f0; border-left: 3px solid #ff4d4f; }
.cmm-card-disabled.active { background: #ffe7e5; }
```
（仍可点击进入重新启用；红条 + 淡红底，醒目不刺眼。）

**自检**：`tsc --noEmit` 0 错；`curl /src/...ComponentManagement.tsx` → 200；停用一个组件目视红底红条。

---

## 实施顺序与隔离

1. **任务 1～3（数据 SQL）**：主工作区直接执行，单事务 + 先打印 will-delete count → 用户确认 → COMMIT。
2. **任务 4～5（代码）**：按 CLAUDE.md 强制规范开**隔离 worktree 特性分支**开发，复用已运行的 dev server 自检，完成后由用户验收再合并清理。

## 验收标准

- 组件管理只剩 3 个目录及其 23 组件；其余目录/组件/引用模板消失
- 报价单列表为空（574→0）；V6 基础资料仍在
- 客户管理只剩 3 个真实客户
- 每个目录标题行有「导出/导入」按钮，导出能下载 JSON、导入抽屉能打开并预览
- 停用组件卡片淡红底 + 左红条
