# BACKLOG

> 项目待办池（推迟/未排期任务的单一登记处）。
> **优先级**：`P0` = 阻断或须尽快；`P1` = 重要、计划内后续期；`P2` = 增强 / 锦上添花 / 待评估。
> **格式**：每条一个 `### [BL-NNNN] 标题`，含 `优先级 / 来源 / 状态 / 登记日期 / 背景 / 范围 / 依赖 / 验收要点`。
> 完成后把条目移到文末「## 已完成」并标 `[DONE 日期]`，不要删除（保留追溯）。

---

## P1

### [BL-0001] 报价提交行键冲突的「编辑期实时预检 + 红点标记」（第二期）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-submit-rowkey-conflict-locator-design.md` §4 非目标（第一期被动定位的增强）；两轮评审均指为"潜在第二期"
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期（Plan 1b）只做"点提交失败后被动定位"。更好的体验是在**编辑期 / 提交前**就实时算出行键重复，在对应料号/页签上打红点标记 + 可点击定位，不必等提交失败。
- **范围**：前端镜像后端行键算法做实时判重（已有 `rowkey-input-dedup` 编辑期判重基础可复用）；在 Step2 料号列表 / 页签 Tab 上挂 badge（数字=冲突数）；复用第一期的 `RowKeyConflictDrawer` / locate 联动。
- **依赖**：第一期 Plan 1b 落地（后端结构化返回 + Drawer + locate 联动）。
- **验收要点**：编辑产生撞键时无需提交即出现红点；标记数与后端提交校验结果一致；不引入额外 batch-expand 风暴（守 AP-31/AP-37）。

### [BL-0005] 第二期前置：版本感知 BOM 闭包展开（让切版本真正重算子料号）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-核价管理财务核价工作台-design.md` §0/§9（第二期前置工程）；cpq-architect 评审 B-1 + 主线穿透核验
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：核价工作台"财务切料号版本→重算子料号"在当前引擎**不生效**——`BomClosureService.CLOSURE_SQL` 硬编码 `is_current=true`（`:71/:88`）、`compute()` 在 P1 显式忽略 `versionOverrides`（`:120-124`）；核价卡片 `expandTemplateDriverBaseRows` 传 `partVersion=null`（`CardSnapshotService.java:1631`）→ `ComponentDriverService.expand:402` `set(null)` 清空版本上下文。
- **范围**：让 BOM 闭包支持按 `bom_version` 逐层迭代展开（注释所言"P2 走 Java 逐层迭代"）；把 `partVersion` 透传进核价卡片 expand 链路。先做最小验证（给某料号造两个版本，确认重算后子料号集合/值真变）。
- **依赖**：无（独立后端工程，是 BL-0006 的前置）。
- **预估规模**：L（1 周以上）
- **验收要点**：切到另一 `bom_version` 后核价卡片的子料号集合与数据按该版本变化；不破坏默认 is_current 行为（现网无版本锁的单逐字节不变）。

### [BL-0006] 第二期：核价单切版本调价主体（财务调价能力）
- **优先级**：P1
- **来源**：spec §9（第二期大纲）+ 12 轮 brainstorming 核心诉求"调价是常态"
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期只做只读复核+审批，财务**不能调价**。本条补上财务切版本调价：另存核价单版本、记录变更、单据总价随之重算，**不动报价单冻结快照**。
- **范围**：新增 `costing_order_revision`（追加）+ `costing_order_line_snapshot`（逐行核价快照 + part_version_locked）；核价专用切版本端点（只重算核价侧改动卡片 + 单据总价 + 写 revision，允许 SUBMITTED+财务，不调 regenerateAllSnapshots）；并发 `SELECT FOR UPDATE costing_order`+seq 序列防竞态（评审 M-1）；line_snapshot 加 FK（M-4）；可编辑工作台外壳 `CostingReviewCardContainer`（持 lineItem state+角色门+切版本入口，内层仍纯只读 `ReadonlyProductCard` 反 AP-50，M-x1）+ 复活 `PartVersionDrawer`；单据总价口径锁定（含/不含 Step3 折扣，倾向复用 `lineDiscountService.recompute`，M-5）。
- **依赖**：**BL-0005（版本感知 BOM 闭包）必须先就绪。**
- **预估规模**：L
- **验收要点**：财务切某料号版本→该卡片子料号/值 + 单据总价按新版本重算、写入核价单新 revision；报价单原始快照不变；重提延续最新 revision（spec §6.4）；并发切版本不丢改动（连跑两次结果一致）。

### [BL-0007] 第二期：核价单覆盖读取层下沉（B-3，对外以核价单为准）
- **优先级**：P1
- **来源**：spec §9 + cpq-architect 评审 B-3
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期不切版本→核价单与报价单数据不分叉，故无需覆盖。第二期切版本后两者分叉，必须让"核价单最新 revision"在**所有读核价值/单据总价处**覆盖报价单原值，否则对外 PDF/Excel/邮件/比对/列表仍是报价原值。
- **范围**：覆盖不能只在 `loadLineItems` DTO 一处——还含导出 `ExcelViewService.exportExcelView:753`、`QuotationExportService`（totalAmount `:193/206/380/382`）、核价表 `CostingSheetService:157-159`、列表、邮件。方案二选一：切版本时回写一份供导出读取的稳定位置；或建统一"读时取核价覆盖值"服务。
- **依赖**：BL-0006（核价单 revision 已产生）。
- **预估规模**：M（3-5 天）
- **验收要点**：财务调价后，详情页核价视图/金额/对外 PDF·Excel·邮件/比对表 TOTAL/列表金额全部以核价单最新值为准；报价侧不受影响。

### [BL-0010] 降低首开 / warm 阻塞时长：核价卡片值 expand 集合化
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §12 + 两轮评审（首开 ~9~12s 阻塞主因＝核价侧逐行实时 `bomClosureService.compute` + `expandTemplateDriverBaseRows`）
- **状态**：TODO（未排期）
- **推迟原因**：超出本期范围 + 动核心基线（须 architect）。本期已用 eager warm 把该成本移出用户感知路径（首存后台 warm + 后续秒开），窄窗口首开阻塞已可接受，故 expand 提速本身可后续单独治。
- **背景**：`ensureCardValues` 第一次跑大单（罗克韦尔 170 行）阻塞 ~9~12s，几乎全在核价侧 `buildCostingCardValues` 逐行实时 BOM 闭包 + driver expand（`CardSnapshotService.java:1037/1044`，Bug-B 闸门不合桶、不能并行）。这也是 saveDraft 首存历史耗时同一热点。
- **范围**：把核价侧多行 driver expand / BOM 闭包按集合化批量（与 `savedraft-setbased` 集合化项目同根，复用其 union 合桶成果）；单线程、逐位等价（md5）、守 `cpq-expand-layer-not-threadsafe`（禁并行）。先做最小验证再推广。
- **依赖**：与 savedraft-setbased 集合化项目协同；动核心基线须走 cpq-architect。
- **预估规模**：L（1 周以上）
- **验收要点**：大单首开/warm 阻塞时长显著下降；卡片值落库逐位等价（`GoldenCardValuesEquivTest` 不变）；无并行竞态（刷新多次行数/值稳定）。

### [BL-0017] `[页签(总计)]` 真实计算口径对齐「金额字段(is_amount)小计之和」
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-30-tabtotal-subtotal-token-corruption-fix.md` §3.6 划出范围 + 两轮评审「语义事实」提示
- **状态**：✅ **已落地合并 master（2026-06-30，commit `e6c53db`）**。方案 A′ 加性哨兵键（不动裸键、不动求值器）。前端 4 写键点 + 后端 5 点（含计划外新增 `CardSnapshotService` byColNode 排除哨兵）落地；`ComponentDataEffectiveRowsTest` 6/6（含哨兵=Σamount + 折扣缩放）、前端 vitest 210/210、tsc 0 错；**值中性硬证据**：`GoldenCardValuesEquivTest#rockwell` 含/不含本改动纯读 golden 逐位等价 `52380a82…`。详见 spec `…BL0017-tabtotal-amount-sum-impl.md` §10。
- **推迟原因**：超出本期范围（本期硬约束＝「所见即所存 + 不动公式计算」，只修显示忠实性）；且涉及改动求值口径，须单独立项评估影响面。
- **登记日期**：2026-06-30
- **背景**：`[页签(总计)]` 本应＝该页签所有「金额属性」字段(`is_amount && is_subtotal`)的小计之和（用户口述权威规则，已由显示行模块 `tabTotalLines.ts#sumTabColumns` 实现）。但公式引擎的裸键 `componentSubtotals[tabName]` 现＝**所有 `is_subtotal` 列之和**（含非金额的 汇率/利润比例/单率），且因解析期 `value` 被塞首个小计列，`[页签(总计)]` 实际只求**首个小计列的列小计**（前端 `产品#汇率` / 后端 `compCode+"#"+col`），**既非「金额字段之和」也非「整页签总计」**。`tabTotalLines.ts:4-7` 注释明确承认显示行与引擎值「有意分叉」。本期 [BL-本次] 只让它「显示对、保存忠实」，数字仍沿用现状（首个小计列）。
- **范围**：让 `[页签(总计)]` 求值＝`Σ(is_amount && is_subtotal 列的列小计)`，需三处对齐 —— 前端卡片裸键（`QuotationStep2.tsx#getComponentSubtotals` 裸键改 amount 过滤，复用 `sumTabColumns` 口径）+ 后端 Excel 路径（`ComponentDataEffectiveRows`：**当前根本没登记裸键**，需新增裸键＝amount 列之和，否则 fix 后该路径 `[页签(总计)]` 求值为 0）+ 配置快照（`ConfigureSnapshotService#accumulateColumnSubtotals`：同样只登记 `#列` 键、需补裸键）。同步评估其它读裸键的路径（产品小计兜底 `evalProductSubtotalFromSubtotals` / 按页签折扣）是否要跟随改口径，或给 `[页签(总计)]` 单独引一个「金额合计」键以缩小影响面（A 全局重定义 vs B 单独键，二选一）。
- **依赖**：本次「所见即所存」修复（[BL-本次] 引入的 `is_tab_total` 标记）先落地；A/B 口径方案须 architect 评审（动核心求值基线，守 `docs/三大核心模块基线.md`）。
- **预估规模**：M（3-5 天）
- **验收要点**：`[页签(总计)]` 在报价单卡片 / Excel 视图 / 配置快照三处求值一致＝该页签金额字段小计之和；与页签底部「本页签金额合计」显示行同值（口径统一）；非金额小计列不再计入；其它读裸键路径行为符合所选 A/B 方案预期。

