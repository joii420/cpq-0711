# test-report · S-BE 分片 · repair-260918 大单升版超时与涨跌率显示

> 执行人：test-engineer（S-BE）　执行时间：2026-09-18 22:21（收到「开跑」）～ 22:35，时间盒 150 分钟内用约 15 分钟
> 环境：worktree `repair/260918-price-adjust-large-quote` 的 `cpq-backend/`，`@QuarkusTest` + test profile → **`cpq_db_test`**
> profile：`Rp0918aProfile`（RBAC 关、`quarkus.scheduler.enabled=false`、`cpq.price-adjust.startup-recovery.enabled=false`）
> 造数前缀 `RP0918A-<8位hex>`；证据根目录 `证据/测试/S-BE/`

⚠️ **大单为合成数据，非真实单据结构；真实结构由主线亲验覆盖。**（1200 / 1845 / 1000 / 12 行合成单，每行约 4 kB 载荷，同一模板：元素页签 + 小计页签。裁决见主线 2026-09-18 回复 1。）

## 1. 结论

| 项 | 结果 |
|---|---|
| 认领 AC | 21 条 |
| 通过 | **21 条**（第 4 轮后；见 §9） |
| 未通过 | 无。AC-9 ① 第 3 轮失败 → 主线判为实现缺口 → 后端修复 → 第 4 轮通过（§9） |
| 本片数据残留 | **0**（22:34:50 采样，10 类对象逐类计数） |
| 一次性库 / 待回收清单 | **无**（只用 `cpq_db_test` 私有数据，未建库） |

## 2. 运行记录（按 surefire 报告 mtime 与自有日志确权）

| 轮次 | 证据目录 | 命令要点 | 结果 |
|---|---|---|---|
| 探测 | `run-20260918-222212-probe13/` | 只跑 T13，核对 `[perf]` 日志形态 | 1/1 通过；`quotation=` **是报价单 UUID**，与后端回报一致 |
| 无效首跑 | （`run-20260918-222345/mvn-output-无效首跑-未匹配测试.log`） | `-Dtest='com.cpq.priceadjust.ac260918.*'` | ⚠️ **BUILD SUCCESS 但一个测试都没跑**（通配符形态没匹配到类）—— 假绿，已作废，改为逐个点名类 |
| 第 2 轮 | `run-20260918-222345/` | 点名 7 个类，带 `-Drp0918a.allowRunOnStartup=true` | 20 个用例 16 过 4 败（3 个为用例问题，已修，见 §4） |
| 第 3 轮 | `run-20260918-223143-r3/` | 只重跑改过的 Budget / JobFailure / LargeQuoteSequence | Budget 3/3、JobFailure 4/4、Sequence 5/6（AC-9 ①） |
| 第 4 轮 | `run-20260918-224333-r4-fix/` | 后端修复表头计数后，点名重跑 LargeQuoteSequence + JobFailure，断言未改 | 10/10 通过（见 §9） |

surefire 摘要（`.txt`）与 maven 原始输出已复制到对应目录的 `surefire/`、`mvn-output.log`。
最终有效结果：T03T05 3/3、T13 1/1、T19 1/1、StartupRecovery 2/2 取第 2 轮；Budget 3/3、JobFailure 4/4、Sequence 5/6 取第 3 轮。

## 3. 逐条 AC

