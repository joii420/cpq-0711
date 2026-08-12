# API 契约修订

## API-1 `PUT /api/cpq/quotations/{id}/draft`

- 鉴权、路径、响应结构不变。
- `componentData[].rowData` 按冻结组件字段定义解释：
  - `INPUT_TEXT`：原文本。
  - `INPUT_NUMBER`：decimal string 原文；`项次/序号`等结构整数可为 JSON number。
  - `FORMULA` / `*_FORMULA`：decimal string/null，最终最多 9 位。
- 示例：

```json
{
  "lineItems": [{
    "componentData": [{
      "componentId": "00000000-0000-0000-0000-000000000001",
      "rowData": "[{\"项次\":1,\"输入单价\":\"1.2300\",\"公式金额\":\"0.410000001\"}]"
    }]
  }]
}
```

- 错误：公式字段传 JSON number、输入数值格式非法、字段元数据缺失时返回 400，消息含完整路径和原值；事务零部分写。

## API-2 `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit`

- `fieldName` 对应输入字段时，`value`保留用户当前单元格值。
- 对应公式字段时禁止直接编辑；若协议允许系统回写，只接受最多 9 位 decimal string。
- `rowData`按 API-1 同一语义校验，不再全局拒绝输入 number。

## API-3 读取与计算响应

- GET 报价、卡片、核价和比较视图中输入字段返回保存原值。
- 单元格公式结果最多 9 位 decimal string。
- 产品卡片小计与报价单总金额当前均最多 9 位 decimal string，但由不同配置变量控制。
- 公式计算内部 12 位是实现边界，不新增响应字段。

## main-api 回写

本次改变上述端点的动态字段类型判定。测试完成后必须将最终契约覆盖回 `dev-docs/main-api.md`。