### [BL-0018] 存量已塌缩 `[页签(总计)]` 公式的批量恢复
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-tabtotal-subtotal-token-corruption-fix.md` §3.6（本期不处理存量）
- **状态**：BLOCKED（需人工逐条确认意图，不可盲目脚本迁移）
- **推迟原因**：修复前「点页签总计」与「点小计列」存出的 token 字节级同形（均 `value=列名`、无 `is_tab_total`），脚本**无法安全区分**存量某条 `value=列名` 原意是「整页签总计」还是「用户确实选了该列」；盲目迁移会把合法列引用误改成总计。
- **登记日期**：2026-06-30
- **背景**：本期机制修复后，**新保存**的 `[页签(总计)]` 才带 `is_tab_total` 标记、显示忠实；修复前已存的旧公式无标记，重开仍显示为 `[页签.列]`，需用户重开该公式重存一次才恢复。
- **范围**：若确有批量恢复需求 —— 先排查存量（grep 快照/组件 JSONB 里 `component_subtotal` 且 `value ∈ subtotalCols` 的 token），再**人工逐条确认**意图后打标，不做无人值守迁移；或提供「一键重存当前公式」的辅助入口让用户自助修。
- **依赖**：[BL-本次] 机制修复落地（标记字段已存在）。
- **预估规模**：S（1-2 天，主要成本在人工确认）
- **验收要点**：被确认为「总计」意图的存量公式补上 `is_tab_total` 后显示恢复 `[页签(总计)]`；列引用意图的公式不被误改；求值不变。

### [BL-0022] 核价汇总视图 `v_costing_summary_full` 漏接已算的模具费/设计费 → 总成本系统性偏低
- **优先级**：P1
- **来源**：2026-07-01 核价单「已具备功能」深度复核（亲验 `V80` SQL）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-01
- **背景**：`CostingSummaryService.compute()` 已算出并入库 7 个 metric（含 `TOOLING_FEE`/`DESIGN_COST`），但 `V80__costing_summary_view_and_excel.sql:31-32,51-58` 里 `v_costing_summary_full` **只 PIVOT 了 `MATERIAL_COST`+`PROCESS_FEE` 两列，且根本没有模具费/设计费的承接列**——`TOOLING_FEE`/`DESIGN_COST` 被静默丢弃，其余 6 列（损耗/管理/财务/利润/税费/电镀/其他）恒 NULL。走该汇总视图的核价 Excel「汇总」页总成本 `=[L]+[M]+…+[T]` 实际只剩材料+加工，**总成本系统性偏低**。注意这**超出**「商务加价 6 项未实现」的已知限制（[[BL-无]] 总结 §八#1）——模具/设计是**已实现却漏接视图列**。
- **范围**：给 `v_costing_summary_full` 补 `tooling_cost`/`design_cost` 两个 PIVOT 列（`MAX(CASE WHEN metric_code='TOOLING_FEE'…)` / `'DESIGN_COST'`），并把默认核价 Excel 模板「汇总」页总成本列公式纳入这两列；确认与 `compute()` 的 `UNIT_TOTAL_COST` 口径一致（避免重复/漏加）。
- **依赖**：无（独立视图 + 模板列改动）；DDL 后须重启 Quarkus（守 CLAUDE.md「视图重建后重启」）。
- **预估规模**：S（1-2 天）
- **验收要点**：汇总视图总成本 = 材料+加工+模具+设计（+未来商务加价），与 `costing_summary_result` 各 metric 之和逐位一致；未实现的商务加价列仍 NULL 显示「—」。

### [BL-0023] 🚨 发版红线：V306 price_type 按 Sheet 细分「只改写入端、取价视图未重写」→ 重导即断链
- **优先级**：P1（发版安全红线）
- **来源**：2026-07-01 核价单深度复核（亲验实库 `unit_price` 分布 + `component_sql_view.sql_template` 谓词）
- **状态**：TODO（未排期）— **落地前禁止跑新一轮 PRICING 核价基础资料导入**
- **登记日期**：2026-07-01
- **背景**：V306 把核价 `unit_price.price_type` 写入端细分为 7 个新值（`INCOMING_PROCESS`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`MATERIAL_PRICE`/`PACKAGING`，10 个 P* handler + CHECK 已就绪），但**取价视图 sql_template 未同步重写**。实库查证：① 新细分值在 `unit_price` 表 **0 行**（V306 handler 尚未跑过导入）；② 现网核价取价视图仍按**旧值**过滤——`gx_view` 滤 `price_type='MATERIAL'`、`ll_view` 滤 `INCOMING_MATERIAL_PROCESS`、`qt_view` 滤 `FINISHED_MATERIAL_OTHER`、`wgj_view` 滤 `COMPONENT_OTHER`、`dd_view` 滤 `PLATING`。因数据与视图当前**都还是旧值**，**系统现在正常**；spec `V306:18` 已自认「视图 sql_template 重写另行处理（推迟）」。
- **⚠️ 触发条件（务必周知）**：**一旦用 V306 之后的 handler 重新导入核价基础资料** → 新行带细分 price_type → 上述视图旧谓词匹配不到 → 来料加工费/自制加工费/电镀/外加工/材料价等**取价返 NULL**；且因 `uq_unit_price` 唯一键含 `price_type` + V306 不回填存量，旧 `MATERIAL` 行与新行**并存**，读取端继续读旧 stale 行（**静默错价、导入看似成功、无报错**）。
- **范围**：重写核价取价视图（`component_sql_view.sql_template`，config 驱动存 DB）的 `price_type` 谓词以匹配新细分值（或做值映射兼容层）；同步核对 `V255` 种子与线上 `component_sql_view` 表已漂移的实际谓词；给存量 `MATERIAL` 行制定回填/迁移策略。**在此之前，运维/开发一律不得对核价侧跑 PRICING 重导。**
- **依赖**：无（后端视图 + 数据迁移）。
- **预估规模**：M（3-5 天）
- **验收要点**：用 V306 handler 重导后，各费用视图仍能按新细分 price_type 取到价；无 NULL 断链、无新旧行并存读旧值；`V255` 种子与线上 sql_template 对齐。

### [BL-0024] 独立核价单 override（what-if 差量）EXCHANGE fieldName 错配静默失效 + discount_rate 死选项
- **优先级**：P1
- **来源**：2026-07-01 核价单深度复核（亲验 `CostingSummaryDetailPage.tsx` 前端 + `CostingSummaryService.java` compute）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-01
- **背景**：独立核价单模块（配置中心→核价单，`CostingSummaryDetailPage`）的差量抽屉里，`fieldName` 下拉给 EXCHANGE 也列出 `costing_price`（标签误写「核价单价 / **核价汇率**」，`:302`），但 compute 对 EXCHANGE 只用 key 后缀 `costing_rate`（`CostingSummaryService.java:367,381`）。用户给汇率建差量若选被诱导的 `costing_price` → key `EXCHANGE:CNY/USD:costing_price` → Map miss → **差量静默忽略、无报错、状态照常 COMPUTED**，用户以为已生效。另 `discount_rate` 选项（`:304`）compute **全程无任何命中** → 死选项。
- **范围**：前端按 `targetKind` 联动 `fieldName` 可选集（ELEMENT/MATERIAL→`costing_price`；EXCHANGE→`costing_rate`），去掉误导标签与不可消费的 `discount_rate`；或后端为 EXCHANGE 同时接受 `costing_price` 别名。补一条保存后校验（key 无对应求值通道时给 warning）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：EXCHANGE 差量只能选 `costing_rate` 且生效；不再出现选了不生效的静默失效；`discount_rate` 死选项移除或接通。

### [BL-0027] 核价树导入后首屏走「实时兜底」需手动刷新才出树（快照值加载时序）
- **优先级**：P1
- **来源**：2026-07-02 核价树配置排查（Playwright 实测 + 前端 `QuotationStep2` 链路核实）
- **状态**：TODO（未排期，动核价单渲染基线 → 须 architect）
- **登记日期**：2026-07-02
- **背景**：导入产品时前端在内存拼 `LineItem`（**不带 `costingCardValues`**），快照在 `saveDraft`/后端同步算好写库，但**前端不重新拉取** → `useSnapCosting = costingLineItems.every(li => !!li.costingCardValues)` 为假 → 走实时兜底（`QuotationStep2.tsx:3193` `useDriverExpansions(...)`）→ 该路径**未注入闭包 partSet、仅展根料号层**（`:3187` 注释原文）→ 核价递归组件（如子配件）**首屏渲染成平表、无「料号/父料号/版本」三系统列**。用户手动刷新触发 `getById` 拿到快照值 → `useSnapCosting` 转真 → 出树。**数据/配置均正确**（快照库里已含 spine），纯前端时序体验瑕疵。
- **范围**（推荐组合 A+B）：
  - **A（必做·前端）**：`saveDraft`（快照落库）成功后**自动 re-fetch `getById`**，把 `costingCardValues`/`quoteCardValues` 灌回内存 `lineItems` → `useSnapCosting` 自动转真、无需手动刷新。重拉须在快照落库**之后**；转真后**断开** batch-expand（`useSnapCosting ? EMPTY_LINEITEMS : ...`）避免两链同跑（守 AP-31）。
  - **B（兜底·前端）**：`useSnapCosting=false` 但核价模板含递归组件时，不静默显示错误平表，而显示「核价树快照生成中…」占位 + 自动重试 1–2 次 `getById`。
  - **C（可选·后端）**：`saveDraft`/导入响应直接带回 `costingCardValues`，免二次往返（代价：响应体增大，需评估）。
- **依赖**：无（前端为主；C 动后端响应）。
- **预估规模**：M（3-5 天，含 architect 评估 + E2E）
- **验收要点**：导入产品后**不手动刷新** → 核价单·递归页签（子配件等）**直接出树 + 三系统列**；`'加载中' final count = 0`；无 batch-expand 风暴（守 AP-31）。
- **注**：改 `QuotationStep2` / `saveDraft` 回调 / getById 时序 = 协议级 + 触碰「核价单渲染」基线（`docs/三大核心模块基线.md`），须走 cpq-architect + E2E（CLAUDE.md 强制）。

### [BL-0028] 🔧 spineKeys 叶子节点空 = `SqlViewExecutor` 数组绑定 `String.valueOf(null)→"null"`（已修待正式落地）
- **优先级**：P1
- **来源**：2026-07-02 核价树配置排查（实测 spineKeys 叶子空 → 逐层定位到绑定层）
- **状态**：**已修 + 已实测通过（应用于主工作区未提交）**；待正式落地（独立分支 + E2E + 提交）
- **登记日期**：2026-07-02
- **背景**：核价 BOM 树边源视图用 `:spineKeys(子件, 父件, 子件自身版本)` 过滤时,**叶子节点**(自身无下级 BOM → 版本=NULL)一直被误滤空,即使第 3 参写对(LATERAL 子件自身版本子查询)。根因:`SqlViewExecutor` 绑 `List→text[]` 时 `list.stream().map(String::valueOf)`,`String.valueOf((Object)null)` 返回**字符串 `"null"`** 而非 SQL NULL → `__skV` 里叶子的 NULL 版本变 `"null"` → `(子件版本) IS NOT DISTINCT FROM k.v` 里 真 NULL vs `"null"` = false → 叶子被滤。违背 `2026-06-06-spinekeys` 设计 §4.2「叶子 NULL-safe 命中」。
- **修法(已应用)**:两处绑定(`executeAllRows` / `executeJdbc`)`map(x -> x==null?null:String.valueOf(x))` 保留 null → `createArrayOf` 得 SQL NULL → NULL-safe 匹配生效。
- **实测**:1922/4141111115 子配件 spineKeys 版 27→19 行(消重)+ 叶子全填 + 仅根 1 空;Playwright 三系统列=true。
- **依赖**:无。
- **预估规模**：S（1-2 天,主要在 E2E + 评审）
- **验收要点**：spineKeys 视图叶子(版本=NULL)正确命中;不破坏非叶子(有版本)匹配;`ys_view` 等其它 spineKeys 视图无回归。
- **注**：**协议级**(动 spineKeys 绑定,影响所有 spineKeys 视图)→ 正式落地须独立分支 + `quotation-flow.spec.ts` E2E + 提交。当前改动在主工作区 `SqlViewExecutor.java`,未提交。

### [BL-0031] 选配「工序」落 V6 承载表 + mirror 视图（选配模板方案前置·architect 级）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §2/§9 + 架构复核（`docs/反模式.md` AP-53 续6）
- **状态**：✅ **已由 task-0712 实质解决（2026-07-15，master `d02b7fe`）**——选配工序落 `unit_price`（`price_type=PROCESS`/`cost_type=自制加工费`，B2 完整落库）+ 组合工艺落 `capacity`（组装加工费，B6）；标识统一到 `process_master.process_no`（缺口1 加法式方案A，V336）；渲染走物理视图 `v_composite_child_processes`（读 `unit_price.operation_no`）。按 `material_no`(=销售料号 V315) 维度落库，与 BL-0031 目标一致。**未新建"专用工序承载表"**（用 unit_price 承载，符合《报价系统Excel导入落库方案》§10），如需独立表另评估，否则可关闭。
- **登记日期**：2026-07-07
- **推迟原因**：AP-53 续6 已标"工序在 V6 侧无承载表、需新建业务表 + mirror UNION，走 architect"；若一期强做则范围过大。
- **背景**：选配方案把"工序"列为一期固定参数，但 V6 侧尚无工序落库承载表（现役选配 Phase1 只落料号+元素+子件）。产品卡片工序 Tab 依赖按 `sales_part_no` 落库 + mirror 视图取数。
- **范围**：设计工序 V6 承载表 + mirror UNION 视图，供选配/核价按 `sales_part_no` 取工序；对齐「销售料号维度落库 V6.2」口径。
- **依赖**：material_master/element/bom V6 Phase1（已就绪）；报价料号统一 Spec1。
- **预估规模**：L（1 周以上）
- **验收要点**：选配产出报价料号的工序能按 `sales_part_no` 落库并在卡片工序 Tab 渲染；不破坏现役核价工序取数。

---

### [BL-0045] 已导入工序码与 process_master 编码体系不相交 → 名称带出为 null
- **优先级**：P1
- **来源**：task-0712 主数据维护-核价基础数据维护 验收发现（C8 名称带出）
- **状态**：**[x] 已完成（DONE 2026-07-13，合并 master `efa5224`；childtask-1）**
- **登记日期**：2026-07-12
- **方案（终）**：经 spec 评审逐条澄清（5 问），从原 spec 方案 A（导入 upsert 主表、仅工序）**重构为方案 B + (ii)**：主数据先行、**核价导入不写主表**；补齐**四码**名称（工序/元素/材质/料号）——① 工序建**批量导入**（对齐材质库，upsert `process_master` `ON CONFLICT DO UPDATE`）；② 元素靠材质库导入已落 `element` 主表，不新建；③ 材质走 `material_part_no → material_master.material_recipe_id → material_recipe.name` 两跳 join；④ 料号已被 P05/P06/P24 导入 upsert `material_master`，仅核对。**无 Flyway**（`uq_process_master_no` 已存在 V218:142）。
- **交付**：后端 `efa52245`（ProcessMasterImportService/DTO/Resource +import/+template、ColumnDef MASTER_2HOP、PricingSheetRegistry+PricingMaintenanceService 两跳 join）+ 前端 `33d628e`（ProcessMasterImportDrawer、v6MasterDataService、V6ProcessCrudTab 入口、EditableSheetTable 灰字兜底）。技术总监亲验：守 B(无 P-handler/无 Flyway)、前后端信封对齐、独立复跑 tsc 0 错 + 后端 10+1+16 测试全绿、合并后 8081 活体 401/5174 200。
- **文档**：`dev-docs/task-0712-主数据维护-核价基础数据维护/childtask-1/{需求说明,backtask,fronttask,api}.md`；原 spec 已标"方案 A 历史留档"。
- **落地后待业务动作（方案 B 主数据先行的必然，非缺陷）**：① 工序名须业务拿真工序 Excel 走新导入端点才落 `process_master`（现 0 个 Z 码）；② 材质名须走「材质管理→绑定料号」补绑（现 `material_master` 绑定率 0/39，全显"未绑定"，PRD §5 非目标）。
- **遗留（P2）**：导入未做 `process_no`(VARCHAR20)/`process_name`(VARCHAR50) 超长截断校验，超限 DB 层报错；backtask/api 未要求，待评估。

### [BL-0064] 外购件（`characteristic='OUTSOURCED'`）的渲染归属 —— 三态统一的展示侧兑现
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md` §9.1（本期明确不做下游）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-20
- **背景**：本期把 `material_bom_item.characteristic` 统一为 `RECIPE`/`ASSEMBLY`/`OUTSOURCED` 三态，业务在报价侧「组成件BOM」sheet 的新增列「组成类型」里区分零件 / 外购件。但**下游视图本期零改动**，导致数据层区分了、展示层没兑现：
  - `zpj_view`（子配件）子表谓词 `characteristic = 'ASSEMBLY'` → 外购件**不显示**；
  - `ll_view`（来料）子表 join **不过滤 characteristic**，只按 `material_no` 关联，叠加本期主表推导改动（含 OUTSOURCED 子行 → 主表判 `ASSEMBLY`）→ 外购件**会混在「来料」页签里显示，但与零件视觉上无法区分**（`ll_view` 不输出 `characteristic` 列，其「类型」列取的是 `component_usage_type` 映射：银点类/非银点类/组成件，与三态无关）。
  - 净效果：业务填了"外购件"，在报价单上看不出区别。**本期已确认接受此取舍。**
