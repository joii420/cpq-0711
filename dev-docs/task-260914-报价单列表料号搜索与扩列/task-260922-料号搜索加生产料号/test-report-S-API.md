# task-260922 · 分片 S-API · 测试报告

| 项 | 值 |
|---|---|
| 执行人 | `test-engineer` #1（S-API） |
| 覆盖 AC | **AC-2 / 3 / 4 / 5 / 6 / 7 / 8** |
| 执行时段 | 2026-09-22 18:06:43 ~ 18:11:01（PDT），时间盒 40 分钟内完成 |
| 结论 | **7 条 AC 全部通过**：正式结果 FAIL 0 · 未验证 0 · 不稳定 0（run2 有 1 次数据库网络中断造成的 harness 失败，见 §4，已重跑） |
| 写入面 | AC-6：`cpq_db_test` 单事务造数、结束回滚，**零残留已只读复核**；其余：对 `cpq_db_0724` 纯 GET + SELECT（唯一非 GET 是 `POST /api/cpq/auth/login`，admin） |
| 待回收清单 | **无**（未建一次性库） |
| 用例来源 | 只从 `任务.md` §④ AC 原文 / §⑥ 契约 / §⑦ 基准 SQL 派生，未读实现目录 |

## 0. 环境采样与验明正身

| 检查 | 结果 |
|---|---|
| 开跑前 worktree 内 java/maven 进程（按 `/proc/<pid>/cwd` 判，非命令行子串） | **2026-09-22T18:06:43 采样为 0 个**（唯一命中是本次采样命令自身的 bash） |
| AC-6 连的库 | mvn 输出 `FlywayExecutor Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_test` |
| 8322 监听进程 | java pid 2324317，cwd = `<worktree>/cpq-backend`；父进程 maven pid 2324028，cwd 同 |
| 8322 连的库 | 接口全量 `totalElements=210` == `cpq_db_0724` Q0 = 210（首采/复采均 210），≠ `cpq_db_test` 143；启动日志 `jdbc:postgresql://10.177.152.12:5432/cpq_db_0724`、`Listening on: http://localhost:8322` |
| 8322 启动参数 | `-q quarkus:dev -Dquarkus.http.port=8322 -Ddebug=false -Dquarkus.scheduler.enabled=false -Dcpq.price-adjust.startup-recovery.enabled=false -Dquarkus.hibernate-orm.log.sql=true -Dquarkus.hibernate-orm.log.format-sql=false` |
| 收尾 | 核对 cwd 后 `kill` 2324317 与 2324028；**2026-09-22T18:10:53 与 18:11:01 两次采样：worktree 内 java/maven = 0，8322 未监听**（仅剩主工作区 8081 的 60571/60573/60999） |

（启动日志里有 `Schema validation: missing table [mat…]` 的 ERROR —— 与本任务无关的既有现象，mat_* 表在本库从未创建；不影响列表接口。）

## 1. 逐条结果（正式：8322 = worktree 新代码 + `cpq_db_0724`）

所有期望值都是**打接口前后各跑一次基准 SQL**得到的（首采 → 复采），前后一致才判定；下表「基准」列为 `首采→复采`。

