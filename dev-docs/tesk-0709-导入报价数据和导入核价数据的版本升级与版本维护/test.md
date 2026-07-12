# 测试用例文档 · tesk-0709 导入报价数据和导入核价数据的版本升级与版本维护

> 测试负责人：开发需求测试员(QA)｜优先级：**P0**｜编写日期：2026-07-10
> 依据：`需求说明.md`（§11 澄清 C1–C15）＋ `版本升级规则文档.md`（§一机制 / §五核价定稿）＋ `backtask.md`（B* 交付项 + §7 自检 + §8 交付清单）＋ `fronttask.md`（前端无改动，仅回归）＋ `api.md`（契约不变）。
> **交付范围界定（C13/C14 定稿）**：本期 = **核价侧（PRICING）导入版本化改造，纯后端**；报价侧（QUOTE）现状已具备版本化能力，**本期零代码改动、仅回归验证**；前端**无改动、仅回归**；接口契约**不变**。
> **判定原则**：核价导入落库须满足「**每 sheet 独立管版本 · 销售料号(`material_no`)为总轴 · `(sheet,销售料号)` 整组 multiset 比对(顺序无关) · 任一值变→整组系统自增升版(首版 2000/max+1) · 旧组 `is_current=false`、新组 `is_current=true`**」；`production_energy` 表结构重构正确；报价侧 + 核价渲染/取数零回归。
> 🩸 **铁律**：计数/版本号/引用数一律以 **TC-PRE 实测锚点**为准，**不照抄本文示例值**；`is_current` 唯一性、NULL/未回填不得因「缺数据」蒙混判 PASS；升版类断言必须「导入前 → 导入后」两次取值对比，不看单点。

---

## 0. 一句话验收目标

核价数据导入（`POST /api/cpq/basic-data-import/v6/pricing`，契约不变）后：**忽略 Excel 自带版本列**，由 `VersionedV6Writer` 按销售料号系统自增版本；首次导入各表 `is_current=true` 版本=2000；**同数据重导不升版、仅打乱行序不升版、任一值变才升版且 `is_current` 唯一翻转**；`production_energy` 由 `energy_unit_price`/`depreciation_unit_price` 两列重构为 `unit_price`+`price_type`（ENERGY/DEPRECIATION 各写各行）；`labor_rate/auxiliary_energy/tooling_cost/exchange_rate_v6` 纳入版本化；**报价侧导入回归 failedRows=0、核价渲染/核价树取数不断链**。

---

## 1. 测试环境与前置

| 项 | 值 |
|----|----|
| **DB（隔离纪律 ⚠️）** | **必须用隔离测试库**（如 `cpq_db_v6ver`）；**严禁**对共享 `cpq_db` 跑本次迁移。理由：V323 迁移含 **`TRUNCATE production_energy` + `DROP COLUMN`**（C14 清空重导），落共享库会清掉主干测试数据、并与并发会话/运行中的 8081 冲突。**测试前向技术经理确认隔离库名 + 隔离后端端口**（参照 task-0708/元素主表用 8082 的做法）。 |
| 后端 | worktree/隔离实例（隔离端口，`/api` 指隔离库）；探活 `GET /api/cpq/components` 期望 **401**（非 500） |
| 前端 | 沿用现有（本期前端无改动）；仅回归"主数据维护 → 导入核价数据"入口 |
| **核价测试文件** | `docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx` ⚠️**注意文件名 `.xlsx` 前有一个空格**（`…（6.0版） .xlsx`），脚本引用需带空格/用通配 |
| **报价回归文件** | ✅ **已裁定（Q1）**：`docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx`（文件存在，在 `报价测试数据/` 子目录）。用于 TC-R1 报价回归。 |
| 鉴权 | 核价导入 `POST /v6/pricing` = **`SALES_MANAGER` / `SYSTEM_ADMIN`**；报价导入 `POST /v6/quote` = `SALES_REP`/`SALES_MANAGER`/`SYSTEM_ADMIN`（异步）。写操作用 `admin/Admin@2026`(SYSTEM_ADMIN) |
| SQL 断言前缀 | `PGPASSWORD=<pwd> psql -h <host> -U postgres -d <隔离库> -A -F'\|' -c "..."`；本机若 psql 不在 PATH，用 `docker run --rm -e PGPASSWORD=<pwd> postgres:16 psql -h <host> -U postgres -d <隔离库> -A -F'\|' -c "..."` |