- **范围**（三选一，二期定）：① `ll_view` 增加一列输出 `characteristic` 的中文映射（改动最小，只动视图 SQL + 组件字段配置）；② `zpj_view` 放宽为 `IN ('ASSEMBLY','OUTSOURCED')`，外购件并入子配件页签；③ 新开独立视图 + 组件 + 模板绑定，外购件单独成页签。
- **依赖**：本期（三态统一 + V344 迁移）交付。
- **预估规模**：S（方案①②）/ M（方案③）
- **验收要点**：报价单上外购件行可与零件行明确区分；不产生重复行（守 AP-22「X (共N项)」族）；改动视图后按 CLAUDE.md「视图 DROP CASCADE / 重建后必须重启 Quarkus」执行。

## P2

### [BL-0019] 零金额列页签 `[页签(总计)]`=0 的配置期 lint 警告 + 回退裁决
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-BL0017-tabtotal-amount-sum-impl.md` §7 风险 5 / §9 范围外；实现 spec 评审「零金额列语义」
- **状态**：TODO（未排期，需 PM 裁决）
- **登记日期**：2026-06-30
- **背景**：BL-0017 落地后，有 is_subtotal 列但**零 is_amount 列**的页签，`[页签(总计)]` 哨兵键 = 0（Σ 空集）。符合「金额字段之和」规则，但可能违反用户对「总计」的直觉（看着有小计列却显示 0）。BL-0017 本期按规则取 0、不回退。
- **范围**：① 组件管理配置期：当页签有 is_subtotal 列但无 is_amount 列、且被 `[页签(总计)]` 引用时，给 lint 警告提示「该页签无金额字段，总计将为 0」；② 由 PM 裁决是否提供「无金额列时回退 Σis_subtotal」的可选口径。**本条仅在 PM 给出回退需求后才动求值口径**。
- **依赖**：BL-0017 落地（哨兵键 Σamount 口径已生效）。
- **预估规模**：S（1-2 天，lint 部分）
- **验收要点**：零金额列页签被 `[页签(总计)]` 引用时配置期有明确警告；若 PM 要回退，回退仅作用于该场景、不影响正常金额页签。

### [BL-0057]（技术债）task-0712 选配工序 `quotation_line_process` 收缩迁移：删 `process_id` 列 + 换主 FK 到 `process_master`
- **优先级**：P2
- **来源**：task-0712 缺口1 工序 id 契约修复（架构评审.md「工序 id 契约修复设计」方案 A）；实现取**加法式变体**（迁移 V336）
- **状态**：TODO（延后，待所有选配相关并发会话/分支收束）
- **登记日期**：2026-07-15
- **推迟原因**：V336 用加法式（加 `process_no` + FK→`process_master` + 放开 `process_id` NOT NULL，**保留** `process_id` 列/旧 FK），因 `process_id` 列被共享 8081(master 实体映射) 及其它并发 worktree 会话引用，`DROP COLUMN` 会致其 Hibernate 映射失效崩溃。功能已完整（选配写 `process_no`、`process_id` 留 NULL），收缩纯属清理。
- **前置条件**：所有引用 `quotation_line_process.process_id` 的并发分支合并/收束；确认无进程再依赖旧列。
- **范围**：新迁移 `DROP COLUMN process_id` + 删旧 `quotation_line_process_process_id_fkey`；`QuotationLineProcess` 实体删 `processId` 字段。
- **预估规模**：S（1-2 天，含并发协调）
- **验收要点**：删列后选配/编辑/saveDraft 工序落库读取全走 `process_no` 无回归；无进程因缺 `process_id` 列崩溃。

### [BL-0020]（技术债）config 路径 `[页签.列]` 经 FormulaCalculationService 只读裸 code 的粗化
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-BL0017-tabtotal-amount-sum-impl.md` §9；实现 spec 评审 #7（pre-existing，非 BL-0017 引入）
- **状态**：TODO（未排期，先确认实际触达面）
- **登记日期**：2026-06-30
- **背景**：`FormulaCalculationService.java:187-192` 的 component_subtotal 分支**只读裸 `component_code`、从不读列键** → 经此引擎求值的 `[页签.列]` 列引用会被粗化成整组件裸键值，而非该列的列小计。这是既有缺陷（与 BL-0017 无关，只是评审顺带发现）。需先确认配置快照产品小计实际走的是 `FormulaCalculator`（有列键，正确）还是 `FormulaCalculationService`（粗化）——若仅后者在边缘路径触达，影响面小。
- **范围**：先排查 `FormulaCalculationService` 的真实调用面（哪些场景的 `[页签.列]` 经此引擎）；若确有错算，让其 component_subtotal 分支对齐 `FormulaCalculator` 的「列键优先、裸键回退」逻辑。
- **依赖**：无（独立技术债）。
- **预估规模**：S（1-2 天）
- **验收要点**：经 FormulaCalculationService 的 `[页签.列]` 求值 = 该列列小计（非整组件裸键）；不回归现有正确路径。

### [BL-0021]（测试债）`GoldenCardValuesEquivTest#rockwell` golden 常量过期 + `RefreshCardSnapshotTest:206` 预存失败
- **优先级**：P2
- **来源**：BL-0017 落地自检时甄别（2026-06-30）
- **状态**：TODO（需 golden owner 重新校准；非 BL-0017 引入）
- **登记日期**：2026-06-30
- **背景**：`GoldenCardValuesEquivTest#rockwell_determinism_and_capture` 的 golden 常量 `GOLDEN_ROCKWELL=3837c2bd…`（2026-06-25 捕获）已过期 —— 在**干净 HEAD（移除 BL-0017）上同样漂移到 `52380a82…`**，系 2026-06-25 后 master 合入的 `2440ab3`（首存集合化落库）/ `9dd6cbc`（失败行落非 NULL 哨兵）/ `6928090`（懒算 Excel）等提交及 LIVE DB 数据变动所致。BL-0017 经背靠背纯读对比证明**值中性**（含/不含 BL-0017 均 `52380a82…`，逐位等价）。另 `RefreshCardSnapshotTest:206`（幽灵 editRow 丢弃断言）在干净 HEAD 同样 FAIL，走 editRow/baseRows 路径，与 BL-0017 无关。
- **范围**：(1) 由 golden owner 确认 `52380a82…` 为当前正确基线后回填 `GOLDEN_ROCKWELL`（或改用对数据漂移不敏感的锚单/夹具）；(2) 排查 `RefreshCardSnapshotTest:206` 是 driver 种子数据漂移致 baseRows 不再含幽灵 rowKey，还是 refresh 重 expand 链路真回归。
- **依赖**：无（独立测试债；不阻断 BL-0017）。
- **预估规模**：S（1-2 天）
- **验收要点**：两测试在当前 master 复绿（golden 重校准 + refresh 根因厘清）；保留确定性护栏语义。

### [BL-0002] 冲突定位下钻到「具体冲突行」高亮
- **优先级**：P2
- **来源**：spec §4 非目标（第一期明确降级，仅定位到料号+页签级）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期定位到"料号卡片 + 页签"即停，不滚动/高亮到具体重复行。后端其实已返回 `rowIndices`，可进一步把卡片内对应行做视觉高亮。
- **范围**：把 `rowIndices`（driver 展开行++手动行的合并序）映射到卡片内**可见行序**（需处理墓碑删除行 / 树形布局错位），对冲突行做短暂高亮/滚动定位。
- **依赖**：第一期 locate 联动；行序映射需对齐 `CardEffectiveRows` / `useCardSnapshots` 的可见行口径。
- **验收要点**：高亮行与后端 rowIndices 语义一致；含墓碑行/组合产品场景不错位（守 AP-51/AP-54）。

### [BL-0003] 核价单提交的同类冲突结构化定位
- **优先级**：P2
- **来源**：spec §4 非目标（第一期只覆盖报价单提交）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期只处理报价单 submit 的行键冲突友好定位。核价单（COSTING 视图）若有同类提交校验，应复用同一套 Drawer + locate 机制做功能对等。
- **范围**：核价单提交链路的行键/数据冲突结构化返回 + Drawer + 定位到核价卡片页签（mainTab='costing'）。
- **依赖**：第一期 Plan 1b；需先确认核价单是否存在等价的提交期硬校验。
- **验收要点**：核价单视图下冲突可定位；与报价单视图共用组件不串味（守 AP-41 prop-drilling 对齐）。

### [BL-0004]（待评估）提交侧行键消歧与渲染侧 `#序号` 对齐
- **优先级**：P2（需求决策待定）
- **来源**：spec §4 非目标 + 历史 [[cpq-rowkey-uniqueness-disambiguation]]（渲染侧已 `#序号` 消歧，提交侧 `RowKeyUniquenessService` 未消歧的设计不对称）
- **状态**：BLOCKED（待产品/架构确认设计意图，不可直接开发）
- **登记日期**：2026-06-29
- **背景**：渲染/编辑绑定侧对撞键自动加 `#0/#1` 消歧，但提交侧仍按原始键硬拦截 → "页面看着正常、唯独提交报错"。是否让提交侧也消歧，会与 Plan 1「组合行键不可重复」的硬约束直接冲突。
- **范围**：仅在产品确认"撞键应自动消歧放行 vs 必须人工去重"后才定方案；**本期不动校验语义**（spec §4 已明确排除）。
- **依赖**：需 `docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` §7/设计 E 的需求复核。
- **验收要点**：先有书面需求结论再开 spec，不在 Plan 1b 内附带修改。

