# task-0806 模板发布全量冻结 —— 测试报告（test-report.md）

> 执行人：测试工程师（本文件作者，即 `test.md` 的编写者）
> 执行日期：2026-08-08
> 依据：`需求文档.md`（**当前版**，含 D16~D20 对 D3 的推翻）｜`api.md`（B22 最新契约）｜`test.md`（本轮同步更新，失效用例已标注）
> 与 `test.md` 的关系：本文件是执行记录的汇总与结论；逐条"实际结果"已直接回填进 `test.md` 对应用例，本文件不重复贴长文本，只摘要 + 给结论。

---

## 1. 执行环境

| 项 | 值 |
|---|---|
| 分支 | `feat/task-0806-template-freeze`（worktree `/home/joii/project/cpq/.claude/worktrees/task-0806-template-freeze`） |
| 后端实例 | **8095**（`quarkus:dev`，跑本 worktree 代码，连 `cpq_db_0724`）——**未另起实例**，全程复用 |
| 前端 | 未起 5174 专属联调（本轮以 API/SQL/代码审查为主，未做浏览器交互测试，见 §6 限制说明） |
| 主库 | `10.177.152.12:5432/cpq_db_0724`（[DEV-API]/[SQL] 用例） |
| 测试库 | `10.177.152.12:5432/cpq_db`（`./mvnw test` 的 `test` profile，仅 TC-REG-6/8 涉及） |
| 权限测试账号 | `admin`(SYSTEM_ADMIN，既有) + 本轮新建又已停用的 `test0806_bob`(SALES_MANAGER) / `test0806_alice`(SALES_REP)（测试后 `PATCH status=INACTIVE`，未删除，用户表无 DELETE 端点） |
| 数据洁净度 | 全部一次性测试夹具（`TEST-0806-*` 模板 5 个、其配套报价单 1 张）已在测试结束后**裸 SQL 清理**；对真实生产数据（COMP-0045/COMP-0181、罗克韦尔模板1）的临时破坏性操作均已**逐 md5 核对精确恢复**，证据见 §7 |

---

## 2. 用例执行汇总

`test.md` 原 81 条用例中，按任务分工："主线已亲验通过"的 12 组 AC（AC-1/2/3/4/7/8/10/11/15/16/17 + 体检A/B）**引用不重跑**；本轮聚焦执行：**权限矩阵、并发/幂等、回归面、AC-5/6/9/12/13/14**，另有 5 条 D16 推翻旧模型后**新增编号**的用例（TC-09-6~12、TC-12-3/12-4）覆盖当前实际判据。

| 类别 | 用例数 | 通过 | 部分通过/有出入 | 失败 | 未执行/阻塞 |
|---|---|---|---|---|---|
| AC-5（10 处收口） | 3 | 1 | 2 | 0 | 0 |
| AC-6（快照缺失报错） | 4 | 3 | 1 | 0 | 0 |
| AC-8（admin 后门，补充复测） | 2 | 2 | 0 | 0 | 0 |
| AC-9（现行 D16~D20 判据，新增 7 条替代旧 5 条） | 8（含 TC-09-5 保留 + TC-09-6~12 新增） | 7 | 1 | 0 | 0 |
| AC-12（现行判据，新增 2 条替代旧 1 条） | 3（TC-12-2/3/4） | 2 | 1 | 0 | 0 |
| AC-13（前端文案） | 4 | 4 | 0 | 0 | 0 |
| AC-14（文档固化） | 5 | 2 | 2 | 0 | 1（未验证，非本轮范围） |
| 权限矩阵 TC-PERM-1~4 | 4 | 4 | 0 | 0 | 0 |
| 并发/幂等 TC-CONC-1~3 | 3 | 3（其中1条以"顺序"代替"严格并发"，见备注） | 0 | 0 | 0 |
| 回归面 TC-REG-1~9 | 9 | 4 | 2 | 1（TC-REG-6，因环境阻断判 BLOCKED 计入失败） | 2（TC-REG-3/7 未执行） |
| AC-18 E2E | 2 | — | — | — | **2（明确不执行，见 §5）** |
| **本轮小计** | **47** | **32** | **9** | **1** | **5** |
| 主线已亲验（引用，不重跑） | 34（AC-1/2/3/4/7/8/10/11/15/16/17 各自条目 + 体检A/B） | 34（引用） | — | — | — |
| **全部合计** | **81** | **66** | **9** | **1** | **5** |

