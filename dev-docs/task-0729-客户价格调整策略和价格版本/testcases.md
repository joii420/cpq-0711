# 客户价格调整策略与价格版本 · 测试用例（testcases.md）

> **验收标准**：`需求说明.md` §11.11 的 **70 条验收要点**，唯一权威口径，本文与之冲突以 §11.11（及其上位的 §11.17/§11.15）为准。
> **用例字段**：验收编号 | 归属（后端自测 / 前端自测 / 跨端(后端主导) / 技术总监亲验，按 `backtask.md` §2 三分制，个别条目 backtask/fronttask 汇总表有遗漏，已按实际测试需要补注）| 测试层级（单元测试 / API 测试 / SQL 断言 / E2E(Playwright) / 人工验证，可组合）| 前置数据 | 执行步骤 | 断言（🔒 必须机械可判定）| 证据形式。
> **环境基线**（2026-08-01 实测 `cpq_db_0724`）：仅 **1 个客户**（`CUST-0001` 罗克韦尔，`customer.id=f6d10ef0-04cc-45f3-829c-568c8cce3adf`）/ 27 料号 / 35+ 张单 / 25 个 `line_item`（**全部**挂同一销售料号 `S-3120014539`）/ 5 个模板・3 个系列（罗克韦尔模板1 id=`70f1b149…`/系列`a91209e6…`；模板2 id=`9e2e6ef3…`/系列`61363e67…`；模板3 三个版本 v1.0=`7fd29ac2…`/v1.1=`1badebec…`/v1.2=`0317efe5…`/系列`c7705bcc…`）/ 仅 2 个用户（`admin`=SYSTEM_ADMIN、`test_finance_c87a27ab`=PRICING_MANAGER，**无 SALES_REP/SALES_MANAGER**）。截至本批次编写时，backend 已建好本任务 12 张新表（V368/V369 已 `success=t`）。**多数用例需要先造数**，前置数据栏给出可直接执行的 SQL。
> **本文档分批交付**，本批（第 1 批）覆盖 **#1 ~ #10**。总览表、#11 及以后、覆盖率自查将在后续批次追加。

---

### #1　策略配置：周期四形态 + 执行时刻可配可回读；边界顺延规则

| 归属 | 测试层级 |
|---|---|
| 前端自测（表单可配可回读，`fronttask.md` §12.1）+ 后端自测（顺延算法为核心计算逻辑，虽未列入 `backtask.md` §2.1 清单，按测试必要性独立补测） | API 测试 + 单元测试 |

**前置数据**：客户 `CUST-0001`（现网真实客户，无需新建）。若 `customer_price_adjust_strategy` 尚无该客户记录，`PUT` 时后端须自动创建（`api.md` §1.2）。

**执行步骤**
1. 依次 `PUT /api/cpq/price-adjust/strategies/CUST-0001`，覆盖四种周期（每次都带 `executeTime="09:30"`）：
   a. `cycleType=DAILY`；b. `cycleType=WEEKLY, cycleWeekday=3`；
   c. `cycleType=MONTHLY_DAY, cycleDayOfMonth=31`（边界：小月顺延）；
   d. `cycleType=MONTHLY_NTH_WEEK, cycleNthWeek=5, cycleWeekday=2`（边界：第 5 周不足）。
2. 每次保存后立即 `GET /api/cpq/price-adjust/strategies/CUST-0001`，比对返回值与写入值。
3. 🔄 **返修（2026-08-01）**：定时任务读的是**真实系统时间**，没有可注入"当前日期"的参数，原步骤 3/4"将版本生成的当前日期参数设为 2026-09-30"**不可执行**，已删除。边界规则改走**单元测试为主路径**：
   - **强制要求后端提供一个可注入基准日的纯函数**（如 `CycleScheduleResolver.resolveSlot(CustomerPriceAdjustStrategy strategy, LocalDate refDate) → ZonedDateTime`），不依赖系统时钟、不依赖整条生成链路即可单测。**这是对实现的合理约束，不是测试迁就实现**——顺延规则是纯日期计算，没有理由必须绑定真实时钟才能验证。
   - JUnit 用例 c：`strategy.cycleType=MONTHLY_DAY, cycleDayOfMonth=31`，`refDate=2026-09-01`（9 月），断言 `resolveSlot(...)` 返回的日期 = `2026-09-30`。
   - JUnit 用例 d：`strategy.cycleType=MONTHLY_NTH_WEEK, cycleNthWeek=5, cycleWeekday=2`，`refDate=2026-11-01`，断言返回日期 = `2026-11-24`（11 月只有 4 个星期二：03/10/17/24，无第 5 周则取最后一个）。
   - 额外补 1~2 个非边界用例（如 `cycleType=DAILY` 每天都应返回当天；`cycleType=WEEKLY` 返回本周该星期几）防止边界修复误伤主路径。
4. API 层（步骤 1/2）**只验"四形态可配可回读"**，不再承担边界日期断言——真正生成版本时会不会用对这个日期，由 #4/#6 的整链路测试兜底。

**断言（机械可判定）**
- 🔒 4 组 GET 回读的 `cycleType/cycleWeekday/cycleDayOfMonth/cycleNthWeek/executeTime` 与写入值逐字段相等。
- 🔒 JUnit c：`resolveSlot(strategy, 2026-09-01).toLocalDate() = LocalDate.of(2026,9,30)`。
- 🔒 JUnit d：`resolveSlot(strategy, 2026-11-01).toLocalDate() = LocalDate.of(2026,11,24)`。
- 🔒 若后端确认**没有**抽出独立可测方法：本条**判为不可测**（阻断项），必须先要求后端重构出该方法再继续验收——不接受"退化为集成测试、拿真实系统时间等到 9 月"这种不可控方案。

**证据形式**：4 次 PUT/GET 的 curl 请求与响应 JSON；JUnit 测试报告（`CycleScheduleResolverTest` 或等价类名，含 c/d 两个边界 + 2 个非边界用例的通过记录）。

---

### #2　料号矩阵：四筛选框组合模糊匹配 + 勾选跨页保留 + 客户料号空显示"—"但可勾

| 归属 | 测试层级 |
|---|---|
| 前端自测（`fronttask.md` §12.1） | API 测试 + E2E(Playwright) |

**前置数据**
```sql
-- 确认现网是否已有 customer_product_no 为空的行（用于验证"—"但可勾）
SELECT count(*) FROM material_customer_map WHERE customer_no='CUST-0001' AND system_type='QUOTE' AND customer_product_no IS NULL;
-- 若为 0，构造一行空客户料号（复用真实销售料号 S-3120014539，插入前先确认无唯一键冲突）
INSERT INTO material_customer_map (material_no, customer_no, customer_material_name, customer_product_no, system_type)
VALUES ('S-3120014539','CUST-0001','触头组件测试件',NULL,'QUOTE')
ON CONFLICT DO NOTHING;
```
- 分页需要 ≥21 条候选才能验证"跨页保留"，实测 `material_customer_map` 现网 CUST-0001/QUOTE 记录数需先查（若 <21，追加造若干行，`material_no` 可复用 `material_master` 已有的 27 个料号循环插入不同 `customer_product_no`）。

**执行步骤**
1. `GET .../strategies/CUST-0001/materials?page=1&size=20`，逐一用 `customerPartNo`/`customerMaterialName`/`materialNo`/`materialName` 四个筛选框分别模糊查询，再任选两个组合查询。
2. 第 1 页勾选 3 个料号（含步骤 0 构造的 `customer_product_no=NULL` 那行），翻到第 2 页再勾 2 个，回到第 1 页。
3. 保存（`PUT .../materials`，`materialNos` 传全集）。

**断言（机械可判定）**
- 🔒 四种单筛选 + 1 种组合筛选返回的 `content` 均只含匹配项（逐行核对 `customerPartNo`/`materialNo` 等字段确实命中关键词）。
- 🔒 翻页后返回第 1 页时，之前勾选的 3 项 `selected=true` 状态仍在（前端本地状态或调用 `selectedOnly=true` 复核）。
- 🔒 `customer_product_no=NULL` 的行 `customerPartNo` 返回 `null`，UI 层渲染为 `—`（E2E 截图/DOM 文本断言 `—`），且该行 `selected` 可被置为 `true` 并保存成功（`PUT` 返回 200，`SELECT count(*) FROM customer_price_adjust_material WHERE material_no='S-3120014539'` = 1）。
- 🔒 保存后 5 个勾选项全部落库：`SELECT material_no FROM customer_price_adjust_material WHERE strategy_id=(SELECT id FROM customer_price_adjust_strategy WHERE customer_no='CUST-0001')` 返回集合 = 步骤 2 勾选的 5 个料号。

**证据形式**：4+1 组筛选的 curl 响应；跨页勾选的 E2E 截图（第 1 页勾选态 → 第 2 页 → 回第 1 页勾选态仍在）；保存后 SQL 输出。

---

### #3　🔴 元素矩阵：右侧 10 版价格 + 涨跌幅，两种空值区分，**一次 pivot 完成（无 N+1）**

| 归属 | 测试层级 |
|---|---|
| 后端自测（pivot 查询本身）+ 前端自测（F12 Network 断言无 N+1） | API 测试 + E2E(Playwright) |

**前置数据**
```sql
-- 为 CUST-0001 构造 ≥10 个版本，便于验证"最近 10 版"与"历史不足按实有出列"两种场景
-- （若当前版本数不足 10，先跑 #4/#5 用例生成到 ≥10 个版本；也可直接批量 INSERT element_price_version/_item 模拟历史）
SELECT count(*) FROM element_price_version WHERE customer_no='CUST-0001';
```
- 元素清单勾选 ≥25 个（分页验证），其中至少 1 个元素本期不在清单（`priceState=NOT_IN_LIST`）、至少 1 个在清单但当期无价（`priceState=NO_PRICE`）、至少 1 个已停用（`element.status='INACTIVE'` 但仍在 `customer_price_adjust_element` 清单里）。

**执行步骤**
1. **后端 SQL 日志计数**：开启 Hibernate/JDBC SQL 日志（或临时 `log.level=DEBUG` for `org.hibernate.SQL`），分别请求 `GET .../elements?page=1&size=5` 与 `.../elements?page=1&size=30`（元素行数不同），统计各自这一次 HTTP 请求期间触发的 SQL 语句条数 `N1`、`N2`。
2. **前端 F12 Network 计数**：Playwright 打开屏 1「参与调价元素矩阵」，用 `page.on('request', ...)` 监听整个矩阵加载过程（从进入 Tab 到渲染完成）对 `/api/cpq/price-adjust/strategies/*/elements` 端点的请求次数。
3. 分别在版本数 = 10（够 10 列）与版本数 < 10（如仅 3 个版本）两种客户状态下各查一次，核对两种空值语义。

**断言（机械可判定）**
- 🔒 **无 N+1 判据**：`N1` 与 `N2` 相等（SQL 语句条数不随本页元素行数从 5 变到 30 而线性增长；这是"一次 pivot 查完"的核心证据 —— 若实现成逐元素查询，`N2` 会显著大于 `N1`）。且 `N1`（`N2`）为一个与元素行数、版本列数无关的小常数（如 ≤ 4：1 条 versionColumns + 1 条 pivot + 1 条 count，允许 ±1 误差）。
- 🔒 **F12 Network 判据**：整个矩阵渲染期间对 `.../elements` 端点的请求次数 **恰好 = 1**（不随分页大小或元素数增加而变多次调用）。
- 🔒 版本数 =10 时 `versionColumns.length = 10`；版本数 <10（如 3）时 `versionColumns.length = 3`（按实有出列，非补空到 10）。
- 🔒 `priceState=NOT_IN_LIST` 的元素前端渲染 `—`；`priceState=NO_PRICE` 的渲染`无价`；两者 DOM 文本**不相同**（用 `expect(cell).toHaveText('—')` vs `'无价'` 分别断言，不得混用同一渲染分支）。
- 🔒 已停用元素（`element.status≠ACTIVE`）仍出现在返回的 `content` 中且带 `elementEnabled:false`（不是被过滤掉 404 或缺行）。

**证据形式**：SQL 日志截取（两次请求各自的语句条数与 SQL 文本）；Playwright `page.on('request')` 采集到的 URL 命中列表（导出为测试日志）；两种版本数场景下的响应 JSON。

---

### #4　版本生成（定时）：到周期点 + 执行时刻自动生成；服务重启错过时刻能补跑；同一周期点只生成一次

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | 单元测试 + SQL 断言（⚠️ 进程重启补跑变体见下方"当前环境限制"） |

**前置数据**
- `customer_price_adjust_strategy`：`CUST-0001`，`enabled=true`，`cycleType=DAILY`，`executeTime` 设为**未来 1~2 分钟**（如当前 09:30 则设 `09:32`），`customer_price_adjust_element` 已勾 ≥1 个有价元素（如 `Ag`）。
- 确认当前无 `PENDING` 版本：`SELECT status FROM element_price_version WHERE customer_no='CUST-0001' AND status='PENDING'`（若有先驳回/走 #67 的确认生成流程清空）。

**执行步骤**
1. 保存策略后静候至 `executeTime` 对应的那一分钟过去（`@Scheduled(every="1m")`，D4 每分钟扫描）。
2. 立即（同一分钟内，模拟并发扫描）再手动触发一次内部扫描方法（若测试环境暴露了可重入调用，或等待下一分钟扫描 tick 自然发生），验证**幂等**。
3. **补跑场景**：把 `scheduled_slot` 未来某一时刻的生成故意跳过（例如把 `executeTime` 设为过去 5 分钟前的时刻但此刻策略 `enabled` 才被打开，模拟"服务当时没跑"），下一分钟扫描应立即补上该时刻的版本。

**断言（机械可判定）**
- 🔒 `executeTime` 对应分钟过后：`SELECT count(*) FROM element_price_version WHERE customer_no='CUST-0001' AND scheduled_slot = '<当日 executeTime 对应的 timestamptz>'` **= 1**（不是 0，不是 >1）。
- 🔒 步骤 2 的重复触发后，同一 `scheduled_slot` 下版本数**仍 = 1**（`UNIQUE(customer_no, scheduled_slot)` 拦住重复插入，第二次调用应静默跳过或捕获唯一键冲突，不产生第二条记录、不抛 500）。
- 🔒 补跑场景：版本的 `scheduled_slot` 等于**原定的那个时刻**（不是"补跑发生时的当前时刻"），即幂等键仍按原计划时刻生成，`trigger_type='SCHEDULED'`。
- 🔒 现有 **7 个既有 `@Scheduled` 任务**（`ScheduledTaskService`）均未受影响：检查它们各自最近一次执行未因本次改动而报错（查应用日志无新增异常）。

**证据形式**：SQL 查询输出（`scheduled_slot` 与生成记录数）；应用日志片段（扫描任务触发记录）；`@Scheduled(concurrentExecution=SKIP)` 的代码走查截图（人工确认注解已加）。

**⚠️ 当前环境限制**：真实"进程重启后补跑"需要重启 Quarkus（8081），而 8081 是本 worktree 与其他并发会话**共享**的 dev server，随意重启会打断他人工作（`CLAUDE.md`"worktree 共享约束"）。此子场景改为：**不做真实进程重启**，改用"故意跳过一个 `scheduled_slot`、下次扫描是否补上"的等效构造（如上步骤 3）替代；若需要验证"进程崩溃瞬间"的真实重启行为，需协调一个独立时间窗口单独执行，本回归套件不包含。

---

### #5　🔴 版本生成（手动）：「立即生成一次」与定时走**完全相同**代码路径；同日再次生成流水递增

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，验收 #5 / #67③） | 单元测试 + API 测试 + 人工验证（代码走查） |

**前置数据**：`CUST-0001` 策略 `enabled=true`，元素清单含 `Ag`（有本期价）。确认当前无 `PENDING` 版本（同 #4）。

**执行步骤（三条互相佐证的证据链，因为"是否同一段代码"本质是白盒问题，纯黑盒行为测不出"代码相不相同"，必须结合代码走查）**
1. **代码走查（人工验证，权威证据）**：读 `VersionGenerationService`（或等价服务类）源码，确认：
   - `POST /price-adjust/versions/generate`（手动，`PriceAdjustStrategyResource`）与 `@Scheduled` 定时扫描方法（`VersionGenerationService` 内部）**都只调用同一个私有/包内方法**（如 `generateVersion(customerNo, triggerType, scheduledSlot)`），两个入口内部**没有第二套独立实现**（没有各自拼版本号、各自处理无价元素继承等重复代码）。
   - 记录该方法的文件路径 + 行号，以及两个调用点各自的行号，作为走查证据。
2. **黑盒行为一致性**（佐证）：分别通过"手动触发"与"定时触发（等下一分钟自然扫描，或 #4 场景里已验证过的定时通道）"各生成一版，断言两次生成的版本在**除 `trigger_type`/`scheduled_slot`/`version_no` 外的处理规则完全一致**：都正确处理了无价元素继承（若清单里有本期无价、有上一版价的元素）、都把上一版转 `SUPERSEDED`、都对该客户名下未完成 `job_item` 做了应有处理。
3. `POST /price-adjust/versions/generate {customerNo:"CUST-0001"}` 手动生成第一版（假设当天 `2026-08-05`）。
4. **同日再次**手动生成（`confirmSupersede=true`）。

**断言（机械可判定）**
- 🔒 代码走查结论**必须写入证据**：两个入口方法体行数均 ≤ 5 行且都只是转调同一方法（不是"各自实现但结果凑巧一样"）。
- 🔒 步骤 3、4 生成的两个版本号：第一个 = `V26080501`，第二个 = `V26080502`（**同日流水位递增**，`YYMMDD` 部分相同，`NN` 部分 `01→02`）。
- 🔒 `SELECT version_no, trigger_type, scheduled_slot FROM element_price_version WHERE customer_no='CUST-0001' ORDER BY created_at`：手动生成的两条 `trigger_type='MANUAL'`、`scheduled_slot IS NULL`。
- 🔒 步骤 2 黑盒对照：定时生成与手动生成各自产生的 `element_price_version_item` 集合，在"无价元素继承标记 `inherited_from_previous`"这一列上表现一致（给定相同输入条件，两条路径都应该正确标注，不会一个标了另一个没标）。

**证据形式**：源码走查截图/引用（文件路径+行号）；两次生成的 curl 响应 + `version_no` 对比；SQL 查询输出。

---

### #6　期期起版：本期价格全无变动时仍生成版本，但 0 变动料号不进待办池

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | API 测试 + SQL 断言 |

**前置数据**
- `CUST-0001` 已有一个 `PENDING` 版本 `V-old`（走 #5 生成），其 `element_price_version_item` 里 `Ag` 当期价 = 5450。
- 令该料号（`S-3120014539`）指针指向 `V-old`：`material_price_version_ref` 手工 upsert 或走一次通过审核使其指针推进到 `V-old`。
- 保证下一版生成时 `Ag` 取到的**当期价与 `V-old` 完全相同**（可直接复用同一价格来源日价，不改动 `element_daily_price`）。

