# 后端任务分解 · task-260901

> 只按本文件做。契约以 `api.md` 为准，AC 原文在 `需求文档.md` §③（**本文件只标编号，不复制原文**）。
> 🚫 遇 `CLAUDE.md` §3.2 不可逆操作红线（DROP/TRUNCATE/无 WHERE 的 DELETE/清库…）**立即停下报告主线**，你没有批准权。

---

## B-0 · 前置调研（先做，结论回报主线，不写代码）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-0** | — | 坐实「未变的行是否真被无谓 UPDATE」。手段二选一：① 在 dev 库临时 `SET log_statement='mod'` 跑一次保存后统计 `update quotation_line_item` / `update quotation_line_component_data` 条数（**用完必须还原**）；② 用 `pg_stat_user_tables.n_tup_upd` 取保存前后差值（零配置改动，优先用这个）。<br>**回报**：一次「改一格」保存实际产生多少条 UPDATE。<br>**为什么要做**：`证据/E2` 里 jsonb 规范化导致判脏是**推断非实测**，B-1 的收益预期建立在它之上。若实测发现 UPDATE 条数本就很少，B-1 的方案要重新评估。 |

---

## 🚨 总则：saveDraft 有两条路径，实跑的是 batch 那条 —— 每处都要改两遍

`saveDraft` 顶部按 kill switch `cpq.savedraft-batch-stage1` 分流（`QuotationService.java:413`，**2026-06-26 起默认 `true`**）：

- `true`（**实际运行的路径**）→ `processBatchStage1(...)`（`:2393~2828`）
- `false`（逃生回落，`-Dcpq.savedraft-batch-stage1=false`）→ 原逐行路径（`:420~701`）

**两条路径各有一份等价代码。只改一条 = 改了等于没改**，且单测若不显式切开关也测不出来。行号对照：

| 关键点 | 逐行路径 | **batch 路径（实跑）** |
|---|---|---|
| 置 NULL 卡片值（B-1c） | `:520-521` | **`:2563-2564`** |
| `cd.subtotal` 赋值（B-1b） | `:648` | **`:2647`（UPSERT 分支）/ `:2657`（新建分支）** |
| `rowData` 赋值（B-1a） | `:647` 附近 | **`:2646`（UPSERT 分支）/ `:2656`（新建分支）** |
| 删除未保留行（B-2b） | `:672-677` | **`:2474-2480`** |

> 📌 本表由 B-0 调研发现主线原文档只标了逐行路径行号后补入（2026-09-01）。**以本表为准。**

## B-1 · 后端识别未变的行（①）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-1a** | AC-6, AC-9, AC-10 | `processBatchStage1` 的 componentData 循环（`:2637~2666`，UPSERT 分支 `:2646`／新建分支 `:2656`）**以及**逐行路径的等价位置：`reused.rowData = cdDraft.rowData` 改为**语义比对后再赋值** —— 用 `MAPPER.readTree(a).equals(MAPPER.readTree(b))` 判等，相等则不赋值。<br>🚨 **禁止用字符串比对**：库列是 jsonb，读回的是 PG 规范化文本（键按字节长度重排），与前端 `JSON.stringify` 必然不等 ⇒ 字符串比对会永远判「变了」，改了等于没改（证据 `E2`）。<br>解析失败 / 任一侧为 null → **按「已变」处理**（失败方向必须安全）。 |
| **B-1b** | AC-6 | 同一处 `reused.subtotal = cdDraft.subtotal`（`:2647`／`:2657`／逐行 `:648`）加 `compareTo` 保护，与 `QuotationService:477`/`:2532` 对 `li.subtotal` 的既有写法**保持一致**（`repair-260829 B-9` 漏修的同款）。 |
| **B-1c** | AC-7, AC-8, AC-19, AC-21 | `li.quoteCardValues = null; li.costingCardValues = null;`（**batch `:2563-2564`** + 逐行 `:520-521`，两处都改）从**无条件**改为**有条件**：仅当该行满足下列任一才置 NULL —— ① 该行任一 componentData 的 `rowData` 经 B-1a 判定为已变；② `productAttributeValues` 变化；③ 该行走的是全删全建路径（非 B-6 UPSERT，即 `snapshot_rows` 确实被重建）；④ 该行是 `added` 新行。<br>🚨 **卡片值的完整依赖输入**（查实结果，判定条件不得少于此集合）：冻结结构 `quotation_view_structure` / `snapshot_rows` / `deleted_row_keys` / `row_data` / `deleted_tree_nodes` / `productAttributeValues`。其中 `saveDraft` 会改的只有 `row_data` 与 `productAttributeValues`（`deleted_tree_nodes` 经 grep 确认 saveDraft 主路径不写）。<br>⚠️ **核价侧不对称**：`buildCostingCardValues` **不读 `snapshot_rows`**，而是重新执行 SQL 视图展开（依赖外部 V6 基础数据），不幂等。跳过置 NULL 不会让它比现状更陈旧（现状也只在 saveDraft 时重算），但实施时须确认。 |

