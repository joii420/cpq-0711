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
    "applicable": true,              // 见下「applicable 的判定（主线 2026-09-07 裁决）」
    "confirmRequired": true,         // 恒 true —— 财务必须人工确认（D-25）
    "summary": {
      "tables": 5,                   // 将被触碰的表数
      "axes": 3,                     // 将被触碰的「表×轴值」组数
      "upgradedGroups": 2,           // 判定为 UPGRADED 的组数
      "unchangedGroups": 1,          // 判定为 UNCHANGED 的组数（一行不写）
      "unanchoredRows": 1,           // 🔴 「无法对齐」的行数，>0 时前端必须显著提示
      "blockedGroups": 0,            // 🆕 D-37：判定为 BLOCKED（跳过回填）的组数。**后端恒发**，>0 时前端必须显式提示
      "nonParticipatingComponents": 0, // 🆕 D-33：不参与基础数据升版的组件数。**后端恒发**
      "recordStale": false,          // 🆕 D-35：本单 _record 快照是否过期。**后端恒发**（标记表未落库时恒 false）
      "recordStale": false,          // 🆕 D-35 布尔，便于前端直接做红条
      "noRecordSnapshot": false      // 🆕 D-39：本单是否从未拍过 _record 快照。**后端恒发**，true 时必须显著提示
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
            "result": "UPGRADED",            // CREATED / UPGRADED / UNCHANGED / BLOCKED（🆕 D-37）
            "blockedReason": null,           // 🆕 D-37：result=BLOCKED 时非空。枚举，目前唯一取值 GRAIN_KEY_COLLISION
            "collidingRows": [],             // 🆕 D-37：result=BLOCKED 时非空，见下方「collidingRows」
            "baseRowCount": 9,               // 主表当前整组行数（= 基底行数）
            "resultRowCount": 9,             // 回填后该组行数。**后端恒发，🚫 绝不缺省**（见硬约束 7）
            "patchedRows": 2,                // _record 表征并覆盖了列的行数
            "untouchedRows": 7,              // 🔑 页签没表征、原样保留的行数
            "unanchoredRows": [              // 🔴 锚不上的行（A0-1 第三支路）
              {
                "recordId": 8812,
                "originId": 161,             // 快照时的主表行 id（已失效）
                "baseRowFingerprint": "9f2c…",
                "displayValues": { "项次": "90", "投入料号": "S-1630010773" },
                "reason": "CROSS_VERSION_FINGERPRINT_MISS"   // 见下方「行级 reason 全集」，🚫 与 nonParticipating 的 reason 是两套独立枚举
              }
            ],
            // 🆕 D-37 · result=BLOCKED 时的明细（其余情况为空数组）
            // "collidingRows": [
            //   { "grainKey": {"material_part_no":"00005","element_code":"C"},  // 物理列名，与 columnScope 同口径
            //     "baseRowCount": 4,        // 该粒度键在**基底**里有几行（>1 = 歧义源头）
            //     "recordRowCount": 1 }     // 该粒度键在 _record 里有几行
            // ],
            "columnScope": {                 // 该页签表征了哪些列 —— AP-60 列维度判据
              "patched": ["component_qty", "unit_weight"],
              "preserved": ["item_seq", "input_material_no", "output_material_type", "…"]
            }
          }
        ]
      }
    ],
    "recordStale": {                 // 🆕 D-35：本单的 _record 快照写失败过 ⇒ 预览内容可能不是最新
      "stale": true,
      "reason": "WRITE_FAILED",
      "detail": "IllegalStateException: …",   // 🚫 仅排障，前端**不得**直接当用户文案渲染
      "detectedAt": "2026-09-07T06:17:29Z"
    },
    "noRecordSnapshot": {            // 🆕 D-39：本单**从来没拍过** _record 快照；null = 不适用（恒发）
      "reason": "NEVER_WRITTEN",     // 枚举，目前唯一值
      "participatingComponents": 3,  // binding 解析成功、本该产出 _record 的组件数
      "recordRows": 0                // 实际 _record 行数（判定成立时恒为 0）
    },
    "nonParticipating": [            // 🆕 D-33：不参与基础数据升版的组件（手写视图，无 builder_config）
      { "componentId": "…", "componentName": "投料", "reason": "NO_BUILDER_CONFIG" }
    ],
    "extendColumnOnly": [               // 只落 extend_column、不回填的字段（AC-3）
      { "sheetKey": "MATERIAL_BOM", "fields": ["自定义列A", "毛利率(公式)"] }
    ]
  }
}
```

**`unanchoredRows[].reason` 全集**（2026-09-07 补 —— 原文只列了一个，实测后端还会返 `NO_ANCHOR`；前端代理指出）：

| 码 | 什么情形 | 给财务看的中文 |
|---|---|---|
| `NO_ANCHOR` | `_record` 这一行**根本没有锚** —— `origin_id` 与 `base_row_fingerprint` 都是空。发生在「只活在 `row_data` 的行」与「用户手工新增的行」上：它们没有经过 driver 展开，主表里本就没有对应行 | 这是报价单上新增的行，基础数据里没有对应记录，确认后按新增写入 |
| `CROSS_VERSION_FINGERPRINT_MISS` | 有过锚，但**跨版后指纹对不上** —— 本单拍快照之后，该行内容被别的报价单改过 | 本单拍快照后，该行内容已被其他报价单改动，无法在当前版本中定位 |

🚫 **这套 reason 与 `nonParticipating[].reason` 是两套独立枚举，不许混成一个值域** —— 前者是「行为什么锚不上」，后者是「组件为什么不参与」。

### `applicable` 的判定（主线 2026-09-07 裁决）

```
applicable = false  当且仅当  tables == []  且  recordStale == null  且  nonParticipating == []
                     且  noRecordSnapshot == null                     ← 🆕 D-39
