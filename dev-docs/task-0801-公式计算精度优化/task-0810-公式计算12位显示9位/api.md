# api.md · 公式计算 12 位、显示 9 位

> 本任务不新增、删除、重命名端点，不改变 HTTP 方法、路径、鉴权和错误码。变更的是精度敏感十进制字段的类型与数值边界。本文是前后端联调契约，完成测试后按任务规则回写 `dev-docs/main-api.md`。

## 1. 通用精度契约

### 1.1 Decimal 类型

精度敏感字段包括：金额、单价、数量、费率、公式变量/结果、小计、合计、差异值，以及卡片/Excel 动态数值。

新响应统一为 JSON string：

```json
{
  "totalAmount": "98765431.123456789012",
  "taxRate": "13",
  "subtotal": "0.333333333333"
}
```

字符串规则：

- 普通十进制，禁止 `1.2E+8`。
- 去无意义尾零，整数 `"5"`，零 `"0"`。
- 最多 12 位小数；公式节点使用 HALF_UP。
- null 仍是 JSON null，不允许写成 `"0"`。

### 1.2 请求契约

受影响请求的精度字段只接受规范十进制字符串：

```json
{ "originalAmount": "123.456789012345" }
```

JSON number 不再属于精度字段的合法协议类型，沿用现有 400 类型校验返回错误。后端直接从字符串构造 BigDecimal；前端直接从字符串构造 Decimal，任一端都不得经过 Double/JS `number`。

### 1.3 不改变的 number

分页、页码、行数、排序值、HTTP 状态、布尔开关等结构性整数继续使用 JSON number。UUID/日期/枚举保持原类型。

### 1.4 嵌套快照

`quoteCardValues`、`costingCardValues`、`quoteExcelValues`、`costingExcelValues`、组件 `rowData` 仍是外层 JSON string。该字符串内部：

- 新计算/数值节点写 decimal string。
- 历史 numeric token 由后端 Jackson 直接读为 BigDecimal，或由前端无损 JSON parser 从原始字面量直接转 Decimal；禁止普通 `JSON.parse` 后再转。
- 文本、布尔、null 不变。

示例：

```json
{
  "quoteCardValues": "{\"tabs\":[{\"formulaResults\":[{\"rowKey\":\"1\",\"values\":{\"金额\":\"0.333333333333\"}}]}]}"
}
```

## 2. API-P1 · 报价主链路

鉴权沿用各端点当前 RBAC。字段名和信封结构不变。

