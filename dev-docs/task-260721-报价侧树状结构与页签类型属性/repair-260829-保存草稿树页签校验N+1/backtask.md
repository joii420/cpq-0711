# backtask.md · repair-260829 保存草稿树页签校验 N+1

> 后端只按本文件做。契约见 `api.md`（本次无契约变更）。AC 原文在 `问题说明.md` ⑥，**本文件只标编号不复制原文**。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, AC-5, AC-6, AC-7, AC-8, AC-9 | `QuotationTreeService` 新增重载 `assertCanAddRowsToRestrictedTab(UUID componentId, String flatRowsJson, UUID lineItemId, Map<UUID, TabMeta> metaByComponent)`；新增不可变值类型 `TabMeta(tabType, partNoField, partNameField)`。**原三参方法保持不动**（`QuotationService:653` 逐行逃生路径继续用它）。新重载内部逻辑与原方法逐字一致，唯一差别是 meta 从入参取而非现查 |
| **B-2** | AC-1, AC-2, AC-3, AC-5, AC-6, AC-8, AC-9 | `QuotationService.processBatchStage1`：① 主循环**之前**用 `q.customerTemplateId` + `publishedTemplateReader.allTabsOf(templateId)` **一次**构造 `metaByComponent`（`templateId == null` 时构造空 Map，后续全部快速放行，与现状「`resolveCustomerTemplateId` 返 null 即 return」等价）；② 主循环内**只收集**待校验三元组到方法级 List，删掉循环内的 `em.flush()` 与逐条 assert 调用；③ 主循环结束后 `em.flush()` **一次**，再遍历收集到的三元组调 B-1 的新重载 |
| ~~B-3~~ | ~~AC-12~~ | 🚫 **已撤出本期**（用户裁决 2026-08-29）——与并发会话 `task-260825/repair-260829-异步物化事务上下文缺失` 撞车，对方根因更准（CDI 传播竞态）且已在实现期。**本任务不碰建单物化链路任何文件**。原内容 | `CreateQuotationMaterializer.materialize` 的 ① 用 `QuarkusTransaction.run(QuarkusTransaction.runOptions().timeout(600), () -> snapshotService.snapshotQuotation(qid))` 包起来（照搬本类 `fillStatus` 的 B-21 手法与理由）。**同时检查 ② `ensureStructure`** 是否有同样的无事务隐患 —— 若有，一并同法处理并在 `test-report.md` 说明；若无，写明「已检查、不需要」 |
| **B-6** | AC-3, AC-4, AC-8, AC-18, AC-19, AC-20, AC-21, AC-23 | `processBatchStage1` 的 `componentData` 写入改 UPSERT。① 主循环前对每个复用行比对「payload 的 componentId 集合」vs「库中现有集合」；② 集合相同 → 复用现有实体只 `UPDATE row_data / subtotal / tab_name / sort_order`，🔒 **`snapshot_rows` 与 `deleted_row_keys` 两列一律不碰**；③ 集合不同 → **回落现行全删全建**；④ UPSERT 路径下不再需要 `preservedSnapshots` / `preservedTombstones` 的读出-删除-写回。**手法用 Hibernate 实体 UPDATE**（A0 裁决），靠 `statement-batch-size=100` 合批；🚫 **本期不许改成原生批量 UPDATE 绕过 Hibernate**（`repair-260828` 正在同类坑上，不叠加风险）。⚠️ 其余三张子表行为不变，仍全删全建 |
| **B-7** | AC-22 | Flyway 迁移：① 删除 6 组重复中 `tab_name IS NULL OR tab_name=''` 的那条（同组皆非空则保留 `created_at` 较早者）；② `ADD CONSTRAINT uq_qlcd_line_component UNIQUE (line_item_id, component_id)`。⚠️ **版本号必须建分支时实取**（`SELECT max(version::int) FROM flyway_schema_history WHERE success`，立项时为 **395**）——共享库号是移动靶。⚠️ **迁移前先跑 `SELECT count(*)` 报影响面**（`CLAUDE.md` §3.2 红线：DELETE 必须先量化） |
| **B-8** | AC-24, AC-25, AC-26, AC-3 | 🆕 **开工后扩范围（用户裁决 2026-08-29）**：`buildHitContext` 的两条 per-lineItem 查询（`loadTemplateComponents` + `loadComponentDataByLineItem`）改整单预取。**只改 saveDraft 这条链路**（经 `assertCanAddToRestrictedTab:767`）；🔒 **另外三个 `buildHitContext` 调用点（`:234`/`:356`/`:461`）一行不动**。⚠️ **先判断能否复用 prep 段已加载的 `oldCdByLineAndComp`**（可复用则零新增查询），不能复用再新增一次 `WHERE line_item_id IN (...)` 批量查询。手法同 B-1：新增接受预取数据的重载，原方法保持不动 |
| **B-9** | AC-27, AC-28, AC-29, AC-24 | 🆕 **开工后第二次扩范围（用户裁决 2026-08-29）**：`processBatchStage1` 中 `li.subtotal = liDraft.subtotal` 改为**先 `compareTo` 判等再赋值**（`li.subtotal == null \|\| li.subtotal.compareTo(liDraft.subtotal) != 0` 才赋）。⚠️ **必须保留 `liDraft.subtotal != null` 这层判断**（null 语义不变，AC-29）。⚠️ **`total` 的累加逻辑不受影响**，仍用 `liDraft.subtotal` 累加，不要改。⚠️ **逐行逃生路径（`batchStage1=false`）若有同款赋值，一并同法处理**并在回报说明。🚫 **本期只修 `subtotal`**，`lineTotalAmount`/`lineUnitPrice`/`lineDiscountAmount`/`discountRateApplied` 等同族字段登记 BACKLOG，不在本次改 |
| ~~B-10~~ | ~~AC-30~~ | 🚫 **方案已推翻（A0 第二轮，2026-08-29）**：原定 `ON CONFLICT DO UPDATE`，用户在了解风险后改为**前端规避**（见 `fronttask.md` F-4）。**后端零改动**，唯一约束 `uq_qlcd_line_component` 保留作兜底。否决理由见 `问题说明.md` ⑤ 的 F-4 段（风险 1 数据倒退 / 风险 2 物化侧走原生 SQL 故 `@SQLInsert` 无效） |
| **B-11** | AC-34, AC-35, AC-36, AC-37 | 🆕 **交付后返修（用户实测撞 409 → 裁决「甲·排队」，2026-08-29）**：`QuotationResource.saveDraft` **入口**（🔒 **事务外**，在 `quotationService.saveDraft` 调用之前）轮询 `MaterializeRegistry.isInProgress(id)`，为真则每 500ms 等待直到为假；超时（默认 40000ms，可配 `cpq.savedraft-materialize-wait-timeout-ms`）抛 `BusinessException(409)` + 可理解中文文案。🔒 **`isInProgress` 为假时立即放行，零额外查询零 sleep**（AC-35）。⚠️ **绝不能挪进 `QuotationService.saveDraft`（`@Transactional`）内部** —— 那会让事务凭空多持有等待时长、吃掉 60s Narayana 预算（AC-37 专验）。⚠️ **超时 40s 的由来**：物化四步实测 29.5s ＋ saveDraft 自身约 16s ＝ 45.5s，须 < 前端 axios 60s 上限。`MaterializeRegistry` 为并发会话交付物（`5cade217`），**只读依赖、不改其代码** |
| **B-4** | AC-16 | **合并前移除全部诊断埋点**：`QuotationService` 的 `[stage1-profile]`（8 处计时 + 2 处日志）、`QuotationTreeService` 的 `[meta-profile]`（2 段计时 + 1 处日志）。⚠️ `QuotationResource` 既有的 `[draft-profile]`（S1/S2/S3，2026-06-26 引入）**是存量常驻埋点，保留不动** |
| **B-5** | AC-1~AC-11 | 单元/集成测试，见 `test.md` T-01~T-09。**必须包含 AC-17 的还原实验** |