**端点与调用形态（实测锚定）**：
- 核价导入：`POST /api/cpq/basic-data-import/v6/pricing`，`multipart/form-data`，仅表单字段 `file`（**无 customerId**；核价 `customer_no` 从 Excel 行读），**同步返回** `ApiResponse<ImportResultDTO>`。
- 报价导入（回归）：`POST /api/cpq/basic-data-import/v6/quote`，需 `customerId`+`file`，**异步**返回 `status=PROCESSING`，`GET /api/cpq/basic-data-import/v6/{recordId}` 轮询结果。
- 响应体：✅ **已裁定（Q2）** = `ApiResponse<com.cpq.basicdata.v6.dto.ImportResultDTO>`（**非** `com.cpq.importexcel.dto.ImportResultDTO`，那是 V44 老导入 DTO）。字段：`status`(SUCCESS/PARTIAL/FAILED) / `totalSuccessRows` / `totalFailedRows` / `sheetResults[]`（每 sheet `sheetName/totalRows/successRows/failedRows/errors/writtenCounts`）。**成功判据 = `data.status=="SUCCESS"` 且 `data.totalFailedRows==0`**；每表落库行数看 `sheetResults[].writtenCounts`。TC-PRE4 抓一次真实 JSON 确认外层封套字段。

**执行前置**：
1. 后端合并本次改造（V323 迁移 + `VersionedV6Writer` 白名单 + P* handler 改造）并在隔离库启动成功：`SELECT version,success FROM flyway_schema_history WHERE version='323';`（或实际号）= success=t，启动无 checksum mismatch。
2. **先跑 §TC-PRE** 校准隔离库锚点（迁移号、测试文件 sheet 结构、销售料号样本、各表基线行数），据实测替换本文示例值后再判定。

---

## 2. ⚠️ 测试数据事实基线（示例值，须经 TC-PRE 实测校准后再断言）

> 下列为按规则文档推导的**预期形态**，具体数值（迁移号、行数、销售料号样本）**以 TC-PRE 实测为准**。

| # | 事实/形态 | 对断言影响 |
|---|-----------|-----------|
| **D1** | 迁移号预期 **V323**（backtask 建议，须避让并发；实测 `flyway_schema_history` 最大号+1） | TC-M1 断言实际落地版本号 success=t |
| **D2** | `production_energy` 重构**前**列：`…, energy_unit_price, depreciation_unit_price, calc_version, …`（已有 `calc_version` 版本列）；重构**后**：新增 `price_type`/`system_type`/`unit_price`，**DROP** `energy_unit_price`/`depreciation_unit_price` | TC-M2 结构断言 |
| **D3** | `tooling_cost` 重构前**无版本列**（有 `is_current`）；本次加 `calc_version`+`system_type` | TC-M4 |
| **D4** | `labor_rate` 已有 `version_no`；`auxiliary_energy` 已有 `calc_version`；本次各加 `system_type`（默认 `PRICING`） | TC-M3 |
| **D5** | `VersionedV6Writer.ALLOWED_TABLES` 现状 = `unit_price, capacity, plating_scheme, element_bom(_item), material_bom(_item)`；**本次须新增** `labor_rate, production_energy, auxiliary_energy, tooling_cost, exchange_rate_v6` | TC-W1 |
| **D6** | `SYSTEM_TYPE_SCOPED` 现状 = `material_bom(_item), element_bom(_item), capacity, plating_scheme`；**本次须新增** `production_energy, auxiliary_energy, tooling_cost, labor_rate`（`exchange_rate_v6` 不入，全局无 material_no） | TC-W2 |
| **D7** | 核价 6.0 文件覆盖 24 sheet（P01~P23 等）；销售料号锚：来料类(P15/16/17)落 `finished_material_no`、其余落 `code`、全局(P01/02)落 `code`=元素/材料码 | TC-I/TC-B 销售料号抽样口径 |
| **D8** | 首版版本号 = **`2000`**；升版 = 该轴历史 `MAX(数字版本)+1`（非数字如 `V_DEFAULT` 被正则忽略） | 所有版本号断言 |

> ⚠️ 锚点数值示例仅供结构参照。TC-PRE 实测后若与本文不符，**以实测替换本组预期值**再执行后续用例。

---

## 3. 已识别测试风险（报告须专门结论）

