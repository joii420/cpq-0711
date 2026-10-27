# 接口契约

> 基础路径沿用 `/api/cpq`。本文只列本任务新增/变更契约；实现完成并通过测试后，按规则回写 `dev-docs/main-api.md`。

## 1. 通用约定

- 金额/价格均以十进制字符串传输，禁止 JSON 浮点数；元素单价由后端按 DEC-0020 `HALF_UP` 到 9 位并在响应中返回规范值，数据库沿用 12 位存储档，不新增 scale 9。
- 所有改变状态的请求携带 `Idempotency-Key`；并发请求携带本文要求的 `expected*` 字段。
- 409 响应统一包含 `code`、`message`、`currentBindings` 和 `recoverableActions`。
- 正式返回同时给业务修订、用户数据版本、目标/活动投影，客户端不得只比较一个“version”。

### 1.1 跨端规范 JSON 与摘要协议

投影输入与结果采用版本化 `CPQ-JCS-1` 协议，前后端不得各自定义稳定序列化：

1. 先按契约构造语义对象，再按 RFC 8785 JCS 生成规范 JSON 字节；编码固定为 UTF-8，不添加 BOM、空白或换行。
2. 金额、价格、数量、汇率、比例及公式中间值等精度敏感数字在语义对象中必须是 JSON string，禁止使用 JSON number。按字段精度完成 `HALF_UP` 后转普通十进制字符串：禁止正号和指数，去除无意义末尾零，正负零统一为 `"0"`。普通文本即使看似数字也不做数值转换。
3. `null`、空字符串、`"0"` 与字段缺失是四种不同语义；契约要求的字段不得因值为空而省略。字符串不额外做 Unicode 归一化，非法 Unicode 输入直接拒绝。
4. JCS 只规范对象键，不改变数组顺序；因此每个数组必须先按契约声明的稳定键升序排列。价格项按 `elementId`，产品按 `lineItemId`，计算格按 `lineItemId + tabCode + componentInstanceId + fieldCode`，来源/映射类数组按其稳定 ID 或完整业务组合键排序；稳定键缺失或重复时返回 `CANONICAL_KEY_INVALID`，不得退回数据库顺序或页面顺序。
5. 输入摘要为 `SHA-256(UTF8("cpq-projection-input-v1") + 0x00 + canonicalInputBytes)`；结果摘要为 `SHA-256(UTF8("cpq-projection-result-v1") + 0x00 + canonicalResultBytes)`。接口文本格式固定为 `sha256:` 加 64 位小写十六进制。
6. 输入语义对象包含 `hashProtocol`、计算契约版本、scope、三条预期绑定和全部冻结输入；结果语义对象包含 `hashProtocol`、`inputHash`、逐格结果、四类结果摘要、公式错误与合计。实现构建号 `engineVersion` 单独审计，不替代计算契约版本。
7. 协议升级必须产生新的协议名和域前缀；投影永久记录自己的 `hashProtocol`。历史查看和审批复验使用原协议，禁止用新版本重写旧摘要。

仓库保存一套语言无关黄金夹具，逐例给出语义 JSON、规范 UTF-8 字节和输入/结果摘要；Java 与 TypeScript 必须分别通过同一夹具，不能共享其中一端的运行时代码来伪造独立性。

```json
{
  "priceRevision": {"id": "uuid", "revisionNo": 3},
  "userDataVersion": 12,
  "calcInputSnapshotId": "uuid",
  "desiredProjectionId": "uuid",
  "activeProjectionId": "uuid",
  "buildStatus": "VALIDATING",
  "comparisonStatus": "PENDING",
  "submissionAllowed": false,
  "blockingReasons": ["PROJECTION_NOT_MATCHED"]
}
```

## 2. 报价单元素价格表

### GET `/quotations/{quotationId}/element-price-table`

权限：按报价单查看权限。默认读取 DRAFT 当前修订；已提交详情可用 `binding=SUBMITTED`。

响应至少包含报价模式、基准日、币种/汇率快照、当前/提交修订、活动元素数、缺价数、人工修改数，以及每项的规范价、报价币种价、使用状态、来源解释和涉及产品。

