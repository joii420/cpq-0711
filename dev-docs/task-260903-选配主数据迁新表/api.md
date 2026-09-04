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