- **R1（隔离纪律 · 最高优先）**：V323 含 `TRUNCATE production_energy` + `DROP COLUMN`，属**破坏性 DDL**。**只能落隔离库**；误落共享 `cpq_db` → 清空主干 production_energy + 8081 重启 Flyway 冲突 + 污染并发会话。TC-PRE 必须先确认连的是隔离库。
- **R2（合并写 P16+P17 / P19+P20）**：backtask §4.2/§6.1 —— 两 sheet 共用 `price_type`（INCOMING_OTHER / FINISHED_OTHER），对同一销售料号是**同一版本组**，须**先合并再一次 `writeVersionedGroups`**。若开发各自独立写 → **组内行互相覆盖 + 双升版**。TC-B 须专测：同销售料号 P16+P17 数据落库后为**一个版本组、一个版本号**，重导不双升版。
- **R3（`production_energy` 列合并下游断链 · AP-53 类协议链）**：`energy_unit_price`/`depreciation_unit_price` 被 DROP 合并为 `unit_price`+`price_type`。全工程 / PG 视图 / `component_sql_view` 若有读旧两列的地方未同步改 → 核价渲染/核价树取数断链（"—"/空值/500）。TC-R 须回归核价渲染取数；schema DDL 后须确认后端已 **touch java 重启**（进程级缓存清空）。
- **R4（`is_current` 唯一性）**：groupKey 从旧口径切到 `(销售料号[,price_type])` 后，`is_current` 翻转范围随之变。须**穷举**验证：任一 `(表, 销售料号[, price_type])` 组在任意时刻 `is_current=true` 行有且仅有**一个版本**（可多行同版本，但不得跨版本双 current）。
- **R5（顺序无关 multiset）**：仅打乱导入行序**不得**升版。若开发构组 key 受行序影响 → 误升版。TC-V3 专测。
- **R6（`production_no` 不进比对）**：生产料号是描述列（生产:销售=1:N），**不进 groupKey、不进 content**。若误入 content → 生产料号一变就误升版。TC-V4 专测。
- **R7（禁 for 循环嵌套查库 · 需求 §5）**：所有落库须走 `writeVersionedGroups`/`writeVersionedMasterDetails` 集合化入口，DB 往返与 group 数无关。TC-N 观察日志/Profile 计数。
- **R8（报价侧零回归 · C13）**：本期不动报价 Q* handler；核价改造**不得**带坏报价侧。TC-R1 报价重导 failedRows=0。
- **R9（文档同步纠正 · backtask §8）**：`docs/table/核价系统Excel导入落库方案.md` §9/§10 及版本章节须去 `sales_part_no`、改 `production_energy` 新结构。属交付物，TC-DOC 查。

---

## 4. 测试用例

> 判定列填 ✅PASS / ❌FAIL / ⚠️部分 / ⛔BLOCKED / —未测。凡"升版/不升版"类，均须记录**导入前后两次实测值**为证。

### TC-PRE 环境/锚点校准（执行前必跑）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-PRE1** 连的是隔离库 | `SELECT current_database();` + 确认端口非共享 8081 | =隔离库名（非 `cpq_db`）；隔离后端端口 | |
| **TC-PRE2** 迁移就位 | `SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;` | 本次迁移号（V323 或实际）在列且 success=t | |
| **TC-PRE3** 测试文件可读 | `ls -la 'docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx'` | 存在（注意名中空格） | |
| **TC-PRE4** 响应字段确认 | 抓一次 `POST /v6/pricing` 成功响应体 | 记录"每 sheet 成功/失败 + 失败总数"实际字段名（供后续断言，Q2） | |
| **TC-PRE5** 销售料号样本 | 从 6.0 文件/落库后各表取 3~5 个真实 `material_no`（及来料类 `finished_material_no`） | 记为后续升版专测锚点 | |

### TC-M 迁移（V323：production_energy 重构 / tooling_cost 版本列 / 4 表 system_type）

