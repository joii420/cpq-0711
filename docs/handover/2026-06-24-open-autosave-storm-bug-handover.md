# 交接文档 — 打开报价单"自动保存风暴 → 加载失败显示空白"BUG(2026-06-24)

> 用途:新会话据此**接续定位 + 修复**一个真实用户 BUG —— 导入并创建报价单后,**重新打开该报价单显示空白**("后端加载失败,已从本地缓存恢复")。
> 本会话已:完成"打开报价单性能"两轨优化(A3/B1/B2/A1/A2 合并 master、B3 实测后舍),随后用户用真实大单测试暴露此 BUG,已**完成根因定位(代码级实证)**,尚未落地修复。
> 当前 master `0c9f6c0`,工作区干净(本次调查只读未改;调查用的 worktree 已删)。

---

## 一、🚨 待修 BUG(本交接核心)

### 症状(用户实测,两张截图)
1. **导入 → 创建报价单(首存,77 行)→ 重新打开** → 顶部红条 **"后端加载失败,已从本地缓存恢复"** + 产品区 **"还未添加任何产品"**(空白)。订单号 `QT-20260624-1844`,quotationId 实测为 `939e072e-bcef-4230-9eb2-66cb64dbe8e1`。
2. Network 瀑布:`draft`(PUT saveDraft)反复出现,**500 @15.53s / 已取消 @30.09s / 200 @24-25s**;`batch-evaluate` @23.75s、`batch-expand` @14.7s + 多个 3-7s。

### 关键实证数据(已查)
- **DB**:`939e072e` 有 **77 行,77/77 都有 quote_card_values + costing_card_values**(快照齐全)。状态 DRAFT,报价+核价模板都有。→ 静止状态快照是好的,不是首存没存进去。
- **后端日志 `/tmp/cpq-backend-dev.log`**:
  - `[saveDraft-diag] id=939e072e received lineItems=77` **出现 7 次**(顺序,非并发;前端有 `savingRef` 在飞守卫)。
  - `[card-snapshot] buildCostingCardValues failed li=... OptimisticLockException: Unexpected row count (expected 1 but was 0) [update quotation_line_item ...]` **16 次**,trace 到 `QuotationService.saveDraft(QuotationService.java:535)`。
  - 还有 `delete from quotation_line_item ... StaleStateException`。

### ✅ 根因(已代码级确认,**不要再当假设、可直接修**)
**打开报价单触发了"自动保存风暴",占满后端线程池,导致加载请求(getById)超时失败 → 前端退回(空的)本地缓存 → 空白页。**

链条:
1. 打开 → `QuotationWizard.loadQuotation`(:466)→ `quotationService.getById`(GET `/quotations/{id}`)→ `applyQuotationData`(:264)→ `setLineItems(basicItems)`(:300+,**首次**)。
2. 紧接 enrich(`enrichComponentData` 块,:405-462)异步 `setLineItems(prev => ...)`(:425,**第二次**)。
3. **autosave 触发器(:249-259)**:`useEffect([lineItems, quotationId])` 里只要 lineItems 变就 `scheduleAutoSave()`(防抖 1.5s → `autoSaveDraft` → PUT saveDraft)。**唯一守卫 `syncingRef`(:253)只挡"saveDraft 响应回填"那一次**;上面 ①② 两处程序化 setLineItems **都没被守卫** → **打开即触发 autosave**(违反 :470 注释"草稿默认冻结、打开不重刷"的设计意图)。
4. **每次 saveDraft 慢 ~14-30s**:主要慢在核价侧逐行 `expandForPartSet/expand`(本会话 B3 profile 实测该段 ≈14s,见下"B3 实测")。
5. **循环 7 连发**:saveDraft 全量删建 lineItems **换新 UUID**(历史行为)→ 响应回填 + enrich 再 `setLineItems` → payload 变 → `lastSaveRef`(:191)去重失效 → 又触发 autosave → 又 saveDraft …
6. 这些慢请求挤满 Quarkus worker 池 → **getById 排不上 → 前端超时(30s)抛错** → `loadQuotation` catch 走 localStorage 兜底(:475-485)→ 本地缓存为空 → **空白**。并发读写(saveDraft 删建 + 其它请求)引发 OptimisticLock 失败。

