# task-260910 · 接口契约

> 🚨 **本文件是前后端并行开发唯一的协调物**（`task-docs.md §6`）。子代理派出时读一次，之后改动**必须显式通知**。
> 变更本文件 = AC/契约变更 ⇒ 走 `task-docs.md §4「开工后 AC / 契约变更」`四步。

---

## 1. 变更总览

| # | 端点 / DTO | 变更类型 | 服务的 AC |
|---|---|---|---|
| §2.1 | `GET /api/cpq/quotations/configure/search-parts` | **签名加参数** + **返回结构变更**（材质多值） | AC-5 / AC-7 / AC-8 / AC-9 |
| §2.2 | `GET /api/cpq/quotations/configure/outsourced-parts` | **签名加参数** | AC-6 |
| §2.3 | `POST /api/cpq/configure-product/quotations/{quotationId}` | **请求体加字段**（直接绑定） | AC-18 / AC-19 / AC-20 |
| §2.4 | `POST /api/cpq/configure-product/lookup-fingerprint` | **响应字段裁剪**（删两个无消费方字段） | AC-22 |
| §3 | 错误码 | 新增 1 个 | AC-20 |

🚫 **不变更**：`PUT /{id}/draft` · `POST /{id}/submit` · `POST /{id}/costing-approve` · `GET /material-recipes*` · `GET /sel-param-types/*/candidates` · `GET /composite-processes` · `GET /quotations/configure/check-product-no` · `GET /quotations/configure/existing-part/{hfPartNo}/material`。

---

## 2. 逐个契约

### 2.1 `GET /quotations/configure/search-parts` —— 已有零件搜索

**现状**：`?q=<keyword>&size=50`，读 V6 `material_master`，跨客户，材质字段为单值。

**改后**：

```
GET /api/cpq/quotations/configure/search-parts?customerNo=CUST-0004&q=<keyword>&size=50
```

| 参数 | 变化 | 说明 |
|---|---|---|
| `customerNo` | 🆕 **新增，必填** | `customer.code`。缺失 → 400（🚫 不许静默跨客户查，D-2） |
| `q` | 不变 | 关键词；空/空白 → 返回空数组（沿用现状） |
| `size` | 不变 | 默认 50，上限 200 |

**返回**（`SearchPartResult[]`，裸数组，沿用现状包装）：

```jsonc
[{
  "hfPartNo": "S0004",
  "partName": "触点组件",
  "specification": "φ5",
  "sizeInfo": "5×3×2",
  "statusCode": "Y",

  "unitWeight": "7.000000000000",     // ✅ 保留（D-2 裁定）—— 「单重」列在读它，不在本次变更面内

  // 🔄 材质字段：单值 → 多值（AC-8）
  "materials": [
    { "recipeCode": "00006", "recipeSymbol": "AgNi10", "recipeName": "银镍10", "recipeSpec": "…", "recipeType": "…", "ratio": "55" },
    { "recipeCode": "00168", "recipeSymbol": "…",      "recipeName": "…",     "recipeSpec": "…", "recipeType": "…", "ratio": "45" }
  ]
  // 🆕 ratio（D-1 裁定）= ds_quote_material_bom.material_ratio 字符串。前后端已独立收敛到同一形状：
  //    后端实发（实测 materials=[{992,AgNi11#-Ⅰ,ratio 55},{00017,AgCu70,ratio 45}]）
  //    前端 types/configure.ts 的 SearchPartMaterial 已声明它
}]
```

🔴 **删除的字段**（原为单值，已被 `materials[]` 取代）：`recipeId` · `recipeCode` · `recipeSymbol` · `recipeName` · `recipeSpec` · `recipeType`。

⚠️ **两处 `materials[]` 形状不一致**（2026-09-10 S-B 实测登记，**不违反任何 AC**，本期不改）：
· `search-parts` 的元素 = `{recipeCode, recipeSymbol, recipeName, recipeSpec, recipeType, ratio}`
· `reusedProductInfo.materials` 的元素 = `{recipeCode, name, ratio}`（**只有 3 个字段，且用 `name` 不是 `recipeName`**）
⇒ 前端若想复用同一个渲染组件会踩空。已登记 `BL-0262`。

> 🚦 **R-2 待定**：`materials[]`（数组）是当前方案。若前端更希望单串展示，可改为 `materialsLabel: "AgNi10 / …"` —— **原型图定稿时确定，定后本节同步更新并通知双方子代理**。

**材质来源判据**（AC-9，🚫 不许用 `output_material_type`）：

```sql
FROM ds_quote_material m
LEFT JOIN ds_quote_material_bom b
       ON b.customer_no = m.customer_no AND b.material_no = m.material_no
JOIN      material_recipe mr ON mr.code = b.input_material_no   -- ← 能 JOIN 上 = 是材质
WHERE m.customer_no = :customerNo AND (<关键词条件>)
```

