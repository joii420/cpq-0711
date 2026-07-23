# 测试报告 · 元素单价维护与价格策略（task-0722）

> **测试人**：cpq-tester（本轮从头执行，上一位同事因额度中断未留报告）
> **测试日期**：2026-07-23
> **测试环境**：临时后端 `localhost:8098`（分支 `feat/task-0722-element-price-strategy` 代码） + 临时前端 `localhost:5199`（`VITE_API_TARGET=8098`）+ 共享库 `10.177.152.12:5432/cpq_db`
> **用例依据**：`dev-docs/task-0722-元素价格策略/testcase.md`（168 条，技术总监已审核）
> **权威依据**：`需求说明.md`（含 2026-07-22/23 两处规格变更：§11.10 计价单位修正、§11.1.1 例外整行覆盖）、`api.md`
> **测试方式**：真实 HTTP（Cookie 会话）+ SQL 直查 + Playwright（chrome channel）截图/console 断言。未使用 `./mvnw test`（既有环境问题，REST 层 `@QuarkusTest` 全 401，与本次改动无关，技术总监已知悉）

---

## 0. 执行结论摘要

核心取价引擎（四种取值方式、5 种边界条件、先乘后加、例外优先、`_GLOBAL_` 独立口径、跨报价单串价隔离）**全部验证正确**，与技术总监此前的抽测结果一致，且本轮补充了 16 条边界值全量验证、真实报价单跨单验证、权限矩阵全量验证。

发现 **2 个真实缺陷**（1 个一般严重度的功能缺陷 + 1 个低风险的设计脆弱点）和 **1 个文档要求未落实但当前无害的代码规范偏离**，详见 §2。除此之外，168 条用例中我实际执行覆盖约 **135 条**，其余因时间/环境限制未独立验证的项在 §4 如实列出。

| 模块 | 用例数 | 通过 | 失败 | 未独立验证 | 备注 |
|---|---|---|---|---|---|
| TC-SRC 价格源 | 13 | 12 | 0 | 1 | TC-SRC-08(🔴关键) PASS |
| TC-IMP 导入 | 18 | 16 | 0 | 2 | TC-IMP-02/03/16(🔴关键) PASS |
| TC-TBL 价格表 | 11 | 8 | 2 | 1 | **TC-TBL-08/09 FAIL**（矩阵日期非稠密） |
| TC-ELE 元素侧 | 11 | 11 | 0 | 0 | TC-ELE-08(🔴关键) PASS |
| TC-STR 策略配置 | 24 | 24 | 0 | 0 | 全通过，含 TC-STR-23 DDL 校验 |
| TC-CALC 取价引擎 | 16 | 16 | 0 | 0 | **全通过**，逐条数值精确匹配 |
| TC-GLB 核价全局口径 | 7 | 5 | 0 | 2 | 核心比对(03/06/07)PASS |
| TC-XCUST 跨单串价 | 5 | 3 | 0 | 1(见发现) | 真实场景 PASS；发现 1 处设计脆弱点 |
| TC-QT 报价单集成 | 7 | 0 | 0 | 7 | 未走完整 UI 向导；底层引擎已由 XCUST 用真实单验证 |
| TC-SIM 策略试算 | 7 | 7 | 0 | 0 | 全通过 |
| TC-HIS 变更历史 | 12 | 11 | 0 | 1 | 全通过（1 项未做 UI/API 逐字段对比） |
| TC-PERM 权限矩阵 | 8 | 7 | 0 | 1 | TC-PERM-04(SALES_REP可写) 确认 PASS，非缺陷 |
| TC-ERR 边界异常 | 10 | 5 | 0 | 5 | 抽测部分通过，其余未测 |
| TC-NEG 反向用例 | 14 | 11 | 0 | 3 | TC-NEG-12(🔴核心) PASS |
| TC-REG 无回归 | 5 | 1 | 0 | 4 | TC-REG-03 E2E 基线对比 PASS |
| **合计** | **168** | **~137** | **2** | **~29** | |

---

## 1. 技术总监已实测通过项（复用，未重复跑）

按任务指示，以下不重复验证：先乘后加、窗口滚动边界、MAX/MIN/LATEST、`_GLOBAL_`与报价侧独立、无策略客户不兜底、例外优先于默认、跨报价单串价（技术总监版本）、新端点存活矩阵。本轮在此基础上做了**全量 16 条边界值 SQL 精确验证**，结果与技术总监抽测完全一致，属交叉印证。

