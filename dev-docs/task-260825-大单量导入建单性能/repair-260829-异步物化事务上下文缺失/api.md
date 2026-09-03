# api · repair-260829 —— **接口契约零变更**

## 为什么不改

| 端点 | 是否变更 | 说明 |
|---|---|---|
| `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | ❌ 不变 | 请求体、响应体（`CommitResult` 的全部字段）、状态码、错误码**一律不变**。改动只在其内部使用哪个 executor |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | ❌ 不变 | 存量修复复用现有端点，不新增、不改签名 |
| `GET /api/cpq/quotations/{id}/materialize-status` | ❌ 不变 | 轮询语义不变（分母 = 明细行数，`done` 由 `ready==total` 派生）|

**`CommitResult.warnings` 是既有字段**，B-4 只是往里多写一条内容，**不是契约变更**（字段类型、是否可空、序列化形态均不变）。

## 临时诊断端点（B-1）

B-1 会建临时诊断端点用于证伪方案丙的假设。它：
- **不进 `main-api.md` 总账**（不是产品契约）
- **跑完即删**，合并前必须确认工作区无残留（`git status` 干净）

🔧 **2026-08-29 约束放宽（主线裁决）**：本节原写「纯读，不写任何数据」。
B-1 第一轮按只读做，**两组都返回 `present(5 tabs)`、连对照组都没复现 `null`，结论 inconclusive** ——
子代理并指出读数可能被 **worker 线程池复用巧合**污染（HTTP 请求线程与其 `runAsync` 任务落在同一物理线程），
而这个质疑对主线勘察期的 E/F 组**同样适用**。

因此放宽为：**允许 B-1 的第二轮验证写数据**，但只在下列护栏内：

| 护栏 | 规定 |
|---|---|
| 操作性质 | **纯 INSERT**（`materialize` 补写 `quotation_line_component_data`），**不删、不覆盖任何既有非空行** |
| 目标单据 | 只允许用 **19 张已知全空单**中的两张：`QT-20260829-0203`（`6d457323`）与 `QT-20260828-0199`（`b72d2def`）。备用 `QT-20260828-0200` / `QT-20260829-0204` |
| 为什么用两张而不是一张跑两次 | 一张跑成功后 `comp_data` 就非 0，重跑不再是对照 —— 而「清空重跑」属 `CLAUDE.md §3.2` 数据销毁红线，**禁止** |
| 端点形态 | 必须**逐位复制** `BasicDataImportV6Resource:177` 的 fire-and-forget 形态（`runAsync(() -> materializer.materialize(bg))` 后立即返回），只是改成对**已有单**跑、不建单 |
| 🚫 禁止 | 不许对这两张之外的任何单据写；不许 DELETE/UPDATE/TRUNCATE 任何表；不许碰 `is_current` |

> 这两张单本就在 B-7 的存量修复清单里，实验成功等于顺带修好其中一张 —— 不产生额外的数据债。

## `main-api.md` 回写

按 `task-docs.md §2.5`：**本次无契约变更，无需回写 `main-api.md`**。此结论须在 `test-report.md` 中复述一次。
