# test-report-S-1 · 审核列表秒开与按需试算（S-1 离线片）

> 状态：14 条全部执行并通过（问题-1 已修复，第 3 节 HEAD 8aa9f15d 全量重跑 12/12 + AC-16/18 2/2，见 §6）。亲验夹具保留中（§7）。执行明细（命令、原始输出、逐批日志）见 `派工回报/测试S1-执行-v1.md`；用例设计见 `派工回报/测试S1-用例-v1.md`。

## 0. 实际环境

| 项 | 值 |
|---|---|
| 代码 | worktree `feat/task-260920-review-list-lazy-compute`，HEAD **`163fa913`**，工作区另有 3 个测试源文件改动：AC-2 事务超时、AC-27 查询条件、AC-17 计时日志 |
| 库 | `cpq_db_test`（test profile 默认；**没有**用 `DB_NAME=` 覆盖） |
| 运行方式 | `cd cpq-backend && ./mvnw -o test -Dtest=<类>`；Quarkus 测试端口随机（`quarkus.http.test-port=0`），**没有用 8081 / 5174** |
| profile | `T920Profiles.Base`（scheduler 关、启动收尾关、RBAC 关、并发度 = 测试默认 1）/ `Conc3`（并发度 **3**，用例开头断言运行时值 = 3）/ `RbacOn`（RBAC 开）/ `StartupOn`（仅 AC-16/18，门控） |
| 前置采样 | 2026-09-21 19:49:06 PDT：本 worktree 上没有别的 maven；连 `cpq_db_test` 的只有采样用的 psql 那 1 个连接 |
| 基线（AC-22、AC-28） | `git archive 700401fa` 解到 `<scratchpad>/s1base`，有自己的 `target/` |
| 前端 | `cpq-frontend`，`npx vitest run src/pages/pricing/price-adjust-review/t260920S1.test.tsx`（20:10） |
| 克隆时点 | 测试库最新报价单 `created_at` = 2026-09-19 05:04 UTC，作为克隆时点的下界（主线口径） |

**SQL 计数口径声明**：`SqlStatementCounter` 只统计经 Hibernate 预编译的语句；裸 JDBC（`SqlViewExecutor`）不计入。AC-3 的 `sql=` 按这个口径解读。

## 1. 逐条结论

