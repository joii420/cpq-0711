# S-BE 阶段一回报（2026-09-18，测试工程师 · 只写用例未执行）

> 按 `subagents.md §1⑥` 先落盘再分析；数值、路径、未验证标注原样保留。

## 执行状态
- 21 条 AC 的用例已写，**未运行**；未跑 `./mvnw`；自述未读 `src/main/java` 与 `cpq-frontend/src`；未 commit。
- 纯编译检查：`javac` 对当前 `target/classes` 编译本片 20 个源文件（14 支撑 + 6 测试类），输出只写 scratch，**退出码 0，产出 30 个 class**（原文：「这只说明能编译，不代表能跑通」）。

## 待主线裁决（原文 5 条）
1. **大单造法偏离 test.md §3.1**：`QT-20260909-0629` 挂 CUST-0004，副本仍挂 CUST-0004 会写共享客户数据；改挂自建客户会改变按客户取数的渲染 ⇒ 改用 SQL **合成大单**（同一模板，12 / 1000 / 1200 / 1845 行，每行约 4 kB，1200 行约 5 MB），升版后小计有独立期望值 `28894.734567890123`（本期价 + 固定额）。代价：非真实单据结构，真实结构由亲验覆盖。
2. **AC-19 缺改动前基线**：需在 master 代码上跑一次生成 `AC-19/fingerprint-master.json`；方法：把 `Rp0918aProfile / Rp0918aDb / Rp0918aFixture / Rp0918aApi / Rp0918aSnapshots / Rp0918aEvidence / Ac260918T19SmallQuoteRegressionTest` 拷到干净 master 检出，带 `-Drp0918a.ac19.label=master -Drp0918a.evidenceDir=<本任务 证据/测试/S-BE>` 跑；无基线时 AC-19 直接失败（不做成跳过）。
3. **AC-25 默认失败，要主线点头**：`runOnStartup()` 无 id 参数，若实现没守住开关会处理 `cpq_db_test` 里所有人的执行中批次与待处理版本；已加闸，只有 `-Drp0918a.allowRunOnStartup=true` 才真正调用。
4. **AC-17 契约歧义**：main-api 审核列表 / 详情字段均无 `budgetError`；用例严格断言库列 `budget_error`，接口有该字段才顺带比对并记证据。
5. 本片 profile 关闭了定时调度器；若预算管道依赖调度器，用 `-Drp0918a.schedulerEnabled=true` 打开并回报。

