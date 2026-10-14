# 接口契约

## 兼容约定

新契约以 `customerIds` 为准；旧 `customerId/customerName` 保留为兼容字段。若请求同时传数组和标量，以 `customerIds` 为准。空数组或 `null` 表示通用模板，不创建关联行。响应中的旧标量字段仅用于兼容展示：单客户时填该客户，多个客户时 `customerId` 为空、`customerName` 可为空或使用“多个客户”，完整范围以数组/摘要为准。

## 模板接口

### `GET /api/cpq/templates`

- 原有 `customerId` 查询参数保留，含义改为“模板关联了该客户”；通用模板仍由原有空客户筛选语义处理。
- 响应模板增加：

```json
{
  "customerIds": ["uuid-a", "uuid-b"],
  "customers": [
    {"id": "uuid-a", "code": "CUST-A", "name": "客户 A"},
    {"id": "uuid-b", "code": "CUST-B", "name": "客户 B"}
  ]
}
```

- 保留 `customerId`、`customerName` 兼容字段；不改变分页、排序、状态和模板类型参数。

### `GET /api/cpq/templates/{id}`

返回完整 `customerIds`、`customers` 和兼容标量。关联客户按名称/稳定 ID 排序，避免刷新后标签跳动。

### `POST /api/cpq/templates`

请求新增 `customerIds: UUID[]`；未提供时读取旧 `customerId`。去重后校验客户存在且当前用户有权使用；空范围表示通用模板。创建仍默认草稿，其他字段语义不变。

### `PUT /api/cpq/templates/{id}`

请求同创建。只有草稿允许修改客户范围；已发布/归档返回 400/409 明确错误。保存客户范围与模板基础字段在同一事务内完成。

### `POST /api/cpq/templates/{id}/new-draft`

新草稿复制源模板的全部客户关联；仍通过发布流程生成新版本，不修改源模板。

## 匹配接口

### `GET /api/cpq/templates/match-customer-quote?customerId=&categoryId=`

路径、参数和响应结构不变。后端用 `template_customer` 的存在性替换旧 `template.customer_id = ?`，仍只使用 `QUOTATION`、`PUBLISHED`、同产品分类和既有排序，并按当前代码组装专属、通用和 `MIXED` 结果。客户 C 未在关联表时仍沿用原通用回退。

## 核价接口

核价模板列表和按客户过滤沿用现有路径/参数，仅将客户归属判断改为关联表；不新增第二套客户匹配规则。

## 错误与权限

- 客户不存在、无权使用或数组包含非法值：HTTP 400，返回字段级错误，不产生部分保存。
- 已发布/归档模板修改范围：HTTP 400 或 409，错误码和现有模板状态错误风格一致。
- 未授权列表/匹配/写入继续沿用现有鉴权和角色校验。
- 不新增独立“绑定客户”接口，避免绕过模板草稿/发布事务；客户范围随创建/更新原子提交。
