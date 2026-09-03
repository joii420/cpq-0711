# api.md · repair-260829

## 结论：本次**无契约变更**

| 端点 | 方法/路径 | 本次是否改动 |
|---|---|---|
| 保存草稿 | `PUT /api/cpq/quotations/{id}/draft` | ❌ 请求体 `SaveDraftRequest`、响应体 `ApiResponse<QuotationDTO>`、错误码**全部不变** |
| 导入建单 | `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | ❌ 请求体 `CreateQuotationFromImportRequest`、响应体 `CommitResult`（含 `materializing` / `cardValuesReady` / `warnings`）**全部不变** |

## 判定依据

- **B-1 / B-2** 改的是 `QuotationService.processBatchStage1` 与 `QuotationTreeService` 的**内部实现**，方法签名变更仅发生在 service 层（新增重载），不穿透到 Resource。
- **B-3** 改的是 `CreateQuotationMaterializer.materialize` 内部的事务边界，`CommitResult` 的字段与语义不变（`warnings` 本就已存在且已被前端消费）。
- **F-1** 改的是客户端超时阈值，不是契约。

## 错误码不变（AC-5 / AC-7 会验）

树页签校验失败仍抛 `BusinessException(400)`，消息逐字为：

```
该料号在 BOM 树上已有下级，不能添加到「材质元素」页签
```

## 行为变化（不属契约变更，但需记录）

**B-1 的模板口径**：保存请求若同时携带 `customerTemplateId`（切换模板），校验将按**本次要绑定的新模板**执行，而非现状的「flush 前查库拿到的旧模板」。对外表现为「同一请求可能从 200 变 400、或从 400 变 200」，但**这是修正而非破坏**（校验理应按本次保存的模板做）。由 **AC-6** 覆盖。

## `main-api.md` 回写

**无需回写** —— 两个端点的方法、路径、参数、响应、错误码均未变更。本结论将在 `test-report.md` 复述一次（`task-docs.md §2.5` 要求）。
