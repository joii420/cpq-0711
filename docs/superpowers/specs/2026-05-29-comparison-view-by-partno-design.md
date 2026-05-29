# 比对视图重构设计 — 料号双行对比 + 单元格高亮

- 日期: 2026-05-29
- 模块: 报价单编辑 / 比对视图（核价单 ↔ 报价单）
- 状态: 设计已确认，待写实现计划
- 关联文档: `docs/Excel模板配置指南.md`、`docs/报价单核价单功能总结.md`、`docs/反模式.md`（AP-22 / AP-31 / AP-37 / AP-44）

## 1. 背景与目标

报价单编辑界面（`QuotationStep2`）已有三个主 Tab：`报价` / `核价` / `比对视图`，前两者各自有「卡片视图」和「Excel 视图」。当前的「比对视图」（`ComparisonView.tsx` + 后端 `buildComparison`）是**按 `comparison_tag` 分组**的纵向对比，与本需求形态不同。

**目标**：把比对视图重构为**按料号横向对比报价单 Excel 视图与核价单 Excel 视图的内容**：

- 把两侧打了**相同 `comparison_tag`** 的字段拿来比对。
- 按**料号**组织：一个料号产出**两行**——一行报价数据、一行核价数据。
- 同一料号、同一 tag 的两侧值**不同**时，**两格都高亮**。
- 整体产出一个料号列表（双行表格），并支持**导出 Excel**（含高亮）。

**硬约束**：比对表里的每个值必须与用户打开对应「Excel 视图」看到的数字**逐个严格一致**。这决定了计算路径必须复用 Excel 视图的渲染计算，导出**不得重算**。

## 2. 现状（事实依据）

- 两个 Excel 视图都由**同一个**模板无关组件 `LinkedExcelView.tsx` 渲染：
  - 报价单 Excel 视图：`QuotationStep2.tsx` 挂载，`linkedTemplateId={customerTemplateId}`、`lineItems={quoteLineItems}`。
  - 核价单 Excel 视图：`QuotationStep2.tsx` 挂载，`linkedTemplateId={costingCardTemplateId}`、`lineItems={costingLineItems}`。
- `LinkedExcelView` 的最终单元格值在 `rows` useMemo 中产出：每行对象含 `__hfPartNo`（料号）、`__key`、`__label`、以及 `cellValues`（按 `col_key` 展开）。VARIABLE 列经 BNF 路径/`pathCache` 解析，FORMULA 列在前端 `evaluateFormula` 计算。
- Excel 列定义结构（`CostingTemplateColumn`）含：`col_key`、`title`、`source_type`('VARIABLE'|'FORMULA')、`variable_path`、`formula`、`comparison_tag`、`hidden`。
- `comparison_tag` 元数据来自 `ComparisonTag` 实体（`code`、`label`、`groupName`、`groupSortOrder`、`tagSortOrder`），前端 `comparisonTagService` 已可读。
- 料号字段：报价侧 `lineItem.productPartNo` → 行 `__hfPartNo`；核价侧同样经 `LinkedExcelView` 产出 `__hfPartNo`。

## 3. 已确认的需求决策

| 维度 | 决策 |
| --- | --- |
| 与旧比对视图关系 | **替换**旧的 tag 分组比对视图 |
| 字段匹配依据 | 按 `comparison_tag` 匹配 |
| 比对表的列 | **两侧 `comparison_tag` 的交集**（两边都打了该 tag 才成列） |
| 同侧一个 tag 多列 | 约定不出现；若出现取**列顺序第一个** |
| 比对表的行（料号） | **两侧料号并集**（都显示） |
| 单边缺失料号 | 缺失侧整行留空，料号行打标签「仅报价」/「仅核价」 |
| 数值差异判定 | **数值容差 + 字符严格**（见 §6） |
| 高亮范围 | 差异 tag 列下**报价行与核价行两格都高亮** |
| 值一致性 | 必须与 Excel 视图**逐格严格一致** → 复用同一计算路径 |
| 架构 | **方案 A**：前端共享 hook 计算 + 后端 POI 只格式化（不重算） |
| 导出 | 需要导出 Excel（含高亮） |

