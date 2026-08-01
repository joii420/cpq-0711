# 接口契约 — 公式计算精度优化（task-0801）

> 配套文档：`需求说明.md`（需求与澄清结论）、`backtask.md`（后端任务）、`fronttask.md`（前端任务）
> 定稿日期：2026-08-01

---

## 0. 一句话总纲

**本期不新增、不删除、不重命名任何 REST 端点，不改任何请求 / 响应的字段名与结构。**
唯一的契约变化是：**数值字段的精度**——响应中的计算类数值最多带 6 位小数（原来最多 4 位，部分展示位仅 2 位）。

前后端**并行开发**的对齐依据就是本文档第 2、3、5 节。

---

## 1. 精度契约（Precision Contract）

### 1.1 三类数值，三种规则

| 类别 | 定义 | 判定方法 | 精度规则 |
|------|------|---------|---------|
| **A. 计算值** | 公式求值结果、各级小计与合计、折扣金额 | 字段 `field_type` ∈ {FORMULA, LIST_FORMULA, TAB_JOIN, CARD_FORMULA}，或 `is_subtotal = true`，或属于 §3 金额字段清单 | **至多 6 位小数，HALF_UP，去掉末尾 0** |
| **B. 取数值** | 直接从基础资料 / 数据源 / SQL 视图读出的原始值 | `field_type` ∈ {DATA_SOURCE, BASIC_DATA}，或 `basic_data_path` 非空 | **保持库中原精度，不做任何规整** |
| **C. 输入值** | 用户手工录入或系统配置的费率 | `field_type` ∈ {INPUT_NUMBER, FIXED_VALUE}，以及折扣率 / 税率字段 | **保持现状不变**（费率 2 位） |

### 1.2 "至多 6 位去尾零"的精确定义

```
输入 0.0774           → "0.0774"        (不补成 0.077400)
输入 5                → "5"             (不补成 5.000000)
输入 0.0432654321     → "0.043265"      (HALF_UP 规整到 6 位)
输入 0.0000004        → "0"             (规整到 6 位后为 0)
输入 12345.678        → "12345.678"
null / 空             → 显示占位 "—"，JSON 中为 null
```

**舍入方式统一 `HALF_UP`**，前后端一致。

### 1.3 四个规整边界

计算过程中**不得规整**；只在下列 4 处规整到 6 位：

| 边界 | 位置 | 责任方 |
|------|------|--------|
| ① 落库 | 写入 DB 金额列前 | 后端 |
| ② API 返回 | 序列化 JSON 前 | 后端 |
| ③ 界面显示 | 渲染到 DOM 前 | 前端 |
| ④ 导出 | 写入 Excel / PDF 前 | 后端 |

### 1.4 两条链路的承载类型（关键，见 需求说明.md §11.11）

| 链路 | 范围 | 承载类型 | 约束 |
|------|------|---------|------|
| 链路一：公式内部 | 单元格级计算（材料成本、用量、加工费…） | 求值内部用 `BigDecimal` / `Decimal`，跨层用 `Double` / `number` | 单次运算必须十进制精确 |
| 链路二：金额汇总 | 产品小计 → ×年用量 → 行合计 → 整单总额 → 核价汇总 | **全程 `BigDecimal` / `Decimal`** | **任何一步不得出现 `.doubleValue()` / `Number()` 回落** |

> 年用量可达几十万件，整单可冲到亿级 = 15 位有效数字 ≈ double 极限，链路二落回 double 会让小数第 5、6 位不可信。

---

## 2. 受影响端点清单

**全部为"响应精度变化"，无结构变更。** 前端无需改任何请求体。

### 2.1 报价单主链路（`/api/cpq/quotations`）