**阻塞用例**：0（无用例因缺前置条件彻底跑不了；TC-REG-6/8 判 BLOCKED 是因环境问题拿不到干净结论，非"用例本身无法设计/无法触达"）

---

## 3. 缺陷清单

### D-1【严重，阻塞集成测试执行，不阻塞线上功能】Flyway V382 迁移 checksum 冲突，`cpq_db` 测试库无法通过 `./mvnw test` 启动 Quarkus

**现象**：`cd cpq-backend && ./mvnw test` 报 `Tests run: 2302, Failures: 1, Errors: 10, Skipped: 1252`，其中 7 个 `@QuarkusTest` 类（`SessionLifecycleTest` / `ComponentCycleUnauthenticated401Test` / `Task0805ConsolidateScopeTest` / `Task0805CommitIgnoreUnboundTest` / `Task0805ExportBindingReportTest` / `Task0805PreviewBindingTest` / `GoldenCardValuesEquivTest`）全部以 `TestAbortedException: Boot failed` 判定失败/跳过。

**根因**：
```
Migration checksum mismatch for migration version 382
-> Applied to database : -1813061758
-> Resolved locally    : 479310567
```
`cpq_db`（test profile 专用库）的 `flyway_schema_history` 记录显示 `V382` 于 **2026-08-07 00:39:05** 首次应用，checksum 为 `-1813061758`；但 worktree 当前 `V382__task0806_template_component_snapshot.sql` 文件内容的 checksum 是 `479310567`。对照 commit 历史，V382 文件在这之后又经历了 **B20**（`24f3751e` 删除存量对齐 INSERT 段）和 **B21**（追加 `UPDATE template SET components_snapshot=NULL`）两轮实质性重写——即：`cpq_db` 里跑的是 B20/B21 之前的旧版 V382，与当前文件不一致，触发 Flyway 的 checksum 校验保护机制拒绝启动。

**预期**：`./mvnw test` 应在 `cpq_db` 上干净跑完（或至少无新增失败）。
**复现**：`cd cpq-backend && ./mvnw test`，看到上述 `FlywayValidateException` 堆栈。
**环境**：worktree `feat/task-0806-template-freeze`，`test` profile → `10.177.152.12:5432/cpq_db`。
**影响**：阻断 TC-REG-6（全量集成测试）、TC-REG-8（`GoldenCardValuesEquivTest` 两个 golden 值不漂移的直接证据）——**这两条是 CLAUDE.md 强制要求的合并前自检项，目前拿不到干净结果**。同时级联影响另外 5 个与本任务无关的历史测试类，掩盖了它们的真实通过/失败状态。
**建议**：合并前需在 `cpq_db` 上跑一次 `flyway repair`（更新 history 里 V382 的 checksum 为当前值）或者干脆重建 `cpq_db`（该库本就是纯测试库，无宝贵数据）。**这不是本次测试工程师能/应该做的操作**（涉及共享测试库的 schema 变更决策），已如实上报，需要后端工程师或主线处理并在合并前重跑一次确认。

---

### D-2【严重】`ExcelViewService.loadFrozenComponentMetaMap()` 用 jsonb 是否为空判断"是否 DRAFT"，导致 PUBLISHED-未冻结 模板在 TAB_JOIN_FORMULA 路径下静默回落读活表

**现象**：`ExcelViewService.java:571` —— `if (t == null || t.componentsSnapshot == null || t.componentsSnapshot.isBlank()) return java.util.Map.of();`。V382/B21 已把"PUBLISHED/ARCHIVED 但尚未按新语义冻结"的模板 `components_snapshot` 置为 `NULL`（与真正 DRAFT 模板的 `NULL` 完全一样）。所以本方法**无法区分**"这是草稿"还是"这是过渡期未冻结的已发布模板"，两者都返回空 map。调用方 `buildTabJoinEffectiveRows`（`:518-560`）拿到空 map 后，在 `:545` 直接 `Component.findById(cd.componentId)` **读活表兜底**——这正是本任务从头到尾要消灭的"活穿透"行为，而且是**静默**的，不抛 `TemplateNotFrozenException`，不产出任何用户可见的异常信号。