### [BL-0008] 撤回扩到 SENT/ACCEPTED + 客户累计金额回退
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-核价管理财务核价工作台-design.md` §7/§12（第一期撤回明确排除 SENT/ACCEPTED）+ 评审 M-2
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期一步撤回只覆盖 `SUBMITTED/COSTING_REJECTED/APPROVED`；`SENT/ACCEPTED` 涉及已对客户发出 + `accept()` 已累加 `customer.accumulated_amount`（无回退路径），风险高故第一期排除。
- **范围**：撤回扩到 SENT/ACCEPTED；从 ACCEPTED 撤回须回退 `customer.accumulated_amount`（`QuotationService.accept():1623-1627` 的逆操作）；解冻须兼顾已发送态。
- **依赖**：无（独立增强）。
- **预估规模**：M（3-5 天）
- **验收要点**：从 ACCEPTED 撤回后客户累计金额正确回退、无重复扣减；SENT 撤回不留发送残留；其余撤回行为不回归。

### [BL-0009]（技术债）`QuotationWithdrawRequest` 残留实体清理
- **优先级**：P2
- **来源**：第一期 T5 收尾（废弃两步撤回时保留实体供 `delete()` 清理）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期废弃两步撤回 `QuotationWithdrawService`/`Resource`，但 `QuotationWithdrawRequest` 实体+DTO 保留，因 `QuotationService.delete()` 仍调 `QuotationWithdrawRequest.delete()` 清理关联（无 DB CASCADE）。该实体现已无任何写入方，属残留技术债。
- **范围**：评估彻底移除 `QuotationWithdrawRequest` 实体/表/`delete()` 引用（或给 FK 加 DB CASCADE 后删）；确认无历史数据/外部依赖。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：移除后 `delete()` 仍正常、无悬挂引用、无历史数据丢失风险。

### [BL-0011] 窄窗口 warming-in-progress 前端轮询进度条
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §3.2/§3.3/§12 + v2 评审 B-3
- **状态**：TODO（未排期）
- **推迟原因**：增强体验。本期 try-lock 返回 warming-in-progress + 简单 spinner + 第一次打开延迟基准已满足正确性与可用性。
- **背景**：窄窗口（刚导入立刻打开同一大单、warm 在飞）首开会等 ~10s。本期显示简单 spinner；更佳体验是轮询 warm 进度（已算行数 / 总行数）显示进度条。
- **范围**：ensure 端点暴露 warm 进度（落库行数 / 总行数）；前端轮询渲染进度条 + 完成自动切快照。
- **依赖**：本期 try-lock 单飞 + ensure 端点已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：窄窗口首开显示真实进度（非裸 spinner）；进度到 100% 自动读快照、不发 batch。

### [BL-0012] 失败哨兵行「重算此行」卡内交互入口
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §3.5/§4.4/§12 + v2 评审 C-2
- **状态**：TODO（未排期）
- **推迟原因**：增强。本期失败行已显式「数据待重算」占位 + warn，并可用既有 admin `POST /components/{id}/refresh-template-snapshots` 重算，故卡内按钮非必需。
- **背景**：卡片值 build 确定性失败的行落 `__cardValueFailed` 哨兵 + 占位。更顺手的是占位上直接给「重算此行」按钮，单行触发重算并回灌。
- **范围**：占位组件加「重算此行」入口 → 调单行重算端点 → 回灌该 line 卡片值 → 切快照；与既有 refresh 端点对齐。含核价侧 sentinel 的重算（现有 `refreshCardSnapshot`/`refreshDraftQuoteCards` 仅刷报价侧，核价侧占位本期不带可用重算入口——只显式静态提示，需按侧/按行重算端点）。
- **依赖**：本期哨兵 + 占位渲染已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：点「重算此行」后该行卡片值正确补回、占位消失、不发整侧 batch 风暴。

### [BL-0013] saveDraft 回 `hasMissingCardValues` 提示以彻底去抖 eager warm
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §4.1/§12 + v2 评审 E-中
- **状态**：TODO（未排期）
- **推迟原因**：性能增强，非正确性。本期靠「仅导入完成 / 显式手动保存 + 客户端防抖」已避免高频空发。
- **背景**：§3.4 删了 saveDraft 响应里的 `newLines` → 前端无法廉价判断是否真有缺值行。若 saveDraft 回一个 `hasMissingCardValues` 布尔，前端可仅在确有缺值时才 fire warm，彻底消除冗余 ensure（即便幂等返 0 也省一次取锁+查询）。
- **范围**：saveDraft 响应增 `hasMissingCardValues`（一次 `EXISTS(... IS NULL ...)` 廉价查）；前端据此条件触发 warm。
- **依赖**：本期 ensure + warm 触发已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：无缺值时不发 warm；有缺值时发且只发一次；不回归打开秒开。

### [BL-0014] 报价单列表页批量提交审批的行键冲突明细可读化
- **优先级**：P2
- **来源**：2026-06-29 行键冲突友好定位（Plan 1b）排查发现的第三个提交入口
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：报价提交审批共 3 个入口——向导 `QuotationWizard` + 详情页 `QuotationDetail` **均已**接结构化冲突 Drawer + 定位；列表页 `QuotationList.tsx:188` 批量提交走 `runBatch` 多选场景，撞键单失败仍只在聚合 `message` 里列纯文本，未结构化。
- **范围**：批量场景不适合弹单个 Drawer（多单各自冲突）；改进方向 = `runBatch` 失败明细按单分组、把每单的行键冲突结构化展示（料号/页签/行键），可选一个汇总抽屉列出「哪些单、哪些行键冲突」。
- **依赖**：复用 `RowKeyConflictDTO` + `RowKeyConflictDrawer`（或新建汇总组件）。
- **验收要点**：批量提交撞键时，失败明细能读到具体料号+页签+行键，不再是纯文本拼串。

### [BL-0015] 核价单彻底冻结残留 live 侧信道（防 V6/主数据 republish 漂移）
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-核价单表与报价单核价单状态机重构-design.md` v3 §0/§5.3/§11（务实版接受残留）+ 第二轮 cpq-architect 聚焦评审 N3（焦点一：§5.2"唯一 live 缺口"不成立，实测 4 条 live 侧信道）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期"务实版真冻结"只冻结构（`frozen_dto` 含 enrich 后 componentData + gvDefs）+ 依赖既有 costing 卡片/Excel 零计算快照冻值。残留 3 条 live 侧信道——`usePathFormulaCache` 仍 live 求值**未被卡片快照覆盖的**少数 path 单元、`useConfigTemplates`（LIST_FORMULA 配置模板）、比对视图 `comparisonTags`——仅在「模板 / 配置模板 / 全局变量 / 对比标签 / V6 底层主数据 **republish**」时漂移，**不被报价单重做触发**（验收#5 照过）。用户从未提出该边角，第一期接受之以消除 N1（两侧 path-cache 捕获互覆盖）/ N2（非 wizard 提交入口不带 cache）两个脆弱点。
- **范围**：若未来确有审计强需求（历史核价单连 V6/主数据漂移也要 1:1 回看），补：①提交时捕获并冻结 path-cache（**取 quote+costing 两侧 `usePathFormulaCache` 返回值的并集**，避第二轮 N1）②冻结 config-template 值③冻结 comparisonTags 元数据；工作台冻结模式短路对应 live 调用。
- **依赖**：无（独立增强，建立在第一期 `frozen_dto` 之上）。
- **预估规模**：M（3-5 天）
- **验收要点**：模板/GV/V6 republish 后打开历史核价单，path 单元/LIST_FORMULA/比对分组仍是提交时值；工作台冻结模式 `batch-evaluate` 请求 0 次。

### [BL-0016] 切料号版本后失效卡片值（lazy 模型 staleness gap）
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` 实现期 Task 4 代码评审 Important（范围外观察）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **推迟原因**：超出本期（saveDraft 重建）范围；且**当前潜在**——按 [[BL-0005]] 切版本引擎尚未生效（`BomClosureService` 硬编码 `is_current`、核价 expand 传 `partVersion=null`），故切版本暂不改变卡片值相关数据，staleness 暂不显现。第二期版本切换真生效后必须补。
- **背景**：`QuotationService.updateLineItemPartVersion`（`:2632`，`li.persist()` `:2667` + `regenerateAllSnapshots`）改动行但**不置空** `quoteCardValues/costingCardValues`。lazy 模型下 `ensureCardValues` 只按 `IS NULL` 重选 → 切版本后卡片值非 NULL 不被重选 → 打开仍显示切版本前的陈旧卡片值。
- **范围**：在 `updateLineItemPartVersion` 的 `regenerateAllSnapshots` 之后把该行 `quoteCardValues/costingCardValues` 置 NULL（与 Task 4 的 D-1 同款失效；宜抽 `invalidateCardValues(li)` 私有助手，三处复用：processBatchStage1 / 逐行路径 / 切版本）。
- **依赖**：[[BL-0005]] 版本感知 BOM 闭包（切版本真生效后此 gap 才显现）；[[BL-0006]] 核价切版本调价主体。
- **预估规模**：S（1-2 天）
- **验收要点**：切版本后该行卡片值被重算（打开显示新版本值，不再陈旧）；未切版本的行不受影响；不引入 batch 风暴。

### [BL-0017] 报价料号统一 Spec 2 —— 选配发号统一（CFG-→XXXX-YYMMNNNNNN）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-06-报价料号统一-design.md` §9（Spec 1 落地时明确拆出）
- **状态**：[x] **已被覆盖（2026-07-08，选配 Plan 3b/3c）**。`ConfigureProductService.resolvePart`（custom SIMPLE）+ COMPOSITE 父级发号已从 `partNoProvider`(CFG- 前缀) swap 成 `QuoteMaterialNoAllocator.mintAndRegister`，产出报价料号格式 `{4位客户码}-{yyMM}{6位流水}`（正则 `^\d{4}-\d{6,}$`），与本条诉求一致；`config_fingerprint` 落库改为恒 NULL（R1，防跨客户撞生产侧全局唯一索引），复用判定改走销售侧客户维度指纹 `sel_part_signature`（R3）；`isCfg` 拒绝逻辑（`MaterialBomMergeHandler`）按 spec 保留未放开。详见 `docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md` 关联的选配 Plan 3b 集成设计 + `ConfigureProductServiceTest` / `ConfigureProductServiceSalesFingerprintTest` R1/R3 断言。
- **登记日期**：2026-07-07
- **推迟原因**：Spec 1（数据基座 + 发号服务）先行；选配是另一子系统。
- **背景**：`PartNoProvider`/`AutoAllocatePartNoProvider` 现按 `part_no_sequence` 发 `CFG-{符号}-{6位流水}` 作选配 `hf_part_no`；统一后应复用 `QuoteMaterialNoAllocator` 发 `XXXX-YYMMNNNNNN`（选配 `XXXX` 客户码取自选配所在报价/客户上下文）。`ConfiguratorInstanceService` 接入；重估 `MaterialBomMergeHandler.isCfg` 拒绝逻辑（选配号统一后是否放开回填）。
- **前置条件**：✅ Spec 1 的 `QuoteMaterialNoAllocator` 已就绪。
- **预估规模**：M（3-5 天）
- **关联**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md`（**该方案是本条的严格超集**：含发号统一 `CFG-→XXXX-YYMMNNNNNN` + `isCfg` 重估，再叠加参数池/行业模板/销售侧指纹去重；若该方案落地则本条随之完成，**勿重复立项**）。

### [BL-0018] 报价料号统一 Spec 3 —— 客户料号维护页面
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-报价料号统一-design.md` §9
- **状态**：TODO
- **登记日期**：2026-07-07
- **推迟原因**：UI 子系统，依赖 Spec 1 数据基座。
- **背景**：`material_customer_map` 加了 `production_no`（生产料号，报价侧后补）。需 UI 手工维护三码映射（客户料号 / 报价料号 / 生产料号）、回填 `production_no`、`source=MANUAL` 标记。
- **前置条件**：✅ Spec 1 表结构（`system_type`/`production_no`）已就绪。
- **预估规模**：M（3-5 天）