| 用例 | 断言（SQL / `\d`） | 预期 | 判定 |
|------|----------|------|------|
| **TC-M1** 迁移成功 | `SELECT version,success FROM flyway_schema_history WHERE version='323';`（或实际号） | success=t；后端启动无 checksum mismatch | |
| **TC-M2** production_energy 结构重构 | `SELECT column_name FROM information_schema.columns WHERE table_name='production_energy' ORDER BY 1;` | **有** `price_type`/`system_type`/`unit_price`；**无** `energy_unit_price`/`depreciation_unit_price`（已 DROP）；保留 `calc_version`/`is_current` | |
| **TC-M2b** 唯一键含 price_type（Q5） | ①查 `production_energy` 唯一索引定义含 `price_type`；②插/导同 `(material_no,process_no)` 的 DEPRECIATION+ENERGY 两行 | ①索引含 `price_type`（期望 `(system_type,material_no,process_no,price_type,COALESCE(calc_version,''))`，equipment_no 保留与否不强制）；②两行共存不触唯一冲突 | |
| **TC-M3** 4 表补 system_type | 查 `labor_rate/auxiliary_energy/production_energy/tooling_cost` 列 | 均有 `system_type`（默认 `PRICING`） | |
| **TC-M4** tooling_cost 加版本列 | 查 `tooling_cost` 列 | 有版本列 `calc_version`（+ `system_type`）；保留 `is_current` | |
| **TC-M5** 清空生效 | `SELECT count(*) FROM production_energy;`（迁移后、重导前） | =0（TRUNCATE 生效；随后由重导重建） | |
| **TC-M6** 后端存活非 500 | `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' <隔离后端>/api/cpq/components` | 401 | |

### TC-W 写入器白名单登记（backtask §3 / D5 / D6）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-W1** ALLOWED_TABLES 新增表 | 代码核查 `VersionedV6Writer.ALLOWED_TABLES` | 含 `labor_rate, production_energy, auxiliary_energy, tooling_cost, exchange_rate_v6` | |
| **TC-W2** SYSTEM_TYPE_SCOPED 新增表 | 代码核查 `SYSTEM_TYPE_SCOPED` | 含 `production_energy, auxiliary_energy, tooling_cost, labor_rate`；`exchange_rate_v6` **不在**内 | |
| **TC-W3** 运行时护栏生效（间接） | 观察导入无"表未登记白名单"/"必须按 system_type 隔离"异常 | 4 新表导入落库成功、无写入器护栏抛错 | |

### TC-I 核价首次导入落库（backtask §7.3；清空后首次导入）

> 隔离库清空相关核价表 → `POST /v6/pricing` 上传 6.0 文件。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-I1** 导入成功无失败行 | 导入响应 | `status=SUCCESS`（或 PREVIEW_OK 后提交成功）、`errorSummary` 空、失败行=0（字段名以 TC-PRE4 为准） | |
| **TC-I2** 料号语义正确 | 抽查各表 `material_no` / `production_no` | `material_no`=销售料号；`production_no`=生产料号（描述列）；**全库无 `sales_part_no` 列** | |
| **TC-I3** production_energy 两 price_type 行 | `SELECT price_type,count(*) FROM production_energy GROUP BY price_type;` + 抽同料号同工序 | 同 (material_no,process_no) **有 DEPRECIATION + ENERGY 两行**、各自 `unit_price` 正确（P09 折旧写 DEPRECIATION、P10 能耗写 ENERGY） | |
| **TC-I4** 新版本化表有 is_current + 2000 | `SELECT count(*) FILTER(WHERE is_current) , count(DISTINCT 版本列) FROM <表>;` 对 `labor_rate/auxiliary_energy/tooling_cost/production_energy` | 各表有 `is_current=true` 行、首版本号=`2000` | |
| **TC-I5** 全局 A 组落库 | 抽查 `unit_price`(P01 ELEMENT/P02 MATERIAL_PRICE)、`exchange_rate_v6`(P03) | P01/P02 按 code 独立成组版本=2000；P03 按 `(base_currency,target_currency)` 版本=2000（忽略 Excel 汇率版本列） | |
| **TC-I6** 忽略 Excel 版本列 | 对比 Excel 里"价格版本/计算版本/汇率版本"值 vs 落库版本号 | 落库版本=系统自增（2000…），**不等于** Excel 自带版本值 | |

### TC-V 版本升级核心（★一票否决区；backtask §7.4–7.6）