| AC | 结论 | 证据（实际值） |
|---|---|---|
| **AC-2** | ✅ | 六分支夹具（各 2 个料号）：旧判定逐个落在分支树期望上，新旧的进池、推进集合逐个相同。正泰全量（测试库）：范围 **3698** 个料号；基本对比旧/新都是 进池 3698 / 推进 0；变体 P（同一回滚事务内合成 100 个同价 + 100 个变价指针）旧/新都是 进池 3598 / 推进 100。回滚事务耗时 **276 685 ms**（**测试事务超时设为 1800 s，是测试侧设置，不是实现**）；回滚后正泰的审核行、指针、版本、策略参与元素都没变 |
| **AC-3** | ✅（N=6/60，按 D-13） | `[perf] budget-enqueue … N=6 pooled=4 advanced=2 sql=16` 对比 `N=60 pooled=40 advanced=20 sql=16` ⇒ sql 相等 |
| **AC-7**（后端） | ✅ | 后台循环（Q1）和点击即算（Q2）两条路径都是：`FAILED` + budget_error「判断依据单已不在活单范围内」+ 审核状态 PENDING + 指针仍在 vPrev。compute-now 202；`GET /row` 和 `GET /{id}` 的 budgetError 都是同一句原文 |
| **AC-7 ②**（前端） | ✅（部分） | vitest：显示「预算失败」、抽屉打开时 FAILED 行不自动计算、有「重新计算」、失败原因原样带回。**未验证**：悬停看到原因、抽屉里原因文字的显示（SSR 渲染不出浮层） |
| **AC-11 ①** | ✅ | approve 对 [READY, FAILED/QUEUED/COMPUTING] ⇒ 409，`data.code=REVIEW_BUDGET_NOT_READY`，`invalidItems` 只含坏行；更新任务数、指针都不变。impact(r1,r5) 的 `materials[]` = {G-0001, G-0005}，既有字段都在 |
| **AC-11 ②** | ✅（部分） | vitest：串行计算（在途 1，次序 1→2→3）、提交集合 = {1,3}、失败块文案「1 个料号计算失败，不参与本次升版」。**未验证**：失败块在确认框「最上方」的位置、approve 实际请求体 |
| **AC-16** | ✅ | 见 §5（D-12 开跑，20:16） |
| **AC-18** | ✅ | 见 §5 |
| **AC-17** | ✅（第 3 节，问题-1 已修复） | 见 §3（修复前）、§6（修复后） |
| **AC-19** | ✅ | X 的采样状态集合 = {READY}；column_count 0、比对列 0 行、basis 为 null、`updated_at = created_at`；按料号搜得到。阳性对照：普通料号走过 QUEUED→COMPUTING→READY，dryRun 3 次 |
| **AC-22** | ✅ | ① `PriceAdjustJobExecutionService`：13 行新增、0 行删除，全部是取锁/放锁；② 20 个类逐类的 run/f/e/s 与 700401fa 基线**完全相同**（68 个测试，失败 2）。单列、不计入通过：`Ac260918StartupRecoveryTest.tBe25`（门控）、`PriceAdjustJobExecutionServiceBatchFallbackTest`（测试库缺 `costing_bom_tree_config`） |
| **AC-24** | ✅ | 变体 A（改策略、改比对列）：PUT 返回时全部 QUEUED:6；每行 dryRun 恰好 1 次；在途峰值 总 3 / 单张依据单 1；阈值 0.5。变体 B：被挂起行重算 2 次、没有旧配置落库、不重启即收敛、阈值 0.25（日志有 3 条「条件完成未命中」） |
| **AC-25 ①** | ✅ | reject 对三种状态都是 409，与 approve 同一个信封。reason：FAILED 行 =「计算失败(T260920 注入的失败原因)」，QUEUED/COMPUTING 行 =「预算未算完(<状态>)」。阳性对照：只驳回 READY 行 → 200，该行变 REJECTED |
| **AC-25 ②** | ✅ | vitest：两种置灰提示与 AC 原文逐字一致；这两种情况下「通过并升版」可点；全部 READY 时驳回可点 |
| **AC-27 ①** | ✅ | status=PENDING：totalElements 4、notComputedTotal 3；status=VOIDED：totalElements 3、notComputedTotal **0**，返回的行 budgetStatus 仍是 QUEUED（注：首轮不带 status 期望 7 行，是用例前提写错，已改） |
| **AC-27 ②** | ✅ | vitest：已作废行不渲染「未计算 / 计算」；筛选已作废时 15 分钟内轮询 0 次；阳性对照：待处理 + COMPUTING 时有轮询 |
| **AC-28** | ✅ | 新代码：distinct 100/100、两次顺序一致。**还原实验**：同一用例在 700401fa（旧排序）上变红，五页只凑出 99 个不同的行；SQL 预检同样 99/100 |
| **AC-29** | ✅ | ④ 阳性对照 `GET /reviews` 未登录 → 401；① 未登录：两个新端点都 401；② SALES_REP：都 403（①② 该行不变、dryRun 0）；③ PM：202 / 200，随后该行变 READY；两个方法上的注解集合 = {PRICING_MANAGER, SYSTEM_ADMIN}。⑤ 还原实验按裁决交后端 |
| AC-13 预检（主线新增，**不替代** S-2 的正式 AC-13） | ✅ | Va = Vb = Vs 的哈希都是 `cd0b932fed8a5d63`，9 行、「调整后」9 行非空且互不相同；篡改副本后哈希 → `ede99ddcba7b9e7d`。限制：只有「报价·调整后」一列有值 |