```json
{
  "quotationId": "uuid",
  "priceMode": "SNAPSHOT",
  "binding": "CURRENT",
  "revision": {"id": "uuid", "revisionNo": 3, "reasonCode": "ADD_ELEMENT", "sealedAt": "..."},
  "priceBaseDate": "2026-10-25",
  "items": [{
    "elementId": "uuid",
    "elementCode": "Cu",
    "usageStatus": "ACTIVE",
    "canonicalUnitCode": "kg",
    "cnyUnitPrice": "5820.000000000",
    "quoteCurrencyCode": "USD",
    "quoteCurrencyUnitPrice": "808.333333333",
    "sourceType": "MANUAL",
    "automaticCnyPrice": "5800.000000000",
    "sourceExplanation": {},
    "usedBy": [{"lineItemId": "uuid", "partNo": "S0001"}]
  }]
}
```

### POST `/quotations/{quotationId}/element-price-revisions/manual-overrides`

权限：`SALES_REP|SALES_MANAGER|SYSTEM_ADMIN`；仅 DRAFT。

```json
{
  "expectedUserDataVersion": 12,
  "expectedPriceRevisionId": "uuid-r3",
  "reason": "客户年度议价",
  "items": [{"elementId": "uuid", "cnyUnitPrice": "5900.000000000"}],
  "frontendProjection": {
    "hashProtocol": "CPQ-JCS-1",
    "engineVersion": "web-...",
    "inputHash": "sha256:...",
    "resultHash": "sha256:..."
  }
}
```

成功返回 202，生成 SEALED 修订并排队目标投影；响应给新修订和投影状态。若任何项目非法，整批 400，不生成修订。

### POST `/quotations/{quotationId}/element-price-revisions/resolve-missing`

用于销售为缺价元素人工补价或按当前策略重试。请求携带预期版本、项目和每项动作 `MANUAL|RETRY_STRATEGY`；只要任一项目失败，修订不封存。

### POST `/quotations/{quotationId}/element-price-revisions/refresh`

显式刷新，不属于日常增量。必须有专门权限/现有调价任务授权、原因和目标元素；可覆盖人工值但保留审计。普通“刷新基础数据”不得隐式调用。

### GET `/quotations/{quotationId}/element-price-revisions`

分页返回修订头与差异摘要。`GET /.../{revisionId}` 返回不可变明细和来源证据；不提供更新/删除已封存修订接口。

## 3. 后端计算与比对

`usageStatus` 是按请求绑定的用户数据/投影与价格目录关联后派生的当前状态，不是对已封存价格明细的修改。

### POST `/quotations/{quotationId}/projections`

保存草稿成功后创建目标投影；也可由既有保存接口内部调用。后端必须以持久化输入计算，前端结果仅用于比对。

```json
{
  "scope": "DRAFT_BUNDLE",
  "expectedUserDataVersion": 12,
  "expectedCalcInputSnapshotId": "uuid-input-u12",
  "expectedPriceRevisionId": "uuid-r3",
  "templateVersionId": "uuid",
  "compositionDigest": "sha256:...",
  "frontend": {
    "hashProtocol": "CPQ-JCS-1",
    "engineVersion": "web-...",
    "inputHash": "sha256:...",
    "resultHash": "sha256:...",
    "cells": [{"lineItemId": "uuid", "tabCode": "MATERIAL", "fieldCode": "subtotal", "value": "123.450000000"}]
  }
}
```

返回 202 + `projectionId`。相同幂等键和相同输入返回原任务。

### GET `/quotations/{quotationId}/projections/{projectionId}`

返回构建、比较、活动状态、阻断原因和轮询建议；完成 MISMATCH 时附 `diffCount` 和差异接口。

### GET `/quotations/{quotationId}/projections/{projectionId}/diff`

分页返回：`lineItemId/tabCode/fieldCode/frontendValue/backendValue/normalizedPrecision/reason`。权限同报价查看。

### POST `/quotations/{quotationId}/projections/{projectionId}/retry`

仅 `FAILED` 且输入绑定仍为当前时允许；过期则 409 `PROJECTION_SUPERSEDED`。重试不创建价格修订。

## 4. 报价提交接口变更

### POST `/quotations/{quotationId}/submit`

保留现有路径与权限，新增必填并发绑定：

```json
{
  "expectedUserDataVersion": 12,
  "expectedCalcInputSnapshotId": "uuid-input-u12",
  "expectedPriceRevisionId": "uuid-r3",
  "expectedQuoteProjectionId": "uuid-p3"
}
```

后端在提交事务内复核 `active=desired=expected`、投影 READY/MATCHED、无业务阻断。新增错误：

