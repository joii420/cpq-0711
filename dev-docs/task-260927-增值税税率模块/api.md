# VAT 税率模块 API 契约（闸门 A 草案）

> 任务：`task-260927-增值税税率模块` · A0 已按推荐方案裁决 · 尚未实现。
> 本文件只定义本期新增/变更契约；现有未列端点保持不变。所有写接口都执行现有菜单可见用户的后端权限校验，不能只依靠前端隐藏按钮。

## 1. 统一数据类型与规则

- 税率全部使用百分比数值，例如 `13.00` 表示 13%，公式上下文转为比例 `0.13`。
- API JSON 使用字符串传输 `BigDecimal`（避免 JavaScript `number` 丢精度）；前端输入可显示百分号但提交原始百分比字符串。
- 日期使用 `YYYY-MM-DD`；版本有效区间为 `[effectiveFrom, effectiveTo)`，`effectiveTo` 为空表示未设失效日。
- 同一 `tax_category_id` 的有效区间不得重叠；`effectiveFrom < effectiveTo`。
- 报价新行按报价日期解析有效版本；解析出的 `vatRate`、分类、版本、来源和解析日期写入报价行，不随主数据回查变化。
- 缺税务分类时使用系统默认分类；缺客户默认 VAT 时也使用系统默认分类在报价日期的有效 VAT。若默认分类无有效版本，返回可定位的业务错误，不静默使用旧值或 0。

## 2. 税务分类与版本

### `GET /api/cpq/tax-categories`

查询分类列表。参数：`status`（`ACTIVE`/`INACTIVE`/不传）、`keyword`、`includeVersions`、`effectiveOn`。响应：

```json
{
  "items": [{
    "id": "uuid",
    "code": "GENERAL_GOODS",
    "name": "一般货物",
    "status": "ACTIVE",
    "isDefault": true,
    "effectiveVatRate": "13.00",
    "effectiveVersionId": "uuid"
  }],
  "total": 1
}
```

### `POST /api/cpq/tax-categories`

创建分类。请求：`{code,name,status}`。`code` 全局唯一且规范化后比较；创建时不直接写 VAT 版本。

### `PUT /api/cpq/tax-categories/{id}`

修改名称/状态/说明，不允许改变已被引用分类的 code；停用分类不能被新产品绑定，但历史报价仍可读取。

### `GET /api/cpq/tax-categories/{id}/vat-versions`

按 `effectiveFrom DESC` 返回版本历史：

```json
[{"id":"uuid","taxCategoryId":"uuid","vatRate":"13.00","effectiveFrom":"2026-01-01","effectiveTo":null,"status":"ACTIVE","createdAt":"..."}]
```

### `POST /api/cpq/tax-categories/{id}/vat-versions`

创建版本。请求：`{vatRate,effectiveFrom,effectiveTo,status}`。税率范围 `[0,100]`；区间重叠返回 `409 TAX_RATE_PERIOD_OVERLAP`。

### `PUT /api/cpq/tax-categories/{id}/vat-versions/{versionId}`

仅允许修改未被报价行快照引用的版本；已引用版本只允许补充失效日期且不得造成重叠。需要改变已引用税率时必须新增版本。

### `PUT /api/cpq/tax-categories/default`

设置默认分类。请求 `{taxCategoryId}`。系统始终只能有一个默认分类；初始化迁移插入 `GENERAL_GOODS / 一般货物` 及一条 `13.00` 的当前版本。删除/停用默认分类必须先指定新默认分类。

## 3. 客户默认 VAT

现有客户接口扩展，不新增独立客户税率页面路由：

- `POST /api/cpq/customers`、`PUT /api/cpq/customers/{id}` 的请求增加可选 `defaultVatRate`。
- `GET /api/cpq/customers`、`GET /api/cpq/customers/{id}` 的 DTO 增加 `defaultVatRate`。
- `GET /api/cpq/customers/import/template` 增加 `default_vat_rate` 列；`POST /api/cpq/customers/import` 按现有整份预校验/整份拒绝语义校验范围 `[0,100]`。

空值表示未设置，不等于 0%；客户字段只作为报价头默认/产品税率缺失时兜底，不绑定税务分类。

## 4. 销售产品税务分类

### `GET /api/cpq/v6/sales-products`

在现有销售产品列表响应中增加：`taxCategoryId`、`taxCategoryCode`、`taxCategoryName`、`effectiveVatRate`、`taxCategoryState`（`SET`/`UNSET`/`NO_EFFECTIVE_VERSION`）。查询必须按 `customerNo + materialNo` 隔离。