## AC → 用例（摘录）
| AC | 用例 | 可观测断言 |
|---|---|---|
| AC-3 | `Ac260918T03T05ChangeRateStorageTest.tBe3_ac3_…_rise`；`…_boundary_fall_…` | `change_rate` = 0.000000034611 / -0.000000034611 |
| AC-5 | `…tBe5_ac5_…` | 历史版本 `change_rate` 仍为 `0.000035000000`，明细整行 md5 不变；阳性对照：新版按 12 位且与 6 位舍入可区分 |
| AC-6 | `Ac260918LargeQuoteSequenceTest.t06_t07_…` | 1200 行，抽屉 200 且首次 ≤5000 ms；`adjustedComputed=true`，`quoteSubtotalAdjusted` = 期望值；`elementImpactTotal` 数值 |
| AC-7 | `t06_t07_…` + `t07b_…` | 抽屉前后版本记录行数 / `last_updated_at`、全行 subtotal + quote_card_values md5 不变（+页签数据、冻结结构）；阳性对照 |
| AC-8 | `t08_t20_…` | 注入超时造失败明细 → 抽屉读 X → 单条重试 202 → 成功 ≤1.5×T₀ → 银价 28893.5、小计 == X → 1 s 后复读不变 |
| AC-9 | `t09_t11_t12_…` | 7 条各在执行开始被探到一次 `running=1`；全部成功；(耗时÷7) ≤1.5×T₀（扣探测耗时）；本期记录 1 行、已升版料号 8 个且顺序保持；`kind=CURRENT` 日志恰 1 行、写快照调用恰 1 次 |
| AC-10 | `t10_…` | 6 条成功；(耗时÷6) ≤1.5×T₀；两单各 1 行 CURRENT；1845 行单初版 + 本期各 1 行编号不同；1200 行单本期仍 1 行追加 3 料号 |
| AC-11 | `t09_t11_t12_…` | 随机 3 行未通过行 4 列 md5 不变（打印种子）+ 其余行聚合 md5 不变；通过行小计 = X = 期望值 |
| AC-12 | `t09_t11_t12_…` | 快照三列与 Java 侧独立计算解析后逐项相等；边界行：卡片值空→null、无页签数据→{}、component_id 空→键 "null"；被升版行 = 升版后卡片值 |
| AC-13 | `Ac260918T13SqlCountIndependentOfLinesTest.tBe13_…` | 12 / 1000 行同料号：试算、首次正式、第二次正式三组 `sql=` 两两相等；`lines=` 12 / 1000；`sql>0` 且正式 > 试算；revision-write ≤1000 ms |
| AC-14 | `Ac260918JobFailureTest.tBe14_…` | 注入分组写快照失败 → 2 条 FAILED / `REVISION_WRITE_FAILED` / 固定文案，价格小计已更新 → 撤注入后单条重试成功、值不变、本期记录含料号、计数一致 |
| AC-15 | `JobFailureTest.tBe15_…`（+ t08 超时形态） | RollbackException / SQLState 57014 → `EXECUTION_TIMEOUT` + 文案；其它 → `UNEXPECTED_ERROR` 以 `IllegalStateException` 开头、无包装文案；单条重试路径落库、冲突 1→0 失败 2→3 |
| AC-17 | `Ac260918BudgetTest.tBe17_…` | 注入试算超时 → 审核行 PENDING / FAILED / 「预算试算超时（超过 60 秒）」，接口 `budgetStatus=FAILED`；同版对照料号正常；撤注入重算 202 → READY |
| AC-18 | `BudgetTest.tBe18_…` | V1 预算中（慢试算）生成 V2 → V1 名下 PENDING = 0；停止日志 N>0；V2 未处理 = 0 |
| AC-19 | `Ac260918T19SmallQuoteRegressionTest.tBe19_…` | 12 行各行 subtotal / quote_card_values、`total_amount`、本期快照三列，抹掉随机 id 后指纹与 master 基线逐项相等 |
| AC-20 | `t08_t20_…` | 恰 2 行：初版已定型、本期挂目标版本；编号 = {R<当天>01, R<当天>02}；本期快照该行 = 升版后，初版 = 升版前 |
| AC-21 | `Ac260918StartupRecoveryTest.tBe21_…` | 造现场（SUCCESS 缺快照 / RUNNING / WAITING，更新时间一天前）→ `recoverJobs(本片批次)` → EXECUTION_INTERRUPTED + 文案；本期记录补料号；批次离开执行中、计数一致；对照批次 md5 不变 |
| AC-22 | `JobFailureTest.tBe22_…` | 注入 `AssertionError` → 90 s 内批次 FAILED、3 条明细 FAILED、日志 ERROR 行 |
| AC-23 | `BudgetTest.tBe23_…` | 前 3 已处理 → `resumeBudgets(本片版本)`：前 2 审核行 `updated_at` 不变、前 3 试算 0 次、后 3 各 ≥1 次、未处理 = 0 |
| AC-24 | `JobFailureTest.tBe24_…` | FAILED + CONFLICT 被重跑（执行时 `running=1`）并成功；STALE 状态 / 重试次数不变、从未执行、价格不动 |
| AC-25 | `StartupRecoveryTest.tBe25_…` | 开关 false 调 `runOnStartup()`，10 s 内每 0.5 s 指纹不变；阳性对照：直接调收尾 / 续跑后指纹确实变 |

## 造数与注入（摘要）
- 每个测试类自建随机客户 `RP0918A-<8位hex>`；策略只勾银、指定料号、周期「每月某日 23:59」且避开今天。
- 价差：上期价与目标版本直接插入；需走「生成版本」的用例只给本客户配一条元素取价策略（系数最小正值 1e-12 + 加价抵消，取价函数复核），**全局元素日价不碰**。
- T₀：12 行单预热一次（不计时）→ 另一张 12 行单计时（通过 → 批次离开执行中），并断言该行确已升版。
- 注入：QuarkusMock「默认转交真实实例」的 mock，`upgrade` 按方法名拦截、参数类型解析；`CurrentPeriodRevisionWriter#write` 同理；每个用例断言注入确实触发。
- CONFLICT / STALE / 中断现场用 SQL 改在本片自建明细上。
- 清理：按本轮客户号 / 自建报价单 id 查出 id 再按 id 删；客户号不符本片格式拒绝清理；残留写证据并判失败。
- 证据：`证据/测试/S-BE/<run-时间戳>/`，每轮新目录。

