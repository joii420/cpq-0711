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

## 7. 报价快照测试调用契约

本节只把既有接口的可执行测试形状集中写清，不新增业务接口，也不改变第 6 节语义。

### 7.1 建单、保存与复制

创建空报价：

```http
POST /api/cpq/quotations
Content-Type: application/json

{"customerId":"<uuid>","name":"T261026_QBE_<唯一后缀>"}
```

`data.id` 是报价 ID，`data.userDataVersion` 是后续增量保存的并发基线。需要模板时可按既有契约附加
`categoryId/customerTemplateId/costingTemplateId`；利润测试本身不要求这三个字段。

新增报价行使用增量协议：

```http
PUT /api/cpq/quotations/{quotationId}/draft
Content-Type: application/json

{
  "baseVersion": 0,
  "added": [{
    "id": null,
    "tempId": "<本次测试生成的稳定 UUID 字符串>",
    "productId": null,
    "templateId": null,
    "productPartNo": "T261026_QBE_MAT_001",
    "productName": "T261026_QBE 产品",
    "customerPartNo": "T261026_QBE_CPN_001",
    "customerProductNo": "T261026_QBE_CPN_001",
    "productAttributeValues": "{}",
    "subtotal": "0",
    "sortOrder": 0,
    "processNos": [],
    "componentData": [],
    "compositeType": "SIMPLE",
    "seedProcessesFromBase": false,
    "compositeProcesses": [],
    "quoteExcelValues": null
  }],
  "modified": [],
  "removed": []
}
```

- `added[].id` 必须是 `null`，`tempId` 必须稳定且在本次请求内唯一。
- `modified[]` 使用相同的行字段集，但 `id` 必须是本报价已有行 ID；禁止在请求中加入 `profit` 或
  `profitSnapshot`，借此验证利润不可由客户端篡改。
- `removed[]` 是待删除行 UUID。三个数组与旧 `lineItems` 协议互斥。
- `baseVersion` 取最近一次 `GET /api/cpq/quotations/{id}` 或保存响应的
  `data.userDataVersion`。保存响应只回本次新增/修改行；完整利润回读以随后 GET 为准。

复制与回读：

```http
POST /api/cpq/quotations/{sourceQuotationId}/copy
Content-Type: application/json

{}
```

请求体也可为空；需要换模板时使用 `{"templateId":"<uuid>"}`。响应 `data.id` 为新报价 ID，随后
`GET /api/cpq/quotations/{id}` 逐行核对源行和复制行的 `profit`（包括 `null`）。

### 7.2 不得改变利润的既有动作

以下接口均不接收利润字段，成功后必须再次 GET 核对同一行 `profit` 未变：

| 动作 | 请求 |
|---|---|
| 报价刷新基础快照 | `POST /api/cpq/quotations/{id}/refresh-card-snapshot`，无 body |
| 选配侧刷新快照 | `POST /api/cpq/configure-product/quotations/{id}/refresh-snapshot`，无 body |
| 确保卡片值 | `POST /api/cpq/quotations/{id}/ensure-card-values`，无 body |
| 重算 | `POST /api/cpq/quotations/{id}/recalculate`，无 body |
| 修改既有行 | 第 7.1 节 `PUT .../draft`，放入 `modified[]` |

### 7.3 候选、已有产品、选配绑定与导入建单

- 候选：`GET /api/cpq/quotations/customer-part-candidates?customerId={uuid}`；导入批次过滤需要时再加
  `importRecordId={uuid}`。`data[]` 每项都必须显式包含 `profit: string|null`。
- 已有产品：
  `GET /api/cpq/quotations/{quotationId}/existing-products?page=0&size=20`，可选过滤键为
  `customerProductNo/salesPartNo/productName/spec`；响应 `data.content[]` 含 `profit`。
- 绑定已有销售产品（最小利润测试请求）：

```http
POST /api/cpq/configure-product/quotations/{quotationId}
Content-Type: application/json

{
  "productType": "SIMPLE",
  "parts": [],
  "compositeProcesses": [],
  "tempId": "<uuid>",
  "customerProductNo": "T261026_QBE_CPN_BIND",
  "customerProductName": "T261026_QBE 绑定产品",
  "bindExistingMaterialNo": "T261026_QBE_MAT_BIND"
}
```

  前提是该报价客户对应的 `ds_quote_material` 已有该销售料号。响应
  `lineItems[]`（本端点不是 `ApiResponse` 信封）必须含本次已冻结的 `profit`。
