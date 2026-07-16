# api.md — 选配/报价/核价 三模板统一到「客户产品分类」轴（update-071501）

> 本文档为本次改动的**前后端接口契约唯一基准**。所有变更以 §9 澄清备案为准。
> 约定：所有接口前缀 `/api/cpq`；统一信封 `ApiResponse<T> = { code, message, data }`；鉴权沿用现有 `@RoleAllowed`。
> **本次是"换轴"，新增接口极少**——大部分是既有接口的**字段改名 / 入参来源变更**。逐条标注 🔴新增 / 🟡改字段 / 🟢不变(仅来源变)。

---

## 0. 变更总览

| 接口 | 变更类型 | 摘要 |
|---|---|---|
| `POST /api/cpq/sel-templates` | 🟡改字段 | 请求体 `industryCode → productCategoryId` |
| `GET /api/cpq/sel-templates` | 🟡改字段 | 响应 `industryCode → productCategoryId` |
| `GET /api/cpq/sel-templates/{id}` | 🟡改字段 | 同上 |
| `GET /api/cpq/sel-templates/effective` | 🟡改字段 | **URL/入参不变**；响应 `resolvedIndustryCode → resolvedCategoryId` |
| `DELETE /api/cpq/sel-templates/{id}` | 🟢不变 | — |
| `GET /api/cpq/product-categories` | 🟢不变 | 复用（选配模板管理 / 客户表单拉分类列表） |
| `POST /api/cpq/customers` | 🟡改字段 | 请求体增 `productCategoryId`（必填，后端兜底默认分类） |
| `PUT /api/cpq/customers/{id}` | 🟡改字段 | 请求体增 `productCategoryId`（可改） |
| `GET /api/cpq/customers` / `GET /api/cpq/customers/{id}` | 🟡改字段 | `CustomerDTO` 增 `productCategoryId` |
| `GET /api/cpq/templates/match-customer-quote` | 🟢不变(来源变) | 签名不变；`categoryId` 由前端从客户带出 |
| `GET /api/cpq/templates`(list COSTING) | 🟢不变(来源变) | 核价模板过滤，`categoryId` 来源同上 |
| `POST /api/cpq/import-session/{id}/commit` | 🟡语义 | `categoryId` 以 `customer.product_category_id` 为权威 |

---

## 1. 选配模板 CRUD（换字段）

### 1.1 `POST /api/cpq/sel-templates` — 新建/更新选配模板（upsert）🟡

**语义不变**：按归属维度 upsert（有则整体替换 items/values，无则新建）。**唯一变化：归属维度 `industryCode → productCategoryId`**。

请求体 `SelTemplateUpsertRequest`：
```jsonc
{
  "productCategoryId": "uuid",   // 🟡 原 industryCode(String)；现 UUID，引用 product_category.id；必填
  "name": "螺栓类选配模板",
  "status": "ACTIVE",            // ACTIVE | INACTIVE，可空默认 ACTIVE
  "items": [
    { "paramTypeCode": "MATERIAL", "enabled": true,  "allowedValues": ["recipeCodeA", "recipeCodeB"] },
    { "paramTypeCode": "ELEMENT",  "enabled": true,  "allowedValues": [] },
    { "paramTypeCode": "PROCESS",  "enabled": false, "allowedValues": [] }
  ]
}
```

响应 `data: SelTemplateDTO`：
```jsonc
{
  "id": "uuid",
  "productCategoryId": "uuid",   // 🟡 原 industryCode
  "name": "螺栓类选配模板",
  "status": "ACTIVE",
  "version": 0,
  "items": [ { "paramTypeCode": "MATERIAL", "enabled": true, "sortOrder": 0, "allowedValues": ["..."] } ]
}
```

- **唯一约束**：`sel_template.product_category_id` UNIQUE（一产品分类一套）。同 `productCategoryId` 重复 upsert = 更新既有。
- **校验**：`productCategoryId` 必填且必须为存在的 `product_category.id`（后端校验，不存在报 400）。
- 说明：`SelTemplateDTO` **不返** `productCategoryName`；前端已拉 `product-categories` 列表自行 id→name 映射（避免 list N+1）。

### 1.2 `GET /api/cpq/sel-templates` — 列表 🟡
响应 `data: SelTemplateDTO[]`，字段同 1.1（`productCategoryId`）。**后端 list 不得逐条查 product_category**（N+1）；只返 id，前端映射名。

### 1.3 `GET /api/cpq/sel-templates/{id}` — 详情 🟡
响应 `data: SelTemplateDTO`，含完整 items。

### 1.4 `DELETE /api/cpq/sel-templates/{id}` 🟢 — 不变。

### 1.5 `GET /api/cpq/sel-templates/param-types` / `.../candidates?paramTypeCode=` 🟢
选配参数池 / 候选值接口**完全不变**（材质库/工序库/元素候选）。

---

## 2. 选配有效模板解析（URL 不变，响应改字段）🟡

### `GET /api/cpq/sel-templates/effective?customerNo={code}`

- **入参不变**：仍传 `customerNo`（客户业务码）。前端 `ConfigureProductDrawer` 调用方式不变。
- **内部解析源变更**：`customer.industryCode → customer.productCategoryId`。
- **兜底链（D10，逻辑不变）**：
  1. `customer.productCategoryId` 对应的选配模板 → 命中则用；
  2. 否则退 `name='默认分类'` 产品分类的选配模板（原 `__DEFAULT__` 哨兵的替身）；
  3. 都没有 → `hasTemplate=false`（前端报"缺选配模板"）。