## 4. 架构与数据流（方案 A）

```
LinkedExcelView ──┐
                  ├── useLinkedExcelRows({linkedTemplateId, lineItems, customerId, templateId})
ComparisonView  ──┘        → { rows, parsedColumns }
        │
        │ 调用两次：报价侧(customerTemplateId, quoteLineItems) / 核价侧(costingCardTemplateId, costingLineItems)
        ▼
   构建比对模型（料号并集 × tag 交集，每料号两行，逐格 diff）
        │
        ├── 渲染：双行表格 + 两格高亮（前端）
        └── 导出：POST 已算好的模型 → 后端 POI 只写值+高亮（不重算）
```

### 4.1 抽取共享 hook

- 从 `LinkedExcelView.tsx` 抽出现有 `rows` useMemo（含 VARIABLE 解析、`pathCache`、FORMULA `evaluateFormula`）及其依赖，形成 `useLinkedExcelRows(params)`，返回 `{ rows, parsedColumns }`。
- `LinkedExcelView` 改为消费该 hook，**渲染行为零变化**（作为回归基线）。
- 该 hook 内部需要的 `pathCache` / `batchEvaluate` / `quotationContext` 等逻辑一并迁入，保持现有的 4 段 `buildEvalKey` 协议（`expr:cust:partNo:templateId`）不变。
- **这是协议关键改动**（CLAUDE.md 列 `LinkedExcelView.tsx` 为强制 E2E 文件）→ 改完必须跑 Playwright E2E 验证两个 Excel 视图渲染零回归。

### 4.2 ComparisonView 的入参

由 `QuotationStep2` 透传它本就持有的数据：

- `quotationId`
- `customerTemplateId`、`quoteLineItems`
- `costingCardTemplateId`、`costingLineItems`
- `customerId`

ComparisonView 内部各自调用 `useLinkedExcelRows`，得到报价侧 `{rowsQ, colsQ}` 与核价侧 `{rowsC, colsC}`。

## 5. 比对模型构建（前端）

### 5.1 列（columns）

- 从 `colsQ` 与 `colsC` 各取带 `comparison_tag` 的列，得到两侧 tag 集合。
- **列 = 两侧 tag 交集**。
- 同侧一个 tag 多列时取**列定义顺序第一个**的 `col_key`，记为该侧该 tag 的取值列。
- 列标题取 `ComparisonTag.label`（经 `comparisonTagService`）；排序按 `groupName` → `groupSortOrder` → `tagSortOrder`；同 `groupName` 的 tag 可加分组表头。

### 5.2 行（rows）

- 料号集合 = `rowsQ.__hfPartNo` ∪ `rowsC.__hfPartNo`（并集），按稳定顺序（报价侧出现顺序优先，核价侧独有的接在后面）。
- 每个料号产出两行：
  - `报价` 行：值取 `rowsQ` 中该料号行、该 tag 取值列 `col_key` 的 `cellValues`。
  - `核价` 行：值取 `rowsC` 中该料号行、对应取值列的 `cellValues`。
- 料号仅一侧存在：缺失侧整行留空，该料号打标签 `仅报价` / `仅核价`。

### 5.3 模型数据结构（也是导出 payload）

```ts
interface ComparisonColumn { tag: string; label: string; groupName?: string; }
interface ComparisonCell  { quote: any; costing: any; highlighted: boolean; }
interface ComparisonRow {
  partNo: string;
  presence: 'BOTH' | 'QUOTE_ONLY' | 'COSTING_ONLY';
  cells: Record<string /*tag*/, ComparisonCell>;
}
interface ComparisonModel {
  quotationId: number;
  columns: ComparisonColumn[];
  rows: ComparisonRow[];
}
```

## 6. 差异判定（数值容差 + 字符严格）

对每个 (料号, tag) 取报价值 `a`、核价值 `b`：

1. 任一侧缺失（料号单边存在或该格无值）→ **不判差异、不高亮**（靠行标签体现单边）。
2. `a`、`b` 皆可解析为有限数字 → 相等条件：
   ```
   |a - b| ≤ max(ABS_EPS, REL_EPS × max(|a|, |b|))
   ```
   默认 `ABS_EPS = 1e-6`、`REL_EPS = 1e-6`，集中定义为常量、可调。满足则不高亮，否则高亮。