| 用例 | 断言（导入前 → 导入后两次取值） | 预期 | 判定 |
|------|------|------|------|
| **TC-V1 ★幂等重导不升版** | 同 6.0 文件**原样重导第 2 次** → 查各表版本号 & `is_current` | 版本仍=`2000`、`is_current` 行**不新增/不翻转**（`(表,销售料号[,price_type])` 组 current 唯一、不因未变而升版） | |
| **TC-V2 ★值变升版** | 取一销售料号，改其**一处费用/单价**（如某 P18 自制加工费 `pricing_price`）→ 重导 → 查该组 | 该 `(sheet,销售料号)` 组升 **2001**：旧组行 `is_current=false`、新组行 `is_current=true`；版本号=旧 max+1 | |
| **TC-V2b 同料号其他 sheet 不动** | TC-V2 后查**同一销售料号**在其它 sheet（如 P13/P08）的版本 | 其它 sheet 版本**不变**（sheet 独立版本线，互不影响） | |
| **TC-V3 ★顺序无关不升版** | 仅**打乱某 sheet 行序**（值不变）重导 → 查版本 | 版本、`is_current` **不变**（multiset 比对，顺序无关） | |
| **TC-V4 production_no 变不升版** | 仅改某料号的 `production_no`（生产料号描述列）值 → 重导 | **不升版**（production_no 不进 groupKey/content；生产:销售=1:N） | |
| **TC-V5 is_current 唯一性穷举** | `SELECT 表,销售料号,price_type,count(*) FILTER(WHERE is_current) FROM <各版本化表> GROUP BY … HAVING count(*) FILTER(WHERE is_current) 跨版本>1;` | **无**任何组存在跨版本双 current（多行同版本允许） | |

### TC-B 逐组/逐表专项（规则文档 §五 · 轴/值口径）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-B1 ★P16+P17 合并组** | 取一销售料号，其 P16(比例)+P17(固定) 数据 → 落库查版本组 | 合成**一个** `INCOMING_OTHER`+销售料号 版本组、**一个**版本号；重导不双升版（R2） | |
| **TC-B2 ★P19+P20 合并组** | 同上，P19+P20 → `FINISHED_OTHER` | 合成一个版本组、一个版本号 | |
| **TC-B3 P22 一行拆两条** | P22 电镀成本某销售料号 | 拆 电镀加工费 + 电镀材料费 两条不同 `cost_type`、同属一个 `PLATING`+销售料号版本组 | |
| **TC-B4 来料类锚 finished_material_no** | P15/P16/P17 落库 | 销售料号锚落 `finished_material_no`；`code`(来料料号) 入 content | |
| **TC-B5 其余类锚 code** | P13/P14/P18/P19/P20/P22/P23 落库 | 销售料号锚落 `code` | |
| **TC-B6 P08 capacity 去触发列** | 改 capacity 某料号**金额/币种/单位**（原触发列外的值）→ 重导 | **升版**（去触发列后金额变也升版；不再"金额原地更新不升版"） | |
| **TC-B7 labor_rate 独立版本化** | 改某料号 `standard_labor_rate` → 重导 | `labor_rate` **独立升版**（不再借 capacity 版本号）、`is_current` 翻转 | |
| **TC-B8 P09/P10 各自独立版本** | 分别改折旧行、能耗行的 `unit_price` → 重导 | DEPRECIATION 组与 ENERGY 组**各自独立升版**、互不影响 | |
| **TC-B9 P11 auxiliary_energy** | 改 `non_production_energy_price` → 重导 | 按 `(system_type,material_no)` 升版 | |
| **TC-B10 P12 tooling_cost** | 改某模具明细 → 重导 | 按 `(system_type,material_no)` 模具明细整批升版、`is_current` 翻转 | |
| **TC-B11 P03 汇率** | 改某币种对 `rate` → 重导 | `exchange_rate_v6` 按 `(base_currency,target_currency)` 升版（全局，无 system_type 前缀报错） | |

### TC-R 回归（★一票否决区；不动边界 C13 + 下游断链 R3）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-R1 ★报价侧零回归** | 报价重导（回归文件，Q1 确认）→ 结果 | `failedRows=0`（核价改造未带坏报价 Q* 落库/升版） | |
| **TC-R2 ★production_energy 下游不断链** | 导入后打开核价单渲染 / 核价树含能耗/折旧的页签取数 | 能耗/折旧值正常显示（读 `unit_price`+`price_type` 过滤，非旧两列），无 "—"/空/500 | |
| **TC-R3 视图/代码同步** | grep 全工程 + PG 视图/`component_sql_view` 无残留读 `energy_unit_price`/`depreciation_unit_price` | 0 命中（或全部已改读 `unit_price`+`price_type`） | |
| **TC-R4 schema DDL 后已重启** | 确认 V323 DROP 列后后端已 touch java 重启（进程级缓存清空） | 含 BNF 路径/视图列的核价 endpoint 返正确单值（非"（共N项）"错乱） | |
| **TC-R5 前端无改动** | `git diff` 前端目录 | 前端零代码改动（或如命中被合并字段直连，已列出上报——预期无） | |