### [BL-0019] 清理 9 字头发号死代码 + 修历史 VersionedV6MasterDetailTest
- **优先级**：P2
- **来源**：报价料号 Spec 1 实现期子代理观察（范围外）
- **状态**：TODO
- **登记日期**：2026-07-07
- **推迟原因**：超出 Spec 1 范围的连带清理。
- **背景**：① `MaterialMasterRepository.maxNineLeadingMaterialNo`/`lockForMaterialNoGeneration` 在 Spec 1 后已无生产调用方（`MaterialNoResolver.generateNextMaterialNo` 已删），仅剩 `MaterialMasterRepositoryTest` 一个测试引用 → 是"测死代码的测试"，宜连方法+测试一并删。② `VersionedV6MasterDetailTest` 两个用例（`materialBom_nullCharacteristic_idempotent`/`childChange_bumpsMaster`）在 **master 上就已失败**（`VersionedV6Writer` 的 `CHILD_UQ=Map.of()` 空实现致 `material_bom_item` 无冲突目标；两名子代理用 git stash 背靠背验证与本次改动无关）→ 属独立历史 bug，需专项修 `CHILD_UQ` 登记。
- **前置条件**：无
- **预估规模**：S（1-2 天）

### [BL-0025] `CostingSummaryService.compute()` 料号级查询忽略 part_version（多版本激活后跨版本累加）
- **优先级**：P2
- **来源**：2026-07-01 核价单深度复核（亲验实库 `costing_part_*` distinct part_version=1）
- **状态**：TODO（潜伏，未排期）
- **登记日期**：2026-07-01
- **推迟原因**：**当前不触发**——实库各 `costing_part_*` 表 distinct `part_version` 均 = 1（多版本未激活）；与 [[BL-0005]] 版本切换尚未生效一致。
- **背景**：compute 的所有料号级查询（`CostingSummaryService.java:177-185`：matBom/element/process/tooling/design）**只按 `hfPartNo+isActive`、不带 part_version 维度**，weight 用无 `ORDER BY` 的 `firstResult()`。若同一 `hf_part_no` 出现多个 `is_active=true` 的 part_version：成本类**跨版本累加**（膨胀）、weight 取任意行（非确定）。
- **范围**：随第二期版本感知改造（[[BL-0005]]/[[BL-0006]]）一并给 compute 传入并过滤 `part_version`；weight 查询加确定性 `ORDER BY`。
- **依赖**：[[BL-0005]]（多版本真正启用后此 gap 才显现）。
- **预估规模**：S（1-2 天，随 BL-0005/0006 同步做）
- **验收要点**：多版本激活后 compute 只取指定 part_version 数据；未激活时逐位不变。

### [BL-0026] 核价侧低危隐患群（状态机旁路 / 渲染死代码 / 兜底反设计）
- **优先级**：P2
- **来源**：2026-07-01 核价单深度复核（多为审计代理报告、主线未逐一独立复核，标 PLAUSIBLE）
- **状态**：TODO（未排期，逐条确认后可拆分）
- **登记日期**：2026-07-01
- **背景/清单**（按面归类，均低危或旁路）：
  - **状态机**：遗留 `QuotationService.approve()/reject()`（`:1266,1298`）旁路核价流、不更新 `CostingOrder`，直连 API 调用可致报价单死锁 + 工作台僵尸排队项（前端 0 调用，仅 API 可触发）；`frozen_dto` 冻入 `status="DRAFT"`（submit `:910` 冻结早于 `:912` 赋 SUBMITTED，展示偏差）；`withdraw` 用 `findLatest` 覆写终态 `REJECTED`→`WITHDRAWN` 丢审计；`/copy`、`/delete`（含 `/approve /reject`）缺 `@RoleAllowed`，`RoleFilter` 无注解即放行（安全）。
  - **BOM/缓存**：`CardSnapshotService.java:1790` `recursive` 在 `dc[1]` 非 Boolean 时兜底 TRUE（与「默认关」相反，当前被 `NOT NULL DEFAULT false` 屏蔽）；单值 `$view.col` 路径 `DataLoader` `resultCache` key 缺 componentId（同名导入副本条件串号，守 [[cpq-sqlview-cache-key-needs-component-dim]]）；DAG 单节点多业务行树形「首条胜」展示瑕疵。
  - **渲染**：旧 `CostingSheetView` + `/costing-sheet` + `CostingSheetService` 是死组件、读「已无人维护」的 `costing_sheet` 表（重新挂回即双源不一致）；frozen 模式 QUOTE 分支在历史单 `quoteCardStructure` 缺失时回落 live `/templates` 请求（`ReadonlyProductCard.tsx:213,219`）。
- **范围**：逐条确认后拆分处理——优先下线遗留 `/approve`·`/reject`（或补 CostingOrder 联动）+ 补写端点 `@RoleAllowed`（安全项）；其余按需修。
- **依赖**：无。
- **预估规模**：M（逐条确认 + 修，主要成本在确认）
- **验收要点**：遗留旁路端点不再能制造报价单死锁；写端点有鉴权门；死代码/兜底反设计逐条裁决（修或标注保留）。

### [BL-0029] 核价递归 SQL 校验器「空 seed 盲区」—— 保存通过、渲染必崩的一类 SQL 漏网
- **优先级**：P1
- **来源**：2026-07-03 QT-20260703-1928 核价卡片全空根因定位
- **状态**：[-] **保存期不可行 → 由 [[BL-0030]] 兜底（2026-07-03 结论）**。实测:①空 seed + LIMIT 0 漏（现状）；②`EXPLAIN` 也抓不到（`cannot compare dissimilar column types` 是**运行期**、非 plan 期错）；③合成非空 seed 若不递归（占位料号无 BOM 子件）→ CYCLE 不触发比较 → 仍抓不到。即那类「只在真数据真递归时暴露」的错**无法在保存期用空/合成 seed 拦下**。真正安全网 = BL-0030（render 失败显式透出错误原文到前端），已实现。若将来仍要保存期兜底,唯一路是「探测库里一个真实有 BOM 子件的料号做 seed 真跑一层」,但耦合数据、且不同递归 SQL 引用的表未知,性价比低。**本条降级为 wontfix/观察,不单独修。**
- **登记日期**：2026-07-03
- **背景**：`CostingTreeSqlValidator.validate()` 用**空 seed** `ARRAY[]::text[]` + `LIMIT 0` 做 dry-run。递归 CTE / `CYCLE` 的**运行时错在空数据下不触发**（0 行→不进递归→不做 CYCLE 行比较），导致「保存期校验通过、真实 render 必崩」的 SQL 漏网。**实证**：用户存的递归 SQL 缺 `material_no::text`，seed 绑 `text[]`(见 `CostingTreeRenderService.queryRecursive` `createArrayOf("text")`)、递归列 `varchar` → `CYCLE material_no` 报 `cannot compare dissimilar column types` → render 崩 → 快照 NULL → 全 77 卡片空。校验器却因空 seed 放行。
- **范围**：dry-run 改为「用一个**非空样例 seed**探测」（如取库里任一有 BOM 子件的真实料号，或注入 1 个占位料号让递归真正走一层 + CYCLE 真比较）；至少要能触发递归分支的类型/语义错。评估样例 seed 来源（固定占位 vs 探测库）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：一条缺 `::text`（或其它仅运行时暴露）的递归 SQL 在**保存期**即被拦下，不再能存成生效配置。

### [BL-0030] 核价树 render 失败被静默吞成空卡片（AP-31 静默失败族）—— 无任何前端可见报错
- **优先级**：P1
- **来源**：2026-07-03 同上根因定位
- **状态**：[x] **已完成（2026-07-03，master `092f48a`）**。`CardSnapshotService` 批量层 `render()` 加 try/catch:失败时不上抛（否则整单 500+全 NULL→前端无限「加载中…」），逐 li 落带错误原文的失败哨兵 `{tabs:[],__cardValueFailed:true,__errorMsg:"核价渲染失败: …"}`；前端 `cardValueFailed.ts#getCardValueError` 取原文，`QuotationStep2` 核价卡片占位以 error Alert 显式展示原文 + 指引查「核价树配置」。配置员一眼定位，取代翻后端日志。
- **登记日期**：2026-07-03
- **背景**：`CostingTreeRenderService.render()` 抛错（递归 SQL 崩 / 无生效配置 / 页签 $view 崩）后，被 `CardSnapshotService.buildCostingCardValues` 的 `try/catch` **catch 成返回 null + 仅 `LOG.warnf`** → `costing_card_values` 留 NULL → 前端**只看到空卡片、无任何红错**。用户无法自知是递归 SQL 崩了，只能靠翻后端日志。另页签 $view 忘输出 `material_no` 时也是渲染期 WARN + 静默落选（已有 WARN 守卫但 UI 不可见）。
- **范围**：让核价树渲染失败对用户**可见**——如 `ensure-card-values` / 渲染响应带回一个「核价树配置错误 + 具体消息（递归 SQL 报错原文 / 未配置生效 SQL / 页签缺 material_no）」的结构化提示，前端在核价卡片区显式提示而非空白。区分「真无数据」与「配置/SQL 报错」两种空。
- **依赖**：与 [[BL-0029]] 同批修更省（都属核价树配置期防呆）。
- **预估规模**：M（3-5 天，含前端提示位）
- **验收要点**：递归 SQL / 页签 $view 报错时，核价卡片区出现明确错误提示（含原因），而非静默空白。

### [BL-0032] 选配模板版本 / 发布状态机
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：一期模板"直接生效"即可满足；草稿/发布为增强。
- **背景**：`sel_template` 一期无版本/发布态，保存即生效。未来需草稿→发布、历史版本留痕，避免编辑中的模板影响线上选配。
- **范围**：给 `sel_template` 加状态机（草稿/发布/停用）+ 版本；选配运行时只取"已发布"版本。
- **依赖**：选配模板 CRUD 落地。
- **预估规模**：M（3-5 天）
- **验收要点**：模板可草稿编辑不影响线上；发布后选配取新版本；历史版本可查。

### [BL-0033] 选配组合体报价料号 BOM 关系落表
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §4.3/§9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：组合体 BOM=N 子件的完整关系表需单列设计；现役 `insertMaterialBomAssemblyV6` 仅雏形。
- **背景**：组合产品选配的组合体报价料号 BOM（=N 个子件报价料号）+ 组合工艺挂载关系的落表口径未定。
- **范围**：设计组合体↔子件报价料号 BOM 关系落库（对齐现役 `insertMaterialBomAssemblyV6`），组合工艺按 `sales_part_no` 落。
- **依赖**：子件报价料号落库稳定；[[BL-0031]]（工序承载表）。
- **预估规模**：M（3-5 天）
- **验收要点**：组合体报价料号 BOM 正确记录 N 子件 + 组合工艺，核价/渲染可读。

### [BL-0034] 选配料号编辑重算撞指纹的处置 / 合并策略
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §5.4（时机 B）/§9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：spec §5.4 已定一期"拦截并提示复用"；合并/并存是后续高级形态。
- **背景**：允许编辑已生成报价料号的材质/元素/工序 → 重算销售侧指纹 → 若撞该客户已有料号，一期仅"拦截提示改为复用"。更完整的合并（把两个料号合一、迁移已引用报价单）留后续。
- **范围**：撞指纹时的合并（引用迁移 / 软删旧料号）或并存策略；含被引用报价单/核价单的影响面处理。
- **依赖**：`sel_part_signature` 唯一约束方案落地。
- **预估规模**：M（3-5 天）
- **验收要点**：编辑撞指纹后按所选策略处置，不产生悬挂引用 / 重复料号。