| AC | 断言 | 实际值 | 同分钟基准 SQL | 判定 |
|---|---|---|---|---|
| **AC-2** | `partNo=300001&page=0&size=20` 的 `totalElements` = Q1′ | 33 | Q1′(300001) 33→33 | ✅ |
| AC-2 | 同分钟旧口径 Q1 = 0 | — | Q1(300001) 0→0 | ✅ |
| AC-2 | content 非空，条数 = min(20, Q1′)，逐条 ∈ Q1′ 集合 | 20 条，DUP 0，OUT 0 | Q1′ 集合 33 个单号 | ✅ |
| **AC-3** | `partNo=3000` 的 `totalElements` = Q1′(3000) | 48 | Q1′(3000) 48→48 | ✅ |
| AC-3 | 严格大于同时刻 `partNo=300001` 的 `totalElements` | 48 > 33 | Q1′(300001) 33→33 | ✅ |
| AC-3 | content 逐条 ∈ Q1′(3000) 集合 | 20 条，OUT 0 | — | ✅ |
| **AC-4** | `partNo=3120011203` 的 `totalElements` = Q1′（size=20 与 size=500 各一次） | 23 / 23 | Q1′ 23→23 | ✅ |
| AC-4 | ≠ 旧口径 + 生产料号一路之和 | 23 ≠ 46 | S 23→23 · P 23→23 · 交集 I 23→23（I>0，去重确被触发） | ✅ |
| AC-4 | content 单号无重复 | size=20：20 条 DUP 0；size=500：23 条 DUP 0，全 ∈ Q1′ | — | ✅ |
| **AC-5 ③** | 同分钟 Q-漏客户 ≠ ① | 43 ≠ 40（样本有效） | Q-漏客户(300021) 43→43 | ✅ |
| AC-5 ① | `partNo=300021&page=0&size=100` 的 `totalElements` = Q1′ | 40 | Q1′(300021) 40→40 | ✅ |
| AC-5 ② | 返回单号不含 `QT-20260907-0564` / `QT-20260908-0620` / `QT-20260910-0806` | content 40 条（= total，一页装下），三张都不在；三张均为有效样本（CUST-0001、各含 S0004 1 行、本客户下该生产料号绑定 0 行） | — | ✅ |
| AC-5 附 | 返回单号全部 ∈ Q1′ 集合 | 40 条，OUT 0 | — | ✅ |
| **AC-6** ① | `t260922p-prod-xy` → 恰好 {QA} | total=1，ids=[53dd30a8-c155-4334-983e-c954edd2ba4e]（T260922P-QA） | 事务内造数（下同） | ✅ |
| AC-6 ② | `T260922P-PROD-XY` → 恰好 {QA} | total=1，ids=[53dd30a8-…]（QA） | | ✅ |
| AC-6 ③ | `PrOd-X` → 恰好 {QA} | total=1，ids=[53dd30a8-…]（QA） | | ✅ |
| AC-6 ④ | 阳性对照 `T260922P-S1` → {QA, QB} | total=2，ids=[53dd30a8-…（QA）, 96e1a056-b6d7-40f1-97a2-e1d9f844bc91（QB）] | | ✅ |
| AC-6 造数前守卫 | 四个关键字造数前全为 0 | 0 / 0 / 0 / 0 | | ✅ |
| AC-6 对照组（非 AC） | 给 B 也绑同一条后 ① → {QA, QB} | total=2，[T260922P-QA, T260922P-QB] | | ✅ |
| AC-6 零残留 | 回滚后前缀 `T260922P` 四张表行数 | customer 0 / quotation 0 / line_item 0 / ds_quote_material 0（18:07:35 只读复核） | | ✅ |
| **AC-7** | `s0004`：`totalElements` = Q1 = Q1′，逐条 ∈ Q1 集合 | 43（20 条 OUT 0） | Q1 43→43 · Q1′ 43→43 | ✅（run3，见 §4） |
| AC-7 | `a002` | 35（20 条 OUT 0） | Q1 35→35 · Q1′ 35→35 | ✅ |
| AC-7 | `s000` | 49（20 条 OUT 0） | Q1 49→49 · Q1′ 49→49 | ✅ |
| AC-7 | `zzz9999`：HTTP 200、`totalElements=0` | HTTP 200，0 | Q1 0→0 · Q1′ 0→0 | ✅ |
| **AC-8** | ① `partNo=3000&size=20` 与 ② `size=1` SQL 条数相等 | ① 20 行 → 5 条；② 1 行 → 5 条 | —（预热三种请求各 2 次后测） | ✅ |
| AC-8 | ① 比 ③（不带 partNo 的 `size=20`）至多多 1 条 | 5 − 4 = 1 | — | ✅ |
| AC-8 | 不逐条查 `ds_quote_material` | 引用它的语句 ① 1 条 = ② 1 条，同文本重复 0 种；③ 0 条 | — | ✅ |

AC-6 surefire：`tests="2" errors="0" skipped="0" failures="0"`，两个点名方法 `ac6_productionNo_caseInsensitive_partial_and_isolatedByCustomer`、`ac6_control_whenCustomerBAlsoBound_qbBecomesVisible` 均在报告内；报告 mtime `18:07:21.407`，晚于开跑时刻 `18:06:57`；`mvn exit=0`（直接取 `$?`，未经管道）。

AC-8 三个窗口的 SQL 原文（整行、未截断；① ② 的 count 语句带 66 个 `?`，其中 64 个是 `id IN (…)` 的占位符）：
- ① `SELECT DISTINCT li.quotation_id FROM quotation_line_item li JOIN quotation q … JOIN customer c … ds_quote_material …`（引用 `ds_quote_material` 的唯一一条）→ `select count(*) … where 1=1 and (q1_0.id in (… 两路子查询 …) or q1_0.id in (?×64))` → 主查询 → `product_category … in (?)` → `template … in (?…)`
- ③ `select count(*) from quotation q1_0 where 1=1` → 主查询 → `product_category` → `template`

## 2. 反向验证（8081 = master 旧代码，与正式结果分开）

主线批准；只跑 AC-2 / AC-3 / AC-5①（脚本 `run.sh TC-AC2 TC-AC3 TC-AC5`，除登录外只发 GET）。时刻 18:10:3x，8081 验明正身：接口全量 210 == `cpq_db_0724` 210。
master HEAD = `67c73258`（立项文档提交），`master..feat/task-260922-partno-production-search` 无提交 ⇒ 8081 跑的代码不含 B-1。