响应 `data: EffectiveTemplateDTO`：
```jsonc
{
  "customerNo": "CUST-0001",
  "resolvedCategoryId": "uuid",   // 🟡 原 resolvedIndustryCode(String)；现 UUID；未命中时 null
  "usedDefault": false,           // 🟢 保留：true=走了"默认分类"兜底
  "hasTemplate": true,
  "templateId": "uuid",
  "params": [
    { "paramTypeCode": "MATERIAL", "name": "材质", "valueMode": "single",
      "effectiveValues": [ { "key": "recipeCodeA", "label": "AgNi11#" } ] }
  ]
}
```

> **回归红线**：`ConfigureProductService.effectiveEnabledTypes` 也消费本 DTO。若其读取过 `resolvedIndustryCode`，须同步改 `resolvedCategoryId`；grep 全工程确保改净（R2）。

---

## 3. 客户 CRUD（增 productCategoryId）🟡

### 3.1 `POST /api/cpq/customers` — 新建
请求体 `CreateCustomerRequest` 增字段：
```jsonc
{
  "name": "苏州西门子",
  "industryCode": "...",          // 🟢 行业保留不动
  "productCategoryId": "uuid",    // 🔴 新增；必填；引用 product_category.id
  "level": "STANDARD",
  "contacts": [ /* 不变 */ ]
}
```
- **必填（业务语义）**：前端保证必选（默认"默认分类"）；**后端兜底**：若 `productCategoryId` 为空 → 自动填 `name='默认分类'` 的 id（保证"不能空"，D3）。
- **DB 列可空（方案B，2026-07-16）**：`customer.product_category_id` 不加 DB `NOT NULL`；"不能空"由上述应用层三重保证（前端必填 + create 兜底 + 迁移 backfill）。见 `优化需求说明.md §9.7`。

### 3.2 `PUT /api/cpq/customers/{id}` — 更新
- 请求体同上增 `productCategoryId`；**可改**（D4）：`if (productCategoryId != null) customer.productCategoryId = ...`。
- 改绑不追溯已有报价单（D4）。

### 3.3 `GET /api/cpq/customers` / `GET /api/cpq/customers/{id}`
- `CustomerDTO` 增 `productCategoryId`（UUID；DB 列不加 NOT NULL，方案B；业务路径永非空）。
- **不返** `productCategoryName`（D5 列表不展示；编辑表单前端用 product-categories 列表映射名）。
- list 保持单查 `CustomerDTO.from`，**不得**为分类名引入 N+1。

---

## 4. 报价/核价模板匹配（签名不变，来源变）🟢

### 4.1 `GET /api/cpq/templates/match-customer-quote?customerId={uuid}&categoryId={uuid}`
- **签名/响应完全不变**（`TemplateMatchResult`：`CUSTOMER_SPECIFIC | GENERAL_FALLBACK | MIXED | NONE` + templates[]）。
- **唯一变化**：前端调用时 `categoryId` **来自客户绑定值**（不再手选）。

### 4.2 `GET /api/cpq/templates?categoryId&status=PUBLISHED&templateKind=COSTING&size=200`
- **不变**：核价模板列表，前端按 `customerId` 过滤 + 排序（客户专属优先）。
- `categoryId` 来源同 4.1。

---

## 5. 报价单创建 commit（categoryId：前端只读锁定 + 后端审计）🟡

### `POST /api/cpq/import-session/{sessionId}/commit`
请求体保持：`{ name, categoryId, customerTemplateId, costingTemplateId }`。

> **架构订正（2026-07-16，实测 `ImportSessionService.commit`）**：commit **不在服务端重新匹配模板**——`customerTemplateId`/`costingTemplateId` 是前端在调 commit **之前**用 `(customerId, categoryId)` 调 `match-customer-quote` / 核价 list 预匹配好、直接塞进请求体的；commit 里 `categoryId` 是**透传字段**（下游不消费、不持久化）。原"commit 用 categoryId 匹配模板"的假设不成立。

- **"以客户为准"的真实落点在前端**：`categoryId` 由客户绑定只读带出（fronttask F3），用户改不了 → 预匹配用的就是客户产品分类。
- **后端 commit 仅做防御性一致性审计**：查一次 `Customer`（`session.customerId`，无 N+1），若 `req.categoryId != customer.productCategoryId` 则 `LOG.warn` 留痕；**不覆盖模板、不改持久化、不重匹配**（重匹配会破坏 MIXED 多模板下用户的手选语义，且越出"只做换轴"边界）。
- 产品分类不持久化到 `quotation`；最终固化 `customer_template_id` / `costing_card_template_id`。

---

## 6. 契约对齐检查清单（前后端联调必过）

- [ ] 选配模板 upsert/list/detail 全链路 `productCategoryId`（无残留 `industryCode`）。
- [ ] `/sel-templates/effective` URL 不变；响应 `resolvedCategoryId`；`ConfigureProductService` 无 `resolvedIndustryCode` 残留。
- [ ] 客户 create/update/DTO 三处 `productCategoryId`；create 后端兜底默认分类；update 可改。
- [ ] `match-customer-quote` / 核价 list 的 `categoryId` 前端从客户带出（非手选）。
- [ ] commit 服务端以 `customer.product_category_id` 为权威。
- [ ] 全链路无 N+1（客户 list、选配模板 list、模板匹配）。