**执行步骤**
1. 手动再生成一版 `V-new`（走 #5 相同路径）。
2. 查询 `V-new` 的 `element_price_version_item`。
3. 查询待办池 `material_price_review`，看 `S-3120014539` 是否为 `V-new` 生成了 review 记录。

**断言（机械可判定）**
- 🔒 `V-new` 版本记录**照常生成**：`SELECT count(*) FROM element_price_version WHERE version_no='V-new对应号'` = 1（价格没变也生成，验收原文"仍生成版本"）。
- 🔒 `V-new` 的 `element_price_version_item` 里 `Ag` 的 `current_price = previous_price`（= 5450），`change_rate = 0`。
- 🔒 🔒 **`S-3120014539` 不出现在 `V-new` 对应的 `material_price_review` 待办池**（`SELECT count(*) FROM material_price_review WHERE version_id=(V-new的id) AND material_no='S-3120014539'` **= 0**）—— 裁决 39「无事可审不进池」。
- 🔒 反向对照：另找一个价格确有变动的元素/料号组合，确认它**照常进池**（防止实现走极端，把"0 变动不进池"错写成"该版所有料号都不进池"）。

**证据形式**：两次版本生成的 SQL 输出；`material_price_review` 表按 `version_id` 分组的行数对比。

---

### #7　版本交替：新版生成时上一版整体作废、未处理料号失效；已通过并执行的更新不回滚

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据**
- `CUST-0001` 生成版本 `V1`，`material_price_review` 下有 3 条待处理记录（材料料号可复用 `S-3120014539` 或按 #2 造的另外几个料号）。
- 通过其中 1 条（`APPROVED`，指针推进到 `V1`），保留 2 条 `PENDING`。

**执行步骤**
1. 手动生成新版 `V2`（`confirmSupersede=true`）。
2. 查询 `V1`/`V2` 状态、`material_price_review` 各条记录的状态、指针指向。

**断言（机械可判定）**
- 🔒 `SELECT status FROM element_price_version WHERE id=V1的id` = `SUPERSEDED`。
- 🔒 `SELECT status FROM element_price_version WHERE id=V2的id` = `PENDING`。
- 🔒 🔄 **返修（2026-08-01，裁定死不再留给实现选择）**：`material_price_review.status` 枚举已在 `backtask.md` B1 定死为 `PENDING`/`APPROVED`/`REJECTED`/`VOIDED` 四态。断言：
  `SELECT status FROM material_price_review WHERE version_id=<V1的id> AND status='PENDING'` 结果集**必须为空**（原 2 条 `PENDING` 不再是 `PENDING`）；且 `SELECT count(*) FROM material_price_review WHERE version_id=<V1的id> AND status='VOIDED'` **= 2**（原 2 条已转 `VOIDED`，不是被物理删除、也不是保持 `PENDING`）。
- 🔒 🆕 **补 §11.6.3.2 断言**：若 `V1` 名下存在**未完成的 `job_item`**（构造前置：先让 `V1` 的通过项之一走一次更新任务且人为制造 1 条 `FAILED`/`WAITING` 的 `material_price_update_job_item`），`V2` 生成后该 job_item **一律置 `STALE` 终态**：`SELECT status FROM material_price_update_job_item WHERE job_id IN (SELECT id FROM material_price_update_job WHERE version_id=<V1的id>) AND status NOT IN ('SUCCESS')` 结果全部 = `STALE`（不再是 `WAITING`/`FAILED`/`CONFLICT`）。
- 🔒 **已通过的那 1 条既成事实不回滚**：其 `material_price_review.status` 仍是 `APPROVED`，`material_price_version_ref` 指针仍指向 `V1`（不因 `V2` 生成而被强制推到 `V2`；只有该料号后续再走一次审核流程才会推进）。

**证据形式**：`V1`/`V2` 生成前后的 `element_price_version.status` 对比；`material_price_review` 三条记录状态变化表（含 `VOIDED` 判定）；`material_price_update_job_item` 的 `STALE` 转换查询输出；`material_price_version_ref` 指针查询输出。

---

### #8　待办池：一料号一行，核心列「当前版本 → 目标版本」正确；驳回料号显示"未升版"停在原版

| 归属 | 测试层级 |
|---|---|
| 🔄 **返修（2026-08-01，归属按测什么拆开）**：后端自测（一料号一行 + 版本号字段 + 指针不变）；**跨端(后端主导)**（"未升版"文案是前端渲染，需要 E2E/人工确认前端渲染层对应文案与字段） | API 测试 + SQL 断言（后端部分）+ E2E(Playwright)（前端渲染部分） |

**前置数据**：同 #7 场景（`V1` 生成、部分料号审核过）；另构造一条被驳回的记录：对 `V1` 下另一料号执行 `POST /reviews/reject {reviewIds:[...], reason:"行情尚未确认"}`。

**执行步骤**
1. `GET /api/cpq/price-adjust/reviews?status=PENDING`（及 `status=REJECTED`）。
2. 核对每条记录的 `currentVersionNo`/`targetVersionNo` 字段。
3. （跨端部分）打开屏 3 待办池页面 / 屏 7 报价单「价格版本」抽屉的料号级版本表，定位到该被驳回料号所在行。

**断言（机械可判定）**
- 🔒【后端自测】每个料号在待办池**只出现一行**（`GROUP BY material_no HAVING count(*) > 1` 的结果集为空）。
- 🔒【后端自测】`PENDING` 记录的 `currentVersionNo` = 该料号指针当前指向版本号（升版前的版本），`targetVersionNo` = `V1` 对应版本号。
- 🔒【后端自测】被驳回记录：`reviewStatus=REJECTED`，`currentVersionNo` = 驳回前的原版本号（未变）；`material_price_version_ref` 里该料号的 `version_id` 在驳回动作前后**逐字节不变**（`SELECT version_id FROM material_price_version_ref WHERE material_no=X` 驳回前后一致）。
- 🔒【跨端】步骤 3 页面 DOM 中该行文案**必须包含**「未升版」字样（`expect(row).toContainText('未升版')`），且**不得**显示成已推进的新版本号。

**证据形式**：SQL 分组去重查询输出（验证一料号一行）；驳回前后的 API 响应 diff；`material_price_version_ref` 驳回前后对比；前端渲染截图/Playwright 断言日志（"未升版"文案）。

---

### #9　🔴 判断依据：行上数字取该料号**版本生成时刻**最近建单日的活单；**版本生成后新建的更新单不改变已锁定的依据**

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，E11 断链 6 核心条目） | SQL 断言 + API 测试 |

**前置数据** 🔄 **返修（2026-08-01，补齐可执行造数 SQL）**

已核实表结构与约束（`information_schema.columns` + `pg_constraint` 实测）：
- `quotation` 必填列（无默认值需显式给）：`quotation_number` / `customer_id` / `name` / `sales_rep_id`；`status`/`created_at`/`updated_at`/`tax_rate`/`tax_amount`/`bound_global_variables_snapshot` 有默认值但**可显式覆盖**（均为普通列，全表无 `CREATE TRIGGER`，`created_at` 不受 `@PrePersist` 之外任何机制保护，INSERT 时可直接指定任意历史日期）。
- `quotation_line_item.product_part_no_snapshot` **无外键约束**（`pg_constraint` 确认该表仅 `parent_line_item_id`/`product_id`/`quotation_id`/`template_id` 四个 FK，`product_part_no_snapshot` 是纯快照文本列），可安全使用一个不存在于 `material_master` 的合成料号做隔离测试，不会触发 FK 失败。
- `sales_rep_id` 复用真实 `admin` 用户：`d1e1147c-a639-4156-aeac-9f938a65ad05`。
- `customer_template_id` 用现网真实模板 `70f1b149-b0d9-4cb1-9245-6c3cee1bc3af`（罗克韦尔模板1 v1.0）。

**判定方式**：直接 `INSERT` 造数（不走向导 API）——因为 #9 只验证"依据单选取逻辑"，不需要该行的公式/卡片值能正确渲染，只需要 `quotation.created_at` 可控、`status` 落在活单范围、`quotation_line_item.product_part_no_snapshot` 命中目标料号即可；直接 INSERT 更简单可控，且已验证不会触发任何约束失败。

```sql
-- 单 A：2026-07-20 建单，DRAFT，含料号 M-BASIS-TEST
INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, customer_template_id, created_at, updated_at)
VALUES (gen_random_uuid(), 'QT-TEST-BASIS-A', 'f6d10ef0-04cc-45f3-829c-568c8cce3adf', '测试单A-判断依据锁定',
        'd1e1147c-a639-4156-aeac-9f938a65ad05', 'DRAFT', '70f1b149-b0d9-4cb1-9245-6c3cee1bc3af',
        '2026-07-20 10:00:00+08', '2026-07-20 10:00:00+08')
RETURNING id;   -- 记为 <QID_A>

INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, customer_part_no, subtotal, created_at)
VALUES (gen_random_uuid(), '<QID_A>', 'M-BASIS-TEST', 'M-BASIS-TEST', 0, '2026-07-20 10:00:00+08');

-- 单 B：2026-07-25 建单，DRAFT，同料号（此刻是"最近一张活单"）
INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, customer_template_id, created_at, updated_at)
VALUES (gen_random_uuid(), 'QT-TEST-BASIS-B', 'f6d10ef0-04cc-45f3-829c-568c8cce3adf', '测试单B-判断依据锁定',
        'd1e1147c-a639-4156-aeac-9f938a65ad05', 'DRAFT', '70f1b149-b0d9-4cb1-9245-6c3cee1bc3af',
        '2026-07-25 10:00:00+08', '2026-07-25 10:00:00+08')
RETURNING id;  -- 记为 <QID_B>

INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, customer_part_no, subtotal, created_at)
VALUES (gen_random_uuid(), '<QID_B>', 'M-BASIS-TEST', 'M-BASIS-TEST', 0, '2026-07-25 10:00:00+08');

-- 令料号在策略范围内（材料范围模式 ALL，或走 #2 的 PUT .../materials 显式加入 M-BASIS-TEST）
```

**执行步骤**
1. **此刻**（假设 `2026-08-01`）触发版本生成 `V-A`，此时该料号"最近一张活单"应为单 B（`2026-07-25`）。
2. 查询 `material_price_review` 中该料号对应 `V-A` 的记录，记下 `basisQuotationId`（应 = 单 B）。
3. 🔒 **关键步骤**：版本生成**之后**，再新建一张**更晚**（如 `2026-08-01` 当天）的活单 C，同样含料号 `M-BASIS-TEST`。
4. 重新 `GET /reviews/{reviewId}`，核对 `basisQuotationId` 是否被单 C 顶替。

**断言（机械可判定）**
- 🔒 步骤 2：`basisQuotationId` = 单 B 的 id（而不是单 A），`basisQuotationDate = 2026-07-25`。
- 🔒 🔒 **核心断言**：步骤 4 的 `basisQuotationId` **仍然 = 单 B 的 id，不变**（即便单 C 建单日期更晚、单 C 是更"新"的活单，依据单也**不**随之改变 —— 因为依据单在版本生成那一刻已锁定并写入 `material_price_review.basis_quotation_id`，此后不随新建单变化）。
- 🔒 抽屉里的"其余单"列表（`GET /reviews/{reviewId}` 的 `quotations` 数组）此时应包含单 A、单 B、单 C 三张，其中**只有单 B** `isBasis=true`，单 A 与单 C 均 `isBasis=false`（标"仅作参考"）。

**证据形式**：版本生成前后 `basis_quotation_id` 的 SQL 查询输出（时间戳标注前后两次查询）；`GET /reviews/{reviewId}` 步骤 2 与步骤 4 两次响应 JSON 的 diff（`basisQuotationId` 字段必须完全相同）。

---

### #10　毛利口径：料号毛利与 task-0717 比对视图同一料号的 `productTotal` 口径逐位一致

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据** 🔄 **返修（2026-08-01，避开已知问题数据 + 加自检门禁）**

⚠️ **`QT-20260726-0016` 不得作为基准**——技术总监小样本对拍已实测该单 `li.subtotal=26` 但 `quote_card_values` SUBTOTAL 页签算出 `14`，差异尚未定性（正是 `backtask.md` B8.2《SUBTOTAL 双端对拍清单》要查清的对象）。同理排除另一条已知问题单（`li.subtotal=755.93` vs 卡片 `214`，对应 `QT-20260731-0037`）。拿本身待定性的数据当"逐位一致"的基准，测出来是红是绿都没有意义。

**基准单不预先写死**，改为运行期自选 + 前置门禁（对未来数据变化也更稳健）：
```sql
-- 门禁查询：候选基准单必须满足 li.subtotal 与卡片 SUBTOTAL 页签值一致（差 ≤ 0.01），
-- 且显式排除已知问题单
SELECT li.id, q.quotation_number, li.product_part_no_snapshot, li.subtotal
FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id
WHERE q.quotation_number NOT IN ('QT-20260726-0016','QT-20260731-0037')
  AND li.quote_card_values IS NOT NULL
ORDER BY li.created_at DESC;
```

**执行步骤**
1. 🔒 **前置门禁（必须先做）**：对候选单逐条比较 `li.subtotal` 与其 `quote_card_values` 中 SUBTOTAL 页签算出的 subtotal（用 `CostingSubtotalUtil#extractUnitSubtotal` 或等价方法离线提取）。**只有两者相差 ≤ 0.01 的行才有资格当基准**；若查完一圈没有任何一条满足，则本条**阻塞**，需先等待 `backtask.md` B8.2 对拍清单交付、S6 口径补齐后再选基准（不得在两处口径本身对不上的情况下继续测"逐位一致"）。
2. 选定满足门禁的基准单后，调用既有 task-0717 比对视图接口（`ComparisonViewService` 对应端点）取该单料号的 `productTotal`（报价侧 SUBTOTAL 页签值）。
3. 调用本任务待办池/审核详情接口（`GET /reviews/{reviewId}`，若该料号当前不在待办池，可直接单元测试调用后端"产品总价计算"共用方法，传入同一 `quoteCardValues`）取同一单同一料号的 `quoteCostCurrent`（或对应的"报价侧产品总价·现"字段）。

**断言（机械可判定）**
- 🔒 步骤 1 门禁：`|li.subtotal - 卡片SUBTOTAL值| ≤ 0.01`，作为后续断言"有意义"的前提条件，须在报告里显式记录该前置检查的通过证据。
- 🔒 两处取值**逐位相等**（数值完全相同，含小数位数），即 `task-0717 的 productTotal == 本任务的 quoteCostCurrent`（同一单同一料号）。
- 🔒 复用同一算法的证据：走查代码确认两处调用的是**同一个工具方法**（如 `CostingSubtotalUtil#extractUnitSubtotal`），而非各自重新实现了一遍 SUBTOTAL 解析逻辑（防止"口径相似但两套实现，未来各自漂移"）。

**证据形式**：门禁查询的 SQL 输出（含被排除的已知问题单、被选中的基准单）；两个接口对同一单同一料号的响应 JSON 并排对比；调用方法的代码走查引用（文件+行号）。

---

### #11　预警：跌破成本差额预警线整行标红；确认弹窗二次提示；**不阻断通过**

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | API 测试 + SQL 断言 |

**前置数据**：`CUST-0001` 策略 `cost_diff_threshold=0`（默认）。造一个料号 R，其待办池预算 `diffAdjusted = -20`（报价侧成本 < 核价侧成本 20 元，跌破 0 阈值）；另造料号 S，`diffAdjusted = +10`（健康，不跌破）。两者都处于 `PENDING` 待审。

**执行步骤**
1. `POST /api/cpq/price-adjust/reviews/impact {reviewIds:[R的reviewId, S的reviewId]}`。
2. 对 R、S 分别 `POST /reviews/approve {reviewIds:[...]}`，观察是否被硬性拒绝。

**断言（机械可判定）**
- 🔒 步骤 1 响应 `breachedMaterials` 数组**包含** R（`materialNo` 命中）、**不包含** S。
- 🔒 `material_price_review.status='PENDING'` 的 R **整行标红**：`SELECT breached_count FROM material_price_review WHERE id=R的reviewId` **> 0**。
- 🔒 步骤 2：R 的 `approve` 调用**照常返回 `202`**（不因跌破预警线被硬性拒绝）——验证"只提醒不阻断"。此断言与 #13 的确认弹窗文案（前端渲染"跌破预警线提示"）配合，本条只测后端不因预警线拒绝请求；前端二次确认弹窗的渲染细节归入 #13。

**证据形式**：`impact` 接口响应 JSON（`breachedMaterials` 内容）；R/S 两次 `approve` 调用的 HTTP 状态码；`breached_count` 的 SQL 输出。

---

### #12　只读审核：审核界面无任何可改价控件；驳回必填原因；驳回版本保留可查

| 归属 | 测试层级 |
|---|---|
| 后端自测（`reason` 必填校验）+ 跨端(后端主导)（前端渲染层需 E2E 确认无可改价控件） | API 测试 + E2E(Playwright) |

**前置数据**：任一 `PENDING` 状态的 review 记录（复用 #7/#8 场景）。

**执行步骤**
1. `POST /api/cpq/price-adjust/reviews/reject {reviewIds:[...], reason:""}`（空原因）。
2. `POST .../reviews/reject {reviewIds:[...], reason:"行情尚未确认"}`（正常驳回）。
3. Playwright 打开屏 4 料号审核抽屉，扫描 DOM 中所有 `<input>`/`<textarea>`（除驳回原因填写框外）。

**断言（机械可判定）**
- 🔒 步骤 1：响应 **`400`**（`reason` 必填，裁决 8），且 `material_price_review.status` **未变**（仍 `PENDING`，未被误驳回）。
- 🔒 步骤 2：响应 `200`，`SELECT status, review_comment FROM material_price_review WHERE id=...` = `REJECTED` / `'行情尚未确认'`。
- 🔒 步骤 3：抽屉内除"驳回原因"输入框外，**不存在**任何价格相关字段的可编辑 `<input>`（`page.locator('input[data-field*="price"]:not([readonly])').count()` **= 0**）。
- 🔒 驳回后的版本记录仍可查：`GET /reviews/{reviewId}` 对已 `REJECTED` 的记录仍返回 `200`（不是 404/410），三段结构完整可读。

**证据形式**：3 次 API 调用响应；抽屉截图（标注无可改价控件）；`input[readonly]` 计数的 Playwright 断言日志。

---

### #13　通过确认：弹窗列出 N 个料号 + 版本推进路径 + M 张单按状态分组 + 跌破预警线提示

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2） | API 测试 + E2E(Playwright) |

**前置数据**：复用 #11 的料号 R（跌破预警线）、S（健康），各自的"最近活单"覆盖至少 2 种状态（如 R 对应单 `DRAFT`、S 对应单 `SUBMITTED`），另各配 1 张 `SENT`/`ACCEPTED` 的历史单（不会被更新，用于验证"排除项"）。

**执行步骤**
1. `POST /api/cpq/price-adjust/reviews/impact {reviewIds:[R的id, S的id]}`。
2. Playwright 选中 R、S 两行，点击工具栏「通过并升版」，断言弹出屏 5 Modal（**唯一允许的 Modal**）并读取渲染内容。