| 用例 | 预期 | 实际 | 结论 |
|---|---|---|---|
| TC-AC2 | 变红 | `totalElements 0 ≠ Q1′ 33`；content 为空 | ✅ 如期变红 |
| TC-AC3 | 变红 | `3000→0 ≠ Q1′ 48`；`0 不大于 0`；content 为空 | ✅ 如期变红 |
| TC-AC5-① | 变红 | `totalElements 0 ≠ Q1′ 40`；② 因 content 为空判失败 | ✅ 如期变红 |
| TC-AC5-③ | 不适用（只看基准 SQL，与后端无关） | Q-漏客户 43 ≠ Q1′ 40 | 通过（该项在新旧代码下都应通过） |

汇总 `PASS=1 FAIL=7`：唯一的 PASS 就是只看 SQL 的 TC-AC5-③。⇒ AC-2 / AC-3 / AC-5① 在旧代码上确实会失败，所以它们在新代码上的通过不是恒真。
**AC-4、AC-7 在旧代码上也会通过**（主线已认可，这是两条 AC 的性质）：AC-4 防「两路重复计数」，AC-7 防「另两路被清零」，只对**错误的新实现**有区分力。AC-6 的区分力靠造数前守卫、④ 阳性对照和对照组（B 也绑定后 QB 出现）。

## 3. 证据与原始输出

路径前缀：`dev-docs/task-260914-报价单列表料号搜索与扩列/task-260922-料号搜索加生产料号/证据/s-api/`

| 目录 | 内容 |
|---|---|
| `AC-6-run1-20260922-180657/` | `mvn-test.txt`（含 `[AC-6 …]` 打印的 total / ids / 单号）、`TEST-com.cpq.quotation.Task260922ProductionNoSearchTest.xml`、`com.cpq.quotation.Task260922ProductionNoSearchTest.txt`、`零残留复核.txt`、`开跑时刻.txt` |
| `正式-8322-run1-20260922-180828/` | 全量 23 PASS。`console.txt`、各 `TC-*.response.txt`（接口原始响应）、`TC-*.sql.txt`（基准 SQL 原文 + 首采/复采值）、`*-set.txt` / `*-numbers.txt`、`AC-8-{1,2,3}-*.sql.txt`（三次请求 SQL 全文 + 条数统计） |
| `正式-8322-run2-20260922-180931/` | 修复脚本显示问题后全量重跑：21 PASS · 1 FAIL（TC-AC7[s0004] 数据库网络中断，见 §4）；其余结果与 run1 逐项一致，AC-8 仍为 5/5/4 |
| `正式-8322-run3-TC-AC7-20260922-181011/` | 只跑 TC-AC7：7 PASS |
| `反向验证-8081-旧代码/` | §2 的原始输出 |

后端 SQL 全量日志：`/tmp/claude-1000/-home-joii-project-cpq/7abf1578-4e32-4455-9117-2441c6881310/scratchpad/s-api-8322.log`（scratchpad，**不作为证据**；AC-8 需要的窗口已截取进上面的证据目录）。

## 4. 执行中的异常（如实记录）

1. **run2 的 TC-AC7[s0004] 失败，原因是基准 SQL 跑不通，不是业务失败。** 原文：`psql: error: connection to server at "10.177.152.12", port 5432 failed: Network is unreachable`（18:09:40，Q1′ 首采）。同一请求的接口返回 43，复采 Q1′ 为 43。守卫按设计把空值判成硬失败，没有当成 0 放过。
   - 根因：本机到 DB 主机那一瞬间网络不可达。我无法进一步定位；前后 1 秒内同一台主机的其他查询都成功。
   - 处置：run3 单独重跑 TC-AC7，7/7 通过（Q1 43→43、Q1′ 43→43、接口 43）。
   - 这不是「偶发重跑就好」的结案：这次失败与被测代码无关，run3 的通过是对 AC-7 的独立一次测量，run1 的 AC-7 同样 7/7 通过。
2. **脚本显示问题（已修，不影响判定）**：run1 的 AC-8 段 PASS 行前多了一个 `1`。原因是我给窗口③用了 `C_` 前缀，`C_OK=$WIN_OK` 覆盖了 lib.sh 的颜色变量 `C_OK`。判定逻辑不受影响（`$A_OK$B_OK$C_OK` 仍为 `111`）。已改成 `Z_` 前缀，run2 用修后脚本重跑，AC-8 结果不变。
3. `.java.draft` 已按主线指示移到 `cpq-backend/src/test/java/com/cpq/quotation/Task260922ProductionNoSearchTest.java`，包名改为 `com.cpq.quotation`（原稿是 `com.cpq.task260922`）。本目录不再保留 draft。

## 5. 未验证 / 不在本片

- 本片 7 条 AC 没有「未验证」项。
- 关键字含 `%` / `_` 时会被当通配符（后端回报的现状），AC 未覆盖，本片未测。
- AC-1 / 9 / 10 / 11 属 S-UI 片。
