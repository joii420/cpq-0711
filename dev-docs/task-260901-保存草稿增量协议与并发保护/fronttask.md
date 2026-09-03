# 前端任务分解 · task-260901

> 只按本文件做。契约以 `api.md` 为准，AC 原文在 `需求文档.md` §③（**本文件只标编号，不复制原文**）。
> 🚫 遇 `CLAUDE.md` §3.2 不可逆操作红线**立即停下报告主线**，你没有批准权。
> 🚨 `QuotationWizard.tsx` / `QuotationStep2.tsx` 属**渲染主链路 + 协议级改动**（`frontend.md` §2.1.5），改完**强制跑 Playwright E2E**，不是可选项。

---

## F-1 · 行级 diff 计算（②）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **F-1a** | AC-1~AC-4 | 在 `QuotationWizard.tsx` 新增行级基线：保存成功后，为每一行记录一份内容指纹。<br>🚨 **口径已于 2026-09-01 修正，不要照抄 `stableDraftDedupKey`**：<br>· `stableDraftDedupKey` 剔除 `componentData[].rowData` 是为了判断「**要不要发**」；行级 diff 要判断「**哪一行变了**」，目的不同、口径不能照搬。剔掉之后 componentData 只剩 `{componentId, tabName, sortOrder}`，**对单元格编辑完全盲** ⇒ AC-1 与 AC-10 双双**静默**失败（请求照发，只是那行永远不进 `modified`）。<br>· 但 `rowData` 也不能直接进指纹：它由 `snapshotRows()` 从 `driverExpansions` 重算，而 `useSnapAll` 在每次保存前后 live↔snapshot 翻转（`draftPayloadDedup.ts` 头注的三连发教训），同一份用户数据能算出不同字符串 ⇒ 1845 行整体涌进 `modified`。<br>· **正解**：指纹算在 **LineItem（React state）** 上，取两者的共同上游 `componentData[].rows` ＋ `deletedRowKeys`（删行墓碑）。<br>· **指纹不含 `sortOrder`** —— 含了则「删中间一行」会让其后 1844 行下标整体前移全判 modified，AC-3 要求此时 `modified` 为空。当前报价单无拖拽排序入口（只能追加/删除），删行后相对顺序不变，故安全；**将来若加「上移/下移产品」，此条必须重新评估**。 |
| **F-1b** | AC-1~AC-3 | `buildDraftPayload` 产出改为三数组：<br>· `added` = 当前有、基线无（无 `id` 的行）<br>· `modified` = 基线有且指纹变了<br>· `removed` = 基线有、当前无（按 `id`）<br>三者皆空且单头未变 → 不发请求（沿用 `repair-260830` 已交付的两层闸，AC-5）。 |
| **F-1c** | AC-1 | `sortOrder` 改为**显式传全局序号**（不再依赖数组下标，后端 B-2e 会校验缺失→400）。 |
| **F-1d** | AC-2 | 组合产品父子：`tempParentIndex`（数组下标）→ `tempParentKey`（父行 `tempId`）或 `parentLineItemId`。二选一，与 `api.md §1.2` 一致。 |
| **F-1e** | AC-21 | 🚨 **Step3 的 `onSilentUpdate` 必须计入 diff**：它写的是真数据（未设置的年用量落默认值 1、`recomputeRow` 算出的原小计），走**程序化**通道。`repair-260830` 已让它置 `dirtyLinesRef`；本期改行级后，**它触碰的那些行必须进 `modified`**，否则用户在 Step3 什么都不改往下走，这些值永久不落库（真丢数据）。 |

---

## F-2 · 版本指纹（③）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **F-2a** | AC-11 | 打开单据时从 `GET /quotations/{id}` 响应取 `userDataVersion` 存入本地 ref；保存/单元格编辑的响应回传新值时更新它。 |
| **F-2b** | AC-1~AC-4 | 每次 `PUT /draft` 请求体带上 `baseVersion`。 |
| **F-2c** | AC-14 | `quote-card-edit` 的响应新增 `userDataVersion`，收到后更新本地 ref。**漏这一步会导致改完格子再保存必报 409。** |
| **F-2d** | AC-12 | 409 且 `reason === 'STALE_VERSION'` → 弹对话框，**1:1 还原 `原型图/冲突提示.html`**：标题「保存失败」、正文含「这张报价单已被他人修改」、**只有一个「刷新页面」按钮**（不加「强制覆盖」「忽略」等任何其他按钮——用户 2026-09-01 明确裁决强制刷新）。点击后 `window.location.reload()`。<br>🚨 **同族补充（2026-09-01 主线裁决，前端子代理实现）**：本地版本基线**未知（null）时拦下不发**，提示「页面数据不完整，请刷新页面后再保存」，**不落 localStorage 兜底**。理由：后端凭 `added/modified/removed` 任一非 null 判定走增量协议（`QuotationService.java:369-370`），而前端恒发三数组 ⇒ `baseVersion == null` 必然 400（`:372-374`）；而 `handleSaveDraft` 的 catch 只特判 409 `STALE_VERSION`，其余一律吞成「已保存到本地，网络恢复后将同步」—— **失败被伪装成成功**，与 `checkedPayload` 吞 TypeError 同族。可达路径：`getById` 超时 → localStorage 恢复的是 draft payload（无 `userDataVersion`）→ 基线为 null。落点：`QuotationWizard#requireVersionBaseline`，`handleSaveDraft` / `autoSaveDraft` 两条发送路径各一道；`toIncrementalPayload` 的 `baseVersion` 同步收紧为 `number`（非 `number \| null`），让「忘了拦」变编译错误。 |

