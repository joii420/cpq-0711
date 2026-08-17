# 测试用例文档 · task-0708 导入报价单/核价单落库料号语义纠偏

> 测试负责人:测试员（QA）｜优先级:P0｜编写日期:2026-07-08
> 依据:`需求说明.md` §8 验收标准 + `backtask.md` §1/§6 权威规则与自检 + `api.md` §4 接口对照 + `fronttask.md`（前端零改动）
> 判定原则:**功能是否达到交付水平** = 迁移终态正确 + 报价/核价清空重导后数据按新语义正确落库 + 撞键不覆盖 + 升版 is_current 唯一 + 接口契约不变 + 两份文档已纠正。

---

## 0. 一句话验收目标

系统引入"销售料号"（=宏丰料号改名，系统主料号）后，统一料号落库语义：
- `material_no` ← **销售料号**（主料号，进唯一键）
- `production_no` ← **生产料号**（新增描述列，不进键）
- `material_part_no` ← **材质料号**（原"物料料号"，仅 `element_bom`/`element_bom_item`，进唯一键）
- 废弃 V311 的反向 `sales_part_no` 设计（列删除 + 唯一键去 sales 后缀）

两个测试文件**清空重导**后数据正确落库即为达标。

---

## 1. 测试环境与前置条件

| 项 | 值 |
|----|----|
| 后端 | `http://localhost:8081`（Quarkus dev；探活 `GET /api/cpq/components` 期望 401） |
| DB | `10.177.152.12:5432/cpq_db`（postgres / joii5231） |
| 报价客户 | **罗克韦尔** — id=`3027d83b-d412-407d-ae43-5d513fed7b1e`，code=`CUST-1269` → QUOTE 落库 `customer_no='CUST-1269'` |
| 报价测试文件 | `docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx`（19 Sheet） |
| 核价测试文件 | `docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx`（25 Sheet） |
| 前端 | **本次零改动**（fronttask.md），不作页面测试，仅在 §TC-F 校验接口契约 |
| 鉴权 | 会话 Cookie；导入端点需 `SALES_REP`/`SALES_MANAGER`/`SYSTEM_ADMIN` 角色 |

**执行前置**：
1. 确认后端已加载最新（handler 改造已合并）代码——`touch` 一个 java 文件触发 Quarkus 热重载后再测。
2. 确认迁移已应用：`SELECT version,success FROM flyway_schema_history WHERE version IN ('308','311','315')` → 三条 success=t。
3. **清空重导**：V6 导入自带按 `system_type` 清空重导机制；若需手工清场见 §附录 A。

---

## 2. ⚠️ 测试数据事实基线（判定口径，编写时已实测两测试文件，务必先读）

这些是从**真实测试文件**中提取的事实，直接决定断言的"预期值"。测试时若实现与此不符即为 Bug。

| # | 事实 | 对断言的影响 |
|---|------|------------|
| D1 | **核价 6.0 全部 Sheet 的 `生产料号` 列均为空**（逐 Sheet 实测 0 非空行） | `production_no` 落库预期 = **NULL**。⚠️ 本数据**无法正向验证"生产料号值→production_no"映射**，只能验证「列存在 + 落 NULL + material_no 正确取销售料号」。见 §TC-C4 与 §3 风险。 |
| D2 | **报价 V3 无任何生产料号列** | 报价侧 `production_no` 恒 NULL（符合 backtask §1.1）。 |
| D3 | **报价 V3 `物料与元素BOM` 的 `材质料号` 列为空**（0/2 行有值） | 报价 `element_bom.material_part_no` 预期 = NULL。 |
| D4 | **核价 `物料与元素BOM` 撞键黄金点**：销售料号 `3110520789` 下有 **2 个不同材质料号** `2101110225` + `2111410069`；`2120011658`→`3112230066`；`2120011659`→`3112230067` | element_bom 撞键测试核心：`3110520789` 必须落 **2 行**（material_part_no 各异），不得互相覆盖。见 §TC-D。 |
| D5 | 报价销售料号样例 `3120012530`（客户产品编号 `10772736`，单重 42.9167） | §TC-B 抽查锚点。 |
| D6 | 核价销售料号样例 `3120018220`（品名 AgC4触点）、`2120011658`、`3110520789`、`2120011659` | §TC-C 抽查锚点。 |
| D7 | 报价 V3 多 Sheet 为空（元素回收折扣/来料*/年降系数=0 行）；有数据的主要是 客户料号关系(1)、物料BOM(1)、物料与元素BOM(2)、自制加工费(1)、成品其他费用(4)、组成件BOM(3)、组成件其他费用(2)、组装加工费(1)、电镀方案(2)、电镀费用(1)、单重(1)、元素单价(1) | 报价 `failedRows=0` 判定时，空 Sheet 不应计入失败。 |