| code | HTTP | 含义 |
|---|---:|---|
| `ELEMENT_PRICE_INCOMPLETE` | 409 | 缺价/待确认/单位或币种失败 |
| `PROJECTION_NOT_READY` | 409 | 仍在构建或后端失败 |
| `PROJECTION_MISMATCH` | 409 | 前后端结果不一致 |
| `PROJECTION_SUPERSEDED` | 409 | 活动/目标/请求投影不一致 |
| `QUOTATION_VERSION_CONFLICT` | 409 | 用户数据或价格修订已变化 |

## 5. 复制接口变更

### POST `/quotations/{quotationId}/copy`

请求明确复制目标和新价格基准日规则；不得接受“复制价格/金额缓存”开关。

```json
{
  "targetTemplateId": "uuid",
  "targetCustomerId": "uuid",
  "priceBaseDate": "2026-10-25",
  "copyBusinessFields": true
}
```

返回 202：

```json
{
  "newQuotationId": "uuid",
  "copyStatus": "REPRICING",
  "copied": ["PRODUCT_CONTENT", "USER_INPUT", "FROZEN_CALC_INPUT"],
  "notCopied": ["PRICE_REVISION", "PROJECTION", "APPROVAL", "COSTING_RESULT"],
  "calcInputSnapshot": {"id": "uuid", "schemaVersion": "1", "status": "SEALED", "contentHash": "sha256:..."},
  "missingInputKeys": [],
  "priceRevisionId": null,
  "projectionId": null
}
```

复制源绑定固定为：DRAFT 读取当前 SEALED 输入快照，已提交及后续状态读取报价提交快照；不读取未绑定快照或核价输入。复制任务完成后给目标单自己的输入快照、R1/P1；可补录输入缺失或缺价为 `INCOMPLETE`，返回 `missingInputKeys`/缺价原因并允许打开 DRAFT。禁止跨客户复制的事实、来源无法证明或黑名单载荷返回 `COPY_SOURCE_PRIVATE_DATA_UNAVAILABLE`，任务 FAILED，不能降级为 0 或产生可用报价。

复制接口不接收任意输入字段列表，也不提供 `copySnapshotRows` 一类开关；复制白名单来自源/目标模板版本化输入 schema 和快照项的 `copyPolicy`。

## 6. 核价审核接口变更

### GET `/costing-orders/{costingOrderId}`

现有响应增加：

- `submittedQuoteBinding`：报价提交修订/投影；
- `appliedCostingBinding`：核价当前修订/投影；
- `finalElementPriceTable`：核价实际使用明细及与报价差异；
- `reviewVersion`、`compositionDigest`、`editable`；
- 投影/缺价/比较阻断状态。

### GET `/costing-orders/{costingOrderId}/version-options`

现有响应增加每个候选版本的组成摘要；列表不得逐候选、逐部件 N+1 查询。

### POST `/costing-orders/{costingOrderId}/version-switch/preview`

```json
{
  "partKey": "...",
  "targetVersionId": "uuid",
  "expectedReviewVersion": 7,
  "expectedSubmittedQuotePriceRevisionId": "uuid-r3",
  "expectedSubmittedQuoteProjectionId": "uuid-p3",
  "expectedAppliedCostingPriceRevisionId": "uuid-r3",
  "expectedActiveCostingProjectionId": "uuid-pc3",
  "expectedCompositionRevisionId": "uuid-c2",
  "expectedCompositionDigest": "sha256:..."
}
```

响应展示部件变化、元素并集、新增/休眠元素、是否需要 R4、缺价预检和一次性 `previewToken`。token 为高熵随机值，数据库只保存摘要，固定 30 分钟过期并绑定核价单、操作者和以上全部预期字段；预览不改变任何绑定。

### POST `/costing-orders/{costingOrderId}/version-switch`

保留现有路径，改为确认接口：

```json
{
  "previewToken": "opaque-database-backed-token",
  "expectedReviewVersion": 7,
  "expectedSubmittedQuotePriceRevisionId": "uuid-r3",
  "expectedSubmittedQuoteProjectionId": "uuid-p3",
  "expectedAppliedCostingPriceRevisionId": "uuid-r3",
  "expectedActiveCostingProjectionId": "uuid-pc3",
  "expectedCompositionRevisionId": "uuid-c2",
  "expectedCompositionDigest": "sha256:..."
}
```