**断言（机械可判定）**
- 🔒 API 响应：`materialCount=2`；`versionPaths` 含 R、S 各自的 `from`/`to` 版本号；`quotationCount` = 实际受影响活单数；`byStatus` **只含 5 个可更新状态**（`DRAFT`/`SUBMITTED`/`APPROVED`/`REJECTED`/`COSTING_REJECTED`）的计数，**不含** `SENT`/`ACCEPTED`；`excludedByStatus` 显式列出 `SENT`/`ACCEPTED` 各自的数量；`breachedMaterials` 含 R。
- 🔒 前端 Modal DOM：文案中出现「另有 N 张单不会被更新（已发送 x / 已接受 y）」的等价表述（`excludedByStatus` 渲染），且 R 所在行有跌破预警线的视觉/文案标记。
- 🔒 Modal 展示的 `quotationCount` 数字与 API 响应的 `quotationCount` **逐位相等**（前端未做二次计算，纯读接口返回值）。

**证据形式**：`impact` 接口响应 JSON；屏 5 Modal 截图；Playwright 断言日志（数字比对）。

---

### #14　🔴 隔离性（核心 · 必须双向断言）：通过料号 A 后 —— 正向 A 已变、反向 B 逐字节不变

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3 + §12.3，与 #21 #28 #40 #41 #64 并列为不接受口头"已通过"的条目） | SQL 断言（双向，逐字节） |

**前置数据**
- 造两个独立料号 A（`M-ISO-A`）、B（`M-ISO-B`），各自一张 `DRAFT` 报价单（参照 #9 的直接 INSERT 造数方式，`quotation_line_item.product_part_no_snapshot` 分别为两个料号），各自的材料成本组件里都有一行 `element=Ag`（写入 `quotation_line_component_data.snapshot_rows`，构造一条含 `{"元素":"Ag","元素单价":5450, ...}` 的 JSON 行，`row_key_fields` 按该组件配置对齐）。
- 两者都在 `CUST-0001` 调价策略范围内，元素清单含 `Ag`。
- 生成版本 `V1`（`Ag` 新价 = 5820），A、B 都进入 `PENDING` 待办池。

**执行步骤**
1. **升版前**，对 B 的 line_item 采集基线：
   ```sql
   SELECT md5(cd.snapshot_rows::text) AS h_snap, md5(li.quote_card_values::text) AS h_qcv,
          li.line_total_amount, li.discount_rate_applied
   FROM quotation_line_item li JOIN quotation_line_component_data cd ON cd.line_item_id = li.id
   WHERE li.product_part_no_snapshot = 'M-ISO-B';
   ```
   同样对 A 采集基线（`snapshot_rows` 里 `Ag` 单价此刻应为 5450）。
2. `POST /reviews/approve {reviewIds:[A的reviewId]}`（**只通过 A，不动 B**），等待异步 job 完成（轮询 `GET /jobs/{id}` 至 `status=SUCCESS`）。
3. 升版完成后，对 A、B 分别重新执行步骤 1 的查询。

**断言（机械可判定，双向缺一不可）**
- 🔒 **正向（A 必须变）**：
  - `A.snapshot_rows` 中 `Ag` 那一行的"元素单价"字段值 **= 5820**（`jsonb_path_query` 提取该字段精确等于本版价，不是"有变化"这种模糊断言）。
  - `A.quote_card_values` 的 `md5` **与升版前不同**。
  - `A.li.subtotal` 与 `A.li.line_total_amount` **与升版前不同**，且新值 = 用新价重算后的期望值（用 #10 门禁过的同一套 `extractUnitSubtotal` 方法离线复核，逐位相等）。
- 🔒 **反向（B 必须逐字节不变）**：
  - `B` 的 `h_snap`、`h_qcv`（两个 md5 哈希）升版前后**完全相同**。
  - `B.li.line_total_amount`、`B.li.discount_rate_applied` 升版前后**数值完全相同**（非近似）。
  - > ⚠️ **本条禁止只写反向断言**——已核实 `CardSnapshotService` 全类从不写 `snapshot_rows`，"B 的 `snapshot_rows` 不变"这条断言**即使 A 根本没升版成功也天然成立**，必须靠上面"正向 A 已变"的断言组合，才能证明测试真的在验证升版执行，而不是在验证"什么都没发生"。
- 🔒 单据级聚合：`quotation.total_amount` 及税额随 A 的变化而变化（预期内），**不算破坏隔离**（硬约束 3）。

**证据形式**：升版前后两组 SQL 查询的完整输出（含 md5 哈希、精确数值）；job 完成状态查询记录；`extractUnitSubtotal` 离线复核计算过程。

---

### #15　升版原子性：全部相关元素一次性完成本期结算；预算值与实际结果逐位一致

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据**：料号 C（`M-ATOMIC-TEST`），BOM 含 `Ag`（有本期价）与 `Ni`（本期无价，按前述已实测事实"`Ni` 无价返 NULL"，且假设 `Ni` 从无历史价，走 §11.3.2.1 第三行"从无历史价则视同不在清单、可编辑"分支——若需要覆盖"本期无价但有上一版价"分支，另造一版历史价，见 #47）。生成版本 `V1`。

**执行步骤**
1. 审核阶段：`GET /reviews/{reviewId}`，记录 `material_price_review_column` 落库的预算值（`quoteAdjusted`）。
2. `POST /reviews/approve`，等待 job 完成。
3. 升版后查询该料号实际 `quote_card_values` 重算出的 SUBTOTAL。

**断言（机械可判定）**
- 🔒 `Ag` 单价升版后 = 本版价（同 #14 正向断言方式）；`Ni`（从无历史价）该行单价列**保持原值/可编辑状态**，不被强制置空或报错——"一次性完成本期结算"允许"无价元素维持原样"，但**不允许**出现"Ag 已切新价、Ni 卡在处理中间态导致该料号整体升版失败"的情况（`material_price_update_job_item` 该料号对应 job_item 的 `status` 最终必须是 `SUCCESS`，不是卡在 `RUNNING`）。
- 🔒 **预算 = 实际，逐位相等**：步骤 1 的 `quoteAdjusted`（预算值，`dryRun=true` 算出并落库）与步骤 3 的实际重算 SUBTOTAL **数值完全相同**（`abs(预算 - 实际) = 0`，不是"接近"）。这是通道 B `dryRun` 复用同一段代码的结构性验证——若两者对不上，说明预算与执行走了不同代码路径。

**证据形式**：审核阶段 `material_price_review_column.quote_adjusted` 的 SQL 输出；升版后实际 SUBTOTAL 的计算过程；两者数值对比表。

---

### #16　🔴 冲突保护（核心）：更新期间人为改动该单 → 标记冲突、未写回、可重试；销售改动不丢失

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2：`row_version` 判据归后端，制造非 saveDraft 编辑路径归前端配合） | API 测试 + SQL 断言 |

**🔒 强制纪律（本条最容易假绿的地方）**：**禁止用 `quotation.updated_at` 做断言的任何形式**——判据只能是 `row_version` 不匹配导致的 `UPDATE ... WHERE row_version=:seen` **受影响行数 = 0**。已实测 `quotation.updated_at` 只由 `@PreUpdate` 维护、DB 无 trigger，而下面 4 条编辑路径**全部不 bump 它**（§11.15.7 裁决 32），若用例里出现任何 `updated_at` 相关断言，视为无效用例。

**共同测试方法（对 4 条路径逐一套用）**

1. 记录目标 `quotation_line_component_data`（或 `quotation_line_item`）当前 `row_version = V0`。
2. 调用该编辑路径的**真实端点**，完成一次"销售侧编辑"。
3. 🔒 **必要前提断言**：查询该行 `row_version`，必须 **= V0 + 1**（编辑路径若不 bump `row_version`，#16 的冲突检测机制根本无从谈起——这本身就是该路径要测的第一件事）。
4. **冲突检测的 SQL 级验证**（对齐 §11.15.5.2 实现规范）：手动执行升级通道 S3 记录的原生写回语句，故意代入**步骤 1 捕获的旧值 `V0`**（模拟"job 在编辑发生前已读到 `V0`，此刻才写回"）：
   ```sql
   UPDATE quotation_line_component_data
      SET snapshot_rows = '<某新价 JSON>', row_version = row_version + 1
    WHERE line_item_id = :lid AND component_id = :cid AND row_version = V0;
   ```
   断言该语句 **`UPDATE 0`**（受影响行数为 0），且步骤 2 编辑写入的内容**未被覆盖**（重新查询该行 `snapshot_rows`/`row_data`，销售编辑的值仍在）。
5. 若后端提供了可控延迟的测试钩子（在升版执行 S1~S2 与 S3 写回之间人为插入等待），追加**真实端到端竞态**：审核通过触发升版 job → 在其读到 `row_version` 之后、写回之前的窗口内，通过该编辑端点完成一次真实编辑 → 断言该 `job_item.status = 'CONFLICT'`（不是 `FAILED`）、可重试、销售的编辑值留存。若无此钩子，以步骤 4 的确定性 SQL 验证作为等效证据，并在报告中注明"真实竞态未覆盖，依赖后端测试钩子"。

**四条路径逐一覆盖（🔒 缺一不可，任何一条都不能只测 `quote-card-edit` 就收工）**

| 路径 | 真实端点 | 前置数据要点 |
|---|---|---|
| a. 单元格编辑 | `PUT /api/cpq/quotations/line-items/{id}/quote-card-edit` | 目标行元素 **不在**本次冲突测试所涉调价清单内（避免与归位机制的其他断言互相干扰），改一个普通字段（如"毛重"） |
| b. 删/恢复 driver 行 | `POST /api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row`（删）+ `POST .../restore-driver-rows`（恢复） | 需要一个 `snapshot_rows` 里存在 ≥2 行 driver 数据的 line_item |
| c. BOM 树增删叶 | `POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/add-leaf`（增）+ `.../tree/delete`（删） | 需要该料号走 BOM 闭包/树形结构（可复用 `S-80011`，其 `material_bom_item` 已有真实父子关系 `S-80011 → 00002`） |
| d. 选配加产品 | `POST /api/cpq/configure-product/quotations/{quotationId}` | 该报价单需为选配场景（`composite_type` 或选配模板），若现网无现成选配单需另造（若造数成本过高，本条允许降级为"代码走查确认 `ConfigureProductService` 写路径同样走原生 SQL + `row_version+1`"，见下方限制说明） |

**断言（机械可判定，逐路径独立验证）**
- 🔒 每条路径步骤 3（`row_version` 确实 +1）与步骤 4（旧版本号写回受影响行数=0 + 编辑内容未被覆盖）**均独立通过**，4 条路径缺一不可。
- 🔒 全程**不出现**任何形如 `WHERE ... updated_at = :seen` 的断言或实现依据。
- 🔒 该 job_item（若走完整审核流程触发）最终状态为 `CONFLICT`（非 `FAILED`/`STALE`），且**可单独重试**（`POST /price-adjust/job-items/{itemId}/retry` 返回非 409 的正常受理响应）。

**证据形式**：4 条路径各自的"编辑前后 `row_version`"查询输出；4 条路径各自的"旧版本号写回受影响行数=0"SQL 执行记录；job_item 冲突态与重试记录（若走完整流程）。

**⚠️ 当前环境限制**：路径 d（选配加产品）若现网 `CUST-0001` 下没有可直接复用的选配产品结构，需要额外造数（选配模板绑定），造数成本明显高于 a/b/c；若时间/环境不允许，允许暂以"代码走查确认 `ConfigureProductService` 的写路径同样使用原生 SQL 且带 `row_version+1`"作为替代证据，但**不得**因此跳过 a/b/c 三条的真实执行验证。

---

### #17　`R` 版本合并：同一 `V` 版内分两次通过两个料号 → 该单只有一个 `R` 版本，「最后更新」时间刷新、「已升版料号」累积为两个

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据**：一张报价单同时含料号 D（`M-R-D`）、E（`M-R-E`），均在 `CUST-0001` 调价范围与元素清单内，生成版本 `V1`（D、E 都进 `PENDING`）。

**执行步骤**
1. `POST /reviews/approve {reviewIds:[D的reviewId]}`，等待完成，记录此刻 `quotation_price_revision` 记录数与内容（`t1` 时刻）。
2. 间隔片刻后 `POST /reviews/approve {reviewIds:[E的reviewId]}`，等待完成（`t2` 时刻）。
3. `GET /api/cpq/quotations/{quotationId}/price-revisions`。

**断言（机械可判定）**
- 🔒 `SELECT count(*) FROM quotation_price_revision WHERE quotation_id=... AND based_version_id=(V1的id)` **= 1**（不是 2）——同一 `V` 版内两次料号升版**合并进同一个 `R`**，不新建版本号。
- 🔒 该 `R` 记录的 `last_updated_at` 在 `t2` 之后被刷新（`t2` 查询时刻 ≥ `last_updated_at` 且明显晚于 `t1` 时刻记录的初值）。
- 🔒 `upgraded_material_nos` 在 `t1` 后含 `[M-R-D]`，在 `t2` 后累积为 `[M-R-D, M-R-E]`（顺序不敏感，用集合比较）。
- 🔒 补 #17 原文遗漏的快照覆写断言（呼应 §11.6.2 / 验收 #63，此处先验证不矛盾）：`t2` 后该 `R` 的 `quote_card_values` 中 D、E **两个料号都是各自升版后的新价**（不是"D 升版时物化一次、E 升版后快照没更新"的半旧状态）——具体逐位断言见 #63。

**证据形式**：`t1`/`t2` 两次 `quotation_price_revision` 查询输出对比；`upgraded_material_nos` 前后内容对比；`GET .../price-revisions` 响应 JSON。

---

### #18　切版本预览：切到历史版为只读（输入框 / 保存 / 提交 / **导出**全部禁用），退出预览恢复；单据当前值未被修改

| 归属 | 技术总监亲验（`backtask.md` §2.3 + `fronttask.md` §12.3，与 #55 同批次核心条目） | E2E(Playwright) + API 测试 |
|---|---|---|

**前置数据**：复用 #17 场景，`quotation_price_revision` 已有 ≥2 条记录（初版 + `R1`）。

**执行步骤**
1. `GET /api/cpq/quotations/{quotationId}/price-revisions/{revisionId}/preview`（对初版或 `R1`）。
2. Playwright 打开该报价单编辑页，通过屏 7「价格版本」抽屉切到历史版本，逐一检查：所有 `<input>`、「保存」按钮、「提交」按钮、**「导出」按钮**的可用状态。
3. 点击「退出预览」，重新检查页面可编辑状态与数据。
4. 退出预览后，直接查询数据库确认单据当前值未被本次切版操作污染。

**断言（机械可判定）**
- 🔒 预览态下：`page.locator('input:not([disabled])').count()` 在产品卡片区域 **= 0**（所有输入框禁用）。
- 🔒 「保存」「提交」「**导出**」三个按钮均处于 `disabled` 状态（`expect(btn).toBeDisabled()`），**导出**尤其容易被漏测——须显式断言，不得只测保存/提交。
- 🔒 退出预览后：以上按钮**恢复可用**（`expect(btn).toBeEnabled()`），输入框恢复可编辑。
- 🔒 `SELECT quote_card_values, snapshot_rows FROM quotation_line_item/quotation_line_component_data WHERE quotation_id=...` 在"进入预览 → 退出预览"前后 **md5 完全相同**（预览操作本身不写库、不改变单据当前值，裁决 14"只读预览，不落库"）。

**证据形式**：预览态截图（含禁用状态的按钮/输入框）；退出预览后截图；单据当前值前后 md5 对比。

---

### #19　料号级版本表：单内每个料号显示各自当前版本；被驳回料号显示停留版本；未参与调价料号显示「未参与调价」

| 归属 | 🔄 跨端(后端主导)（⚠️ `fronttask.md` §12.1/§12.2 汇总表未收录本条，但正文 §6 屏 7 section 明确列出"验收归属：#19"，属汇总表遗漏，已按正文归属校正，测试时前端仍须自查渲染） | API 测试 + E2E(Playwright) |

**前置数据**：一张单含 4 个料号，分别构造四种状态：
- F（`M-STATE-UPGRADED`）：已通过升版，指针在最新版。
- G（`M-STATE-REJECTED`）：被驳回，停在原版。
- H（`M-STATE-NOT-UPDATED`）：指针已推进但该单更新失败（构造：走 #16 的冲突手法故意让该单该料号的 job_item 落 `FAILED`/`CONFLICT`）。
- I（`M-STATE-NOT-PARTICIPATING`）：不在调价策略范围内。

**执行步骤**：`GET /api/cpq/quotations/{quotationId}/price-revisions`，核对 `materialVersions` 数组。

**断言（机械可判定）**
- 🔒 F：`state="UPGRADED"`，`currentVersionNo` = 最新版本号。
- 🔒 G：`state="REJECTED"`，`currentVersionNo` = 驳回前的原版本号（未变）。
- 🔒 H：`state="NOT_UPDATED"`，且 **`pendingJobItemId` 不为空**（判定必须读 `material_price_update_job_item`，不能只读指针——若只读指针会误判成已推进，参见 §11.6.3.1 后果 2）。
- 🔒 I：`state="NOT_PARTICIPATING"`，`currentVersionNo=null`。
- 🔒 前端渲染（E2E）：H 那一行**必须显式**渲染「尚未更新」文案，**不得**直接显示已推进的新版本号（`expect(row_H).toContainText('尚未更新')` 且 `not.toContainText(最新版本号)`）。

**证据形式**：`GET .../price-revisions` 响应 JSON（四种 `state` 各一条）；`material_price_update_job_item` 对应查询；H 行渲染截图。

---

### #20　权限：`SALES_REP` / `SALES_MANAGER` 登录看不到「价格调整审核」菜单，报价单内可见版本轨迹但只读，无通过/驳回/触发更新入口

| 归属 | 前端自测（`fronttask.md` §12.1，权限判定逻辑归前端路由/菜单渲染，服务端接口层的角色校验归后端但未单列自测条目，测试设计按需要补充后端 API 层 403/401 校验） | E2E(Playwright) + API 测试 |

**⚠️ 环境限制（先处理）**：实测 `cpq_db_0724` 当前仅 2 个用户（`admin`=`SYSTEM_ADMIN`、`test_finance_c87a27ab`=`PRICING_MANAGER`），**没有 `SALES_REP`/`SALES_MANAGER` 用户**。需先造：
```sql
-- 造一个销售角色测试用户（密码哈希需按项目既有加密方式生成，建议走既有"新建用户"API 而非直接 INSERT，
-- 避免密码哈希算法/盐值不一致导致登录失败——用户表未探明是否有密码相关 NOT NULL 列及加密方式，
-- 此处不猜测直接 INSERT 的可行性，改为要求走 POST /api/cpq/users 或等价管理端点创建）
```
若既有系统提供用户管理 API（如 `POST /api/cpq/users {username, role:'SALES_REP', ...}`），优先走该 API 创建，附带确认该销售对目标报价单（前置数据里的测试单）有可见性（`sales_rep_id` 指向该用户，或复用既有"报价单可见性规则"——需先查 `QuotationService` 的可见性判定逻辑，不做假设）。

