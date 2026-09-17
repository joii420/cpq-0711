# test-report · S-API（task-260916 元素价格支持 9 位小数）

> 分片：**S-API**（后端接口 / 库层，`@QuarkusTest`）· 库：**`cpq_db_test`**（test profile 默认，未用 `DB_NAME=` 覆盖）
> 执行人：test-engineer（S-API）· 执行时间：2026-09-16 23:23 ~ 23:27 PDT · 分支 `feat/task-260916-element-price-scale9`
> 用例依据：`需求文档.md §③`（AC-3 / AC-10 / AC-11 / AC-21 原文）、`test.md §2`（T-API-04~09）、`api.md §2`；**未读实现源码**（RX-1 读取函数定义已获主线批准）

---

## 0. 结论

| 项 | 结果 |
|---|---|
| 认领 AC | **AC-3 ✅ · AC-10 ✅ · AC-11 ✅ · AC-21 ✅**（4/4 通过） |
| 辅助用例 | T-API-04 ✅（含 04 附）· T-API-05 ✅ · T-API-06 ✅ · T-API-07 ✅ · T-API-08 ✅ · AC-10 附 ✅ |
| 还原实验 | **RX-1 ✅**：干预下 AC-3 判据变红（`0.1235`），ROLLBACK 后复验函数仍为 `, 9)`，AC-3 重新变绿 |
| 最终运行 | run3：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，**EXIT=0** |
| 清理 | 每个用例清理后自检残留为 0；运行结束后从库外按前缀全表复查，残留为 0 |
| 未验证 / 未覆盖 | 见 §5 |

---

## 1. 执行过程（三轮，逐轮说明）

**开跑前采样**（`testing.md §4.2.5`）
- 采样时刻：`2026-09-16 23:23:02 PDT`、`23:24:02 PDT`（第 2 轮前复采）
- 结果：「无 java/mvn 进程 cwd 在 $W 内」。判据：遍历 `pgrep -f java` / `pgrep -f mvn`，看 `readlink /proc/<pid>/cwd` 是否以 `$W` 开头。

**草稿移入**：7 个文件**复制**到 `cpq-backend/src/test/java/com/cpq/task260916/`，`diff -r` 两边一致；草稿目录保留副本（T-API-08 的修正两边同步改了）。

| 轮 | 命令 | 起止（PDT） | EXIT | 结果 | 处理 |
|---|---|---|---|---|---|
| run1 | `./mvnw -B clean test -Dtest='com.cpq.task260916.*' -Dsurefire.failIfNoSpecifiedTests=false` | 23:23:19 ~ 23:23:31 | 0 | 🚨 **假绿（空跑）**：surefire 一个用例都没执行，日志里没有任何 `Tests run`，也没有 `target/surefire-reports` 目录；只是 12 秒的编译 | 判为命令问题：`包名.*` 形式的 `-Dtest` 没匹配到类（`target/test-classes/com/cpq/task260916/*.class` 已编出，排除编译问题）。改用路径形式重跑。**这一轮不作证据** |
| run2 | `./mvnw -B clean test '-Dtest=com/cpq/task260916/*Test' -Dsurefire.failIfNoSpecifiedTests=false` | 23:24:09 ~ 23:24:48 | 1 | 12 个跑了 11 个过；T-API-08 失败 | 判为**用例问题**，见 §4 |
| run3 | 同 run2 | 23:25:27 ~ 23:26:10 | **0** | **12/12 通过** | 作为证据 |

- run3 surefire 报告文件的时间戳均在 23:25~23:26（run3 区间内），复制到 `证据/S-API/run3-全绿/`，确认是本轮产物。
- 🚩 **命令偏差请知悉**：主线下达的 `-Dtest='com.cpq.task260916.*'` 在本工程会静默空跑且 EXIT=0，以后引用请改用 `-Dtest='com/cpq/task260916/*Test'`。

---

## 2. 认领 AC 的实际值（摘自 run3 日志，逐字）