| 编号 | 方法与路径 | 精度字段变化 | 对应需求 |
|---|---|---|---|
| P1-01 | `GET /api/cpq/quotations` | `totalAmount`, `originalAmount` 等金额为 decimal string | FR-7/9 |
| P1-02 | `GET /api/cpq/quotations/{id}` | 单头、lineItems、componentData 金额与四份值快照按 §1 | FR-5~9 |
| P1-03 | `POST /api/cpq/quotations` | 创建 DTO 无精度入参；响应金额为 decimal string | FR-7 |
| P1-04 | `PUT /api/cpq/quotations/{id}/draft` | 草稿内金额/数量/快照 numeric 新写 string | FR-5~8/11 |
| P1-05 | `POST /api/cpq/quotations/{id}/refresh-card-snapshot` | 新快照 12 位 string；仅 DRAFT 改写 | FR-3/6/11 |
| P1-06 | `POST /api/cpq/quotations/{id}/ensure-card-values` | 返回卡片值 12 位 string | FR-3/7 |
| P1-07 | `POST /api/cpq/quotations/{id}/ensure-excel-values` | 返回 Excel 值 12 位 string | FR-3/7 |
| P1-08 | `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | 精度 `value` 只接受 decimal string；返回快照内部同为 string | FR-6~8 |
| P1-09 | `POST /api/cpq/quotations/{id}/calculate-discount` | `originalAmount` 必须为 decimal string；结果为 12 位 string | FR-2/7 |
| P1-10 | `POST /api/cpq/quotations/{id}/recalculate` | 计算结果/单头/行金额 12 位 string | FR-2~7 |
| P1-11 | `GET /api/cpq/quotations/{id}/snapshot` | 不重算冻结值；新草稿快照遵守 §1.4 | FR-6/11 |
| P1-12 | `GET /api/cpq/quotations/{id}/field-trace` | trace 中数值以 decimal string 返回，保留 12 位工作值 | FR-7/12 |
| P1-13 | `POST /api/cpq/quotations/{id}/submit` | 请求快照 string；响应金额 string；对账按 12 位 | FR-11/12 |
| P1-14 | `POST /api/cpq/quotations/{id}/copy` | 冻结复制不重算；跨模板重算输出新 string | FR-6/11 |
| P1-15 | `GET /api/cpq/quotations/{id}/costing-approve/preview` | 预览金额/差异为 decimal string | FR-7 |
| P1-16 | `POST /api/cpq/quotations/{id}/costing-approve` | 落库和响应金额 12 位 string | FR-5/7 |
| P1-17 | `POST /api/cpq/quotations/line-items/{lineItemId}/reconcile-report` | frontend/backend value 与 inputs 接受 decimal string；服务端按 BigDecimal 记录 | FR-12 |
| P1-18a | `POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/add-leaf` | 请求仅含 UUID/节点/料号；响应 `quoteCardValues` 内精度值适用 §1.4 | FR-6/7 |
| P1-18b | `POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete-preview` | 请求和响应均为结构字段，无精度字段 | FR-6/7 |
| P1-18c | `POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete` | 请求仅含 UUID/模式/节点/令牌；返回投影快照适用 §1.4 | FR-6/7 |
| P1-18d | `POST /api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row` | 请求仅含 UUID/行键/指纹；返回投影快照适用 §1.4 | FR-6/7 |
| P1-18e | `POST /api/cpq/quotations/{qid}/line-items/{lid}/restore-driver-rows` | 请求仅含组件 UUID；返回投影快照适用 §1.4 | FR-6/7 |

### P1 请求示例 · 保存草稿

```json
{
  "lineItems": [{
    "subtotal": "1.234567891235",
    "lineUnitPrice": "1.234567891235",
    "lineTotalAmount": "98765431.123456789012",
    "componentData": [{
      "subtotal": "1.234567891235",
      "rowData": "[{\"单价\":\"0.333333333333\",\"数量\":\"3\"}]"
    }]
  }]
}
```

### P1 错误码

沿用当前 400/401/403/404/409/500。新增的类型校验失败仍返回 400，message 必须指出字段和无法解析的原值；不得静默按 0。

## 3. API-P2 · 公式求值

### P2-01 `POST /api/cpq/formulas/evaluate`

- 鉴权：沿用当前端点。
- 请求：expression、templateId 等结构不变；bindings/context 中精度值只接受 decimal string。
- 响应：公式数值结果为 decimal string，12 位节点精度。

```json
{
  "expression": "1 / 3",
  "templateId": "00000000-0000-0000-0000-000000000001"
}
```

```json
{
  "success": true,
  "data": {
    "value": "0.333333333333"
  }
}
```

### P2-02 `POST /api/cpq/formulas/batch-evaluate`

- 每项请求/响应遵守 P2-01。
- 顺序、错误隔离和 `templateId` 透传不变。
- 批量中单项非法公式沿用原错误结构，不因字符串精度契约变成 HTTP 整批失败。

### P2 错误码

保持当前语义：非法表达式、参数错误、路径错误、权限错误不新增状态码。除零仍按父任务既有业务语义返回零值 `"0"`。

## 4. API-P3 · Excel 视图与导出

| 编号 | 方法与路径 | 精度契约 |
|---|---|---|
| P3-01 | `GET /api/cpq/quotations/{id}/excel-view` | 计算列数值 decimal string；原始列保持原精度 |
| P3-02 | `POST /api/cpq/quotations/{id}/excel-view/dry-run` | 试算结果 decimal string |
| P3-03 | `PUT /api/cpq/quotations/{id}/excel-view` | 精度值请求/保存均为 decimal string |
| P3-04 | `POST /api/cpq/quotations/{id}/export/html` | 文件/HTML 显示最多 9 位 |
| P3-05 | `POST /api/cpq/quotations/{id}/export/pdf` | PDF 显示最多 9 位 |
| P3-06 | `POST /api/cpq/quotations/{id}/export/excel` | 精度敏感计算值全部写 9 位显示文本单元格，禁止 double NUMERIC |
| P3-07 | `GET /api/cpq/quotations/{id}/export-excel-view` | 同 P3-06 |
| P3-08 | `POST /api/cpq/quotations/{id}/send` | 邮件正文最多 9 位，附件沿用对应导出规则 |

导出响应 MIME、文件名、错误码不变。导出是显示边界，文件中不暴露 12 位工作值，除非该列是明确标识的原始取数列且有自己的显示配置。

## 5. API-P4 · 核价、比较和价格调整

### P4-01 核价单

| 方法与路径 | 精度字段 |
|---|---|
| `GET /api/cpq/costing-orders` | 总额、核价总额 decimal string |
| `GET /api/cpq/costing-orders/{coid}` | 总额、明细、版本金额 decimal string |
| `GET /api/cpq/costing-orders/{coid}/version-options` | 金额类选项值 decimal string |
| `POST /api/cpq/costing-orders/{coid}/version-switch` | 响应金额 decimal string |

### P4-02 报价/核价比较

| 方法与路径 | 精度字段 |
|---|---|
| `GET /api/cpq/quotations/{id}/comparison` | 报价、核价、差异 decimal string |
| `POST /api/cpq/quotations/{id}/comparison/export` | 导出显示最多 9 位 |
| `GET /api/cpq/quotations/{id}/comparison-view/data` | 当前值、核价值、差异值 decimal string |

meta/config 端点不返回计算数值，本任务无类型变化。

### P4-03 价格版本、审核和任务

| 方法与路径 | 精度字段 |
|---|---|
| `GET /api/cpq/quotations/{quotationId}/price-revisions` | `quoteTotalAmount` decimal string |
| `GET /api/cpq/quotations/{quotationId}/price-revisions/{revisionId}/preview` | 总额/行金额 decimal string |
| `GET /api/cpq/price-adjust/reviews` | current/adjusted/diff/warn 数值 decimal string |
| `GET /api/cpq/price-adjust/reviews/{reviewId}` | 同上 |
| `POST /api/cpq/price-adjust/reviews/impact` | 影响金额和差异 decimal string |
| `POST /api/cpq/price-adjust/reviews/approve` | 响应派生金额 decimal string |
| `POST /api/cpq/price-adjust/reviews/{reviewId}/recompute-budget` | 重算金额 decimal string |
| `GET /api/cpq/price-adjust/jobs` | `diffValue` decimal string |
| `GET /api/cpq/price-adjust/jobs/{jobId}` | `diffValue`/金额 decimal string |
| `GET /api/cpq/price-adjust/jobs/{jobId}/items` | `diffValue` decimal string |

设置阈值和元素价格接口保持原业务 scale，但十进制字段只允许 decimal string 请求，禁止前端 number 噪声。

## 6. 前后端联调断言

1. 响应原始 JSON 中 `98765431.123456789012` 必须位于引号内，禁止 JSON number。
2. 保存后 DB `numeric(26,12)` 为 `98765431.123456789012`，重新 GET 仍返回同一字符串。
3. 嵌套快照解开后计算值是 string；历史 numeric token 经无损解析得到相同 Decimal，过程中不存在 JS `number`/Java Double。
4. UI 显示 `98765431.123456789`，不覆盖 state 中 12 位字符串。
5. `1/3` 的公式 API 和前端本地引擎都返回 `0.333333333333`。
6. 精度字段发送 JSON number 时返回现有 400 类型校验；接口路径、方法、字段名、鉴权和错误码集合不变。

## 7. main-api 回写

本任务存在契约变更，测试完成后必须将本文件涉及端点的 decimal 类型说明逐端点覆盖回 `dev-docs/main-api.md`，添加本任务来源标记并更新文件头日期。未回写不得合并。
