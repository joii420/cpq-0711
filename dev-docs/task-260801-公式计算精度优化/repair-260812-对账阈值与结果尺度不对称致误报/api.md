# api.md — repair-0812 对账阈值与结果尺度不对称致误报（前后端契约）

- **归属**：`dev-docs/task-260801-公式计算精度优化/repair-260812-对账阈值与结果尺度不对称致误报/`
- **结论：本次接口契约零变更**（无新增端点、无字段增删、无类型/语义/错误码变化）。
- 按 `CLAUDE.md` 要求，零改动也必须写本文档，内容 = **「为什么不改」的判定依据 + 涉及端点的现行契约快照（供实施与验收对照）+ 回归确认清单 + 二期触发条件**。

---

## 0. 为什么契约不变（判定依据）

本次修复**只改前端两个值之间的"比较方式"**（`QuotationStep2.tsx#valuesReconcile` 比较前把两侧归一到 `FORMULA_RESULT_SCALE = 9`），**不改**：

| 不变项 | 说明 |
|---|---|
| 请求/响应字段 | 三个相关端点的字段名、类型、必填性全不动 |
| 传输值本身 | 上报报文里的 `frontendValue` 仍是**前端原始 12 位工作值**，`backendValue` 仍是**后端原始 9 位结果值** —— 归一只发生在**判定**，不落进报文（保留诊断现场，见 §1.2 设计说明） |
| 语义 | `diffs: []` 仍表示"本轮无差异"（消解条件①）；非空仍表示"存在未落定差异" |
| 错误码 | 提交仍在有 pending 时返 `409 RECONCILE_PENDING` |
| 服务端行为 | `ReconcileDiffStore` 整份替换、`SubmitGateService` 非空即拦 —— 全不动 |

**唯一可观测的变化是"频次"不是"形状"**：修复后 `diffs` 数组在口径差场景下由"恒非空"变为"恒空"，报文结构一模一样。

> ⛔ 因此**本次无需回写 `dev-docs/main-api.md`**（`任务平台规则.md` §2.4：只改实现、未改契约的任务可跳过回写，但须在 `test-report.md` 写明"本次无契约变更，无需回写 main-api.md"）。

---

## 1. 涉及端点的现行契约快照（**仅供对照，非本次新增**）

> 以下三节是**实施与验收时用来"确认没被改动"的基准**。权威定义仍在 `dev-docs/task-260806-报价编辑链路优化与前后端对账/api.md`（API-3 / API-5）与 `dev-docs/main-api.md`。

### A-1 `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit`

编辑回写报价卡片单元格。**本次不改。**

- **鉴权**：登录态（同报价单其它端点）
- **实现**：`QuotationResource.java:236-249` → `CardSnapshotService#editCardValue`

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `componentId` | string(uuid) | ✅ | 组件（页签）id；缺 → 400 |
| `rowKey` | string | ✅ | 组合行键；缺 → 400 |
| `fieldName` | string | ✅ | 字段名；缺 → 400 |
| `value` | **decimal string / string / null** | — | ⚠️ 走 `DecimalRequestValidator.rejectNumericTokens` —— **JSON number 会被 400 拒绝**，精度值必须以字符串传（task-0810 契约） |

**响应** `ApiResponse<Map>`，`data` 含 `quoteCardValues` / `quoteExcelValues` / `quoteValuesAt`，前端就地回灌（AP-50）。

**错误码**：`400` 请求体为空 / 三个必填缺失 / 值里含裸 JSON number / **非 DRAFT 单据编辑**（已知缺陷 `BL-0152`：前端 `catch{}` 静默吞该 400，本次不处理）。

### A-2 `POST /api/cpq/quotations/line-items/{lineItemId}/reconcile-report`

对账差异埋点（fire-and-forget，D1 只记录不改数据）。**本次不改。**

- **实现**：`QuotationResource.java:263-276`；DTO `ReconcileReportRequest` / `ReconcileDiffEntry`

**请求体**