确认事务锁定核价单、校验 token 未消费/未过期及全部预期绑定，并在同一事务消费 token、创建不可见组成候选和异步操作；返回 `202 + operationId + candidateCompositionRevisionId`，不提前改变应用组成、价格修订或活动投影。后台构建候选价格修订/投影后，在最终短事务重新锁定并 CAS 全部基准绑定，才一次激活组成、应用价格修订、目标/活动核价投影并推进 `reviewVersion`。异步失败写操作错误，原绑定不变；重试前必须重新预览。

### GET `/costing-orders/{costingOrderId}/version-switch-operations/{operationId}`

返回 `QUEUED/BUILDING/READY/FAILED/SUPERSEDED`、候选组成/价格/投影标识、当前阶段、缺价/错误码和最终绑定；只允许查询本核价单操作。FAILED/SUPERSEDED 不提供强制激活，只能重新预览。

### POST `/quotations/{quotationId}/costing-approve`

### POST `/quotations/{quotationId}/costing-reject`

现有路径新增相同预期绑定：

```json
{
  "costingOrderId": "uuid",
  "expectedReviewVersion": 8,
  "expectedSubmittedQuotePriceRevisionId": "uuid-r3",
  "expectedSubmittedQuoteProjectionId": "uuid-p3",
  "expectedAppliedCostingPriceRevisionId": "uuid-r4",
  "expectedActiveCostingProjectionId": "uuid-p4",
  "expectedCompositionDigest": "sha256:...",
  "reason": "驳回时必填"
}
```

任何不匹配返回 `COSTING_REVIEW_VERSION_CONFLICT`，不执行批准/驳回。

## 7. 策略与 AI 接口

现有 `/element-price/strategies` 的可变 CRUD 仅用于 LEGACY 兼容；SNAPSHOT 策略 v2 使用版本端点：

- `GET /element-price/strategy-versions?customerNo=`：版本列表与当前发布版；
- `POST /element-price/strategy-versions/drafts`：从空白或指定发布版本创建草稿；
- `GET/PUT /element-price/strategy-versions/{versionId}`：读取/更新草稿完整 DSL；
- `POST /element-price/strategy-versions/{versionId}/simulate`：用正式执行器试算，不落价格修订；
- `POST /element-price/strategy-versions/{versionId}/validate`：返回规则冲突、价格源、日历覆盖、单位/币种和 Schema 检查及 validation token；
- `POST /element-price/strategy-versions/{versionId}/publish`：仅定价经理/系统管理员，携带预期版本和 validation token；
- `POST /element-price/strategy-versions/{versionId}/archive`：只阻止未来选择，不影响历史修订读取。

本任务还要求：

- 发布请求必须引用完整结构化 DSL、日历选择/版本和解析确认摘要；
- AI 解析接口不保存/发布策略，响应状态只允许 `READY|NEEDS_CONFIRMATION|UNSUPPORTED|INVALID`；
- 运行期取价接口不接受自然语言，不调用 AI；
- 试算响应与修订来源使用相同的日期映射、精度和执行器。

## 8. 交易日历

### GET/POST `/trading-calendars`

GET 支持 `type/status/customerId/keyword/page/size`，返回当前发布版本、覆盖区间、时区和绑定数。POST 仅 `PRICING_MANAGER|SYSTEM_ADMIN`，创建主档并校验类型归属、唯一编码和 IANA `zoneId`。

### GET/PUT `/trading-calendars/{calendarId}`

GET 返回主档、当前版本和引用摘要。PUT 只允许修改未冻结元数据；编码、类型和已发布版本内容不可改。`POST /{calendarId}/status` 停用前检查价格源和策略引用。

### GET `/trading-calendars/{calendarId}/versions`

### POST `/trading-calendars/{calendarId}/versions/drafts`

从空白或指定 `sourceVersionId` 创建草稿。相同幂等键不得重复创建。

### GET/PUT `/trading-calendars/{calendarId}/versions/{versionId}`

GET 返回覆盖区间、摘要和分页/月份日期明细；PUT 仅 DRAFT，携带版本号并整批校验每日状态。

### POST `/trading-calendars/{calendarId}/versions/{versionId}/import`

`multipart/form-data` 导入 Excel 到草稿。重复日期、非法状态/原因、范围外日期或覆盖缺日时整批失败，响应包含行号错误；不得部分写入。

### GET `/trading-calendars/{calendarId}/versions/{versionId}/export`