**对照**：同一文件里 `tabDefsOfTemplate`（`:1098-1174`）在 B21 时已经改成用 `"DRAFT".equals(t.status)` 做真正的状态判断（`:1106`），且注释明确写着这是"缺口一"的修复方式——但这个修复**没有传播到同文件的兄弟方法** `loadFrozenComponentMetaMap`，是**同一类缺陷在同一文件内的重复发生**。

**预期（AC-5①）**：`CardSnapshotService` / `ExcelViewService` 活表直查 = 0（这两处按需求文档 2026-08-07 修订判据，无 DRAFT 分支豁免）。
**复现**（≤5 步）：
1. 新建 PUBLISHED 模板并配一列 `TAB_JOIN_FORMULA` 的 Excel 列（`excel_view_config`）
2. 该模板下产品行走过一次含 `componentData` 的正常流程
3. 裸 SQL `DELETE FROM template_component_snapshot WHERE template_id=X` + `UPDATE template SET components_snapshot=NULL WHERE id=X`（模拟 D17 未冻结态）
4. 调用触发 `buildTabJoinEffectiveRows` 的接口（Excel 视图渲染，`hasTabJoin=true` 时）
5. 观察：无 409，无 500，直接用活表当前配置算出一份"看起来正常"的结果

**环境**：代码级确认（grep + 通读实现），HTTP 级复现因需要"含 TAB_JOIN_FORMULA 列的模板 + 已有 componentData"这一较窄前置条件、且构造成本较高，本轮**未做端到端 HTTP 复现**，以代码读证为准。
**影响**：严重（触发条件窄，仅影响使用 `TAB_JOIN_FORMULA` 列的 Excel 导出/视图；一旦触发即是"数据从哪读"错误，且完全静默，用户/运维都不会收到任何信号）。
**建议**：把 `loadFrozenComponentMetaMap` 的判据从 `componentsSnapshot == null` 改成 `!"DRAFT".equals(t.status)`（与 `tabDefsOfTemplate` 同款修法），或者干脆改为调用 `publishedTemplateReader.isFrozen(templateId)` 结合 `t.status` 判断，让 PUBLISHED-未冻结 走到 `allTabsOf` 触发 `TemplateNotFrozenException`。

---

### D-3【一般】AC-6 文档承诺的"错误信息含 sortOrder"实际不可达——`findTab()` 是零调用方的死代码

**现象**：api.md §10 与 `test.md` TC-06-1 都写明"快照有行但缺某 sortOrder"应报 `500` 且错误信息含 `templateId` **和** `sortOrder`（示例：`模板快照缺失：templateId={id}, sortOrder={n}。已发布模板存在部分页签快照但该页签缺失...`）。这条消息文本的产出者是 `PublishedTemplateReader.findTab()`（`:160-173`）。但经 `codegraph_callers` + `/usr/bin/grep -a` 双重确认，**`findTab` 在生产代码里零调用方**（`RowDataMaterializer.findTab` 是同名但完全无关的私有方法，操作 `JsonNode` 不是 `PublishedTemplateReader`）。

实际生效的"部分缺行"检测机制是 `verifyConsistentWithJsonb()`（内嵌于人人都会经过的 `allTabsOf`），比对的是**表行数 vs jsonb 数组长度**，产出的消息是：
```
模板快照损坏：templateId=X，template_component_snapshot 行数=Y，components_snapshot jsonb 长度=Z。
两者应恒相等...出现不相等说明其中一侧被发布流程之外的方式改动过（后门 / 裸 SQL），请检查两张表
```
**不含具体缺失的 sortOrder**，只能定位到"哪个模板坏了"，不能一眼看出"坏在哪个页签"。

