# api · repair-260829 —— **接口契约零变更**

## 为什么不改

| 端点 | 是否变更 | 说明 |
|---|---|---|
| `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | ❌ 不变 | 请求体、响应体（`CommitResult` 的全部字段）、状态码、错误码**一律不变**。改动只在其内部使用哪个 executor |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | ❌ 不变 | 存量修复复用现有端点，不新增、不改签名 |
| `GET /api/cpq/quotations/{id}/materialize-status` | ❌ 不变 | 轮询语义不变（分母 = 明细行数，`done` 由 `ready==total` 派生）|

**`CommitResult.warnings` 是既有字段**，B-4 只是往里多写一条内容，**不是契约变更**（字段类型、是否可空、序列化形态均不变）。

## 临时诊断端点（B-1）

B-1 会建一个临时诊断端点用于证伪方案丙的假设。它：
- **不进 `main-api.md` 总账**（不是产品契约）
- **跑完即删**，合并前必须确认工作区无残留（`git status` 干净）
- 纯读，不写任何数据

## `main-api.md` 回写

按 `task-docs.md §2.5`：**本次无契约变更，无需回写 `main-api.md`**。此结论须在 `test-report.md` 中复述一次。