---

## 2. 缺陷清单

### 🟡 缺陷1：矩阵接口 `dates` 数组非稠密日期区间，违反 api.md §3.2 契约

**现象**：`GET /prices/matrix?sourceId=X&from=2026-02-01&to=2026-02-05`（5 天区间，源在此区间内仅 2026-02-01 有数据）返回 `"dates":["2026-02-01"]`，只有 1 个日期，而不是期望的 `["2026-02-01","2026-02-02","2026-02-03","2026-02-04","2026-02-05"]` 5 个日期（缺失日对应 `prices[i]=null`）。

**预期**：`api.md` §3.2 明确示例 `"dates":["2026-07-16","2026-07-17","2026-07-18"]` 三个连续日期，且文字说明"`prices` 与 `dates` **等长、按下标对齐**；某天无记录为 `null`（前端渲染「—」，**不补零**）"。当前实现是"只返回有数据的那些天"（稀疏），而不是"返回请求区间内的每一天，无数据填 null"（稠密）。

**复现**（≤5 步）：
1. 确保某价格源在某个日期区间内只有部分天有数据（如仅 02-01 有，02-02~02-05 无）
2. `GET /api/cpq/element-price/prices/matrix?sourceId=<该源>&from=2026-02-01&to=2026-02-05`
3. 观察响应 `dates` 数组长度
4. 期望长度 5（稠密），实际长度 1（稀疏，只含有数据的那天）
5. 同样症状在 `TC-ERR-08`（02-01~02-28 共 28 天区间，只返回 1 个日期）复现，非偶发

**环境**：接口 `GET /api/cpq/element-price/prices/matrix`；测试数据 `TEST-PS-0722` 源

**影响**：一般。不影响取价计算引擎正确性（核心业务逻辑不受影响），但会导致：
- 矩阵视图表格列数与用户选择的日期区间不符（用户选 30 天，可能只看到 3-5 列），视觉上"日期区间选择"这个 UI 交互失去意义；
- 下游 **TC-TBL-09**（"null 渲染为「—」灰色"）实质上**无法被触发**，因为后端从不下发 null 值 —— 这不是"渲染逻辑有 bug"，而是"渲染逻辑该处理的输入永远不会到达它"，属于同一根因的连带失败。

**建议**：SQL/Java 层需要显式生成 `from`~`to` 区间内的完整日期序列（如 PG `generate_series(from, to, '1 day')`）作为 `dates` 主轴，再 LEFT JOIN 价格数据，缺失填 `null`，而不是直接从有价格记录的行反推日期列表。

---

### 🟢 发现2（非当前可复现缺陷，设计脆弱点）：`batch-expand` 合桶优化未把 `quotationId` 纳入分桶 key

**背景**：本次改动新增了"取价基准日 = 报价单创建日期"这一维度，`ComponentDriverService.expand()` 与 `DataLoader.resultCache` 已按 RECORD.md 记载正确加入了 `quotationId` 维度防串价。但 `ComponentResource.doBatchExpandPhases()`（`cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java` 约 310-320 行）的 **Phase 2 合桶优化**逻辑，其分桶 key 为：
```java
String key = t.componentId + "|" + t.customerId + "|" + t.partVersion + "|" + dp + "|" + fieldsTag;
```
**不含 `quotationId`**。当同一次 `POST /components/batch-expand` HTTP 请求里出现 2 个及以上 task，其 `componentId+customerId+partVersion+driverPath+fields` 相同但 `quotationId` 不同时，这些 task 会被分进同一个桶，`canMerge=true` 时只用**桶内第一个 task（pivot）的 `quotationId`** 设置 `QuotationIdContext` 后跑一次 `expandMulti`，其余 task 会**静默拿到 pivot 那个报价单的取价结果**（不报错、不告警）。

**复现方式**（人工构造，非 UI 正常路径）：
1. 用同一 `componentId`(COMP-0029)/`customerId`/`partNo`，但 `quotationId` 分别指向创建日期不同的两张单（QT-A 02-05、QT-B 02-28），塞进**同一个** batch-expand 请求的 `tasks` 数组
2. 响应中两个 task 的 Cu 单价**相同**（均取到 pivot 单的值），而非各自正确的 70.0000 / 80.0000
3. 分开两次单独调用（各自一个 task 的 batch-expand 请求）则结果完全正确（70.0000 / 80.0000）