## 其它
- 预算入口 `processMaterial` 包内可见，用例通过反射调用（签名取自既有测试 D39）。
- 用例依赖的前提：批准后再开抽屉仍给出 X；AC-13「正式 > 试算」由 ⑤-2 推出；AC-20 日期同时接受系统时区与 Asia/Shanghai。
- **测试源码已在工作区，后端 test-compile 会一起编译**；若改了 `recoverJobs / resumeBudgets / runOnStartup` 签名，后端构建会被卡住。
- 规避的坑：取价策略 factor 必须 >0；版本号字段 20 位；组件编码全局唯一；每客户同一时间只能一个待处理版本；AC-19 填充串去掉随机盐、未点名随机 id 抹成占位。
- 被 hook 拦下一次 `rm -rf`（清 scratch 编译输出），**未换写法重试**，改编译到新目录。

## 文件
`cpq-backend/src/test/java/com/cpq/priceadjust/ac260918/`：测试类 `Ac260918T03T05ChangeRateStorageTest`、`Ac260918LargeQuoteSequenceTest`、`Ac260918T13SqlCountIndependentOfLinesTest`、`Ac260918JobFailureTest`、`Ac260918BudgetTest`、`Ac260918StartupRecoveryTest`、`Ac260918T19SmallQuoteRegressionTest`；支撑 `Rp0918aProfile`、`Rp0918aDb`、`Rp0918aFixture`、`Rp0918aApi`、`Rp0918aSnapshots`、`Rp0918aEvidence`、`Rp0918aLogCapture`、`DelegatingMock`、`UpgradeInterceptor`、`RevisionWriterInterceptor`

---

## 主线审用例结论与裁决（2026-09-18）

- 审核 a~e：21 条 AC 均有用例 ✅；断言对准 AC 可观测量、多数带阳性对照 ✅；单点 / 序列 / 边界齐全 ✅；数据为自建前缀数据、断言前先断非空 ✅；单片私有写（前缀 `RP0918A`）✅ ⇒ **通过**。
- 裁决：① 合成大单接受（test-report 须注明「合成数据，非真实结构；真实结构由亲验覆盖」）；② AC-19 基线由测试员在主线新建的基线检出 `.claude/worktrees/rp0918-ac19-baseline`（detached @ `958f3502`）生成 `AC-19/fingerprint-master.json`；③ AC-25 批准带 `-Drp0918a.allowRunOnStartup=true` 执行（主线已读实现：`runOnStartup` 首句检查开关，test profile 为 false）；④ AC-17 `budgetError` 契约缺口待用户裁决，现阶段断言库列 `budget_error`；⑤ 关调度器同意。
- 另：必须把 `Rp0918aFixture.java:386` 对 `processMaterial` 的直接调用改为真正的反射（后端报告 worktree `mvnw test` 因此编译不过）；后端提示三条路径的 `upgrade` 重载（批次 4 参 / 预算试算 3 参 / 审核抽屉 5 参），`[perf] quotation=` 为 UUID。
- 状态：准备工作（修编译、AC-19 基线）可做；开发 worktree 正式执行**待后端删除 lz4 后放行**。

## 准备工作回报（2026-09-18，测试工程师）

- **A 反射**：`Rp0918aFixture` 调 `processMaterial` 改为 `getDeclaredMethod(...)` + `setAccessible(true)`；纯 `javac` 对开发 worktree 当前 `target/classes` 编译整个 `ac260918` 包退出码 0（输出只写 scratch，未碰 `target/`）。
- **B AC-19 基线**：`证据/测试/S-BE/AC-19/fingerprint-master.json`（开发 worktree 下）；在 `rp0918-ac19-baseline/cpq-backend/`（`958f3502`）只跑 AC-19，surefire 1 测试 0 失败（本轮时间）。指纹摘要：12 行，`total_amount` = 346725.814814680000；升版行（第 5 行）小计 = 28894.734567890000，其余 = 28893.734567890000；本期快照三列各 12 行，已升版料号仅第 5 行。**A/A**：同代码换标签再跑与基线逐项一致；**证伪**：基线第 5 行小计改 0.000000001 → 在 `$.lines.5.subtotal` 明确失败（用 scratch 证据目录，未覆盖正式证据）。4 次运行后 `cpq_db_test` 本片前缀数据全部 0 行。基线检出保留。
- **自查修正**：夹具期望值原按 12 位精确和（…890123）写，基线显示系统按 **9 位**舍入产品小计（…890000，符合精度契约）⇒ 期望值与种子小计已改 9 位舍入；不改会让 AC-6/8/9/11/14 小计断言误报。首次错误基线留 `baseline-master-20260918-221355/`，**已作废仅追溯**。
- **未验证**：`[perf]` 行里 `quotation=` 的实际形态尚未见到（`[b0-upgrade]` 类日志是单号）；后端回报称 `[perf]` 打 UUID。若不匹配，开跑后先报主线定，不自行改断言。
- 状态：开发 worktree 未跑 maven，等「S-BE 开跑」。