### [BL-0035] 「生产料号」BNF 逻辑名重定向 + 概念收敛（架构级）
- **优先级**：P1
- **来源**：task_0708 导入落库料号纠偏澄清（决策外溢；`docs/table` 落库方案配套）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：本次任务只做 V6 导入落库；BNF 映射重定向牵动公式引擎，需 architect 评审 + 公式回归，独立立项更稳。
- **背景**：「生产料号」概念现三处分裂 —— ① `SchemaContext.java:146` 把 BNF 逻辑名`生产料号`映射到**已废弃**的 `mat_part`；② `internal_material`（生产料号管理 UI）；③ task_0708 新落的 V6 表 `production_no` 列。若公式/组件引用 BNF 路径`生产料号`，会解析到废弃 `mat_part` 而**非**新落的 `production_no`，导致"落了数据公式取不到"。
- **范围**：把 `SchemaContext` 逻辑名`生产料号`映射从 `mat_part` 改指向 live 源（`production_no` / `internal_material`，方案待定）；盘点并回归所有引用`生产料号`/`mat_part.*` BNF 路径的公式模板。
- **前置条件**：✅ 已就绪（task_0708 的 `production_no` 落库已合入 master，2026-07-09 结案）。
- **依赖**：[[BL-0036]]（若与 mat_part 退役合并推进）。
- **预估规模**：M（3-5 天）
- **验收要点**：引用`生产料号`的公式解析到 live 数据（非 mat_part）；公式回归全绿；无静默取空。

### [BL-0036] `mat_part`（V44）全面退役 → 迁 V6 等价物（architect 主导 · 高风险）
- **优先级**：P2
- **来源**：task_0708 调研外溢（用户误以为已退役，实测仍为多块核心活跃底座）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：动 **2 个核心基线**（报价渲染 + 公式/组件），多周工程、高风险，必须 `cpq-architect` 出迁移方案后分阶段实施，不可并入落库任务。
- **背景**：`mat_part` 被标记 V44 废弃（AP-53），但实测仍是活跃底座：**7 个现役视图** JOIN 它（`v_composite_child_materials/processes/elements/weights`、`v_part_material_recipe`、电镀方案视图，喂组合产品渲染）；**选配加产品**写它（`ConfigureProductService`：config_fingerprint / material_recipe_id / product_type）；**单重** `mat_part.unit_weight` 被报价+核价公式 BNF 引用 **19 处**；产品维度 width/length/height；共 **47 个 java 文件 + 20 个迁移**引用。
- **范围**：组合子件视图迁 V6；选配写入迁 V6；`unit_weight`/维度 BNF 路径迁 V6；PartVersion / 导入 staging 解耦；数据迁移 + 老表 DROP；配套清理过时注释（`QuotationDTO.java:159` 等把 mat_part 当主档的旧注释）。
- **依赖**：V6 表覆盖组合/选配/单重全部维度；[[BL-0035]]。
- **预估规模**：L（1 周以上，实为多周）
- **验收要点**：组合产品 / 选配 / 单重公式 / 电镀 全部改读 V6 且 E2E 全绿；`mat_part` 可安全 DROP 无残留引用。

### [BL-0037] V6 基础资料查询页料号列标签校正 + 可选展示新列
- **优先级**：P2
- **来源**：task_0708 前端影响面评估（前端非强制改动项）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：不在 task_0708「数据正确落库」验收范围内；属语义一致性优化，可择期。
- **背景**：task_0708 后 V6 表 `material_no` 值语义翻转为**销售料号**。直接读 V6 表的前端页（`V6BomQueryTab`/`V6BomItemDetailDrawer`、`CustomerMaterialMappingTab`、物料主数据页）若列标签仍写旧名（宏丰料号/生产料号），会"标签写 X 实显销售料号"错位；按生产料号搜索也会搜不到。
- **范围**：校正上述页面料号列标签为「销售料号」；按需增列展示 `production_no`（生产料号）/ `material_part_no`（材质料号）；搜索键口径对齐。
- **前置条件**：✅ 已就绪（task_0708 落库已合入 master，2026-07-09 结案）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：V6 查询页标签与实际值语义一致；如加新列则正确回显。

### [BL-0038] task_0708 遗留验收数据二次验证（R1 production_no 取值 + R4 报价铸号回归）
- **优先级**：P2
- **来源**：task_0708 测试报告 §四 未覆盖遗留项（R1/R4）
- **状态**：进行中 —— ✅ **R1 已闭合**（repair-1 用自洽文件正向验证 production_no=生产料号≠销售料号，QA 报告 + 技术总监亲验 SQL 通过，2026-07-09）；⏳ R4（报价铸号正向路径）仍待补数据
- **登记日期**：2026-07-09
- **推迟原因**：官方测试文件数据不具备触发条件，非代码缺陷；task_0708 已结案，遗留为"补样例二次验证"。
- **背景**：
  - **R1**：官方核价 6.0 文件「生产料号」列全空 → `production_no` 落库全 NULL，取值映射（生产料号值→production_no 且 ≠ 销售料号）未被官方数据证伪。**开发方已用两列都填的补充文件自测 `capacity.production_no=PN-3120018220` 逐值吻合**，逻辑已验证，仅缺官方数据走查。
  - **R4**：官方报价 V3「投入料号」列全空 → V308「组件缺料号→按名铸号 `XXXX-YYMMNNNNNN`」正向路径未触发/未回归。反向（Q04 不再错误铸号）已由 TC-B5b 覆盖通过；铸号代码本次未改动。
- **范围**：制作/并入含真实生产料号值的核价样例 + 含"有名称无料号"组件行的报价样例，各正式重导一次，断言 `production_no=对应生产料号值` 及生成 `XXXX-YYMMNNNNNN` 号正确落库。

### [BL-0039] 导入引用校验：成本行销售料号必须在客户映射表存在（防静默断链/畸形数据）
- **优先级**：P2
- **来源**：task_0708 repair-1 复验（RR-1：新文件映射表销售料号裸号/含元素码，与成本表零交集，导入不报错静默建断链记录）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-09
- **推迟原因**：本质是导入防呆增强，非某次落库缺陷；repair-1 只修数据不加校验，另立项评估。
- **背景**：成本各 Sheet 引用的 `销售料号` 若在 `宏丰-客户料号对应关系`（客户映射表）不存在，或映射表把元素/材质编号误列为销售料号，当前导入**不报错**——照样把断链/畸形记录写库（如 material_master 出现同产品裸号+S-号两行、元素码被当销售料号登记）。只能靠人工比对发现。
- **范围**：导入时对成本行 `销售料号` 做引用校验（须在客户映射表存在），不匹配则告警/记入 `failedRows` 或预检报告；可选：映射表销售料号列疑似元素码（与元素/材质编号集合交集）时提示。需定"硬失败 vs 软告警"口径。
- **前置条件**：无。
- **依赖**：无。
- **预估规模**：M（3-5 天）
- **验收要点**：畸形/断链导入数据被导入层主动拦截或告警，不再静默落库。
- **前置条件**：✅ 已就绪（task_0708 代码已合入 master）。
- **依赖**：无。
- **预估规模**：S（1-2 天，主要成本在造数据）。
- **验收要点**：R1/R4 两条路径各跑通一次官方级复验，消除数据遗留。

### [BL-0041] 材质↔料号关联功能（导入绑定 / 关联料号 tab / 智能建议）
> 注：原登记为 BL-0039，与并发会话「导入引用校验」撞号，2026-07-09 改为 BL-0041。
- **优先级**：P1
- **来源**：`dev-docs/task-0708-材质库规范澄清/`（材质库规范化需求澄清，Q4 明确本期推迟）
- **状态**：TODO（**2026-07-09 业务方明确"先不做、继续隐藏"**；前置已就绪但暂不排期）
- **登记日期**：2026-07-09
- **推迟原因**：本期聚焦"材质库规范化 + 导入"，料号关联另属一块，用户明确"料号这块的功能后期再议"。2026-07-09 澄清时业务方再次确认**先不做、关联料号 tab 继续隐藏**。
- **背景**：材质库导入本期**只读** `材质编号`+`材质对应元素` 两 sheet，`材质对应料号` sheet 忽略；编辑抽屉「关联料号」tab 本期**隐藏**；现有绑定基建（`material_master.material_recipe_id`、`/material-recipes/{id}/parts|bind-parts|unbind-parts|search-parts|suggest-bindings|confirm-bindings`、`MaterialRecipePartsTab.tsx`）**保留未删**、仅不挂载/不调用。
- **范围**：重新启用"材质↔料号"绑定 —— 恢复编辑抽屉「关联料号」tab + 顶部「智能建议」入口；评估是否需从 Excel `材质对应料号` sheet 批量导入绑定；对齐 V6 落库口径（`material_master.material_recipe_id`）。
- **依赖**：**task-0708 材质库规范化（材质编号/元素/element 主表）必须先落地**——料号绑定需以规范化后的材质编号为锚。
- **前置条件**：✅ **已就绪**（task-0708 2026-07-09 验收通过：material_recipe 253 落库 + element 主表 39 元素）。
- **预估规模**：M（3-5 天）
- **验收要点**：材质编辑抽屉可绑定/解绑料号并落 `material_recipe_id`；智能建议可用；选配抽屉反查材质不回归。

### [BL-0040] 元素主表管理 UI（元素字典 CRUD）
- **优先级**：P2
- **来源**：`dev-docs/task-0708-材质库规范澄清/`（Q5）→ **已澄清立项 `dev-docs/task-0709-元素主表管理/`（2026-07-09）**
- **状态**：**[x] 已完成（2026-07-09 合 master `c27f604`）**。B 模型全落地并终验收通过（技术总监独立查隔离库 cpq_db_elemtest + 代码核实）：element_no 不可改业务主键(V319 补 Au/CdO 号)、material_recipe_element 加 element_no(V320 628行全回填)、符号锁(被引用改符号 409)、只软删、导入按编号 upsert 不覆盖人工值、253/1 零回归、定价 join 通、不动选配/定价边界；前端「元素」页签 + 符号锁 UI。8081 `/elements` 404→401 上线、cpq_db 已应用 V319/V320。
- **登记日期**：2026-07-09
- **推迟原因**：本期 `element` 主表只作字典（seed + 导入按符号 upsert 回填），无独立管理界面需求；元素 CRUD 属增强。
- **背景**：task-0708 新建 `element(element_code 符号 PK / element_name 中文 / element_no 编号 / status)`，仅由 seed + 导入维护；管理端无处增删改元素、无处补录字典外新符号的中文名。
- **范围**：主数据维护下增「元素」管理页（SelectableTable + 工具栏动作 + Drawer 编辑），支持元素增删改停用、补录中文名、维护元素编号；与导入 upsert 语义对齐（不冲突）。
- **依赖**：task-0708 的 `element` 主表已建。
- **前置条件**：✅ **已就绪**（task-0708 2026-07-09 验收通过：`element` 主表已建、39 元素含数字牌号；补录中文名的实际缺口 = 191/206/223/258/721 五个数字牌号暂无中文名）。
- **预估规模**：S（1-2 天）
- **验收要点**：可在管理页维护元素字典；补录的中文名不被后续导入的符号占位覆盖；停用元素不破坏已引用它的材质渲染。

---

### [BL-0042] 报价侧产能 `Q14 组装加工费` 触发列拉平（报价/核价升版口径统一）
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（C12 / 版本升级规则文档 §5.4）；本期核价 `capacity` 已去触发列，报价侧留档暂不动
- **状态**：TODO（未排期）
- **登记日期**：2026-07-10
- **推迟原因**：本期范围 = 仅核价侧（C13 报价侧零代码改动）；报价 `capacity`(Q14) 改触发列属报价侧改动，另立项。
- **背景**：核价 `capacity` 本期去触发列 → 金额/币种/单位变化也升版（甲·任一值变即升版）；报价 `Q14 组装加工费` 仍保留触发列 `process_no,seq_no`（金额原地更新不升版）。二者短期内对"产能金额是否升版"口径不一致。
- **范围**：评估报价侧 `Q14CapacityHandler`（或对应 handler）是否去触发列与核价拉平；若拉平需跑报价 E2E 回归确认不破坏现有升版行为。
- **依赖**：本任务（核价侧）落地后再评估是否需要拉平。
- **预估规模**：S（1-2 天）
- **验收要点**：拉平后报价/核价 `capacity` 升版口径一致；报价重导回归 `failedRows=0`、既有版本线不被误升。

### [BL-0043] `pricing_price` 表纳入版本化（报价侧多 sheet 附带写、当前无版本历史）
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（版本升级规则文档 §四·差异④）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-10
- **推迟原因**：`pricing_price` 附带写发生在**报价侧** handler（Q07/Q08/Q10/Q11/Q13/Q15/Q17），本期报价侧不动（C13）；核价 P* handler 不写 `pricing_price`，本期无触及。
- **背景**：报价多张 unit_price sheet 除写 `unit_price` 外还"另写 `pricing_price` 表"，该表本身**未接版本化**（无 is_current/版本线）。与 unit_price 主线的版本化不对齐。
- **范围**：评估 `pricing_price` 是否需随对应 unit_price 组同步版本化（同 groupKey/同版本号），或明确其"衍生表、不独立版本"定位并留档。
- **依赖**：BL-0042 一类报价侧改造窗口；需先厘清 `pricing_price` 下游取数是否依赖版本。
- **预估规模**：M（3-5 天）
- **验收要点**：`pricing_price` 与其来源 unit_price 组版本一致（若纳入）或有明确"不版本化"决策留档；下游取价不断链。