**预期**：错误信息含 `templateId` 和缺失的 `sortOrder`。
**复现**：①一次性发布一个 3-tc 模板 ②裸删其中 1 行快照（保留其余 2 行） ③触发渲染（如 `GET /templates/{id}/excel-view-config/tab-defs`）④观察返回消息，无 `sortOrder=` 字样。
**环境**：`cpq_db_0724`，一次性模板 `caea9e0f-...`。
**影响**：一般——**核心不变量（500 报错 + 不回落活表）完全成立**，只是运维排错时定位精度打了折扣，且 api.md 文档描述了一个不会发生的行为（误导后续维护者以为 `findTab` 有在用）。
**建议**：二选一——① 把 `findTab` 真正接入某条调用路径（如 `verifyConsistentWithJsonb` 检测到不一致后，进一步逐行 diff 找出具体缺哪个/哪些 `sortOrder` 加进消息）；② 若判定不值得改代码，至少把 api.md §10 和 `docs/三大核心模块基线.md` 里那条"含 sortOrder"的消息示例改成实际的"行数 vs jsonb 长度"格式，避免文档与代码继续脱节。

---

### D-4【一般，文档完整性】AC-14 四份目标文档均止步于 B19，未追上同一任务后续的 D16→D20 决策链

**现象**：`docs/PRD-v3.md` / `docs/三大核心模块基线.md` / `docs/方案制定前必读.md` / `docs/反模式.md` 四份文档均已按 AC-14 要求做了实质性更新（非敷衍打勾，见 `test.md` TC-14-1~4 的 diff 摘录）。但全文 `grep -a "TEMPLATE_NOT_FROZEN\|首次冻结\|freeze-unfrozen"` 在这四份文档里 **0 命中**。对照 commit 时间线，这些文档更新集中在 `74577376`（2026-08-07 上午，B19 收尾）；而"不迁移存量 + 409 TEMPLATE_NOT_FROZEN + A11/A12 首次冻结端点"这一整条决策链（D16~D20）发生在 `24f3751e` 之后，即**同一天下午到次日**。四份文档因此仍在描述"miss 一律 500 + 一次性迁移回填存量"的旧模型，**与最终实际交付的行为不符**。

**预期**：AC-14 要求文档"内容与本文档（需求文档.md）一致"——需求文档.md 本身已完整收录 D16~D20，四份基线/规范文档理应同步。
**复现**：`/usr/bin/grep -a "TEMPLATE_NOT_FROZEN\|首次冻结" docs/PRD-v3.md docs/三大核心模块基线.md docs/方案制定前必读.md docs/反模式.md` → 0 命中。
**环境**：worktree 内文档文件，纯文本核对。
**影响**：一般——不影响运行时行为，但会让后续读这几份"权威基线文档"的人（包括未来处理相关 bug 的 agent）得到与当前代码不符的心智模型，例如以为"快照 miss 一律 500"，从而在排查"打开报价单报 409"时误判为异常而非正常过渡态。
**建议**：主线在收尾前补一轮文档更新，把 D16~D20 的核心结论（不迁移存量 / 零行=409/正常态 / A11 freeze / A12 批量首次冻结）追加进这四份文档。

---

### D-5【轻微，风险提示级，非本次改动引入】`refreshQuoteCardValues`/`refreshDraftQuoteCards` 的整体 `catch(Exception)` 可能悄悄吞掉 `TemplateNotFrozenException`

**现象**：`CardSnapshotService.refreshQuoteCardValues(li, force)`（`:3138-3229`）整个方法体包在 `try { ... } catch (Exception e) { LOG.warnf(...); }` 里（`:3226-3228`），这是**该方法本身早已存在的"降级不阻断"设计**（javadoc 明写"任一步失败 → 保留上一次报价值快照，不抛、不阻断打开"），并非本次改动引入。但本次改动把 `PublishedTemplateReader.allTabsOf`（会在未冻结时抛 `TemplateNotFrozenException`）接入了这条调用链深处（经 `loadQuoteTabsForValues`/`expandFlatDriverBaseRows`/`overlayTreeTabsFromFrozenSnapshot` 等）。若某个已存在但从未真正 bake 过冻结结构的极端场景走到这条路径且模板未冻结，异常会被这个 catch 静默吞掉，只在后端日志留一行 `WARN`，HTTP 层面用户拿到 `200` 且值"看起来没变化"，感知不到系统在提醒"该模板需要冻结"。