### AC-3 · 取价第 10 位舍入（`Ac03CustomerPriceScaleTest.ac3_tenthDigitRounding`）✅
- 前置自证（直插日价回读）：`Cu@2020-03-03 实际=0.123456789500`、`Zn@2020-03-03 实际=0.123456789400`
- 操作：`f_customer_element_price('T916-API-A3-6ED992', 2020-03-03)` 得 `{Cu=0.123456790, Zn=0.123456789}`
- 断言：`AC-3 Cu 实际=0.123456790 期望数值=0.12345679` ✅；`AC-3 Zn 实际=0.123456789 期望数值=0.123456789` ✅

### AC-10 · 直调价格接口按 9 位存（`Ac10PriceWriteScaleTest.ac10_createAndUpdateRoundToScale9`）✅
- POST `price="3.1234567895"` → 201，响应原文片段 `"price":"3.12345679"`；库 `raw_price 实际=3.123456790000` ✅
- PUT `price="4.0000000004"` → 200，响应原文片段 `"price":"4"`；库 `raw_price 实际=4.000000000000` ✅

### AC-11 · 策略系数/加价按 9 位存（`Ac11StrategyWriteScaleTest`）✅
- PUT default `factor="1.2345678915"`、`premium="0.0000000015"` → 200，响应原文片段 `"factor":"1.234567892","premium":"0.000000002"` ✅
- 库中默认策略行数 = 1；`factor 实际=1.234567892000` ✅；`premium 实际=2.000E-9`（这是 JDBC 返回的 BigDecimal 在 Java 里的 toString 写法，数值等于 0.000000002）✅
- GET 回读：`default.factor 实际=1.234567892`、`default.premium 实际=0.000000002` ✅

### AC-21 · 历史快照数字为十进制字符串（`Ac21HistorySnapshotStringTest`）✅
类型用 Jackson 的 `JsonNode` 判断，看的是原始响应体。
- 单价历史：`CREATE snapshot.price 节点类型=STRING 原文="3.123456789"` ✅；`UPDATE snapshot.price 节点类型=STRING 原文="3.123456788"` ✅
- 策略历史：`CREATE snapshot.factor 节点类型=STRING 原文="1.123456789"` ✅、`CREATE snapshot.premium 节点类型=STRING 原文="0.000000002"` ✅；`DELETE snapshot.factor 节点类型=STRING 原文="1.123456789"` ✅、`DELETE snapshot.premium 节点类型=STRING 原文="0.000000002"` ✅
- 这 6 个数字字段的原文里都没有科学计数法 ✅
- 字段名与层级（按存在性检查，均通过）：
  - 分页外层：`content/totalElements/page/size`
  - 单价历史条目 12 键；快照含 `price/currency/priceUnit/fetchStatus`
  - 策略历史条目 8 键；快照含 `sourceName/method/windowNum/windowUnit/factor/premium`
  - 两个接口里 `changes` 都是数组、`snapshot` 都是对象；`windowNum` 为 `null`（LATEST 下，类型检查通过）
- 策略历史原文片段：`"action":"DELETE","changes":[],"snapshot":{"factor":"1.123456789","method":"LATEST","premium":"0.000000002",...,"windowNum":null,...`

---

## 3. 辅助用例实际值（run3，逐字）

| 用例 | 实际值 | 结论 |
|---|---|---|
| T-API-04 | 首次导入结果行 `price 实际=2.000000001`，库 `2.000000001000`；覆盖提示原文 `「原值 2.000000001 → 新值 2.000000002」`；再次导入后库值 `2.000000002000` | ✅ |
| T-API-04 附 | 导入 `Cu=4.0E-10`（Excel 数值单元格）→ 结果行 `"price":"0","result":"FAILED","message":"单价必须大于 0"`；该源该日行数 = 0 | ✅ |
| T-API-05 | `price 变更 = 101.13921 → 101.13922`（本源 Cu 的 UPDATE 条目恰好 1 条）；新增记录快照 `price 实际=101.13921` | ✅ |
| T-API-06 | 新建例外 `factor=1.123456789`、`premium=0.000000002`；修改后 `factor=1.123456788`；最新一条 UPDATE 的 changes 原文 `[{"field":"factor","fieldLabel":"系数","oldValue":"1.123456789","newValue":"1.123456788"}]`，没有 premium 变更；新增记录快照 `factor=1.123456789 / premium=0.000000002`；删除记录快照 `factor=1.123456788` | ✅ |
| T-API-07 | 试算 Cu：`rawValue=101.13921`、`factor=1.2`、`premium=50`、`finalPrice=171.367052`；取价函数 `{Cu=171.367052000}` | ✅ |
| T-API-08 | 调价策略 PUT → 200（`"executeTime":"11:26"`）；参与元素 PUT → 200；generate → 201 `{"versionId":"daeedac9-…","versionNo":"V26091601","baseDate":"2026-09-16",...,"itemCount":1,"budgetStatus":"QUEUED"}`；版本明细原文 `{"elementCode":"Cu",...,"currentPrice":"171.367052","previousPrice":null,...,"noPrice":false,"inheritedFromPrevious":false}`；库 `current_price 实际=171.367052000000` | ✅（前置在私有数据上满足了，**已覆盖**） |
| AC-10 附（api.md §2 第 2 行 / D-8） | POST `0.0000000004` → `400 {"code":400,"message":"price 必须大于 0"}`；该源该日行数 = 0 | ✅ |