```
只要四者之一非空，恒为 `true`。

⚠️ 原文「`false` = 本单不走 ds_ 新回填（老单），前端不渲染该区」**太窄**，与本节硬约束 4/6 自相矛盾：
一张 100% 手写视图的老单 `tables` 为空，但它恰恰有 `nonParticipating` 要给财务看 ——
若此时 `applicable=false` 让前端整块不渲染，**警告就在最该出现的场景里消失了**。
⇒ `applicable=false` 的含义收窄为「**真的没什么可说**」；
`applicable=true` + `tables=[]` 是**合法组合**，前端必须能渲染。

**字段语义的三条硬约束**：

1. `untouchedRows` **必须出现在响应里**，且前端**必须渲染**。它是 `AP-60` 的守卫：财务要能看见「这一组有 7 行本次不动」，否则「不写 = 删除」这类后果就永远不在她的视野里。
2. `unanchoredRows` 非空时，`confirmRequired` 仍为 `true` 但前端**必须显著提示**；🚫 不许折叠进「更多」里。
3. `result: "UNCHANGED"` 的组仍要出现在列表里（带 `patchedRows: 0`），🚫 不许过滤掉 —— 否则财务无法区分「这张表没变」和「这张表根本没被算进去」。
4. 🆕 🚨 **`recordStale.stale === true` 时前端必须显著提示**（`D-35`）：「**此刻预览的内容可能不是报价单的最新数据**」。
   🚫 不许折叠、不许静默。文案由前端按 `reason` 映射，**🚫 不许把 `detail` 里的异常原文给财务看**。
   ⚠️ `recordStale` 非空时 `applicable` 恒 `true`（即使 `tables=[]`）—— 否则警告恰好在最该出现时整块不渲染，与 `D-33` 同理。
5. 🆕 **`unanchoredRows[].reason` 与 `nonParticipating[].reason` 是两套独立值域**（后端实测交集为空，定义在两个不同的类）。
   🚫 **前端不许合并成一张映射表。** 行级实测有三个值：`NO_ANCHOR` / `CROSS_VERSION_FINGERPRINT_MISS` / **`SAME_VERSION_ORIGIN_MISS`**（同版但 `origin_id` 没命中）。
7. 🆕 🚨 **`resultRowCount` 后端恒发，前端不得依赖缺省**（`D-37` 配套）。
   语义 = **回填后该组会有多少行**。「一个字节不写」的两态（`UNCHANGED` / `BLOCKED`）恒等于 `baseRowCount`，
   **不是「未知」也不是 0**。
   ⚠️ 实证：前端「组会变小」告警判据是裸的 `resultRowCount < baseRowCount`，字段缺失时取 0 ⇒ `0 < 4` 恒真
   ⇒ **正常单据被点亮红条「该组行数会减少，请先不要确认」** —— 一个缺失字段把正常单据卡住，
   且症状与真缺陷同形。后端已收口（`applyNoWriteContract`），前端的前置闸保留作纵深防御。

8. 🆕 **`result: "BLOCKED"` 的语义是「这一组跳过回填」，🚫 不是「核价通过被拒」**（`D-37`）。
   `BLOCKED` 组一个字节不写（不升版、不归档、不删除），但 **`POST /costing-approve` 照常返回 200、
   报价单照常转 `APPROVED`**。C′ 的目的是「别写坏」，不是「别通过」——
   不拿业务流程给数据层的边界情况陪葬。
   ⇒ 前端必须把 `BLOCKED` 组**显式列出**并说明「本组跳过，需人工处理」，
   🚫 不许折叠、🚫 不许因此禁用确认按钮。
   与「只能整体确认/取消」（`D-26`）不冲突：那条约束的是财务不能挑组确认，不是「有一组异常就整单否决」。

9. 🆕 **`recordStale` 后端恒发**（`D-35`）：无过期标记时为 `null` 且 `summary.recordStale = false`。
   ⚠️ **标记表 `ds_quote_record_stale` 尚未落库期间**，后端有 `information_schema` 探针 ⇒
   `find`/`markStale`/`clearStale` **整体 no-op** ⇒ 该字段**恒为 `null`**、`summary.recordStale` **恒为 `false`**。
   ⇒ 这段窗口内前端的「过期提示」拿不到数据是**预期**，不是前端漏实现；落库后自动开始有值。

10. 🆕 **`noRecordSnapshot` 后端恒发**（`D-39`）：无此情形时为 `null` 且 `summary.noRecordSnapshot = false`。
    **判定 = `participatingComponents > 0` 且 `recordRows == 0`，两个条件缺一不可。**
    🔑 这是「本单没拍过快照」与「本来就没什么要回填」的分水岭 —— 组件全 `nonParticipating` 的单
    第一个条件不成立 ⇒ **不报**，否则告警会在正常场景刷屏，很快就没人看了。
    🚨 非空时前端**必须显著提示**：本单没有任何快照可回填，**确认后主表一个字节都不会写**
    —— 这与「本来就没什么要回填」在界面上无法区分，只能由后端点破。
    ⚠️ 🚫 **不许与 `recordStale` 合并成一个提示**：那条是「写过但过期」，这条是「从来没写过」；
    处置动作不同（前者重存一次即覆盖；后者重存**确实能解决**，但用户得先知道要这么做）。
    这是**第五套**独立 reason 值域。
    📌 已纳入 `previewToken`（`#nosnap=` 段）：预览时报了、确认前销售补存一次让它消失 ⇒ token 失效、需重看。
    📌 最常见成因：**导入建单不经 `saveDraft`**（`_record` 的唯一写点），本期只可见、不补写入（`D-39`）。

6. 🆕 **`nonParticipating` 非空时前端必须显式告知**（`D-33`）：「本单有 N 个组件不参与基础数据升版」。🚫 不许静默 —— 实测现网 156/228 个组件视图是手写的（无 `builder_config`），财务会以为全覆盖了。这与 `AP-60` 判据四（「不写 = 删除」不在 diff 模型里）是同型的静默。

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

## 4. ~~⛔ 待上游确定的字段~~ → ✅ 已确定（2026-09-07 闭合，保留本节作留痕）

| 字段 | 阻塞于 | 现状 |
|---|---|---|
| `dsBackfill.tables[].groups[].customerNo` | ~~上游 `A0-1`~~ ✅ **2026-09-07 已裁决** | 类型 **`string`**（DB 侧 `varchar(20) NOT NULL`，取值 = `customer.code`，如 `CUST-0001`）。契约里不再是 `null` 占位 —— **前端按 `string` 渲染**。<br>⛔ 但上游 DDL **尚未落库**（实测 0 张带版本表有该列）⇒ 后端在建表前该字段实际取不到值，**过渡期允许返 `null`，前端必须容忍 `null` 不报错**（渲染为「—」）。DDL 落地后即恒非空 |