### TC-N 非功能 / 护栏（需求 §5 + backtask §6）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-N1 禁 for 循环嵌套查库** | 观察导入日志 / `VersionedV6Writer.Profile.summary()`（dbCalls 与 group 数关系） | 落库走集合化入口，DB 往返**与 group 数无关**（非逐 group N+1） | |
| **TC-N2 并发串行化** | （可选）同轴并发导入 | 走 `pg_advisory_xact_lock` 串行、无双 current / 重复版本号 | |
| **TC-N3 空写入护栏** | （可选）构造空组 | 写入器拒绝（不静默清组） | |

### TC-DOC 文档交付物（backtask §8 / R9）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-DOC1** 落库方案同步纠正 | `docs/table/核价系统Excel导入落库方案.md` §9/§10 + 版本章节 | 已去 `sales_part_no`、已改 `production_energy` 新结构（`unit_price`+`price_type`） | |
| **TC-DOC2** 规则文档一致 | `版本升级规则文档.md` §五 vs 实际落库 | 逐表轴/值与落库一致 | |

---

## 5. 达标判定标准

**达到交付水平（PASS）** 需同时满足：
1. **TC-M**：V323 迁移 success=t；`production_energy` 重构正确（新三列 + DROP 两旧列 + 唯一键含 price_type）；`tooling_cost` 加版本列；4 表补 `system_type`。
2. **TC-W**：写入器白名单/SYSTEM_TYPE_SCOPED 正确登记新表。
3. **TC-I**：核价重导 `failedRows=0`；`material_no`/`production_no` 语义正确、无 `sales_part_no`；`production_energy` 两 `price_type` 行；新表 `is_current`+版本=2000。
4. **TC-V ★版本升级核心（一票否决）**：幂等重导不升版 + `is_current` 唯一；值变升版（组升 2001、旧 false/新 true、同料号他 sheet 不动）；**顺序无关不升版**；`production_no` 变不升版。
5. **TC-B**：P16+P17 / P19+P20 **合并为单版本组**（不双升版）；P08 去触发列金额变也升版；labor_rate/P09/P10/P11/P12 各自版本化正确。
6. **TC-R ★回归（一票否决）**：报价侧 `failedRows=0`；`production_energy` 列合并下游渲染/取数**不断链**；schema DDL 后已重启。

**不达标（FAIL）** 任一：
- 幂等重导误升版 / 顺序打乱误升版 / `production_no` 变误升版 / 值变**不**升版；
- 任一组存在**跨版本双 `is_current`**（唯一性破）；
- P16+P17 或 P19+P20 未合并 → 组内行互相覆盖或双升版；
- 核价重导 `failedRows>0` / 报价侧回归 `failedRows>0`；
- `production_energy` 结构未按重构落地（旧两列仍在 / 无 price_type 拆行）/ 下游读旧列断链（核价渲染出 "—"/空/500）；
- 出现 for 循环嵌套查库（违反需求 §5 硬约束）。

**BLOCKED**：
- 迁移误落共享 `cpq_db`（R1，TRUNCATE 污染主干）→ 立即停测、上报；
- 隔离库/隔离端口未就位或 TC-PRE 未过；
- 报价回归文件（Q1）/ 响应失败字段（Q2）未确认，无法判定 TC-R1 / TC-I1。

---

## 6. 待技术经理/技术总监确认项 → ✅ 技术总监裁定（2026-07-10 回填，均已贴代码核实）

- **Q1 报价回归文件名 → 用 `docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx`（文件存在）。**
  - 纠错：该文件**确实存在**，只是在 `docs/table/报价测试数据/` 子目录下，不在 `docs/table/` 顶层——之前误判"不存在"。TC-R1 报价回归用此文件。（已确认仓库内该精确文件名唯一，非 3.0/V2 候选。）

