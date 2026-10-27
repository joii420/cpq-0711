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

## 6. 报价单产品卡片利润快照

本节是验收期扩围契约。数据库保存列为 `quotation_line_item.profit_snapshot numeric(26,12) NULL`，对外统一命名 `profit`。

### `GET /api/cpq/quotations/{id}`

`data.lineItems[]` 新增：

```json
{
  "productPartNo": "S3120011203",
  "vatRate": 13,
  "profit": "12.500000000000"
}
```

- `profit` 键恒出现，值为十进制字符串或 `null`；禁止 JSON number。
- 字段只读自报价行快照，不在 GET 时实时 JOIN 基础利润；历史报价行返回 `null`。
- 前端非空时使用现行 `formatDisplayDecimal` 展示（最多 9 位小数、`HALF_UP`、去尾零），但内存和接口值保持原十进制字符串；`null` 或旧服务未带字段时显示 `利润: -`。

### `GET /api/cpq/quotations/customer-part-candidates`

候选项新增 `profit: string | null`，用于新增行尚未首次保存时即时显示。值来自候选对应客户的 `ds_quote_material.profit`，不增加逐行查询。
候选 DTO 现有类级 `NON_NULL` 不得吞掉空利润键；该字段须单独按 `ALWAYS` 序列化，使无利润候选明确返回 `"profit": null`。

### `GET /api/cpq/quotations/{quotationId}/existing-products`

分页 `content[]` 新增 `profit: string | null`。过滤、分页、总数和现有列表列均不变；利润只供“加入报价单”构造新增行，不要求在已有产品抽屉新增可见列。

### `POST /api/cpq/configure-product/quotations/{quotationId}` 响应

`lineItems[]` 新增 `profit: string | null`，值与本次已落库 `quotation_line_item.profit_snapshot` 一致。绑定已有销售产品时冻结当前利润；新铸产品未维护利润时为 `null`。

### `PUT /api/cpq/quotations/{id}/draft`

- 不新增客户端可修改的利润字段；服务端只在 `added[]`/旧协议中新建的报价行首次持久化时按客户与销售料号解析并写入快照。
- `modified[]`、头字段保存、重算、刷新基础数据均不得修改已有 `profit_snapshot`；历史 `NULL` 不补值。
- 报价单复制继承源行快照，不重新解析基础利润。

### 兼容性

- 新后端 + 旧前端：新增响应键被忽略。
- 新前端 + 旧后端：`profit` 缺失按 `null` 处理并显示 `利润: -`。
- Flyway 只加可空列，不回填、不中断存量报价；现有计算与导出响应不新增利润。