---

## F-3 · 响应回填改按 id 认领（④）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **F-3a** | AC-17 | 🚨 `syncLineItemsFromResponse`（`QuotationWizard.tsx:794`）现在是**按数组下标对齐**，且开头有 `if (respLines.length !== prev.length) return prev;`。响应改成只回变化行后，长度必然不等 ⇒ **整个回填被静默跳过** ⇒ 新增行拿不到 DB id ⇒ 下次保存重复插入。<br>**必须改为按 `id` 匹配**；`added` 行按响应回传的 `tempId` 认领新 id。 |
| **F-3b** | AC-16 | 响应不再含 `componentData`，确认前端无任何地方依赖它（已查实：`syncLineItemsFromResponse` 只读 6 个字段，`setQuotationPreservingStructures` 只读单头——见 `证据/E3-前端消费点.md`）。 |
| **F-3c** | AC-8 | 保存后的 `warmCardValues` 触发判据保持不变（`shouldWarmCardValues` 检查卡片值是否为空）——B-1c 落地后只有变化行的卡片值为空，故它天然只重算那几行，前端无需改动。**本项为确认项，非改动项。** |

---

## F-4 · 回归确认（不改代码，但必须验）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **F-4a** | AC-22 | 提交审批流程：`handleSubmit` 内的 `handleSaveDraft(false, {skipWarm:true})` 走新协议后仍能正常提交。 |
| **F-4b** | AC-23 | 连续两次编辑不同行 + 刷新后两次改动都在。 |
| **F-4c** | AC-24 | 0 行空单保存不报错。 |
| **F-4d** | AC-19 | 详情页核价视图各页签有数据，不出现「暂无组件数据」。 |
| **F-4e** | AC-5 | 导入建单流程：导入后 `autoSaveDraft`（`import-auto-save` effect）与本期 diff 协议共用 `buildDraftPayload`，确认其 `backendBuiltLinesRef` 跳过逻辑仍生效（后端已服务端建行时不触发客户端首存）。守的是 `repair-260830` 已交付行为不被本期 diff 改造打破。 |

---

## 双向覆盖自检

**正向**：AC-1→F-1b/c；AC-2→F-1d,F-3a；AC-3→F-1b；AC-4→F-1b,F-2b；AC-5→F-1b（沿用既有）；AC-11→F-2a；AC-12→F-2d；AC-13→后端 B-3e（前端无对应改动，由 F-4 回归确认无误报 409）；AC-14→F-2c；AC-16→F-3b；AC-17→F-3a；AC-18→全体；AC-19~24→F-4a~e。

**反向**：F-1a~e、F-2a~d、F-3a~c、F-4a~e 均已在表中标注所服务的 AC。F-3c 为确认项（指向 AC-8 的前端侧无改动结论），F-4 全部为回归项。

---

## 强制自检（`frontend.md` §2.1）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
- [ ] 每个改动的 `.tsx`：`curl -s -o /dev/null -w "%{http_code}" http://localhost:5174/src/<相对路径>` → **200**
- [ ] `curl http://localhost:5174/` → 200
- [ ] 🚨 **Playwright E2E 强制**：`npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`
  - ⚠️ 该 spec 在**干净 master 上恒有 4 条失败**（`144` LEGACY smoke / `463` TC-F1 / `522` TC-F2 / `624` TC-075，后者因缺 `PW_PRECISION_SEED_QUOTATION_NO` 环境变量）。判断回归**必须做 A/B 同型对比**（`git stash` 后跑基线），不许直接把 4 失败当成本次引入
- [ ] 冲突对话框已逐屏比对 `原型图/冲突提示.html`，偏差逐条列出
- [ ] ⚠️ worktree 内改动时，共享 5174 服务的是**主工作区**代码，拿它验证 = 假绿（`frontend.md` §2.2）