| AC | 用例 | 结果 | 关键实际值 | 证据 |
|---|---|---|---|---|
| AC-3 | `T03T05.tBe3_*` | ✅ | 涨：28892.5→28892.501，库原值 `change_rate=0.000000034611`；跌（边界）：`-0.000000034611` | `run-…222345/AC-03.txt` |
| AC-5 | `T03T05.tBe5_*` | ✅ | 历史版本 `0.000035000000` 生成新版后仍为 `0.000035000000`，明细整行 md5 前后一致；阳性对照：旧版已作废，新版按 12 位算出 `-0.000034575250` | `AC-05.txt` |
| AC-6 | `Sequence.t06_t07_*` | ✅ | 1200 行依据单抽屉首次 **504ms**、再次 476ms，HTTP 200；依据单行 `adjustedComputed=true`、`quoteSubtotalAdjusted=28894.73456789`（= 独立期望）；`elementImpactTotal=1` | `AC-06.txt` |
| AC-7 | `t06_t07_*` + `t07b_*` | ✅ | 单据从未升版时：版本记录 0→0 行，1200 行 subtotal+卡片值 md5、页签数据 md5、冻结结构行数前后一致；已有 2 行版本记录时：两行 `last_updated_at` 与快照 md5 前后一致。阳性对照：抽屉确有 2 次试算；前序升版确实改变了行指纹 | `AC-07.txt` |
| AC-8 | `t08_t20_*` | ✅ | 注入超时造出失败明细（`EXECUTION_TIMEOUT`）→ 抽屉 X=`28894.73456789` → 单条重试 **1102ms**（1.5×T₀=1347ms）成功 → 行银价 28893.5、小计 `28894.734567890000` 与 X 逐位相等 → 1 秒后再读不变 | `AC-08.txt`、`T0.txt`（第 2 轮 T₀=898ms） |
| AC-9 | `t09_t11_t12_*` | ❌ ①；✅ ②③④ | **①** 见下；② 7 条全部成功，每条 548ms（扣探针后）≤ 1.5×T₀=1219.5ms；③ 本期记录 1 行，已升版料号 8 个（原 1 + 本批 7，顺序保持）；④ `kind=CURRENT` 日志恰好 1 行（211ms），写快照调用恰好 1 次 | `run-…223143-r3/AC-09.txt` |
| AC-10 | `t10_*` | ✅ | 6 条明细（两单各 3）全成功，每条 740ms ≤ 1347ms；两单 `kind=CURRENT` 各 1 行；1845 行单初版 `R26091801`（已定型）+ 本期 `R26091802`；1200 行单本期仍 1 行，追加 3 个料号 | `run-…222345/AC-10.txt` |
| AC-11 | `t09_t11_t12_*` | ✅ | 随机 3 行（种子 180997902961241）4 列 md5 逐字节不变；其余全部行聚合 md5 不变；7 行通过后的小计 = 通过前抽屉 X = `28894.73456789` | `run-…223143-r3/AC-11.txt` |
| AC-12 | `t09_t11_t12_*` | ✅ | 本期快照三列与独立计算逐项相等（第一处差异 = 无）；边界：卡片值为空→`null`、无页签→`{}`、component_id 为空→键 `"null"` | `run-…223143-r3/AC-12.txt` |
| AC-13 | `T13.tBe13_*` | ✅ | 12 行 vs 1000 行：试算 sql 24=24，首次正式 28=28，第二次 27=27；正式 > 试算；1000 行单 revision-write 128 / 157 / 163ms（≤1000） | `run-…222345/AC-13.txt` |
| AC-14 | `JobFailure.tBe14_*` | ✅ | 分组写快照注入失败（1 次调用携带 2 个料号）→ 两条明细 `REVISION_WRITE_FAILED` + 固定文案，价格已更新；单条重试成功，小计 / 单价与重试前相同，本期记录 `R26091802` 含该料号 | `run-…223143-r3/AC-14.txt` |
| AC-15 | `JobFailure.tBe15_*` + `t08` | ✅ | Rollback / SQLState 57014 两种形态 → `EXECUTION_TIMEOUT` + 固定文案；其它异常 → `UNEXPECTED_ERROR` `IllegalStateException: boom`（无外层包装文案）；单条重试 → 信息改为 `…boom-retry`；冲突项重试 → FAILED，批次 冲突 1→0、失败 2→3 | `run-…223143-r3/AC-15.txt` |
| AC-17 | `Budget.tBe17_*` | ✅ | B1：PENDING / FAILED / `预算试算超时（超过 60 秒）`；列表 `budgetError` 同原文（D-8 严格断言）；对照 B2：READY、`budgetError=null`；重算 202 → READY | `run-…223143-r3/AC-17.txt` |
| AC-18 | `Budget.tBe18_*` | ✅ | 日志「版本已作废，停止剩余 **7** 个料号」；V1 名下审核只剩 `VOIDED:1`（PENDING=0）；V2 未处理料号 0 | `AC-18.txt` |
| AC-19 | `T19.tBe19_*` | ✅ | 与 master 基线（`958f3502`）指纹逐项一致：12 行 subtotal / 卡片值、整单 total_amount、本期快照三列 | `run-…222345/AC-19.txt`、`AC-19/fingerprint-master.json` |
| AC-20 | `t08_t20_*` | ✅ | 恰好 2 行：`R26091801`（初版，已定型）/ `R26091802`（本期）；本期快照该行 = 升版后卡片值，初版快照该行 = 升版前值 | `run-…222345/AC-20.txt` |
| AC-21 | `StartupRecovery.tBe21_*` | ✅ | 执行中 / 等待 → FAILED + `EXECUTION_INTERRUPTED` + 固定文案；本期记录补写已成功料号；批次 RUNNING→PARTIAL（1 成功 2 失败，执行中 0）；未传入的对照批次 md5 不变 | `AC-21.txt` |
| AC-22 | `JobFailure.tBe22_*` | ✅ | 注入 `AssertionError` → 批次 FAILED，3 条明细全 FAILED；ERROR 日志 3 行（执行入口异常 / 收尾 / 异步派发） | `run-…223143-r3/AC-22.txt` |
| AC-23 | `Budget.tBe23_*` | ✅ | 续跑前未处理 3 → 续跑后 0；前 2 个审核行 `updated_at` 不变；前 3 个料号试算 0 次，后 3 个各 ≥1 次（阳性对照） | `run-…223143-r3/AC-23.txt` |
| AC-24 | `JobFailure.tBe24_*` | ✅ | FAILED、CONFLICT 两条重跑成功，执行时探到 `running=1` 且该条为 RUNNING；STALE 状态与 retryCount 不变、执行 0 次 | `run-…223143-r3/AC-24.txt` |
| AC-25 | `StartupRecovery.tBe25_*` | ✅ | 开关 = false；`runOnStartup()` 后 10 秒内每 0.5 秒核对：批次 / 明细 / 审核 / 指针指纹不变。阳性对照：直接调收尾 + 续跑后指纹都变了（带 `-Drp0918a.allowRunOnStartup=true`，主线已批准） | `run-…222345/AC-25.txt` |