---

## 阶段二执行回报（测试工程师，2026-09-18 22:21 起约 15 分钟）

- 结果：**21 条通过 20 条**；**AC-9 ①（执行中可见）未过**。报告 `test-report-S-BE.md`；证据轮次 `run-20260918-222212-probe13`、`run-20260918-222345`、`run-20260918-223143-r3`、`AC-19/`。`cpq_db_test` 本片 `RP0918A` 前缀 10 类对象残留全部为 0（22:34:50 采样）；无一次性库；未 commit。
- **AC-9 ① 失败原文要点**：执行期间 `running=1` 始终成立，明细分布第 k 次探测为「成功 k / 执行中 1 / 等待 6−k」；但 `GET /jobs/{id}` 的 `success` 整个执行期间为 0，批次结束才汇总 ⇒ 抽屉表头「成功 0 / 等待 6」不动，与明细不一致。AC-24 批量重试时同一现象（执行中表头停在 `failed=3, success=0`）。断言未放宽。
- 核对：`[perf]` 的 `quotation=` 实为报价单 UUID（与后端回报一致）；三条 `upgrade` 路径（批次 4 参 / 预算试算 3 参 / 审核抽屉 5 参）均确认被拦截，每个注入用例断言了注入触发。
- 执行中改用例 3 处（断言值未动）：① AC-17/18 原直接调生成版本服务方法导致预算未启动，改走用户实际入口 `POST /versions/generate`；② AC-15 把「重试次数递增」误写成等待条件，改为等「失败信息按本次异常重新落库」；③ AC-17 按 D-8 改严格断言列表 `budgetError`。
- 观察（未写成断言）：单条重试失败时 `retry_count` 不递增（现有语义只在 CONFLICT 时 +1）；预算只在 HTTP 生成入口启动，直接调服务方法不启动（定时路径未验证）。
- **一次假绿已作废**：通配 `-Dtest='com.cpq.priceadjust.ac260918.*'` 报 BUILD SUCCESS 但无一行 `Tests run`（一个测试都没跑），改逐个点名 7 个测试类重跑。**复跑不要用该通配写法。**

## 主线裁决（2026-09-18）
- AC-9 ① → **实现缺口，属本次范围，回流后端修**：依据 ③ E-4「执行期间看得出正在执行，而不是一直显示等待 / 0%」、AC-16「等待 = 总数 − 已完成 − 执行中」（「已完成」须实时）、用户原始现象 3。修法：批次执行中 `GET /jobs/{id}` 与列表的各计数按明细实时汇总（常数 SQL，`GROUP BY status`），不再只在 finalize 时写。不涉及 AC 变更。
- `retry_count` 现有语义（只在冲突时 +1）不在本次 AC 范围，不改；定时生成路径日志实证会进预算（18:03:53 `triggerType=SCHEDULED` 后紧跟 `onVersionGenerated`），不构成问题。

## 第 4 轮重跑回报（AC-9 修复后，2026-09-18 22:43~22:46）
- 逐个点名两类，断言未改：`Ac260918LargeQuoteSequenceTest` 6/6、`Ac260918JobFailureTest` 4/4，BUILD SUCCESS、mvn 退出码 0；surefire 报告 mtime 22:44:35 / 22:45:32（本轮）。**S-BE 21/21 通过。**
- AC-9 ①：7 次探测表头 `success` 0→1→2→3→4→5→6、`running` 恒 1，「等待 = 总数 − 已完成 − 执行中」与明细实际等待数一致；第 2 次探测原始响应 `"status":"RUNNING","total":7,"success":1,…,"running":1`。
- AC-24：批量重试执行中第 1 次 `failed=0, conflict=1, stale=1, running=1`，第 2 次 `success=1, conflict=0, stale=1, running=1`（不再停在 `failed=3, success=0`）。
- 残留：22:46:05 `cpq_db_test` 的 `RP0918A-` 前缀 10 类对象全部 0。证据 `证据/测试/S-BE/run-20260918-224333-r4-fix/`（含 `mvn-output.log`、`surefire/`）；`test-report-S-BE.md` 结论表改 21/21、§2 补第 4 轮、新增 §9。
- 观察（未成断言，主线已于前节裁决）：单条重试失败 `retry_count` 不增；手动生成版本只有走 `POST /versions/generate` 才启动预算。