导出草稿或发布版本，包含主档、覆盖区间、所有日期状态和摘要。

### POST `/trading-calendars/{calendarId}/versions/{versionId}/validate`

无副作用返回完整性、唯一性、覆盖断档、引用影响和发布阻断。

### POST `/trading-calendars/{calendarId}/versions/{versionId}/publish`

仅 `PRICING_MANAGER|SYSTEM_ADMIN`；请求携带预期草稿版本和 validation token。发布后不可变。覆盖不完整返回 `CALENDAR_COVERAGE_INCOMPLETE`。

### PUT `/element-price/sources/{sourceId}/calendar-binding`

扩展既有价格源模块；仅 `PRICING_MANAGER|SYSTEM_ADMIN`。

```json
{
  "defaultCalendarId": "uuid",
  "marketZoneId": "Asia/Shanghai",
  "expectedVersion": 3
}
```

解除绑定传 `defaultCalendarId=null`；若存在待发布策略依赖，允许解绑但返回影响摘要，相关策略发布时必须失败。普通价格源更新接口不接受这两个高影响字段。

## 9. AI 解析配置

以下端点仅 `SYSTEM_ADMIN` 可写；`PRICING_MANAGER` 只读版本、金标与连通状态。所有响应排除 Secret 明文。

### GET/POST `/system/ai-parser/providers`

### GET/PUT `/system/ai-parser/providers/{profileId}`

维护 provider、base URL、模型、超时、重试、`secretRef` 和启用状态。`POST /{profileId}/connection-test` 返回成功、耗时和脱敏错误。

### GET/POST `/system/ai-parser/prompts`

### GET/POST `/system/ai-parser/schemas`

创建不可变版本草稿；发布后无 PUT 内容接口。Schema 发布前执行 JSON Schema 自校验。

### GET/POST `/system/ai-parser/gold-case-sets`

维护金标集不可变版本；草稿支持 JSON/Excel 整批导入，记录自然语言输入、上下文、期望解析状态和期望 DSL/错误。发布后不可修改；非法或重复 case 整批拒收。

### GET/POST `/system/ai-parser/releases`

Release 草稿绑定 Provider、Prompt、Schema 和已发布金标集版本。`POST /{releaseId}/gold-tests` 执行该金标集；`POST /{releaseId}/publish` 仅在全部通过时发布并保证唯一启用；`POST /{releaseId}/disable` 关闭自然语言入口。

### POST `/element-price/strategies/ai-parse`

请求只含自然语言、策略上下文和目标 scope；响应给解析状态、候选 DSL、解释、待确认项和实际 release/model/prompt/schema 版本。接口不得保存草稿或发布策略。

## 10. 计算投影任务监控

### GET `/system/quotation-projections`

支持 `quotationId/costingOrderId/scope/status/errorCode/from/to/page/size`，返回绑定摘要、阶段、耗时、重试次数、引擎版本、diff 数和 `retryable`。

### GET `/system/quotation-projections/{projectionId}`

返回投影头、行摘要、绑定关系、错误和保留策略；逐格差异复用投影 diff 契约。敏感输入只返回摘要。保留信息至少包含 `everActivated`、`businessReferenced`、`payloadPurgedAt`、`payloadRetainUntil`、`headerRetainUntil` 和排除清理原因；正式证据的 retainUntil 返回空并标记 `WITH_BUSINESS_DOCUMENT`。

### POST `/system/quotation-projections/{projectionId}/retry`

仅 `SYSTEM_ADMIN` 且 `retryable=true`；仍须验证它是当前目标。过期返回 409 `PROJECTION_SUPERSEDED`。不提供强制 READY、切活动指针、忽略 MISMATCH 或删除正式绑定投影端点。

监控接口只读显示冻结基线：未生效终态重载荷 90 天、技术头 365 天、已消费/过期 token 30 天、终态命令 180 天。不存在单条或批量手工清理 API；dry-run 与正式清理由部署任务执行并写清理审计。

## 11. 兼容

- `priceMode=LEGACY` 的现有响应字段继续返回，新增绑定字段可为空；旧客户端不得创建 SNAPSHOT 报价后绕过投影门禁。
- SNAPSHOT 的正式导出/审批接口不读取旧四个缓存列。
- 本任务测试完成后，变更端点必须回写 `dev-docs/main-api.md`；立项阶段不提前改总账。