## 2. 未覆盖 / 未验证

2. **AC-7 ②、AC-11 ② 的浮层部分：未验证**（悬停看到原因、抽屉里显示原因、失败块在确认框最上方、approve 实际请求体）。仓库没有 jsdom，SSR 渲染不出 antd 的浮层，需要亲验。
3. **前端用例的证伪实验：S-1 没做**（派工禁止改实现）。前端回报里的 E1、E3 作用在同一批被测函数上。
4. AC-3 用 N=6/60：已按 **D-13**（用户 2026-09-21 同意，需求文档 AC-3 与 ⑥ 已同步）。

## 3. 缺陷

### 问题-1：生成新版本要等旧版本「整轮」后台试算跑完，旧版本的试算不会中途停下（AC-17）

【现象】客户 A 版本的后台试算在跑（并发度 3，三个工作线程都在算），A 还剩 12 行「未计算」时，调「立即生成」生成 B。生成请求**直到 A 的 15 行全部算完才返回**：第 2 次跑耗时 4 695 ms，返回时刻比 A 的 `budget-compute … done` 晚 34 ms。随后 A 的 15 行才被作废（全是 `VOIDED/READY`），日志里**没有**「版本已作废，停止剩余 n 个料号」。
【预期】AC-17：「A 的全部待处理行（含 QUEUED）变为已作废；A 的循环停止（日志出现『版本已作废，停止剩余 n 个料号』），每个工作线程都停；B 正常进池」。api.md §2.1 的说法是：生成请求只等「在途的那一次」试算提交。
【复现】
1. `Ac260920Conc3Test#ac17*`（Conc3 profile，并发度 3）；
2. 夹具：3 张依据单 × 5 个料号，A 的每次 dryRun 注入 1.5 s；
3. 等 3 个工作线程都在算 A，再调 `POST /versions/generate`；
4. 观察生成请求何时返回、A 的最终状态和日志。
环境：`cpq_db_test`，HEAD `163fa913`。频率：**2/2 必现**（19:58、20:00）。
【影响】**严重**。按实测速率，正泰全量后台试算约 15~30 分钟（立项期实测）；这期间生成新版本的请求（包括 18:00 的定时版）都会卡住，前端请求 60 秒就超时，而且旧版本会被整轮算完，AC-17 设想的「作废即停」不成立。⚠️ 这个时长是**按本片的时序外推**的，没在正泰上实测。
【证据】
```
[T260920-EVIDENCE AC-17] … 生成 B 请求耗时=4695ms（发出=2026-09-21 20:00:50.458 返回=2026-09-21 20:00:55.153）；三个线程同时在算 A=true …；作废时刻 A 的 QUEUED 行=12；A 分布=VOIDED/READY:15；停止日志=[] …
20:00:55.057 … [perf] budget-compute version=9e106ff4… groups=3 materials=15 workers=3 ms=4801
20:00:55.091 … 旧版 V26092101 作废：… 待处理审核置 VOIDED …
```
日志：`<scratchpad>/s1-b2.log`、`s1-b2-ac17r.log`；证据文件：`证据/测试/S-1/run-20260921-195819/`、`run-20260921-200101/`。
【建议】方向（不下结论）：
- 试算事务对版本行持有的共享锁（`FOR SHARE`），由三个工作线程接力持有，可能**始终没有空档**，生成请求要的排他锁一直排不上（行级共享锁接力导致写锁饥饿）。旧的单线程循环在两次试算之间有空档，所以 repair-260918 的 AC-18 当时通过了。
- 也可能是生成路径在等试算名额 / 依据单锁。
- 可以用 `pg_locks` 在生成请求等待期间采样来确认。本片的 1.5 s 注入会放大这个现象，但三个线程交错持锁在正常负载下同样会发生。

