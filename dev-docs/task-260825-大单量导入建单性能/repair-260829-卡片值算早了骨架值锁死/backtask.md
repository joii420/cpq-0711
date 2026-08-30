# backtask · repair-260829 卡片值算早了骨架值锁死

> 后端只按本文件做。AC 原文在 `问题说明.md ⑥`，本文件**只标编号不复制原文**。
> 🔒 同一文件（`CreateQuotationMaterializer` / `CardSnapshotService`）已被本任务族返修三次，改动前先读 `问题说明.md ⑤` 的「已否决备选」，别走回头路。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-2, AC-5, AC-6 | **③步落库前的产物自检（本次主体）**。在 `CardSnapshotService` 卡片值落库前加判据，**两个条件同时成立**才判「算早了」：<br>　① 算出的卡片值**所有页签 `baseRows` 合计 == 0**<br>　② 该行 `quotation_line_component_data` 的 `snapshot_rows` **至少一行非空**<br>命中 → **跳过该行落库**（保持 `quote_card_values` 为 `NULL`，留给后续自愈）+ 打 **WARN**（可观测，不许静默）。<br>🚨 **条件②不可省**：只有①会把「组件视图合法返 0 行」的正常空结果误判成故障（AC-6 会红）。<br>⚠️ 判据要能识别 `SUBTOTAL` 页签 —— 它 `baseRows` 恒为 0 属正常（AC-10-③），所以判据是「**所有**页签合计为 0」，不是「**任一**页签为 0」 |
| **B-1b** | AC-2 | **后端 `ensureCardValues` 自检 `MaterializeRegistry`**（2026-08-29 主线审代码后补齐）：③步进入计算**之前**判断 `materializeRegistry.isInProgress(qid)`，为真则**不计算、不落库**直接返回（`IS NULL` 保持待自愈）+ 打 WARN（与 B-1 的 WARN 文案分开，便于分辨「物化中拒算」vs「算完发现是空的」）。<br>🔑 **补它的理由**：B-1 兜不住「①步一行都还没写」的时序 —— 那时 `cds` 也是空的、条件②不成立、判据不拦，骨架值照样落库并被 `IS NULL` 锁死。详见 `问题说明.md ⑤` 改动 1b 的时序推演。<br>🔑 **放后端不放前端**：所有调用方都经过后端，一层挡住前端四处 warm + 自愈 + 手动刷新 + 未来新增调用点。<br>⚠️ 检查点放在**真正开始算之前**；🚨 **不改对外契约**（响应体/状态码不变）；⚠️ 不得形成「永久拒绝」（`end()` 在 finally、内存态重启即清）；建议抽成可传参的判定方法便于单测 |
| **B-2** | AC-3 | **自愈能力**：确认命中 B-1 的行保持 `quote_card_values IS NULL`，从而下次 `ensureCardValues(qid, false)` 的 `IS NULL` 判据会**重算**它。若现有实现会写入非 NULL 占位，必须改掉 —— 这是「锁死」的根源 |
| **B-3** | AC-7 | **不得退化成每次全量重算**：`IS NULL` 自愈判据的既有收益必须保留。自检只在「本次算出来是空的」时介入，不改变正常路径的跳过逻辑 |
| **B-4** | — | **新增 `MaterializeRegistry`**（`cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterializeRegistry.java`）：`@ApplicationScoped` + `ConcurrentHashMap.newKeySet()`，`begin(UUID)` / `end(UUID)` / `isInProgress(UUID)`。内存态，重启丢失可接受。<br>在 `CreateQuotationMaterializer.materialize()` **开头 `begin(qid)`**、**最外层 `finally` 里 `end(qid)`**。<br>⚠️ `begin` 必须放在方法开头那句 `if (r == null || r.quotationId == null) return;` **之后**（否则 `qid` 还没取到）。<br>⚠️ 顶层已有 `catch (Exception e)` 吞掉所有异常不上抛 ⇒ `finally` 必然执行，不会泄漏标志。<br>📌 **本 bean 供并发会话 `修复draft超时问题` 消费**（他们在 `QuotationResource.materializeStatus` 加字段 + 修自愈判据）。**落地后立刻把包名/类名/方法签名报主线，由主线转告对方**；在那之前对方不写消费代码 |
| **B-5** | AC-8, AC-9 | 🔒 **隔离纪律**：**不改** `ensure-card-values` 的对外契约（路径/方法/请求体/响应体/状态码 —— 对方正在其响应上加字段，改契约会撞车）；**不动** `MaterializeExecutor` / `checkMaterializeOutcome`（`repair-260829-异步物化事务上下文缺失` 成果）与 `repair-260828` 的批量化成果；**不动**核价侧独立方法。提交前 `git diff --stat` 自证 |
| **B-6** | AC-4 | 配合还原实验：提供一个能**并发多发** `ensure-card-values` 的手段（临时诊断端点或脚本皆可，跑完即删），供主线在 ①步进行中并发触发 ≥5 次。<br>🚨 **不要用单发端到端验**——本缺陷是时序偶发（`0205` 正常 / `0206` 中招），单次通过证明不了任何事（`repair-260829-异步物化事务上下文缺失` AC-3 已在这上面栽过一次） |

## 不做什么（防超范围）

- 🚫 **不改前端**（自愈判据 `quotationService.ts:329`、四处 warm 调用 `QuotationWizard.tsx:626/633/816/821`）—— 前端归并发会话的 F-4，本线只提供 registry
- 🚫 不给 `ConfigureSnapshotService`（①步）加单飞锁 —— 那是 `BL-0201`（互斥锁根治）的范围，需两线协调
- 🚫 不改 `IS NULL` 自愈判据本身的语义（只让「算出来是空的」不落库，不动判据）
- 🚫 不修存量 —— 全库扫描已 **0 行**（`0206` 已由主线修复）
