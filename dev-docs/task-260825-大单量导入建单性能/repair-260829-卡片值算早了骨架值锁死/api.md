# api · repair-260829 卡片值算早了 —— **本线接口契约零变更**

## 为什么不改

| 端点 | 是否变更 | 说明 |
|---|---|---|
| `POST /api/cpq/quotations/{id}/ensure-card-values` | ❌ **不变** | 路径 / 方法 / 请求体 / 响应体 / 状态码**一字未变**。B-1 只改它内部「算完要不要落库」的判断 |
| `GET /api/cpq/quotations/{id}/materialize-status` | ❌ **本线不变** | ⚠️ **并发会话 `修复draft超时问题` 正在此响应体上加字段**（暴露 `MaterializeRegistry` 状态）。**本线不碰这个端点**，避免两线同时改同一响应体 |
| `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | ❌ 不变 | 内部多了 `MaterializeRegistry.begin/end`，不影响契约 |

🔒 **`ensure-card-values` 契约不变是硬约束**（`问题说明.md` E-6）：它是 `task-260825` D-5 前端轮询的依赖，且对方正在其相关端点上加字段，改契约会直接撞车。

## 跨会话接口约定（非 HTTP 契约，但需要协调）

本线 B-4 新增的 `MaterializeRegistry` 是**内部 CDI bean**，不是 HTTP 接口，但它是两条线的接缝：

| 项 | 约定 |
|---|---|
| 提供方 | 本线（`repair-260829-卡片值算早了骨架值锁死`） |
| 消费方 | 并发会话 `修复draft超时问题`（在 `QuotationResource.materializeStatus` 读取并暴露） |
| 交付方式 | B-4 落地后，主线把**包名 / 类名 / 方法签名**发给对方；**在那之前对方不写消费代码**（避免签名变更导致返工） |
| 语义 | 只反映「`materialize()` 任务是否正在执行」，覆盖 ①~④ 全程，**与卡片值状态无关** |
| 生命周期 | 内存态、`@ApplicationScoped`；进程重启丢失 —— 可接受（重启时物化本来也中断了，标志消失语义正确） |

## `main-api.md` 回写

按 `task-docs.md §2.5`：**本次无 HTTP 契约变更，无需回写 `main-api.md`**。此结论须在 `test-report.md` 中复述一次。