## 4. 待回收清单
无。S-1 没有建一次性库；全部造数在 finally 里按 id 清理，20:11 psql 实查 `T260920-%` / `t260920-%` 残留全部为 0。

## 5. AC-16 / AC-18（D-12 开跑，2026-09-21 20:16 PDT）

- 放行依据：主线 20:14:52 复采与 01:52 一致 ⇒ 发「D-12 开跑」。我在开跑前自己又采了一次（**20:15:56 PDT**，`pgrep`）：只有主仓 8081 开发服务（pid 60571/60573/60999，cwd 主仓 `cpq-backend`，连开发库），外加我自己执行 pgrep 的那个 shell；连 `cpq_db_test` 的只有 1 个连接（我的采样）。
- 命令：`cd cpq-backend && ./mvnw -o test -Dtest='Ac260920StartupResumeTest' -Dt260920.allowRunOnStartup=true`（20:16:05~20:17:04）→ `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
- **AC-18 ✅**：两行「计算中」行，updated_at 拨到 1 天前 → 调 `runOnStartup()`，日志「残留「计算中」审核行 2 条重置为未计算」「待续跑版本 1 个 [夹具版本]」→ **1477 ms** 内收敛，两行都 READY，待处理 4 行，dryRun 2 次（证明确实重新算过）。
- **AC-16 ✅**：每个料号都已有审核行，其中 2 行是 QUEUED → `runOnStartup()` 日志「待续跑版本 1 个 [fd0965c5…]」（选中的就是夹具版本）→ 两行都 READY，dryRun 2 次。反向夹具（全部 READY 的版本）dryRun 0 次，审核行指纹前后相同（`e3a0090e…`）。
- **测试库 `CUST-0002 / V26090101`**：被 **Quarkus 启动时自动那次** `runOnStartup()` 处理掉了（20:16:26，这是 StartupOn profile 开关打开的预期副作用，属于 D-12 批准的范围）。用例里的两次显式调用都没有选中它。
  - 日志：`预算续跑：待续跑版本 1 个 [06656cfc-606c-40ba-9129-68f9d21ce1b4]` → `budget-enqueue customer=CUST-0002 N=1 pooled=1 advanced=0` → `budget-compute … materials=1 ms=1047` → `done: 进池=1 … 试算就绪=1`。
  - 结果（只读 SQL，原始输出在 `证据/测试/S-1/D-12/d12-after.txt`）：**新建了 1 条审核行** `c9c15cab-e62e-4303-ad12-238c4c4f0f04`，料号 `T260907R-XCB-203527`，`PENDING / READY`，column_count 1，依据单 `f7181d6b-…`，created 03:16:27.110 UTC、updated 03:16:27.418 UTC。**没有推进指针**：CUST-0002 的唯一指针 `3120011203 → 8e5893f6…`，updated_at 仍是 2026-08-10，和跑之前一样。
  - 该版本的其它行：跑之前该版本名下审核行是 **0 条**（`d12-before.txt`），所以没有「其它行」可被动到。CUST-0002 其它版本的 10 条审核行，md5 前后都是 `904b3d9ead6032db964f4ee8ad5a7b6d`，没变。
  - 其它夹具外的待处理版本（CUST-0001 / CUST-0729-FV2 / CUST-0729-QA）：10 分钟内被更新的审核行 0 条；在跑的更新批次 0。
- 清理：两个用例的 finally 都打印了 `cleanup`；`T260920-%` 客户、审核行、版本的残留都是 0。
- 证据：`证据/测试/S-1/D-12/`（`d12-before.txt`、`d12-after.txt`、`run-log-excerpt.txt`），完整日志在 `<scratchpad>/s1-d12.log`。
- ⚠️ **留在测试库里的写入**：CUST-0002 的这条新审核行 `c9c15cab…`（PENDING/READY）是 D-12 批准范围内产生的，**我没有删它**（它不是我的夹具，删它要单独报批）。要不要复原，请主线裁决。

## 6. 第 3 节 · 问题-1 修复后全量重跑（HEAD `8aa9f15d`，2026-09-21 23:11~23:31 PDT）

- 采样：**23:11:42**（开跑前）和 **23:22:14**（AC-16/18 开跑前），两次都只有主仓 8081 开发服务加我自己的进程；`cpq_db_test` 上只有 1 个连接（我的采样）。
- 批次 0：`./mvnw -o clean test-compile` → BUILD SUCCESS。
- **全量**：`./mvnw -o test -Dtest='Ac260920*Test,!Ac260920StartupResumeTest'` → **`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS**（9 个类合在一次 maven 里跑）。
- **AC-17 ✅（问题-1 修复验证）**：
  ```
  生成 B 请求耗时=2354ms（发出=23:18:20.002 返回=23:18:22.356）；三个线程同时在算 A=true；作废时刻 A 的 QUEUED 行=12；
  A 分布=VOIDED/COMPUTING:3,VOIDED/QUEUED:9,VOIDED/READY:3；停止日志=[版本已作废，停止剩余 12 个料号]；
  各线程最后一次开始算 A 的时刻 - tB(ms)=[-2393, -2374, -2445]；B 审核行=15 B 未收敛=0
  ```
  - 生成请求 2354 ms，和「单条试算 1.5 s 注入 + 在途那一次」同一量级（后端自测 2053 ms）；修复前是 4695 ms，要等整轮试算跑完。
  - 3 个工作线程在 B 生成后都没有再开始算 A；A 的 15 行全部作废。
  - 说明：A 里有 3 行留在 `VOIDED/COMPUTING`（作废那一刻正在算），按 R-2「budget_status 只在待处理时有意义」，不影响断言。
