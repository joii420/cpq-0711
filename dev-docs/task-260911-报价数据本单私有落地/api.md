# task-260911 · 接口契约（第二版）

> 主文档：`需求文档.md`。
> **接口改动仍然很小**：新增端点 0 个 · 改动响应体 1 处 · **语义变更 3 处**（第二版从 1 处增到 3 处）。
> 绝大部分改动发生在**落库位置与取数来源**，对调用方不可见 —— 这是设计目标，不是遗漏。

---

## 1. 改动：`GET /api/cpq/quotations/{quotationId}/existing-products`

**服务的 AC**：AC-5 · **认领**：`B-15` / `F-1`

### 1.1 变更点

响应体 `ExistingProductDTO` **新增一个字段**，其余逐字不变：

| 字段 | 类型 | 含义 |
|---|---|---|
| `pendingApproval` | `boolean` | `true` = 该料号的版本化数据**尚未转正**（任一带版本主表中无该 `(customer_no, material_no)` 的行）⇒ 前端显示「待核价」标签 |

### 1.2 不变的部分（写清楚是为了让实现不要"顺手优化"）

- 🚫 **不因该字段过滤或禁用**（岔路 1 甲）。`pendingApproval=true` 的行照常可选、可加产品
- 分页 / 搜索参数 / 排序 **逐字不变**
- 🚫 **N+1 硬指标不变**：单次请求恒为 **3 条 SQL**。新判据走 `EXISTS` 聚合子查询一次 `LEFT JOIN` 带出
- 🚨 `LEFT JOIN v_compat_material_master` **刻意保留、不许直连 `ds_quote_material`**（`D-3`：实测直连会让 42 个料号改走新表值、6 个老表独有料号整个消失）

### 1.3 兼容性

加法式变更。旧前端不读该字段 ⇒ 行为不变。

---

## 2. 语义变更 A：核价通过确认预览的 `resultRowCount`

**服务的 AC**：AC-11 · **认领**：`B-8`（+ 条件项 `F-3`）

### 2.1 字段不变，判定变

| 情形 | `task-260907` 第二段 | 本任务之后 |
|---|---|---|
| `resultRowCount < baseRowCount` | 🔴 **非法态** —— 红色 + 「该组行数会减少 —— 按设计不应发生」 | ✅ **合法态** —— 用户在页签删了行，回填就该少行 |

**原因**：`D-34`「回填永不删行」被本任务推翻（岔路 5）。

### 2.2 🆕 同批必须新增：将删除的行清单

**服务的 AC**：AC-11③ · **认领**：`B-8④` / `F-3`

预览响应需能让财务看到**哪些行会被删**，不只是「行数变少」。

🚨 **为什么不能只摘警示**：`AP-60` / `repair-0727` 的核心症状就是「预览 0 变更、执行删 3 行」。摘掉唯一的哨兵而不装替代品，等于把事故的检出手段拆了。`BL-0211` 原文也要求「将删除 N 行**单列出来**」。

### 2.3 🚨 三条必须保住的既有不变量

1. **`resultRowCount` 恒发**，且在「不写」两态（`UNCHANGED` / `BLOCKED`）等于 `baseRowCount`。
   > `D-37` 实证：字段缺省取 0 会让 `0 < 4` 恒真，**把正常单据点亮成红条「请先不要确认」**，症状与真缺陷同形。
2. **「本次覆盖的列」与「原样保留的列」必须成对出现**（`AP-60` 列维度守卫）；「本次不动」列在每一张展示料号组的表格里都必须有（行维度守卫）。
   > 本任务改的是**行**的判定，🚫 不许顺手动这两条**列**维度守卫。
3. 🆕 **红色警示是三处并存的**（`AC-5e` 原文：单元格小注 + 顶部独立红条 + 汇总条，明写「只做单元格内小注不合格」）⇒ 改判据要**三处一起改**。

### 2.4 存量单提示（复用现有机制，非新字段）

**服务的 AC**：AC-15 · **认领**：`B-9`

`_record` 为空的单，预览响应返回既有的 `noRecordSnapshot`。🚫 不许静默 no-op。

---

## 3. 🆕 语义变更 B：公式求值端点的 `quotationStatus` 从「被信任」变为「被忽略」

**服务的 AC**：AC-21 · **认领**：`B-19` · 评审 `F-1`

### 3.1 现状

`POST /api/cpq/formulas/(batch-)?evaluate` 的请求体带 `quotationId` + `quotationStatus`，后端 `FormulaEvaluateResource:122` 原样塞进 `SqlViewRuntimeContext`：