### [BL-0044] `material_version_mgmt`（P04 核价版本包）"按版本号钉住历史价"能力
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（C6 / 版本升级规则文档 §5.1 A 组 P04）
- **状态**：TODO（待评估）
- **登记日期**：2026-07-10
- **推迟原因**：本期核价取数口径 = A·永远取 `is_current=true` 最新版（C6），不按版本号复现历史价；P04 版本包"用元素/材料/汇率版本号钉住历史核价"的职责随之退化，划出本期范围（与 C3"全局登记表暂不纳入"一致）。
- **背景**：`material_version_mgmt`（P04 核价版本 sheet）原设计承载"一张核价单锁定当时的元素价/材料价/汇率版本号 → 复现历史核价"。当前业务永远取最新价，该能力未启用。
- **范围**：若未来需求要"复现历史核价/审计当时价格"，再设计版本包引用链（P01/P02/P03 保留业务版本号 + P04 登记 + 核价取数按版本号回溯）。属"版本感知取数"大工程，与 [[BL-0005]] 版本感知 BOM 闭包同族。
- **依赖**：[[BL-0005]] 版本感知 BOM 闭包展开（历史复现的前置能力）。
- **预估规模**：L（1 周以上）
- **验收要点**：能按核价单锁定的版本号复现当时的元素/材料/汇率价与子料号 BOM；不影响默认"取最新"路径。

### [BL-0046] 物料BOM 组成件 `component_no` 按 `calc_type` 动态下拉
- **优先级**：P2
- **来源**：task-0712（C13）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期 C13 定为自由文本，动态下拉实现稍复杂、非核心路径。
- **背景**：P06 物料BOM 组成件按 `calc_type`（材料/元素）语义不同——材料→`material_master`、元素→`element`；本期统一自由文本、名称尽力解析。
- **范围**：`EditableSheetTable` 支持按同行 `calc_type` 动态切换 `component_no` 下拉源；后端 lookup 复用。
- **依赖**：无（前端为主）。
- **预估规模**：S（1-2 天）
- **验收要点**：切 `calc_type` 时组成件下拉源正确切换、选中带出对应名称。

### [BL-0047] 核价基础数据历史版本"恢复为当前"（回滚）
- **优先级**：P2
- **来源**：task-0712（C7）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期历史版本定为纯只读，回滚非必需。
- **背景**：料号核价维护抽屉可切历史版只读查看；用户可能需要"把某历史版内容恢复为新当前版"。
- **范围**：新增"恢复为当前"操作，取历史版行集作为新提交走升版（复用 `saveGroup`，结果 UPGRADED/CREATED）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：从历史版一键恢复生成新当前版、内容=该历史版、旧当前版 `is_current=false`。

### [BL-0048] 全局 4 表（P01/P02/P03/P21）维护入口
- **优先级**：P2
- **来源**：task-0712（C1）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：全局表不挂销售料号、不适配"料号核价"抽屉模型，本期排除，另立项。
- **背景**：元素核价价格(P01)/材料核价价格(P02)/汇率(P03)/电镀方案(P21) 各按自身轴独立升版，需独立维护页（列表+版本切换+编辑）。
- **范围**：为 4 张全局表各建维护入口，可复用本任务元数据驱动 registry/`EditableSheetTable` 模式（去料号维度轴）。
- **依赖**：无。
- **预估规模**：M（3-5 天）
- **验收要点**：4 表可查/改/升版，取数取 `is_current` 最新版。

---

### [BL-0049] create-quotation 建行失败当前 500 掐整单 → 评估降级为「空单+前端兜底」
- **优先级**：P2
- **来源**：task-0712 核价展示修复 最终评审 Important-2
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期尊重 spec §5「建单+建行强一致」，materializeLines 在 createQuotation 同事务内；Critical 修复(候选正确框定)后抛错风险已低，且幂等重入可安全重试，暂不改事务模型。
- **背景**：旧流程 create-quotation 恒建出（可能空）报价单，明细行由前端 autoPopulate 补；新流程把建行塞进强一致事务，materializeLines 抛异常会使整单创建 500 且报价单也不落库（相对旧行为的可用性回归）。
- **范围**：评估将 materializeLines 移到建单事务提交后（Resource 层 materialize 的 step 0，best-effort try/catch），失败则留空单 + 前端 autoPopulate 兜底（与 Task5 的 backendBuiltLinesRef=false 分支天然衔接）；权衡 §5 原子性 vs 可用性。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：模拟 materializeLines 抛错 → 报价单仍创建成功（空单）、前端进编辑页 autoPopulate 兜底建行，不 500。

### [BL-0050] create-quotation 降级时前端消费 CommitResult.warnings/cardValuesReady 给用户提示
- **优先级**：P2
- **来源**：task-0712 核价展示修复 最终评审 Minor-4
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：后端已回填 warnings/cardValuesReady/costingTreeRows，但前端导入流未读；降级时靠下游失败哨兵占位兜底（不误导），非阻断。
- **背景**：物化失败时 cardValuesReady=false + warnings 列出料号，用户在创建当下拿不到 toast/banner 提示，只能进编辑/详情页被动看到失败哨兵。
- **范围**：`QuoteBasicDataImportV6Drawer`/导入流创建成功回调处，若 `data.cardValuesReady===false` 或 `data.warnings?.length` 弹一次 `message.warning` 列出未就绪料号。
- **依赖**：无。
- **预估规模**：S（1 天）
- **验收要点**：降级建单后前端弹 warning 提示，正常建单无多余提示。

### [BL-0051] task-0713 收尾：`:spineKeys` 死代码清理
- **优先级**：P2
- **来源**：task-0713 backtask B8（计划内可选，本期跳过）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-14
- **背景**：`SpineKeysMacro`/`SpineKeysContext` 已确认死代码（`SqlViewExecutor` 侧从未读取、纯 no-op）；task-0713 用 `CostingTreeVarsContext`+`VersionFilterMacro` 取代。B8 清理与新宏解耦、低风险，本期未做。
- **范围**：删 `SpineKeysMacro`/`SpineKeysContext` 及残留引用（`zpj_view` 等 $view 里的 `:spineKeys` 文本 V334 已被 versionFilter 改造）。
- **依赖**：无。**预估规模**：S。
- **验收要点**：删后核价树渲染无变化、E2E 绿。

### [BL-0052] task-0713：`CostingSubtotalUtil` 模板缺 SUBTOTAL 组件时静默返 0 → 是否显式报错
- **优先级**：P2
- **来源**：task-0713 3a 返修实现期披露
- **状态**：TODO（待 PM 裁决）
- **登记日期**：2026-07-14
- **背景**：`CostingSubtotalUtil.extractUnitSubtotal` 对「找不到 SUBTOTAL 组件 / 公式为空」静默返 ZERO（不抛错不 warn）。若未来某核价模板忘配 SUBTOTAL 组件，单据总价会悄悄变 0 而非报错——当前是有意的宽松兜底。
- **范围**：若要「配置缺失显式可见」，加配置期 lint / 渲染期 warn。
- **依赖**：无。**预估规模**：S。
- **验收要点**：核价模板缺 SUBTOTAL 组件时有明确提示，不再静默 0。

### [BL-0053] task-0713：版本切换扩到其余核价模板克隆副本 + DELETE override 端点
- **优先级**：P2
- **来源**：task-0713 backtask 已知限制
- **状态**：TODO（未排期）
- **登记日期**：2026-07-14
- **背景**：本期只精确改了 7 个已核实的 `component_sql_view` 物理行（"BOM树演示-核价模板"5 件套 + pj/ys_view）；其余克隆副本（如"核价模板0603"）$view 未加 `view_version`+`versionFilter`，暂不支持版本切换。DELETE override 端点（api.md §5 标可选）未实现（用「切回 is_current 版本」代替）。
- **范围**：给需版本切换的其余核价模板 $view 加约定列+宏（机制通用、平台零改动）；按需实现 DELETE override 端点。
- **依赖**：无。**预估规模**：S-M。
- **验收要点**：目标核价模板的 $view 输出 view_version 即可版本切换；DELETE 复位生效（若实现）。

### [BL-0054] task-0713 连带：`GoldenCardValuesEquivTest` golden 常量重校准
- **优先级**：P2
- **来源**：task-0713 后端回归排查（金标准 hash 漂移）
- **状态**：TODO（需 golden owner 重新捕获，非 task-0713 引入 bug）
- **登记日期**：2026-07-14
- **背景**：task-0713 给 `buildTabNode` 加 `componentType` + 让 SUBTOTAL tab 真出 subtotal（修 3a）+ 顺手修 `zpj_view` bug（"子配件"页签从恒空变真实出数据），使核价卡片值**合法变化** → `GoldenCardValuesEquivTest`/`NonRecursiveCostingBucketEquivTest` golden hash 漂移。经临时回退验证：漂移是「值合法变化」非回归，且 golden 常量本就已过期（与 [[cpq-golden-cardvalues-preexisting-drift]] 一致）。**未擅改 golden 常量**，留 owner 复核重捕获。
- **范围**：golden owner 确认新基线正确后回填常量（或改用对数据漂移不敏感的锚单/夹具）。
- **依赖**：无。**预估规模**：S。
- **验收要点**：两测试在当前 master 复绿、保留确定性护栏。

### [BL-0055] 报价单删除行 Phase 2：内容指纹身份（uniqFp）根治重复行"连删"
- **优先级**：P2
- **来源**：删除删错行 Phase 1 交付后重评估（2026-07-15）
- **状态**：TODO（依赖 partno 专项，暂缓）
- **登记日期**：2026-07-15
- **背景**：Phase 1（commit `9245555`）已把墓碑匹配从 effKey+fp 双命中改为 **fp 内容身份单键**（真根因=前后端 computeRowKey 算的 effKey 不一致），删除删对行、值不串、多次重渲染稳定。**唯一残留**=字节级完全重复行 fp 相同 → 删一个"连删"同 fp 行（实测：删 AgNi → 两 AgNi 都删、两 H65 保留）。而重复行的**唯一来源** = `element_bom_item` 销售号+生产号双重登记（见 [[BL-0035]]/partno 暂搁专项）。设计文档 `dev-docs/task-删除行删错架构重构/设计方案.md` 的 Phase 2（uniqFp 加 `#序号` + editRows/formulaResults/React-key/写回 统一身份 + 向后兼容读）可精确只删重复对里的一个。
- **范围**：**若 partno 数据修好（消除重复行）→ 大概率可不做**；否则按设计文档 T2.0~T2.5（spike→后端契约→前端契约→写回退耦→只读页→E2E）。删除侧已由 fp-match 覆盖，可缩到只做**编辑侧 + Excel 只读快照同源**。
- **依赖**：**partno 专项（消除重复行数据源）——建议先做，做完再评估本项是否还需要**。**预估规模**：L（全量）/ M（缩到编辑侧）。
- **验收要点**：重复行删一个只删一个；editRows/formulaResults/React-key/写回全走统一身份；存量墓碑向后兼容读。

### [BL-0056] 报价单编辑页 driver INPUT 编辑落库存疑（需真人复现定性）
- **优先级**：P1（若属实 = 编辑数据丢失，需先确认真伪）
- **来源**：Phase 2 影响面实测（2026-07-15）顺带发现
- **状态**：TODO（需真人手动复现）
- **登记日期**：2026-07-15
- **背景**：合成 E2E（Playwright `fill`+`blur`）编辑 c4d9b1dc（QT-20260713-1963）来料 row1 的「加工费」→88.88：**卡片正确显示 + 公式（材料成本）更新**（本地算、落对行、无混行），但 **DB `row_data` 仍 0.04326、`quote_card_values.editRows` 空** → 编辑未落库（刷新会丢）。可能：①Playwright `fill/blur` 没完全驱动 `EditableCellInput` 的 onBlur→onCommitBlur→`handleSnapshotCellEdit`（QuotationStep2 L2675-2676，`useSnapEdit` 门控）提交流（**测试假象**）；②既有编辑持久化 gap（`editQuoteCardValue` 静默失败被 catch 吞 / autosave `skipRowsWithSnapshot` 跳过快照行）。**与本次删除修复（Phase 1）无关**（编辑路径 handleSnapshotCellEdit/editCardValue/autosave 一字未改）。
- **范围**：真人手动改一个 driver INPUT 值 → **刷新页面看是否还在**。若丢：查 `handleSnapshotCellEdit`(L1934) 是否被调、`editQuoteCardValue` 是否返 null、autosave 是否跳过快照行。
- **依赖**：无。**预估规模**：S（复现+定性）；修复规模视根因定。
- **验收要点**：编辑 driver INPUT 值刷新后仍在（editRows 或 row_data 落库）。