| 方法 | 路径 | 影响的数值 |
|------|------|-----------|
| GET | `/api/cpq/quotations` | 列表项 `totalAmount`、`originalAmount` |
| GET | `/api/cpq/quotations/{id}` | 整单金额 + `lineItems[]` 全部金额字段 + 卡片值 |
| POST | `/api/cpq/quotations/{id}/draft` | 响应回传的整单与行金额（落库同步变 6 位） |
| POST | `/api/cpq/quotations/{id}/submit` | 同上 + 冻结快照内数值 |
| POST | `/api/cpq/quotations/{id}/recalculate` | 重算后的整单与行金额 |
| POST | `/api/cpq/quotations/{id}/calculate-discount` | `totalAmount`、`originalAmount` |
| POST | `/api/cpq/quotations/{id}/ensure-card-values` | 卡片值（计算结果） |
| POST | `/api/cpq/quotations/{id}/ensure-excel-values` | Excel 视图计算值 |
| POST | `/api/cpq/quotations/{id}/refresh-card-snapshot` | 卡片快照内数值 |
| PUT | `/api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | 编辑后回算的卡片值与小计 |
| GET | `/api/cpq/quotations/{id}/snapshot` | 快照内数值（**新生成的**快照按 6 位；存量快照不变） |
| GET | `/api/cpq/quotations/{id}/field-trace` | 溯源链路上的中间值 |
| POST | `/api/cpq/quotations/{id}/copy` | 复制单继承的金额 |

### 2.2 Excel 视图与导出

| 方法 | 路径 | 影响 |
|------|------|------|
| GET | `/api/cpq/quotations/{id}/excel-view` | 视图内计算列 |
| POST | `/api/cpq/quotations/{id}/excel-view` | 保存后回算值 |
| GET | `/api/cpq/quotations/{id}/excel-view/dry-run` | 试算值 |
| GET | `/api/cpq/quotations/{id}/export/html` | **导出金额由 2 位改 6 位** |
| GET | `/api/cpq/quotations/{id}/export/pdf` | **同上** |
| GET | `/api/cpq/quotations/{id}/export/excel` | **同上**（含 POI 单元格数字格式串） |
| GET | `/api/cpq/quotations/{id}/export-excel-view` | 同上 |
| POST | `/api/cpq/quotations/{id}/send` | 邮件正文内嵌金额 |

### 2.3 公式求值

| 方法 | 路径 | 影响 |
|------|------|------|
| POST | `/api/cpq/formulas/evaluate` | 单公式求值结果 |
| POST | `/api/cpq/formulas/batch-evaluate` | 批量求值结果 |

> ⚠️ 调用 `/formulas/(batch-)?evaluate` 时若 Excel 列 `variable_path` 引用了 `$view.col`，
> 前端**必须继续携带 `templateId`**（`docs/方案制定前必读.md` 改动 6）。本期不得删改该字段的透传。

### 2.4 核价侧

| 方法 | 路径 | 影响 |
|------|------|------|
| — | `/api/cpq/costing-orders/**`（`CostingOrderResource`） | 核价单总额、明细金额、版本重算值 |
| — | `/api/cpq/costing-sheets/**`（`CostingSheetService` 求值） | 核价表公式计算值 |
| — | 比对视图（`ComparisonViewResource`） | 报价 / 核价差异值与差异率 |
| POST | `/api/cpq/quotations/{id}/costing-approve/preview` | 预览金额 |
| POST | `/api/cpq/quotations/{id}/costing-approve` | 审批落库金额 |

---

## 3. 金额字段精度契约（逐字段）

### 3.1 整单级（`quotation` / `QuotationDTO`）

| JSON 字段 | DB 列 | 原 | 新 | 类别 |
|-----------|-------|----|----|------|
| `totalAmount` | `quotation.total_amount` | numeric(18,4) | **numeric(20,6)** | A 计算值 |
| `originalAmount` | `quotation.original_amount` | numeric(18,4) | **numeric(20,6)** | A |
| `taxAmount` | `quotation.tax_amount` | numeric(18,4) | **numeric(20,6)** | A |
| `systemDiscountRate` | `quotation.system_discount_rate` | numeric(5,2) | **不变** | C 输入值 |
| `finalDiscountRate` | `quotation.final_discount_rate` | numeric(5,2) | **不变** | C |
| `taxRate` | `quotation.tax_rate` | numeric(5,2) | **不变** | C |

### 3.2 行级（`quotation_line_item` / `LineItem`）

| JSON 字段 | DB 列 | 原 | 新 | 类别 |
|-----------|-------|----|----|------|
| `subtotal` | `subtotal` | numeric(18,4) | **numeric(20,6)** | A（产品小计，单件） |
| `discountBaseAmount` | `discount_base_amount` | numeric(18,4) | **numeric(20,6)** | A |
| `lineUnitPrice` | `line_unit_price` | numeric(18,4) | **numeric(20,6)** | A |
| `lineFinalPrice` | `line_final_price` | numeric(18,4) | **numeric(20,6)** | A |
| `lineDiscountAmount` | `line_discount_amount` | numeric(18,4) | **numeric(20,6)** | A |
| `lineTotalAmount` | `line_total_amount` | numeric(18,4) | **numeric(20,6)** | A（行合计 = 折后单价 × 年用量，**链路二**） |
| `discountRateApplied` | `discount_rate_applied` | numeric(8,4) | **不变** | C |
| `systemDiscountRate` / `finalDiscountRate` | 同名列 | numeric(5,2) | **不变** | C |

### 3.3 页签级（`quotation_line_component_data`）

| JSON 字段 | DB 列 | 原 | 新 | 类别 |
|-----------|-------|----|----|------|
| `subtotal` | `subtotal` | numeric(18,4) | **numeric(20,6)** | A |
| `rowData` / `snapshotRows` 内数值 | JSONB | 无 scale 约束 | 写入前按 6 位规整 | A / B 按字段类型分别处理 |

> ⚠️ JSONB 内的数值**必须按字段类型区分**：计算列规整 6 位，取数列保持原值。
> 不能对整个 JSONB 一刀切规整，否则会把 8 位小数的取数列压坏（违反 §1.1 类别 B）。

### 3.4 核价级（`costing_order`）

| JSON 字段 | DB 列 | 原 | 新 | 类别 |
|-----------|-------|----|----|------|
| `totalAmount` | `total_amount` | numeric(18,4) | **numeric(20,6)** | A |
| `costingTotalAmount` | `costing_total_amount` | numeric(18,4) | **numeric(20,6)** | A |

---

## 4. JSON 序列化约定

1. **数值一律以 JSON number 传输**（保持现状，不改成字符串）；
2. 后端序列化前已按 §1.2 规整，因此响应中不会出现 `0.30000000000000004` 这类浮点尾巴；
3. **`null` 语义不变**：无值仍为 `null`，前端渲染为 `—`；不得用 `0` 代替 `null`；
4. 前端**不得**再对收到的数值做二次运算后直接展示（会重新引入误差）——
   需要展示的值以后端返回值为准；需要本地实时预览的值走前端 `precision.ts` 的十进制求值器。

---

## 5. 前后端对齐要求（并行开发的契约锚点）

### 5.1 精度常量必须同值

| 端 | 文件 | 常量 | 值 |
|----|------|------|-----|
| 后端 | `com/cpq/common/PrecisionPolicy.java` | `DISPLAY_SCALE` | `6` |
| 后端 | 同上 | `DIVISION_SCALE`（除法中间精度） | `12` |
| 前端 | `src/utils/precision.ts` | `DISPLAY_SCALE` | `6` |
| 前端 | 同上 | `DIVISION_SCALE` | `12` |
| 前端 | `src/utils/formatNumber.ts` | `COMPUTED_FALLBACK` | `6`（引用 `precision.ts`，不再自持常量） |
| 后端 | `com/cpq/common/NumberFormatUtil.java` | `COMPUTED_FALLBACK` | `6`（引用 `PrecisionPolicy`） |
| 后端 | `ExcelViewService` | `COMPUTED_FALLBACK_DECIMALS` | `6`（引用 `PrecisionPolicy`） |

**双方各写一条单元测试断言本端常量 = 6**；联调时用 §5.2 黄金用例做跨端比对。

### 5.2 黄金用例（前后端共用，逐字节比对）

前后端**各自实现**、**共用同一份期望值**。任一端不符即为缺陷。

| # | 表达式 / 场景 | 期望结果 | 考察点 |
|---|--------------|---------|--------|
| G-1 | `0.1 + 0.2` | `0.3` | 十进制精确（double 会得 0.30000000000000004） |
| G-2 | `1.005` 规整到 2 位 | `1.01` | 舍入边界（double 会得 1.00） |
| G-3 | `1 / 3` | `0.333333`（显示） | 无限小数按 12 位中间精度算，显示规整 6 位 |
| G-4 | `10 / 3 * 3` | `10`（显示） | 中间不截断（若中间截到 6 位会得 9.999999） |
| G-5 | `0.0000004` 规整 | `0` | 6 位以下归零 |
| G-6 | `0.0000005` 规整 | `0.000001` | HALF_UP 向上进位 |
| G-7 | `2.5 × 0.4` | `1` | 去尾零（不显示 1.000000） |
| G-8 | 空值 / null 参与运算 | 按 `0` 参与，结果非 null | 与现有 `toNumericString` 行为一致，不改语义 |
| G-9 | 除以 0 | 返回 `0`（不抛异常） | 与现有 `Double.isNaN/isInfinite → ZERO` 行为一致 |
| G-10 | 6 层嵌套：元素行 → 列小计 → 页签合计 → 产品小计 → 行合计(×500000) → 整单总额(20 行) | 与一次性十进制精确计算结果**逐字节相同** | **链路二核心用例**，对应 AC-13 / AC-14 |
| G-11 | 单价 `123.456789` × 年用量 `800000` | `98765431.2` | 亿级金额精度 |
| G-12 | 一元负号 `-(2+3)*2` | `-10` | 前后端解析器语义对齐 |
| G-13 | 运算符优先级 `2+3*4` | `14` | 同上 |
| G-14 | 全角运算符 `2×3÷4` | `1.5` | 现有 `×→*` `÷→/` 转换不得丢失 |

### 5.3 联调验收方式

同一张报价单、同一组输入数据，比对：
1. 前端页面显示值；
2. `GET /api/cpq/quotations/{id}` 响应值；
3. DB 落库值；
4. 导出文件值。

**四处必须逐字节相同**（对应 AC-15）。

---

## 6. 不变量（禁止破坏）

1. **不得改动任何端点路径、HTTP 方法、请求体结构、字段名**；
2. **不得把数值改成字符串传输**（会击穿前端全部消费方）；
3. **不得对 JSONB 内的取数列做规整**（§3.3 注意事项）；
4. **不得删除 `/formulas/evaluate` 的 `templateId` 透传**（`$view.col` 路由依赖它）；
5. **不得改变 null / 除零 / 空值参与运算的既有语义**（G-8 / G-9）；
6. **不得改动折扣率 / 税率字段的精度**；
7. 存量已冻结快照**只读不重算**，接口不得在读取时"顺手"按新精度改写。
