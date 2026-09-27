# 接口契约 · 客户编号与客户 Excel 导入

> 前后端唯一协调物。Base URL 为 `/api/cpq`；统一响应沿用 `ApiResponse`（`{code,message,data}`）。

## 0. 公共规则

- 权限沿用客户 Resource：`SALES_REP`、`SALES_MANAGER`、`PRICING_MANAGER`、`SYSTEM_ADMIN`。
- 客户编号 `code`：请求可省略/空白；非空时原始值首尾含空格返回 400，不做静默裁剪；长度 1~50，合法值比较时按 `lower(trim(code))` 全局唯一。
- 客户名称 `name`：必填，长度 1~200；原始值首尾含空格返回 400；比较时 `lower(trim(name))` 全局唯一。更新时排除当前客户自身。
- 唯一冲突返回 HTTP 409；字段格式/文件错误返回 HTTP 400；任何导入校验错误都不写客户或联系人。

## 1. `POST /customers` — 创建客户

`CreateCustomerRequest` 新增可选字段：

```json
{
  "code": "ABC-001",
  "name": "华东客户",
  "level": "STANDARD",
  "industryCode": "IND-01",
  "productCategoryId": "uuid",
  "region": "华东",
  "address": "上海",
  "creditLimit": "100000.00",
  "paymentMethod": "月结",
  "remarks": "",
  "contacts": [{ "name": "张三", "role": "采购", "phone": "13800138000", "email": "", "wechat": "", "isPrimary": true }]
}
```

- `code` 省略/空白：沿用 `customer_code_seq` 生成 `CUST-xxxx`。
- `code` / `name` 冲突：HTTP 409，`ApiResponse.data` 为空。
- 成功：HTTP 200，`data` 为现有 `CustomerDTO`，其中 `code` 是最终编号。

## 2. `PUT /customers/{id}` — 编辑客户

- `code` 不可通过编辑接口修改（即使请求带 code 也保持原编号）。
- `name` 若传入，按公共规则校验唯一性；冲突 HTTP 409，原记录不变。
- 其他字段与现有契约不变。

## 3. `GET /customers/import/template` — 下载导入模板

- 成功 HTTP 200，Content-Type 为 `.xlsx`，文件名 `客户导入模板.xlsx`。
- Sheet 名：`客户`。
- 表头（必须按顺序）：

`客户编号, 客户名称, 客户等级, 所属行业, 产品分类, 所属区域, 地址, 信用额度, 付款方式, 备注, 联系人姓名, 联系人职务, 联系人电话, 联系人邮箱, 联系人微信`

- 示例/批注说明：客户编号可空；客户名称/联系人姓名/联系人电话必填；电话为 11 位数字；一个数据行代表一个客户和一个主要联系人。

## 4. `POST /customers/import` — 导入客户 Excel

请求为 `multipart/form-data`，字段 `file`（`.xlsx`）。

成功 HTTP 200：

```json
{ "code": 200, "message": "success", "data": {
  "totalRows": 2,
  "createdCustomers": 2,
  "createdContacts": 2,
  "errors": []
} }
```

校验失败 HTTP 400/409：

```json
{ "code": 409, "message": "客户导入校验未通过，本次未写入任何数据", "data": {
  "totalRows": 2,
  "createdCustomers": 0,
  "createdContacts": 0,
  "errors": [
    { "row": 3, "column": "客户编号", "reason": "客户编号已存在：ABC-001" },
    { "row": 4, "column": "客户名称", "reason": "客户名称在文件内重复：华东客户（第3行）" }
  ]
} }
```

错误原因至少覆盖：文件不是 xlsx/文件损坏、表头不匹配、必填项为空、首尾含空格、超出长度、非法等级、客户编号已存在、客户名称已存在、文件内编号/名称重复、行业/产品分类不存在、电话格式错误、信用额度格式错误。

处理顺序固定为：解析 → 全量规范化/校验（零写库）→ 一次性查重与主数据预加载 → 同一事务批量创建客户及联系人。任一错误整份拒绝；空数据文件是成功的 0/0/0 报告。
