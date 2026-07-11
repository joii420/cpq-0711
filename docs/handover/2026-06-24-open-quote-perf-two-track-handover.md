# 交接文档 — 打开报价单性能优化(两轨:请求次数 + 每次业务逻辑)2026-06-24

> 用途:新会话据此**接续落地**"打开报价单性能"优化。本会话完成了:Phase 1+2 已合并 master、
> 一轮彻底 profile(把首存 145s 拆到分项)、一份**两轨实现计划(权威下一步)**、以及若干架构决策(否掉前端权威重写 + 后台异步核价)。
> 代码状态干净(所有 profile 探针已删/已 revert,未提交)。

---

## 一、一句话现状

- **Phase 1+2 已合并 master `93c7768`**(报价侧批量写 + 报价侧合桶):saveDraft 里 `snapshotQuotation` 108s→12s,**已验证 md5 逐位等价**。
- **但 saveDraft 首存仍 ~145s**:因为真正大头是**逐行 card values(~132s)**,Phase 1+2 **没碰**(我前期误判了主战场,已纠正)。
- **下一步 = 执行两轨计划**:📄 `docs/superpowers/plans/2026-06-24-open-quote-perf-two-track-plan.md`(**先完整读这份,它是实现依据**)。

---

## 二、🎯 下一步要做的事(两轨计划摘要)

唯一权威下一步 = 上面那份 plan。两轨:

**Track A — 前端请求次数**
- A1 gate `usePathFormulaCache` 的 batch-evaluate(报价+核价,有快照传空集);**前置:逐字段核对快照模式不依赖 path cache**(LIST_FORMULA BNF fallback,QuotationStep2 :1440)。
- A2 gate `useLinkedExcelRows` 的 batch-evaluate。
- A3 batch-expand/batch-evaluate 加 **AbortController**(消 enrich 重发 + 30s 取消;全工程现无 AbortController)。

**Track B — 后端每次逻辑**
- B1 **memoize computeRows**(assemble 33s:PASS1 小计 + PASS2 结果**每 tab 跑 2 遍**同输入,共用一份)。
- B2 **批量 EM**(模板 parse 一次复用 + compdata 整单 IN,复用已落地 `ConfigureSnapshotService.loadSnapshotRowsByLines`)。
- B3(可选)批量核价 driver expand。

**落地顺序**:A3(最低风险)→ B1 → B2 → A1/A2(先核对再 gate)→ B3 视实测。

---

## 三、🔑 本会话决定性实测(新会话必须知道,别重测/别走回头路)

罗克韦尔大单 `8f0c37a4-8186-4f5e-a9ca-358bd2d9662d`(170 行/77 不同料号,含重复料号,有 BOM 树)= 性能测试基准单。已存小单 `82c8e07a`(77 行,已存过=增量跳过,不适合测首存)。

- **saveDraft 首存 ≈ 145s** = `snapshotQuotation`(报价侧,已优化)12s + 逐行 card values ~132s。
- **card values 88s 分项(实测,探针已删)**:
  - **assemble 33s** ← `FormulaCalculator.computeRows` 每 tab **跑 2 遍**(`assembleTabsWithFormulaResults` PASS1 `computeTabSubtotalsByColumn` + PASS2 `calculate`,均 → computeRows)。B1 治这个。
  - baseRows 25s(driver expand I/O + spine + Jackson `valueToTree` 逐行)
  - **EM 查询 15s**(模板读+解析 ×170 同模板 + compdata ×170)。B2 治这个。批量版实测 0.19s。
  - Excel 渲染 11s(`ExcelViewService.buildLineRowData`)
  - 闭包 1s
  - **公式求值 0.1s**、序列化 0.02s ← **可忽略**
- **🔑 核心认知:慢在"计算周围的管道"(重复 computeRows + EM I/O + Jackson 建节点),不是公式本身。** "纯计算很快"是对的。
- **前端打开实测**:`batch-expand` 已按 `useSnapQuote`/`useSnapCosting` gate(有快照传 `EMPTY_LINEITEMS`,不发)✅;**`batch-evaluate` 未 gate**(`usePathFormulaCache` QuotationStep2 :2731/:3011 + `useLinkedExcelRows` :263)→ 有快照也照发 ⚠️;**全工程无 AbortController**。
- 单卡编辑 ≈ 0.24s/卡(可接受)。

**测量手法(复用)**:写 `@QuarkusTest` 直调 `snapshotService.snapshotQuotation(q, false)` 或逐 `buildCardValues/buildCostingCardValues/buildExcelValues` 计时;先清快照模拟首存(`UPDATE quotation_line_component_data SET snapshot_rows=NULL,row_data=NULL` + `UPDATE quotation_line_item SET quote_card_values=NULL,costing_card_values=NULL,quote_excel_values=NULL,costing_excel_values=NULL`)。**跑完即删探针**。

---

## 四、🧭 架构决策(本会话与用户敲定,别推翻重议)