- 报价基础数据导入：先按既有 multipart 契约调用
  `POST /api/cpq/dataset/quote/quotation-import`（字段 `customerId`、`file`），轮询
  `GET /api/cpq/dataset/quote/quotation-import/{recordId}` 至 `SUCCESS`，再调用：

```http
POST /api/cpq/dataset/quote/create-quotation
Content-Type: application/json

{
  "importRecordId": "<成功批次 UUID>",
  "customerId": "<与导入相同的客户 UUID>",
  "name": "T261026_QBE_IMPORT_<唯一后缀>",
  "categoryId": null,
  "customerTemplateId": null,
  "costingTemplateId": null
}
```

  响应 `data.quotationId` 是新报价 ID，`data.materializing=true`。轮询
  `GET /api/cpq/quotations/{id}/materialize-status`，必要时调用
  `POST /api/cpq/quotations/{id}/ensure-card-values`，再 GET 完整报价核对利润快照。

### 7.4 测试身份、夹具与 SQL 观测口径

- `@QuarkusTest` 使用测试配置 `cpq.security.rbac.enabled=false`，REST 请求不需要登录；测试端口为随机端口。
- 测试进程必须显式用 `DB_NAME=<主代理创建的临时数据库>` 指向隔离库；不得使用默认
  `cpq_db_0724` 或共享 `cpq_db_test`。只读数据源自动跟随主数据源。
- 可从隔离库选择既有 `status='ACTIVE'` 且有业务编号的客户作为父夹具；所有新增报价名、客户产品编号、
  销售料号及直接插入的基础行统一使用 `T261026_QBE_` 前缀。测试只删除自己前缀/自己记录，禁止全表清理。
- 基础利润夹具写在该客户业务编号与唯一销售料号对应的 `ds_quote_material` 行；报价及报价行优先走本节 REST
  创建。历史行用明确的 `profit_snapshot IS NULL` 夹具模拟，不从主数据回填。
- 浏览器隔离栈通过 `PW_BASE_URL`、`PW_BACKEND_URL` 显式注入，不使用 5174/8081，也不使用默认
  Playwright `globalSetup`（它会修改共享用户表）。隔离库保留种子管理员时可用
  `loginAsAdmin(page)`，账号契约为 `admin / Admin@2026`。
- N+1 只统计“读取利润的 `ds_quote_material` 查询”，不拿整条保存流程的写 SQL 总数冒充利润查询数。
  开启并清空 Hibernate `Statistics`，对同一已预热动作分别建立 2 行和 6 行私有夹具；汇总统计键或 SQL
  捕获中同时命中 `ds_quote_material` 与 `profit` 的查询次数。每次候选/已有产品/保存/绑定/导入物化动作
  允许最多 1 次利润批量查询，且 6 行次数不得大于 2 行次数。若当前 Hibernate 版本不暴露 native SQL
  查询键，允许用测试日志 handler/statement observer 做同一正则计数；禁止退化为源码目测。

### 7.5 浏览器入口与稳定 DOM

- 只读详情：`/quotations/{id}`。
- 编辑态可靠入口：先打开只读详情，点击文本严格为“编辑”的按钮，再点击文本含“下一步”的按钮进入
  Step 2；不得以直接 `/quotations/{id}/edit` 后 0 张卡片作为产品缺陷。
- 产品卡片：`.qt-product-card`；同一卡片头部：`.qt-card-header-left`。
- 单位：`.qt-sku-badge` 且文本以 `单位:` 开头；VAT：`.qt-vat-badge`；利润：
  `.qt-profit-badge`；编辑态删除：`button.qt-action-btn.delete`。
- 在 `.qt-card-header-left` 的直接子元素中断言单位、VAT、利润的 DOM 下标严格递增；不要只比较整页文本。
- 编辑态必须存在并可点击删除按钮；只读态同一卡片内该按钮数量必须为 0。
- `profit=null` 或键缺失断言文本 `利润: -`；`profit="12.500000000000"` 断言
  `利润: 12.5`；`profit="1.234567891500"` 断言 `利润: 1.234567892`。