**执行步骤**
1. 造 `SALES_REP` 测试用户，登录。
2. 检查左侧菜单：是否出现「价格调整审核」「更新任务」入口。
3. 直连尝试 `GET /api/cpq/price-adjust/reviews`（以销售身份的 session/token）。
4. 打开一张该销售可见的报价单，查看屏 7「价格版本」抽屉：是否可见版本轨迹；是否存在"通过/驳回/触发更新"相关按钮；单内切版本是否只能预览（不能真正切换生效）。

**断言（机械可判定）**
- 🔒 步骤 2：菜单 DOM 中**不存在**「价格调整审核」「更新任务」两个菜单项（`page.locator('text=价格调整审核').count() = 0`）。
- 🔒 步骤 3：后端返回 **`401`/`403`**（服务端强制校验，不只是前端隐藏菜单——防止"前端藏了但接口没锁，直连 URL 能绕过"）。
- 🔒 步骤 4：版本轨迹表**可见**（`materialVersions`/`revisions` 数据正常渲染）；页面上**不存在**"通过""驳回""触发更新"按钮（`count()=0`）；切版本只能进入 #18 定义的只读预览态，无法真正落库切换（尝试调用审核类端点均应 403）。
- 🔒 对照组：以 `PRICING_MANAGER`（`test_finance_c87a27ab`）身份重复步骤 2/3，应看到菜单、接口返回 200（证明权限差异确实是角色决定的，不是环境本身坏了）。

**证据形式**：`SALES_REP` 用户创建记录；两种角色下菜单截图对比；直连 API 的 401/403 响应；对照组 200 响应。

---

---

### #21　🔴 核价侧联动（核心）：料号升版后核价卡片按本版客户元素价重算且数值变化；核价单 `costing_order` frozen 快照逐字节不变

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3，前置条件已就绪：COMP-0049「元素单价」字段已由 V366 配好，实测 `expand-driver` 取到 `Ag=216770.0`，**本条现在可测，不再标"前置未就绪"**） | SQL 断言（双向，逐字段） |

**前置数据**：复用现网真实数据——`QT-20260726-0018`（`status=PENDING`，材料 `S-3120014539`）已有关联 `costing_order`（`id=f07efd64-fdbe-4f12-bb1e-a54a578324d1`）。将该料号纳入 `CUST-0001` 调价范围，元素清单含 `Ag`，生成版本 `V1`（`Ag` 新价 ≠ 当前取价结果，如取价函数当前返回 `Ag=216770.0000`，构造 `element_price_version_item` 里 `Ag` 的 `current_price` 为一个明确不同的新值，如 `220000.0000`，便于断言"确实变了"而非凑巧相同）。

**执行步骤**
1. **升版前**采集基线：
   ```sql
   -- 核价单 frozen 快照全字段哈希（整行序列化，任何一列变了都会体现）
   SELECT md5(row_to_json(co)::text) AS h_full, md5(co.frozen_dto::text) AS h_frozen,
          co.total_amount, co.costing_total_amount
   FROM costing_order co WHERE co.id = 'f07efd64-fdbe-4f12-bb1e-a54a578324d1';
   -- 报价单内的核价卡片值（本次要变的对象）
   SELECT li.costing_card_values FROM quotation_line_item li
   WHERE li.quotation_id = '5ae37319-3134-402a-aa46-23c0e03aa45f' AND li.product_part_no_snapshot='S-3120014539';
   ```
2. `POST /reviews/approve` 通过该料号，等待 job 完成。
3. 升版后重新执行步骤 1 的两条查询。

**断言（机械可判定，双向缺一不可）**
- 🔒 **正向**：`li.costing_card_values` 中 `Ag` 对应的元素材料成本字段值 **= 220000.0000**（本版价，精确值，非"有变化"）；且该料号核价侧 SUBTOTAL（走 `CostingSubtotalUtil#extractUnitSubtotal` 提取）升版前后**数值不同**。
- 🔒 **反向**：`h_full`（`costing_order` 整行 `row_to_json` 的 md5）升版前后**完全相同**；`h_frozen`（`frozen_dto` 单独的 md5）**完全相同**；`total_amount`/`costing_total_amount` 数值**完全相同**——本次升版按 §11.8.2 只应动 `quotation_line_item.costing_card_values`，`costing_order` 表**一个字节都不该碰**。

**证据形式**：升版前后两组 SQL 查询完整输出（含 md5 与精确数值）；`costing_card_values` 里 `Ag` 字段的 diff。

---

### #22　双侧同源可复现：审核页显示的「调整后核价」与通过后实际重算出的核价卡片值逐位一致

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据**：复用 #21 场景，生成版本后先让该料号的预算算完（`budgetStatus=READY`）但**先不通过**。

**执行步骤**
1. `GET /reviews/{reviewId}`，读 `comparisonColumns` 中"产品总价"列（或已配置的自定义列）的 `costingAdjusted` 字段——这是**预算值**，来自 `material_price_review_column` 落库。
2. `POST /reviews/approve` 通过，等待 job 完成。
3. 升版后，用同一套算法（`CostingSubtotalUtil#extractUnitSubtotal`）从实际 `costing_card_values` 重新提取同一指标，得到**实际值**。

**断言（机械可判定）**
- 🔒 `SELECT costing_adjusted FROM material_price_review_column WHERE review_id=... AND column_id='col-default'`（预算值）与步骤 3 提取的实际值 **数值完全相等**（`abs(预算-实际)=0`，不是"接近"）。
- 🔒 该结论成立的结构性依据（走查代码佐证）：`material_price_review_column.costing_adjusted` 是通道 B `dryRun=true` 时算出并落库的，`costing_card_values` 是 `dryRun=false` 时算出并落库的——**两者是同一段代码在 `dryRun` 分叉前后的产物**，理论上不可能出现结构性分叉，本条断言是对这个结构性保证的直接验证。

**证据形式**：预算值与实际值的 SQL/计算过程并排对比。

---

### #23　比对列可配置：默认列=产品总价；可另加任意「报价侧页签指标 ↔ 核价侧页签指标」比对列

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1）。⚠️ **前端屏 1 已合入（commit `66aa072c`），但屏 3~8 尚未开发**——本条 UI 断言（比对列配置区的连线抽屉交互）暂缓，标注「待屏 1 比对列配置区 / 屏 4 抽屉交付后执行」，本批只做 API 层 | API 测试 + SQL 断言 |

**前置数据**：`CUST-0001` + 系列「罗克韦尔模板1」（`templateSeriesId=a91209e6-743b-4ebe-9ede-5b2737077674`）。该系列下需要有真实 `componentId` 可引用——取该系列模板（`70f1b149-b0d9-4cb1-9245-6c3cee1bc3af`）里"材料成本"组件的 `componentId`（走 `template_component` 关联查询）。

**执行步骤**
1. `GET /price-adjust/comparison-columns?customerNo=CUST-0001&templateSeriesId=a91209e6-743b-4ebe-9ede-5b2737077674`（未配置态）。
2. `PUT` 同端点，`columns` 追加一条 `kind=TAB_PAIR`，`quoteComponentId=<材料成本组件id>`、`quoteMetric=材料小计`、`costingComponentId=<核价物料与元素BOM组件id>`、`costingMetric=__TAB_TOTAL__`、`threshold=2.00`。
3. 再次 `GET` 同端点。

**断言（机械可判定）**
- 🔒 步骤 1：`configured=false`，`columns` 只含 1 条 `kind=PRODUCT_TOTAL`，`removable=false`。
- 🔒 步骤 3：`configured=true`，`columns` 含 2 条（默认列 + 新加列），新加列各字段与步骤 2 提交值逐字段相等。
- 🔒 `SELECT columns FROM comparison_column_config WHERE customer_no='CUST-0001' AND template_series_id='a91209e6-743b-4ebe-9ede-5b2737077674'`（jsonb）与步骤 3 响应的 `columns` 内容一致（纯读落库值，无实时拼装差异）。

**证据形式**：3 次 API 调用响应；`comparison_column_config` 落库 SQL 输出。

---

### #24　配置隔离（双向）：客户 A 的比对列配置不影响客户 B；同一客户下模板系列甲的配置不影响系列乙；新组合默认只产品总价且不可删

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 + API 测试 |

**前置数据**
- 现网**仅 1 个真实客户**（`CUST-0001`）——「客户 A 不影响客户 B」这一半无法用两个真实客户验证，改用**合成 `customer_no`** 做纯配置层隔离验证（`comparison_column_config.customer_no` 是普通 `VARCHAR`、无 FK 约束，允许不挂靠真实 `customer` 表记录）：`INSERT INTO comparison_column_config (customer_no, template_series_id, columns) VALUES ('CUST-SYNTH-B', '<任意已存在的 template_series_id，如 a91209e6-...>', '[{"id":"col-x","kind":"TAB_PAIR", ...}]')`。
- 系列甲/乙用真实数据：`a91209e6-743b-4ebe-9ede-5b2737077674`（罗克韦尔模板1）与 `61363e67-e8a0-4cb0-943c-79f29f6f0dcb`（罗克韦尔模板2）。

**执行步骤**
1. 给 `CUST-0001 × 系列甲` 配一条自定义列（同 #23 步骤 2）。
2. 直接查询 `CUST-SYNTH-B`（合成客户）在**同一个** `template_series_id` 下的配置：`GET /comparison-columns?customerNo=CUST-SYNTH-B&templateSeriesId=a91209e6-...`。
3. 查询 `CUST-0001 × 系列乙`（`61363e67-...`）的配置：`GET /comparison-columns?customerNo=CUST-0001&templateSeriesId=61363e67-...`。
4. 查询一个**从未配置过**的组合（如 `CUST-0001 × 系列乙` 若步骤 3 前从未配过）。

**断言（机械可判定）**
- 🔒 步骤 2（跨客户隔离）：`CUST-SYNTH-B` 的返回**不包含**步骤 1 给 `CUST-0001` 加的那条自定义列（除非步骤 0 显式为 `CUST-SYNTH-B` 单独造了配置，那种情况下应看到的是**它自己独立造的那条**，而非 `CUST-0001` 的）。
- 🔒 步骤 3（同客户跨系列隔离）：`CUST-0001 × 系列乙` 的 `columns` **不含**步骤 1 加给系列甲的那条自定义列。
- 🔒 步骤 4：`configured=false`，`columns` 恰好 1 条（`kind=PRODUCT_TOTAL`），`removable=false`（`PUT` 尝试删除该列应被拒绝或被服务端强制保留，需额外验证 `PUT columns:[]` 或不含该列的提交是否被拒 `400`/或被静默补回）。

**证据形式**：4 次查询响应 JSON 并排对比；`comparison_column_config` 表按 `(customer_no, template_series_id)` 分组的 SQL 输出。

---

### #25　🔴 行标红判定（核心）：产品总价差异为正但某自定义列差异 <0 → 整行必须标红；全橙 → 不得标红

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2，硬约束 19 专测条目）。⚠️ UI 渲染断言（`rowRed` 驱动的行背景色）标注「待屏 3 待办池交付后执行」，本批做 API 层 `rowRed`/`breachedCount` 断言 | SQL 断言 + API 测试 |

**前置数据**：为 `CUST-0001 × 系列甲` 配 **3 个比对列**（对齐 `🔴1 🟠1 / 3列` 的原文标记，必须凑够 3 列才能验证"红橙分开计数+第三列静默 NORMAL 不进计数"）：
- Col1 = 产品总价（默认列，`threshold=5.00`）。
- Col2 = 自定义列 A（`threshold=2.00`）。
- Col3 = 自定义列 B（`threshold=2.00`）。

料号 X 的预算构造为：Col1 `diffAdjusted=+4.05`（`0≤4.05<5` → `AMBER`）；Col2 `diffAdjusted=-1.90`（`<0` → `RED`）；Col3 `diffAdjusted=+10.00`（`≥threshold` → `NORMAL`）。

**执行步骤**
1. `GET /reviews/{reviewId}`（料号 X），核对 `comparisonColumns` 三列各自 `status`。
2. 查询待办池列表接口，核对 `breachedCount`/`amberCount`/`rowRed`/汇总标记字段。
3. **反向用例**：另造料号 Y，三列全部落在 `[0, threshold)` 区间（全 `AMBER`）。

**断言（机械可判定）**
- 🔒 料号 X：`comparisonColumns` 里 Col1.status=`AMBER`、Col2.status=`RED`、Col3.status=`NORMAL`。
- 🔒 🔒 **核心断言**：`SELECT breached_count, amber_count, column_count FROM material_price_review WHERE id=X的reviewId` = `(1, 1, 3)`；`rowRed = true`（`breached_count > 0`，**不是** `产品总价diff < 0`——产品总价本身是 `AMBER` 不是 `RED`，若实现误写成"产品总价判定"，此处 `rowRed` 会错判为 `false`，这条断言正是防这个）。
- 🔒 待办池汇总标记字符串 = `🔴1 🟠1 / 3列`（与原型屏 3 样例逐字符一致）。
- 🔒 料号 Y（反向用例）：`breached_count=0`，`rowRed=false`，标记 = `🟠3 / 3列`（**不得**因为有橙就标红）。

**证据形式**：X/Y 两个料号的 `GET /reviews/{reviewId}` 与列表接口响应 JSON；`material_price_review` 汇总列 SQL 输出；标记字符串的逐字符对比。

---

### #26　比对列配置变更重算：改动配置并保存 → 该「客户 × 模板系列」下 `待处理` 料号预算立即（异步）刷新；`已通过`/`已驳回` 不动

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | API 测试 + SQL 断言（🔄 **返修口径**：E14-3 下重算是**异步**的，不能断言"保存后立刻同步变新值"，必须经过 `budget_status` 中间态） |

**前置数据**：`CUST-0001 × 系列甲` 下有 2 条待办：`PENDING` 的料号 P（budget 已 `READY`），`APPROVED` 的料号 Q（历史已通过）。

**执行步骤**
1. 记录 P、Q 当前 `material_price_review_column` 内容（旧配置下算出的值）。
2. `PUT /price-adjust/comparison-columns`（改阈值或加列），响应含 `budgetRecomputeTriggered:true, affectedReviewCount`。
3. 🔒 **异步轮询**：立即查询 P 的 `budget_status`（预期短暂进入 `QUEUED`/`COMPUTING`），每隔一段时间轮询直到转回 `READY`（设置合理超时，如 10~30s，超时判失败）。
4. Q 的 `budget_status`/`material_price_review_column` 在整个过程中重复查询。

**断言（机械可判定）**
- 🔒 步骤 2 响应 `affectedReviewCount` **等于**该「客户 × 模板系列」下 `PENDING` 状态的料号数（本例 = 1，即 P；不含 Q）。
- 🔒 步骤 3：P 的 `budget_status` 曾经历过非 `READY` 的中间态（若实现是完全同步瞬间完成，允许"轮询第一次就已是 READY 且新值已生效"作为合规结果，但**不允许**"P 的 `material_price_review_column` 值在配置保存后长时间未更新"）；轮询结束时 P 的 `material_price_review_column` 内容**必须反映新配置**（新阈值/新增列已体现在数值与 `status` 分类上）。
- 🔒 **`APPROVED` 的 Q 全程 `budget_status` 与 `material_price_review_column` 内容不变**（`SELECT md5(...)` 或逐字段对比，配置变更**不得**触碰已审核过的记录——审核依据必须可追溯，硬约束不变）。
- 🔒 若额外构造一个**其他模板系列**（如系列乙）下的 `PENDING` 料号 R，本次改系列甲配置**不应触发** R 的重算（`R.budget_status` 不进入 `QUEUED`/`COMPUTING`，§11.5.4 收窄范围"仅该客户×模板系列"）。

**证据形式**：改配置前后 `material_price_review_column` 内容对比（P/Q/R 三条分别记录）；`budget_status` 轮询过程日志（时间戳序列）。

---

### #27　列表与抽屉逐位一致：屏 3 汇总标记的红/橙计数与屏 4 抽屉逐列明细的 `status` 分布完全一致

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1）。⚠️ UI 层面的"列表页 vs 抽屉页肉眼对照"待屏 3/4 交付后再做人工复核，本批做 API/SQL 层的落库口径一致性验证 | SQL 断言 |

**前置数据**：复用 #25 场景的料号 X（3 列，1 红 1 橙 1 正常）。

**执行步骤**
1. `SELECT breached_count, amber_count, column_count FROM material_price_review WHERE id=X的reviewId`（列表页汇总口径）。
2. `SELECT status, count(*) FROM material_price_review_column WHERE review_id=X的reviewId GROUP BY status`（抽屉页明细口径）。

**断言（机械可判定）**
- 🔒 步骤 2 里 `status='RED'` 的计数 **=** 步骤 1 的 `breached_count`；`status='AMBER'` 的计数 **=** `amber_count`；`status IN ('RED','AMBER','MISSING','STALE','NORMAL')` 总计数 **=** `column_count`。
- 🔒 两处**同读同一份落库数据**（`material_price_review` 的冗余汇总列本应是 `material_price_review_column` 的预聚合结果，而非独立实时计算）——走查代码确认汇总列写入逻辑是"预算落库时一并计算好冗余汇总列"，而非"列表接口临时 `COUNT()`"（若是后者，理论上也会数字对，但不满足"免 JOIN 免 N+1"的设计目标，需在报告里注明是否符合设计意图）。

**证据形式**：两条 SQL 的输出对比表。

---

### #28　🔴 升版不被 saveDraft 回滚（核心）：料号升版后写 `snapshot_rows`；再执行一次 saveDraft、重新打开 → 价格仍是新价

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3，与 #14 #21 #40 #41 #64 并列不接受口头"已通过"） | SQL 断言 + API 测试 |

**🔒 顺序是本条的全部价值所在，不可打乱**：升版 → 断言 `snapshot_rows` 已新价 → **再执行一次真实 `saveDraft`** → 重新打开（`GET`）→ 断言价格仍是新价。少了"再 saveDraft"这一步，测的只是 #14/#21 已经测过的东西，测不出"销售存一次草稿就被 `ensureCardValues` 从旧 `snapshot_rows` 重建、新价静默回滚"这个本期最隐蔽的失败模式。

**前置数据**：料号 A（`M-ISO-A`，复用 #14 场景或另造），在 `CUST-0001` 调价范围+元素清单内，其 `DRAFT` 单已生成版本并待审。

**执行步骤**
1. `POST /reviews/approve` 通过 A，等待 job 完成。
2. `SELECT snapshot_rows FROM quotation_line_component_data WHERE line_item_id=... AND component_id=...`，断言 `Ag` 单价字段 = 本版价（同 #14 正向断言）。
3. 🔒 **关键步骤**：对该报价单执行一次**真实** `PUT /api/cpq/quotations/{id}/draft`（saveDraft 端点），payload 用**该单当前从后端 `GET` 到的最新数据**回填提交（模拟销售正常保存，不刻意构造陈旧 payload——那是 #40/#41 的场景，本条测的是"哪怕是正常保存也不该回滚"）。
4. 重新 `GET /api/cpq/quotations/{id}`（模拟"重新打开"），再次查询 `snapshot_rows` 与渲染出的 `quote_card_values`。

