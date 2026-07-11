# 接口文档 · task-0708 导入落库料号语义纠偏

> 结论先行:**本次任务不改任何接口契约**。导入是文件驱动(multipart 上传),料号语义纠偏全部发生在后端 handler 解析 + DB 落库层,对前端**透明**。本文档存在的目的是:①锚定本次涉及的现有端点;②明确"哪些不变、为什么不变";③给联调/验收一个对照基线。

---

## 1. 契约变更结论

| 维度 | 是否变化 | 说明 |
|------|:---:|------|
| 请求路径 | ❌ 不变 | 沿用现有 `/api/cpq/basic-data-import/v6/*` |
| 请求方法 | ❌ 不变 | 沿用 POST(multipart) / GET |
| 请求参数 / 请求体 | ❌ 不变 | 仍是 `customerId + file` / `file`;**不新增前端要传的字段** |
| 响应结构 | ❌ 不变 | 仍是 `ImportResultDTO` / 轮询 Map;**不新增回传字段** |
| 落库结果(DB) | ✅ 变化 | `material_no`/`production_no`/`material_part_no` 语义与取值变化(见 backtask.md) |

> 因此前端无需改动(详见 fronttask.md);本表用于评审时快速确认"无破坏性契约变更"。

---

## 2. 本次涉及的现有端点(逐一确认无契约变更)

基址 `http://localhost:8081`;鉴权=会话 Cookie;类级 `@Path=/api/cpq/basic-data-import/v6`;`@Produces=application/json`。

### 2.1 报价基础数据导入(异步)
- **方法/路径**: `POST /api/cpq/basic-data-import/v6/quote`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **请求体(multipart)**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| customerId | UUID | 是 | 客户 ID(用其 `code` 作为 V6 `customer_no` 注入) |
| file | 文件 | 是 | 报价基础数据 Excel(19 Sheet;测试文件=`报价系统功能基础数据功能结构所需字段V3.xlsx`) |

- **响应**: `ApiResponse<ImportResultDTO>`,异步立即返回 `status=PROCESSING` + `importRecordId`,前端轮询 §2.4 查进度。
- **本次影响**: 仅内部 `QuoteImportService` 各 Q* handler 的列读取与落库字段变化;**入参/响应不变**。

### 2.2 核价基础数据导入(同步)
- **方法/路径**: `POST /api/cpq/basic-data-import/v6/pricing`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **请求体(multipart)**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| file | 文件 | 是 | 核价基础数据 Excel(24 Sheet;测试文件=`核价系统功能基础数据功能结构所需字段（6.0版） .xlsx`;`customer_no` 从 Excel 行读) |

- **响应**: `ApiResponse<ImportResultDTO>`(同步返回逐 Sheet 结果 `sheets[]` + 计数)。
- **本次影响**: 仅内部 `PricingImportService` 各 P* handler 的列读取与落库字段变化;**入参/响应不变**。

### 2.3 导入后建报价单
- **方法/路径**: `POST /api/cpq/basic-data-import/v6/quote/create-quotation`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **请求体**: `CreateQuotationFromImportRequest`(`importRecordId` / `customerId` / `name` 必填)
- **响应**: `ApiResponse<CommitResult>`
- **本次影响**: 无(仅依赖 §2.1 已落库的料号数据,自动 autoPopulate)。

### 2.4 查询导入结果(轮询)
- **方法/路径**: `GET /api/cpq/basic-data-import/v6/{recordId}`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `recordId`(UUID)
- **响应**: `ApiResponse<Map>`,字段 `importRecordId / systemType / status / totalRows / successRows / failedRows / originalFileName / createdAt / metadata`。
- **本次影响**: 无。

---

## 3. ImportResultDTO(响应结构,本次不变,仅备查)

| 字段 | 类型 | 说明 |
|------|------|------|
| importRecordId | UUID | 导入记录 ID |
| systemType | String | `QUOTE` / `PRICING` |
| status | String | `PROCESSING` / `SUCCESS` / `PARTIAL` / `FAILED` |
| totalRows | int | 总行数 |
| successRows | int | 成功行数 |
| failedRows | int | 失败行数 |
| sheets | SheetResultDTO[] | 逐 Sheet 结果(sheet 名 / 成功 / 失败 / 错误明细) |

> ⚠️ 联调注意:本次纠偏后,重导测试文件时,**成功/失败计数不应因料号改动而回退**;若某 Sheet 因列改名(如核价「材质料号」未识别)导致 `failedRows` 上升,即为 handler 未适配的信号(见 backtask 自检)。

---

## 4. 验收对照(接口层)

1. 用 §2.1 上传报价 V3 → 轮询 §2.4 至 `status=SUCCESS`,`failedRows=0`。
2. 用 §2.2 上传核价 6.0 → 同步响应 `sheets[]` 全部成功,无 Sheet 因料号列改名报错。
3. 契约回归:请求/响应 JSON 结构与改动前逐字段一致(无新增/删除字段)。
