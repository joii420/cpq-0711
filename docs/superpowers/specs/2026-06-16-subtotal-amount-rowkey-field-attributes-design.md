# 字段属性「小计 / 金额 / 行键」完整定义与渲染修正

> 日期：2026-06-16
> 状态：设计已确认，待写实现计划
> 边界：**只修渲染/统计判定，禁止改动公式计算引擎**

## 1. 背景与问题

组件管理中「项次 / 毛重 / 净重 / 组成用量 / 损耗率」等列**未勾选「小计」**，但在报价单的「小计」行里仍被整列求和显示；「本页签总计」把全部 is_subtotal 列重复累加（如 ¥260.67 = 6 个小计列全加，其中材料成本本已含前几项）。根因是渲染层判定条件错误，而非数据模型缺失。

### 现状诊断

| 现象 | 根因 |
|---|---|
| 未勾小计的数值列也出现在小计行 | `QuotationStep2.tsx:2554`、`ReadonlyProductCard.tsx:657` 小计行判定 = `is_subtotal **OR** INPUT_NUMBER/FORMULA/DATA_SOURCE`，把所有数值列都求和 |
| 本页签总计重复累加 | `QuotationStep2.tsx:2584`、`ReadonlyProductCard.tsx:685` 本页签总计 = `sumTabColumns` = 所有 **is_subtotal** 列之和，而非「金额列」之和 |

**已就绪（无需新建）**：`is_amount` / `is_subtotal` 字段（`component/types.ts:65-66`）、组件级 `row_key_fields`、三个勾选框 UI（`FieldConfigTable.tsx`）、小计行按列渲染、¥ 对 is_amount 生效、行键判重 + 公式分组（引擎）。本设计是「修判定」而非「从零做」。

## 2. 三大属性最终定义

### 2.1 小计 (is_subtotal)
- **可勾范围**：所有字段类型（含文本）均可勾；默认不勾。
- **效果**：勾选的列在小计行**整列求和**；**未勾的列在小计行一律留空**；文本列勾了按 0 处理（`parseFloat` NaN → 0）。
- **公式**：可作为单值参与页签连表公式（已实现，本次不动）。

### 2.2 金额 (is_amount)
- **依赖**：必须先勾小计才能勾；**取消小计时自动联动取消金额**；金额勾选框在未勾小计时置灰（disabled）。因此 `is_amount ⊆ is_subtotal` 由 UI 强制保证（见 C4），新数据不会出现金额脱离小计。
- **效果**：金额列的小计单元格前加 **¥**，数字**跟随通用数值精度**（最多 4 位小数、去末尾 0，与其它小计列同款格式，仅多 ¥ 前缀）；并计入本页签金额合计。
- **本页签金额合计**（即页签底部那行「本页签总计」，本设计为消歧改名，见 O1）= 仅所有**金额列**的小计之和，格式 **¥ + 2 位小数**（财务惯例）；**无任何金额列时，整行隐藏**。
  - ⚠️ **命名消歧（O1）**：此「本页签金额合计」是**纯显示行**，由 `sumTabColumns` 单独算出，**不参与任何公式计算、无任何 token 读它**。它与公式引擎的「组件小计 component_subtotal」（= 该页签所有 `is_subtotal` 列之和，喂产品小计 / 跨页签公式）是**两个不同的值**：改本行显示不触公式引擎。两者本设计前数值恰好相等，改后将分叉（显示 = 金额列之和，引擎值仍 = 小计列之和），故须改名 + 注释区分。

### 2.3 行键 (row_key_fields)
- **定义**：页签数据唯一键，**支持多列联合主键**。
- **效果**：① 同页签内该（组合）值不可重复（判重提示已有）；② 作为宿主页签主键，是**跨页签明细按行键归组写回宿主行**的对齐依据（已在公式引擎实现）。
- **本次改动**：无。仅纳入定义文档。

## 3. 改动清单

> 两处渲染层**对称改动**（AP-50：避免详情页僵尸数据）。核价单（编辑）与报价单共用 `QuotationStep2.tsx` 的 ProductCard 渲染路径（cardSide 区分）；详情（只读）走 `ReadonlyProductCard.tsx`。

### C1 — 小计行：仅 is_subtotal 列求和，非小计列留空
- `QuotationStep2.tsx` 小计行单元格判定（约 2554-2558）：`isNumericCol = is_subtotal || INPUT_NUMBER || FORMULA || DATA_SOURCE` → **仅 `field.is_subtotal`**。
- 小计行整体显示门槛（约 2539-2544）：`some(numeric)` → **`some(f => f.is_subtotal)`**。
- `ReadonlyProductCard.tsx` 同步（约 645、657-661）。