### [BL-0058]（技术债）`DataLoader.resultCache` 缺 versionFilter（mode/override）维度 → 同请求内同 `$view` 的 LIST/RENDER expand 串号
- **优先级**：P2（生产影响低；非阻断）
- **来源**：repair-071501 排查核价单版本切换 Bug2 时顺带发现（2026-07-15）
- **状态**：TODO
- **登记日期**：2026-07-15
- **背景**：`DataLoader` 为 `@RequestScoped`，`resultCache` 按 `$view` 归一化 path 为 key、**不含 versionFilter 的 mode/override 维度**（AP-37 / [[cpq-sqlview-cache-key-needs-component-dim]] 同族"缺维度缓存串号"）。同一请求内对同一 `$view` 先 LIST 模式 expand（`:versionFilter`→`TRUE` 返全版本）再 RENDER 模式 expand（按 override 渲染）时，第二次命中第一次缓存 → 返回全版本混版。
- **生产影响 = 低**：`switchVersion` 生产是独立 HTTP 请求（每请求新 `@RequestScoped DataLoader`、单请求内该 `$view` 只 expand 一次）→ 无串号、版本过滤正确（用户 live 数据实为干净单版本，已印证）。仅 `listVersionOptions` 端点自身一个请求内 LIST→RENDER 连查同 path，会让"当前版本高亮 `currentVersion`"在**无 override 兜底时**可能取错版本（纯高亮、不影响实际切换；有 override 时 currentVersion 读 override 表不受影响）。`@QuarkusTest` 因两次服务调用共享同一 request scope 会放大成"混版"（测试假象）。
- **范围**：`DataLoader.resultCache` key 增加 versionFilter 维度（override 指纹 + mode）；或 `listVersionOptions` 两次 expand 之间清 resultCache。触及核价渲染取数缓存核心（AP-37 高风险区 + `docs/三大核心模块基线.md`），需单独评审。
- **依赖**：无。**预估规模**：M（含 AP-37 回归验证）。
- **验收要点**：同请求内同 `$view` 先 LIST 后 RENDER，RENDER 结果按 override/is_current 正确过滤、不返全版本；`listVersionOptions` 无 override 时 currentVersion 取到真实 is_current 版本。

### [BL-0059] 按列折扣的后端提交重算：cross_tab 公式列折扣不生效（引擎行值缺真值）
- **优先级**：P1（按列折扣 + cross_tab 模板组合场景折扣静默失效；整体折扣 SUBTOTAL 源不受影响）
- **来源**：subtotal-fix-071701 全链路口径统一（2026-07-17，QT-20260716-2033 排查）已知限制
- **状态**：TODO
- **登记日期**：2026-07-17
- **背景**：`LineDiscountService.recompute` 的按列折扣 S1 依赖 `ComponentDataEffectiveRows.computeScaled` 的 Pass1 `columnSums(row_data)`——但 cross_tab_ref 公式列（如 来料.材料成本 = SUM(元素行 用量×单价)）的真值不落 `row_data`（实测恒 0）→ 缩放该列无效果（折扣比例=1）。本次修复已把 S0/行合计对齐前端完整口径（S0 采信 li.subtotal + 比例映射），按列折扣残留此限制（与修复前一致、不更糟）。
- **范围**：提交链路引擎行值改读卡片值快照（`quote_card_values.tabs[].resolvedRows` 列和，后端 CardSnapshotService 物化时已含 cross_tab 对称逻辑），与 `row_data`（手动行真相源）按列 merge（公式列取快照、输入列取 row_data）；submit 前需 `ensureCardValues` 保证快照非 NULL。注意 [[quote-card-values-excludes-manual-input-rows]]（qcv 不含手动行）的合并语义。
- **依赖**：无（quote_card_values 物化链路已就绪）。**预估规模**：M。
- **验收要点**：按列折扣（如 来料#材料成本 打 9 折）提交后 lineFinalPrice 与前端 Step3 显示一致；整体折扣与无折扣提交回归不变。

### [BL-0060] 报价单比对视图「导出」按新模型重做（task-0717 二期）
- **优先级**：P2
- **来源**：`dev-docs/task-0717-比对视图/需求说明.md §11.F`（本期明确不做导出）；技术总监澄清定稿 2026-07-18
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：task-0717 比对视图改造把列模型换成「用户配置的页签对比列 + 每料号 3 行块 + 阈值红/橙双色 + 差异行」。旧导出（`ComparisonExportService` / `POST /{id}/comparison/export`，2 行 tag 模型 + 单色高亮）与新模型对不上；本期为聚焦主功能，去掉比对视图上的导出按钮、旧端点保留不动、不再被调用。
- **范围**：按新比对视图模型重做导出——每料号 3 行块（报价/核价/差异）、用户配置列、差异格红/橙双色、单边料号变灰标注；沿用旧导出"前端传已算好的模型、后端 POI 只写值+填色、不重算"思路；前后端各动一处。
- **依赖**：task-0717 比对视图本期功能（前后端）落地。
- **预估规模**：M（3-5 天）
- **验收要点**：导出的 Excel 与页面比对视图逐值/着色一致；单边料号标注正确；不触碰旧 tag 导出回归（`CostingComparisonResourceTest` 仍绿）。
### [BL-0061] 核价（PRICING）侧 handler 料号语义对齐 RECIPE 模型
- **优先级**：P1
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查——本次只改了报价（QUOTE）侧 Q06-Q10/Q13 handler + 报价/核价视图品名兜底，**核价侧导入 handler 本身未动**
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：并发分支 `feat/pricing-sales-part-no` 仍在用旧口径写 `sales_part_no`，核价（PRICING）侧对应投入/材质料号的 handler 尚未按本次报价侧确立的"投入料号=材质料号→恒按材质、原始码、不进 material_customer_map/不登记 material_master"语义对齐，存在报价/核价两侧行为不一致的风险（核价侧材质料号仍可能被当真实组件 resolve+登记，重蹈报价侧修复前的"跨客户串号"覆辙）。
- **范围**：核对核价侧对应 Sheet 的 handler（核价 24 Sheet 体系中来料/自制加工费/组成件其他费用等同构 Sheet），按本次报价侧 Q06-Q10/Q13 的模式做等价改造；需先与 `feat/pricing-sales-part-no` 分支的口径冲突理清（[[cpq-shared-flyway-history-churn]] 类并发风险，见历史记忆 task0708-partno-semantics-delivered）。
- **依赖**：`feat/pricing-sales-part-no` 分支归属 / 合并状态需先明确（当前架构疑似冲突，用户暂搁）。
- **预估规模**：M（3-5 天，含核价侧对应 handler 数量核实 + 测试）。
- **验收要点**：核价侧材质料号导入不再触发跨客户串号类错误；核价侧 unit_price/material_bom_item 等落库 code 为原始材质料号；不进 material_customer_map(PRICING)/material_master。

### [BL-0062] material_customer_map 存量组件级脏占号行清理（RECIPE 模型下已无害）
- **优先级**：P2
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查（repair-2 已确认无需 DELETE 迁移）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：repair-2 + task-0717 落地后，投入/材质料号统一走原始码路径，不再新增 `material_customer_map` 占号行；但历史遗留约 31 行组件级脏占号行（材质料号被当年旧逻辑误登记为客户专属料号映射）仍残留在共享 DB。architect 已证这批行属"只写不读"（RECIPE 模型下渲染/校验均不再读它们），当前对功能无害，故本次不做 DELETE 迁移。
- **范围**：评估是否值得专门清理（存量数据整洁度 vs 改动风险），若清理需先 SELECT 精确圈定这 31 行（区别于合法的真实组件料号映射）再 DELETE，且需在报价/核价双视图跑一遍回归确认零影响。
- **依赖**：无。
- **预估规模**：S（1-2 天，主要成本在圈定+回归验证）。
- **验收要点**：清理后 `material_customer_map` 不再含材质料号误登记行；报价/核价渲染、导入回归无变化。

### [BL-0063] material_recipe 缺库告警：材质料号缺配方时料件名仍空
- **优先级**：P2
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查（14 视图品名兜底 V341/V342 的已知边界情况）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：本次给 10 个报价侧 + 4 个核价侧视图的 `_料件`/组件名列加了 `COALESCE(material_master.name, material_recipe.name)` 兜底，解决了"材质料号不在 material_master 时品名显示为空"的问题——但前提是该材质料号必须在 `material_recipe` 表里已建库。若某材质料号在 `material_recipe` 里也缺失（配方库未覆盖该牌号），兜底仍会落空、料件名列继续显示空白，且当前导入/渲染链路对此**不告警**，只能人工肉眼发现"这行料件名是空的"。
- **范围**：导入或渲染时对"材质料号在 material_bom_item/element_bom_item 出现，但 material_master 与 material_recipe 均无对应行"的情况做告警（导入侧 `recordError`/软告警，或渲染侧标记"缺配方"提示），避免静默空白误导业务判断为"数据正常但恰好没名字"。
- **依赖**：无。
- **预估规模**：S-M（视告警落地在导入侧还是渲染侧而定）。
- **验收要点**：材质料号缺配方时，导入结果或详情页有明确"缺配方/未知材质"提示，而非单纯空白料件名列。

---

## 已完成

### [DONE 2026-07-09] task_0708 导入报价单/核价单落库料号语义纠偏
- **交付**：master 提交 `4ce28a3`(feat) / `8d61cc0`(P24单重) / `257b8cd`(TC-B1) / `8767d87`(文档) / `1f47c9c`(record) + 迁移 `V315`。
- **验收**：测试报告全项 PASS（schema 终态 / 报价核价 material_no=销售料号 / element_bom 撞键一票否决 / is_current 唯一不累加 / 契约零变更 / 前端零改动）；R1/R4 转 [[BL-0038]]。
- **未并入本次（另立项）**：[[BL-0035]] 生产料号 BNF 重定向、[[BL-0036]] mat_part 退役、[[BL-0037]] V6 查询页标签校正。

### [DONE 2026-07-15] task-0712 选配模板 + 报价单选配功能
- **交付**：master `d02b7fe`（origin 已推）。后端6/6（B5 model_config 新表 V330 / B1 选配模板 / B2 选配落库改造六处齐全 / B6 组合工艺收敛 process_master ASSEMBLY / B3 已有产品端点 F005 过滤 / B4 加入链路复用）+ 前端 F1-F5（1:1 复刻原型：选配模板管理页 / 3D模型配置页 / 从已有产品添加 / 选配添加明细表）。
- **缺口补后端**：缺口1 工序 id 契约（加法式方案A，process_no 全链，V336）、缺口2 lookup-fingerprint 3a（确认前实时预览、与提交端同源零副作用）；F5 协同去兜底。
- **Critical 修复**：选配工序首存被 saveDraft 全删全建静默清空的 data-loss bug（gap1 漏跟 process_no，被 grep=ugrep 二进制坑掩盖，见 [[cpq-grep-ugrep-binary-pitfall]]）；V336 迁移改幂等（共享 DB churn 安全）。
- **验收**：后端服务测试全绿（六处齐全/幂等/N+1/指纹零副作用/孤儿 TP10 均独立复跑真绿）；F6 E2E 临时服务跑通——quotation-flow 回归 pass（渲染未破坏 '加载中'=0）+ 选配 SIMPLE/COMPOSITE 冒烟 pass；33 张截图为证。
- **关闭/关联**：[[BL-0031]] 由本次实质解决（工序落 unit_price/自制加工费 + v_composite_child_processes mirror）；收缩迁移转 [[BL-0057]]；F6 发现 2 个既有 bug（QuotationCreateForm stale closure / TC-F1F2 夹具漂移）另立项。

（暂无）
