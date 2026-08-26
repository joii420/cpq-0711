# api.md —— 接口契约

## 结论：本任务接口零变更

🔴 **2026-08-25 扩范围（D-1 → D-1+D-3）后复核：结论不变，仍是零变更。**

改动全部位于 `ConfigureSnapshotService` 与 `CardSnapshotService` 的**私有方法**内部，
不涉及任何 Resource 层签名、DTO 字段、HTTP 状态码或错误码。

---

## 涉及但不变更的端点

### `POST /api/cpq/basic-data-import/v6/quote/create-quotation`

`BasicDataImportV6Resource.java:139`

| 项 | 内容 | 本次是否变更 |
|---|---|---|
| 权限 | `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN` | 否 |
| 请求体 | `CreateQuotationFromImportRequest{ importRecordId, customerId, name }`，三者非空否则 400 | 否 |
| 响应体 | `ApiResponse<V6QuotationCommitService.CommitResult>` | 否 |
| `CommitResult` 字段 | `quotationId` / `importRecordId` / `hfPairsCount` / `lineItemsCount` / `cardValuesReady` / `costingTreeRows` / `warnings` | 否 |
| 幂等性 | 同 `importRecordId` 重入返回既有 quotation（日志「V6 commit: 幂等重入」） | 否 |
| 降级语义 | 物化失败不回滚整单；置 `cardValuesReady=false` + `warnings`，前端 warm 兜底 | **否 —— 且 AC-5 专门守它不被吞掉** |

**唯一可观测差异（行为不变，取值变化）**：修复后 1845 行场景下
`cardValuesReady` 由 `false` 变为 `true`、`warnings` 由非空变为空数组 —— 这是**缺陷修复的结果**，
不是契约变更。字段语义与类型均未改动。

⚠️ **响应耗时会变化但无阈值承诺**：AC-1 的秒数判据按用户裁决**先测后定**，
本文件**不写死任何响应时间 SLA**，待实测后再定。

---

## 超时相关（本次明确不动，记录以免后续会话重提）

| 位置 | 值 | 本次 |
|---|---|---|
| `cpq-frontend/src/services/api.ts:5` | `timeout: 30000`（axios 全局） | **不动** |
| `cpq-frontend/vite.config.ts` `/api` proxy | 未设超时 | 不动 |
| Narayana 事务超时 | 未显式配置 → 默认 60s | **不动** |

理由见 `问题说明.md §④ 证据 5` 与 §⑤「已否决备选」。