**断言（机械可判定）**
- 🔒 步骤 2：`snapshot_rows` 里 `Ag` 单价 = 本版价（精确值）。
- 🔒 🔒 **核心断言**：步骤 4 的 `snapshot_rows` 与 `quote_card_values` 中 `Ag` 单价**仍然 = 本版价**，与步骤 2 完全相同（saveDraft 前后 md5 不变，或至少该字段精确值不变）——**不得**退回升版前的旧价。
- 🔒 若发现步骤 4 价格退回旧价：判定为**该失败模式命中**，须定位是否走了"只重算 `quote_card_values` 不写 `snapshot_rows`"的实现路径（`CardSnapshotService` 全类只读 `snapshot_rows` 从不写，是本条要防的具体代码坑）。

**证据形式**：三个时点（升版后 / saveDraft 前 / saveDraft 后重新打开）的 `snapshot_rows`/`quote_card_values` 完整查询输出；saveDraft 请求的 payload 记录。

---

### #29　手工值按列清除：某行手改过元素单价与毛重两个字段 → 升版后单价=本版价（覆盖），毛重仍是手改值（未误伤）

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**：料号 A 的 driver 行，在其 `row_data`/`editRows`（视具体存储位置，走查 `CardEffectiveRows` 确认字段合成口径）中**直接 SQL 构造**存量手改值（模拟"机制生效前销售已经手改过"的历史状态，因为一旦机制生效该元素单价列前端会变只读、无法再通过 UI 产生新的手改值）：
```sql
-- 示例：把该行的"元素单价"改成一个明显不同于取价结果的手改值 9999，"毛重"改成 88.8
-- 具体写法依 row_data/editRows 的真实 JSON 结构而定，需先 SELECT 出该行原始 JSON 再定点替换对应字段
```

**执行步骤**
1. 造完手改值后，先确认该行当前渲染出的单价确实是 `9999`（手改值优先级最高生效，佐证 `CardEffectiveRows` 的合成口径）。
2. `POST /reviews/approve` 通过该料号，等待完成。
3. 查询升版后该行的单价字段与毛重字段。

**断言（机械可判定）**
- 🔒 单价字段 **= 本版价**（如 220000.0000，不是 `9999`）——手改值被按列覆盖清除。
- 🔒 🔒 毛重字段 **仍 = 88.8**（手改值原样保留，未被"整行清空"式的误伤实现波及）。
- 🔒 两个断言必须**同时满足**——只测单价覆盖、不测毛重保留是不完整的（防止实现走"整行清空再重算"这种简单粗暴但误伤其他手改字段的写法）。

**证据形式**：造数前后与升版后三个时点的行数据 JSON 对比（单价、毛重两个字段分别标注）。

---

### #30　导出走新价：料号升版后**不打开报价单**、直接导出 Excel → 导出文件中元素单价与金额均为新价

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | API 测试 |

**🔒 断言前提（本条最容易漏测的一步）**：整个用例过程中**禁止**出现任何 `GET /api/cpq/quotations/{id}`（详情/编辑页数据接口）调用——一旦打开过报价单，`quoteExcelValues` 可能因为其他渲染路径被提前重算，测不出"导出接口自己 fallback 重算"这条能力。

**前置数据**：料号 A 所在报价单，升版前先确保 `li.quote_excel_values` **非空**（模拟"之前导出过一次"的历史状态；若当前为空可先调用一次导出接口预热，再继续后续步骤）。

**执行步骤**
1. `POST /reviews/approve` 通过 A，等待 job 完成。
2. **立即** `SELECT quote_excel_values FROM quotation_line_item WHERE ...`，确认 S7 已生效。
3. 🔒 **不经过任何 `GET` 报价单详情接口**，直接调用 `GET /api/cpq/quotations/{id}/export-excel-view`（对应 `ExcelViewService.exportExcelView`，已确认服务端 fallback 会在快照为空时重算）。
4. 再次 `SELECT quote_excel_values`，检查是否已被步骤 3 的调用重新物化。

**断言（机械可判定）**
- 🔒 步骤 2：`li.quote_excel_values IS NULL`（升版 S7 已置空，验证"导出快照失效"这一半）。
- 🔒 🔒 **核心断言**：步骤 3 响应中该料号 `Ag` 元素单价字段 = 本版价（精确值），对应金额列 = 用本版价重算后的值——证明 fallback 重算确实生效，**不依赖"先打开过报价单"这个前提**。
- 🔒 步骤 4（可选加固）：确认导出调用本身是否顺带把 `quote_excel_values` 重新物化（无论是否物化都不影响步骤 3 的核心结论，仅作实现细节记录）。

**证据形式**：升版后立即查询 `quote_excel_values` 为空的 SQL 输出；`export-excel-view` 响应 JSON（含新价数值）；全程请求日志（证明未调用过报价单详情接口）。

---

---

### #31　🔴 子件吃版本（最容易测不出来）：父件料号升版后，闭包展开出的子件行元素单价同样切到本版价

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**🔒 必须用 `S-80011` 构造**——已实测 QUOTE 侧 `material_bom_item` 只有 2 条父子关系：`S-3120014539`（**无子件**）与 `S-80011 → 00002`；用 `S-3120014539` 测闭包视图只返父件自己 2 行，**根本测不出子件行**。

**前置数据（已核实真实结构）**
- 罗克韦尔**模板2**（`customer_template_id=9e2e6ef3-3865-4e90-a509-803761b6e837`）与**模板3**（`7fd29ac2-…`/`1badebec-…`/`0317efe5-…`，三个版本共享 `componentId=a91f5aaa-7a09-452a-aa42-777e71fc736e`）的「材料成本」组件走**闭包形态** `mc_view`（已用 `sql_template ILIKE '%bom_closure%'` 查实，且用 `template_component` 反查确认这两个系列绑定的正是闭包版 `mc_view`）；**模板1 是平铺形态，不能用来测本条**。
- `S-80011` 当前（`is_current=true`）在 `element_bom_item` 里只有它自己名下的 2 行（`characteristic=2003`：`Ag`/`C`），**子件 `00002` 目前没有任何 `element_bom_item` 行**——需要新插入才能让闭包视图真正展开出"子件行"：
  ```sql
  INSERT INTO element_bom_item
    (id, system_type, customer_no, material_no, characteristic, component_no, seq_no,
     composition_qty, issue_unit, hf_part_no, is_current, material_part_no, created_at, updated_at)
  VALUES
    (gen_random_uuid(), 'QUOTE', 'CUST-0001', '00002', 'RECIPE', 'Ag', 1,
     0.02, 'g/KPCS', NULL, true, '00002', now(), now());
  ```
- 用真实"新增产品"流程（而非手工拼 `snapshot_rows`）把 `S-80011` 加进一张 `CUST-0001` 的报价单（`customer_template_id=7fd29ac2-b2d3-48b4-8e31-9b107ae9eedd`，模板3 v1.0），保存草稿，让 driver expand 走真实闭包 SQL——此时该行「材料成本」组件应展开出**2 行 `Ag`**：一行归属 `S-80011` 自己（`characteristic=2003`），一行归属子件 `00002`（刚插入的那行）。
- `CUST-0001` 调价范围含 `S-80011`，元素清单含 `Ag`，生成版本 `V1`（`Ag` 新价，取一个明确区别于当前取价结果的值，如 `220000.0000`）。

**执行步骤**
1. 升版前：`SELECT snapshot_rows FROM quotation_line_component_data WHERE line_item_id=... AND component_id='a91f5aaa-7a09-452a-aa42-777e71fc736e'`，确认确实有 2 行 `元素=Ag`（一行 `_归属料号=S-80011`、一行 `_归属料号=00002`，字段名依实际视图输出列而定）。
2. `POST /reviews/approve` 通过 `S-80011`，等待完成。
3. 升版后重新查询该行 `snapshot_rows`。

**断言（机械可判定）**
- 🔒 升版前：2 行 `Ag` 单价均为升版前旧价（一致，佐证两行确实读的同一个取价函数结果）。
- 🔒 🔒 **核心断言**：升版后，`jsonb_path_query_array` 提取该组件 `snapshot_rows` 中**所有** `元素=Ag` 的行的单价字段，结果集**每一个值都 = 220000.0000**——**特别是归属子件 `00002` 的那一行**（这正是本条要防的"JOIN 键写成 `cep.material_no = ebi.material_no` 导致子件行 JOIN 不中、静默不吃版本"的失败模式；若该子件行单价仍是旧价，判定为失败，需定位 JOIN 键是否与 `hf_part_no` 表达式逐字一致）。

**证据形式**：升版前后 `snapshot_rows` 的完整 JSON（标注两行分别的"归属料号"与单价）；新插入 `element_bom_item` 子件行的 SQL 记录。

---

### #32　元素列显式绑定 + 保存期校验：三下拉候选受限 / 缺配拒绝保存 / 未接取价函数可空保存 / 存量组件迁移预填正确

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1）+ 前端自测（`fronttask.md` §12.1，三下拉渲染） | 单元测试 + API 测试 |

**前置数据**：真实组件——`COMP-0049`（核价「物料与元素BOM」，V366 已配）；报价侧「材料成本」组件 `COMP-0021`/`COMP-0027`/`COMP-0102`（对应 `componentId` 需先按名称查出，`SELECT id, name, code FROM component WHERE code IN ('COMP-0021','COMP-0027','COMP-0102')`）；另找 1 个**未接取价函数**的普通组件（如「加工费」`jg_view` 系列任一）。

**执行步骤**
1. **①** `GET /api/cpq/components/{COMP-0021的id}`（或前端组件编辑器），核对候选下拉的取值集合。
2. **②** `PUT /api/cpq/components/{id}` 对一个**接了取价函数**（`sqlTemplate` 含 `f_material_element_price`/`f_customer_element_price`）但 `elementCodeField` 留空的组件提交保存。
3. **③** `PUT /api/cpq/components/{未接取价函数组件id}`，三项全留空提交保存。
4. **④** 查询三个报价侧组件与 `COMP-0049` 迁移后的落库值。

**断言（机械可判定）**
- 🔒 ①：三个下拉（`elementCodeField`/`elementPriceField`/`elementCurrencyField`）候选**只来自该组件已有字段**（`fieldNameOptions`，与该组件 `fields` 表已定义的字段名集合逐一比对，不包含任何该组件没有的字段名）。
- 🔒 ②：响应 **`400`**，`code="COMPONENT_ELEMENT_BINDING_REQUIRED"`，`missingFields` 含 `elementCodeField`（**不是** `200` 静默保存、也不是只给警告不拦截）；`SELECT element_code_field FROM component WHERE id=...` 保存前后**不变**（校验失败不落库）。
- 🔒 ③：响应 `200`，三项均落库为 `NULL`，保存成功。
- 🔒 🔒 **④ 核心断言（迁移预填正确性，防"迁移把料号列/名称列以外的三项忘配"）**：
  - `SELECT element_code_field, element_price_field, element_currency_field FROM component WHERE id=(COMP-0049的id)` **= `('元素代码','元素单价',NULL)`**（**注意字段名叫「元素代码」不是「元素」**，这是推导算法的反例样本，专防"逐客户列名不同、禁止靠约定列名猜"被写死成"元素"这个常见值）。
  - `COMP-0021`/`COMP-0027`/`COMP-0102` 三个的 `element_code_field/element_price_field` **必须是推导算法自己算出来的**（`= ('元素','元素单价',NULL)`），🔒 **验收执行者不得直接断言这个值是"应该等于元素/元素单价"就算过**——必须额外确认这三个值**不是**测试脚本或运维手工写死的（走查迁移脚本/`GET .../element-binding-suggest` 的 `confidence` 字段应为 `HIGH`），否则测的是"我手写的断言对不对"而不是"推导算法对不对"。

**证据形式**：①下拉候选列表与组件 `fields` 定义的对比；②③两次 `PUT` 的响应与前后 `SELECT`；④四个组件的落库值 + `element-binding-suggest` 响应（含 `confidence`/`alias`）。

---

### #33　🔴 多行同元素全覆盖：父件 + ≥2 子件、多行元素同为 Ag → 升版后每一行都切到本版价

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**：在 #31 基础上**再加一个子件**（真实数据只有 1 个子件 `00002`，需再合成 1 个才能凑够"≥2 子件"）：
```sql
-- 第二个子件关系（纯测试用途，material_bom_item 无外键约束限制新增合成节点）
INSERT INTO material_bom_item (id, material_no, component_no, characteristic, customer_no, system_type, is_current)
VALUES (gen_random_uuid(), 'S-80011', '00003', 'RECIPE', 'CUST-0001', 'QUOTE', true);

INSERT INTO element_bom_item
  (id, system_type, customer_no, material_no, characteristic, component_no, seq_no,
   composition_qty, issue_unit, is_current, material_part_no, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'QUOTE', 'CUST-0001', '00003', 'RECIPE', 'Ag', 1,
   0.015, 'g/KPCS', true, '00003', now(), now());
```
此时 `S-80011` 的闭包展开应含 **3 行 `Ag`**：父件自己（`characteristic=2003`）+ 子件 `00002` + 子件 `00003`（跨不同料号，行键「料号+材质+元素」三元组天然不同）。同一版本 `V1`（`Ag` 新价 `220000`）。

**执行步骤**
1. 重新保存/刷新该行，确认 `snapshot_rows` 里确实出现 3 行 `Ag`。
2. `POST /reviews/approve` 通过，等待完成。
3. 升版后查询该行全部 `Ag` 行。

**断言（机械可判定）**
- 🔒 升版前：3 行 `Ag` 分属 3 个不同"归属料号"（`S-80011`/`00002`/`00003`），单价均为旧价。
- 🔒 🔒 **核心断言**：升版后，`jsonb_path_query_array` 提取的 3 行 `Ag` 单价**全部 = 220000.0000**，**逐行验证，不允许只查第一条**（本条专防"按 rowKey 对齐"被实现成一对一——若实现只更新了数组第一个匹配到的 `Ag` 行，其余两行会静默漏改，此断言要求 `count(distinct 单价) = 1 AND 该值 = 220000.0000 AND 命中行数 = 3`）。

**证据形式**：升版前后 3 行 `Ag` 完整 JSON 数据（含各自归属料号与单价）；`jsonb_path_query_array` 查询与结果计数。

---

### #34　销售改过元素列 → 按改后的值匹配；S4 不清元素键；再升版一次结果仍按改后值（不漂移）

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**⚠️ 范围收窄说明**：driver 行的元素列已按 §11.15.2.6(2) 改为**只读**（值来自 BOM 基础数据），销售改不了；本条"坑3"的适用范围**收窄到手动行**——手动行元素列本就可编辑，是唯一还存在"销售改过元素列"这个动作的地方。

**前置数据**：某报价单手动新增一行，元素列填 `Ag`（∈清单），此时单价应自动带出 `Ag` 版本价；销售随后把元素改为 `Cu`（同样 ∈清单，直接 SQL 构造该行 `row_data` 里 `_origin='manual'` 且元素字段 `= 'Cu'`，模拟"改元素后自动带出 Cu 价"的落库结果）。生成版本 `V1`（`Cu` 新价，如 `1900.0000`，明确区别于当前取价 `Cu=1850.0000`）。

**执行步骤**
1. 升版前确认该手动行：元素字段 `= 'Cu'`，单价 = 改元素时带出的 `Cu` 当期价（非 `Ag` 的价）。
2. `POST /reviews/approve` 通过，等待完成。
3. 查询该行升版后的元素字段与单价字段。
4. 🔒 **再升版一次**：生成新版本 `V2`（`Cu` 再涨一次，如 `1950.0000`），再次通过，再次查询。

**断言（机械可判定）**
- 🔒 步骤 3：该行**取的是本版 `Cu` 价**（`1900.0000`），**不是** `Ag` 的价；元素字段**仍 = `'Cu'`**（S4 只清价格键，未被连带清空/重置回 `Ag`）。
- 🔒 🔒 **核心断言（防漂移）**：步骤 4（第二次升版）后，该行单价 **= 1950.0000**（`Cu` 的新版价），元素字段**仍 = `'Cu'`**——**不允许**出现"这次 `Cu`、下次退回 `Ag`"的漂移。此断言必须真的执行第二次升版，不能只做一次就收工。

**证据形式**：升版前 / 第一次升版后 / 第二次升版后三个时点的行数据（元素字段 + 单价字段）对比表。

---

### #35　手动行同样吃版本：元素填 `Ag` → 单价=本版价；元素填不对应 `element_code` 的值 → 该行不动

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**（复用已实测的真实无价/非元素编码样本，避免自己再去核实哪个元素无价）：
- 手动行 A：元素 = `Ag`（∈清单，有本期价）。
- 手动行 B：元素 = `银`（中文名，**不对应** `element_code`——元素编码是 `Ag` 不是"银"，走"不做名称→编码兜底"规则）。
- 手动行 C：元素 = `Ni`（已实测：调价元素清单外或取价函数返回 NULL 的样本，视 `CUST-0001` 元素清单勾选情况而定；若 `Ni` 本次未勾入清单，等价覆盖"元素不在清单"分支）。
- 手动行 D（可选加固）：元素 = `301`（已实测：非元素主表编码，取价函数对它返 NULL）。
均直接 SQL 构造 `row_data`（`_origin='manual'`），令 B/C/D 三行升版前单价保持某个"手改初值"（如 `100.00`），便于观察是否被动过。生成版本 `V1`（`Ag` 新价）。

**执行步骤**：`POST /reviews/approve` 通过后，逐行查询升版后的单价字段。

**断言（机械可判定）**
- 🔒 手动行 A：单价 **= 本版价**（同前几条的精确值断言方式）。
- 🔒 手动行 B（`银`）：单价 **仍 = 100.00**（升版前手改初值），**一个字节不变**——"银"对不上 `element_code='Ag'`，不做名称兜底换算。
- 🔒 手动行 C（`Ni`）：若 `Ni` 不在本次调价清单/取价结果为 NULL，单价 **仍 = 100.00**（不动）。
- 🔒 手动行 D（`301`）：单价 **仍 = 100.00**（不动，`301` 不是有效元素编码）。

**证据形式**：4 行升版前后单价对比表；`CUST-0001` 当次调价元素清单内容（确认 `Ni` 是否在列，避免误判）。

---

### #36　无活单料号自动锁版（D5）：不进待办池 / 指针照样推进 / 此后新建单取本期版本价；反例外——曾被驳回的不适用自动推进

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**🔒 口径依据 E14-2**：「活单」= 5 个可更新状态（`DRAFT`/`SUBMITTED`/`APPROVED`/`REJECTED`/`COSTING_REJECTED`）。某料号只存在于 `SENT`/`ACCEPTED`/`EXPIRED`/`CANCELLED` 单中，**视同不在任何活单**，走 D5。

