# API 契约：物料利润字段

> 本任务不新增 URL，只对现有响应/请求和字段树做加法扩展。

## 1. 销售产品列表

### `GET /api/cpq/dataset/quote/parts`

现有 query、分页和权限不变。`items[]` 新增：

```json
{
  "axisValue": "S-3120014539",
  "customerNo": "8000137",
  "productionNo": "P-3120014539",
  "profit": "-12.340000000000"
}
```

契约：

- `dataset=quote`：`profit` 键恒出现，值类型是十进制字符串或 `null`。
- `dataset=cost-basic` / `cost-detail`：物料表没有该列，整个 `profit` 键省略。
- 禁止返回 JSON number，避免 JavaScript 精度丢失。
- SQL 数量保持 2：count 1 条 + page 1 条。

## 2. 销售产品单列维护

### `PUT /api/cpq/dataset/quote/parts/{axisValue}?customerNo={customerNo}`

权限、状态码和多行守卫沿用现有端点。新增允许的 body 键：

```json
{ "profit": "-2.500000000000" }
```

清空：

```json
{ "profit": null }
```

成功响应沿用 `ApiResponse<DsPartPatchResult>`，`updated` 回显调用方键：

```json
{
  "code": 200,
  "data": {
    "dataset": "quote",
    "axisValue": "S-3120014539",
    "updated": { "profit": -2.500000000000 },
    "source": "IMPORT"
  }
}
```

说明：后端内部回显可能序列化为 JSON number；列表读接口才是供前端长期展示的精度保真字符串契约。前端保存成功直接使用自身已规范化的字符串回填，刷新后以 GET 字符串为准。

校验与错误：

- `profit` 允许负数、零、小数；空字符串按现有归一规则转 `null`。
- 非数字返回 400，消息点名「利润」；不得 500。
- 找不到目标料号返回 404。
- 未带/带错 `customerNo` 且命中多个客户行时返回 409，事务回滚。
- `source` 不因维护利润而改变；`updated_at/updated_by` 更新。

## 3. 报价基础数据导入

导入 URL 与 multipart 契约不变。物料 Sheet 新增表头：

| Excel 表头 | Registry 字段 | 类型 | 单元格必填 | 导入空值语义 |
|---|---|---|---|---|
| 利润 | `profit` | `DECIMAL` / `numeric(26,12)` | 否 | 已存在行保留旧值；新行写 `NULL` |

错误响应沿用现有导入校验结构，必须能定位 `sheet=物料`、行号、`column=利润` 和 `reason=不是合法数值`。缺少「利润」表头按现有严格模板契约报缺列。

## 4. 取数配置字段树

### `GET /api/cpq/config/semantic-graph/field-tree`

当 `dialect=QUOTE` 且坐标对应数据源「物料」时，物料分组新增字段：

```json
{
  "sourceNodeKey": "MATERIAL",
  "sourceColumn": "profit",
  "displayName": "利润",
  "dataType": "NUMBER",
  "roles": [],
  "viewColumn": "_物料_利润",
  "lookupLib": null,
  "isCore": false,
  "elemKey": false
}
```

字段顺序位于物料分组现有 9 个字段之后。拖入后的编译请求仍只发送 `sourceNodeKey/sourceColumn/fieldName/fieldType` 等既有列结构，默认 `fieldType=INPUT_NUMBER`；服务端编译结果选择 `ds_quote_material.profit` 并声明视图列 `_物料_利润`。

## 5. 兼容性

- 这是响应体和白名单的加法扩展；既有客户端忽略未知 `profit` 键即可。
- 旧 9 列 Excel 因缺少新表头不再兼容；这是本任务已显式记录的严格模板升级。
- 存量 builder 配置没有 `MATERIAL.profit`，迁移不得自动追加；重新打开时只在可用字段列表看到候选项。