---

## B-2 · 增量协议（②）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-2a** | AC-1~AC-4 | `SaveDraftRequest` 改造：`lineItems` → `added[]` / `modified[]` / `removed[]` + `baseVersion`。`LineItemDraft` 内部字段不增删（见 `api.md §1.2`）。**保留 `lineItems` 字段一个版本周期做兼容**：非 null 时按旧全量语义处理并打 WARN，便于回滚。 |
| **B-2b** | AC-3 | 删除语义从隐式改显式：「删除 payload 未保留的旧行」（**batch `:2474-2480`** + 逐行 `:672-677`，两处都改）改为**只删 `removed` 数组里的 id**。<br>🚨 这是本任务风险最高的一处：失败方向从「误删」反转为「删不掉」（静默残留）。必须有日志记录每次实际删除的 id 列表。 |
| **B-2c** | AC-1, AC-4 | 主循环只遍历 `added` + `modified`，不再遍历全量。`removed` 单独走批量删除。 |
| **B-2d** | AC-4 | 总价改为从库聚合：原 `total = total.add(liDraft.subtotal)` 逐行累加 → 改为写入完成后 `SELECT sum(subtotal) FROM quotation_line_item WHERE quotation_id = ?`。 |
| **B-2e** | AC-1 | `sortOrder` 不再回退 payload 下标（`:480` / `:2535` 的 `: i` 分支删除），改为必填校验：缺失 → 400。 |
| **B-2f** | AC-2 | 组合产品父子关系：`tempParentIndex`（payload 下标）→ `tempParentKey`（父行 tempId）或 `parentLineItemId`。原 `newIdsByIndex[parentIdx]`（`:690~697`）按新键改写。**这是增量后下标失效的第三处，漏改会导致组合产品父子静默错乱。** |

---

## B-3 · 版本指纹（③）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-3a** | AC-11 | Flyway 迁移：`quotation` 加列 `user_data_version integer NOT NULL DEFAULT 0`。<br>⚠️ **不要与既有 `row_version` 混淆**：`quotation_line_item` / `quotation_line_component_data` 已各有一列 `row_version bigint NOT NULL DEFAULT 0`（`V368__task0729_price_adjust_schema.sql:307-308`），那是 price-adjust 的**原生 SQL 乐观锁**，Hibernate 不映射它、也无触发器。🚫 **不许顺手把它改成 JPA `@Version`**——会让 price-adjust 既有写点全部失效。本期新增的是 `quotation.user_data_version`，与之无关。<br>⚠️ 迁移版本号是**移动靶**（多会话并发），建号前先 `ls cpq-backend/src/main/resources/db/migration/ \| tail`，且**已应用到共享库的迁移禁止改名改号**。 |
| **B-3b** | AC-11, AC-12 | `saveDraft` 入口在悲观锁内校验 `baseVersion`：不等 → 抛 409 `STALE_VERSION` + `currentVersion`（响应体见 `api.md §1.4`）。校验必须在**任何写入之前**。 |
| **B-3c** | AC-11 | 本次有实际写入时 `user_data_version = user_data_version + 1`，新值随响应返回。 |
| **B-3d** | AC-14 | `CardSnapshotService.editCardValue`（`quote-card-edit`）同样递增版本号并在响应中回传。 |
| **B-3e** | **AC-13（最关键）** | 🚨 **派生数据写入不得递增版本号**：`ensureCardValues` / `ensureCardValuesDetailed` / `ensureExcelValues` / `snapshotQuotation` / `CreateQuotationMaterializer` 四步 / `priceReconcile` 全部**不碰** `user_data_version`。<br>**判定口径复用 `stableDraftDedupKey` 的既有划分**（它已明确剔除派生字段），不另立标准。<br>违反本条的后果：用户什么都不做，等后台重算跑完就撞 409，而重算是每次保存后必然触发的 ⇒「保存→重算→必冲突→刷新」死循环。 |