**实测边界**：本轮对**已有正常 bake 过结构**的真实报价单（罗克韦尔模板1）做过清零快照测试，`refresh-card-snapshot` 走的是 `loadQuoteTabsForValues` 优先读该行自己的 `quotation_view_structure`，**根本不会重新问** `PublishedTemplateReader`，所以该场景下**不会**触发本缺陷（详见 `test.md` TC-09-8）。真正会踩到这个 catch 的场景（"已存在 line item 但从未真正 bake 过，随后模板又变成未冻结"）在正常业务流程下**较难自然出现**（因为 `ensure-card-values`——首次 bake 的唯一入口——本身对未 bake 行是能正确 409 的，见 TC-09-7），故本条降级为**轻微/风险提示**，不是已证实可复现的 P1 缺陷。
**建议**：主线评估是否需要在这个 catch 块里对 `TemplateNotFrozenException` 单独放行（不吞、继续往外抛），使"刷新基础数据"按钮在遇到未冻结模板时也能让用户看到明确提示，而不是自求多福地保留旧值。

---

### D-6【轻微，归因未确认】`./mvnw test` 中另外两组失败与本任务无关文件不在本分支 diff 内，疑似环境/预置问题而非本任务回归

1. `QuotePendingScopeOpenWhitelistTest.openCallSites_fileLevelWhitelist_exactMatch` FAIL：实际命中文件集合比白名单多了 `com/cpq/quotation/service/QuotationService.java`。`QuotationService.java` **不在**本分支相对 `master` 的 diff 里（`git diff master --stat` 未列出该文件）。
2. `DataLoaderTest` 4 个方法 NPE：`this.sqlViewExecutor is null`，同样 `DataLoaderTest.java` / `DataLoader.java` 不在本分支 diff 内。
3. 前端 `formulaGolden.test.ts` 2 个用例 FAIL（`amt-002`/`amt-003`）：`formulaGolden.test.ts`/`formulaEngine.ts` 不在本分支 diff 内。

**结论**：三组失败对应的源文件均未被本分支改动，**从"diff 是否触碰"这个角度看不是本任务引入的回归**。但由于 D-1（Flyway checksum）已经让整个后端测试环境无法干净启动，**本轮未能在 master 上跑一次"背靠背对照"来做最终确认**（项目历史教训"agent报的bug两个方向都要对照实验"要求的标准动作未能完成）。建议主线在解决 D-1 后，顺手在 master 跑一次同款测试做 A/B 对照，若 master 同样失败则可放心归为存量问题，从 test-report 里降级为"已知不相关"。
**影响**：轻微——大概率是存量问题，但严谨起见不敢直接下"与本任务无关"的最终结论。

---

## 4. AC 逐条达成对照表