### ❌ AC-9 ① 失败详情（原始输出）

AC-9 ① 原文：「执行过程中进度抽屉出现『执行中 1』，明细逐条从『执行中』变『成功』」。每条明细开始执行的那一刻探测一次：

```
#0 running=1 success=0 total=7 明细分布={WAITING=6, RUNNING=1}            正在执行那条=RUNNING
#1 running=1 success=0 total=7 明细分布={WAITING=5, SUCCESS=1, RUNNING=1} 正在执行那条=RUNNING
#2 running=1 success=0 total=7 明细分布={WAITING=4, SUCCESS=2, RUNNING=1} 正在执行那条=RUNNING
…
#6 running=1 success=0 total=7 明细分布={SUCCESS=6, RUNNING=1}            正在执行那条=RUNNING
```
第 2 次探测时 `GET /jobs/{id}` 的原始响应：
`{"status":"RUNNING","total":7,"success":0,"failed":0,"conflict":0,"stale":0,"skipped":0,"running":1,…}`

- **已满足**：`running=1`；明细确实逐条 RUNNING → SUCCESS。
- **不一致**：批次表头 `success` 执行期间一直是 0，批次结束才汇总。按「等待 = 总数 − 已终态 − 执行中」（问题说明 ⑤-17），抽屉会一直显示「成功 0 / 等待 6」，与明细列表（成功 k / 等待 6−k）对不上。
- **判断**：AC-9 ① 字面（执行中 1、明细逐条变成功）是满足的；不一致出在表头计数执行中不刷新。它算不算 AC-9 的范围、是否要修，由主线裁决。断言保持原样（要求表头 `success` 随明细递增），**没有为迁就实现而放宽**。
- 同一现象 AC-24 也能看到：批量重试执行中，表头仍是 `failed=3, success=0`（AC-24 本身不断言这一项）。

## 4. 执行期对用例的修改（都是用例问题，**断言值一个未改**）

| 用例 | 现象 | 原因 | 改法 |
|---|---|---|---|
| AC-17 / AC-18 | 生成版本后预算一次试算都没发生 | 直接调的是服务方法 `generateVersion`，**生成后启动预算不在服务方法里**（日志只有 `generated version`，没有 `onVersionGenerated`；同一轮 `resumeBudgets` 能正常启动预算） | 改走用户入口 `POST /api/cpq/price-adjust/versions/generate` |
| AC-15 | 等待超时 | 我把「`retry_count` 递增」写进了等待条件，这不是 AC-15 的断言 | 等待条件改为 AC 的可观测量（信息已按本次异常重新落库）；`retry_count` 单独记录（见 §5） |
| AC-9 | 第一个违例就中断，后面的步骤拿不到证据 | 断言顺序问题 | ① 的违例先收集、方法末尾统一断言；探针补记明细状态分布 |
| AC-17 | — | D-8 裁决 | 列表 `budgetError` 改为严格断言（失败项 = 原文，对照项 = null） |

