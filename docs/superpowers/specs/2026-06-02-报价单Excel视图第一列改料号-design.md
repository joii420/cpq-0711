# 报价单 Excel 视图第一列改为「料号」（生产料号）— 设计方案

- 日期：2026-06-02
- 状态：已与用户确认，待实现
- 影响面：前端单文件（`LinkedExcelView.tsx`），无后端 / 无 DB / 无迁移

## 一、需求

报价单的 Excel 视图，第一列当前显示「客户料号」，需改为显示**生产料号**（即宏丰料号 `productPartNo` / `hf_part_no`），**列标题就叫「料号」**（不带「宏丰」字样）。客户料号列整列去掉，整个视图只保留一个料号列。

## 二、现状根因（已核实）

报价单 Excel 视图由前端组件 **`cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`** 渲染（在 `QuotationStep2.tsx` 使用）。其渲染逻辑：

- 产品卡片的所有料号(lineItems)投成行；每行用 `productPartNo` 去匹配模板 SQL 视图的 `hf_part_no`，对 VARIABLE 列做 BNF 路径求值取数（`useLinkedExcelRows.ts`）。这与用户描述的"把产品卡片对应的所有料号投到 Excel 视图、与模板 SQL 视图的 hf_part_no 匹配显示数据"完全一致。
- **第一列是前端硬编码的行头列，与模板 `excel_view_config` 无关**：
  - `LinkedExcelView.tsx:64-67` 标题写死 `'客户料号'`，`dataIndex='__label'`。
  - `__label` 在 `useLinkedExcelRows.ts:322-331` 的取值优先级为 `customerPartNo → customerProductNo → productPartNo → productName`，因此显示成了客户料号。

所以这是一个**纯前端行头列**问题，不涉及模板配置、SQL 视图或数据迁移。

## 三、改动方案（仅前端 1 个文件）

**文件：`cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`** 第一列（约 64-68 行）：

```js
// 改前
{ title: '客户料号', dataIndex: '__label', key: '__label', fixed: 'left', width: 200,
  render: (v) => <span style={{ fontFamily: 'monospace' }}>{v}</span> }

// 改后
{ title: '料号', dataIndex: '__hfPartNo', key: '__hfPartNo', fixed: 'left', width: 200,
  render: (v) => <span style={{ fontFamily: 'monospace' }}>{v}</span> }
```

### 为什么用 `__hfPartNo` 而非改 `__label`
- `__hfPartNo` 在 `useLinkedExcelRows.ts:332` 已经等于 `li.productPartNo`（生产料号），零额外逻辑。
- 全工程 grep 确认 `__label` **仅** `LinkedExcelView.tsx:65` 这一处消费；换 `dataIndex` 后 `__label` 自然不再被引用，改动最小且无副作用。
- 用户确认（问题 7 选 C）：`productPartNo` 必有值，**第一列不做空值兜底**，直接显示。

## 四、明确不涉及 / 排除项

- 模板 `excel_view_config`、`template_sql_view`、Flyway 迁移：**不动**（第一列与模板配置无关）。
- 已发布(PUBLISHED)模板、历史报价单 `excel_view_snapshot`：**不动**。
- `ComparisonView`（比对视图）：第一列本来就已是「料号」(`partNo`)，无需改。
- `ExcelView.tsx`（走后端 viewConfig 的另一条渲染）：未硬编码客户料号，不在本次范围。
- 导出的 .xlsx（`QuotationExportService`）：是另一套汇总布局（序号/产品规格/产品分类/属性值/小计），本就无料号列，无关。
- 后端：无任何改动。

## 五、验证（遵循 CLAUDE.md 自检规范）

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
2. `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx` → 200。
3. `LinkedExcelView` 属报价单渲染链路，跑 E2E `quotation-flow.spec.ts` → `1 passed`、`'加载中' final count = 0`；确认 Excel 视图第一列表头为「料号」、单元格值为生产料号(productPartNo)。