- **AC-19 归因（后端合跑时的一次失败）**：本轮合跑 1/1 通过。读自己用例的采样循环后确认是**用例的采样窗口问题，不是产品问题**。
  - 原因：退出判断在每轮采样查询**之后**又查了一次行数。如果 X 行恰好在两次查询之间提交，循环会在一次都没采到 X 的情况下退出，于是 `xStates` 为空、断言失败。这和后端描述的现象（行提交后约 0.3 s 结束，一行都没采到）一致；服务端行为正确。
  - 修法（改的是我自己的用例）：退出条件加上 `!xStates.isEmpty()`。这是**结构性修复**，这个失败模式被构造性消除了。修后单独跑 3 次，3/3 通过，采样结果都是 `[READY]`。
- **AC-16/18 ✅**：
  - 开跑前只读采样（`证据/测试/S-1/D-12/第3节-开跑前采样.txt`，23:22:04）：在跑批次 0；4 个待处理版本的「未计算」行都是 0；CUST-0002/V26090101 那个料号上次已经建了审核行（PENDING/READY）。
  - 按 D-12 规则，夹具外没有会被选中的对象 ⇒ 直接跑。
  - 结果：Quarkus 启动时那次日志是「**待续跑版本 0 个 []**」（这次没有碰任何夹具外版本）；AC-18 两行在 1471 ms 内收敛到 READY；AC-16 两行 READY、反向夹具指纹不变 → 2/2 通过。
- **AC-22 ✅**：新代码侧重跑，20 个类逐类的 run/f/e/s 与 `700401fa` 基线 `diff` 完全相同（68 个测试，失败 2，都是单列的那两个）；`PriceAdjustJobExecutionService` 相对基线仍是 13 行新增、0 行删除。
- **前端**：`npx vitest run src/pages/pricing/price-adjust-review` → `Test Files 4 passed (4) · Tests 74 passed (74)`。
- **git status 收尾**：
  - `repair-260918…/S-BE/AC-19/fingerprint-branch.json` 又被 T19 覆盖了，已用 `git show HEAD:… >` 还原，`git diff --quiet` 确认干净（被覆盖的那版另存为 `<scratchpad>/fingerprint-branch.s3-2324-run.json`）。
  - 新出现的 `run-*` 目录（不删）：`S-BE/run-20260921-232407/`；`S-1/run-20260921-231237/`、`231829/`、`231929/`、`232048/`、`232119/`、`232151/`、`232245/`（`S-BE/run-20260921-202701/` 不是本轮产生的）。
  - 工作区里还有别人的已修改文件（e2e spec、原型图、S2 准备、编排日志），我没有碰。

