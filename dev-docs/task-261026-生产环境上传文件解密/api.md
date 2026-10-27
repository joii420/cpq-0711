# 生产环境上传文件解密 · 接口契约

> 本文是本任务的前后端唯一协调物。成功响应如本文未特别声明，必须保持现有形状。
> 契约依据：[`../../docs/文件加解密服务接口调用文档.md`](../../docs/文件加解密服务接口调用文档.md)。

## 1. 启用与配置契约

### 1.1 启用条件

- 只有 Quarkus active profile **精确等于 `prod`** 时执行文件识别／解密。
- `dev`、`test`、自定义非 `prod` profile 直接返回原字节，不校验外部服务配置，不发网络请求。
- 不增加数据库或系统设置开关，不允许在 `prod` 中通过业务开关绕过处理。

### 1.2 基础地址

`application.properties` 必须存在：

```properties
file.encryption.server=http://172.16.18.45:9502
```

约束：

- 值是 `scheme://host:port` 形式的基础地址；允许末尾一个 `/`并在内部归一化。
- 不允许配置 `/v1/inspect`、`/v1/decrypt` 或其他 path/query/fragment。
- 代码在基础地址后固定拼接 `/v1/inspect` 和 `/v1/decrypt`。
- JVM 系统属性可覆盖文件值：

  ```bash
  -Dfile.encryption.server=http://<ip>:<port>
  ```

- Quarkus 环境变量等价名为 `FILE_ENCRYPTION_SERVER`，但部署文档以配置文件或上述 JVM 参数为主。

### 1.3 令牌

```properties
file.encryption.token=${CRYPTO_API_TOKEN:}
```

- 真实令牌仅通过受控环境的 `CRYPTO_API_TOKEN` 注入；不写入 Git、前端、响应或日志。
- `prod` 上传时值为空视为配置错误并失败关闭；非 `prod` 不要求配置。

## 2. CPQ → 文件加解密服务

### 2.1 通用请求

```http
Authorization: Bearer <token>
Content-Type: application/octet-stream
X-File-Name: <RFC 3986 / encodeURIComponent 等价编码后的原文件名>
X-Request-Id: <UUID>
```

- body 是原始文件字节，禁止 JSON/base64 包装。
- 空格必须编码为 `%20`，不是 form encoding 的 `+`。
- 同一份文件的 inspect/decrypt 共用一个 `X-Request-Id`；两个 multipart 文件位分配两个 requestId。
- 不在单文件处理前额外调用 `/healthz` 或 `/readyz`；inspect 本身是该次请求的可用性判据。

### 2.2 `POST <base>/v1/inspect`

请求体：当次上传的原始字节。

决策规则：

1. `encrypted=true` **或** `isEncrypted=true` → 视为加密，继续 decrypt。
2. 响应至少包含上述一个布尔字段，且所有已出现字段都为 `false` → 视为明文，返回原字节。
3. 字段均缺失、类型错误、非 JSON 或空响应 → 上游响应无效，拒绝上传。

### 2.3 `POST <base>/v1/decrypt`

- 仅在 inspect 判定为密文时调用。
- 请求体仍是原密文；成功响应体是将要交给业务链路的明文字节。
- 2xx 但响应体为空视为无效上游响应，拒绝上传。
- 不自动重试，避免在超时后产生无法归因的重复大文件请求。

## 3. 失败契约

文件安全处理错误统一返回 CPQ 既有错误信封：

```json
{
  "code": 503,
  "message": "文件解密服务暂不可用，请稍后重试",
  "detail": {
    "bizCode": "FILE_CRYPTO_UNAVAILABLE",
    "upstreamCode": "CRYPTO_NOT_INITIALIZED",
    "requestId": "28dbd4c6-..."
  }
}
```

`detail.upstreamCode` 仅允许透传权威文档列出的业务码；不识别的值省略。`detail.requestId` 在已创建请求编号时必须返回。

| 场景 | CPQ HTTP | `detail.bizCode` | 安全文案／行为 |
|---|---:|---|---|
| 上传文件为空 | 400 | `EMPTY_UPLOAD_FILE` | `上传文件为空` ；不调上游 |
| `prod` 令牌缺失，或 server 非法／带业务路径 | 503 | `FILE_CRYPTO_CONFIG_INVALID` | `文件解密配置不完整，请联系管理员` |
| 上游 400 | 400 | `FILE_CRYPTO_REJECTED` | 保留允许的 upstreamCode，不透传原 message/body |
| 上游 413 | 413 | `FILE_CRYPTO_FILE_TOO_LARGE` | `上传文件超出加解密服务限制` |
| 上游 422 | 422 | `FILE_CRYPTO_REJECTED` | `上传文件无法解密` |
| 上游 401 | 502 | `FILE_CRYPTO_AUTH_FAILED` | 视为服务端凭证配置故障，不误导为当前用户未登录 |
| 上游 503 | 503 | `FILE_CRYPTO_UNAVAILABLE` | `文件解密服务暂不可用，请稍后重试` |
| 上游其他非 2xx、连接失败、响应不完整 | 502 | `FILE_CRYPTO_BAD_GATEWAY` | 不透传上游原始错误体 |
| 调用超时 | 504 | `FILE_CRYPTO_TIMEOUT` | `文件解密服务超时，请稍后重试` |

