# fronttask.md · 前端任务分解

> 立项文档：`./问题说明.md`

---

## ⚠️ 结论已被 `D-23` 推翻：本任务有 1 项前端改动

> **2026-09-09 更新**：用户真机撞到「改完格子点保存草稿必报 409」，根因在前端
> （`waitForPendingEdits()` 只接进提交路径、没接进保存草稿路径），用户裁决**纳入本任务修**。
> ⇒ **原「前端零改动」的判定不再成立**，下方 §1 的判定依据仅对**取数口径改动本身**有效。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** ✅ | AC-21 | 🆕 `D-23`：① `handleSaveDraft`（`QuotationWizard.tsx:1542`）在取 `baseVersion` **之前**加 `await waitForPendingEdits()`；② `handleSubmit` 把 `waitForPendingEdits()`（`:1683`）**移到 `handleSaveDraft()`（`:1675`）之前**。<br>🚨 **必须先复现 409 再修**，并做证伪（注释掉改动后 409 必须回来） |

### F-1 交付实证（commit `8e4d8587`，单文件 22+/4-，主线已独立核验）

**复现（修前）** —— 自然时序拿到的是**另一副面孔**：

```
[11:52:12.857] REQ PUT /quote-card-edit      [11:52:12.921] REQ PUT /draft baseVersion=16   ← 同 tick
[11:52:14.207] RES 500 /draft
后端：ERROR: deadlock detected / while updating tuple in relation "quotation_line_item"
      → PessimisticLockException @ QuotationService.processBatchStage1:3223 ← saveDraft:475
```

确定性的 409 要求窗口 = 「edit **已提交**、响应**尚未回到前端**」。用 Playwright `page.route`
只扣住 `quote-card-edit` 的**响应**（请求照常打后端、事务照常提交，不改任何前端代码路径）：

```
[11:55:10.648] GATE 后端已提交 edit#1（版本已 +1），响应扣住 4000ms 不交付
[11:55:10.710] REQ  PUT /draft
[11:55:10.791] RES  409 {"reason":"STALE_VERSION","currentVersion":19}
后端：[saveDraft-stale] baseVersion=18 但库中 user_data_version=19 → 409
```

**A/B/A 证伪**（主线读 `b6-8098.log` 原始日志独立核验，非采信汇报）：

| 时刻 | 事件 | `saveDraft-stale` |
|---|---|---|
| 04:55:10 | 修前门控复现 | **有** |
| 04:58 | 修后 | 无 |
| 04:59:50 | 注释掉 `await waitForPendingEdits()` | **有** |
| 05:01 / 05:03 | 恢复后 | 无 |

⇒ 判据既不恒真也不恒假。自然时序版复跑时**死锁 500 也一并消失**。


---

## 原判定（对取数口径改动本身仍然有效）：前端零改动

**本次没有 `F-x` 任务项。** 这不是"暂缓"，是**本任务范围内前端确实没有需要改的东西**。

---

## 1. 判定依据（为什么不改）

两个缺陷的改动链是：

```
SemanticCompiler（生成 SQL 与 axisScope）
   → component_sql_view / template.sql_views_snapshot（落盘）
   → ComponentDriverService（决定 hfPartNos 加宽）
   → ExpandDriverResponse.rows（后端返回的行集）
```

**改动全部止步于 `ExpandDriverResponse.rows` 之前。** 前端拿到的是同一个结构的 `rows` 数组，
只是**元素变少了**（跨客户行被滤掉、主件页签闭包行被收窄）。

逐条核对四个可能受影响的前端面：

| 前端面 | 会不会受影响 | 依据 |
|---|---|---|
| **接口契约** | 否 | `ExpandDriverResponse` 的字段集、类型、`driverPath` / `rowCount` 语义全不变。见 `./api.md` |
| **渲染分支** | 否 | 行数变化不触发新的渲染分支；`rowCount === 0` 的兜底分支（AP-38）**本来就有**，且 `AC-13` 会验它 |
| **行键 / 编辑写回** | 否 | 行键由 `row_key_fields` 的**值**决定，不依赖行在数组里的下标。⚠️ 但 `AC-10`（编辑→保存→重开值保留）必须实测，因为 AP-54 的教训正是「渲染用过滤子集、写回用原集合」的下标错位 —— 本次是**后端少返行**，不是前端过滤，**结构上不构成 AP-54**，仍须用例证明 |
| **页面布局 / 交互** | 否 | 无新页面、无新弹层、无布局或交互改动 |

⇒ **不触发 `frontend.md §1.3` 的原型图要求**（该规则的触发条件是「新页面 / 新弹层 / 布局或交互改动」，本次一条都不沾）。
⇒ 本任务目录**无 `原型图/`**，这是判定结果，不是遗漏。

---

## 2. 前端回归确认清单（不改代码，但要验）

由测试分片 `S-UI` 承担（见 `./test.md`），**主线亲验时逐条复核**：

| # | 确认项 | 对照 AC |
|---|---|---|
| R-1 | 报价单编辑页 Step2 产品卡片各页签**正常渲染**，不出现「加载中…」永久占位（AP-31 族） | AC-1, AC-3 |
| R-2 | 主件页签收窄到 1 行后，**不出现 "—" 或空白单元格**（AP-38：0 行 driver 的鬼魂行） | AC-1, AC-13 |
| R-3 | 页签切换 → 刷新页面，行数**稳定不变**（不出现切回后变多） | AC-9 |
| R-4 | 单元格编辑 → 保存草稿 → 重开，**值保留且行不错位** | AC-10 |
| R-5 | 详情页（`ReadonlyProductCard`）与编辑页（`QuotationStep2`）**行数一致** —— AP-41/AP-50 的两侧不对齐族 | AC-1, AC-3 |
| R-6 | 报价单**提交**不再返「行键重复」422 | AC-2 |

⚠️ **R-5 单独列出来的理由**：AP-41（prop drilling 漏传）与 AP-50（详情页渲染层 single-source）
两次都表现为「一个视图功能正常另一个失效」。本次后端返回的行集变了，**两个消费方都要看**。

---

## 3. 二期触发条件（什么情况下前端就要改了）

| 触发条件 | 会需要的前端改动 |
|---|---|
| 将来若把 `axisScope` 暴露到取数配置器 UI 让用户逐页签选 | `SqlViewBuilderTab.tsx` 加一个「轴范围」选择控件 |
| 若用户要求在卡片上**显式提示**「本页签只显示当前料号」 | 页签头加说明文案 |
| 若缺陷② 的口径将来扩到费用类页签（`D-1` 现裁为不扩） | 无前端改动，仍是后端 `axisScope` 取值变化 |

以上**本期一条都不做**。