### C2 — 金额列小计单元格：¥ + 通用精度
- `QuotationStep2.tsx`（约 2562-2564）：is_amount 列文本 `formatCurrency(v)`（2 位） → **`¥ ` + 通用精度**（`v === 0 ? '0' : parseFloat(v.toFixed(4)).toString()`，前缀 `¥ `）。
- `ReadonlyProductCard.tsx` 同步（约 665-666）。

### C3 — 本页签金额合计：仅金额列之和 + 无金额列隐藏（含 O1 改名）
- `tabTotalLines.ts#sumTabColumns`：过滤条件 `f.is_subtotal` → **`f.is_amount && f.is_subtotal`**（M1：`&&` 是白拿的保险，使「金额合计只认有真实小计值的列」语义显式；值来源 per-column 小计仅为 is_subtotal 列写入）。`CompLike.fields` 类型补 `is_amount?`（O3，否则 `tsc` 不过）。
- **注释（O1）**：`tabTotalLines.ts` 顶部注释写明「此行仅显示用，与公式引擎的 `component_subtotal` / `allComponentSubtotals[tabName]` 无关，勿混淆」。
- 本页签金额合计行门槛：`QuotationStep2.tsx:2579`、`ReadonlyProductCard.tsx:681` 的 `some(f => f.is_subtotal)` → **`some(f => f.is_amount)`**。
- **可见文案（O1）**：底部那行 label `本页签总计` → **`本页签金额合计`**（`QuotationStep2.tsx:2582`、`ReadonlyProductCard.tsx:683`），与新语义对齐。
- 数值格式：保持 `formatCurrency`（¥ + 2 位）。

### C4 — 组件管理勾选框联动（M2：两项必须同一 PR 落地，缺一即留脏数据入口）
- `FieldConfigTable.tsx` 金额勾选框（约 416-419）：加 **`disabled={!record.is_subtotal}`**。
- `handleSubtotalChange`（约 77-80）：取消小计时联动清金额 → `updateField(key, { is_subtotal: checked, ...(checked ? {} : { is_amount: false }) })`。
- 二者配套强制 `is_amount ⊆ is_subtotal`：新数据永不出现金额脱离小计（从源头堵死 M1 漏算场景）。

### 不改
- 公式计算引擎（`FormulaCalculator.java` / `computeAllFormulas` / cross_tab / 行键分组）。
- `buildColumnSumsByComp` 数据层求和谓词（保持 numeric，渲染层 gate 即可；避免破坏 `columnSumsByComp.test.ts` / `unitConversion.nonSubtotalSums.test.ts` 等数据层测试）。
- 行键相关全部逻辑。
- Excel 视图（单独评估）。
- 存量数据迁移：**不考虑存量，以最新报价数据为主**（只改代码）。存量未勾金额的页签其金额合计行将隐藏，已确认接受；不为存量做防御性补偿。

## 4. 边界场景

| 场景 | 期望 |
|---|---|
| 页签有 is_subtotal 列、无 is_amount 列 | 小计行显示（纯数字、无 ¥）；本页签金额合计行隐藏 |
| 页签无 is_subtotal 列 | 小计行 + 本页签金额合计行均隐藏 |
| 文本列勾 is_subtotal | 小计行该列显示 0 |
| 取消某列 is_subtotal（原已勾 is_amount） | is_amount 同步取消（C4）；该列退出小计行与金额合计 |
| is_amount=true 但 is_subtotal=false | 新数据不会发生（C4 从源头堵死）；存量不考虑（`is_amount && is_subtotal` 保险下也不计入金额合计） |

## 5. 质量保证

- 协议级改动（`QuotationStep2.tsx` / `ReadonlyProductCard.tsx`）→ **强制 Playwright E2E**：`quotation-flow.spec.ts`，须 `1 passed` + `'加载中' final count = 0` + 8 Tab `'加载中'=0`。
- 前端自检：`tsc --noEmit` 0 错误；改动 `.tsx` 文件 Vite 200。
- 人工验收：报价单 / 核价单 / 详情三视图，对照「未勾小计列留空」「金额列 ¥+精度」「本页签金额合计仅金额列、无金额列隐藏、label 已改名」「组件管理金额置灰+取消小计联动清金额」。
- 单元测试：`sumTabColumns` 改为 is_amount 后，更新/新增 `tabTotalLines.test.ts` 断言。

## 6. 开发流程
- 隔离 worktree 特性分支开发，默认 subagent-driven。
- 用户确认达标后自动收尾合并 master + 清理 worktree。