- **料号本身用 `LEFT JOIN ds_quote_material_bom`**：外购件与组合父料号没有材质行，不能被吞掉；
- **材质那一跳是 INNER**：它就是判据本身；
- 🚫 **不带 `output_material_type` 条件**（实测该列 8 种值，`成品` 2645 行，是用户自填业务字段）。

### 2.2 `GET /quotations/configure/outsourced-parts` —— 外购件候选

**现状**：`?keyword=&page=1&size=20`，读 `v_compat_material_master WHERE material_type='外购件'`。

**改后**：

```
GET /api/cpq/quotations/configure/outsourced-parts?customerNo=CUST-0004&keyword=&page=1&size=20
```

| 参数 | 变化 |
|---|---|
| `customerNo` | 🆕 **新增，必填**；缺失 → 400 |
| `keyword` / `page` / `size` | 不变 |

**数据源改为**：`ds_quote_material WHERE customer_no = :customerNo AND material_type = '外购件'`

**返回结构不变**：`{ "total": 5, "items": [{ "materialNo": "S0011", "materialName": "密封圈B", "specification": "…" }] }`

🔑 **为什么必须带客户**（AC-6 阳性可证伪）：实测这 5 个外购件料号（`S0003` 铆钉配件 / `S0007` 弹簧件A / `S0011` 密封圈B / `S0014` 绝缘座C / `T260907-M2` 测试主件B）**同时挂在 `CUST-0001` 与 `CUST-0004` 下，共 10 行**。不带客户过滤 ⇒ 列表出双份。
📌 兼容视图原先靠 `DISTINCT ON`（V435 / `repair-260908 C-1`）收敛，直连新表等于绕过那次修复 ⇒ 必须用客户过滤替代。

⚠️ **分页仍不接**（本期明确不做）：前端 `page` 恒 1 / `size` 恒 20 / `pagination={false}`。候选超 20 个时选不到第 21 个 —— 现被数据量掩盖（5 条），已登记 BACKLOG。

### 2.3 `POST /configure-product/quotations/{quotationId}` —— 选配提交

**请求体加 1 个字段**（加法式，老 payload 行为逐字不变）：

```jsonc
{
  "productType": "SIMPLE",
  "tempId": "<UUID>",
  "customerProductNo": "T260910-BIND-01",
  "customerProductName": "…",

  // 🆕 S-7：直接绑定已有销售料号（AC-18）
  //   非空 ⇒ 走绑定路径：不铸新号、不进指纹、不写 BOM/元素
  //   非空时 parts 必须为空数组或 null；两者同时非空 → 400 BIND_AND_PARTS_EXCLUSIVE
  "bindExistingMaterialNo": "S0004",

  "parts": [],
  "compositeProcesses": null
}
```

**响应结构不变**（`ConfigureProductResponse`）。绑定路径下：

| 字段 | 值 |
|---|---|
| `lineItems` | 1 行，`productPartNo = bindExistingMaterialNo`，`compositeType='SIMPLE'`，`processNos=[]` |
| `fingerprintMatched` | **`false`**（不进指纹） |
| `reusedHfPartNos` | `[]` |
| `productType` | `"SIMPLE"` |
| `reusedProductInfo` | `null` |
| `structureVersion` | 照常返回 `"v2"` |

**绑定路径的落库**（AC-18，🚫 不多不少）：

| 表 | 写不写 |
|---|---|
| `ds_quote_customer_part` | ✅ 1 行（`source='MANUAL'`） |
| `quotation_line_item` | ✅ 1 行 |
| `ds_quote_material` | ❌ **零新增**（料号已存在） |
| `ds_quote_material_bom` / `_element_bom` **主表** | ❌ **零新增**（沿用既有数据） |
| 同两表的 **`_record`** | ✅ **有投影行**（`origin_id` 认领主表既有行、值逐字一致、`source='QUOTE_DRAFT'`）—— 🔴 2026-09-10 更正，原写「零新增」是错的 |
| `sel_part_signature` | ❌ **零新增** |
| `quote_material_no_seq` / `quote_customer_code` / `material_customer_map` | ❌ **零新增**（不发号） |

### 2.4 `POST /configure-product/lookup-fingerprint` —— 指纹预览

**响应裁剪两个字段**（实测**全前端零消费方**，查完即丢）：

```jsonc
{
  "matched": true,
  "hfPartNo": "0028-2609000013",
  "matchedPartNo": "0028-2609000013",
  "snapshot": {
    "processes": [{ "processCode": "Z008", "seqNo": 1 }]   // ✅ 保留（AC-22 唯一消费方）
    // 🔴 删除 "unitWeightGrams"     —— 原查 v_compat_material_master.unit_weight，前端不读
    // 🔴 删除 "compositeProcesses"  —— 原查 capacity，前端不读
  }
}
```

