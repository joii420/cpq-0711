# 接口契约 · task-260909 已有产品抽屉数据源收敛

> **本次有契约变更**，结案前须按 `task-docs.md §2.5` 回写 `dev-docs/main-api.md`。

---

## 1. `GET /api/cpq/quotations/{quotationId}/existing-products`

报价单「从已有产品添加」抽屉的列表数据源。**路径、方法、鉴权、分页包络全部不变**，只改数据来源与返回字段。

### 1.1 请求（不变）

| 位置 | 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| path | `quotationId` | UUID | 是 | 服务端据此派生 `customer.code`，**前端不传客户** |
| query | `customerProductNo` | string | 否 | 客户产品编号，模糊匹配（`ILIKE %v%`） |
| query | `salesPartNo` | string | 否 | 销售料号，模糊匹配 |
| query | `productName` | string | 否 | 品名，模糊匹配 |
| query | `spec` | string | 否 | 规格，模糊匹配 |
| query | `page` | int | 否 | 默认 0，**0-based** |
| query | `size` | int | 否 | 默认 20 |

四个过滤参数全可选、**AND 组合**、模糊匹配。鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。

⚠️ 过滤谓词**在去重之前生效** —— 保证「按任一客户产品编号都能搜到该料号」（AC-14）与「该料号只出现一行」（AC-13）同时成立。

### 1.2 响应包络（不变）

`ApiResponse<PageResult<ExistingProductDTO>>`：

```json
{
  "code": 200,
  "data": {
    "content": [ /* ExistingProductDTO[] */ ],
    "page": 0,
    "size": 20,
    "totalElements": 2662,
    "totalPages": 134
  }
}
```

🚫 字段名是 `content` / `totalElements`，**不是** `items` / `total`。前端 `quotationService.listExistingProducts` 已内部解开 `ApiResponse` 信封，直接返回 `PageResult`。

### 1.3 `ExistingProductDTO` 字段变更表

| 字段 | 类型 | 本次 | 取值来源 |
|---|---|---|---|
| `materialNo` | string | 不变 | `ds_quote_customer_part.material_no`（销售料号） |
| `customerProductNo` | string? | 不变 | 代表编号（`DISTINCT ON` 取 `created_at` 最早的） |
| `customerProductNos` | string[]? | 不变 | 该料号名下**全部**编号，按 `created_at` 升序 |
| **`customerDrawingNo`** | string? | 🆕 **新增** | `ds_quote_customer_part.customer_drawing_no` |
| `customerMaterialName` | string? | 🔧 **语义变更** | 改为 `ds_quote_customer_part.customer_part_name`（客户侧名称） |
| `productName` | string? | 🔧 **语义变更** | 改为 `v_compat_material_master.material_name`（主数据品名） |
| `spec` | string? | 不变 | `COALESCE(NULLIF(specification,''), dimension)` |
| `source` | string | 🔧 **语义变更** | `IMPORT`→`"EXISTING"`；`MANUAL`→`"CONFIGURED"`。**按来源列判定，不再按「编号是否为空」** |
| `configProductType` | string? | 不变 | `SIMPLE` / `COMPOSITE`，取自 `sel_part_signature` 最近一条；非选配为 `null` |
| ~~`has3d`~~ | ~~boolean~~ | ❌ **删除** | 3D 预览已移除（D-4） |
| ~~`thumbnailUrl`~~ | ~~string?~~ | ❌ **删除** | 同上 |

#### 🚨 `productName` / `customerMaterialName` 的语义拆分（本次最容易踩的一条）

**改动前**：后端两个字段同取 `r[2]`，值是一条 COALESCE 兜底链（`customer_material_name` → `material_name` → `material_type` → `material_no`）。前端两列渲染出来**必然相同**。

**改动后**：两个字段取两个**不同的列**：

```
customerMaterialName ← dqcp.customer_part_name            （客户怎么叫这个件）
productName          ← v_compat_material_master.material_name（主数据里的品名）
```

⚠️ 两者都可能为空，各自独立兜底：
- `customerMaterialName` 为空 → 返回 `null`，前端渲染 `—`
- `productName` 为空 → 回退 `material_no`（保证品名列不空白），🚫 **不再回退到 `customer_part_name`**（那会让两列又变回相同）

### 1.4 数据来源（本次核心变更）

**改动前**：三支 `UNION ALL`（`material_customer_map` ∪ `sel_product_no` ∪ `ds_quote_customer_part`）+ 两条 `NOT EXISTS` 互斥挡板 + `sel_part_signature` 兜底谓词。

**改动后**：单表 + 一个 JOIN。

```sql
SELECT DISTINCT ON (d.material_no)
       d.material_no, d.customer_product_no, d.customer_drawing_no, d.customer_part_name,
       v.material_name, COALESCE(NULLIF(v.specification,''), v.dimension) AS spec,
       CASE WHEN d.source = 'IMPORT' THEN 'EXISTING' ELSE 'CONFIGURED' END AS source,
       (SELECT sps.product_type FROM sel_part_signature sps
         WHERE sps.quote_part_no = d.material_no AND sps.customer_no = d.customer_no
         ORDER BY sps.created_at DESC LIMIT 1) AS config_product_type,
       agg.all_product_nos
  FROM ds_quote_customer_part d
  LEFT JOIN v_compat_material_master v ON v.material_no = d.material_no
  LEFT JOIN ( /* array_agg 全部编号，按 material_no 分组 */ ) agg ON agg.material_no = d.material_no
 WHERE d.customer_no = :customerNo
 ORDER BY d.material_no, d.created_at, d.customer_product_no
```

**保留不动的三件**：
1. `DISTINCT ON (material_no)` 按销售料号去重（唯一键是 `(customer_no, customer_product_no)`，同一料号可挂多编号）
2. `array_agg` 聚合子查询出 `customerProductNos`（🚫 不许改成逐行查，那是 backtask B-19 治过的 N+1）
3. 服务端分页 `setFirstResult` / `setMaxResults`

**删除的四件**：① mcm 分支 ② spn 分支 ③ 两条 `NOT EXISTS` 挡板 ④ `model_config` JOIN。

### 1.5 错误码（不变）

| 码 | 场景 |
|---|---|
| 404 | 报价单不存在 |
| 400 | 报价单未绑定客户（`customer_id` 为空） |
| 401 | 未认证 |
| 403 | 角色不在白名单 |

---

## 2. 本次**不涉及**的接口

| 接口 | 为什么列出来 |
|---|---|
| `GET /api/cpq/model-configs/current` | 前端不再调用（3D 预览移除），但**端点本身完全不动**，`ModelConfigManagement` 页仍在用 |
| `POST /api/cpq/dataset/quote/quotation-import` | 写入侧，本次只改读侧 |
| 「加入报价单」相关端点 | 下游链路不改，仅做回归（AC-11） |