## 5. 观察到但不属于本片 AC 的现象（报主线，未写成断言）

1. **单条重试失败时 `retry_count` 不递增**：AC-15 两次单条重试（FAILED→FAILED、CONFLICT→FAILED）前后都是 0（`run-…223143-r3/AC-15.txt`「观察」行）。AC-24 用到了重试次数，这个口径是否符合预期请主线判断。
2. **手动生成版本的预算只由 HTTP 入口触发**：直接调服务方法不会启动预算。管理端点或定时生成若也直接调服务方法，就可能出现生成了版本却没有预算（未验证定时路径：本 profile 关了调度器）。
3. **表头计数执行中不刷新**：见 §3 AC-9 ①。

## 6. 三条 `upgrade` 路径的拦截（后端提示的核对）

| 路径 | 实际拦到的签名 | 是否触发 |
|---|---|---|
| 批次执行 | `upgrade(UUID,UUID,boolean,PrecomputedTreeRows)`（4 参数，dryRun=false，executor 线程） | ✅ 多次 |
| 预算试算 | `upgrade(UUID,UUID,boolean)`（3 参数，dryRun=true） | ✅ 多次 |
| 审核抽屉 | `upgrade(UUID,UUID,boolean,PrecomputedTreeRows,Map)`（5 参数，dryRun=true） | ✅ 多次 |

证据：各轮 `upgrade-paths.txt`。每个用到注入的用例都断言了「规则确实触发过」。

## 7. AC-19 基线与证伪

- 基线：主线建的检出 `rp0918-ac19-baseline`（`958f3502`，改动前代码），只跑 AC-19，得到 `AC-19/fingerprint-master.json`。
- A/A（同一份 master 代码再跑一次，与基线比较）：逐项一致，说明指纹可重复（`AC-19/fingerprint-master-aa-check.json`）。
- 证伪：把基线第 5 行小计改掉 0.000000001，测试在 `$.lines.5.subtotal` 处硬失败（放在 scratch 证据目录，未覆盖正式证据）。
- 作废：`baseline-master-20260918-221355/` 是期望值口径修正前生成的，仅作追溯（期望值从 12 位精确和改为 9 位结果边界，依据 main-api 精度契约，并经基线实测确认）。

## 8. 清理与残留

每个用例 `finally` 按自建 id 清理。22:34:50 对 `cpq_db_test` 采样：`RP0918A-%` 前缀的客户、报价单、版本、批次、审核、指针、调价策略、元素取价策略、组件、模板**全部为 0**；没有 `cleanup-residue` 证据文件。

## 9. 第 4 轮：AC-9 ① 修复后重跑（2026-09-18 22:43～22:46）

> 起因：第 3 轮 AC-9 ① 失败（批次表头计数执行中不刷新），主线判为实现缺口，后端修复为「批次 RUNNING 时 `GET /jobs/{id}`、`GET /jobs` 的计数按明细实时汇总」。
> 22:43:33 采样 worktree 无 java/mvn 进程。**逐个点名**重跑两个类（不用通配）：
> `-Dtest='com.cpq.priceadjust.ac260918.Ac260918LargeQuoteSequenceTest,com.cpq.priceadjust.ac260918.Ac260918JobFailureTest'`
> **断言一个未改**（AC-9 ① 仍要求表头 `success` 随明细递增）。证据目录：`证据/测试/S-BE/run-20260918-224333-r4-fix/`（新开，不覆盖旧轮），含 `mvn-output.log` 与 `surefire/`。

**结果**：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，mvn 退出码 0。surefire 报告 mtime：JobFailure 22:44:35、LargeQuoteSequence 22:45:32（本轮）。