```jsonc
{
  "reconciledAt": "2026-08-13T00:12:34.567Z",     // Instant，缺省则服务端取 now()
  "diffs": [                                       // 空数组 = 本轮无差异 → 服务端 pending.remove()
    {
      "componentId": "74c0cede-094e-478c-a8fe-8f0028d538cd",
      "tabName": "物料",
      "rowKey": "3120011203/3110520422/00255::Ag粉",
      "fieldName": "材料成本",
      "frontendValue": "63.211125158028",          // ← 仍是前端原始 12 位工作值（本次不归一报文）
      "backendValue": "63.211125158",              // ← 仍是后端原始 9 位结果值
      "frontendInputs": { "材料净重": "91.768628", "材料占比": "25" },
      "backendInputs":  { "材料净重": "91.768628", "材料占比": "25" }
    }
  ]
}
```

- ⚠️ `frontendValue` / `backendValue` / `frontendInputs` / `backendInputs` 四项均过 `rejectNumericTokens` → **必须字符串**，裸 number 返 400。
- `diffs` 为 `null` 视同 `[]`。
- **服务端不做任何业务判定**，只落 WARN 日志 + 写进程内 `ReconcileDiffStore`。

**响应**：`204 / 200`（前端 `.then(ok, ok)` 消音，失败不影响用户操作）。

### A-3 `POST /api/cpq/quotations/{id}/submit`（提交闸门，**本次不改**）

有未落定差异时：

- **HTTP `409`**，`ApiResponse.error` 结构（`GlobalExceptionMapper.java:31-37`）：

```jsonc
{
  "code": 409,
  "message": "……",
  "data": {
    "reason": "RECONCILE_PENDING",      // 或 WRITE_IN_FLIGHT（阶段② 占位，当前恒不触发）
    "conflicts": [
      { "lineItemId": "…", "productPartNo": "3120011203", "tabName": "物料",
        "rowKey": "…", "fieldName": "材料成本",
        "frontendValue": "63.211125158028", "backendValue": "63.211125158" }
    ]
  }
}
```

- 前端消费点：`ReconcilePendingDrawer.tsx`（抽屉「提交校验未通过：前后端算值不一致」）
- **修复后预期**：口径差场景不再进入此分支；**真差异仍必须进入此分支**（AC-3 专项验证）

### 1.2 设计说明：为什么归一只在判定、不落报文

若把归一后的 9 位值写进 `frontendValue` 上报，一旦将来出现**真**差异，报文里两边都会是 9 位，**丢掉"前端第 10~12 位是多少"这条诊断信息** —— 而这正是排查前后端引擎分歧的关键线索。故：

> **判定用归一值，展示与上报用原始值。**

---

## 2. 回归确认清单（契约不变，但必须确认没被连带改坏）

| # | 确认项 | 方法 | 期望 |
|---|---|---|---|
| API-R1 | A-2 请求体形状不变 | F12 Network 抓一次 `reconcile-report` | 字段与 §A-2 示例一致；`frontendValue` 仍是 12 位原始值 |
| API-R2 | 消解链路通 | 修复后编辑一格 | 请求体 `diffs: []`，服务端 `pending.remove()` |
| API-R3 | A-1 响应结构不变 | 抓一次 `quote-card-edit` | `data` 仍含 `quoteCardValues` / `quoteExcelValues` / `quoteValuesAt` |
| API-R4 | decimal-string 契约未破 | 归一后传入 `isWithinTolerance` 的仍是 string | 无任何 JS number 进入上报路径（否则 400） |
| API-R5 | A-3 阳性能力在 | 注入第 8 位差异后提交 | 仍 409 + `reason: RECONCILE_PENDING` + `conflicts[]` 非空 |
| API-R6 | 无新增/删除端点 | `git diff` 看 `QuotationResource.java` | **0 改动** |

---

## 3. 二期触发条件（什么情况下契约才需要动）

| 触发条件 | 契约要怎么改 |
|---|---|
| 恢复 FR-12 原意（12 位逐位对账，即被否的方案 C） | A-2 需要新增字段承载 12 位工作值（或后端快照新增 `formulaResultsWorking` 后前端改比这一份），届时必须改本文件 + 回写 `main-api.md` |
| 对账要支持多实例部署（task-0806 D8 已知限制） | `ReconcileDiffStore` 外置后，A-2/A-3 语义不变但需补幂等与 TTL 约定 |
| 要把「差异是口径类还是真分歧」区分上报（便于运维统计） | A-2 需新增 `diffKind` 枚举字段 —— 本次**不做**，避免为一次性排障扩契约 |