**前置数据**（参照 #9 的直接 `INSERT` 造数手法）
- 料号 P（`M-D5-NORMAL`）：只造 1 张 `status='SENT'` 的单含该料号（`chk_q_status` 已确认 `SENT` 是合法枚举值），**不造**任何 5 态活单。
- 料号 Q（`M-D5-REJECTED`）：先在**上一版** `V0` 里把 Q 走一遍 `POST /reviews/reject`（产生一条 `REJECTED` 记录），随后确保 Q 同样**不在任何活单**中（只留一张 `SENT`/`ACCEPTED` 单或干脆没有单）。
- P、Q 均在 `CUST-0001` 调价范围与元素清单内。生成新版本 `V1`（元素价较 `V0` 有变化）。

**执行步骤**
1. `V1` 生成后，分别查询 P、Q 是否进入待办池、指针指向。
2. 对 P：以该料号新建一张 `DRAFT` 单（走真实建单流程或参照 #9 的 INSERT 手法），查询其 driver 行取到的元素单价。
3. 对 Q：同样查询其待办池状态与指针。

**断言（机械可判定）**
- 🔒 P：`SELECT count(*) FROM material_price_review WHERE version_id=(V1的id) AND material_no='M-D5-NORMAL'` **= 0**（不进池）；`SELECT version_id FROM material_price_version_ref WHERE material_no='M-D5-NORMAL'` **= V1的id**（指针照样推进，不经财务点头）。
- 🔒 P 步骤 2：新建单的该行元素单价 **= `V1` 版本价**（不是当天实时算价、也不是 `V0` 的旧价）——验证"此后新建含该料号的单，取价用本期版本价"。
- 🔒 🔒 **反例外核心断言**：Q **不进 `V1` 待办池**（同 P），但 `material_price_version_ref` 里 Q 的 `version_id` **仍 = `V0` 的 id（不变）**——**不适用**自动推进，与 P 形成对照（P 会推进、Q 不会，唯一区别是 Q"曾被驳回"）。

**证据形式**：P/Q 两条 `material_price_review`/`material_price_version_ref` 查询输出对比；P 的新建单取价结果查询。

---

### #37　提交凭证不被升版污染：`submission_snapshot` 逐字节不变；字段追溯页出现标注

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1）。⚠️ "字段追溯页标注"若对应 UI/字段尚未实现，本条 UI 部分标注待开发后执行，`submission_snapshot` 逐字节不变的 SQL 断言现在可执行 | SQL 断言 |

**前置数据**：复用真实数据 —— `QT-20260726-0018`（`status='SUBMITTED'`，`submission_snapshot` 应已非空，需先 `SELECT submission_snapshot IS NOT NULL FROM quotation WHERE quotation_number='QT-20260726-0018'` 确认；若为空则另找/另造一张已提交单）。该单料号 `S-3120014539` 纳入调价范围+元素清单，生成版本并通过升版。

**执行步骤**
1. 升版前：`SELECT md5(submission_snapshot::text) FROM quotation WHERE quotation_number='QT-20260726-0018'`。
2. `POST /reviews/approve` 通过，等待完成。
3. 升版后重新执行步骤 1 查询；额外 `GET /api/cpq/quotations/{id}`（或专门的字段追溯端点，若存在）查看是否出现"该单曾发生价格升版，本快照为提交时状态"的等价标注字段。

**断言（机械可判定）**
- 🔒 步骤 1 与步骤 3 的 md5 **完全相同**（`submission_snapshot` 逐字节不变——它是审批凭证，事后不因升版刷新）。
- 🔒 若追溯页/字段已实现：响应中出现对应标注字段且内容语义正确（人工确认文案含"价格升版"字样）；**若尚未实现**：本子项标注「待前端/后端追溯页交付后执行」，不影响 `submission_snapshot` 逐字节不变这一核心断言的独立通过。

**证据形式**：升版前后 `submission_snapshot` md5 对比；追溯页响应（若已实现）。

---

### #38　🔴 一客户多模板不漏红（核心 · 只测单模板客户发现不了）：系列甲配置不影响系列乙；系列乙差异<0 同样必须标红

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.1 + §2.2）。⚠️ 待办池/抽屉 UI 断言标注「待屏 3/4 交付后执行」，本批做 API/SQL 层 | API 测试 + SQL 断言 |

**前置数据**：用罗克韦尔真实 **3 个系列**——系列甲=`a91209e6-743b-4ebe-9ede-5b2737077674`（模板1，「材料成本」`componentId=6800cdd5-…-4997`）、系列乙=`61363e67-e8a0-4cb0-943c-79f29f6f0dcb`（模板2）。**只给系列甲配一条自定义比对列**（`quoteComponentId=6800cdd5-…`）。构造两个待办料号：X 的最近一张活单用模板1（走系列甲），Y 的最近一张活单用模板2（走系列乙，**不配置**，应落默认列）。令 Y 的默认「产品总价」列 `diffAdjusted < 0`。

**执行步骤**
1. `GET /reviews/{X的reviewId}`，核对 `comparisonColumns`。
2. `GET /reviews/{Y的reviewId}`，核对 `comparisonColumns`。
3. 查询 Y 的待办池汇总标记。
4. 追加：给**系列乙**也配一条自定义列，重新查询 Y。

**断言（机械可判定）**
- 🔒 X：`comparisonColumns` 含系列甲配置的自定义列，且该列 `status` 按其 `diffAdjusted` 正确着色。
- 🔒 🔒 **核心断言（Y 不受影响）**：Y 的 `comparisonColumns` **只含默认「产品总价」列**（`kind=PRODUCT_TOTAL`），**不出现**系列甲那条自定义列；该产品总价列 `status` **不是 `STALE`**（`STALE` 专指"配置过但 componentId 解析失败"，Y 属于"系列乙从未配置过"，应直接走默认列分支，**不产生 STALE**）。
- 🔒 Y 步骤 3：待办池标记 = `🔴1 / 1列`（产品总价本身 `<0` → `RED`），`rowRed=true`，**不得**因为"配置在系列甲、Y 找不到系列乙的配置"而误显示 `✓ 1列全通过`（这正是 §11.5.4 改判前的静默漏红失败模式，本条断言直接命中）。
- 🔒 步骤 4：给系列乙配上自定义列后，Y 的 `comparisonColumns` 出现该新列并正确着色（证明"未配置→落默认列"与"配置后→按配置走"两个分支都对，不是靠 `STALE` 掩盖了配置缺失）。

**证据形式**：X/Y 两次 `GET /reviews/{reviewId}` 响应 JSON 并排对比；Y 的待办池标记字符串；系列乙配置前后 Y 的响应 diff。

---

### #39　模板升版本不用重配：`componentId` 不变，配好的比对列自动沿用，无需重配

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | API 测试 + SQL 断言 |

**前置数据**：系列丙 = `c7705bcc-eebc-4479-a78d-f2ff69e7650e`（模板3，**三个版本 `v1.0`/`v1.1`/`v1.2` 已核实共享同一个 `componentId=a91f5aaa-7a09-452a-aa42-777e71fc736e`**，天然适合测本条）。给该系列配一条自定义比对列（引用该 `componentId`）。

**执行步骤**
1. `GET /price-adjust/comparison-columns?customerNo=CUST-0001&templateSeriesId=c7705bcc-…`，记录配置内容与 `SELECT updated_at FROM comparison_column_config WHERE template_series_id='c7705bcc-…'`。
2. 构造一个走**模板3 v1.1**（`customer_template_id=1badebec-…`）的新料号/新单，令其进入待办池。
3. 查询该料号抽屉的 `comparisonColumns`。
4. 重新执行步骤 1 的查询，确认配置本身未被"升版"这个动作动过。

**断言（机械可判定）**
- 🔒 步骤 3：走 v1.1 模板的料号，`comparisonColumns` **直接命中**步骤 1 配置的自定义列（无需为 v1.1 单独再配一次——因为 `comparison_column_config` 按 `template_series_id` 存，不按 `template_id`）。
- 🔒 步骤 4：`comparison_column_config` 的 `updated_at`/内容与步骤 1 **完全相同**（模板升版本这个动作本身**不触发**配置表任何写入）。

**证据形式**：步骤 1/4 两次配置查询对比（确认零变化）；走 v1.1 的料号抽屉响应（确认命中配置）。

---

### #40　🔴 陈旧页面保存不退价 · driver 行（核心）

> ## 🚨🚨🚨 顺序绝对不可颠倒 🚨🚨🚨
> **必须严格按此顺序执行，任何一步提前或错序都会导致测不出问题：**
> **① 先打开报价单页面（此刻前端持有旧价）→ ② 保持该页面不刷新 → ③ 后台执行升版（新价生效）→ ④ 回到步骤①那个陈旧页面点保存。**
> 若写成"升版后才打开页面"，前端 `row_data` 里已经是新价，**这条用例测不出任何问题，是无效测试**。

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3 + `fronttask.md` §12.3，与 #14 #21 #28 #41 #64 并列） | E2E(Playwright)（权威证据）+ API 测试（等效序列，辅助证据） |

**前置数据**：料号 A（driver 行，元素 `Ag` ∈ 调价清单），当前价 `5450`；`CUST-0001` 调价范围内；已生成待审版本 `V1`（`Ag` 新价 `5820`），**先不通过**。

**执行步骤（Playwright，权威证据，🔒 步骤顺序即上方警告框内容）**
1. **①** Playwright `page.goto('/quotations/{id}/edit')`，进入 Step2，确认页面渲染的 `Ag` 单价 = `5450`（旧价）。**不要刷新、不要关闭这个 page**。
2. **②** 保持该 `page` 对象存活（不执行任何会重新拉取数据的操作）。
3. **③** 用另一个 API 调用（同一测试脚本内，不通过这个 `page`）：`POST /reviews/approve` 通过 A，等待 job 完成，确认后端 `snapshot_rows` 已是 `5820`（服务端状态已变，但**这个浏览器页面对此一无所知**）。
4. **④** 回到步骤①的 `page`，**不刷新**，直接点击「保存」按钮（触发前端已加载的旧数据回传 `saveDraft`）。

**执行步骤（API 等效序列，辅助证据，用于无浏览器环境快速复现）**
1. `GET /api/cpq/quotations/{id}` 拿到此刻的完整 `row_data`/`snapshot_rows`（此时 `Ag=5450`），**保存这份 payload 副本**——模拟"浏览器已加载旧数据"。
2. `POST /reviews/approve` 通过 A，等待完成，确认后端已是 `5820`。
3. 用**步骤 1 保存的旧 payload 副本**（`Ag=5450`）原样调用 `PUT /api/cpq/quotations/{id}/draft`（模拟"陈旧页面点保存"）。

**断言（机械可判定）**
- 🔒 步骤 4/3（保存动作）**必须成功**（HTTP `200`，**不报错**——归位机制应静默纠正，而不是拒绝这次保存）。
- 🔒 🔒 **核心断言**：保存后 `SELECT snapshot_rows FROM quotation_line_component_data WHERE ...` 中 `Ag` 单价 **仍 = `5820`**（本版价），**未退回 `5450`**——即便前端提交的 payload 里明确带着 `5450`，归位机制应在"落库 → 归位 → 重算"这个既定插入点上把它纠正回来。
- 🔒 重新打开报价单（再 `GET` 一次）确认渲染出的单价**仍是 `5820`**（三重确认：保存成功 + 落库是新价 + 重新渲染也是新价）。

**证据形式**：Playwright 执行日志（含每步时间戳，证明顺序）+ 截图（步骤①旧价截图 / 步骤④保存后重新渲染截图）；API 等效序列的完整请求/响应记录；保存前后 `snapshot_rows` 对比。

---

---

### #41　🔴 陈旧页面保存不退价 · 手动行（与 #40 是不同的失败路径，必须单独测）

> ## 🚨🚨🚨 顺序绝对不可颠倒（同 #40）🚨🚨🚨
> **① 先打开报价单页面 → ② 保持不刷新 → ③ 后台执行升版 → ④ 回到陈旧页面点保存。**

**🔒 为什么不能用 #40 的结果替代本条**：手动行的值**直接来自 `row_data` 本身**，前端提交什么就是什么；`mergeRowDataInputsIntoEdits` 的循环边界 `int n = Math.min(baseRows.size(), rowData.size())` 中 `baseRows` **只含 driver 行**，手动行排在数组尾部、下标 **≥ driverCount**，**merge 逻辑根本迭代不到它**。也就是说 #40（driver 行）的归位插入点是"落库后归位再重算"，但手动行完全绕开了 merge 这一步——两条防线不同，`#40 通过` **不代表** `#41 也通过`，必须独立执行。

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3 + `fronttask.md` §12.3） | E2E(Playwright)（权威）+ API 测试（等效序列） |

**前置数据**：某手动新增行，元素 `Ag`（∈调价清单），当前单价 `5450`；`V1`（`Ag` 新价 `5820`）待审。

**执行步骤（Playwright，同 #40 的四步顺序，目标换成手动行）**
1. 打开报价单编辑页，确认手动行渲染单价 = `5450`，**不刷新、不关闭页面**。
2. 后台 `POST /reviews/approve` 通过（另一个上下文，不经过这个 `page`），确认后端手动行数据已是 `5820`。
3. 回到步骤 1 的 `page`，**不刷新**，点击「保存」。

**执行步骤（API 等效序列）**：同 #40 的等效序列写法，但目标行换成手动行的 `row_data` 记录。

**断言（机械可判定）**
- 🔒 保存 **成功**（`200`，不报错）。
- 🔒 🔒 **核心断言**：保存后该手动行单价 **仍 = `5820`**，**未退回 `5450`**——即使手动行完全绕开 `mergeRowDataInputsIntoEdits`，归位机制必须在"`row_data` 落库之后、`quoteCardValues` 重算之前"这个统一插入点上同样纠正它。
- 🔒 重新打开确认渲染仍是 `5820`。

**证据形式**：同 #40 结构（Playwright 日志+截图、API 等效序列记录、保存前后 `row_data` 对比）；报告中须明确写出"本条与 #40 分别独立执行、互不替代"的执行记录。

---

### #42　单价列只读态：只读文本+版本徽标（非置灰）/ 清单外可编辑 / 详情页同步显示徽标（AP-50）/ 指针为空仍只读

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3 + `fronttask.md` §12.3） | E2E(Playwright) |

**前置数据**
- 料号 A：driver 行元素 `Ag`（∈清单，指针有值，走 #14/#31 已升版场景）。
- 料号 A 的一个手动行：元素 `Cu`（∈清单）。
- 同一料号另一行元素 `301`（∉清单，用真实样本）。
- 料号 B：从未升版（指针为空）、但元素 `Ag` 仍在清单里、料号在范围内、策略启用（走 §11.15.2.6(1bis) 的 A 规则——指针为空也应只读，取实时算价）。

**执行步骤**
1. Playwright 打开编辑页，分别定位 A 的 driver 行（`Ag`）、A 的手动行（`Cu`）、A 的 `301` 行。
2. 打开该料号**详情页**（`ReadonlyProductCard`），定位同样的 `Ag` 行。
3. 打开料号 B 的编辑页，定位 `Ag` 行。

**断言（机械可判定）**
- 🔒 ① A 的 driver 行 `Ag`：单价列渲染为**只读文本节点 + 版本徽标**（`page.locator(...).locator('input').count() = 0`，即该单元格内**不存在** `<input>` 元素，而是纯文本 + 徽标 DOM；**不是** `<input disabled>` 这种置灰输入框——用元素标签类型断言，不是用 `disabled` 属性断言）。徽标文本含版本号（如 `🔒V26080501` 或等价格式）。
- 🔒 ① A 的手动行 `Cu`：**同样**只读文本+徽标（driver 行与手动行一视同仁，不得只测 driver 行）。
- 🔒 ② A 的 `301` 行：单价列**是** `<input>` 且**未禁用**（`toBeEnabled()`），可正常输入。
- 🔒 🔒 ③ **核心断言（AP-50 最容易漏）**：步骤 2 详情页的 `Ag` 行**同样**渲染只读文本+版本徽标（不是详情页本来就整页只读所以"顺便"看不出来——需要显式确认徽标 DOM 存在，而不是只确认"没有 input"，因为整页只读页面本来就没有任何 input，那不能证明徽标逻辑生效了）。
- 🔒 ④ 料号 B（指针为空）的 `Ag` 行：**仍是**只读文本+徽标（不因指针为空跳变成可编辑；徽标内容可能显示"实时算价"而非具体版本号，但只读状态不变）。

**证据形式**：4 个场景各自的截图 + DOM 结构断言日志（`input` 元素计数、徽标文本内容）。

---

### #43　手动行元素↔单价联动：空占位 / 填 `Ag` 自动带出转只读 / 改 `301` 解锁且**清空原值** / 再改 `Cu` 带出 Cu 价

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2） | E2E(Playwright) + API 测试 |

**前置数据**：`CUST-0001` 元素清单含 `Ag`/`Cu`，不含 `301`。一张 `DRAFT` 单，新增一个手动行。

**执行步骤（四步连续操作，同一行）**
1. 新增手动行，元素列留空，检查单价列。
2. 元素列填 `Ag`，触发失焦（`onCellBlur → PUT quote-card-edit`），检查单价列。
3. 元素列改为 `301`，触发失焦，检查单价列。
4. 元素列再改为 `Cu`，触发失焦，检查单价列。

**断言（机械可判定）**
- 🔒 步骤 1：单价列**只读**，占位符文本 = 「请先填写元素」（`expect(cell).toHaveText('请先填写元素')` 或对应 placeholder 断言）。
- 🔒 步骤 2：单价列**自动带出 `Ag` 当期版本价**（精确值）并**转为只读**。
- 🔒 🔒 **核心断言（最容易漏）**：步骤 3——单价列**解锁为可编辑**（`<input>` 存在且 `enabled`），且**原先自动带出的 `Ag` 价值已被清空**（`input.value` **不是**步骤 2 的那个值，而是空/0——"留着就是一个看起来很合理的错价"，必须显式断言"值已清空"而不仅仅是"变可编辑"）。
- 🔒 步骤 4：单价列重新**自动带出 `Cu` 的当期版本价**（且**不是**步骤 2 `Ag` 的那个值——需要 `Ag` 价 ≠ `Cu` 价才能有效验证"确实按新元素重新取价"，若两者数值恰好相同需换一组测试数据）。

**证据形式**：4 步的截图/DOM 断言 + 每步 `PUT quote-card-edit` 的响应 JSON（`quoteCardValues` 中该行单价字段的变化轨迹）。

---

### #44　归位不误伤：手改毛重 + 元素∈清单行 → 单价被归位、毛重原样保留；元素∉清单行整行一字节不变

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**：料号 A 的 driver 行 R1（元素 `Ag` ∈ 清单），手改过毛重字段（如从原始值改成 `99.9`，直接 SQL 构造存量手改状态，理由同 #29）；另一行 R2（元素 `301` ∉ 清单），先记录其完整内容 md5。

**执行步骤**
1. 记录 R1 升版前：单价（旧价）、毛重（`99.9`）；R2 的完整行 md5。
2. `POST /reviews/approve` 通过，等待完成。
3. 查询升版后 R1、R2。

