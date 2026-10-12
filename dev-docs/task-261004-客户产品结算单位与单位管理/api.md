# 接口契约

状态：接口实现完成，待环境补验。所有写接口仅 `SYSTEM_ADMIN`。

## 客户产品

“客户料号”导入/导出列增加可选字段 `settlement_unit`（中文表头“结算单位”）。旧模板缺少该列时按空值导入，不拒绝整份文件；新导出模板统一带出该列。

`ds_quote_customer_part.version` 仅为服务端并发控制字段，由系统自动维护，不属于 Excel 导入/导出列，用户无需也不允许在文件中声明“版本”。

基础核价与详细核价的“物料”导入/导出各自增加可选字段 `pricing_unit`（中文表头“计价单位”）。旧模板缺少该列时按空值导入；两套表分别写入 `ds_cost_basic_material.pricing_unit` 与 `ds_cost_detail_material.pricing_unit`，不互相回退或覆盖。

### `GET /api/cpq/dataset/{dataset}/customer-parts`

现有列表响应每条记录增加 `settlementUnit`，未维护返回 `null`。

### `PATCH /api/cpq/dataset/{dataset}/customer-parts/{id}/settlement-unit`

请求：`{ "settlementUnit": "PCS", "version": 3 }`。单位代码匹配不区分大小写，保存并返回标准写法；`null` 表示清空。仅允许启用单位。400=未知/停用/格式错误，403=非管理员，404=不存在，409=版本冲突。

## 单位定义

### `GET /api/cpq/units`

查询：`category=QUANTITY|WEIGHT`、`enabled`、分页参数。响应字段：`id`、`code`、`category`、`conversionCount`、`enabled`、`sortOrder`、`version`、审计字段。

### `POST /api/cpq/units`

请求：`{ code, category, enabled?, sortOrder? }`。代码大小写不敏感唯一。

### `PATCH /api/cpq/units/{id}`

请求：`{ code, sortOrder, version }`。已被引用时只允许大小写/显示形式调整，不允许改变类型；失败返回 400，版本冲突返回 409。

### `POST /api/cpq/units/{id}/disable`

请求：`{ version }`。停用而不删除，历史引用保留；新选择列表排除。关系未补齐的单位不可启用。

## 有向换算关系

### `GET /api/cpq/units/{sourceUnitId}/conversions`

返回来源单位及同类型全部目标单位：

```json
{
  "sourceUnit": { "id": 1, "code": "PCS", "category": "QUANTITY", "version": 4 },
  "targets": [
    { "targetUnitId": 2, "targetUnitCode": "EA", "factor": 1 },
    { "targetUnitId": 3, "targetUnitCode": "KPCS", "factor": 0.001 }
  ]
}
```

### `PUT /api/cpq/units/{sourceUnitId}/conversions`

整组保存：`{ "sourceVersion": 4, "targets": [{ "targetUnitId": 2, "factor": 1 }, { "targetUnitId": 3, "factor": 0.001 }] }`。

- 来源必须存在；目标须同类型且不同（停用目标也可先维护关系，便于补齐新单位）；系数必须大于 0；目标不得重复。单位启用前必须存在到该类型全部启用单位的关系，且其他启用单位也必须指向它。
- 每个方向独立，不自动生成/改写反向关系。
- 任一项失败则整组不写入；400 返回逐项错误，409 表示版本冲突。

## 运行时换算契约

- `sourceUnit` 来自 `unit_source_field` 指向的同组件字段；`targetUnit` 来自客户产品 `settlement_unit`。
- 匹配大小写不敏感；同单位隐含系数 1。
- 同类型且存在启用关系时：`converted = raw × factor(sourceUnit → targetUnit)`。
- 无结算单位、跨类型、未知、关系缺失或停用时：返回原值并记录非阻断诊断信息。
- 已提交/已发布报价（包括刷新/重算）读取冻结上下文；草稿重算读取当前有效关系。

## 核价运行时目标单位

- 先按报价客户和销售料号批量读取 `ds_quote_material.production_no`。
- 基础核价目标单位读取该根生产料号在 `ds_cost_basic_material.pricing_unit` 的值；详细核价目标单位读取 `ds_cost_detail_material.pricing_unit` 的值。
- 两套核价数据独立维护、独立读取；一套为空不使用另一套补值。
- 核价卡片中的子件行不切换目标单位，仍使用该报价行根生产料号的目标单位。
- 无销售→生产绑定、目标数据集无计价单位、目标单位未知/停用/无关系时原值透传并记录非阻断诊断。
- 报价提交生成核价单时冻结两套核价目标上下文；已提交核价单的刷新、重算、版本切换不读取最新计价单位或单位关系。