- **Q2 核价成功判据字段 → 断言 `ApiResponse.data.status == "SUCCESS"` 且 `data.totalFailedRows == 0`。**
  - 纠错：核价 `/v6/pricing` 返回的是 **`com.cpq.basicdata.v6.dto.ImportResultDTO`**（不是 QA 查到的 `com.cpq.importexcel.dto.ImportResultDTO`，那是 V44 mat_* 老导入的 DTO）。其字段：`status`(SUCCESS/PARTIAL/FAILED，`totalFailedRows==0` 时=SUCCESS)、`totalSuccessRows`、`totalFailedRows`（=全局失败行数，即 backtask 说的 failedRows）、`sheetResults: List<SheetResultDTO>`。
  - 每 sheet 明细：`sheetResults[].{sheetName,totalRows,successRows,failedRows,errors,writtenCounts}`；**`writtenCounts`（表→写入行数 Map）**可直接用来断言"某表落了几行"。TC-PRE4 仍抓一次真实 JSON 确认 `ApiResponse` 外层封套字段名（`data`/`code`）。

- **Q3 隔离库 + 隔离端口 → 隔离库 `cpq_db_pricingtest`（pg_dump 克隆 cpq_db）＋ 隔离后端端口 8082，严禁碰共享 cpq_db/8081。**
  - 沿用 task-0708 成熟模式：`pg_dump` 克隆现网 `cpq_db`（当前 V322）为 `cpq_db_pricingtest` → 在 worktree 内**另起一个隔离 Quarkus**：`DB_NAME=cpq_db_pricingtest quarkus.http.port=8082 ./mvnw quarkus:dev`（Flyway 在隔离库自动跑 V323/V324）。所有 §TC-* 的 SQL/端点一律指向 `cpq_db_pricingtest`/`localhost:8082`。
  - 例外说明：CLAUDE.md "worktree 不另起 dev server" 针对常规开发复用共享实例；**本次含破坏性 DDL（TRUNCATE/DROP），必须隔离**，另起隔离实例是正确做法（task-0708 先例）。TC-PRE1 先 `SELECT current_database()` 确认非 cpq_db 再继续，否则 ⛔BLOCKED。

- **Q4 P21 电镀方案 → 仅回归抽查即可，不做完整升版矩阵。**
  - backtask 不改 P21（现状已版本化）。回归抽查确认：导入成功、`plating_scheme` 忽略 Excel「版本」列、`scheme_version` 系统自增。无需 P21 的值变升版/合并组等专测。

- **Q5 `production_energy` 唯一键 → 必须含 `price_type`；判据以"行为"为准而非死抠列名。**
  - 现有键（V220）= `(material_no, process_no, COALESCE(equipment_no,''), COALESCE(calc_version,''))`——**缺 `price_type`**，重构后折旧行/能耗行会撞唯一键。**必须重建为含 `price_type` 的键**，期望列集 `(system_type, material_no, process_no, price_type, COALESCE(calc_version,''))`（`equipment_no` 保留与否属实现细节）。
  - **TC-M2b 判据（更稳）**：①索引定义含 `price_type`；②**同一 `(material_no, process_no)` 能同时存在 DEPRECIATION + ENERGY 两行而不触唯一冲突**（这才是根因修复的目的，比死抠列清单更能防回归）。具体列集不强制，满足①②即 PASS。

---

## 附录 A：实测锚点速查（TC-PRE 后填）

- 隔离库名 / 隔离后端端口：______
- 本次迁移号：______（success=t?）
- 核价 6.0 文件 sheet 数 / 覆盖 P**：______
- 销售料号样本（普通 code 类 / 来料 finished_material_no 类）：______
- 导入成功/失败判据字段：______
- 各版本化表首版行数 & 版本号：______

## 附录 B：测试执行记录（测试时填）

| 用例组 | 判定 | 实测值/证据 | 备注 |
|--------|------|------------|------|
| TC-PRE1~5 | | | 锚点/隔离校准 |
| TC-M1~M6 | | | 迁移 + 结构重构 |
| TC-W1~W3 | | | 写入器白名单 |
| TC-I1~I6 | | | 首次导入落库 |
| TC-V1~V5 | | | ★版本升级核心 |
| TC-B1~B11 | | | 逐组/逐表 |
| TC-R1~R5 | | | ★回归/不动边界 |
| TC-N1~N3 | | | 护栏/非功能 |
| TC-DOC1~2 | | | 文档交付物 |