---

## B-4 · 回传瘦身（④）

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-4a** | AC-15, AC-16 | 新增 `SaveDraftResponse` DTO（**不复用 `QuotationDTO`**）：单头字段 + `userDataVersion` + `lineItems[]`，每个 line 只含 6 个字段（`id` / `partVersionLocked` / 4 份值快照）+ `tempId` 回传。**不含 `componentData`**。 |
| **B-4b** | AC-15 | `saveDraft` 末尾的 `dto.lineItems = loadLineItems(id)` 替换为只查变化行的 6 个字段的轻量查询。<br>🔑 **这是那 18.8 秒的来源**：`loadLineItems` 要把 9225 条 componentData（9.3 MB）经 1.74 MB/s 的链路搬回、实体化、再序列化（证据 `E1` 场景 3）。 |
| **B-4c** | AC-2, AC-17 | `added` 行的新 id 按 `tempId` 配对回传，**不按数组顺序**（避免重蹈下标耦合）。<br>🚨 **`tempId` 只带在 `added` 行上**（7 键），`modified` 行**恰好 6 键、不带 `tempId`` —— 见 `api.md §1.3` 的收敛表。测试 T-16 断言的正是 `modified` 行的 6 键。两者原本冲突，2026-09-01 已按作用域收敛。 |

---

## B-5 · 回归保障

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| **B-5a** | AC-19, AC-20 | 补后端测试：改一行后，核价侧卡片值、工序、组合工艺三者的**未变行**记录逐字节不变。 |
| **B-5b** | AC-9, AC-10 | 补单测：jsonb 键序不同但语义相同 → 判「未变」；任一值差最后一位小数 → 判「已变」。 |
| **B-5c** | AC-12 | 补集成测试：模拟两个会话的版本冲突，断言 409 + `reason=STALE_VERSION`。 |
| **B-5d** | AC-13 | 补测试：调用 `ensureCardValues` 后 `user_data_version` 不变。**这条是 B-3e 的守卫，不能省。** |

---

## 双向覆盖自检

**正向**（每条 AC 有人认领）：AC-1→B-2a/c/e；AC-2→B-2f,B-4c；AC-3→B-2b；AC-4→B-2a,B-2d；AC-5→前端；AC-6→B-1a/b；AC-7,8→B-1c；AC-9,10→B-1a,B-5b；AC-11→B-3a/b/c；AC-12→B-3b,B-5c；AC-13→B-3e,B-5d；AC-14→B-3d；AC-15,16→B-4a/b；AC-17→B-4c；AC-18→全体；AC-19,20→B-1c,B-5a；AC-21→前端+B-1c；AC-22,23,24→回归。

**反向**（每项指回 AC）：B-0 为调研（无 AC，结论驱动 B-1）；其余 B-x 均已在表中标注所服务的 AC。

---

## 强制自检（`backend.md` §2）

- [ ] 改 DDL 后**强制重启** dev server（Flyway `migrate-at-start`），不手工 `psql -f`
- [ ] 迁移 `SELECT success FROM flyway_schema_history WHERE version='<新号>'` = `t`
- [ ] 业务端点返 200/401，不得 500
- [ ] 🚫 **N+1 硬指标**：单个业务操作的 SQL 条数必须是常数，与行数无关。B-2d 的 `sum()` 是 1 条；B-4b 的轻量查询必须是 1 条 IN 查，**不许逐行查**
- [ ] worktree 内跑测试必须在 worktree 的 `cpq-backend` 下跑（`mvnw` 在该子目录），不要 cd 主仓
