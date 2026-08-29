# test · repair-260828 建单物化逐行 DB 往返

> 本文件是**闸门 A 的前置产物**（开工前写完），只写方案与追溯矩阵；执行结果落 `test-report.md`。
> 环境：`cpq-backend` 自动化测试走 **test profile → `10.177.152.12:5432/cpq_db`**（与 dev 库 `cpq_db_0724` **不是同一个库**，写集成测试时注意）；性能实测走 dev server `localhost:8081` + `cpq_db_0724`。

---

## 0. 🚨 本任务测试的首要风险：空验证

本次是**纯性能改动，值必须逐位不变**——这类改动的测试最容易写成「怎么跑都绿」的空验证。

🔒 **每一条自动化用例交付时必须附「还原实验」证据**：把对应的修复改回原样（或用开关关掉），**重跑必须变红**。

- 变红 → 用例真的在测这件事 ✅
- 仍绿 → **该用例是空的**，必须重写，不许作为交付
- 🚫 「首次运行 PASS」**不构成**用例有效的证据

## 0.1 不许做的事（红线，`CLAUDE.md` §3.2）

- 🚫 **不许在共享库上跑会清库 / 重置全局状态的测试**，哪怕写在 `@BeforeAll` 里
- 🚫 不许 `TRUNCATE` / 无 `WHERE` 的 `DELETE`·`UPDATE` / `DROP`
- 测试数据一律**自建自清**，用自己创建的 quotation，不许改动他人在途单据
- 遇到需要不可逆操作才能验证的场景 → **停下报主线**，不许自行执行

---

## 1. 测试用例

### T-1 · componentData 预载不再逐行查库（AC-4）

- **类型**：集成 / SQL 计数断言，扩 `SqlCountNPlusOneGuardTest`
- **前置**：自建两张单，N=300 与 N=1845，**行全部无 componentData**（这是根因 A 的触发形态，必须刻意构造）
- **断言**：③ 段对 `quotation_line_component_data` 的 SELECT 条数**不随 N 线性增长**（每批 ≤2 条）
- **还原实验**：把 B-1 的 `putIfAbsent` 去掉 → SELECT 条数应回到 ≈N → 用例变红

### T-2 · UPDATE 往返次数 = 批数（AC-5）

- **类型**：集成 / JDBC 层计数
- **前置**：1845 行单，chunk=300
- **断言**：③④ 两段各自 `[perf] …-write` 埋点的 `updates`（`executeBatch()` 次数）**= 7**，且 `rows=1845`。**不是 1845**
- **还原实验**：把 B-4 的 detach + 原生写改回托管实体赋值 → `updates` 应变成 1845 量级 → 用例变红
- ⚠️ `pg_stat_statements` 在 `cpq_db_0724` **未安装**（已实测），不要依赖它

### T-3 · 值逐位不变（AC-8, AC-9）

- **类型**：等价测试，参照既有 `CardValuesBatchPersistEquivTest` / `LazyExcelValuesEquivTest` 的手法
- **前置**：同一张导入记录，改动前 / 后各建一次单
- **断言**：逐行比对 `quote_card_values` / `costing_card_values` / `quote_excel_values` / `costing_excel_values` / `subtotal` 的 **md5 完全相同**；单头 `original_amount` / `total_amount` **逐位相同**
- **排除项**：`quote_values_at` / `card_snapshot_at` 时间戳列（本就每次不同，不进 md5）
- **⚠️ 归因纪律**：md5 若不一致，**先做 A/B 同型对比**（同一份输入在干净 master 上跑一遍），确认是本次引入而不是共享库夹具漂移——`quotation-flow` 类 E2E 在共享库上有已知的夹具漂移史

### T-4 · `@DynamicUpdate` 并发防线仍生效（AC-6）

- **类型**：并发集成测试，复刻 `3a69ca97` 的 A/B 判据
- **前置**：一张 DRAFT 单
- **步骤**：并发 warm × saveDraft，两侧改**不同列**（一侧写 `quote_card_values`，另一侧写 `annual_volume`）
- **断言**：`annual_volume` 哨兵**存活 8/8**（历史基线：注解 OFF 时 0/8）
- **为什么必须有这条**：C-丙 新增了一条绕开 ORM 的写通道，必须证明它**没有**把注解防线绕塌。这是本任务风险最高的一条用例
- 🔒 断言的是**状态不变量**（哨兵值等于写入值），不是 delta

### T-5 · 批失败不连坐 + `IS NULL` 自愈（AC-7）

- **类型**：集成
- **步骤**：人为让第 3 批命中 `lock_timeout`（另开会话锁住该批某行）
- **断言**：① 其余 6 批正常提交；② `EnsureResult.failedBatches=1` / `failedRows=300`；③ `warnings` 出现 `卡片值物化部分未完成：1 批（共 300 行）未完成，将在下次打开/轮询时自动补算`（逐字，见 `api.md`）；④ **再次触发只补算那 300 行**，不重算已完成的 1545 行
- **为什么必须有**：B-4 改了落库机制，`IS NULL` 自愈依赖「失败批的行确实还是 NULL」——原生写若部分提交就会破坏这条语义