### `PUT /api/cpq/v6/sales-products/{customerNo}/{materialNo}/tax-category`

请求 `{taxCategoryId}`。允许菜单可见用户修改；写入 `ds_quote_material.tax_category_id`。传空值表示清除显式绑定，后续按默认分类解析；不存在/停用分类返回 `400/409`。

### 客户产品列表

现有客户产品列表响应增加同名只读税务字段，服务端通过 `(customerNo, materialNo)` LEFT JOIN 销售产品主记录；查不到关联显示 `taxCategoryState=UNSET`，不得把客户默认 VAT 伪装成产品税率。

## 5. 报价与报价行

### `POST /api/cpq/quotations`

创建报价时响应及 `GET /api/cpq/quotations/{id}` 增加：报价头 `defaultVatRate`、`defaultVatSource`（`CUSTOMER`/`DEFAULT_CATEGORY`）。报价头默认值取客户 `defaultVatRate`，为空则取默认分类在报价日期的有效 VAT；它只是新增行默认值。

### 新增报价行的统一内部契约

基础数据导入物化、选配添加、复制报价等所有新增行路径调用同一个 `VatSnapshotService.resolveForNewLine(quotationId, customerNo, materialNo, quotationDate)`：

```json
{
  "taxCategoryId":"uuid",
  "taxCategoryVersionId":"uuid",
  "vatRate":"13.00",
  "vatRateRatio":"0.13",
  "vatSource":"PRODUCT_CATEGORY|QUOTATION_DEFAULT|DEFAULT_CATEGORY",
  "vatEffectiveFrom":"2026-01-01",
  "resolvedAt":"..."
}
```

解析顺序：销售产品有效分类版本 → 报价头默认 VAT → 默认分类有效版本。解析结果一次写入 `quotation_line_item`。

### `GET /api/cpq/quotations/{id}` / `POST /api/cpq/quotations/{id}/recalculate`

报价行 DTO 增加 `vatRate`、`vatRateRatio`、`taxCategoryId`、`taxCategoryVersionId`、`vatSource`、`vatEffectiveFrom`、`unitPriceWithTax`（若该价格策略/公式有产出）。`recalculate` 只能读取行快照，不重新解析主数据税率。

### `GET /api/cpq/quotations/customer-part-candidates`

现有候选响应增加销售产品税务分类摘要，供“从基础数据导入”预览显示默认分类/未设置状态；导入未知分类编码必须整批拒绝，错误包含 Excel 行号、列名和编码。

## 6. 公式标签与含税元素单价

- 组件公式字段的标签元数据增加 `{label:"增值税", token:"vat_rate", valueType:"RATIO"}`；序列化 token 统一为 `{"type":"vat_rate"}`，不使用全局变量 token。
- 报价行上下文绑定 `vatRateRatio`；无报价行快照的运行上下文返回结构化状态 `{code:"VAT_QUOTE_CONTEXT_REQUIRED", label:"待报价税率"}`，不能读取系统当前税率。
- 物料元素 BOM 的含税价格策略为可选派生结果：`taxIncludedUnitPrice = elementUnitPrice × (1 + vatRateRatio)`，不得覆盖未税策略；没有报价上下文时返回上述状态。
- 该策略在 QUOTE、COST_BASIC、COST_DETAIL 三处使用同一契约；是否在组件/模板公式中引用 VAT 仍由用户配置，不自动给所有金额加税。

## 7. 错误码

`400 INVALID_VAT_RATE`、`400 INVALID_EFFECTIVE_PERIOD`、`400 TAX_CATEGORY_INACTIVE`、`400 VAT_CONTEXT_REQUIRED`、`404 TAX_CATEGORY_NOT_FOUND`、`409 TAX_RATE_PERIOD_OVERLAP`、`409 TAX_CATEGORY_IN_USE`、`409 UNKNOWN_TAX_CATEGORY_CODE`、`422 IMPORT_VALIDATION_FAILED`。

## 8. 兼容边界

现有 `quotation.tax_rate`、旧 `customer_tax` 和历史普通字段“税率”不在本期自动回填或改写；旧接口/历史报价读取保持原语义。新 API、公式标签和报价行快照只使用明确的 `vat*` 命名。

## 9. AC 追溯

本契约覆盖：AC-1（产品税率快照）、AC-2（6% 优先级）、AC-3（标签 token）、AC-4（含税/未税公式）、AC-5（快照重算）、AC-6（删除后添加）、AC-7（多税率明细）、AC-8（三级回退）、AC-9/AC-10（导入默认与整批拒绝）、AC-11（无上下文状态）、AC-12（旧字段兼容）。