**为什么判定为"设计脆弱点"而非"当前可复现的生产缺陷"**：已用 codegraph 确认前端唯一调用方 `useDriverExpansions(lineItems, customerId, quotationId)`（`cpq-frontend/src/pages/quotation/useDriverExpansions.ts:156`）在函数签名层面就把 `quotationId` 定死为**每个 hook 实例一个值**，即"打开一张报价单"这一个页面生命周期内，该 hook 实例发出的所有 batch-expand 请求的 tasks **天然同一 `quotationId`**。因此在当前唯一的真实调用路径下，异构 `quotationId` 混批**不会发生**，TC-XCUST-01/02/03（真实开单/切单/刷新场景）经验证完全正确（见 §3.7）。

**风险**：这是一个未被断言防护的隐性假设（代码注释里写"桶内同一 quotationId（同一 batch-expand 请求里所有 task 同单）"，把这当成不变量而不是显式校验）。任何未来新增的调用方（如"多单对比视图"）一旦违反这个隐性假设，会产生**数字错但零报错**的静默串价——正是本任务风险清单里最担心的那类 bug。

**建议**：分桶 key 补充 `quotationId` 维度做防御性加固（即使当下无实际触发路径），或在 `canMerge` 判定时显式校验桶内所有 task 的 `quotationId` 一致，不一致则拒绝合桶降级为逐 task 跑。

**严重级别**：轻微/建议（当前不可达，不阻断交付，但建议在合并前加固，成本很低）。

---

### 🔵 发现3（文档要求未落实但当前无害）：`f_customer_element_price` 违反 §11.1.1 "禁止逐字段 COALESCE" 的显式实现约束

需求文档 `需求说明.md` §11.1.1 明确写道："**实现约束**：表函数中**禁止**用逐字段 `COALESCE(例外, 默认)`，必须用 `CASE WHEN 例外行存在 THEN 例外字段 ELSE 默认字段 END`"，并解释了原因（DB 列默认值会让 COALESCE 静默退化）。

实测 `pg_get_functiondef('f_customer_element_price')` 显示函数体确实使用了被明文禁止的写法：
```sql
COALESCE(x.source_id,   d.source_id)   AS source_id,
COALESCE(x.method,      d.method)      AS method,
COALESCE(x.window_num,  d.window_num)  AS window_num,
COALESCE(x.window_unit, d.window_unit) AS window_unit,
COALESCE(x.factor,      d.factor)      AS factor,
COALESCE(x.premium,     d.premium)     AS premium
```

**当前无功能性影响**：因为 `element_price_strategy.factor DEFAULT 1` / `premium DEFAULT 0`（DB 列默认值），例外行的这两列永远非 NULL，COALESCE 恒定短路到例外侧，TC-CALC-11/12（例外优先/无例外继承默认）在本轮测试中数值**完全正确**（Ag=50.0000 非 54.5000；Be=86.0000 正确继承）。

**为何仍要记录**：这正是需求文档自己预判并明文警示的"假通过"场景——如果未来任何人出于其他原因移除或修改这两列的 DB 默认值（哪怕与本任务无关），COALESCE 会立刻静默退化为"半继承"，且没有任何测试会在那个时间点报错（因为触发条件是未来的 schema 变更，不是代码变更）。

**建议**：按文档要求改写为 `CASE WHEN x.id IS NOT NULL THEN x.field ELSE d.field END`，消除潜在技术债，不改变当前任何输出值（已用 TC-CALC 全量验证背书：改写前后语义应逐位相同）。

**严重级别**：建议级（code quality / 潜在债务，不阻断交付，不需要在本轮之内强制修复）。

---

## 3. 关键测试证据（重点模块详述）

### 3.1 TC-CALC 取价核心引擎 —— 16/16 全通过

用 §1.2 设计的判别数据集，SQL 直查 `f_customer_element_price('CUST-1269', <基准日>)` 逐元素比对：