| AC | 判定 | 依据 |
|---|---|---|
| AC-1 | ✅ 达成 | 主线亲验（引用） |
| AC-2 | ✅ 达成 | 主线亲验（引用）+ 本轮 `npx vitest run`：1105 测试中 1103 通过，2 个失败（`formulaGolden.test.ts`）与本任务改动的文件无关（见 D-6），不计入 AC-2 判定 |
| AC-3 ⭐ | ✅ 达成 | 主线亲验（引用） |
| AC-4 | ✅ 达成，且本轮追加强证据 | 主线亲验（引用）+ 本轮 TC-REG-5：真实 `PUT /components/COMP-0181` 保存后，**7 个**引用模板快照 md5 **逐一比对前后完全一致** |
| AC-5 | ⚠️ **未完全达成** | `CardSnapshotService`=0✅、`ConfigureSnapshotService`=1 处且正确门控✅，但 `ExcelViewService` 实测 **1 处不合规**（D-2）。判定：AC-5①"ExcelViewService 活表直查=0"**未达成** |
| AC-6 | ⚠️ **部分达成** | 核心不变量（500 + 不回落活表 + 活表完好仍报错）**全部验证通过**；但错误消息不含 `sortOrder`（D-3），与 api.md §10 文档承诺的具体格式有出入 |
| AC-7 | ✅ 达成 | 主线亲验（引用） |
| AC-8 | ✅ 达成 | 主线亲验（引用）+ 本轮 TC-08-1/TC-08-2/TC-CONC-3 追加确认（预览零写入、执行写审计、重复执行幂等） |
| AC-9 | ✅ **达成**（按 D16~D20 现行判据，非 test.md 原 TC-09-1~4 的旧判据） | 本轮全新端到端验证：①V382 无回填✅ ②真实未冻结态触发 409 TEMPLATE_NOT_FROZEN✅ ③A11 freeze 成功恢复✅ ④500-vs-409 两类 miss 正确区分✅（虽然 500 消息格式见 D-3）⑤`archive()` 自动补冻✅。旧 TC-09-1~4 已在 test.md 标注作废 |
| AC-10 | ✅ 达成 | 主线亲验（引用）；本轮在自建/真实模板上多次调用 `frozen-drift` 附带验证其 `frozen` 字段语义正确 |
| AC-11 | ✅ 达成 | 主线亲验（引用）+ 本轮 TC-REG-2 意外印证：18-tab COSTING 真实核价单渲染正常 |
| AC-12 | ⚠️ **部分达成（弱验证为主）** | 现行判据（冻结前后逐位一致）用一次性简单模板验证通过，但只有 1 个 INPUT 字段无公式，属弱验证；强验证（多字段+公式+批量）依赖主线已有的 `GoldenCardValuesEquivTest` 手法，本轮因 D-1 环境阻断未能亲自复核该测试的 golden md5 是否漂移。**另外做了一次真实 6-tab 生产模板的清零/精确恢复 md5 全等验证（TC-12-4，强证据）**，但这验证的是"操作本身无副作用"，不完全等价于"值中性"本身 |
| AC-13 | ✅ 达成 | grep 0 命中 + Alert 三层文案 + tsc 0 错误 + vite 200，全部确认 |
| AC-14 | ⚠️ **部分达成** | 4 份文档均有实质更新，但均止步于 B19，未反映 D16~D20（D-4）；`RECORD.md` 回写未验证（不在本轮职责范围） |
| AC-15 | ✅ 达成 | 主线亲验（引用） |
| AC-16 | ✅ 达成 | 主线亲验（引用） |
| AC-17 | ✅ 达成 | 主线亲验（引用）+ 本轮 `archive()` 测试（TC-09-9）附带印证 |
| AC-18 | ⛔ **未执行** | 见 §5，明确原因 + 补验计划 |

**汇总**：18 条 AC 中，**13 条完全达成**、**4 条部分达成/有出入**（AC-5、AC-6、AC-12、AC-14）、**1 条未执行**（AC-18，非本轮职责）。

---

## 5. AC-18（E2E）未执行说明

`quotation-flow.spec.ts` / `composite-product-flow.spec.ts` 的 Playwright 配置指向 `localhost:5174`（前端）+ **`localhost:8081`**（后端）——8081 跑的是**主仓 master 分支代码**，本任务的新端点（`freeze`/`freeze-unfrozen`/`frozen-drift`/`sqlview-closure-check`）以及改造后的渲染路径在 8081 上**不存在**，E2E 打过去测的是旧代码，结果毫无意义，故本轮**主动不执行**，避免产出虚假的"通过"或"失败"信号。

**由谁在何时补**：按 `dev-docs/任务平台规则.md` §4 步骤 10「主线亲验」流程，本分支合并进 master、8081 重启热加载新代码后，由主线在合并后立即补跑：
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
断言标准见 `test.md` §10.2（两个 spec 均 `1 passed`，`'加载中' final count = 0`，9+ 张截图证据）。

---

## 6. 本轮测试方法的限制（如实说明，供解读结果时参考）

1. **无浏览器/Playwright 交互测试**：本轮除已执行的 `tsc`/`vitest`/静态文件 curl 外，未做任何真实浏览器 UI 操作。TC-REG-3（详情页三视图切换）、TC-REG-7（`BulkImportPartsDrawer` 批量导入交互）依赖前端组件内部状态流转，未执行，标注见 `test.md`。
2. **并发测试非严格同时**：TC-CONC-1/3 受限于工具的顺序化 `curl` 执行方式（Bash 工具的后台任务机制在本环境下退化为"先后极短间隔"而非"同一进程内真正的字节级并发"），未能构造出触发 `UNIQUE (template_id, template_component_id)` 约束冲突所需的严格竞态窗口。已在 `test.md` 如实标注为"命中②非①"，未夸大结论。
3. **`./mvnw test` 的干净基线被 D-1 阻断**：TC-REG-6/8 无法给出"通过"或"确认新增失败"的结论，只能给"BLOCKED，需先修复环境问题"。
4. **AC-12 的强验证依赖主线已有测试**：`GoldenCardValuesEquivTest` 本应是 AC-12 的关键证据，但因 D-1 未能亲自复核。