所有失败分支都必须在原文件进入业务解析器或文件存储前终止。可以保留既有的失败审计记录，但不得产生导入成功数据、模型版本或落盘文件。

## 4. CPQ 文件入口矩阵（20 个有效操作）

| # | HTTP 操作 | multipart 文件位 | 契约处理 |
|---:|---|---|---|
| 1 | `POST /api/cpq/v6/process-master/import` | `file` | 成功响应不变 |
| 2 | `POST /api/cpq/elements/import` | `file` | 成功响应不变 |
| 3 | `POST /api/cpq/material-recipes/import` | `file` | 成功响应不变 |
| 4 | `POST /api/cpq/currencies/rates/import` | `file` | 成功响应不变 |
| 5 | `POST /api/cpq/currencies/{code}/rates/import` | `file` | 成功响应不变 |
| 6 | `POST /api/cpq/customers/import` | `file` | 成功响应不变 |
| 7 | `POST /api/cpq/dataset/quote/quotation-import` | `file` | 异步导入语义不变；字节在异步任务前处理完 |
| 8 | `POST /api/cpq/dataset/{dataset}/import` | `file` | `customerNo` 等其他字段不变 |
| 9 | `POST /api/cpq/element-price/import` | `file` | `sourceId/priceDate/currency` 不变 |
| 10 | `POST /api/cpq/excel-templates/parse-headers` | `file` | 解析入口也属安全边界 |
| 11 | `POST /api/cpq/customers/{customerId}/material-mappings/import` | `file` | 成功响应不变 |
| 12 | `POST /api/cpq/imports/execute` | `file` | 其他 multipart 字段不变 |
| 13 | `POST /api/cpq/imports/import-excel` | `file` | 预览结果不变 |
| 14 | `POST /api/cpq/internal-materials/import` | `file` | 成功响应不变 |
| 15 | `POST /api/cpq/model-configs` | `glbFile`、可选 `thumbnailFile` | 两份独立处理，都成功后才落盘 |
| 16 | `POST /api/cpq/products/import` | `file` | 成功响应不变 |
| 17 | `POST /api/cpq/users/import` | `file` | 成功响应不变 |
| 18 | `POST /api/cpq/templates/{id}/excel-view-config/parse-header` | `file` | 解析入口也属安全边界 |
| 19 | `POST /api/cpq/component-directories/{id}/import` | `file` | **新增** `multipart/form-data` 形态；查询参数不变 |
| 20 | `POST /api/cpq/component-directories/{id}/import/commit` | `file` | **新增** `multipart/form-data` 形态；查询参数不变 |

不纳入：

- `POST /api/cpq/basic-data-import/v6/quote`：永久停用，保持 410，不读取 multipart body，不调外部服务。
- `PartModelResource` 中 UG NX / STEP 骨架：保持 `not_implemented`。
- 报价邮件中后端生成的 Excel 附件：不是用户上传文件。

## 5. 组件 Bundle 跨端契约

### 5.1 预览

```http
POST /api/cpq/component-directories/{id}/import?conflictPolicy=RENAME
Content-Type: multipart/form-data

file=<原始 .json 文件>
```

成功响应：与现有 `ApiResponse<ImportPreviewResult>` 逐字段一致。

### 5.2 提交

```http
POST /api/cpq/component-directories/{id}/import/commit?conflictPolicy=RENAME&ignoreMissingDeps=false&ignoreUnboundFormulas=false
Content-Type: multipart/form-data

file=<同一原始 .json 文件>
```

成功响应：与现有 `ApiResponse<ImportCommitResult>` 逐字段一致。

### 5.3 兼容性

- 同方法同路径的 `Content-Type: application/json` 形态继续接受 `ComponentExportBundle` JSON body，用于已有直接 API 调用方。
- JSON body 形态不是文件上传，不调用加解密服务。
- CPQ 前端从本任务起仅使用 multipart 形态，预览和提交都传 `File`，不在浏览器读取／解析 JSON。

## 6. 零改动与回写

- 除 § 5 新增 multipart 形态外，§ 4 中其他端点的 HTTP 方法、路径、业务字段、权限、成功响应和业务错误不变。
- CPQ 现有 `quarkus.http.limits.max-body-size=100M` 不变；不追随外部服务的 2GiB 上限放大。
- 测试完成后、合并 master 前，必须将§ 5 的新增契约和§ 3 的通用错误口径回写 `dev-docs/main-api.md`，来源标记按 `task-docs.md § 2.5` 更新。