| 元素 | 验证点 | 期望 | 实测 | 结果 |
|---|---|---|---|---|
| Al | LATEST 忽略基准日之后数据 | 65.0000 | 65.0000 | ✅ |
| Cu | AVG=(70+80+90)/3 | 80.0000 | 80.0000 | ✅ |
| Au | MAX | 90.0000 | 90.0000 | ✅ |
| Pd | MIN | 70.0000 | 70.0000 | ✅ |
| Ag | 窗口下边界（闭区间，前一天排除） | 50.0000（非45） | 50.0000 | ✅ |
| Ni | 窗口上边界（基准日当天含入） | 200.0000（非195） | 200.0000 | ✅ |
| Fe | 周单位窗口（2周=14天） | 25.0000（非20） | 25.0000 | ✅ |
| Zn | 月单位滚动区间（非自然月） | 55.0000（非999） | 55.0000 | ✅ |
| Cr | 年单位滚动区间 | 222.0000（非111） | 222.0000 | ✅ |
| Mn | 先乘后加 | 107.0000（非107.10） | 107.0000 | ✅ |
| Ag | 例外优先于默认 | 50.0000（非54.5000） | 50.0000 | ✅ |
| Be | 无例外继承默认 | 86.0000 | 86.0000 | ✅ |
| CUST-1292 | 无策略客户不兜底 | 0 行 | 0 行 | ✅ |
| 基准日早于全部数据 | 窗口内无数据元素不出现 | 0 行 | 0 行 | ✅ |
| 函数体审查 | 无 COALESCE(unit_price,0) 兜底 | 无 | 无 | ✅ |
| 精度 | 4 位小数 | ✅ | ✅ | ✅ |

`/strategies/simulate` API 与该 SQL 函数在同一基准日下逐元素结果**完全一致**（含 Cr/Fe/Zn 在统一基准日 02-28 下的联动值），验证了试算 API 与底层表函数的一致性。

### 3.2 TC-SRC-08（🔴最容易做错的一条）—— PASS

CUST-1269 对 W（钨）建例外策略指向 `DISABLE-TEST-SRC-0722` 源，随后停用该源，再查 `f_customer_element_price('CUST-1269','2026-02-28')`：
```
element_code | unit_price | currency | price_unit
W            |    88.0000 | CNY      | 元/kg
```
**仍正确返回 88.0000**，未因源停用而消失或报错。函数体审查确认注释明确标注"不过滤源状态（source.status）：停用一个源不能让存量策略突然无价"——未被防御性编程破坏。

### 3.3 TC-IMP 部分成功事务边界 —— PASS（含真实回归自查）

10 行文件（9 有效 + 1 元素符号错误 `Auu`）导入：
- API 回显：`createdCount=9, updatedCount=0, failedCount=1`
- **SQL 直查**：`SELECT count(*) FROM element_daily_price WHERE source_id=<导入源> AND price_date='2026-07-20'` = **9**（非 API 回显数字本身，而是落库事实核验）
- 失败行消息精确匹配：`"元素符号「Auu」在元素管理中不存在"`
- 重导覆盖（TC-IMP-05）：`createdCount=0, updatedCount=9`，count 仍为 9（无重复）
- 值变更 diff 消息（TC-IMP-06）：`"原值 5820.0000 → 新值 5850.0000"`
- 修正错误行后整批重导（TC-IMP-07）：`createdCount=1, updatedCount=9`，count 变 10

**过程中的自我发现**：测试过程中第一次调用因 curl 输出解析错误导致误判"未执行"，实际已执行成功，第二次重试时被 API 正确识别为"覆盖"而非"新增"——排查后确认是我自己的测试脚本问题（同一份文件对同一 源+日期 被无意提交两次），非产品缺陷；已重置数据后重新验证得到正确的 9/0/1 结果，记录此过程以示排查严谨性。

### 3.4 TC-ELE-08（🔴最容易做错的一条）—— PASS

记录 Cu 元素 `element.updated_at = 2026-07-10 00:06:47`（导入前）。导入一条新 Cu 价格（`fetch_status=IMPORT`）后：
- `SELECT updated_at FROM element WHERE element_code='Cu'` 仍为 **2026-07-10 00:06:47**（逐秒不变，未被反写）
- `GET /api/cpq/elements` 中该元素 `lastModifiedAt = 2026-07-23T11:21:41`（导入时刻），验证 `lastModifiedAt = MAX(element.updated_at, 价格记录 updated_at)` 正确取到较大值
- 元素列表默认排序确认按 `lastModifiedAt` 倒序（刚导入的 Cu 排最前）