### ❗ 仍需确认的一点(不改变根因结论,影响修法侧重)
- **H1 vs H2**:getById 是"被 saveDraft 风暴占满线程才超时"(H2,更可能)还是"它自己就慢"(H1)?
  - 验法:净环境(无风暴)直接测 `QuotationService.getById('939e072e')` 耗时(写个 `@QuarkusTest` 计时,或 curl 带 auth)。<2s = H2(止血只需停风暴);若慢 = H1(getById 自身也要优化,候选:`loadLineItems` 77 行 N+1 / `driftDetectionService.detect` for DRAFT,见 `QuotationService.getById`:134-158)。

---

## 二、🔧 建议修法(止血优先,本会话定的方向)

**核心**:打开 / enrich(程序化、非用户编辑)阶段**不该触发 autosave**;只有**用户真实编辑**才存。

候选方案(择一,需评估):
- **方案 A(语义最正确,稍侵入)**:加 `userEditedRef`,在所有用户编辑入口(handleRowChange / handleInputBlur / 增删产品 / Step3 编辑 / form onValuesChange)置位;autosave effect `if (!userEditedRef.current) return;`。风险:漏标某入口 → 该编辑不 autosave(丢数据),需穷举。
- **方案 B(改 lastSaveRef 初值,最小)**:加载(+enrich settle)后把 `lastSaveRef.current` 初始化为**当前 payload 签名**,使首个 autosave 去重命中→跳过;只有真实编辑改了 payload 才发。**前置必读**:`autoSaveDraft`(:633 起)build 的 payload 到底含哪些字段——若含 enrich 改的 componentData 元数据,则 enrich 会让签名变、方案 B 失效;若 payload 只含用户数据则方案 B 干净。**本会话没读完 :633-692 这段,新会话先读它再定 A/B**。
- **方案 C(扩展 syncingRef,有坑)**:在 ①② 两处程序化 setLineItems 前各置 `syncingRef.current=true`。**坑**:enrich 的 `setLineItems(prev=>...)` 可能 `return prev`(:427 长度不符 / :431 产品不符)→ 不改引用 → effect 不 fire → flag 不被消费 → 泄漏到下次用户编辑被误挡(丢 autosave)。选 C 必须处理"flag 未消费"问题。
- 真正治本还需 **saveDraft 提速**(那 14s 核价 expand,即 B3,本会话因 Bug B 风险舍;若要做需走 architect + [[cpq-expand-layer-not-threadsafe]] 纪律)。但**止血只需停打开时的 autosave**,saveDraft 慢可后续单独治。

**修复后验证铁律**:必须**真实复现用户流程**(导入→建单→重开),不能只靠 rows=0 的 E2E / md5 单测(本会话教训:孤立单元全绿但真实流程炸)。验:重开该单 → 不再有 saveDraft 风暴(Network draft=0 或仅 1 次)→ getById 成功 → 产品正常显示;且**用户编辑仍能 autosave**(改一格 → 1.5s 后有 saveDraft、刷新存活)。

---

## 三、📁 关键文件 / 行号

前端 `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`:
- `scheduleAutoSave`(:237)/ autosave debounce 注册(:236-243)
- **autosave 触发 effect(:249-259)** ← 病灶;`syncingRef` 守卫(:253)
- `lastSaveRef`(:191)/ `syncingRef`(:195)/ `savingRef` 在飞守卫(:213,:636)/ `pendingSaveRef`
- `applyQuotationData`(:264)→ 首次 `setLineItems(basicItems)`(:300+)
- enrich 块 → `setLineItems(prev=>...)`(:425;:427/:431 可能 return prev)
- `loadQuotation`(:466)/ 失败兜底 + "后端加载失败,已从本地缓存恢复"(:475-488)
- `autoSaveDraft`(:633 起,**新会话先完整读 :633-692 看 payload 构成**)

后端:
- `QuotationService.getById`(:134;loadLineItems + populateViewStructures + populateDriftInfo)
- `QuotationService.saveDraft`(:535 处调 buildCostingCardValues,OptimisticLock 源)
- `QuotationResource.saveDraft`(PUT `/{id}/draft`,:114)+ 卡片值循环(:141-154)
- `CardSnapshotService.buildCostingCardValues` / `expandTemplateDriverBaseRows`(核价逐行 expand=慢源)