## 硬约束（违反即打回）

1. ⚠️ **本条已于 2026-08-29 随 B-8 扩范围修订**（原文：「🚫 不许动 `assertCanAddToRestrictedTab`（真正的校验体）与 `buildHitContext`」）—— 现在**允许**改这两处的**取数方式**，但仍 🚫 **不许改「校验怎么判」的判定逻辑**（`partNosWithChildren` 的收集与比对、异常文案、`treeRowsByComp.isEmpty()` 短路条件一律不动）。**改的只能是「数据从哪来」，不能是「怎么判」。**
2. 🚫 **不许删 `loadSingleComponentTabMeta`** —— 逐行逃生路径还在用（AC-7 会验）。
3. 🚫 **不许改 `snapshot_rows` / `deleted_row_keys` 的回填逻辑**（`preservedTombstones` / `preservedSnapshots`）—— AC-8 逐位等价会验。
4. 🔒 **B-6 绝不许碰 `snapshot_rows` / `deleted_row_keys`** —— 「这两列在 UPSERT 路径下不变」正是 B-6 的目标与判据（AC-19 / AC-21 会验）。要改这两列的写入时机，先回主线。
5. ⚠️ **B-6 的结构判定宁严勿宽** —— 判不准就回落全删全建。回落只是慢，判错会写坏数据。
6. ⚠️ **B-2 的 `templateId` 口径变化是有意的**（见 `问题说明.md` ⑤ B-1 段），但**必须由 AC-6 覆盖**。若测试期发现该变化导致既有用例变红，**先报主线，不许自行改回查库**。
7. 🚫 **N+1 硬指标**（`docs/rules/backend.md`）：改完后该校验路径的 SQL 条数必须与行数无关。自检方式：`[stage1-profile]` 埋点在移除前先跑一次留证。
8. ⚠️ **单线程纪律**：`processBatchStage1` javadoc 的「严禁并行」继续有效，本次不引入任何并发（`cpq-expand-layer-not-threadsafe`）。

## 不做（本期明确排除）

- INSERT 落库 29.8 s 的优化 —— 判定实验已证非配置问题，转 BACKLOG
- `snapshotQuotation`（S2）14.1 s 的优化 —— 根因未查，转 BACKLOG
- **完整**增量保存（逐行 diff `row_data` 内部明细）—— B-6 只做**记录级** UPSERT，不比对 jsonb 内部；完整增量会撞 `AP-40`~`AP-54` 行身份重灾区，转 BACKLOG
- 前端只发变化的 payload —— 需前端脏标记，转 BACKLOG
- 原生批量 UPDATE 绕过 Hibernate —— B-6 实测不够快才考虑，本期不做
- D-1 逐行置 NULL 改批量 —— 实测仅值 47 ms，收益太小，不做
