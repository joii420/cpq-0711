# backtask · repair-260829 异步物化事务上下文缺失

> 后端只按本文件做。AC 原文在 `问题说明.md ⑥`，本文件**只标编号不复制原文**。
> 🚦 **B-1 是闸门**：它的结论决定 B-2 走丙还是回落甲。**B-1 未出结论前不许写 B-2 的实现代码。**

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-2, AC-3 | **先证伪方案丙的假设**（实现期第一步）。复用勘察期探针骨架建临时诊断端点（纯读、跑完即删）：<br>· 对照组 = fire-and-forget + **默认** `ManagedExecutor` → 期望 `componentsSnapshot=null`<br>· 实验组 = fire-and-forget + **`cleared(ThreadContext.CDI)`** 的专用 executor → 期望 `present(N tabs)`<br>**两组都要跑，缺对照组 = 空验证。** 结论写进 `test-report.md`，并报主线。<br>🔀 实验组仍为 null → **丙证伪，回落方案甲**（见 `问题说明.md ⑤`「实现期第一步」），不要自行发明第三条路 |
| **B-2** | AC-1, AC-2, AC-9 | 按 B-1 结论实施主体修复。<br>**丙成立**：`BasicDataImportV6Resource` 内另建 `cleared(ThreadContext.CDI)` 的专用 `ManagedExecutor`，**只**用于 `:177` 的 `materializer.materialize(bg)`。<br>**丙证伪**：`CreateQuotationMaterializer:93` 改 `QuarkusTransaction.run(QuarkusTransaction.runOptions().timeout(600), () -> snapshotService.snapshotQuotation(qid))`。`timeout(600)` **不可省**（实测 `S2.snapshotRows=14114ms`，全量路径更久，默认 60s 撞 Narayana reaper）|
| **B-3** | AC-6, AC-7 | 🔒 **隔离纪律：以下一行不动**——`BasicDataImportV6Resource:87`（Step 1 导入）· `priceadjust` 的 6 处 `@Inject ManagedExecutor` · 全局默认 `ManagedExecutor` bean 配置 · `ConfigureSnapshotService.loadComponentsSnapshot` 的 `SUPPORTS` 注解 · `QuotePendingRewriter` / `QuotePendingScope` / 8 张白名单表。<br>提交前用 `git diff --stat` 自证改动文件数 ≤ 2 |
| **B-4** | AC-4 | ① 步空转的**结果校验**：`CreateQuotationMaterializer` 在 ① 之后查本单 `quotation_line_component_data` 行数，若「明细行 > 0 且 comp_data == 0」→ `r.warnings.add(<明确指出组件数据 0 行>)` + `LOG.errorf`（**ERROR 级**）。<br>🚫 **不改** `ConfigureSnapshotService.snapshotQuotation` 的签名（它有 5 个调用点）。<br>⚠️ 校验查询本身不得引入 N+1：一条 `SELECT count(*)`，不许逐行查 |
| **B-5** | AC-8 | B-4 的三种边界不得误报：① 明细行 0 条 ② 模板 driver 组件 0 个 ③ 组件视图合法返 0 行（此时 comp_data 行**存在**且 `snapshot_rows='[]'`）。判据用「明细行 > 0 **且** comp_data == 0」，不是「comp_data == 0」 |
| **B-6** | AC-5 | 补 ① 步耗时基线：用请求线程（`POST /quotations/{id}/ensure-card-values` 或 `ConfigureProductResource` 路径）对同规模单跑一次**成功**物化，实测耗时写进 `test.md` 作为 AC-5 的比较基线。<br>🚫 **不得拿 `6689ms` 当基线**——那是 ① 空转的耗时 |
| **B-7** | AC-10 | 存量修复脚本：19 张空单分批重跑 `ensure-card-values`。<br>🚨 **网络层必须 abort 一切非 GET 请求**（`RECORD.md` 2026-08-28 事故：诊断脚本触发自发 `PUT /draft` 清空 1845 行卡片值）。<br>🚨 **执行前先向主线报告影响面数字**（`CLAUDE.md §3.2`），逐批核对行数后再继续 |

## 覆盖说明

- **AC-9（序列）** 由 B-2 覆盖：修好 ① 步后，「建单→编辑→保存→切走切回→刷新」的序列自然成立，无独立实现项；由 T-12 验证。
- **AC-11（自检证据）** 不派 B-x：属主线闸门 B 汇报职责（`CLAUDE.md §6.1`），由主线执行。

## 不做什么（防超范围）

- 🚫 不动 27 万+ 行 pending 基础资料（`is_current` 一行不改）——见 `问题说明.md` E-4
- 🚫 不"顺手"把 `SUPPORTS` 改 `REQUIRES_NEW`（已否决备选，留 BACKLOG）
- 🚫 不重构 `materialize` 的四步编排、不动 `repair-260828` 的批量化成果
- 🚫 不排查 08-26/08-27 那 14 张空单的成因（未坐实项，列入结案后核查）