---

## 4. 失败归因与用例修改记录

| 轮 | 失败 | 归类 | 根因 | 改了什么 |
|---|---|---|---|---|
| run1 | 空跑（EXIT=0 但 0 个用例） | **环境 / 命令** | `-Dtest='com.cpq.task260916.*'` 这种写法没匹配到任何测试类 | 只改命令，用例不动 |
| run2 | T-API-08：`【T-API-08 前置不满足 ⇒ 报未覆盖】调价策略保存失败：{"code":400,"message":"executeTime 格式非法，应为 HH:mm: 11:24:00"}` | **用例问题**（造数请求格式写错；草稿回报时已标注「executeTime 格式不确定」） | 我传的是 `HH:mm:00`，服务端要求 `HH:mm` | 只改 **前置请求体** 的 `executeTime` 格式：`HH:mm:00` → `HH:mm`（`DateTimeFormatter.ofPattern("HH:mm")`）。**没改任何断言**；T-API-08 的断言（`current_price = 171.367052`）仍取自 test.md T-API-08 与需求文档 §④ 第 3 行。两份副本同步修改 |

- run2 失败时清理照常执行，T-API-08 的清理自检残留为 0（日志 `run2-mvn.log` 第 247~248 行）。
- **与本次改动无关的失败：无**（仅有的一次失败是我自己的用例问题），所以不需要做 A/B 对照。

---

## 5. 未验证 / 未覆盖

- **未验证**：「是否写 `element_daily_price_log`」这类非本片断言没有单独验证。另外按 D-13，价格导入本来就不写历史；T-API-05 用的是手工新建和修改的路径，不受影响。
- **T-API-08 的预算任务**：生成响应里 `budgetStatus` 为 `QUEUED`，等待循环读到的状态是 `null`（版本轨迹里没按 `versionId` 找到该字段，于是没有继续等）。预算任务后来是否执行完，**未验证**；不过运行结束 16 秒后从库外复查，`material_price_update_job` / `material_price_review` / `element_price_version` 的前缀残留都是 0（§6）。
- **AC-21「字段名与层级不变」只检查了「登记过的字段都在」**，不检查「有没有多出新字段」（与主线提供的 8 键 / 12 键清单已逐一对上）。
- 其余认领项：无未覆盖。

---

## 6. 本片写入清单与清理结果

**写入面**（均在 `cpq_db_test`，本轮后缀：run2 `0C8D3B`、run3 `6ED992`）

| 表 | 键 | 由哪些用例写 |
|---|---|---|
| `customer` | `code = T916-API-<标签>-<RUN>` | 全部（SQL 直插） |
| `element_price_source` | `id` = 自造，名字 `T916-API-SRC-<标签>-<RUN>` | 全部（接口） |
| `element_daily_price` / `_log` | `source_id` = 自造源 | AC-3、RX-1（SQL 直插 10 位原值），AC-10、AC-21、T-API-04/05/07/08（接口） |
| `element_price_strategy` / `_log` | `customer_no` = 自造客户 | AC-3、RX-1、AC-11、AC-21、T-API-06/07/08 |
| `customer_price_adjust_strategy`（级联删元素）、`element_price_version`（级联删明细） | `customer_no` = 自造客户 | 仅 T-API-08（run3 删除行数：version 1、策略 1） |
| `material_price_*` 三张表 | `customer_no` = 自造客户 | 兜底清理，实际删除 0 行 |

