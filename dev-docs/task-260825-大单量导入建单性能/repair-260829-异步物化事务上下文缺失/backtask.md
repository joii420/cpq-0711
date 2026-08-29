# backtask · repair-260829 异步物化事务上下文缺失

> 后端只按本文件做。AC 原文在 `问题说明.md ⑥`，本文件**只标编号不复制原文**。
> 🚦 **B-1 是闸门**：它的结论决定 B-2 走丙还是回落甲。**B-1 未出结论前不许写 B-2 的实现代码。**

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-2, AC-3 | **先证伪方案丙的假设**（实现期第一步）。复用勘察期探针骨架建临时诊断端点（纯读、跑完即删）：<br>· 对照组 = fire-and-forget + **默认** `ManagedExecutor` → 期望 `componentsSnapshot=null`<br>· 实验组 = fire-and-forget + **`cleared(ThreadContext.CDI)`** 的专用 executor → 期望 `present(N tabs)`<br>**两组都要跑，缺对照组 = 空验证。** 结论写进 `test-report.md`，并报主线。<br>🔀 实验组仍为 null → **丙证伪，回落方案甲**（见 `问题说明.md ⑤`「实现期第一步」），不要自行发明第三条路 |
| **B-2** | AC-1, AC-2, AC-9 | 按 B-1 结论实施主体修复。<br>**丙成立**：`BasicDataImportV6Resource` 内另建 `cleared(ThreadContext.CDI)` 的专用 `ManagedExecutor`，**只**用于 `:177` 的 `materializer.materialize(bg)`。<br>**丙证伪**：`CreateQuotationMaterializer:93` 改 `QuarkusTransaction.run(QuarkusTransaction.runOptions().timeout(600), () -> snapshotService.snapshotQuotation(qid))`。`timeout(600)` **不可省**（实测 `S2.snapshotRows=14114ms`，全量路径更久，默认 60s 撞 Narayana reaper）|
| **B-3** | AC-6, AC-7 | 🔒 **隔离纪律：以下一行不动**——`BasicDataImportV6Resource:87`（Step 1 导入）· `priceadjust` 的 6 处 `@Inject ManagedExecutor` · 全局默认 `ManagedExecutor` bean 配置 · `ConfigureSnapshotService.loadComponentsSnapshot` 的 `SUPPORTS` 注解 · `QuotePendingRewriter` / `QuotePendingScope` / 8 张白名单表。<br>提交前用 `git diff --stat` 自证改动文件数 ≤ 2 |
| **B-4** | AC-4 | ① 步空转的**结果校验**：`CreateQuotationMaterializer` 在 ① 之后校验，满足**三元判据**时 → `r.warnings.add(<明确指出组件数据 0 行>)` + `LOG.errorf`（**ERROR 级**）：<br>　`明细行数 > 0` **且** `driver 组件数 > 0` **且** `comp_data 行数 == 0`<br>🚨 **`driver 组件数 > 0` 这一维不可省**（2026-08-29 修正）：少了它就区分不了「① 步炸了」（故障，要报）和「模板本来就挂 0 个 driver 组件」（合法，不报）—— **两者终态都是 `comp_data == 0`**。<br>🔧 **把守卫抽成独立可测方法**（如 `checkMaterializeOutcome(qid, lineCount, driverCompCount, compDataCount)` 或等价形态），便于 test-engineer 直接构造 DB 状态做单测，不必依赖 fire-and-forget 复现。<br>🚫 **不改** `ConfigureSnapshotService.snapshotQuotation` 的签名（它有 5 个调用点）。<br>⚠️ 校验查询本身不得引入 N+1：三个计数各一条 `SELECT count(*)`（或一条聚合查询），不许逐行查 |
| **B-5** | AC-8 | B-4 的三种边界不得误报，逐条对应三元判据的一个维度：<br>　① **明细行 0 条** → 被 `明细行数 > 0` 挡住<br>　② **模板 driver 组件 0 个** → 被 `driver 组件数 > 0` 挡住（**这一维就是为它加的**）<br>　③ **组件视图合法返 0 行** → 被 `comp_data == 0` 挡住（此时 comp_data 行**存在**且 `snapshot_rows='[]'`，行数 = 明细行 × 组件数 ≠ 0）|
| **B-6** | AC-5 | 补 ① 步耗时基线：用请求线程（`POST /quotations/{id}/ensure-card-values` 或 `ConfigureProductResource` 路径）对同规模单跑一次**成功**物化，实测耗时写进 `test.md` 作为 AC-5 的比较基线。<br>🚫 **不得拿 `6689ms` 当基线**——那是 ① 空转的耗时 |
| **B-7** | AC-10 | 存量修复脚本，**19 张单**（构成随实验推进而变，**执行前必须重新查一次**，不要照抄任何时点的快照），**按 `cd`/`sub_nz` 分两类、各走不同步骤**（2026-08-29 三次修正——原方案只考虑了半修复类，漏想了全空类）：<br><br>**A 类·半修复**（`cd>0 且 sub_nz=0`，① 步早年已跑过，comp_data 本来就有数据，只是③步骨架值卡住）：<br>　① `UPDATE quotation_line_item SET quote_card_values=NULL, costing_card_values=NULL WHERE quotation_id=:qid`<br>　② `POST /quotations/{id}/ensure-card-values`<br><br>**B 类·全空**（`cd=0`，① 步从未成功跑过，comp_data 从未被写过，无源数据可渲染）：<br>　① `POST /configure-product/quotations/{quotationId}/refresh-snapshot`（=驱动 `snapshotService.snapshotQuotation`，即 materialize 的①步；UPSERT 语义、保留编辑层、无需请求体、已在 AC-6"从基础刷新"白名单内、T-07 已判零回归；**同步请求线程执行，不经 `managedExecutor.runAsync`，不受本次 CDI 传播竞态影响**）<br>　② `POST /quotations/{id}/ensure-card-values`<br><br>🚨 **只做②不做①会像 `0203`/`0199` 一样"半修复"**（A 类风险）——`ensureCardValues(qid)` 内部走 `force=false`，`IS NULL` 自愈判据会跳过第一次失败物化时写下的非 NULL 骨架 `quote_card_values`（subtotal=0），不重算，金额永远 0 且不报错。<br>🚨 **B 类若只做②不做 `refresh-snapshot`，`ensure-card-values` 对空 comp_data 空转、原样返回 200 但状态分毫不变**（`ensure-card-values` 内部只调 `ensureStructure`+`ensureCardValues`，两者都只渲染"已有"的 snapshot_rows，从不驱动展开）。详见 `问题说明.md` B-1/B-7 追记。<br>🚨 **网络层必须 abort 一切非 GET 请求**（`RECORD.md` 2026-08-28 事故：诊断脚本触发自发 `PUT /draft` 清空 1845 行卡片值）。<br>🚨 **①是写操作，执行前先向主线报告影响面数字**（A 类 quotation_id 列表 + 预计影响行数 = A 类数量×1845，`CLAUDE.md §3.2`），逐批核对行数后再继续。<br>📋 **执行前重新用下面这条 SQL 核定构成**（别用历史快照）：<br>　`SELECT q.quotation_number, (SELECT count(*) FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id WHERE li.quotation_id=q.id) cd, (SELECT count(*) FROM quotation_line_item li WHERE li.quotation_id=q.id AND COALESCE(li.subtotal,0)<>0) sub_nz FROM quotation q WHERE q.id IN (SELECT DISTINCT pending_quotation_id FROM material_bom_item WHERE created_at>='2026-08-26') ORDER BY 1;`<br>　`cd=0` → B 类；`cd>0 且 sub_nz=0` → A 类。<br>🚨 **`quotation.updated_at` 不是判据**：写 `quotation_line_component_data` 不更新主表时间戳，实测有单 `cd` 已 7380 而 `updated_at` 还停在两天前。**判状态一律查 `cd` + `sub_nz` 两个计数，不看时间戳。**<br>✅ **5 张已正常、不得触碰**：`QT-20260825-0180` / `0181` / `QT-20260826-0182` / `QT-20260827-0191` / `QT-20260828-0198` |

## 覆盖说明

- **AC-9（序列）** 由 B-2 覆盖：修好 ① 步后，「建单→编辑→保存→切走切回→刷新」的序列自然成立，无独立实现项；由 T-12 验证。
- **AC-11（自检证据）** 不派 B-x：属主线闸门 B 汇报职责（`CLAUDE.md §6.1`），由主线执行。

## 不做什么（防超范围）

- 🚫 不动 27 万+ 行 pending 基础资料（`is_current` 一行不改）——见 `问题说明.md` E-4
- 🚫 不"顺手"把 `SUPPORTS` 改 `REQUIRES_NEW`（已否决备选，留 BACKLOG）
- 🚫 不重构 `materialize` 的四步编排、不动 `repair-260828` 的批量化成果
- 🚫 不排查 08-26/08-27 那 14 张空单的成因（未坐实项，列入结案后核查）