3. 否则按字符串严格相等：`String(a).trim() === String(b).trim()`，不等则高亮。

差异判定函数纯函数化，单测覆盖：数值容差边界、`12.0 vs 12`、字符串大小写/空格、单边缺失。

## 7. UI 布局

- 表格左侧两固定列：`料号`（同料号两行**合并单元格 rowspan=2**）、`口径`（值为 `报价` / `核价`）。其后每个交集 tag 一列。
- 差异格：该 tag 列下报价行与核价行**两格都高亮**（统一红/黄底色；本期不区分核价>报价 / <报价 的方向色）。
- 单边料号：缺失侧整行留空 + 料号处标签 `仅报价` / `仅核价`。
- 顶部工具栏：
  - `导出比对 Excel` 按钮。
  - （轻量可选）`仅看有差异的料号` 过滤开关。
- 挂载点不变：`QuotationStep2` 的 `mainTab==='comparison'`，替换原 `ComparisonView` 内容；`quotationId` 不存在时禁用该 Tab（沿用现状）。

## 8. 导出（后端 POI，只格式化不重算）

- 新端点：`POST /api/cpq/quotations/{id}/comparison/export`，请求体 = 前端算好的 `ComparisonModel`。
- 后端用 Apache POI 按 payload 直接写：
  - 表头（含 tag 分组表头、`料号`、`口径` 列）。
  - 每料号两行值；`料号` 列纵向合并。
  - 对 `cell.highlighted === true` 的两格填充底色。
  - 单边料号留空格 + 标签。
- 后端**不做任何 BNF 路径 / 公式求值**，纯序列化写出 → 与页面所见严格一致。
- 返回 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 流，文件名含报价单号。

## 9. 清理 / 影响面

- 删除旧 `ComparisonView.tsx` 的 tag 分组对比 UI（替换为新实现，文件名可沿用以减少 callsite 改动）。
- 后端 `CostingSheetService.buildComparison` 及 `GET /api/cpq/quotations/{id}/comparison`、前端 `costingSheetService.getComparison`、`comparisonTagService` 中仅服务旧视图的部分：**先 grep 全工程确认无其它引用再废弃**；`ComparisonTag` 元数据（label/分组/排序）仍被新视图复用，保留。
- `LinkedExcelView` 抽 hook 为协议关键改动，遵守 CLAUDE.md「修改后强制自检」与 AP-31/AP-37 协议传播注意事项。

## 10. 测试计划

1. **回归（强制 E2E）**：抽 hook 后跑 `quotation-flow.spec.ts`，确认报价/核价两个 Excel 视图渲染零回归，`'加载中' final count = 0`，附截图基线。
2. **单测**：差异判定纯函数（数值容差、字符严格、单边缺失、非有限数字）。
3. **比对模型单测**：构造已知两侧 rows/cols，验证列=tag 交集、行=料号并集、`presence` 标记、`highlighted` 命中。
4. **渲染验收**：构造含差异料号的报价单，验证两格高亮 + 并集行 + 交集列 + 单边标签。
5. **导出验收**：导出 xlsx 的值与高亮格 = 页面所见（逐格抽样比对）。

## 11. 自检清单（交付前）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] 改动的 `.tsx` 经 Vite 取到 HTTP 200
- [ ] Playwright `quotation-flow.spec.ts` 全 passed，`'加载中'=0`，附 qf 截图
- [ ] 后端 `/api/cpq/quotations/{id}/comparison/export` 返回 200 + 正确 xlsx
- [ ] 差异判定 / 比对模型单测通过
- [ ] grep 确认旧 `buildComparison` 链路无残留引用后再废弃
- [ ] 「已自检」声明一行附在交付说明

## 12. 未决 / 可后续扩展（本期不做）

- 高亮方向色（核价>报价 红 / <报价 绿）—— 本期统一一种底色。
- tag 多列时的求和聚合 —— 本期约定一个 tag 单列。
- 比对容差按 tag/列单独配置 —— 本期全局常量。
