# 报价单复制 — 模板选择 + 跨模板用户输入迁移 设计

> 日期：2026-06-16
> 模块：报价单（Quotation）/ 复制功能
> 状态：设计待用户复审（已吸收架构评审 + 用户决策）

## 1. 背景与现状

当前「复制」(`QuotationList.tsx` → `quotationService.copy(id)` → `QuotationService.copy(UUID id)`)直接克隆出一份**相同模板**的报价单并跳转编辑，没有模板选择步骤。

**现有 `copy()` 的已知缺陷**（架构评审核实，`QuotationService.java:1234-1264`）：
- 复制行项目时只搬了 `productId / templateId / productAttributeValues / subtotal / sortOrder`；
- **未复制 `compositeType` / `parentLineItemId`** → 组合产品的父子链断裂；
- **未复制也未重建 4 份行级值快照** `quote_card_values / quote_excel_values / costing_card_values / costing_excel_values` → 新单渲染值为空；
- 设计层面错误依赖 `row_data` / `snapshot_rows`，而它们在「报价单整份快照」体系下已退役（基线 §4.4 / §Phase4）。

本设计在实现「换模板复制」的同时一并修正上述缺陷。

## 2. 数据模型要点（评审核实修正）

- **报价模板挂在单据头**：`Quotation.customerTemplateId`（报价侧）、`Quotation.costingCardTemplateId`（核价侧）。整单统一一个头模板；`QuotationLineItem.templateId` 存在但兜底取 `customerTemplateId`，渲染/重算（`CardSnapshotService`）按头模板字段走，不读行级 `templateId`。
- **渲染/导出权威源 = 行级 4 份值快照**（`QuotationLineItem.quoteCardValues` 等），由 `CardSnapshotService.snapshotLineValues(li)` 按头模板计算；`row_data` / `snapshot_rows` 已退役。
- `QuotationLineItem` 含 `compositeType`（SIMPLE / COMPOSITE / PART）+ `parentLineItemId`（PART 指向父行），表达组合产品父子结构（V169）。
- 组合产品与单一产品**走同一套数据源 SQL 查询管线**（统一智能视图，driver 按 `compositeType` 决定查"单料件"还是"父料号下所有子件"）。无字段级双轨逻辑（双轨字段方案已废弃）。
- 模板侧 `Template`（`template_kind` = QUOTATION / COSTING、`status`、`components_snapshot`）+ `TemplateComponent`（`componentId` + `fieldsOverride`）持有页签集合与字段定义（字段 key / 显示名 / 是否公式）。
- 业务约束（用户确认）：同一模板内 `componentId` 唯一、页签名(tabName)唯一 → 页签配对天然一对一。

## 3. 需求与设计决策（用户确认）

1. 选中一个报价单 → 点「复制」→ 弹出**模板选择抽屉**，默认选中源报价单所用模板。
2. 模板列表 = **仅 QUOTATION 且 PUBLISHED**（评审收敛；COSTING 当报价模板会走错渲染分支，核价复制需求另立）。
3. 确认后用所选模板创建新报价单（DRAFT），跳转编辑页。
4. **整单统一一个模板**：换模板 = 改新单单据头 `customerTemplateId`（及按需 `costingCardTemplateId`）。
5. **迁移原则（核心修订）**：迁移的是**用户输入值**（INPUT 列值 / editRows / 手动新增行 / 选中料件·选配等用户决策），**driver 基础层与公式层一律由新模板重算、不搬运**。换模板后 4 份值快照清空并用新模板重建。
6. 页签配对：**先 componentId，其次 tabName**，一对一；新模板独有页签 → 空数据；源单独有页签 → 丢弃。
7. 字段映射：**默认只做字段标识(key)/名称(name)精确匹配**，未匹配留空；新模板公式字段不赋值；模糊匹配（显示名兜底 + 同名按序配对）列为**可选增强**，非默认。
8. **组合产品一视同仁**：复制时连同 `compositeType` + `parentLineItemId` 一起拷并把 `parentLineItemId` 重映射到新行 id；不为组合产品分叉。

## 4. 方案取舍

