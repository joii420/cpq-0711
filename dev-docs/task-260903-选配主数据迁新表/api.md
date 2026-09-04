# task-260903 · 契约

## 1. HTTP 契约：零改动

本任务**不新增、不修改、不删除任何 HTTP 端点**。

选配的四个端点（`POST /configure-product/quotations/{id}`、`POST /configure-product/lookup-fingerprint`、
`GET /quotations/configure/check-product-no`、`GET /quotations/configure/outsourced-parts`）
的**请求体、响应体、状态码全部保持不变** —— 前端因此零改动（见 `fronttask.md`）。

改的只是「同样的输入，落到哪几张表」。

---

## 2. 数据契约：兼容视图的列映射

三张兼容视图的列**必须与 V6 表逐字一致**（列名、列序、类型），否则 135 段组件 SQL 里会有某一段静默失败。

### 2.1 `v_compat_material_bom_item`

| V6 列 | V6 存量侧 | 新表侧（`ds_quote_material_bom`） |
|---|---|---|
| `system_type` | 直取 | 常量 `'QUOTE'` |
| `customer_no` | 直取 | `JOIN ds_quote_customer_part` 取 `customer_no` |
| `material_no` | 直取 | `material_no` |
| `characteristic` | 直取 | **`output_material_type`**（取值逐字相同：`ASSEMBLY` / `RECIPE`） |
| `seq_no` | 直取 | `item_seq` |
| `component_no` | 直取 | `input_material_no` |
| `component_usage_type` | 直取 | `LEFT JOIN material_recipe ON code = input_material_no` 取 `symbol`<br>⚠️ **必须 LEFT** —— 外购件不在 `material_recipe` 里，INNER 会吞掉整行 |
| `material_ratio` | 直取 | `material_ratio`（`numeric(24,12)` → `(26,12)` 放宽，无损） |
| `is_current` | 直取 | 常量 `true`（新表主表天然只存当前版本） |

| `composition_qty` | 直取 | **`component_qty`**（2026-09-03 补，超出立项时的 §2.1）<br>🚨 实测 **53 段模板引用 `composition_qty`**，组合产品的装配用量全靠它，不映射 A-AC-8 必挂 |

📌 **其余列（`rough_weight` / `net_weight` / `scrap_rate` / `defect_rate` 等）一律留 `NULL`**：
它们的语义有「百分比 vs 小数」歧义，而选配侧在 V6 时代本来就写 NULL ——
**留 NULL 才是行为等价**，硬填一个猜测值反而制造新 bug。

### 2.2 `v_compat_material_master` / `v_compat_element_bom_item`

同构，映射见 `backtask.md B-1`。`element_bom_item.content_pct` ↔ `ds_quote_element_bom.content_pct` 同名直取。

---

## 3. 写入契约：必须走 `VersionedGroupWriter`

```java
@Inject VersionedGroupWriter writer;   // @ApplicationScoped

writer.writeGroup(sheet, axisValue, rows, VersionedGroupWriter.SOURCE_MANUAL, reason, operator);
```

四条硬约束（来自其 javadoc 与「新料号数据规则」会话确认）：

1. **`rows` 是该轴值的整组全量行**，不是增量 —— 传部分行 = 其余行被当成删除、整组重写
2. **调用方不填 `version_no` / `row_fingerprint`** —— 由写入器负责。🚫 自己算指纹必然与导入侧漂移
3. **`output_material_type` 参与行指纹，必须显式填** —— 留空会让用户下次导入误判「内容变了」而整组升版
4. **`item_seq` 不参与指纹**（多重集比较不看行序），但**行数变了就升版**

免版本表（`ds_quote_material` / `ds_quote_customer_part`）🚫 **不得走这个写入器**（会抛 `IllegalArgumentException`），直接 upsert。

---

## 4. 🚨 兼容视图的作用域约束（2026-09-03 主线亲验，A 阶段硬约束）

**兼容视图的 BOM 侧必须能追溯到 `ds_quote_customer_part`，否则整组不出行。**

事务内造数验证（建视图 → 注入 → 断言 → `ROLLBACK`）：

| 场景 | 结果 |
|---|---|
| 新表独有料号进 `ds_quote_material` | ✅ `v_compat_material_master` 恰好 +1 行，不重复 |
| 该料号的 BOM 行，**但无 `customer_part` 行** | ❌ **0 行** —— 被客户作用域过滤 |
| 补上 `ds_quote_customer_part` | ✅ 1 行，`characteristic=RECIPE` / `component_usage_type=AgNi10` / `material_ratio=60.500000000000` 全对 |
| **COMPOSITE 子件**（无自己的 `customer_part`） | ✅ 通过「它是父件的 `input_material_no`」推导取到 `customer_no` |

### 对 A 阶段的三条要求

1. **`ds_quote_customer_part` 必须与 BOM 在同一事务内写** —— 否则存在一个「BOM 已落库但渲染不出来」的窗口。
2. **指纹命中复用时只写 `customer_part`、不写 BOM** —— 这没问题，BOM 早已存在；但要确认 `customer_part` 写入成功，否则复用出来的产品同样渲染不出。
3. 🚫 **不得假设「写了 BOM 就能渲染」** —— 这是本次亲验推翻的直觉。

### 为什么这条验证是必要的（而不是多此一举）

立项时我以为「新表侧为空所以 diff 必然全绿」。后端实测推翻了第一层（新表已有 42 行 IMPORT）。
「新料号数据规则」会话又推翻了第二层：**那 42 行的料号与 `material_master` 恰好 42/42 全重叠**，
所以被反连接全部排除 —— **「零新增行」是这个巧合的结果，不是反连接逻辑的证明**。

⇒ 上表是在「新表侧真的有 V6 没有的料号」这个条件下重做的验证。
📌 那条线即将重新导入含 `S0001/S0002/S0003` 等全新料号的数据，届时新表侧会**第一次真正投影进渲染链路**。