---

## 3. 已识别的测试风险（报告需专门结论）

- **R1（生产料号无正向数据）** 【已上报需求方待定】：因 D1/D2，两文件均无生产料号数据，`production_no` 全落 NULL。**本次核心新增字段 `production_no` 的"取值正确性"无法被现有测试数据证伪**。处理见 TC-C4 双分支：
  - 需求方**补含生产料号值的新数据** → 走 TC-C4 分支②，验证 `production_no=对应行生产料号值 且 ≠ material_no`，R1 消除；
  - 需求方**确认不补数据** → 走 TC-C4 分支①，报告"达标判定"里**显式列为未覆盖遗留项**（"列存在+落NULL+未错填"通过；取值映射未覆盖），不默默带过、不阻断达标。
- **R2（存量脏数据）**：编写时实测 `material_customer_map` 有 235 QUOTE 行、`element_bom` 有 2231 行（含 4 PRICING）为历史遗留。测试必须**先清空对应 system_type**再导，否则计数/撞键判定被污染。
- **R3（handler 未适配列改名）**：核价「材质料号」（原"物料料号"）、「销售料号」（原"宏丰料号"）、报价「销售料号」（原"报价料号/宏丰料号"）若 handler 未加新列别名 → 该 Sheet `failedRows` 上升或整表落空。§TC-B/§TC-C 的 `failedRows=0` + 行数断言即为探针。特别关注 `P04PricingVersionHandler`（核价版本，读列若仍是宏丰料号→整表失败）。
- **R4（铸号正向路径无数据触发）**：决策要求"保留投入料号缺失时铸号"，但报价 V3 的投入料号/组成件料号列**实测均为空**（仅「组成件其他费用」有组成件料号 10002/10003→component_no），主铸号路径本轮不触发。TC-B8 为 N/A，报告须注明"铸号正向路径未回归"（逻辑保留但数据未覆盖），建议补含未匹配投入料号的样例二次验证。反向（Q04 不再错误铸号）由 TC-B5b 正向覆盖。

---

## 4. 测试用例