**断言（机械可判定）**
- 🔒 R1 升版后：单价 **= 本版价**（归位结果）；毛重 **仍 = `99.9`**（原样保留，未被误伤——精确值断言，不是"大致没变"）。
- 🔒 🔒 R2（元素∉清单）：`md5(该行完整JSON)` 升版前后 **完全相同**（"整行一个字节都不碰"——用整行哈希而不是逐字段比较，防止漏查某个隐藏字段被意外改动）。

**证据形式**：R1 单价+毛重升版前后对比；R2 整行 md5 前后对比。

---

### #45　归位幂等：连续 3 次保存（无编辑）→ 价格值逐次相同，`snapshot_rows`/`row_data` 无累积变化

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，与 AP-51 行数纪律同源） | SQL 断言 |

**前置数据**：料号 A 已升版（`Ag` 单价 = 本版价）的一张单。

**执行步骤**：不做任何字段编辑，连续 3 次 `PUT /api/cpq/quotations/{id}/draft`（每次都用**当前从 GET 拿到的最新数据**回填，模拟"用户打开后什么都没改直接连点 3 次保存"），每次保存后立即查询该行 `snapshot_rows`/`row_data`。

**断言（机械可判定）**
- 🔒 3 次保存后 `Ag` 单价字段**逐次相同**（第 1 次 = 第 2 次 = 第 3 次，精确值）。
- 🔒 🔒 **核心断言**：`snapshot_rows`/`row_data` 里该组件的**行数**（`jsonb_array_length`）3 次保存**恒定不变**（不因反复归位而累积增删行——呼应 AP-51「driver 行权威优先，禁止 `Math.max(rowCount, baseRows.length)`」的纪律，本条是它在归位场景下的具体体现）。
- 🔒 3 次保存的 `snapshot_rows` **整体 md5 也应相同**（若实现里存在"每次归位都重写 `updated_at` 之类的元信息"导致 md5 不同，需在报告里区分"业务字段幂等"与"元信息字段不幂等"两种情况，核心业务字段幂等是硬性要求）。

**证据形式**：3 次保存后的 `snapshot_rows`/`row_data` 完整内容与 md5 对比表。

---

### #46　🔴 活单范围逐状态断言（核心 · 防"排除两个、其余全更新"）：9 个 `status` 逐一表态，5 更新 4 不动

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言（双向，逐字节） |

**⚠️ 环境限制**：现网仅有 `DRAFT`(38)/`SUBMITTED`(1)/`APPROVED`(1) 三种状态的单，其余 6 种（`REJECTED`/`COSTING_REJECTED`/`SENT`/`ACCEPTED`/`EXPIRED`/`CANCELLED`）需要造。`chk_q_status` 已确认这 9 个值全部合法。

**前置数据（可执行 SQL，9 张单同用一个合成料号 `M-9STATUS-TEST` 便于统一断言）**
```sql
-- 9 张单，quotation_number 各自唯一（撞 UNIQUE(quotation_number) 会直接报错，便于发现造数失误）
DO $$
DECLARE
  statuses text[] := ARRAY['DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED',
                            'SENT','ACCEPTED','EXPIRED','CANCELLED'];
  s text;
  qid uuid;
  i int := 1;
BEGIN
  FOREACH s IN ARRAY statuses LOOP
    qid := gen_random_uuid();
    INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status,
                            customer_template_id, created_at, updated_at)
    VALUES (qid, 'QT-TEST-9ST-' || lpad(i::text,2,'0'), 'f6d10ef0-04cc-45f3-829c-568c8cce3adf',
            '9态覆盖测试-' || s, 'd1e1147c-a639-4156-aeac-9f938a65ad05', s,
            '70f1b149-b0d9-4cb1-9245-6c3cee1bc3af', now(), now());
    INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, customer_part_no, subtotal, created_at)
    VALUES (gen_random_uuid(), qid, 'M-9STATUS-TEST', 'M-9STATUS-TEST', 100.00, now());
    i := i + 1;
  END LOOP;
END $$;
```
> ⚠️ 若某状态存在服务端状态机守卫（如 `ACCEPTED` 必须先经过 `SENT`），直接 `INSERT` 绕开应用层状态机是**有意为之**——本条只关心"升版时按 `status` 值分流是否正确"，不测状态流转本身是否合法，直接造终态数据是被允许的捷径。

- `M-9STATUS-TEST` 纳入 `CUST-0001` 调价范围，元素清单含 `Ag`；每张单的 driver 行含一行 `Ag`（需按 #14 的手法直接构造 `quotation_line_component_data.snapshot_rows`，9 张单统一初始单价，便于后续"变/不变"判断）。生成版本 `V1`（`Ag` 新价，明确区别于初始价）。

**执行步骤**
1. 升版前：对 9 张单分别采集基线（`snapshot_rows` md5 + `quote_card_values` md5 + `li.line_total_amount`）。
2. `POST /reviews/approve` 通过 `M-9STATUS-TEST`，等待完成（`GET /jobs/{id}` 至终态）。
3. 升版后对 9 张单重新采集，逐一对比。

**断言（机械可判定，逐状态列出，缺一不可）**
- 🔒 **5 张必须已更新**（`DRAFT`/`SUBMITTED`/`APPROVED`/`REJECTED`/`COSTING_REJECTED`）：各自 `Ag` 单价 = 本版价（精确值），`snapshot_rows` md5 升版前后**不同**。
- 🔒 **4 张必须逐字节不变**（`SENT`/`ACCEPTED`/`EXPIRED`/`CANCELLED`）：`snapshot_rows`/`quote_card_values` md5 升版前后**完全相同**，`li.line_total_amount` 数值**完全相同**。
- 🔒 🔒 **专防断言**：`material_price_update_job_item` 里这 9 张单对应的记录数 **= 5**（不是 7、不是 9）——若实现写成"排除 `EXPIRED`/`CANCELLED`、其余全更新"，会把 `SENT`/`ACCEPTED` 也纳入更新范围，此时 job_item 记录数会变成 7，本断言可直接抓出这个典型错误实现。

**证据形式**：9 张单升版前后的 md5/金额对比表（按 `status` 分组，5 张变化 vs 4 张不变一目了然）；`material_price_update_job_item` 记录数 SQL 输出。

---

### #47　无价元素不死格：本期无价但有上一版价 → 沿用上一版价（只读非空）；从无历史价 → 可编辑

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**（复用已实测样本）：`Ni`、`301` 当前均取不到价（`301` 非有效元素编码，行为等价"不在清单"，走 #35 已覆盖；本条聚焦 `Ni` 的两个子场景）：
- 场景①（本期无价、有上一版价）：先在 `V0` 里让 `Ni` 有价（如手工造一条 `element_price_version_item`，`element_code='Ni', current_price=800.00`），`V0` 通过某料号升版使其吃到 `Ni=800.00`；随后生成 `V1` 时 `Ni` 取价策略返回 NULL（本期无价）。
- 场景②（从无历史价）：另一元素（如 `Cd`）从未在任何版本里有过价，本期同样无价。

**执行步骤**
1. 生成 `V1`，查询 `element_price_version_item` 里 `Ni`/`Cd` 两条记录。
2. 对含 `Ni` 的料号执行升版，查询该行 `Ni` 单价字段与列可编辑性标记（`__priceLocked`）。
3. 对含 `Cd` 的料号（若从未升版，走归位而非升版）查询单价列状态。

**断言（机械可判定）**
- 🔒 `Ni` 的 `element_price_version_item`：`no_price=false`，`inherited_from_previous=true`，`current_price = 800.00`（沿用上一版价）。
- 🔒 升版后含 `Ni` 的行：单价 **= `800.00`**（非空），`__priceLocked=true`（只读，不死格）；该料号**照常升版**（`material_price_review.status` 正常走完 `APPROVED`，不因 `Ni` 无价而卡住）。
- 🔒 `Cd` 的 `element_price_version_item`：`no_price=true`，`inherited_from_previous=false`，`current_price=NULL`。
- 🔒 含 `Cd` 的行：单价列 `__priceLocked=false`（**可编辑**，销售能填进去——视同不在清单，走实时算/手填）。

**证据形式**：`Ni`/`Cd` 两条 `element_price_version_item` 记录；对应行的单价字段与 `__priceLocked` 标记查询输出。

---

### #48　🔴 成本差额预警线（金额）生效：配 50 元 + 差额 +30 → `AMBER`；配 0 + 差额 −20 → `RED`

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2）。⚠️ 前端着色渲染标注"待屏 3/4 交付后执行"，本批做 API/SQL 层 | API 测试 + SQL 断言 |

**🔒 强制纪律**：`cost_diff_threshold` **配 0 永远测不出这条**（0 时 `AMBER` 区间 `[0,0)` 为空集，任何非负差额都直接落 `RED`/`NORMAL`，测不出 `AMBER` 分支是否正确实现）。必须用**非零阈值**。E13 后它是**金额**，不是百分比——若发现 API 请求体里传的是类似 `0.12`（疑似百分比语义）而不是 `50.00`（金额），判定为口径错误。

**前置数据**：`CUST-0001` 的 `cost_diff_threshold` 分两轮配置：
- 轮 1：`PUT /price-adjust/strategies/CUST-0001 {costDiffThreshold: 50.00}`，构造料号 M1，`报价侧成本 - 核价侧成本 = +30.00`。
- 轮 2：`PUT ... {costDiffThreshold: 0.00}`，构造料号 M2，`报价侧成本 - 核价侧成本 = -20.00`。

**执行步骤**：分别对 M1、M2 触发预算计算，查询 `material_price_review_column` 的默认「产品总价」列。

**断言（机械可判定）**
- 🔒 M1：`diffAdjusted = 30.00`（`0 ≤ 30 < 50`），`status='AMBER'`。
- 🔒 M2：`diffAdjusted = -20.00`（`< 0`），`status='RED'`。
- 🔒 `SELECT cost_diff_threshold FROM customer_price_adjust_strategy WHERE customer_no='CUST-0001'` 两轮分别 = `50.00`/`0.00`（确认口径是金额存储，非百分比小数如 `0.5`）。

**证据形式**：两轮配置的 API 响应；`material_price_review_column` 两条记录的 `status`/`diffAdjusted` 输出。

---

### #49　缺核价数据不装作全通过：`status=MISSING` 计入整行标红；反向 `STALE` 不计入

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**：复用真实样本——`costing_card_values IS NULL` 的行现网约占三成（如 `QT-20260726-0002`~`0006` 对应 `S-3120014539` 的行），任取其一所在料号（若该料号已被其他用例占用，改用同类新构造的 `costing_card_values IS NULL` 行，操作等价）。另需一条 `STALE` 样本（`comparisonColumns` 某列因 `componentId` 已被模板改版删除而失效，可复用 #38 场景稍作改造，或直接把某比对列配置的 `quoteComponentId` 改成一个不存在的 UUID 来人为构造）。

**执行步骤**
1. 对核价数据缺失的料号触发预算计算，查询其 `material_price_review_column`。
2. 对 `STALE` 样本同样查询。
3. 查询两者各自的待办池汇总行。

**断言（机械可判定）**
- 🔒 ①：核价数据缺失那一列 `status='MISSING'`。
- 🔒 🔒 ②：`SELECT breached_count FROM material_price_review WHERE id=...` **> 0**（`MISSING` 计入 `breached_count`），`rowRed=true`（整行标红）。
- 🔒 ③：待办池汇总标记出现 `⚪K`（`K=missing_count`，如 `🔴0 🟠0 ⚪1 / N列`或等价格式，具体以 `missingCount>0` 时的字符串拼装规则为准，核心是**必须出现 `⚪` 标记**）。
- 🔒 ④：抽屉对应列显示文案「—（缺核价数据）」（`missingSide` 字段辅助定位，`api.md` §2.2 示例已给出该口径）。
- 🔒 🔒 **⑤ 反向核心断言**：`STALE` 样本那一列 `status='STALE'`，`SELECT breached_count FROM material_price_review WHERE id=(STALE样本的reviewId)` **不因这一列而增加**（即若该料号只有这一列失效、其余列正常，`breached_count` 应为 0，`rowRed=false`）——`MISSING` 与 `STALE` **必须分开处理**，前者标红后者不标红，混用会导致"模板改版就让整池飘红"或"核价缺数据却装作全通过"两个方向的错误。

**证据形式**：`MISSING`/`STALE` 两条样本的 `material_price_review_column`/`material_price_review` 完整查询输出并排对比。

---

### #50　驳回有出口：V1 驳回后 V2 与 V1 价格相同仍进池（因与指针指向的 V0 有差异）；不在活单不自动推进（D5 反例外）

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1） | SQL 断言 |

**前置数据**：料号 W，指针当前指向 `V0`（`Ag=5450`）。生成 `V1`（`Ag=5820`），对 W 执行 `POST /reviews/reject`（驳回，指针仍在 `V0`）。随后生成 `V2`，**刻意让 `V2` 的 `Ag` 价与 `V1` 相同**（都是 `5820`，模拟"行情不再波动"）。

**执行步骤**
1. `V2` 生成后，查询 W 是否进入 `V2` 对应的待办池。
2. 场景②（复用 #36 的手法）：另一料号 Z，同样在某期被驳回，此后 Z **不在任何活单**中，观察下一版生成后指针是否推进。

**断言（机械可判定）**
- 🔒 🔒 **核心断言**：`SELECT count(*) FROM material_price_review WHERE version_id=(V2的id) AND material_no=(W的料号)` **= 1**（W **仍然进池**）——判据是"与指针当前指向的 `V0`（`5450`）比较，`V2`（`5820`）与之确有差异"，**不是**"与上一个批次 `V1` 比较"（若按后者判断，`V2 vs V1` 无差异，W 会被误判"无事可审"而永久跳过）。
- 🔒 场景②：`SELECT version_id FROM material_price_version_ref WHERE material_no=(Z的料号)` 在新版本生成后**仍指向驳回前的旧版本**（不自动推进），与 #36 的"未驳回料号会自动推进"形成对照——两条一起才能证明"反例外"确实生效而不是恰好都没推进。

**证据形式**：W 的 `material_price_review` 进池记录；Z 的指针查询（新版本生成前后对比，佐证不自动推进）。

---

---

### #51　元素停用不静默解锁：已停用元素仍在清单里、屏 1 可见并标「已停用」、照常参与调价、单价列仍只读

| 归属 | 测试层级 |
|---|---|
| 后端自测 + 前端自测（`backtask.md` §2.1 + `fronttask.md` §12.1，双侧各自独立自测） | API 测试 + SQL 断言 |

**前置数据**：`CUST-0001` 元素清单已含 `Ag`（参与调价），料号 X 的 driver 行含 `Ag`（`__priceLocked=true`）。

**执行步骤**
1. `UPDATE element SET status='INACTIVE' WHERE element_code='Ag'`（模拟元素主数据停用；元素只能停用不能删）。
2. `GET /price-adjust/strategies/CUST-0001/elements`，核对 `Ag` 那一行。
3. `SELECT count(*) FROM customer_price_adjust_element WHERE strategy_id=... AND element_code='Ag'`。
4. 生成新版本，确认 `Ag` 照常出现在 `element_price_version_item` 且正常计价。
5. 查询料号 X 的 driver 行单价列可编辑性标记。

**断言（机械可判定）**
- 🔒 步骤 3：`= 1`（`Ag` **仍在**清单，未被系统自动移出）。
- 🔒 步骤 2：响应中 `Ag` 那一行 `elementEnabled=false`，且**默认查询（`includeDisabled=true`）就能看到**（不是必须额外传参才可见，防止"现只列启用元素，财务看不到就无从自查"这个原文明确点出的问题）。
- 🔒 步骤 4：`element_price_version_item` 里 `Ag` 一条正常记录（`no_price` 视实际取价结果而定，但**不应**因元素停用而被跳过/标记异常）。
- 🔒 🔒 **核心断言**：步骤 5，料号 X 的 `Ag` 行 `__priceLocked` **仍 = `true`**（只读状态**不因元素停用而静默解锁**——这是本条要防的具体实现错误：把"停用"误判成"从清单移出"从而触发可编辑性联动）。

**证据形式**：停用前后 `customer_price_adjust_element` 记录数；`GET .../elements` 响应（`elementEnabled` 字段）；X 行 `__priceLocked` 前后对比。

---

### #52　更新失败可找回：关闭抽屉后仍能从「更新任务」菜单找到批次；屏 7 显式标「尚未更新」而非新版本号；通知含失败项

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2） | API 测试 + SQL 断言 |

**前置数据**：料号 Y 涉及 3 张单，构造其中 2 张走 #16 的冲突手法（人为制造 `row_version` 不匹配）使升版执行时落 `FAILED`/`CONFLICT`，第 3 张正常 `SUCCESS`。

**执行步骤**
1. `POST /reviews/approve` 通过 Y，等待 job 跑完（`status` 应为 `PARTIAL`）。
2. **模拟"关闭抽屉"**：不再轮询该 `jobId`（前端行为，API 层面等价于"不再调用 `GET /jobs/{jobId}`"），间隔一段时间后再次调用。
3. `GET /price-adjust/jobs?customerNo=CUST-0001`（"更新任务"常驻页列表），定位该批次。
4. `GET /price-adjust/jobs/{jobId}/items?status=FAILED`，对失败项分别单条重试与批量重试。
5. `GET /api/cpq/quotations/{那2张失败单的id}/price-revisions`，核对料号 Y 在 `materialVersions` 里的 `state`。

**断言（机械可判定）**
- 🔒 步骤 3：**间隔任意长时间后依然能查到该批次**（`jobId` 持久存在，不因"没人盯着"就消失或需要重新触发）。
- 🔒 步骤 4：2 条失败明细可见（`errorCode`/`errorMessage`），单条重试与批量重试均返回正常受理响应（非 404/410）。
- 🔒 🔒 **核心断言**：步骤 5，那 2 张失败单的 `materialVersions` 中料号 Y **`state="NOT_UPDATED"`**，且渲染文案（若已实现）应为「尚未更新」，**绝不能**直接显示成已推进的新版本号——这条判定必须读 `material_price_update_job_item`（而非只读 `material_price_version_ref` 指针，指针早已推进到新版但这 2 张单的卡片其实还是旧价）。
- 🔒 完成通知（若通知系统已就绪，查 `NotificationService` 对应记录）内容**同时包含**成功数与失败数，不能只报成功。

**证据形式**：`jobId` 持久查询记录；重试前后 `job_item` 状态；`price-revisions` 响应中 `state="NOT_UPDATED"` 的具体条目；通知内容记录。

---

### #53　初版定型：建单即建初版（未定型）→ 跟随编辑变化 → 首次升版定型（内容=升版前状态，此后不变）→ 同 V 版第二次升版不影响初版 → 未定型期间 `snapshot` 为 NULL → 定型时物化双侧

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2） | SQL 断言 |

**前置数据**：新建一张单，含料号 E、F 两个（均在调价范围+元素清单内）。

**执行步骤**
1. 首次保存（已有产品行）后，立即查询 `quotation_price_revision`。
2. 改数量、加一个手动行，再次保存，重新查询。
3. 生成版本 `V1`，`POST /reviews/approve` 通过 **E**（首次升版）。
4. 立即再改一次单（改数量），确认初版不受影响。
5. 同一 `V1` 内，再通过 **F**（并入同一 `R`，同 #17 场景）。
6. 全程核对 `snapshot`（`quote_card_values`/`costing_card_values`/`snapshot_rows`）是否为 NULL 的时间点。

