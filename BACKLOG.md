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

---

## P2

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
- **范围**：占位组件加「重算此行」入口 → 调单行重算端点 → 回灌该 line 卡片值 → 切快照；与既有 refresh 端点对齐。
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

---

## 已完成

（暂无）