---

## 7. 关键证据（curl / SQL 摘录）

### 7.1 数据完整性——测试前后全库状态对比（证明未留脏数据）

```
测试前（体检基线，主线报告）：20 PUBLISHED 模板（17 QUOTATION + 3 COSTING），181 tc = 181 tcs 行，0 unfrozen
测试后（本报告执行完毕后）：
  SELECT status, template_kind, count(*) FROM template GROUP BY status, template_kind;
    PUBLISHED | COSTING   | 3
    PUBLISHED | QUOTATION | 17
  SELECT count(*) FROM template_component;            -> 181
  SELECT count(*) FROM template_component_snapshot;   -> 181
  SELECT count(*) FROM template t WHERE t.status IN ('PUBLISHED','ARCHIVED')
    AND NOT EXISTS (SELECT 1 FROM template_component_snapshot s WHERE s.template_id=t.id); -> 0
  SELECT sort_field, element_code_field, element_price_field, element_currency_field, column_count
    FROM component WHERE code='COMP-0045';
    -> 项次 | 元素 | 元素单价 | 货币 | 11   （与测试前完全一致，AC-3 相关数据未被本轮触碰）
```
**结论：测试前后全库状态完全一致，0 条脏数据残留。**

### 7.2 罗克韦尔模板1（真实生产模板）清零→恢复的 md5 证据

```
备份时：tcs md5 = ee34c3ae3851a6dd49056104f16d68a4（6 行）；jsonb md5 = a2fcd6c326f6b8423760bb9bc6f820f6
清零后：count=0；components_snapshot IS NULL
恢复后：tcs md5 = ee34c3ae3851a6dd49056104f16d68a4  ← 完全一致
        jsonb md5 = a2fcd6c326f6b8423760bb9bc6f820f6 ← 完全一致
frozen-drift 复查：frozen:true, hasDrift:false
GET /quotations/{真实单号} 复查：200，6 tab 完整
```

### 7.3 AC-9② 关键 409 复现（真实端到端，非纯代码推理）

```
POST /api/cpq/quotations/90cba7f7-.../ensure-card-values  （模板 5c292466-... 零快照行 + jsonb NULL）
→ HTTP 409
{"code":409,"message":"模板尚未冻结：templateId=5c292466-0aad-490d-b06a-f96a7cbee0e8, status=PUBLISHED。
该模板的渲染配置冻结快照为空（过渡期正常状态），请联系管理员冻结该模板后再试。",
 "data":{"templateId":"5c292466-...","code":"TEMPLATE_NOT_FROZEN","templateStatus":"PUBLISHED"}}
```

### 7.4 A11 freeze 全链路（权限 + 零行守卫 + 恢复）

```
alice(SALES_REP)  POST /templates/{id}/freeze → 403
未登录            POST /templates/{id}/freeze → 401
bob(SALES_MANAGER) POST /templates/{id}/freeze → 200（componentsSnapshot 非空）
再次 freeze（已有快照）→ 409 "该模板已冻结，如需更新配置请走 createNewDraft → publish 发布新版本"
freeze 到 DRAFT 模板 → 400 "DRAFT 模板不支持首次冻结..."
freeze 到不存在的 id → 404 "Template not found: ..."
冻结后 ensure-card-values → 200（渲染恢复）
```

### 7.5 D-2 关键代码证据

```java
// ExcelViewService.java:571
if (t == null || t.componentsSnapshot == null || t.componentsSnapshot.isBlank())
    return java.util.Map.of();   // ← 判据是 jsonb 是否为空，不是 t.status

// :545（调用方 buildTabJoinEffectiveRows 内）
com.cpq.component.entity.Component c = com.cpq.component.entity.Component.findById(cd.componentId);
    // ← 活表兜底，未冻结的 PUBLISHED 模板会走到这里且不报错
```