**断言（机械可判定）**
- 🔒 ①：`SELECT count(*) FROM quotation_price_revision WHERE quotation_id=... AND based_version_id IS NULL` **= 1**（初版立即出现），`sealed=false`。
- 🔒 ⑤ **核心断言（性能纪律，容易漏）**：步骤 1、2 两次查询，初版记录的 `quote_card_values`/`costing_card_values`/`snapshot_rows` **全部为 `NULL`**（未定型期间不物化）。
- 🔒 ②：虽然 `snapshot` 是 NULL，但屏 7 渲染时**取该单当前值**（服务端 `snapshot IS NULL` 时的 fallback，若已有对应接口可直接断言渲染结果 = 单据当前值，两次编辑后的渲染结果应各自不同，体现"跟随编辑变化"的等价效果）。
- 🔒 ③ 🔒 **核心断言**：步骤 3 首次升版后，初版记录**转为 `sealed=true`**，此刻**才**物化 `snapshot`（非 NULL），内容 = **升版前**（也就是步骤 2 编辑后、升版前）的最后状态。
- 🔒 步骤 4：再次改单后，初版记录的 `snapshot` **不变**（`sealed=true` 后不再跟随编辑）。
- 🔒 ④ 🔒 **核心断言**：步骤 5（F 升版，并入同一 `R`）后，初版记录**仍然不变**（`sealed` 记录只在"首次升版"这一刻物化一次，后续同 `V` 版内其他料号的升版不影响它）。
- 🔒 ⑥：步骤 3 定型时物化的 `snapshot` 同时含 `quote_card_values` **与** `costing_card_values`（双侧，不是只存报价侧）。

**证据形式**：6 个时间点的 `quotation_price_revision`（初版那一行）完整查询输出，标注每次的 `sealed`/`snapshot` 状态。

---

### #54　调价策略变更留痕：改料号范围/元素清单/预警线/比对列配置 → 变更历史可查时间/变更人/变更前后内容

| 归属 | 测试层级 |
|---|---|
| 前端自测（历史 Drawer 渲染，`fronttask.md` §12.1）+ 后端自测（`customer_price_adjust_strategy_log` 落库，虽 `backtask.md` §2.1 未单列，按实际职责补测） | API 测试 + SQL 断言 |

**前置数据**：`CUST-0001` 已有策略主体。

**执行步骤**：依次执行 4 类变更各一次：① `PUT .../materials`（改料号范围）；② `PUT .../elements`（改元素清单）；③ `PUT .../strategies/CUST-0001`（改 `costDiffThreshold`）；④ `PUT .../comparison-columns`（改比对列配置）。每次变更后立即 `GET .../logs`。

**断言（机械可判定）**
- 🔒 每次变更后，`SELECT count(*) FROM customer_price_adjust_strategy_log WHERE strategy_id=... ORDER BY changed_at DESC LIMIT 1` **新增 1 条**，`change_type` 分别对应 `MATERIAL_SCOPE`/`ELEMENT_LIST`/`STRATEGY`/`COMPARISON_COLUMN`（或等价枚举）。
- 🔒 每条记录的 `before_snapshot`/`after_snapshot` **内容与实际变更前后值一致**（如 ③ 的 `before_snapshot.costDiffThreshold` = 变更前的值、`after_snapshot.costDiffThreshold` = 变更后的值，逐字段核对）。
- 🔒 `changed_by`/`changed_by_name`/`changed_at` 均非空，且 `changed_by` 与本次操作的实际用户 id 一致。
- 🔒 前端「🕘 变更历史」Drawer（若已交付）渲染出这 4 条记录，人工/E2E 核对文案摘要（`summary` 字段）语义可读。

**证据形式**：4 次变更各自的 `logs` 查询响应；变更前后值与 `before/after_snapshot` 的逐字段对比。

---

### #55　🔴 切版预览双侧都是历史值（核心）：报价侧与核价侧同为 R1 当时原貌，不得报价正常、核价读当前值

| 归属 | 测试层级 |
|---|---|
| **技术总监亲验**（`backtask.md` §2.3 + `fronttask.md` §12.3，与 #14 #21 #28 #40 #41 #64 并列） | API 测试 + SQL 断言 |

**🚨 本条专防"报价侧看着完全正常"的最隐蔽失败模式**：若实现是"报价侧读快照、核价侧读当前值"，报价侧的所有断言都会通过（结构、数量、金额全对），只有**核价侧**的数值会悄悄跟着当前状态漂移——必须专门验证核价侧，不能因为报价侧对了就认为整条通过。

**前置数据（严格按顺序构造）**
1. 建单，含料号 A、B、C。
2. 生成版本并通过 A 的升版 → 产生 `R1`（内容 = 升版后状态，含 A/B/C）。**记录 `R1` 此刻的核价侧数值**（`costing_card_values` 中某具体元素成本字段，如 `Ag` 材料成本，取一个精确值 `X1`）。
3. **销售在 `R1` 之后做三件事**：① 改 A 的数量；② 删除 B 这一行；③ 新增一个手动行 D。
4. 生成第二版并通过（可以是同一料号 A 再次升版，或另一料号），此时该单核价侧 `Ag` 的成本因价格变化已不同于 `X1`（记为 `X2`，`X2 ≠ X1`——**必须确保这个差值真实存在**，否则本条测不出"核价侧到底读的是哪份数据"）→ 产生 `R2`。

**执行步骤**
1. `GET /api/cpq/quotations/{quotationId}/price-revisions/{R1的revisionId}/preview`。
2. 核对返回的 `lineItems` 结构（料号列表、数量）与 `costingCardValues` 中的 `Ag` 成本字段。
3. 同时查询该单**当前**（非预览）的 `costing_card_values`，取同一字段现值 `X_current`。

**断言（机械可判定）**
- 🔒 报价侧（基本项）：`lineItems` 含 A（数量 = **升版后、编辑前**的原始值，不是编辑后的新数量）、B（**存在**，不是已被删除的状态）、**不含** D（D 是编辑后加的，`R1` 时还不存在）。
- 🔒 🔒 **核价侧核心断言**：响应中 `costingCardValues` 的 `Ag` 成本字段 **= `X1`**（`R1` 当时值），**绝不能 = `X_current`**（当前值）或 `X2`（这两个都是"编辑/再升版之后"才有的数，出现即判定为本条失败）。
- 🔒 若 `X1 = X2`（构造时价格恰好没变化，判别力不足）：本条判定**测试设计需返工**，必须重新构造让两次的价格确有差异，不接受"凑巧一样所以测不出但也算过"这种结论。
- 🔒 预览态毛利对照数（若有对应的成本差额展示字段）与 `R1` 生效时刻财务本应看到的数（用 `material_price_review_column` 若当时留有痕迹，或用离线按 `R1` 快照重新计算的值）**逐位一致**。

**证据形式**：`R1`/`R2` 构造过程的完整时间线记录；预览响应中 `costingCardValues.Ag` 与当前值 `X_current`、`R1` 原值 `X1` 的三方对比表（必须三个数字都摆出来才有说服力）。

---

### #56　结构变化下的涨跌对齐：`R1`→`R2` 结构不同（删 B 加 D）不得报错或错位对齐

| 归属 | 测试层级 |
|---|---|
| 后端自测（涨跌计算）+ 前端（"已移除"/"本期新增"渲染，标注待屏 7 交付后执行） | API 测试 + SQL 断言 |

**前置数据**：复用 #55 场景——`R1` 含 A/B/C，`R2` 含 A/C/D（B 被删、D 新增）。

**执行步骤**：`GET /api/cpq/quotations/{quotationId}/price-revisions`，核对轨迹表涨跌对比数据结构（该端点或专门的版本对比端点应输出逐料号的涨跌信息）。

**断言（机械可判定）**
- 🔒 接口响应 `200`（**不报错**，即便 `R1`/`R2` 行数不同——`3 行 vs 3 行`但成分不同，若实现按下标位置对齐会直接崩或算出无意义的结果，本条验证接口层面不崩溃且语义正确）。
- 🔒 A、C：两版都有，正常给出涨跌数据（价格变化的百分比/金额，具体取决于两版间该料号是否发生价格变动）。
- 🔒 B：标记为**「已移除」**（如 `trend="REMOVED"` 或等价字段），**不参与**涨跌计算（不应出现一个"B 涨了/跌了 xx%"这种无意义的数字）。
- 🔒 D：标记为**「本期新增」**（`trend="NEW"`），**不算**涨跌（D 在 `R1` 时不存在，没有"上一次的价"可比）。
- 🔒 断言方式**必须按料号级对齐**（`materialNo` 做 key 关联两版，而不是按数组下标位置对齐）——若实现按下标对齐，`R1[1]=B` 与 `R2[1]=C` 位置错位比较会产生完全错误的"涨跌"，此断言通过数据结构层面直接可判定实现是否按料号对齐。

**证据形式**：接口响应 JSON（A/B/C/D 四个料号各自的 `trend` 标记与涨跌数值）。

---

### #57　🔴 改配置四项都触发重算：比对列/预警线/元素清单/料号范围 —— 每一项保存后 `待处理` 料号预算刷新，`已通过`/`已驳回` 不动

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，硬约束级条目，专防"只有比对列触发重算"） | API 测试 + SQL 断言（🔒 E14-3 异步，断言须轮询 `budget_status`） |

**前置数据**：`CUST-0001` 下 4 个 `PENDING` 待处理料号（P1~P4，均 `budget_status=READY`），另 1 个 `APPROVED` 料号 Q 作对照。

**执行步骤（四轮独立执行，每轮改一项、其余不动，逐轮验证）**
1. **轮 1**：`PUT .../comparison-columns`（改 P1 所属系列的比对列阈值）→ 轮询 P1 的 `budget_status` 直到回到 `READY`，核对 `material_price_review_column` 是否按新阈值重新着色。
2. **轮 2**：`PUT /price-adjust/strategies/CUST-0001 {costDiffThreshold: <新值>}` → 轮询 P2 的 `budget_status`，核对产品总价列着色是否按新阈值刷新。
3. **轮 3**：`PUT .../elements`（增/减一个参与调价元素）→ 轮询 P3，核对其调整后报价是否因元素清单变化而重新计算。
4. **轮 4**：`PUT .../materials`（增/减料号范围）→ 轮询 P4，核对其待办池归属状态是否按新范围调整。

**断言（机械可判定，四轮独立缺一不可）**
- 🔒 🔒 **每一轮**（不只是"比对列"那一轮）：对应的 `Pn.budget_status` 都经历了重算流程（进入过非 `READY` 中间态，或至少最终值与改配置前不同），且改动生效后的 `material_price_review_column`/预算值**确实反映新配置**——**专防"只有 §1 那种改动触发重算，改 §2/§3/§4 保存成功、变更历史有记录，待办池却纹丝不动"**这个典型漏洞。
- 🔒 **全程 Q（`APPROVED`）在 4 轮改动中 `budget_status`/`material_price_review_column` 内容均不变**（md5 或逐字段核对，四轮各自独立验证一次，共 4 次对照）。
- 🔒 轮 4（料号范围）额外核对：若 P4 被移出范围，其 `PENDING` 记录应转为"已作废（移出范围）"退出待办池；若新增料号进范围且有价格变动，应新纳入待办池并算出预算（覆盖 §11.5.3(5) 提到的料号范围特殊处置）。

**证据形式**：4 轮各自的改动前后 `budget_status` 轮询记录 + `material_price_review_column` 内容对比；Q 的 4 次不变性对照记录。

---

### #58　手动取消勾选元素弹确认：明示「N 张存量单该元素单价列将解锁为可编辑」；确认后出清单且单价列恢复可编辑

| 归属 | 测试层级 |
|---|---|
| 跨端(后端主导)（`backtask.md` §2.2 + `fronttask.md` §12.2，同时 §12.1 前端自测确认弹窗渲染） | API 测试 + E2E(Playwright) |

**前置数据**：`CUST-0001` 元素清单含 `Cu`，若干 driver 行元素为 `Cu` 且当前 `__priceLocked=true`。

**执行步骤**
1. `PUT .../elements {elementCodes: [不含Cu的新清单], confirmUnselect: false}`。
2. 核对响应，取 `unlockedQuotationCount`。
3. Playwright（若前端已交付）：在屏 1 元素矩阵取消勾选 `Cu`，观察弹窗文案。
4. `PUT .../elements {elementCodes: [...], confirmUnselect: true}` 确认提交。
5. 重新查询之前含 `Cu` 的 driver 行的 `__priceLocked` 标记。

**断言（机械可判定）**
- 🔒 步骤 1：响应 **`409`**，`code="UNSELECT_NEEDS_CONFIRM"`，`removedElementCodes` 含 `Cu`，`unlockedQuotationCount` 为一个 **> 0** 的具体整数（等于实际含 `Cu` 且当前只读的存量单行数）。
- 🔒 步骤 3（若已交付）：弹窗文案含「该元素将退出调价机制，N 张存量单上它的单价列将解锁为可编辑」的等价表述，`N` = 步骤 2 的 `unlockedQuotationCount`。
- 🔒 步骤 4：`200`，`SELECT count(*) FROM customer_price_adjust_element WHERE element_code='Cu'` **= 0**（已出清单）。
- 🔒 🔒 **核心断言**：步骤 5，之前含 `Cu` 的 driver 行 `__priceLocked` **变为 `false`**（恢复可编辑），且不需要额外任何操作触发（取消勾选保存后即时生效，或至少下一次归位/查询时立即体现）。

**证据形式**：4 个步骤的 API 响应；取消勾选前后目标行 `__priceLocked` 对比；弹窗截图（若前端已交付）。

---

### #59　🔴 归位作用域三条件：范围外料号不误锁 / 手改值不被清 / 「未参与调价」是真的；策略停用后范围内料号也恢复可编辑

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，E11 断链 4 核心条目） | SQL 断言 |

**前置数据**：`CUST-0001` 策略 `materialScopeMode='SPECIFIED'`，指定料号范围恰好 3 个（不含料号 G）。料号 G 的 BOM 含 `Ag`（`Ag` 在元素清单内，但 G 不在指定范围内）。料号 H 在指定的 3 个范围内，BOM 同样含 `Ag`。

**执行步骤**
1. 查询 G、H 两个料号的 driver 行 `__priceLocked` 标记。
2. 对 G 的 `Ag` 行直接手改单价（模拟销售自填，因为它可编辑），执行一次 `saveDraft`（触发归位流程）。
3. 生成版本并升版（若 G 因不在范围内根本不会进待办池，此步针对范围内的对照料号），检查 G 的手改值是否被清除。
4. 查询 G 在 `GET /price-revisions` 的 `materialVersions` 里的 `state`。
5. **反向对照**：H 的 `Ag` 行确认 `__priceLocked=true`，手改值（若强行 SQL 构造）会在下次 `saveDraft` 归位时被清除。
6. **策略停用**：`PUT .../strategies/CUST-0001 {enabled:false}`，重新查询 H 的 `Ag` 行。

**断言（机械可判定）**
- 🔒 ① G 的 `Ag` 行：`__priceLocked=false`（**未被误锁**，尽管元素 `Ag` 本身在清单里，但料号不在范围内）。
- 🔒 🔒 ② G 的手改值：步骤 2 的手改单价，在步骤 3（无论是否有其他料号升版）**始终保留**，`saveDraft` 归位**不清除**它（归位作用域三条件不满足，G 一个字节都不碰）。
- 🔒 ③ G 在 `materialVersions` 里 `state="NOT_PARTICIPATING"`，且这**是真的**（不是掩盖了实际参与但显示错的假象——因为 ①② 已经证明 G 确实完全没被本机制碰过）。
- 🔒 反向：H 的 `Ag` 行 `__priceLocked=true`（照常只读+归位，与 G 形成对照）。
- 🔒 🔒 步骤 6（策略停用）：H 的 `Ag` 行 `__priceLocked` **变为 `false`**（策略停用后，范围内料号的单价列也恢复可编辑——三条件之③"策略启用"不再成立）。

**证据形式**：G/H 两个料号的 `__priceLocked` 全程对比表；G 手改值在多次归位触发点前后的具体数值；策略停用前后 H 的状态对比。

---

### #60　🔴 归位实时算基准日 = 建单日：从未升版的单，实时算价用建单日而非执行当天；跨天多次保存值不变

| 归属 | 测试层级 |
|---|---|
| 后端自测（`backtask.md` §2.1，E11 断链 3 核心条目，补 #45 幂等的盲区——#45 只保证"同一天多次保存不变"，本条保证"跨天也不变"） | SQL 断言 |

**前置数据**（参照 #9/#36 手法直接 `INSERT` 造数）：一张单 `created_at='2026-07-20'`，料号 I 从未升版（`material_price_version_ref` 无该料号记录，指针为空），`Ag` ∈ 调价清单。

**执行步骤**
1. **计算参照值**：直接调用取价函数/视图，显式传 `p_base_date='2026-07-20'`（该单创建日），得到「按 7-20 算的实时价」`P_0720`。
2. 同样显式传 `p_base_date=<今天日期>`，得到「按今天算的实时价」`P_today`。
3. ⚠️ **前置判别力检查**：若 `P_0720 = P_today`（该期间 `Ag` 日价恰好没变过），本条**判别力不足**，需人为在 `element_daily_price` 里为 `Ag` 造两个不同日期的不同日价（如 7-20 一个价、今天前后另一个价），确保 `P_0720 ≠ P_today` 才继续。
4. 对该单执行一次 `saveDraft`（触发归位实时算分支，因指针为空），查询归位结果单价。
5. 间隔（模拟"跨天"，若无法真实跨天等待，改为**在断言逻辑上**确认归位读取的 `base_date` 参数固定取自 `quotation.created_at::date`、与"执行时刻的系统当前日期"无关——即读代码/日志确认调用取价时传入的 `p_base_date` 值，而不依赖真的等到第二天）再执行一次 `saveDraft`。

**断言（机械可判定）**
- 🔒 🔒 **核心断言**：步骤 4 归位结果单价 **= `P_0720`**（按建单日算），**≠ `P_today`**（不是按执行当天算）——这是本条唯一要证明的事。
- 🔒 步骤 5：两次 `saveDraft`（无论测试脚本实际执行的系统时间是否跨天）归位结果单价**始终相同 = `P_0720`**，且日志/参数记录显示归位调用取价时传入的 `p_base_date` **固定 = `2026-07-20`**（读 `quotation.created_at::date`，不读 `LocalDate.now()`）。
- 🔒 若发现归位调用传入的 `p_base_date` 是执行时刻的当前日期：判定为**该失败模式命中**——一张从未经审核的单，销售每保存一次价格就按当天日价漂移，绕开了活单白名单的审核约束。

**证据形式**：`P_0720`/`P_today` 两个参照值的计算过程；两次 `saveDraft` 归位结果对比；归位调用取价函数时 `p_base_date` 参数的日志/走查记录。

---

**本批（#51~#60）交回，等待下一批指令续写 #61 及以后。**