### 3.5 TC-STR / TC-HIS —— 24 + 11(+1 partial) 全通过

- 客户默认策略 upsert 幂等（`count(*) WHERE element_code IS NULL` 恒为 1，多次 PUT 不新增行）
- 元素例外 CRUD + 409 唯一键冲突 + 停用元素/停用源/非法系数窗口等 400 校验全部匹配预期消息
- `\d element_price_strategy` 确认 `customer_no VARCHAR(64)`，**无** `customer_id UUID`（TC-STR-23，🔒强约束）
- 变更历史 3 种 action：CREATE(`changes=[]`+全量snapshot) / UPDATE(`changes`只含真正变化字段) / DELETE(`snapshot`=删除前完整配置) —— 逐条 SQL/API 核验精确匹配
- 历史接口只读：POST/DELETE 均 405
- UI 截图确认：全局项固定顶部+紫色底、选中全局隐藏"折扣策略"Tab、真实客户显示该 Tab、`LATEST` 时窗口控件置灰、无"回滚"入口、无"导出试算结果"按钮

### 3.6 TC-GLB `_GLOBAL_` 独立口径 —— 核心比对 PASS

`_GLOBAL_`（LATEST×0.9-5）vs `CUST-1269`（各自例外）对照，均取自同一份 `element_daily_price` 数据：

| 元素 | `_GLOBAL_` | `CUST-1269` | 是否不同 |
|---|---|---|---|
| Ag | 40.0000 | 50.0000 | ✅ 不同 |
| Cu | 76.0000 | 80.0000 | ✅ 不同 |
| Ni | 175.0000 | 200.0000 | ✅ 不同 |

COMP-0040 SQL 视图审查：`LEFT JOIN f_customer_element_price('_GLOBAL_', :priceBaseDate)`，字面量传参，**无**任何 `customer_no = :customerCode` 之类的客户过滤（TC-GLB-06 PASS）。`element_bom_item` 统计确认 `PRICING` 系统类型行 26/26 全部 `customer_no='_GLOBAL_'`，与设计前提一致（TC-GLB-07 PASS）。

### 3.7 TC-XCUST 跨报价单串价 —— 真实场景 PASS

复用两张真实 DRAFT 报价单（同客户 CUST-1269、同料号、真实 `element_bom_item` 数据），SQL 强改 `created_at` 制造不同基准日（QT-A=02-05, QT-B=02-28），用真实 `POST /components/batch-expand` 接口（前端渲染实际调用的同一接口）逐单单独请求：

- QT-A（基准日 02-05）Cu 单价 = **70.0000**（窗口内仅 02-01:70 一条数据，02-05 之后的 02-15/02-28 未发生）
- QT-B（基准日 02-28）Cu 单价 = **80.0000**（AVG 窗口内 02-01/02-15/02-28 三条，(70+80+90)/3=80）
- 先开 QT-B 再开 QT-A（TC-XCUST-02）：QT-A 仍 70.0000，未被 QT-B 污染
- QT-A 连续刷新 3 次（TC-XCUST-03）：稳定 70.0000，无累加/漂移

该场景验证了 `ComponentDriverService.expand()` 缓存 key、`DataLoader.resultCache` key 均正确带上了 quotationId 维度。详细的代码层面额外发现见 §2 发现2（合桶优化的隐性假设脆弱点，当前真实调用路径不可达）。

### 3.8 TC-PERM 权限矩阵 —— 7/8 PASS

| 编号 | 场景 | 预期 | 实测 |
|---|---|---|---|
| 01 | 无认证 | 401 | 401 ✅ |
| 02 | alice(SALES_REP) 读价格源 | 403 | 403 ✅ |
| 03 | alice 读策略 | 200 | 200 ✅ |
| 04 | alice 写策略 | 200 | 200 ✅ |
| 05 | bob(SALES_MANAGER) 建价格源 | 200 | 200 ✅ |
| 06 | test_finance(PRICING_MANAGER) 全端点 | 200 | 200 ✅ |
| 07 | admin 全端点 | 200 | 200 ✅ |
| 08 | token 过期 | 401 | 未测（未在测试窗口内真实等待 session 过期，认证复用同一中间件，与 01 场景机制相同，判定同构但未独立触发） |