**匹配 + 重算逻辑放后端（推荐）**：前端只选模板传 `templateId`；页签配对、用户输入值映射、模板字段切换、4 份快照重建全在后端 `QuotationService.copy(id, templateId)` 内完成（事务一致、字段定义/公式类型在后端最权威、不与渲染层 AP-44 协议脱节）。

## 5. 详细设计

### 5.1 前端（`QuotationList.tsx` + 新建模板选择抽屉）

- 「复制」动作打开**模板选择抽屉**（Ant Design `Drawer`，`placement="right"`，遵守「Drawer 替代 Modal」规范）。
- 抽屉：列出**所有 QUOTATION + PUBLISHED 模板**，可按名称搜索；**默认选中源单 `customerTemplateId`**；展示模板名/版本辅助选择。
- 确认 → `quotationService.copy(sourceId, { templateId })` → `message.success('复制成功')` → 跳转 `/quotations/{newId}/edit`。

### 5.2 后端 `QuotationService.copy(UUID id, UUID templateId)`

- 端点：`POST /api/cpq/quotations/{id}/copy`，body `{ "templateId": "<uuid>" }`。向后兼容：缺省 templateId → 沿用源单 `customerTemplateId`（即"同模板复制"，但同样走下方修正后的重建流程，不复刻旧缺陷）。
- **单据头**：复制源单字段，`status='DRAFT'`，新编号，`sourceQuotationId` 指向源单；**`customerTemplateId` 改为传入 templateId**（及按需 costing 模板）。
- **行项目**：复制 `productId / productAttributeValues / processes / sortOrder`，并**新增复制 `compositeType`**；`parentLineItemId` 在所有行建好后按 `源行id → 新行id` 映射表重链；行级 `templateId` 同步设为新模板。
- **页签用户输入值迁移**（每行项目）：
  1. 读新模板页签集合（component + 字段定义：key / name / 是否公式）。
  2. **页签配对**：先 componentId、后 tabName，一对一；未配上的新页签 → 空输入；源独有页签 → 丢弃。
  3. **字段映射**：对配对页签，按字段 key/name 精确匹配，从源用户输入值取值填入新模板对应字段；公式字段不赋值；未匹配字段留空。映射只作用于**用户输入层**（不迁移 driver 展开的基础行）。
  4. componentId 完全一致时字段天然全一致 → 输入值整体平移。
- **快照重建**：所有行项目建好后，对每个行项目**清空 4 份值快照并调 `CardSnapshotService.snapshotLineValues(li)`（或等价重刷）用新模板重算重建**；不依赖前端打开时序（同时修复现有"复制后快照为空"缺陷）。

### 5.3 边界与重算
- `subtotal` / `totalAmount`：换模板复制时归零（或标记待重算），由快照重建/公式引擎回填，避免列表展示旧模板陈旧金额。
- driver 字段：按迁移过来的用户选择（如选中料件）由新模板重新展开。
- 字段"是否公式 / key / name"取自**新模板** `TemplateComponent.fieldsOverride` / `components_snapshot`。

## 6. 影响面与强制自检

- 后端：`QuotationService.copy`（签名变更 + 重建逻辑）、`QuotationResource` copy 端点（接受 body）、复用 `CardSnapshotService.snapshotLineValues`。
- 前端：`QuotationList.tsx`、`quotationService.copy`、新建模板选择抽屉。
- **协议级改动**（触及 `QuotationService` + 值快照重建）：按 CLAUDE.md 强制跑 E2E `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）；验证复制后编辑页"加载中=0"、刷新 3 次行数稳定（AP-51）、列小计=行值（AP-50）、组合产品父子链完好。
- PR 附：复制前后两单的关键 Tab 渲染截图 + E2E 通过证据。

## 7. 关联但独立的清理任务（不并入本功能）
- **废弃的字段级双轨 `basic_data_path_composite` 死代码删除**：`adminPatchComposite` 的 fieldComposites 分支 + `TemplateService` 注入块（写入后无人读、且被 migrate-to-unified-view 剥离）。保留现役 `data_driver_path_composite`（统一智能视图）。因触及协议级文件 `TemplateService`，单独走 worktree + E2E。