1. **否掉"前端权威 4 快照重写"**:实测证明它不会更快(CPU 地板搬浏览器只会一样/更慢),却要背**双引擎 + 精度契约(JS number vs Java BigDecimal)+ 信任/篡改**三座山。设计备忘存档 `docs/superpowers/specs/2026-06-24-frontend-authoritative-4snapshot-design.md`(**仅存档,不实施**)。
2. **否掉"后台异步线程预算核价"**:踩 `cpq-expand-layer-not-threadsafe`(9/73 静默错价已回滚)+ saveDraft 全量重建换新 UUID 致后台写旧 line FK 违反(日志已现)。
3. **选定**:后端批量(memoize + 批量 EM)+ 前端 gate batch-evaluate + AbortController + 渲染读快照。**后端权威不变,不碰双引擎/精度契约。**
4. **概念澄清**:**核价侧 ≠ 核价单**。核价侧 = 报价单内的核价视图(per line `costingCardValues`,`buildCostingCardValues`,saveDraft 里算,即那 ~48s);核价单 = 独立单据 `CostingSummary`(主键 hf_part_no+版本,自己的 `compute` 端点/状态机,**不在 saveDraft**)。
5. 渲染口径:已存单应**读 4 快照渲染**(打开 ≈1 次 GET);没算过的单才实时算一次。

---

## 五、🚫 硬约束(违反即作废,先读对应记忆)

1. **禁止并行化** expand/公式/快照层([[cpq-expand-layer-not-threadsafe]],9/73 实证)。B 轨全部单线程。
2. **不改任何业务落库/计算结果**(行数/列值/公式逐位一致);每改动带 **A/B md5 等价**。
3. 前端 gate(A1/A2)**必须先逐字段核对快照模式不依赖 path cache**,否则缺值(AP-31 永久"加载中")。
4. 改渲染取数链路走 **AP-31**;涉及 cache key 走 **AP-37**。
5. 派 agent 后**主线亲验**(测试/DB/Network,agent 会虚报,[[cpq-deliver-agents-overreport]]);worktree 子代理可能提交到 master,验收查 `git branch --contains`。

**开工前必读记忆**(`/home/joii/.claude/projects/-home-joii-project-cpq/memory/`):`cpq-firstsave-real-perf-measurement`(本轮实测总览)、`cpq-expand-layer-not-threadsafe`、`cpq-import-firstsave-perf-bottlenecks`、`cpq-savedraft-incremental-snapshot`、`cpq-deliver-agents-overreport`、`cpq-worktree-maven-test-tree`、`cpq-auto-finish-merge-e2e-cleanup`、`cpq-decimal-display-policy`(若将来碰精度)。

---

## 六、⚙️ 环境与工作流

- 后端 dev server `http://localhost:8081`(Quarkus dev,改 .java 自动热重载;健康 `curl .../api/cpq/components` 期望 **401**)。日志在 `/tmp/cpq-backend-dev.log`(进程 4863 的 stdout)。
- 前端 dev server `http://localhost:5174`。
- 远程 DB:`PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`(`pg_stat_statements` 未启用 → 往返度量用 Hibernate Statistics 或探针计时)。
- **dev server / DB / node_modules 共享**:勿在 worktree 另起 server/装依赖;**勿在主工作区跑 `mvnw compile/test` 扰动 dev server**(本会话曾因此触发瞬时构建失败 500,自愈;教训)。
- 后端测试:`cd <worktree>/cpq-backend && ./mvnw -o test -Dtest=Xxx`;看结果读 `target/surefire-reports/*.txt`(`-q` 吞摘要)。
- 工作流:新功能用隔离 worktree(`superpowers:using-git-worktrees` / EnterWorktree);默认 subagent-driven;完成走安全合并 + 清理 worktree;只 `git add` 本次文件,**严禁 git add -A**(主树有其它会话 WIP:ComponentService.java/UnitConversion.java 等)。

---

## 七、📁 关键文件索引

- 计划(权威下一步):`docs/superpowers/plans/2026-06-24-open-quote-perf-two-track-plan.md`
- 前端权威设计(仅存档,不实施):`docs/superpowers/specs/2026-06-24-frontend-authoritative-4snapshot-design.md`
- 后端:`CardSnapshotService`(`buildCardValues`:565 / `buildCostingCardValues`:631 / `buildExcelValues`:690 / `assembleTabsWithFormulaResults`:825 PASS1:847/PASS2:875)、`FormulaCalculator`(`evaluateExpression`:77 / `calculate`:518 / `computeTabSubtotalsByColumn`:571 / `computeRows`)、`ConfigureSnapshotService`(`snapshotLines` / `loadSnapshotRowsByLines` 已落地 / `writeSnapshotBatch`/`writeRowDataBatch` 已落地)、`QuotationResource.saveDraft`:115。
- 前端:`QuotationStep2.tsx`(:2731/:3011 usePathFormulaCache、:3017 useSnapQuote/useSnapCosting、:3030/:3032 useDriverExpansions gate)、`useDriverExpansions.ts`(:360 batchExpandDriver)、`usePathFormulaCache.ts`(:234)、`useLinkedExcelRows.ts`(:263)、`useCardSnapshots.ts`(:216 读快照渲染)。

> ⚠️ 本会话写的 3 个 docs(plan/spec/本交接)是**主工作区未提交文件**,在磁盘上可直接读;如需入库自行 `git add` 这三个。
</content>
