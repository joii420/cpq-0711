# task-260907 · 第二段 —— 接口契约

> **单一事实源**：AC 原文在 `需求文档.md`，本文只标 AC 编号，🚫 不复制 AC 原文。
> **前后端唯一协调物**：并行的前后端子代理互相看不见，只能靠本文对齐（`task-docs.md` §6）。
> **回写总账**：合并 master 前须把本文的端点回写 `dev-docs/main-api.md`（`task-docs.md` §2.5，强制）。

---

## 0. 本次契约变更总览

| 端点 | 变更 | 服务的 AC |
|---|---|---|
| `GET /api/cpq/quotations/{id}/costing-approve/preview` | **扩展响应体**（加 `dsBackfill` 段），路径/方法/鉴权不变 | AC-5, AC-10, AC-14, AC-20 |
| `POST /api/cpq/quotations/{id}/costing-approve` | **请求体不变**；行为改为「确认即执行 ds 回填」 | AC-6, AC-7, AC-18 |

🚫 **不新增端点。** 依据闸门 A0-3 裁决：复用现有两段式（预览拿 token → 带 token 提交），不另起一套预览。
🚫 **不改这两个端点的路径、HTTP 方法、`@RoleAllowed`**（现为 `{PRICING_MANAGER, SYSTEM_ADMIN}`）。

---

## 1. `GET /api/cpq/quotations/{id}/costing-approve/preview`

**语义**：只读、无副作用、幂等。返回「**将写入什么**」，不是「哪些值变了」。

> 🚨 这句话是 `AP-60` 判据四的直接产物：原实现按「逐列 diff」判断有无变更，`rd.changes.isEmpty() → continue`，
> 于是「组内未被页签表征的行将被删除」这件事**根本不在 diff 模型里** —— 预览显示 0 变更，执行删 3 行。
> ⇒ 本段的 `dsBackfill` 必须描述**结果状态**（这一组回填后会是什么样），不许只描述增量。

### 响应体（`BackfillPreviewDTO` 扩展）

既有字段 `quotationId` / `previewToken` / `summary` / `products` / `globalShared` / `groups` **一律保持原样**（老回填链路仍在用，AC-15）。新增一段：

```jsonc
{
  "quotationId": "…",
  "previewToken": "…",              // 既有：提交时必须原样带回
  "summary": { … },                  // 既有：老回填摘要（新单恒 0/0/0/0）
  "products": [ … ],                 // 既有
  "globalShared": { … },             // 既有
  "groups": [ … ],                   // 既有

  "dsBackfill": {                    // 🆕 本次新增
    "applicable": true,              // false = 本单不走 ds_ 新回填（老单），前端不渲染该区
    "confirmRequired": true,         // 恒 true —— 财务必须人工确认（D-25）
    "summary": {
      "tables": 5,                   // 将被触碰的表数
      "axes": 3,                     // 将被触碰的「表×轴值」组数
      "upgradedGroups": 2,           // 判定为 UPGRADED 的组数
      "unchangedGroups": 1,          // 判定为 UNCHANGED 的组数（一行不写）
      "unanchoredRows": 1            // 🔴 「无法对齐」的行数，>0 时前端必须显著提示
    },
    "tables": [
      {
        "sheetKey": "MATERIAL_BOM",
        "sheetName": "物料BOM",       // 与 Excel sheet 名逐字相等（SheetDef.sheetName）
        "tableName": "ds_quote_material_bom",
        "groups": [
          {
            "axisValue": "S-3120014539",     // 销售料号（D-3：轴 = 报价单产品卡片的销售料号）
            "customerNo": "CUST-0001",       // string，= customer.code；上游 DDL 落库前过渡期可为 null（前端须容忍）
            "baseVersionNo": 1,              // _record 拍快照时的版本
            "currentVersionNo": 2,           // 库里当前版本
            "targetVersionNo": 3,            // 将升到的版本 = max(current, historyMax) + 1
            "crossVersion": true,            // baseVersionNo != currentVersionNo → 走指纹重锚
            "result": "UPGRADED",            // CREATED / UPGRADED / UNCHANGED
            "baseRowCount": 9,               // 主表当前整组行数（= 基底行数）
            "resultRowCount": 9,             // 回填后该组行数
            "patchedRows": 2,                // _record 表征并覆盖了列的行数
            "untouchedRows": 7,              // 🔑 页签没表征、原样保留的行数
            "unanchoredRows": [              // 🔴 锚不上的行（A0-1 第三支路）
              {
                "recordId": 8812,
                "originId": 161,             // 快照时的主表行 id（已失效）
                "baseRowFingerprint": "9f2c…",
                "displayValues": { "项次": "90", "投入料号": "S-1630010773" },
                "reason": "CROSS_VERSION_FINGERPRINT_MISS"
              }
            ],
            "columnScope": {                 // 该页签表征了哪些列 —— AP-60 列维度判据
              "patched": ["component_qty", "unit_weight"],
              "preserved": ["item_seq", "input_material_no", "output_material_type", "…"]
            }
          }
        ]
      }
    ],
    "nonParticipating": [            // 🆕 D-33：不参与基础数据升版的组件（手写视图，无 builder_config）
      { "componentId": "…", "componentName": "投料", "reason": "NO_BUILDER_CONFIG" }
    ],
    "extendColumnOnly": [               // 只落 extend_column、不回填的字段（AC-3）
      { "sheetKey": "MATERIAL_BOM", "fields": ["自定义列A", "毛利率(公式)"] }
    ]
  }
}
```