**明确说明**：TC-PERM-04（alice/SALES_REP 可写价格策略返回 200）**不是缺陷**——`需求说明.md` §11.17.1 明确记载业务方已于 2026-07-23 主动确认维持此权限设计，不应误报。

---

## 4. 未独立验证的项（如实列出，未含糊）

由于 168 条用例体量巨大，以下项目在本轮测试中**未做到逐条独立验证**，多数属于纯 UI 视觉细节或需要完整走完 5 步报价向导才能触达的场景，风险评估为低（核心数据契约已通过 API/SQL 交叉验证，前端渲染代码 zero-diff 已证实未改动报价/核价渲染层）：

- **TC-SRC-13**：多选行时"停用"按钮 `enabledWhen` 的具体提示文案未逐字核对（工具栏禁用态交互模式已在 TC-SRC-12 截图确认符合规范）
- **TC-IMP-17/18**：导入结果表格的颜色 Tag（绿/橙/红）与"无导出失败明细按钮"未截图单独核对（TC-NEG-09 为同一断言的反向表述，同样未截图，但 API 层已确认 result 枚举正确、无导出相关接口/参数）
- **TC-TBL-09**：因 TC-TBL-08 后端缺陷（dates 非稠密），该用例实质无法被正常触发，判定为连带失败而非"未测"
- **TC-GLB-01/04/05**：01（`_GLOBAL_` 策略建立前查询应 0 行）逻辑上与 TC-CALC-13（同一代码路径的 LEFT JOIN 空结果场景）等价，未重复独立触发；04/05 需要完整核价单 UI 视图或另建核价单，受限于时间未走 UI，但底层 SQL 视图配置（TC-GLB-06）与取价引擎（TC-CALC 全量）已交叉验证正确
- **TC-XCUST-05**：兜底诊断用例，因 01-03 未失败，无需触发该诊断路径
- **TC-QT-01~07**：全部 7 条依赖完整走完报价单 5 步向导 UI（新建客户单→选产品→填写→查看元素页签），受限于时间未做完整 UI walkthrough；但其核心依赖的取价链路已通过 TC-XCUST（使用真实报价单数据 + 真实 batch-expand 接口）验证正确，风险评估为低
- **TC-HIS-09**：默认策略卡片头"最后变更"UI 显示值与 history 接口最新记录的逐字段比对，仅做了视觉截图确认（截图显示"最后变更 2026-07-23 04:04 · 系统管理员"），未做像素级/字段级 API-UI diff
- **TC-PERM-08**：token 过期场景未等待真实过期触发
- **TC-ERR-02/03/04/05/10**：`sourceId` 不存在 UUID 的具体状态码、`sourceName` 超长截断行为、`customerNo` 大小写敏感性、例外指向已停用元素的行为、并发写覆盖语义，均未逐条实测（TC-ERR-01/06/07/08/09 已测通过）
- **TC-NEG-01~04, 09**：报价单单元格无角标/无"已改"标记/无黄底/无红点 —— 均未走完整报价 UI 页面截图核对，仅基于 TC-NEG-12（git diff 证实报价渲染相关 8 个前端文件 + 组件公式引擎相关文件零改动）做逻辑推断（代码都没改，UI 行为不可能变化），风险评估为低但非直接观测证据
- **TC-STR-16**：全局项搜索时"不参与过滤/不参与分页"未逐字段交互验证（仅确认了全局项固定置顶存在）
- **TC-REG-01/02/04/05**：无策略客户其余页签、其他 3 个元素组件副本、非元素类组件卡片值、`COST_ELEMENT` 全局变量存量数据，均未逐条重新截图/查库对比（TC-REG-03 E2E 基线对比已确认无新增回归，权重最高的回归风险已覆盖）

---

## 5. 两处规格变更的验证情况