**清理方式**：每个用例在 finally 里按「自造客户号精确值 / 自造源 id 精确值」用 SQL 删除（主线已批准），删源和删客户时再加前缀条件保护；删完做残留自检。run3 共 12 个用例，每次都打印 `客户侧残留=0（应 0） 源侧残留=0（应 0）`。

**运行结束后库外复查**（`证据/S-API/清理后残留与函数定义复验.txt`，采样 `2026-09-16 23:26:55 PDT`）：

```
 customer | source | strategy | strategy_log | version | pa_strategy | pa_log | job | review | vref
----------+--------+----------+--------------+---------+-------------+--------+-----+--------+------
        0 |      0 |        0 |            0 |       0 |           0 |      0 |   0 |      0 |    0
```

未写 `element` 表：Cu / Zn / Ni 只读引用。**无一次性库、无待回收清单。**

---

## 7. RX-1 红 → 绿原文（run3 日志第 108~131 行）

```
[S-API] f_customer_element_price('T916-API-RX1-6ED992', 2020-03-03) = {Cu=0.123456790, Zn=0.123456789}   ← 干预前基线：绿
[RX-1 干预前] 命中片段#1：ROUND(agg.raw_value * w.factor + w.premium, 9)
[RX-1 干预前] … 命中数=1
[RX-1 干预中] (?<pre>ROUND…, 9) 命中数=0
[RX-1 干预中] 命中片段#1：ROUND(agg.raw_value * w.factor + w.premium, 4)          ← 先证明干预已生效
[RX-1] 干预中取价 = {Cu=0.1235, Zn=0.1235}
[RX-1] ✅ AC-3 断言在干预下变红：AC-3 Cu（日价 0.1234567895，第 10 位 5 进位）：期望数值 0.12345679，实际 0.1235 ==> expected: <0> but was: <-1>
[RX-1] ROLLBACK 已执行
[RX-1 还原后] 命中片段#1：ROUND(agg.raw_value * w.factor + w.premium, 9)
[RX-1 还原后复验] ROUND(…, 9) 命中=1（应 1）
[S-API] f_customer_element_price('T916-API-RX1-6ED992', 2020-03-03) = {Cu=0.123456790, Zn=0.123456789}
[RX-1 还原后复验] AC-3 断言重新为绿
```

- 做法：用独立 JDBC 连接，关掉自动提交；先确认连接地址含 `/cpq_db_test`；事务内设 `lock_timeout 5s` / `statement_timeout 30s`；在事务内 `CREATE OR REPLACE` 后查取价，然后 **ROLLBACK，没有提交**。
- 库外独立复验（23:26:55）：`round_expr_after_rx1 = ROUND(agg.raw_value * w.factor + w.premium, 9)`；另查 `flyway_schema_history` 中 V445 为 `success = t`。

---

## 8. 证据路径（`dev-docs/task-260916-元素价格支持9位小数/证据/S-API/`）

- `run3-mvn.log`：最终全绿一轮的完整 Maven 输出（含上文全部实际值打印）
- `run3-全绿/`：该轮 surefire 报告（`TEST-*.xml`、`*.txt`）
- `run2-mvn.log` + `run2-T-API-08格式失败/`：T-API-08 用例问题的原始失败证据
- `run1-空跑-mvn.log`：`-Dtest` 包名写法空跑的证据
- `清理后残留与函数定义复验.txt`：库外残留复查与函数定义复验

## 9. 文件

- 测试源码：`cpq-backend/src/test/java/com/cpq/task260916/`（`T916ApiBase`、`Ac03CustomerPriceScaleTest`、`Ac10PriceWriteScaleTest`、`Ac11StrategyWriteScaleTest`、`Ac21HistorySnapshotStringTest`、`AuxApi04to07Test`、`AuxApi08VersionGenerateTest`）
- 草稿副本：`测试草稿/S-API/`（与测试源码一致，包含 T-API-08 的修正）
- 本次没有契约形状变更，不需要回写 `main-api.md`（只是数值舍入和快照类型口径变化，见 `api.md §2`）