### 7.6 D-1 关键报错

```
Migration checksum mismatch for migration version 382
-> Applied to database : -1813061758
-> Resolved locally    : 479310567
[cpq_db] flyway_schema_history: version=382, checksum=-1813061758, installed_on=2026-08-07 00:39:05, success=t
```

### 7.7 D-3 关键对比

```
codegraph_callers("findTab") → 仅 1 个同名但无关的私有方法（RowDataMaterializer.findTab）
/usr/bin/grep -a "\.findTab(" cpq-backend/src/main/java/ → 0 命中（PublishedTemplateReader.findTab 零调用方）

实测"部分缺行"的真实报错：
{"code":500,"message":"模板快照损坏：templateId=caea9e0f-...，template_component_snapshot 行数=2，
components_snapshot jsonb 长度=3。两者应恒相等..."}
（不含 sortOrder=1，与 api.md §10 文档示例不符）
```

### 7.8 权限矩阵完整结果（A1~A12）

见 `test.md` TC-PERM-1~4 已回填的完整表格与实测响应，此处不重复。

---

## 8. `test.md` 更新说明（回应任务要求"标注失效用例"）

已在 `test.md` 内**就地**完成以下更新（未删除原文，保留追溯价值）：

1. **AC-9 整节**：TC-09-1~4（旧 D3 存量回填模型）标记为 **~~删除线~~ + "已作废"**，注明作废原因（D16 推翻 D3，回填 SQL 已从 V382 物理删除，无法重跑）；TC-09-5 语义保留但改写验证口径；**新增 TC-09-6~12** 共 7 条，覆盖现行 D16~D20 判据，全部标注了实际执行结果
2. **AC-12 整节**：TC-12-1（旧"迁移前后"框架）标记为 **~~删除线~~ + "N/A"**；**新增 TC-12-3/TC-12-4** 覆盖现行"冻结前后"判据，TC-12-2 结果改写为符合本轮实际执行方式的表述
3. 其余 AC 分组（AC-5/6/8/13/14）、权限矩阵、并发/幂等、回归面：**逐条回填"实际结果"**，不是占位符

---

## 9. 回归结论

**总体：未发现由本任务改动引入的功能性回归**。核心不变量（发布后快照不可变、改组件不影响已发布模板、快照缺失不回落活表）均在真实数据上得到验证。发现的 6 项缺陷中：

- 1 项（D-1）是**环境/流程问题**（共享测试库 Flyway 历史与文件不同步），不是代码逻辑缺陷，但**阻塞了标准自检流程**，必须先处理再谈"回归为零"的完整结论
- 1 项（D-2）是**真实的、本任务范围内的代码缺陷**（AC-5 核心指标未达成），触发条件较窄但性质与本任务要根治的问题同源
- 1 项（D-3）是**文档-代码不一致**（死代码 + 消息格式），不影响核心行为正确性
- 1 项（D-4）是**纯文档时效性问题**
- 2 项（D-5/D-6）是**风险提示级/归因未确认**，均不构成已证实的功能回归

**不建议**在 D-1、D-2 修复前直接判定"AC 逐条达成、可以合并"——尤其 D-2 直接命中 AC-5 这条核心验收指标，需要主线决策是修代码还是接受该窄场景为已知缺口并登记 BACKLOG。

---

## 10. 给主线的清单（优先级排序）

1. **必须处理**：D-1（Flyway checksum）—— 不处理就没法拿到 TC-REG-6/8 的干净结果，也没法验证 D-6 的归因
2. **建议处理或明确接受为已知缺口**：D-2（ExcelViewService 活穿透）—— 若接受，需要在 `BACKLOG.md` 登记并说明触发条件窄的理由
3. **建议顺手修**：D-3（消息格式）、D-4（文档补齐 D16~D20）
4. **建议评估**：D-5（catch-all 吞异常风险）
5. **需要主线在解决 D-1 后做一次 A/B**：D-6（三个疑似存量失败的最终归因）
6. **合并后立即补**：AC-18 E2E 双 spec

dev-docs 索引：无变化（本次为测试执行 + 报告产出，未新建任务目录，任务状态在需求文档.md 层面由主线更新）