> 每个用例含：关联验收项 / 前置 / 步骤 / 断言（可直接执行的 SQL/命令）/ 预期 / 判定列（PASS/FAIL/BLOCKED，测试时填）。
> SQL 统一前缀：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -A -F'|' -c "..."`

---

### TC-A 迁移与 DB 结构（终态 schema）

关联：backtask §2、§6.1｜交付物：V315（或改写 V311）。

| 用例 | 步骤/断言 | 预期 | 判定 |
|------|----------|------|------|
| **TC-A1** 迁移成功 | `SELECT version,success FROM flyway_schema_history WHERE version IN ('308','311','315') ORDER BY version;` | 涉及的迁移全部 `success=t`（V315 存在且成功；若走改写 V311 路线则 V311 成功且无 checksum mismatch，后端能正常启动） | |
| **TC-A2** `sales_part_no` 已全删 | `SELECT table_name FROM information_schema.columns WHERE column_name='sales_part_no' AND table_name IN ('unit_price','material_bom','material_bom_item','element_bom','element_bom_item','capacity','labor_rate','production_energy','auxiliary_energy','tooling_cost','material_customer_map');` | **返回 0 行**（11 表均无 sales_part_no） | |
| **TC-A3** `production_no` 已加 | 同上查 `column_name='production_no'` | 覆盖 11 表：`unit_price,material_bom,material_bom_item,element_bom,element_bom_item,capacity,labor_rate,production_energy,auxiliary_energy,tooling_cost,material_customer_map`（后者由 V308 建，也应在） | |
| **TC-A4** `material_part_no` 仅两表 | 同上查 `column_name='material_part_no'` | **恰好** `element_bom` + `element_bom_item` 两表（不多不少） | |
| **TC-A5** element_bom 唯一键含 material_part_no | `SELECT indexdef FROM pg_indexes WHERE indexname='uq_element_bom_v6';` | 索引定义含 `material_no` 且含 `COALESCE(material_part_no,...)` 且 **不含** `sales_part_no` | |
| **TC-A5b** element_bom_**item** 唯一键含 material_part_no | `SELECT indexdef FROM pg_indexes WHERE indexname='uq_element_bom_item';` | 索引定义含 `material_part_no` 且 **不含** `sales_part_no`（V311 曾给子表唯一键追加 sales_part_no，须一并去除并纳入 material_part_no） | |
| **TC-A6** 其余表唯一键去 sales 后缀 | `SELECT indexname,indexdef FROM pg_indexes WHERE indexname IN ('uq_unit_price','uq_material_bom_v6','uq_material_bom_item','uq_capacity','uq_labor_rate','uq_production_energy','uq_auxiliary_energy','uq_tooling_cost');` | 全部 8 个索引 indexdef **均不含** `sales_part_no` | |
| **TC-A7** 后端存活 | `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/basic-data-import/v6/00000000-0000-0000-0000-000000000000` | 401/404（**非 500**——500 说明实体/schema 不一致） | |

---

### TC-B 报价导入落库（QUOTE，客户=罗克韦尔 CUST-1269）

关联：需求 §8.3、backtask §6.3｜测试文件：报价 V3.xlsx。

**执行**：报价单管理 → 从基础数据导入 → 选罗克韦尔 → 上传报价 V3 →（或直接 `POST /api/cpq/basic-data-import/v6/quote`，multipart `customerId=3027d83b-...`,`file=报价V3`）→ 轮询 `GET /v6/{recordId}` 至 `status=SUCCESS`。

> 🔒 **TC-B0（执行前必做·阻断项）**：核实客户 id/code 未变，否则本组全部 BLOCKED。
> `SELECT id,code FROM customer WHERE name LIKE '%罗克韦尔%';`
> 期望 id=`3027d83b-d412-407d-ae43-5d513fed7b1e`、code=`CUST-1269`；若不一致，以实测值替换本组所有 `customerId` 与 `customer_no='CUST-1269'` 断言后再执行。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-B1** 导入成功无失败 | 轮询响应 `status` 与 `failedRows` | `status=SUCCESS`，`failedRows=0`（空 Sheet 不计失败，见 D7） | |
| **TC-B2** material_no=销售料号 | `SELECT material_no,customer_no,customer_product_no FROM material_customer_map WHERE system_type='QUOTE' AND customer_no='CUST-1269' AND material_no='3120012530';` | 返 1 行；`customer_no=CUST-1269`，`customer_product_no=10772736`（D5） | |
| **TC-B3** 报价 production_no 恒 NULL | `SELECT count(*) FROM material_bom WHERE system_type='QUOTE' AND production_no IS NOT NULL;`（对 capacity/unit_price 等同理可抽查） | count=0（报价无生产料号列，D2） | |
| **TC-B4** 物料BOM 落库 | `SELECT count(*) FROM material_bom WHERE system_type='QUOTE' AND customer_no='CUST-1269' AND material_no='3120012530';` | ≥1 行（销售料号建键） | |
| **TC-B5** 报价 element_bom material_part_no=NULL | `SELECT material_no,material_part_no FROM element_bom WHERE system_type='QUOTE' AND customer_no='CUST-1269' AND material_no='3120012530';` | material_no=3120012530；material_part_no **IS NULL**（D3，报价材质料号列空） | |
| **TC-B5b** Q04 不再铸号（显式探针） | `SELECT count(*) FROM element_bom WHERE system_type='QUOTE' AND customer_no='CUST-1269' AND material_no ~ '^[0-9A-Za-z]{4}-[0-9]{10}$';` | **count=0**——报价 element_bom.material_no 一律为销售料号，**不得**是铸号格式 `XXXX-YYMMNNNNNN`（Q04 本次修复：不再按投入料号 resolve/铸号，`Q04ElementBomHandler.java:50` 已注释确认。此为 bug 修复的显式回归） | |
| **TC-B6** 单重落库 | `SELECT material_no FROM material_customer_map WHERE system_type='QUOTE' AND material_no='3120012530';`（单重 sheet 用「料号」列→material_no） | 3120012530 存在（无因列名"料号"≠"销售料号"漏读） | |
| **TC-B7** 无残留 sales 语义脏数据 | 对比导入前后：`SELECT count(*) FILTER(WHERE system_type='QUOTE') FROM material_customer_map;` | 清空重导后仅本次导入行数，无历史累加（R2） | |
| **TC-B8** 铸号正向路径（仅在有投入料号数据时） | ①先判数据：报价 V3 的 `来料*`/`自制加工费`/`组成件BOM` 的「投入料号/组成件料号」列**实测均为空**（仅「组成件其他费用」有 组成件料号 `10002/10003`→component_no）→ 本文件**不触发主铸号路径**。②若确触发：`SELECT material_no FROM material_customer_map WHERE system_type='QUOTE' AND material_no ~ '^[0-9A-Za-z]{4}-[0-9]{10}$';` 应为合法 `XXXX-YYMMNNNNNN` 且正确落库 | **无投入料号数据 → 判定 N/A，报告注明"铸号正向路径本次无数据触发，未回归"**（决策要求"保留缺失投入料号铸号"，逻辑保留但本轮数据未覆盖；R4） | |

---

### TC-C 核价导入落库（PRICING，customer_no 从 Excel 行读）

关联：需求 §8.4、backtask §6.4｜测试文件：核价 6.0.xlsx。

**执行**：主数据维护 → 导入核价数据 → 上传核价 6.0 →（或 `POST /api/cpq/basic-data-import/v6/pricing`，multipart `file=核价6.0`）→ 同步响应 `sheets[]`。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-C1** 逐 Sheet 全成功 | 响应 `sheets[]` 各 Sheet `failed=0` | 无 Sheet 因料号列改名报错；`failedRows=0`（重点看 物料与元素BOM、核价版本、产能等） | |
| **TC-C2** material_no=销售料号 | `SELECT material_no FROM capacity WHERE system_type='PRICING' AND material_no='3120018220' LIMIT 5;` | 返 material_no=`3120018220`（销售料号，**非**生产料号；D6） | |
| **TC-C3** 核价版本表不整表失败 | `SELECT count(*) FROM material_version_mgmt WHERE material_no='3120018220';`（P04PricingVersionHandler 须读「销售料号」） | ≥1（若为 0 且 sheet 报失败=handler 仍读宏丰料号，R3/FAIL） | |
| **TC-C4** production_no 落库（★分支用例，取决于需求方是否补测数据） | **分支①（不补数据，当前默认）**：`SELECT count(*) FILTER(WHERE production_no IS NULL) AS n_null, count(*) AS total FROM capacity WHERE system_type='PRICING';` <br>**分支②（拿到含生产料号值的新数据后升级）**：`SELECT material_no, production_no FROM capacity WHERE system_type='PRICING' AND production_no IS NOT NULL LIMIT 10;` | **分支①**：n_null=total（因 D1 全空→全 NULL）。判定=列存在且未被错填即 PASS；**取值映射正确性列为 R1 未覆盖遗留项，报告须显式记录，不默默带过**。<br>**分支②**：每行 `production_no` = 该行 Excel「生产料号」值，且 `production_no <> material_no`（证明生产料号未与销售料号混淆）。此分支通过后 R1 消除 | |
| **TC-C5** 物料BOM 落库 | `SELECT count(*) FROM material_bom WHERE system_type='PRICING' AND material_no='3120018220';` | ≥1 行 | |
| **TC-C6** 汇总/价格表未被误导 | 核价「汇总」无 handler（不导入）；材料/元素核价价格表不在 11 表 | `sheets[]` 中「汇总」不产生错误行；材料价格主表/元素价格主表键照旧未受影响 | |

---

### TC-D element_bom 撞键（核心，material_part_no 进唯一键）

关联：backtask §1.3、§5.3、§6.4｜数据锚点：D4。

**这是本次最关键的正确性用例**——`material_part_no` 若没真正进唯一键，同一销售料号下多个材质料号行会互相覆盖丢数据。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-D1** 核价同销售料号多材质料号均存在 | `SELECT material_no,material_part_no FROM element_bom WHERE system_type='PRICING' AND material_no='3110520789' ORDER BY material_part_no;` | 返 **2 行**，material_part_no 分别为 `2101110225` 与 `2111410069`（都在，未撞键覆盖，D4） | |
| **TC-D2** 其余销售料号材质料号正确 | `SELECT material_no,material_part_no FROM element_bom WHERE system_type='PRICING' AND material_no IN ('2120011658','2120011659') ORDER BY material_no;` | `2120011658`→`3112230066`；`2120011659`→`3112230067` | |
| **TC-D3** element_bom material_no=销售料号且 production_no=NULL | `SELECT DISTINCT material_no,production_no FROM element_bom WHERE system_type='PRICING' AND material_no='3110520789';` | material_no=销售料号；production_no **IS NULL**（该 Sheet 无生产料号列，backtask §1.3） | |
| **TC-D4** element_bom_item 同步含 material_part_no | `SELECT material_no, material_part_no, count(*) FROM element_bom_item WHERE system_type='PRICING' AND material_no='3110520789' GROUP BY 1,2;` | 子表行也按 material_part_no 区分，`2101110225`/`2111410069` 两组明细均在 | |
| **TC-D5** 无唯一键冲突报错 | 核价导入响应中 element_bom 相关 Sheet（物料与元素BOM）`failed=0` | 无 duplicate key / on conflict 异常 | |

---

### TC-E 升版与 is_current 唯一

关联：backtask §1.2、§5.1、§6.5、记忆 v6-child-multiversion-iscurrent-audit-scope。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-E1** 分组键=销售料号 | 首次核价导入后：`SELECT material_no,count(*) AS versions,count(*) FILTER(WHERE is_current) AS n_current FROM material_bom WHERE system_type='PRICING' GROUP BY material_no;` | 每个销售料号 `n_current=1`（当前版本唯一） | |
| **TC-E2** 重导递增不累加 | **同一文件再导一次**，重跑 TC-E1 | 版本号正确递增；`n_current` 仍恒=1（不出现跨版本多 current，不无限累加行） | |
| **TC-E3** 报价侧同理 | `SELECT material_no,count(*) FILTER(WHERE is_current) FROM material_bom WHERE system_type='QUOTE' GROUP BY material_no HAVING count(*) FILTER(WHERE is_current)<>1;` | **返回 0 行**（无多 current / 无 0 current） | |

---

### TC-F 接口契约不变（前端零改动佐证）

关联：api.md §1/§4、fronttask.md。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-F1** 报价端点入参不变 | `POST /v6/quote` 仍只需 `customerId + file` | 无新增必填字段；旧前端调用可成功 | |
| **TC-F2** 核价端点入参不变 | `POST /v6/pricing` 仍只需 `file` | 无新增必填字段 | |
| **TC-F3** 响应结构不变 | 轮询 `GET /v6/{recordId}` 返回字段集 | 仍为 `importRecordId/systemType/status/totalRows/successRows/failedRows/originalFileName/createdAt/metadata`，无增删 | |
| **TC-F4** 前端零改动佐证 | `git diff --stat` 本次合并范围 | `cpq-frontend/` 下**无源码改动**（与 fronttask.md 结论一致） | |

---

### TC-G 文档纠正（交付物）

关联：需求 §8.2、backtask §4。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-G1** 报价文档已补料号章节 | 阅 `docs/table/报价系统Excel导入落库方案.md` | 含"销售料号→material_no（主料号）/ 材质料号→material_part_no"说明；修正过时点（宏丰料号→销售料号列名、9 字头发号已废、system_type、customer_product_no 可空） | |
| **TC-G2** 核价文档已改写 | 阅 `docs/table/核价系统Excel导入落库方案.md` | "material_no=生产料号 + sales_part_no 维度" 已整体改写为 "material_no=销售料号 + production_no=生产料号"；物料与元素BOM 标注 material_part_no 例外；物料料号→材质料号改名；**已删除 sales_part_no 相关描述** | |
| **TC-G3** 无 sales_part_no 残留描述 | `grep -rn "sales_part_no" docs/table/*.md` | 两份落库方案文档中无遗留 `sales_part_no` 设计描述（或仅作"已废弃"说明） | |

---

### TC-H 回归 / 自检声明

关联：backtask §6 交付声明。

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-H1** 导入后建报价单可用 | `POST /v6/quote/create-quotation`（罗克韦尔，用 TC-B 的 importRecordId） | 返 `CommitResult` 成功，autoPopulate 拿到已落库料号数据（报价单能创建、数据非空） | |
| **TC-H2** 后端无 500 / 无 checksum mismatch | 查后端启动日志 / 再 `touch` 重启一次 | Flyway 无 mismatch；无实体-schema 不一致 500 | |
| **TC-H3** 交付自检声明齐全 | 开发方交付说明 | 含一行：flyway success=t ✅ / 报价 failedRows=0 ✅ / 核价 element_bom material_part_no 正确 ✅ / is_current 唯一 ✅ | |

---

## 5. 达标判定标准（测试报告结论口径）

**达到交付水平（PASS）** 需同时满足：
1. TC-A 全 PASS（迁移终态正确：sales_part_no 删净、production_no/material_part_no 到位、唯一键正确）。
2. TC-B / TC-C 报价与核价均 `failedRows=0`，material_no 落销售料号值正确。
3. **TC-D 全 PASS**（撞键不覆盖，`3110520789` 两材质料号都在）——一票否决项。
4. TC-E is_current 唯一、重导不累加——一票否决项。
5. TC-F 接口契约零变更、前端零改动。
6. TC-G 两份文档已纠正、无 sales_part_no 残留。

**允许带条件通过（PASS with注记）**，报告须列出下列**未覆盖遗留项**（显式记录、不默默带过）：
- **R1**（production_no 取值）：若需求方不补数据，`production_no` 仅验证"列存在+落 NULL+未错填"，取值映射正确性列为未覆盖项，建议补含生产料号值的样例二次验证；若补了数据走 TC-C4 分支②则本项消除。
- **R4**（铸号正向路径）：报价 V3 无投入料号数据，TC-B8 铸号正向路径未回归；Q04 反向不铸号已由 TC-B5b 覆盖。建议补含未匹配投入料号的样例二次验证。

**不达标（FAIL）** 任一：
- 任一测试文件导入出现 `failedRows>0` 且系料号列未适配所致；
- `3110520789` element_bom 少于 2 行（撞键覆盖丢数据）；
- material_no 落的是生产料号而非销售料号；
- is_current 出现多 current 或重导无限累加；
- 接口契约被改动 / 前端被动改动；
- 文档未纠正或仍描述 sales_part_no 为现行设计。

---

## 附录 A：手工清空重导 SQL（R2，若导入未自动清场时使用）

> ⚠️ 仅测试库操作。按 system_type 清，避免误删另一主线数据。

```sql
-- QUOTE 侧（报价）
DELETE FROM element_bom_item  WHERE system_type='QUOTE';
DELETE FROM element_bom       WHERE system_type='QUOTE';
DELETE FROM material_bom_item WHERE system_type='QUOTE';
DELETE FROM material_bom      WHERE system_type='QUOTE';
DELETE FROM material_customer_map WHERE system_type='QUOTE';
-- （其余 unit_price/capacity/... 按需同法）

-- PRICING 侧（核价）——同上把 'QUOTE' 换成 'PRICING'
```

## 附录 B：测试文件事实速查（实测提取，供报告引用）

- 报价 V3：19 Sheet；销售料号样例 `3120012530`；无生产料号列；物料与元素BOM 材质料号列空。
- 核价 6.0：25 Sheet；销售料号 `3120018220/2120011658/3110520789/2120011659`；**生产料号列全空**；物料与元素BOM 撞键点 `3110520789`→{`2101110225`,`2111410069`}。
- 罗克韦尔 code=`CUST-1269`。

---

## 附录 C：测试执行记录（测试时填写）

| 用例 | 判定 | 实测值 / 证据 | 备注 |
|------|------|--------------|------|
| TC-A1~A7（含 A5b） | | | |
| TC-B0（阻断前置） | | | |
| TC-B1~B8（含 B5b） | | | |
| TC-C1~C6（C4 双分支） | | | |
| TC-D1~D5 | | | |
| TC-E1~E3 | | | |
| TC-F1~F4 | | | |
| TC-G1~G3 | | | |
| TC-H1~H3 | | | |