**`processes` 的数据源切换**：`unit_price`（`cost_type='自制加工费' AND is_current`）→ **`ds_quote_self_process_fee`**

```sql
SELECT operation_no, operation_item_seq AS seq_no
  FROM ds_quote_self_process_fee
 WHERE customer_no = :customerNo AND material_no = :hfPartNo
 ORDER BY operation_item_seq
```

⚠️ 原 SQL 用 `DISTINCT ON (seq_no)` 跨客户取；新表有 `customer_no` 复合轴 ⇒ **改为按客户精确取**，`DISTINCT ON` 不再需要。

---

## 3. 错误码

| HTTP | code | 触发 | 服务的 AC |
|---|---|---|---|
| 409 | `CUSTOMER_PRODUCT_NO_TAKEN` | 客户产品编号已被占用（**含绑定路径**） | AC-19 |
| 400 | 🆕 `BIND_AND_PARTS_EXCLUSIVE` | `bindExistingMaterialNo` 与 `parts` 同时非空 | AC-20 反向 |
| 400 | 🆕 `BIND_MATERIAL_NOT_FOUND` | `bindExistingMaterialNo` 在该客户的 `ds_quote_material` 中不存在 | AC-18 边界 |
| 400 | `CUSTOMER_NO_REQUIRED`（沿用既有风格） | 两个候选端点缺 `customerNo` | AC-6 / AC-7 |

**沿用不变**：`CUSTOMER_PRODUCT_NO_REQUIRED` · `OUTSOURCED_PART_REQUIRED` · `PART_HAS_NO_MATERIAL` · `MATERIAL_DUPLICATED` · `MATERIAL_RATIO_SUM_INVALID` · `RECIPE_NOT_FOUND` · `RECIPE_HAS_NO_CONFIG` · `MATERIAL_SOURCE_AMBIGUOUS` · `CONFIG_NOT_FOUND` · `RECIPE_CUSTOM_NOT_ALLOWED` · `CUSTOM_CONTENT_SUM_NOT_ONE` · `CUSTOM_CONTENT_ELEMENT_UNKNOWN` · `PART_WEIGHT_REQUIRED` · `PART_TEXT_TOO_LONG` · `PART_TEXT_INVALID_CHAR`。

⚠️ **前端错误码解析的既有坑**（`ConfigureProductDrawer.tsx:259` 已处理，本次不要退回）：现网错误信封的 `code` 有时装的是 HTTP 状态码数字，前端只把「非纯数字的字符串」当业务码。

---

## 4. 不走接口层的内部契约（后端内部，前端无需关心）

| 项 | 契约 |
|---|---|
| `ds_quote_self_process_fee` 列映射 | `material_no`←`finished_material_no` · `input_material_no`←`code`（**零件料号**，D-4）· `operation_no`←`operation_no` · `operation_item_seq`←`seq_no` · `item_seq` 新增（`required=true`，按行序 1..N）· `currency`←`currency` · `pricing_unit`←`unit` · `value` 留 NULL（`required=false`） |
| `ds_quote_assembly_fee` 列映射 | `material_no`←`material_no`(父) · `customer_no` 新增（从 `customerCode` 透传）· `assembly_operation`←`process_no` · `item_seq`←`seq_no` · **`assembly_fee=0`**（D-5，`required=true`）· `currency`←`currency` · `pricing_unit`←`capacity_unit` · `defect_rate`←`default_defect_rate`。**无落点**：`process_name`（可 JOIN `process_master` 现算）· `production_type`（硬编码常量，不迁） |
| 写入器 | `VersionedGroupWriter.writeGroup(sheet, AxisKey.of(sd, customerNo, materialNo), rows, SOURCE_MANUAL, REASON_MANUAL_UPGRADE, operator)`。🚫 不再用 `VersionedV6Writer` |
| ~~`_record` patch 合并~~ | 🔴 **随 D-14 整条作废** —— 带版本表改直写主表，渲染侧不再读 `_record`。<br>📌 两条实证保留备查：① 渲染层拿到的 `driverRow` 是**视图输出行、身上没有主表 `id`** ⇒ `origin_id → 主表.id` 这条关联在那一层建不起来；② **回填侧的列级覆盖本来就有**（`DsBackfillCollector.patchedColumns`），不受本次影响 |
| `_record` 的角色 | **报价单对主数据的投影 / 核价回填的数据来源**。两侧挂点：导入 `CreateQuotationMaterializer:179` · 选配 `ConfigureProductResource`（建单末尾）。🚫 **不是渲染数据源** |
| `:quotationId` | 现成可用，与 `:customerCode` 同一条注入管线；`ConfigureSnapshotService` 是 `QuotationIdContext` 的 set 点之一 |