**字段语义的三条硬约束**：

1. `untouchedRows` **必须出现在响应里**，且前端**必须渲染**。它是 `AP-60` 的守卫：财务要能看见「这一组有 7 行本次不动」，否则「不写 = 删除」这类后果就永远不在她的视野里。
2. `unanchoredRows` 非空时，`confirmRequired` 仍为 `true` 但前端**必须显著提示**；🚫 不许折叠进「更多」里。
3. `result: "UNCHANGED"` 的组仍要出现在列表里（带 `patchedRows: 0`），🚫 不许过滤掉 —— 否则财务无法区分「这张表没变」和「这张表根本没被算进去」。
4. 🆕 **`nonParticipating` 非空时前端必须显式告知**（`D-33`）：「本单有 N 个组件不参与基础数据升版」。🚫 不许静默 —— 实测现网 156/228 个组件视图是手写的（无 `builder_config`），财务会以为全覆盖了。这与 `AP-60` 判据四（「不写 = 删除」不在 diff 模型里）是同型的静默。

### 错误码

| 码 | 条件 |
|---|---|
| 404 | 报价单不存在 |
| 403 | 非 `PRICING_MANAGER` / `SYSTEM_ADMIN`（既有 `@RoleAllowed`） |

> 🕰️ **本表曾写「400 · 报价单状态不是 `SUBMITTED`」，是主线起草时的臆测，已删**（2026-09-07 后端代理实查指出）。
> 实测 `QuotationResource:600` 的 `costingApprovePreview` **没有任何状态校验**，直接 `preview(id)` —— 预览是只读、无副作用、幂等的，任何状态调它都不会写库。
> 🚫 **本段不给它补状态闸** —— 那是改既有端点行为，不在任何一条 AC 内，属超范围。要补另立任务。
> ⚠️ 状态闸在 `POST .../costing-approve`（`doCostingApprove` 的 `!"SUBMITTED".equals(q.status)` → 400），**写入侧是拦住的**，这才是要紧的那一侧。

---

## 2. `POST /api/cpq/quotations/{id}/costing-approve`

**请求体不变**：

```jsonc
{ "comment": "…", "previewToken": "…" }   // previewToken 必填，缺失 → 400（既有行为）
```

**行为变更**：在既有 `quoteBackfillService.execute(...)`（老回填，新单 no-op）之后，追加 ds_ 新回填的执行。**同一事务**，失败整体回滚、报价单保持 `SUBMITTED`（与既有 `doCostingApprove` 的事务语义一致）。

**响应体**：既有 `QuotationDTO`，其 `backfill` 字段（老回填摘要）保持原样；新增 `dsBackfill` 摘要段，形状同预览的 `dsBackfill.summary`。

### 错误码

| 码 | 条件 | 说明 |
|---|---|---|
| 400 | `previewToken` 缺失 | 既有行为，强制先调预览 |
| 400 | 状态不是 `SUBMITTED` | 既有行为 |
| **409** | token 与提交时重算不一致 | 文案「报价数据在预览后发生变化，请重新预览」。⚠️ **这是「预览后又变了」，不是「乐观锁撞车」** —— 后者已按 `D-25` 改为不拒绝、由财务确认。<br>🔧 **`D-32` 契约变更（2026-09-07 用户批准）**：`previewToken` 的计算**必须纳入 `dsBackfill`**。现状只哈希老 `QuoteBackfillPlan.groups` ⇒ 预览后销售再保存一次、`_record` 变了 token 却不变，而 `_record` 正是财务确认的对象 ⇒ 该保护对新链路完全失效。 |
| 403 | 权限不足 | 既有 |

🚫 **不新增 409 STALE_VERSION 类错误。** `D-25` 明确推翻了「拒绝后通过的单」这一设计。

---

## 3. 前端不需要新增的东西（写明，防止子代理自己加戏）

- 🚫 不新增「回填历史」查询端点 —— 不在本期 AC 内
- 🚫 不新增 `_record` 的 CRUD 端点 —— `_record` 由 `saveDraft` 内部写，不对外暴露
- 🚫 不改 `saveDraft` 的请求/响应契约 —— `_record` 写入是**服务端内部行为**，前端无感知（AC-2 由 DB 断言验证，不由接口验证）

---

## 4. ⛔ 待上游确定的字段

| 字段 | 阻塞于 | 现状 |
|---|---|---|
| `dsBackfill.tables[].groups[].customerNo` | ~~上游 `A0-1`~~ ✅ **2026-09-07 已裁决** | 类型 **`string`**（DB 侧 `varchar(20) NOT NULL`，取值 = `customer.code`，如 `CUST-0001`）。契约里不再是 `null` 占位 —— **前端按 `string` 渲染**。<br>⛔ 但上游 DDL **尚未落库**（实测 0 张带版本表有该列）⇒ 后端在建表前该字段实际取不到值，**过渡期允许返 `null`，前端必须容忍 `null` 不报错**（渲染为「—」）。DDL 落地后即恒非空 |