---

## 四、🧭 背景:本会话已完成的"打开报价单性能"两轨优化(已合并,与本 BUG 基本无关)

权威进度见记忆 `cpq-firstsave-real-perf-measurement`。master `0c9f6c0` 已含(均 A/B 等价 + 评审 + E2E + 主线亲验 + 安全合并):
- **A3** `d703c1f`:batch-expand/evaluate 加 AbortController(消重发/取消)。
- **B1** `05e5fed`:memoize computeRows(assemble 内 PASS1/PASS2 复用,computeRows 省 42%≈14s)。harness `B1ComputeRowsMemoEquivTest`(pin golden `c52532b35660226493b212f6d874e35a`)。
- **B2** `c46e7c7`:批量 EM(模板 parse 一次 + compdata 整单 IN),EM 段 7.5s→0.26s(29×)。harness `B2BatchEmEquivTest`。
- **A1** `2254e7b`:快照模式 gate usePathFormulaCache batch-evaluate(`lineItemsNeedPathCache` 自我保护)。
- **A2** `0c9f6c0`:QUOTE 侧 gate useLinkedExcelRows batch-evaluate。
- **B3 实测后舍**:profile 核价 buildCostingCardValues(罗克韦尔170行)=28.3s = driverComps查询1.5s + **expand-fallback逐行14s(大头)** + buildSpineBaseRows(Jackson)仅30ms(可忽略) + 其余(closure+assemble[B1]+序列化)12.8s;union 一次没命中(白做1.2s)。14s 全是带 lineItemId 维度的 expand,eligibleForBomUnion 安全闸门(Bug B)故意不合桶 → 安全批量=高风险动锁定核心基线 → 舍。
- ⚠️ **诚实保留**:这些是真优化但**没解决本 BUG**;且本会话曾"声称完成却没在真实大单跑端到端"——务必引以为戒。

测试基准单:罗克韦尔 `8f0c37a4-8186-4f5e-a9ca-358bd2d9662d`(170行);本 BUG 复现单 `939e072e-...`(77行,QT-20260624-1844)。

---

## 五、⚙️ 环境 / 工作流 / 硬约束

- 后端 dev `http://localhost:8081`(Quarkus dev,改 .java 自动热重载;健康 `curl .../api/cpq/components` 期望 **401**)。日志 `/tmp/cpq-backend-dev.log`(很大,100MB+,用 grep tail)。
- 前端 dev `http://localhost:5174`(Vite,改 .tsx 自动 HMR)。
- 远程 DB:`PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`(jsonb 列查询用 `::text` 再 LIKE)。
- **dev server / DB / node_modules 共享**:勿在 worktree 另起 server/装依赖;worktree 跑 tsc 需 `ln -s` 主仓 node_modules(gitignored)。**dev server 服务的是主工作区"已合并"代码** → 改→评审→合 master→对 live server 验。
- 工作流:新功能用隔离 worktree(`EnterWorktree`)+ 默认 subagent-driven + 安全合并清理;只 `git add` 本次文件,**严禁 git add -A**(主树有其它会话 WIP)。后端测试在 **worktree 的 cpq-backend** 跑 `./mvnw -o test -Dtest=Xxx`(看 target/surefire-reports/*.txt)。
- 必读记忆:`cpq-firstsave-real-perf-measurement`(本轮进度总览)、`cpq-deliver-agents-overreport`(派 agent 必主线亲验)、`cpq-expand-layer-not-threadsafe`(禁并行/Bug B)、`cpq-savedraft-incremental-snapshot`、`cpq-worktree-maven-test-tree`、`cpq-auto-finish-merge-e2e-cleanup`、`cpq-worktree-subagent-commits-to-master`。
- ⚠️ 调试纪律(systematic-debugging):先根因后修;本 BUG 根因已实证,但修法 A/B/C 选型前**先读完 `autoSaveDraft` :633-692**;修完**必须真实复现用户流程验证**(非 rows=0 E2E)。