| AC | 用例 | 第 4 轮 | 关键实际值 |
|---|---|---|---|
| AC-6 | `t06_t07_*` | ✅ | 抽屉首次 429ms / 再次 445ms，`adjustedComputed=true`，`quoteSubtotalAdjusted=28894.73456789` |
| AC-7 | `t06_t07_*` + `t07b_*` | ✅ | 从未升版时版本记录 0→0，行 / 页签指纹前后一致；已有 2 行版本记录时同样不变 |
| AC-8 | `t08_t20_*` | ✅ | T₀=818ms；重试 826ms ≤ 1227ms；银价 28893.5，小计 `28894.734567890000` = X |
| AC-9 | `t09_t11_t12_*` | ✅ **（① 已通过）** | ① 见下；② 每条 563ms ≤ 1227ms；③ 本期 1 行、8 个料号；④ `kind=CURRENT` 恰好 1 行（180ms），写快照调用 1 次 |
| AC-10 | `t10_*` | ✅ | 6 条全成功，每条 724ms；两单 `kind=CURRENT` 各 1 行；1845 行单初版 + 本期各 1 行 |
| AC-11 | `t09_t11_t12_*` | ✅ | 随机 3 行（种子 181676379272534）4 列 md5 不变；7 行小计 = 通过前抽屉 X |
| AC-12 | `t09_t11_t12_*` | ✅ | 快照三列与独立计算第一处差异 = 无；三种边界行口径正确 |
| AC-20 | `t08_t20_*` | ✅ | `R26091801`（初版，已定型）/ `R26091802`（本期） |
| AC-14 | `JobFailure.tBe14_*` | ✅ | `REVISION_WRITE_FAILED` + 固定文案；单条重试成功，本期记录含该料号 |
| AC-15 | `JobFailure.tBe15_*` | ✅ | 超时两形态 → `EXECUTION_TIMEOUT`；其它异常 → `UNEXPECTED_ERROR`；单条重试两条路径均落库并重算计数 |
| AC-22 | `JobFailure.tBe22_*` | ✅ | 注入 `AssertionError` → 批次 FAILED、3 条明细 FAILED、ERROR 日志 3 行 |
| AC-24 | `JobFailure.tBe24_*` | ✅ | FAILED + CONFLICT 重跑成功；STALE 不动 |

**AC-9 ① 探测**（每条明细开始执行时各一次；违例 = 无）：

```
#0 running=1 success=0 total=7 明细分布={WAITING=6, RUNNING=1}            正在执行那条=RUNNING
#1 running=1 success=1 total=7 明细分布={WAITING=5, SUCCESS=1, RUNNING=1} 正在执行那条=RUNNING
#2 running=1 success=2 total=7 明细分布={WAITING=4, SUCCESS=2, RUNNING=1} 正在执行那条=RUNNING
#3 running=1 success=3 total=7 明细分布={WAITING=3, SUCCESS=3, RUNNING=1} 正在执行那条=RUNNING
#4 running=1 success=4 total=7 明细分布={WAITING=2, SUCCESS=4, RUNNING=1} 正在执行那条=RUNNING
#5 running=1 success=5 total=7 明细分布={WAITING=1, SUCCESS=5, RUNNING=1} 正在执行那条=RUNNING
#6 running=1 success=6 total=7 明细分布={SUCCESS=6, RUNNING=1}            正在执行那条=RUNNING
```

执行中 `GET /jobs/{id}` 原始响应（第 2 次探测，第 1 条已成功、第 2 条执行中）：

```json
{"jobId":"0559422f-9838-446c-aa69-f9ca174cb5b3","customerNo":"RP0918A-240938de","versionNo":"RP8A240938de-TGT","triggeredBy":"d1e1147c-a639-4156-aeac-9f938a65ad05","triggeredAt":"2026-09-19T05:45:03.668958Z","status":"RUNNING","total":7,"success":1,"failed":0,"conflict":0,"stale":0,"skipped":0,"running":1,"finishedAt":null,"notified":false}
```

**AC-24 批量重试执行中的表头（核对「不再停在 `failed=3, success=0`」）**：

```
第 1 次探测：{"status":"RUNNING","total":3,"success":0,"failed":0,"conflict":1,"stale":1,"skipped":0,"running":1,…}   ← 原 FAILED 那条正在执行
第 2 次探测：{"status":"RUNNING","total":3,"success":1,"failed":0,"conflict":0,"stale":1,"skipped":0,"running":1,…}   ← 第 1 条已成功，原 CONFLICT 那条正在执行
```
第 3 轮同一时刻为 `failed=3, success=0, conflict=0, stale=0`；第 4 轮表头已与明细实时一致。

**残留**：22:46:05 采样 `cpq_db_test`，`RP0918A-%` 前缀的 10 类对象全部为 0；本轮没有 `cleanup-residue`。

**S-BE 最终**：21 条 AC 全部通过。§5 第 1、2 条观察（单条重试失败时 `retry_count` 不递增；手动生成的预算只由 HTTP 入口触发）仍待主线判断；第 3 条已随本轮修复关闭。