## 7. 主线亲验夹具（**保留中**，等主线验完通知再清）

- 构造方式：`Ac260920AcceptanceFixtureBuilder`（门控 `-Dt260920.buildAcceptanceFixture=true`，只造数、不清理）。a/b/c/d 先走真实生成入口进池并**真实试算**（没有注入延时），再把 a/b/c 改回所需状态（删掉它们自己的比对列）。23:31 建成。
- **客户** `T260920-43482dfe`；**搜索词** `T260920-43482dfe`：待处理的 7 行 ≤ 20，同一页能看到。**PM 账号** `t260920-accept-43482dfe` / `T260920@Accept1`（PRICING_MANAGER）。
- 目标版本 `64a669af-017b-4ac3-8b10-0a938cdfbaea`；已作废版本 `2634ffb5-bcfd-41d0-91fe-761398d187aa`。

| 类 | 料号 → reviewId | 依据单 | 状态 |
|---|---|---|---|
| a | A-0001 → `4647c0d0-d858-469d-8052-10436c040295`；A-0002 → `cb1bb8dd-19c4-4391-81c8-582169150382`；A-0003 → `8a621045-e266-43cf-88e1-6fee068ad532` | `T260920-43482dfe-A`（DRAFT） | PENDING / QUEUED |
| b | B-0001 → `dfcc3d95-abc4-442d-a652-4f8e275d4cb6` | `T260920-43482dfe-B`（**CANCELLED**） | PENDING / QUEUED（点计算会变 FAILED） |
| c | C-0001 → `72914b12-1a45-4f0c-bd93-7a6dcffc03de` | `T260920-43482dfe-C`（CANCELLED） | PENDING / FAILED「判断依据单已不在活单范围内」 |
| d | D-0001 → `f069eda5-51fb-449d-ba73-92da32440510`；D-0002 → `ba1f9aa0-634e-49df-ad92-5d7c340685a4` | `T260920-43482dfe-D`（DRAFT） | PENDING / READY，报价·调整后 28894.73456789，breached_count 1（两行都标红） |
| e | D-0001 同时挂在 `T260920-43482dfe-E`（**SENT**，建于 D 之前，所以依据单仍是 D） | — | — |
| f | F-0001 → `565e3aee-9af7-4582-8151-97b62d1e6084`；F-0002 → `5b7aeb24-bb08-4686-ad18-2fe00a9c1a75` | 无 | **VOIDED / QUEUED**（在「已作废」筛选下看） |

- ⚠️ d 的「标红」来自比对列 `MISSING / 缺核价侧`（这个夹具的模板没有核价侧），不是真的超阈值；「报价·现」为空。确认框里「跌破预警线」这一块按 `breachedMaterials` 出现，但显示的原因是缺数据。
- ⚠️ 亲验时临时后端如果连 `cpq_db_test`，必须带 `-Dcpq.price-adjust.startup-recovery.enabled=false`，否则启动时会把 a/b 的 QUEUED 行续跑算掉。
- 核对：`证据/测试/S-1/亲验夹具/夹具核对-只读SQL.txt`（只读 SQL 原始输出）。
- **清理 SQL（只写不跑）**：`证据/测试/S-1/亲验夹具/清理-T260920-43482dfe.sql`。全部按本夹具的客户号 / 报价单 id / 策略 id / 模板 id / 用户 id 精确限定，覆盖亲验「通过」可能产生的 job、job_item、审核行、比对列、指针、报价单修订记录（`quotation_price_revision`），末尾带残留自检。