```java
prevCtx = SqlViewRuntimeContext.setNestedTemplate(effectiveTemplateId, req.quotationId, req.quotationStatus);
```

而 `SqlViewExecutor:735-737` 的改写门槛正是读这个上下文 ⇒ **客户端传什么状态，后端就按什么状态处理**。

实测前端**两处调用都不传 `quotationStatus`**（`QuotationStep2.tsx:4569` COSTING / `:4648` QUOTE），一路 `|| null` ⇒ `isQuotationFrozen()` 恒 false。

### 3.2 本任务之后

**ds 改写器不再信任请求体里的 `quotationStatus`，一律按 `quotationId` 自查单据状态。**

| 项 | 变化 |
|---|---|
| 请求体字段 | **不删、不改类型**（加法式兼容，旧调用方不受影响） |
| `quotationStatus` 的作用 | **对 ds 改写器不再有效**。V6 通道行为不变 |
| 冻结单（SUBMITTED/APPROVED/PUBLISHED） | 无论请求体传什么，ds 改写器**不触发** |
| 前端 | 🚫 **零改动**（乙案「前端补传」已被否决 —— 信任模型没变，下个调用方忘传洞就回来） |

🚨 **自查结果可缓存，但缓存必须可失效** —— 🚫 **不许照抄 `priceBaseDateCache` 那种「`created_at` 不可变所以永久缓存」的模式**：单据状态**会变**（DRAFT → SUBMITTED → APPROVED）。

---

## 4. 🆕 语义变更 C：取价函数 `f_material_element_price` 的候选名单

**服务的 AC**：AC-20 · **认领**：`B-18` · 评审 `F-2`

### 4.1 这是数据库函数签名不变的**行为**变更

```
f_material_element_price(p_customer_no text, p_base_date date, p_pending_quotation_id uuid)
  → TABLE(material_no, element_code, unit_price, currency, price_unit)
```

签名、返回列**逐字不变**。变的是内部 `candidate_materials` CTE：

| 分支 | 现状 | 本任务后 |
|---|---|---|
| `material_bom_item` / `element_bom_item`（V6） | `is_current = true OR pending_quotation_id = :pq` | **不变** |
| `ds_quote_material_bom` / `ds_quote_element_bom` | 只有 `customer_no = :cust`（函数注释自陈「只对应 `is_current` 那半边」） | 🆕 **补两行读 `_record`**，按 `quotation_id = :pq` 收窄 |

### 4.2 不改的后果（这是它进本期的理由）

A 之后新料号 BOM 只在 `_record` ⇒ 候选名单缺席 ⇒ `realtime` 分支产不出行 ⇒ **元素单价整列 NULL**，往下所有用单价的公式全塌。

实测 **11 段视图**引用该函数，且这 11 段**全部**同时引用 ds 表。这正是 `repair-260830` 的原症状，区别是这次**按构造必然发生**而非偶发。

### 4.3 两参重载

`f_material_element_price(text, date)` 也存在。🚨 `B-18` 必须确认它有没有被 ds 视图调用，结论写进回报 —— 🚫 不许省略。

---

## 5. 无契约变更但行为变化的端点（回归清单，不改代码）

| 端点 | 行为变化 | 守卫 AC |
|---|---|---|
| `POST /api/cpq/dataset/quote/quotation-import` | 带版本 sheet 落 `_record` 不落主表；响应体不变 | AC-1 / AC-2 |
| `POST .../configure-product`（选配） | 同上（4 张带版本表）🚦 **延后批** | AC-8 / AC-8b |
| `POST /api/cpq/components/batch-expand`（物化期） | 取数来源变为「本单 `_record` ∪ 主表」；响应结构不变 | AC-3 / AC-4 / AC-23 |
| `DELETE /api/cpq/quotations/{id}` | 🔄 **已实现**（`QuotationService:2199`），本期是复核 + 补 `import_batch_id` 通道 | AC-7 / AC-24 |
| 主数据维护页保存端点 | 🚫 **逐字不变**，仍写主表升版 | AC-9（反向） |
| 核价通过回填 | 🚫 **逐字不变**，仍写主表 | AC-22（反向） |
| 核价侧全部端点 | 🚫 **逐字节不变** | AC-10（反向） |

---

## 6. `main-api.md` 回写

契约变更共 3 处：§1（新增 `pendingApproval`）· §3（`quotationStatus` 语义）· §4（DB 函数行为，非 HTTP 端点，按「数据层变更」附注）。

**时机**：`test-report.md` 产出、缺陷闭环之后，**合并 master 之前**。
来源标记：`> 来源任务：task-260911-报价数据本单私有落地｜回写日期：<实取>`