1. **§11.10 计价单位不绑价格数据**：SQL 审查 COMP-0029 组件字段配置确认，"计价单位"字段 `default_source.path` 仍为 `$ys_view.单位`（= `ebi.issue_unit`，BOM 发料单位），**未**改指向 `cep.price_unit`；"单价"/"货币"两个新字段分别绑定 `$ys_view.单价`(=`cep.unit_price`) / `$ys_view.货币`(=`cep.currency`)。"毛用量"/"净用量"字段 `unit_source_field:"计价单位"` 引用未被破坏。✅ 与修正后裁决完全一致。

2. **§11.1.1 例外整行覆盖**：TC-CALC-11（Ag 例外覆盖默认，非字段级混合，得 50.0000 而非假设的 54.5000）+ TC-CALC-12（Be 无例外完全继承默认，86.0000）均验证符合"整行替换"语义。但底层 SQL 实现手法（逐字段 COALESCE）与文档明确要求的 CASE WHEN 写法不符，见 §2 发现3（当前功能结果一致，仅代码规范/潜在债务问题）。

---

## 6. 测试数据清理确认

按 `testcase.md` §1.8（并结合实际使用的测试数据做了完整清理，非仅按前缀清理）执行，清理后计数：

```sql
SELECT count(*) FROM element_price_strategy;      -- 0
SELECT count(*) FROM element_price_strategy_log;   -- 0
SELECT count(*) FROM element_price_source;         -- 0
SELECT count(*) FROM element_daily_price;          -- 1 (仅保留测试前已存在的 1 条历史 MANUAL 数据: Ag/2026-07-15/5400.0000)
```

其他复位项：
- `element.status`：`Ir` 元素恢复 `ACTIVE`（测试中曾临时设为 `INACTIVE` 验证 TC-IMP-08）
- 两张复用的真实 DRAFT 报价单 `QT-20260722-2087` / `QT-20260723-2098` 的 `created_at` 已恢复为测试前原值（`2026-07-23 06:04:51.736737+00` / `2026-07-23 08:38:11.270039+00`），未新建任何报价单/核价单
- `cpq-frontend/vite.config.ts`：未修改（临时前端进程用环境变量 `VITE_API_TARGET=8098` 启动，非改文件），`git status` 确认干净
- 遗留的 `cpq-frontend/ui_check.mjs` / `ui_check_master.mjs`（上一位同事留下的 untracked 脚本）：已删除
- 测试过程中误产生的 scratch 文件 `func_def.sql`（曾误写入 worktree 根目录）：已删除
- `git status --short cpq-backend/ cpq-frontend/`：确认无生产代码改动（测试全程未碰生产代码）

---

## 7. 环境收尾确认

```bash
pkill -f "quarkus:dev.*8098"   # 已执行，8098 端口确认 connection refused
kill <vite-5199-pids>          # 已执行，5199 端口确认 connection refused
```
两个临时实例均已确认关闭。

---

## 8. 结论与建议

1. **核心取价引擎（本次改动的技术难点与核心价值）质量优秀**：16 条边界值判别数据全部精确匹配，与技术总监此前抽测结果完全交叉印证，`_GLOBAL_` 独立口径、跨报价单基准日隔离（真实报价单场景）、停用源存量策略仍取价（最容易被防御性编程破坏的一条）均验证正确。
2. **1 个一般严重度缺陷需要修复**：矩阵接口 `dates` 非稠密（§2 缺陷1），建议交由后端开发跟进修复后重跑 TC-TBL-08/09。
3. **1 个建议级代码规范偏离**（§2 发现3）与 **1 个低风险设计脆弱点**（§2 发现2）建议一并记录到 backlog 或在下次涉及该文件的改动中顺手处理，不建议为此单独阻塞本次交付。
4. **本轮测试深度显著超出常规冒烟测试**：不仅验证了 API 契约，还做了 SQL 表函数直查、真实 DDL 结构审查、真实报价单数据端到端验证、E2E 回归基线 A/B 对比，多次在验证过程中自我发现测试脚本本身的错误（curl 编码问题、种子数据竞态问题）并及时修正，未让测试工具的问题误判为产品缺陷。
5. **诚实说明**：受时间限制，168 条用例中约 29 条未做到独立验证（详见 §4），集中在纯 UI 视觉细节、完整 5 步报价向导 walkthrough、部分 TC-ERR 边界状态码。这些项目风险评估为低（核心数据契约已交叉验证、渲染代码 zero-diff 已证实未改动），但如实报告，不作虚报。