### T-6 · 单行写路径不受影响（AC-10）

- **类型**：集成 + UI 验证
- **断言**：`assignQuoteCardValues(li, json, quotationId)` 单行重载（`preloadedCd == null`）**仍走 fallback 查库并正确覆盖** `cd.subtotal`；编辑失焦 → 切走切回 → 刷新后值持久
- **还原实验**：若 B-1 误删了 fallback → 该用例变红

### T-7 · 有 componentData 的行不回归（AC-11）

- **类型**：集成
- **前置**：一张 componentData **非空**的单（与 T-1 的形态相反）
- **断言**：各页签 `cd.subtotal` 被正确覆盖；`[subtotal-single-source]` 计数日志与改动前一致
- **为什么必须有**：B-1 只补空列表，非空分支**一个字都不该变**。这条专门拦「修 A 时顺手改坏了非空路径」

### T-8 · 边界：0 行 / 1 行（AC-12）

- **断言**：③④ 对 0 行与 1 行的单不抛异常；`materialize-status` 返 `done=true`（`total=0` 也算 done）；原生批量写在**空批**时不发语句

### T-9 · 性能实测（AC-1, AC-2, AC-3）

- **类型**：**受控人工实测**（非自动化断言——共享 dev server 上的耗时受他人负载影响，写成 CI 断言会变成不稳定失败源）
- **步骤**：dev server 上从基础数据导入建 1845 行单，抓 `[create-quotation-timing]` / `[ensure-cardvalues-batch]` / `[lazy-excel]` 三处日志原文
- **判据**：总计 ≤ 20000ms、③ ≤ 12000ms、④ ≤ 8000ms、每批 ≤ 2000ms
- **对照**：与 `证据/埋点原文-四步与分批耗时.md` 的基线（①506 ②238 ③71362 ④33800 总计 105906ms）同表并列
- ⚠️ **实测前先确认 8081 连的是哪个库**：比对 `GET /api/cpq/quotations?page=1&size=1` 的 `totalElements` 与 `SELECT count(*) FROM quotation`

---

## 2. AC 可追溯矩阵

| AC | 实现认领 | 测试认领 |
|---|---|---|
| AC-1 总耗时 ≤20s | B-2, B-3, B-4 | T-9 |
| AC-2 ③ 每批 ≤2s | B-2, B-4 | T-9 |
| AC-3 ④ ≤8s | B-5 | T-9 |
| AC-4 componentData SELECT 与 N 无关 | B-1 | T-1 |
| AC-5 UPDATE 往返 = 批数 | B-4, B-5, B-6 | T-2 |
| AC-6 `@DynamicUpdate` 防线 8/8 | B-4（detach 纪律 + 不动注解） | **T-4** |
| AC-7 批失败不连坐 + 自愈 | B-4 | T-5 |
| AC-8 卡片值/Excel 值 md5 不变 | B-4, B-5 | T-3 |
| AC-9 单头总额逐位不变 | B-2, B-3 | T-3 |
| AC-10 单行写路径不变 | B-1（保留 fallback） | T-6 |
| AC-11 有 componentData 的行不回归 | B-1 | T-7 |
| AC-12 空/极小单边界 | B-4, B-5 | T-8 |
| AC-13 后端自检（401 / 无迁移） | B-7 | 主线亲验复核 |
| AC-14 N+1 自检声明 | B-7 | 主线亲验复核 |

**双向覆盖检查**：
- 正向：AC-1~AC-14 **每条都有** `B-x` 认领且有测试用例 ✅
- 反向：B-1~B-7 **每项都指回**至少一条 AC ✅（B-1→AC-4/10/11；B-2→AC-1/2/9；B-3→AC-1/9；B-4→AC-1/2/5/6/7/8/12；B-5→AC-3/5/8/12；B-6→AC-5；B-7→AC-13/14）

## 3. 回归范围（不在 AC 内，但必须不坏）

- `QuotationService.submit` 的两处 `ensureCardValues` 调用（走同一条 ③ 路径，是 B-15 明确点名的敏感调用点）
- `refresh-card-snapshot`（另一条路径，本次不碰，但共用 `assignQuoteCardValues` 收敛点）
- 导出 Excel / 比对视图 / 详情页（读 `*_excel_values`，B-5 改了它的写法）
- 🚫 **本次不跑 E2E**：不涉及前端改动、不涉及 `field_type` / driver expansion / 模板 schema 变更 → 未命中 `E2E测试方法.md` 的强制触发条件。若开发中发现改动越界到渲染层，**停下报主线重新评估**
