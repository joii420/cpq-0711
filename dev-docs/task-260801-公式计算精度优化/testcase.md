# 测试用例 — 公式计算精度优化（task-0801）

> 依据：`需求说明.md`（§4.3 计算规则 / §8 验收标准 AC-1~AC-23 / §11 十一项澄清 / §9.2 风险表）、
> `api.md`（§1 精度契约 / §5.2 黄金用例 G-1~G-14 / §6 不变量 7 条）、`backtask.md`、`fronttask.md`。
> 本文档仅做**测试用例设计**，不执行、不改产品代码。前后端开发完成后由测试工程师按本文档执行。
> 定稿日期：2026-08-01（含技术总监补充的三条现网数据事实，见 §0.3）。
> **2026-08-01 修正记录**（技术总监审出 4 处问题，均已改）：
> 1. §0.3 事实三 / §5.5 —— AC-10 样本单 `total_amount` 恒为 `0.0000`，原判据空转，改为以 `snapshot_rows`/`row_data` JSONB 逐字节 diff 为主判据；
> 2. §5.2 PREC-AC15 —— "四处逐字节相同"被误放宽成"数值相等"会掩盖导出格式化未统一的真缺陷，改回两级判定；
> 3. §7 新增 EDGE-12b —— 补一条"大金额 payload 不应出现浮点噪声尾巴"（与 EDGE-12"取数列不被截断"成对，两个方向都要测）；
> 4. §0.2 —— 原"组合产品 v1.10 已核实可跑通"是仅读 spec 源码、未查库/未实跑得出的**错误结论**，已重新查库更正为真实可用的罗克韦尔模板1/2/3 + 真实物料 fixture。

---

## 0. 测试基础信息

### 0.1 环境

| 项 | 值 |
|---|---|
| 前端 | `http://localhost:5174`（curl 加 `--noproxy '*'`） |
| 后端 | `http://localhost:8081`（业务端点期望 401 表示存活；`/q/health` 不是探针） |
| dev 库 | `10.177.152.12:5432/cpq_db_0724`，`postgres/joii5231` |
| grep | 本环境 `grep` = ugrep 别名会静默返空，统一用 `/usr/bin/grep -a` |
| 后端单测库 | `test` profile → `10.177.152.12:5432/cpq_db`（**与 dev 库不同**，SQL 核对 DB 落库值时注意别查错库：UI/API 验证查 `cpq_db_0724`，`mvnw test` 断言查的是它自己连的 `cpq_db`） |

### 0.2 测试数据 fixture（**2026-08-01 二次核实修正**：原版本"组合产品 v1.10 已核实可跑通"的说法有误，是基于读 spec 源码推断、未实际查库/未实跑，已被技术总监指出并核实纠正；下表为重新查库后的真实数据）

| 用途 | fixture | 备注 |
|---|---|---|
| ❌ 已证伪，不要再用 | `e2e/quote-manual-row.spec.ts` 硬编码的报价模板搜索 `v1.10` 选中「组合产品 v1.10」 | **当前 dev 库不存在名为"组合产品"的模板**（`SELECT id FROM template WHERE name ILIKE '%组合产品%'` 零命中；`template` 表全库仅 7 条：罗克韦尔模板1/2/3 各若干版本 + 核价模板1，均 7/27~7/28 建）。这条是我在上一版文档里的**错误结论**——当时只读了 spec 源码就下结论"可跑通"，没有实际查库或实跑，属于流程失误，已改正 |
| ❌ 同样已证伪 | `quotation-flow.spec.ts` 硬编码的「苏州西门子」客户 + 「报价模板0608」 | 客户 `SELECT id FROM customer WHERE name ILIKE '%西门子%'` 零命中；模板 `报价模板0608` 同样零命中。这是**环境夹具漂移**，不是本任务引入的回归 |
| ✅ **新建单通用场景（重新核实，真实可用）** | 客户「罗克韦尔」（`customer.id = f6d10ef0-04cc-45f3-829c-568c8cce3adf`，`product_category_id = 2c4b8ed8-59ce-4125-b7f7-86ab59ca4bc3` 即「默认分类」）+ 报价模板「罗克韦尔模板1」（`id=70f1b149-b0d9-4cb1-9245-6c3cee1bc3af`, v1.0, PUBLISHED, `template_kind=QUOTATION`, 绑 6 个组件：产品/BOM/材料成本/外购件成本/加工费/产品小计1，**5 个 NORMAL Tab + 1 SUBTOTAL**）；也可用「罗克韦尔模板2」（`9e2e6ef3-...`, v1.0）或「罗克韦尔模板3」（`7fd29ac2-...` v1.0 / `1badebec-...` v1.1 / `0317efe5-...` v1.2） | 三个模板 `customer_id`/`category_id` 均指向罗克韦尔/默认分类，`status=PUBLISHED` 已核实 |
| ✅ **产品/物料 fixture（从真实历史数据反查，非猜测）** | `part_no = S-3120014539`（`customer_part_no = PN0507945`，`product_name_snapshot = "主料1"`） | 通过 `SELECT DISTINCT product_part_no_snapshot, customer_part_no, product_name_snapshot, template_id FROM quotation_line_item li JOIN quotation q ... WHERE customer='罗克韦尔'` 反查得到：**现网全部 35 条罗克韦尔行项目都用的这一个物料**，且跨罗克韦尔模板1/2/3（v1.0/v1.1/v1.2）都用过，说明该物料+这几个模板的组合在当前环境是被验证过能正常走通配置的（但**具体在新建向导里怎么"添加"这个物料尚未实测** —— 现网数据不代表数据是通过本 UI 向导录入的，也可能是导入或旧版本流程留下的，需要 §「E2E fixture 可行性探明」实测确认，见下方独立小节） |
| 8 位小数取数列真实样本 | `tooling_cost.tooling_unit_price = 0.01333333`，`material_no ∈ {S-3120014539, S-3120018220}` | 已 SQL 核实存在，第 7、8 位小数均非零（`0.01500000` 那几条第 7/8 位是 0，不适合用来证伪压位，别选错行）；**注意 `material_no=S-3120014539` 与上面产品 fixture 的 part_no 是同一个料号**，意味着 FUNC-08a 完全可以复用同一张新建单验证 |
| 已提交 / 已冻结存量单（AC-10 用） | `QT-20260726-0016`（APPROVED，行项目 `template_id=1badebec-...` 即罗克韦尔模板3 v1.1）、`QT-20260726-0018`（SUBMITTED，同模板） | 当前库**仅有这 2 张**非 DRAFT 单，客户均为「罗克韦尔」，且都用的罗克韦尔模板3 v1.1 + 上述同一物料。详见 §0.3 事实三 |

> **本次教训记录（写给自己也写给后续执行者）**：上一版文档"已核实可跑通"这句话是从读 `quote-manual-row.spec.ts` 源码文件头注释 + 代码逻辑推断出来的，**没有做 SQL 核对，也没有实际跑一遍**。这是明确的流程失误——判断"能不能跑通"这种强断言，必须要么查库验证数据存在，要么实际执行看结果，不能只读代码就下结论（代码写的是"预期路径"，不代表数据仍然匹配）。已在 §「E2E fixture 可行性探明」小节改为实测。

### 0.3 技术总监核实的三条现网数据事实（**必须显式处理，不能假装没这回事**）

**事实一：亿级金额场景在现网数据中完全不存在**
```sql
SELECT max(annual_volume), avg(annual_volume) FROM quotation_line_item;  -- max=1, avg=1
```
AC-14（亿级金额精度）**没有可直接复用的存量数据**，必须造数据。处理方式见 §5.3 PREC-AC14-b（含具体造数步骤）。

**事实二：取数列的真实最高精度分布，12 位档在现网无一条有"第 7~12 位非零"的行**
```sql
-- production_energy.unit_price 声明 scale=12，但：
SELECT unit_price FROM production_energy WHERE unit_price <> round(unit_price,6);  -- 零命中
-- tooling_cost.tooling_unit_price 声明 scale=8，且真的有非零样本：
SELECT tooling_unit_price FROM tooling_cost WHERE tooling_unit_price <> round(tooling_unit_price,6);
--   0.01333333 | S-3120014539   0.01333333 | S-3120018220   ← 仅这两行是有效样本
```
即：8 位档有真实可测样本（`0.01333333`），**12 位档现网数据全是"看起来 12 位、实际第 7 位起全 0"的假样本**，无法用来证伪"12 位取数列被压到 6 位"这类回归。处理方式见 §5.4 FUNC-08b（区分真实数据用例与需要构造/退化到单测的用例，明确标注，不合并成一条假装覆盖）。

同时注意：前端 `PAYLOAD_NORMALIZE_SCALE` 的口径技术总监已改口（`fronttask.md` 原写"10 位小数"，现改为"**15 位有效数字**"，原因正是"10 位小数会压坏 12 位取数列"）。**执行测试前先确认代码里实际实现的是哪个口径**，若前端仍是旧的"10 位小数"版本，直接判 FUNC-08b 不通过。

**事实三：已提交 / 已冻结单只有 2 张，且都是当前 sprint 内新产生的（非"老"数据）**
```sql
SELECT quotation_number, status FROM quotation WHERE status IN ('SUBMITTED','APPROVED');
--  QT-20260726-0016 | APPROVED
--  QT-20260726-0018 | SUBMITTED
```
样本量极小（AC-10 理想情况下应该拿"改动前很久就冻结、从未被本次改动前的任何代码路径碰过"的单，但库里没有更老的）。处理方式见 §5.5 FUNC-10（**前置动作：改动前采集基线**，给出具体 SQL 快照命令；如合并后发现这 2 张单在本任务开发期间被其它并发会话动过，须在测试报告里如实注明"样本不足以证明零回归，仅证明改动后到执行测试期间未被静默重算"）。

**⚠️ 补充事实（技术总监审出，2026-08-01）：这 2 张单的 `quotation.total_amount` 都是 `0.0000`**
```sql
QT-20260726-0018 | SUBMITTED | total_amount = 0.0000
QT-20260726-0016 | APPROVED  | total_amount = 0.0000
```
**这意味着"比对改动前后 `total_amount` 是否相等"这条判据是空转的**——`0 = 0` 永远成立，验不出任何精度相关的东西（不管改动对不对，这条判据都会显示"通过"）。行级 `subtotal`（`26.0000`/`14.0000`）不为 0，是目前唯一有判别力的数字。§5.5 已按此重新设计判据优先级，不能只看整单合计。

---

## 1. 用例分组与执行顺序

| 顺序 | 组 | 目的 | Gate 逻辑 |
|---|---|---|---|
| 1 | **冒烟 SMK**（6 条） | 快速判断"能不能测下去" | 任一条 FAIL → 停止，打回开发，不再往下执行 |
| 2 | **功能 FUNC**（AC-1~10，20 条） | 逐条验收标准过一遍 | 允许部分 FAIL，记录后继续 |
| 3 | **精度 PREC**（11 求值点 + G-1~14 + AC-11~16，31 条） | 本需求的核心 —— 精度正确性 | 与 FUNC 同级，是本任务验收重点 |
| 4 | **回归 REG**（AC-17~23，7 条） | 确认没把别的功能改坏 | E2E/单测项必须全绿才能进入合并流程 |
| 5 | **边界 EDGE**（21 条，2026-08-01 补充 EDGE-12b） | 容易被漏的细节点，PR 评审重点核对区 | 全部跑完才能出交付报告 |

**为什么这个顺序**：SMK 先行是常规冒烟纪律；FUNC 在 PREC 之前是因为 FUNC 用真实 UI 操作跑通一遍能顺带发现崩溃类问题，比直接扎进黄金用例更快定位大方向问题；PREC 是本任务价值所在放在验收主体位置；REG 因为跑 E2E 耗时最长放后面且需要 FUNC/PREC 都过了再跑才有意义（否则 E2E 失败不知道是精度改动直接导致还是别的原因）；EDGE 放最后是因为很多 EDGE 用例依赖 PREC 组已经造好的数据（如 AC-14 造的 20 行大数据，EDGE-13 导出超 15 位有效数字直接复用）。

---

## 2. 冒烟组（SMK）

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| SMK-01 | AC-19/20/22（前置门槛） | worktree 已合并改动 | 后端 `cd cpq-backend && ./mvnw -q compile`；前端 `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` | 后端编译 0 错误；前端 TS 0 错误 | 命令输出 | 自动化（CI 命令） |
| SMK-02 | R-6 | 后端/前端 dev server 已重启到含本次改动的代码 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components`；`curl ... http://localhost:5174/` | 后端 401，前端 200 | curl 输出 | 自动化 |
| SMK-03 | AC-1 | 无 | 按 §0.2 fixture 新建一张报价单，Step2 任填一个数值字段触发公式计算 | 卡片渲染无崩溃、无红屏、计算列显示数值（不是 `NaN`/空白/报错） | UI 观察 | 人工 |
| SMK-04 | AC-4 | 同上，SMK-03 已完成 | 点保存草稿 | 无 500、无报错提示，页面停留可继续编辑 | UI 观察 | 人工 |
| SMK-05 | AC-5 | 同上 | 打开报价单列表页 | 列表正常渲染，刚建的单出现在列表中，总金额列非报错占位 | UI 观察 | 人工 |
| SMK-06 | AC-7 | 需要一张已有核价单（可用现有测试核价单） | 打开核价单明细页 | 正常渲染，无崩溃 | UI 观察 | 人工 |

---

## 3. 功能验收组（FUNC，AC-1~10）

**前置**：以下用例除特别注明外，均基于 §0.2 fixture（罗克韦尔 + 罗克韦尔模板1，产品用「从已有产品添加」加入 `S-3120014539`/`PN0507945`，**不是**已证伪的"组合产品 v1.10"）新建的同一张报价单 `Q-FUNC-01`（测试执行时记录实际生成的 `quotation_number`，供后续复用；若执行时 §「E2E fixture 可行性探明」已给出更新的可用 fixture，以那份结论为准）。

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| FUNC-01 | AC-1 | `Q-FUNC-01` 已建，Step2 任一页签有 FORMULA 字段 | 填入触发非整除结果的数值（如某数量列填 3，单价列填 1，公式产出 0.333333…） | 计算列单元格显示"至多 6 位、去尾零"，且与用计算器手工按 §4.3 规则算出的值一致 | UI 观察 + 手工计算比对 | 人工 |
| FUNC-02a | AC-2 | 同上 | 观察该页签底部"列小计"行 | 列小计为 6 位口径（不是旧的 4 位截断） | UI 观察 | 人工 |
| FUNC-02b | AC-2 | 同上 | 观察该页签"本页签金额合计" | 同为 6 位口径 | UI 观察 | 人工 |
| FUNC-02c | AC-2 | 同上 | 观察卡片底部"产品小计" | 同为 6 位口径（去尾零，不是旧的固定 2 位） | UI 观察 | 人工 |
| FUNC-03a | AC-3 | 进入 Step3 优惠策略页，行折扣率填一个非整数比例（如 12.3%） | 观察"行折扣"列 | 6 位口径 | UI 观察 | 人工 |
| FUNC-03b | AC-3 | 同上 | 观察"行合计"列 | 6 位口径 | UI 观察 | 人工 |
| FUNC-03c | AC-3 | 同上 | 观察页面底部"整单合计" | 6 位口径 | UI 观察 | 人工 |
| FUNC-04 | AC-4 | `Q-FUNC-01` 已保存草稿 | 执行 SQL：`SELECT total_amount, original_amount, tax_amount FROM quotation WHERE quotation_number='<Q-FUNC-01>'; SELECT subtotal, discount_base_amount, line_unit_price, line_final_price, line_discount_amount, line_total_amount FROM quotation_line_item WHERE quotation_id=(SELECT id FROM quotation WHERE quotation_number='<Q-FUNC-01>'); SELECT subtotal FROM quotation_line_component_data WHERE quotation_line_item_id IN (...);` | 12 个金额列的列定义均为 `numeric(20,6)`，且非零值确实带到第 6 位小数（不是尾部恒为 `.000000`，除非算出来就是整数） | SQL 直查 | 人工（可脚本化） |
| FUNC-05 | AC-5 / §11.6 | 列表页已能看到 `Q-FUNC-01`，且该单详情页已打开 | 对比列表「总金额」列与详情页「整单合计」显示的**位数口径**（去尾零后最多几位小数） | 位数口径一致（比如都是"至多 6 位去尾零"）；**不要求数值相等**——草稿态与提交态本来就是两套算法（§11.4/§11.6 已拍板不改），若两处数字本身不同属于**已知设计差异**，不判 FAIL，只判"位数口径"是否统一 | 同一张单两处对比 | 人工 |
| FUNC-06a | AC-6 | `Q-FUNC-01` 已有数据 | 导出 Excel（`GET .../export/excel` 或 `export-excel-view`） | 打开导出文件，金额列为 6 位口径（含 POI 单元格数字格式串） | 打开导出文件核对 | 人工 |
| FUNC-06b | AC-6 | 同上 | 导出 PDF（`GET .../export/pdf`） | 金额 6 位口径 | 打开导出文件核对 | 人工 |
| FUNC-06c | AC-6（补充） | 同上 | `GET .../export/html`（邮件正文用的那份） | 金额 6 位口径 | 打开文件核对 | 人工 |
| FUNC-07a | AC-7 | 需一张核价单，明细页有非整数金额 | 打开核价单明细 | 6 位口径 | UI 观察 | 人工 |
| FUNC-07b | AC-7 | 同上 | 打开核价汇总页 | 6 位口径 | UI 观察 | 人工 |
| FUNC-07c | AC-7 | 需已进入财务核价工作台流程的单（若无现成数据，先跑通一遍提交→核价审批流程） | 打开财务核价工作台 | 6 位口径 | UI 观察 | 人工 |
| FUNC-07d | AC-7 | 核价表（`CostingSheetService` 渲染页） | 打开含公式列的核价表 | 计算列 6 位口径 | UI 观察 | 人工 |
| FUNC-08a | AC-8 | `Q-FUNC-01` 的产品 BOM 若引用了 `material_no ∈ {S-3120014539, S-3120018220}`，或先用 SQL 确认该产品实际引用的物料列表再挑一个含高精度取数列的字段 | 在卡片对应"工装单价"字段观察显示值；保存草稿后重新打开 | 显示值 = `0.01333333`（8 位，未被压到 6 位）；重新打开后仍是 8 位 | UI 观察 + 与库值 `SELECT tooling_unit_price FROM tooling_cost WHERE material_no=...` 比对 | 人工 |
| FUNC-08b | AC-8（12 位档，见 §0.3 事实二） | 现网无真实 12 位非零样本 | **两选一**：① 若测试环境允许，临时 `UPDATE production_energy SET unit_price = 0.070000123456 WHERE ...`（仅测试库，测试完成后可保留，因为反正是测试数据）构造一条真实样本，走完整链路验证；② 若不便改数据，改为后端/前端单测层面验证（构造 12 位输入直接调用相关 handler/组件渲染函数） | 走完整条"渲染 → 保存草稿 → 重新打开"链路后，该字段仍是 12 位小数（**这条专门用来抓"payload 按小数位数 10 位截断"的回归**——若发现只剩 10 位，直接判 FAIL） | UI 观察 + SQL 比对 / 单测断言 | ⚠️ **标注为"需人工构造数据或退化为单测"，不是开箱可测**（原因见 §0.3 事实二） |
| FUNC-09 | AC-9 | `Q-FUNC-01` Step1/Step3 有折扣率、税率字段 | 观察折扣率、税率显示 | 仍为 2 位（不受本次改动影响） | UI 观察 | 人工 |
| FUNC-10 | AC-10 | 见 §5.5（独立小节，含前置基线采集步骤） | — | — | — | 见 §5.5 |

---

## 4. 精度正确性组（PREC）—— 11 个求值点

**目的**：R-1 风险指出漏改一个求值点是"静默失败"（不报错、不崩溃，只是某个页签的数还是老精度），所以必须逐点单独覆盖，不能用一条总的"随便填个公式看看"用例代替。

| 编号 | 求值点 | 追溯 | 触达方式 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| PREC-EP-01 | 后端 `FormulaCalculator.ArithParser`（`:1961-2030`，主引擎） | R-1 / backtask B2 | 后端单测直接调 `evaluate("0.1+0.2")` | 结果 = `0.3`（`BigDecimal`，非 `0.30000000000000004`） | 单测 | **自动化（后端 JUnit：`FormulaCalculatorTest`）** |
| PREC-EP-02 | 后端 `FormulaCalculationService`（`:25`建引擎/`:223`求值，报价卡片行公式） | backtask B3 #1 | 单测 + 组件字段配置一条 `FORMULA` 公式为 `0.1+0.2` 后在卡片实际渲染观察 | JEXL 求值 = `0.3` | 单测 + UI 观察 | 自动化（后端 JUnit）+ 人工抽样 1 次 |
| PREC-EP-03 | 后端 `FormulaEngine`（`:58/65`建引擎，`:113/289`求值，通用引擎+函数库+`$view`） | backtask B3 #2 | 单测 + 用引用 `$view.col` 的 Excel 视图列公式验证求值不出浮点尾差 | 结果精确，`$view` 路由仍正常（不变量 4） | 单测 + UI | 自动化（`FormulaEngineTest`）+ 人工抽样 |
| PREC-EP-04 | 后端 `TemplateFormulaService`（`:104` `rowJexl`，模板 Excel 视图公式） | backtask B3 #3 | 单测 + 模板管理里配置一条 Excel 视图行级公式 `0.1+0.2` | 结果 = `0.3` | 单测 + UI | 自动化 + 人工抽样 |
| PREC-EP-05 | 后端 `ExcelViewService`（`:625-627`，报价单 Excel 视图） | backtask B3 #4 | 单测 + 打开报价单 Excel 视图页签，观察含小数运算的列 | 结果精确 | 单测 + UI | 自动化 + 人工抽样 |
| PREC-EP-06 | 后端 `TabJoinPlanEvaluator`（`:76-77/:200`，页签连表公式） | backtask B3 #5 | 单测 + **需要一个配置了"页签连表公式"的模板**（若当前 fixture 模板未配置，先确认哪个模板有，或退化为纯单测） | 结果精确 | 单测（必做）+ UI（若有可用模板则做，否则标注跳过并说明原因） | 自动化（必做）+ 人工（条件允许） |
| PREC-EP-07 | 后端 `CostingSheetService`（`:275-276`，核价表公式） | backtask B3 #6 | 单测 + 核价表页签含公式列 | 结果精确 | 单测 + UI | 自动化 + 人工抽样 |
| PREC-EP-08 | 前端 `precision.ts` 的 `evaluateArithmetic`（递归下降解析器本体） | R-1 / fronttask F1 | 前端单测直接调用 | `evaluateArithmetic("0.1+0.2")` → `Decimal("0.3")` | 单测 | **自动化（前端 vitest：`precision.test.ts`）** |
| PREC-EP-09 | 前端 `formulaEngine.ts:587-589`（主公式求值 `evaluateExpression` 调用点） | fronttask F2 #1 | 单测 + UI：字段类型 `FORMULA` 的卡片单元格填 `0.1+0.2` 等价场景 | 结果 = `0.3`，且不再有 `toDecimalPlaces(4)` 截断残留 | 单测 + UI | 自动化（`formulaEngine.test.ts`）+ 人工抽样 |
| PREC-EP-10 | 前端 `formulaEngine.ts:703-705`（LIST_FORMULA 字符串公式求值调用点） | fronttask F2 #2 | 单测 + UI：字段类型 `LIST_FORMULA` 的场景 | 结果精确；非法表达式仍返回 `null`（不崩溃） | 单测 + UI | 自动化 + 人工抽样 |
| PREC-EP-11 | 前端 `pages/quotation/ExcelView.tsx:65`（Excel 视图公式求值调用点） | fronttask F2 #3 | UI：核价侧 Excel 视图页签（`ExcelView.tsx` 主要服务核价侧渲染） | 结果精确 | UI 观察（该文件目前没有独立单测文件，若开发补了单测则改走自动化） | **人工**（除非确认已有专属单测文件） |

> **PREC-EP-06 / EP-11 的现实处理**：这两点依赖"当前模板是否配置了对应类型的公式"，测试执行前必须先用 SQL/组件管理页确认可用模板，若确认没有，**必须由测试工程师在测试库里配置一个最小可用的示例**（页签连表公式 / Excel 视图公式各配一条 `0.1+0.2` 级别的公式），而不是直接跳过——跳过 = 这个求值点全程零验证，正是 R-1 警告的"静默失败"发生地。

---

## 5. 精度正确性组（PREC）—— 黄金用例 G-1~G-14

**规则**：前后端**各自实现**、**共用同一份期望值**（`api.md` §5.2），任一端不符即缺陷。每条用例默认要求前后端**都**跑一遍。

| 编号 | 追溯 | 表达式/场景 | 期望结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|
| PREC-G01 | G-1 / AC-11 | `0.1 + 0.2` | `0.3` | 后端 `PrecisionPolicyTest`/`FormulaCalculatorTest` + 前端 `precision.test.ts` | 自动化（双端单测） |
| PREC-G02 | G-2 / AC-12 | `1.005` 规整到 2 位 | `1.01`（非 double 的 `1.00`） | 双端单测 | 自动化 |
| PREC-G03 | G-3 | `1 / 3` | 显示 `0.333333`（12 位中间精度算，规整 6 位显示） | 双端单测 | 自动化 |
| PREC-G04 | G-4 | `10 / 3 * 3` | `10`（若中间截到 6 位会得 `9.999999`，这条专门抓"中间截断"回归） | 双端单测 | 自动化 |
| PREC-G05 | G-5 | `0.0000004` 规整 | `0`（6 位以下归零） | 双端单测 | 自动化 |
| PREC-G06 | G-6 | `0.0000005` 规整 | `0.000001`（HALF_UP 向上进位） | 双端单测 | 自动化 |
| PREC-G07 | G-7 | `2.5 × 0.4` | `1`（去尾零，不显示 `1.000000`） | 双端单测 + UI 抽样（找一个能配出该场景的公式字段观察显示） | 自动化 + 人工抽样 |
| PREC-G08 | G-8 / 不变量 5 | 空值 / null 参与运算 | 按 `0` 参与，结果非 `null` | 双端单测 | 自动化 |
| PREC-G09 | G-9 / 不变量 5 | 除以 0 | 返回 `0`，不抛异常 | 双端单测 + UI（配一条会除零的公式，观察卡片不崩溃且显示 0） | 自动化 + 人工抽样 |
| PREC-G10 | G-10 / AC-13 / AC-14 | 6 层嵌套：元素行→列小计→页签合计→产品小计→行合计(×500000)→整单总额(20 行) | 与一次性十进制精确计算结果**逐字节相同** | 双端单测（核心用例，必须有） | **自动化（算法级）**，端到端另见 §5.3 PREC-AC14-b |
| PREC-G11 | G-11 / AC-14 | 单价 `123.456789` × 年用量 `800000` | `98765431.2` | 双端单测 | 自动化 |
| PREC-G12 | G-12 | 一元负号 `-(2+3)*2` | `-10` | 双端单测 | 自动化 |
| PREC-G13 | G-13 | 运算符优先级 `2+3*4` | `14` | 双端单测 | 自动化 |
| PREC-G14 | G-14 | 全角运算符 `2×3÷4` | `1.5`（`×→*`、`÷→/` 转换不丢失） | 双端单测 + UI 抽样（若组件管理支持全角输入） | 自动化 + 人工抽样（若可行） |

---

## 5.1 精度正确性组（PREC）—— AC-11/12/16 收尾 + 审计复核

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| PREC-AC11 | AC-11 | PREC-EP-01~11 已全部执行 | 汇总 11 个求值点的 `0.1+0.2` 断言结果 | 11/11 全部 = `0.3`，无一处仍是 double 尾差 | 单测汇总表 | 自动化（结果汇总，人工过一遍勾选表） |
| PREC-AC12 | AC-12 | — | 同 PREC-G02 | — | — | 见 G-2 |
| PREC-AC16 | AC-16 | — | 后端断言 `PrecisionPolicy.DISPLAY_SCALE == 6`；前端断言 `precision.ts` 的 `DISPLAY_SCALE === 6`；再断言 `NumberFormatUtil.COMPUTED_FALLBACK`、`ExcelViewService.COMPUTED_FALLBACK_DECIMALS`、`formatNumber.ts` 的 `COMPUTED_FALLBACK` 均引用同一常量而非各自持有字面量 | 5 处全部 = 6，且是"引用"关系（改一处全改，不是重复写死） | 单测断言 + 代码走查（引用关系用 `/usr/bin/grep -n "COMPUTED_FALLBACK\s*=" ` 确认右侧不是字面量 `6` 而是变量引用） | 自动化（单测）+ 人工（引用关系走查） |
| PREC-AUDIT-01 | R-2 / backtask B4-2 | 开发交付说明附带三条审计命令输出（`doubleValue()` / `setScale(4` / `Sum\|Total\|Amount\|subtotal)\s*\+=`） | 逐条核对开发给出的"改了/不属本次范围"结论：抽样至少 30% 命中点，回到源码确认结论是否成立 | 抽样命中点结论均正确；若发现有命中点被错误标注"不属范围"但实际是链路二残留，判 **FAIL** 并列为 Bug | 人工代码走查 | 人工（不可自动化，需要业务语义判断） |

---

## 5.2 精度正确性组（PREC）—— AC-15 前后端一致性

> **判定口径修正（2026-08-01）**：`api.md` §5.3 原文要求"四处**逐字节相同**"，上一版文档把它放宽成了"数值相等即可"（理由是"`4.5` 和 `4.500000` 数值相同"），**这个放宽会掩盖真缺陷**——按 `backtask.md` Task B6 的设计，导出侧本来就该统一成"至多 6 位去尾零"，即导出**本应该**输出 `4.5` 而不是 `4.500000`；如果导出仍固定位数输出 `4.500000`，说明 B6 没改对，是一个真实缺陷，但按"数值相等即可"的旧判据会被误判 PASS。**改为两级判定，不能再合并成一句话**：

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| PREC-AC15 | AC-15 / api.md §5.3 | `Q-FUNC-01` 已保存草稿，含至少一个非整数金额（如 `4.5`，避免用整数或恰好 6 位的值，那样测不出格式差异） | 同时记录四处：① 前端 Step2/详情页显示值 ② `GET /api/cpq/quotations/{id}` 响应对应字段值 ③ `SELECT` 对应 DB 列值 ④ 导出 Excel/PDF 对应单元格值 | **两级判定，缺一不可**：<br>**第一级（硬性 FAIL）**：四处**数值不相等** → 直接判 FAIL（如详情页显示 `4.5` 但导出成了 `4.6`，是精度计算错误）；<br>**第二级（同样判 FAIL，但性质不同，需单独登记为缺陷）**：四处**数值相等，但字符串形式不符合"至多 6 位去尾零"**——即出现 `4.500000`（固定 6 位补零）、`4.50`（固定 2 位）这类不是"去尾零"的表示形式 → 说明该处没有走统一格式化口径（`formatNumber`/`NumberFormatUtil`/导出 helper），也判 FAIL，但要在缺陷描述里注明"数值正确，格式化口径未统一"，与第一级"算错了"的缺陷分开登记，不要混在一条里 | 四处比对（脚本辅助：写一个小脚本拉 API JSON + 读导出文件数值 + 查 DB，人工核对前端显示；**同时人工核对导出文件里该数字的原始字符串**，不要只看数值） | 人工为主（可选：写一次性对账脚本辅助抓取 API/DB/导出值，减少人工誊抄出错） |

---

## 5.3 精度正确性组（PREC）—— AC-14 亿级金额精度（重点：数据缺口处理）

> **技术总监明确要求**：不允许把 AC-14 标成"自动化覆盖"就了事；算法级单测验的是公式，验不了整条落库/API/渲染/导出链路，必须有人工造数 + 四处比对的端到端用例。

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| PREC-AC14-a | AC-14（算法级） | 无 | 后端 `FormulaCalculatorTest`/`PrecisionPolicyTest` 新增：构造 20 个 `123.456789`（或不同单价）× `800000`（或 500000~800000 区间不同值）的行，用 `BigDecimal` 累加，断言与"一次性用 `BigDecimal` 精确计算"的参考值逐位相同；前端 `precision.test.ts` 同款用例 | 断言全部通过，第 6 位小数正确 | 单测 | **自动化（后端 JUnit + 前端 vitest 各一条）** —— 这条只证明"算法本身在该量级下正确"，**不能替代下面 AC-14-b** |
| PREC-AC14-b | AC-14（端到端，**强制，不可用单测替代**） | 见下方"造数步骤" | 见下方 | 前端显示 / `GET` API 响应 / DB 落库 / 导出文件（Excel+PDF）四处的整单合计，第 6 位小数**互相一致**，且与"用 `psql` 对 20 行 `line_unit_price × annual_volume` 做 `numeric` 精确求和"的参考值一致 | 四处比对 + SQL 参考值比对 | **人工（强制）** |

**PREC-AC14-b 造数步骤（具体，可照做）**：

1. 按 §0.2 fixture 新建一张报价单 `Q-PREC-14`（客户罗克韦尔 + 罗克韦尔模板1，**不是**已证伪的"组合产品 v1.10"）。
2. 用**手动新增行**功能（已确认存在，参考 `e2e/quote-manual-row.spec.ts`：driver 页签"+ 添加行"）在 Step2 产品卡片内新增行，若卡片含 `INPUT_NUMBER` 类型的"单价"字段，逐行填 `123.456789`（20 行都填一样即可，不要求互不相同）。
   - 若当前模板产品卡片没有可直接手填数字的 `INPUT_NUMBER` 单价字段（全靠物料公式链路算出），则改为在 Step2 添加 **20 个独立产品行项目**（同一产品重复添加 20 次即可），让"产品小计"取模板自然算出的值（不强求是 `123.456789`，只要有非零小数位即可）。
3. 每个产品行项目的"年用量"字段（`annualVolume`，`Integer` 类型，**前后端均无 max 限制**，已核实）填 `500000`~`800000` 区间的值（20 行可以都填 `800000`）。
4. 保存草稿（若模板允许，提交）。
5. 记录 `quotation_number`，执行：
   ```sql
   SELECT id, quotation_id, line_unit_price, annual_volume, line_total_amount
   FROM quotation_line_item WHERE quotation_id = (SELECT id FROM quotation WHERE quotation_number='<Q-PREC-14>');
   -- 用 psql 自己再算一遍参考值（numeric 类型本身就是精确的，可直接当"金标准"）：
   SELECT SUM(line_unit_price * annual_volume) FROM quotation_line_item WHERE quotation_id = (...);
   SELECT total_amount FROM quotation WHERE quotation_number='<Q-PREC-14>';
   ```
6. 对比：DB 的 `SUM(line_unit_price * annual_volume)` 参考值 vs `quotation.total_amount` 落库值 vs 前端 Step3/Step4 显示的整单合计 vs `GET /api/cpq/quotations/{id}` 响应的 `totalAmount` vs 导出 Excel/PDF 里的合计。
7. **重点检查第 6 位小数**：若参考值第 6 位与任一其它三处不一致，判 **FAIL**。

> **兜底说明**：如果第 2 步发现模板确实没有任何手填单价入口、也无法让产品小计自然产生 6 位小数精度（比如所有物料单价现网数据都是整数），可以退而求其次：先走完 1~4 步用真实产品建好 20 行 + 大年用量，再用一次性 `UPDATE quotation_line_item SET line_unit_price = 123.456789 WHERE ...`（仅测试库，仅用于验证"落库→API→前端渲染→导出"这条**读路径**的精度保真，不代表验证了"计算引擎"本身——计算引擎那部分已由 PREC-AC14-a 单测 + PREC-G10/G11 覆盖）。测试报告里必须注明用了哪种方式，不要含糊带过。

---

## 5.4 补充：FUNC-08b 的执行说明（承接 §3 表格）

已在 §3 FUNC-08b 行给出，此处不重复。核心是：**8 位档（`tooling_cost`）有真实数据可直接测，12 位档（`production_energy`）现网无有效样本，必须人工构造或退化为单测**，两者不能混为一谈地都写"已覆盖"。

---

## 5.5 FUNC-10：AC-10 存量已冻结单不重算（含前置基线采集）

> **判据优先级说明（2026-08-01 修正）**：`quotation.total_amount` 对这两张样本单**恒为 `0.0000`**（改动前后必然都是 `0=0`），**没有判别力，不得作为 FUNC-10 的通过依据**，只能作为"顺带记录一下"的次要项。真正有判别力的判据按优先级排列：
> 1. **（最强）`quotation_line_component_data.snapshot_rows` JSONB 原文逐字节 diff** —— 这是冻结快照的直接物理证据，只要改动前后这段 JSON 文本完全一致，就能直接证明"冻结快照未被任何代码路径改写"，比看几个汇总数字有力得多；
> 2. **（次强）行级 `quotation_line_item.subtotal`**（`0016`=`26.0000`，`0018`=`14.0000`，均非零，有判别力）；
> 3. **（弱，仅供参考）`quotation.total_amount`**（恒为 0，不判定，只记录）。

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| **FUNC-10-baseline** | AC-10 前置动作（**必须在本特性分支合并到共享 dev server 之前执行**） | 当前共享 8081/5174 仍跑 master（未合并本任务代码） | 对 `QT-20260726-0016`（APPROVED）与 `QT-20260726-0018`（SUBMITTED）：① 打开详情页截图（记录整单合计、行小计等所有可见数值）；② 执行并**完整保存**以下 SQL 输出（含 JSONB 原文，不要只存人工摘抄的数字）：<br>`SELECT quotation_number, total_amount, original_amount, tax_amount, updated_at FROM quotation WHERE quotation_number IN ('QT-20260726-0016','QT-20260726-0018');`<br>`SELECT quotation_id, subtotal, discount_base_amount, line_unit_price, line_final_price, line_discount_amount, line_total_amount FROM quotation_line_item WHERE quotation_id IN (SELECT id FROM quotation WHERE quotation_number IN ('QT-20260726-0016','QT-20260726-0018'));`<br>**`SELECT id, quotation_line_item_id, subtotal, snapshot_rows::text, row_data::text FROM quotation_line_component_data WHERE quotation_line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id IN (SELECT id FROM quotation WHERE quotation_number IN ('QT-20260726-0016','QT-20260726-0018'))) ORDER BY id;`**（这条把 JSONB 原文整段导出到文件，用于后续 `diff`）<br>已知基线（2026-08-01 核实）：0016 行 `subtotal=26.0000`；0018 行 `subtotal=14.0000`；两单 `quotation.total_amount` 均为 `0.0000`（**这个 0 不能拿来判定，见上方判据优先级说明**，`line_total_amount` 同样为 `0.0000`，属已知现象非本次范围） | 基线截图 + 三条 SQL 的完整输出已保存到文件（文件名建议 `QT-20260726-0016_baseline.sql.txt` / `QT-20260726-0018_baseline.sql.txt`，JSONB 那条务必整段存文本文件供 `diff` 用，不要截断） | 保存留存 | 人工（**这是整个 AC-10 判定的前提，漏做则 AC-10 无法验证，且过了合并时间点无法补救**） |
| FUNC-10 | AC-10 / §11.10 | 本特性分支已合并、共享 dev server 已重启到新代码；FUNC-10-baseline 已完成 | 重新打开 `QT-20260726-0016` 与 `QT-20260726-0018` 详情页，重新截图；重新执行 FUNC-10-baseline 里的三条 SQL（含 JSONB 原文导出）；执行 `diff QT-20260726-0016_baseline.sql.txt QT-20260726-0016_after.sql.txt`（对 `snapshot_rows`/`row_data` 那部分） | ① **主判据**：`snapshot_rows`/`row_data` 的 JSONB 原文 `diff` **零差异**（逐字节相同，这是"冻结不重算"最直接的证据）；② **次判据**：行级 `subtotal`（`26.0000`/`14.0000`）与基线**数值相等**（允许因 `numeric(18,4)→numeric(20,6)` 放大转换导致小数位补零，如 `26.0000`→`26.000000`，这是预期的不是回归；但如果数值本身变了，如变成 `26.000001` 或别的数，判 **FAIL**）；③ 详情页显示的**数值**与基线数值相等（显示字符串位数变化是预期的，如 `¥26.00`→`¥26`，格式变化不是数值变化，视为 PASS）；④ `total_amount=0.0000` 这条**只记录不判定**；⑤ `updated_at` 理想情况下不变（若变了，说明打开详情页触发了写库，需要进一步排查是否顺手做了重算——这是不变量 7 明确禁止的行为，若发生判 **FAIL**） | JSONB diff（主）+ 截图 + SQL 结果对比基线（次） | 人工 |

---

## 6. 回归组（REG，AC-17~23）

| 编号 | 追溯 | 前置条件 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| REG-01 | AC-17 | 前端改动已完成 | ```cd cpq-frontend && rm -f e2e/screenshots/qf-*.png && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list``` | 全部 test passed；`'加载中' final count = 0`；8 个 Tab 均 `'加载中'=0` | Playwright 输出 + qf-19/qf-21~28 共 9 张截图 | **自动化（E2E）**；⚠️ 若在 UI 选客户步骤就失败（因"苏州西门子"客户不存在），先按 §0.2 的说明确认是**环境夹具漂移**（该脚本硬编码的客户/模板在当前库不存在），不是本任务回归，需要 A/B 对比：在合并前的 master 上跑一次同一个 spec 确认是否同样失败，两边失败现象一致才能排除是本次改动引入 |
| REG-02 | AC-18 | 同上 | `npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list` | passed | Playwright 输出 | 自动化（E2E），同上 A/B 对比纪律 |
| REG-03 | AC-19 | 后端改动已完成，**在 worktree 的 `cpq-backend` 目录跑**（不是主仓，避免测错树报假绿） | `cd <worktree>/cpq-backend && ./mvnw -q test` | 全量单测通过（含新增的 T1~T7 精度用例 + 原有用例） | 命令输出 | 自动化 |
| REG-04 | AC-20 | 前端改动已完成 | `cd cpq-frontend && npm test` | 全量单测通过（含新增 T1~T8 + 原有 `formulaEngine.test.ts`/`formatNumber.test.ts`/`formulaSerialize.test.ts`/`computeFormula.test.ts`/`buildExcelSnapshot.test.ts`/`columnSumsByComp.test.ts`/`lineDiscount.test.ts`/`unitConversion.*.test.ts`） | 命令输出 | 自动化 |
| REG-05 | AC-21 | REG-01/02 已跑，或有可用报价单/核价单/详情页 | 分别打开报价单编辑页、核价单、详情页，检查渲染无回归、无 `—` 占位（非预期的那种）、无"加载中"残留 | 三视图均正常渲染 | 人工 + 截图 | 人工 |
| REG-06 | AC-22 | 前端每个改动的 `.tsx` 文件清单已知（`QuotationStep2.tsx`/`QuotationStep3.tsx`/`ReadonlyProductCard.tsx`/`QuotationList.tsx`/`QuotationWizard.tsx`/`ProductDetailViews.tsx`/`ExcelView.tsx`/`SnapshotTab.tsx`/`CostingSummaryDetailPage.tsx` 等） | `npx tsc --noEmit -p tsconfig.json`；对每个改动文件 `curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<path>` | TS 0 错误；每个文件返回 200 | 命令输出 | 自动化 |
| REG-07 | AC-23 | Flyway 迁移已跑 | `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "SELECT version, success FROM flyway_schema_history WHERE description LIKE '%scale6%';"` | `success = t` | SQL 结果 | 自动化（脚本化 SQL） |

---

## 7. 边界与反例组（EDGE）

| 编号 | 追溯 | 场景 | 操作步骤 | 预期结果 | 判定方式 | 自动化/人工 |
|---|---|---|---|---|---|---|
| EDGE-01 | §1.2 / api.md | 整数结果显示 | 配一条公式产出整数 `5` | 显示 `"5"`，不是 `"5.000000"` | 单测（`formatNumber.test.ts`）+ UI 抽样 | 自动化 + 人工抽样 |
| EDGE-02 | §1.2 | 4 位小数结果 | 公式产出 `0.0774` | 显示 `"0.0774"`，不是 `"0.077400"` | 单测 + UI | 自动化 + 人工抽样 |
| EDGE-03 | G-6 的 UI companion | 舍入进位边界 | 公式产出恰好 `0.0000005` | 显示 `0.000001` | UI 观察（若能配出这个精确场景；否则以 G-6 单测为准） | 人工（若可行）+ 自动化（单测兜底） |
| EDGE-04 | G-5 的 UI companion | 舍入归零边界 | 公式产出 `0.0000004` | 显示 `0` | 同上 | 同上 |
| EDGE-05 | §1.2 / G-8 | null 值处理 | 某取数列字段无值 | 界面显示 `—`；对应 API JSON 字段为 `null`（不是 `0`） | UI + API 响应 | 人工 + 自动化（API 断言可脚本化） |
| EDGE-06 | G-9 / 不变量 5 | 除零 | 配一条会导致除以 0 的公式（如除数字段留空且公式为 `A/B`） | 卡片不崩溃，显示 `0`，控制台无未捕获异常 | UI 观察 + 浏览器 console 检查 | 人工 |
| EDGE-07 | fronttask F1 "非法表达式返回 null" | 非法表达式 | 后端/前端单测直接传入语法错误的表达式字符串 | 返回 `null`，不抛异常导致整页崩溃 | 单测 | 自动化 |
| EDGE-08 | G-14 / 不变量（隐含） | 全角运算符 | 若组件管理支持配置含 `×÷` 的公式，配一条 `2×3÷4` | 结果 `1.5` | 单测 + UI（若可配置） | 自动化 + 人工（条件允许） |
| EDGE-09 | G-12 | 一元负号 | 单测 `-(2+3)*2` | `-10` | 单测 | 自动化 |
| EDGE-10 | G-13 | 运算符优先级 | 单测 `2+3*4` | `14` | 单测 | 自动化 |
| EDGE-11 | api.md §3.3 坑 | JSONB 内计算列与取数列共存，不能一刀切规整 | 找一个页签，其 `row_data`/`snapshot_rows` 同时含计算列（FORMULA）和取数列（BASIC_DATA，如高精度取数字段），保存草稿后直接 `SELECT row_data FROM quotation_line_component_data WHERE ...` 查看 JSONB 原文 | JSONB 里计算列字段值是 6 位口径，取数列字段值保持原精度（不是整个 JSONB 被同一规则处理） | SQL 查 JSONB 原文 + 人工比对每个字段 | 人工 |
| EDGE-12 | fronttask F5 / §0.3 事实二 | 前端 payload 精度约定 —— **防截断方向** | 打开浏览器 DevTools Network 面板，观察 `POST .../draft` 请求体里高精度取数列字段的位数 | 请求体里该字段位数 ≥ 落库位数（若口径是"15 位有效数字"，8~12 位小数的取数值应完整保留在请求体里，不应被截断到 10 位小数或 6 位小数）；后端落库该字段本身属于类别 B（取数列）**不规整**，所以落库后应仍是原精度，不是 6 位 | DevTools 抓包 + SQL 落库值比对 | 人工 |
| EDGE-12b | fronttask F5（`toPrecision(15)` 改法）/ §0.3 事实二（**2026-08-01 新增，与 EDGE-12 成对**） | 前端 payload 精度约定 —— **防噪声方向**（EDGE-12 只验了"不截断"，没验"不引入浮点噪声"，两个方向都要测，缺一个都会漏掉一类回归） | 复用 PREC-AC14-b 造的亿级金额数据（`Q-PREC-14`）。打开 DevTools Network 面板，观察 `POST .../draft` 请求体里整单/行级大金额字段（如 `98765431.2` 量级）的原始文本 | **不应出现**类似 `98765431.19999999552965` 这种浮点噪声尾巴（`(98765431.2).toFixed(14)` 就会暴露这种噪声，这是"按小数位数规整"这一类实现方式的典型症状）；也**不应被截断**丢失有效位。若前端把 payload 规范化实现成了"按固定小数位数 `toFixed(N)`"而不是"按有效数字 `toPrecision(15)`"，这条会在亿级金额场景下失败 | DevTools 抓包，人工核对请求体原始 JSON 文本（不要只看格式化后的显示值，要看网络面板里的原始字符） | 人工，**依赖 PREC-AC14-b 已造好亿级数据**，建议紧跟在 PREC-AC14-b 之后一并执行 |
| EDGE-13 | backtask B6 "已知限制" | 导出 Excel 单元格 15 位有效数字上限 | 复用 PREC-AC14-b 造的亿级数据（9 位整数 + 6 位小数 = 15 位，正好触顶），导出 Excel | 该金额单元格按约定**写字符串**（不是数值单元格），且能在打开的 xlsx 里看到完整数值而非被 Excel 自身截断；PDF/HTML 导出（字符串渲染）不受此限制，正常显示 | 打开导出文件检查单元格类型（数值 vs 文本）+ 数值完整性 | 人工，**依赖 PREC-AC14-b 已完成造数**（若 AC-14-b 因数据原因未能造出真正 15 位有效数字的场景，这条同样受阻，需在报告里注明"依赖项未满足，本条未验证"） |
| EDGE-14 | AC-9 边界值 | 折扣率非典型值 | 折扣率填 `33.33`（2 位边界） | 仍显示 2 位，不受精度改动影响 | UI 观察 | 人工 |
| EDGE-15 | AP-50 / §4.2 | 三视图一致性 | 同一张单同一数值，分别在报价单编辑页（`QuotationStep2`）、核价单、详情页（`ReadonlyProductCard`）查看 | 三处显示**完全一致**（值和位数口径都一致） | 三处截图比对 | 人工 |
| EDGE-16 | backtask B5 "坑"：round 两次≠round 一次 | 二次舍入漂移 | 对同一卡片字段：编辑→失焦（触发一次规整）→再次编辑（哪怕填相同值）→再失焦（触发第二次规整）→保存草稿→重新打开 | 数值不因多次编辑/规整产生累积偏差（前后两次失焦后的值应相等，除非用户确实改了输入） | UI 观察 + 多次操作后数值前后对比 | 人工 |
| EDGE-17 | 数据状态覆盖：空数据 | 新建报价单未添加任何产品卡片 | 新建单，Step2 不加产品，直接保存草稿/查看 Step4 汇总 | 不崩溃，金额显示 `—` 或 `0`，不出现 `NaN`/`undefined`/白屏 | UI 观察 | 人工 |
| EDGE-18 | 数据状态覆盖：新旧数据混合 | 报价单列表同时有新建单（6 位精度）与存量老单（旧精度冻结值） | 打开列表页，同页滚动查看多条记录 | 两类单各自正常显示自己的精度口径，互不干扰、不报错 | UI 观察 | 人工 |
| EDGE-19 | 不变量 4 | `templateId` 透传未被误删 | 调用 `POST /api/cpq/formulas/evaluate`，请求体含引用 `$view.col` 的表达式 + `templateId` | 正常求值返回，不因本次改动而 400/500 | curl + 响应体检查 | 人工（可脚本化为一次性 curl 校验） |
| EDGE-20 | 不变量 1/2 | 端点结构与数值类型不变 | 对比 `api.md §2` 列出的端点在改动前后的响应：字段名是否一致、数值字段是否仍是 JSON number（不是字符串） | 无端点被删除/改名/改方法；无数值字段被改成字符串传输 | 前后响应体 diff（保留改动前一份基线 JSON，改动后重新拉一份 diff 字段名/类型） | 人工（建议对 §0.2 fixture 单跑一次改动前基线快照，供 diff） |

---

## 8. 覆盖率自查表

### 8.1 AC-1 ~ AC-23

| AC | 内容摘要 | 覆盖用例 | 状态 |
|---|---|---|---|
| AC-1 | 卡片计算列 6 位去尾零 | FUNC-01 | ✅ 覆盖 |
| AC-2 | 列小计/页签合计/产品小计 6 位 | FUNC-02a/02b/02c | ✅ 覆盖 |
| AC-3 | Step3 行折扣/行合计/整单合计 6 位 | FUNC-03a/03b/03c | ✅ 覆盖 |
| AC-4 | 保存草稿后 DB 12 列 6 位 | FUNC-04 | ✅ 覆盖 |
| AC-5 | 列表与详情页位数口径一致 | FUNC-05 | ✅ 覆盖（含"数值不同属已知设计"澄清） |
| AC-6 | 导出 Excel/PDF 6 位 | FUNC-06a/06b/06c | ✅ 覆盖 |
| AC-7 | 核价侧四模块 6 位 | FUNC-07a/07b/07c/07d | ✅ 覆盖（07c 依赖财务核价工作台测试数据，若无需先跑通提交审批流） |
| AC-8 | 取数列不被压 | FUNC-08a（8 位，真实数据）/ FUNC-08b（12 位，**需造数据或退化单测**） | ⚠️ 部分覆盖 —— 8 位档完整覆盖，12 位档现网**无真实样本**，见 §0.3 事实二 |
| AC-9 | 折扣率/税率仍 2 位 | FUNC-09、EDGE-14 | ✅ 覆盖 |
| AC-10 | 存量冻结单不重算 | FUNC-10-baseline + FUNC-10 | ⚠️ 样本仅 2 张（`QT-20260726-0016`/`0018`），且这 2 张单产生时间较新（非跨越很久的老数据），**只能证明"改动后到执行测试期间未被重算"**，不能证明"历史上所有已冻结单都不受影响"这一更强命题；已在用例里指名单号 + 要求改动前采集基线。**另：这 2 张单的 `quotation.total_amount` 均为 `0.0000`，对该字段的比对无判别力（0=0 恒成立），已改为以 `snapshot_rows`/`row_data` JSONB 原文逐字节 diff 为主判据、行级 `subtotal`（26/14，非零）为次判据，`total_amount` 仅记录不判定** |
| AC-11 | 十进制精确性（11 求值点） | PREC-EP-01~11、PREC-G01、PREC-AC11 | ✅ 覆盖，但 EP-06/EP-11 的 UI 层验证依赖"是否已有配置好的示例模板"，若无则需测试工程师现场配置最小示例（已在 §4 表格下方注明） |
| AC-12 | 舍入边界 1.005→1.01 | PREC-G02 | ✅ 覆盖 |
| AC-13 | 无中间截断（多层嵌套） | PREC-G10 | ✅ 覆盖（算法级）；UI 层可结合 EDGE-11/EDGE-16 间接观察，无单独必要性——链路一场景在 FUNC-01/02 系列已隐含验证 |
| AC-14 | 亿级金额精度 | PREC-AC14-a（算法级自动化）+ PREC-AC14-b（**端到端，强制人工造数**） | ⚠️ **需人工造数**，见 §5.3 详细造数步骤；这是本任务风险最高的一项，务必按步骤实做，不能只靠 AC-14-a 单测就结案 |
| AC-15 | 前后端一致（四处逐字节相同） | PREC-AC15 | ✅ 覆盖（判定口径为"数值相等"，已在用例中说明为何不强求字符串逐字节） |
| AC-16 | 前后端精度常量同值 | PREC-AC16 | ✅ 覆盖 |
| AC-17 | E2E quotation-flow | REG-01 | ⚠️ 覆盖，但需注意该 spec 硬编码的客户/模板在当前库不存在（环境夹具漂移），执行时必须做 A/B 对比排除误判，见用例说明 |
| AC-18 | E2E composite-product-flow | REG-02 | ✅ 覆盖（同样建议 A/B 对比） |
| AC-19 | 后端全量单测 | REG-03 | ✅ 覆盖 |
| AC-20 | 前端全量单测 | REG-04 | ✅ 覆盖 |
| AC-21 | 三视图渲染无回归 | REG-05、EDGE-15 | ✅ 覆盖 |
| AC-22 | TS 0 错误 + Vite 200 | REG-06 | ✅ 覆盖 |
| AC-23 | Flyway success=t | REG-07 | ✅ 覆盖 |

### 8.2 G-1 ~ G-14

全部覆盖：PREC-G01 ~ PREC-G14，逐条对应 `api.md` §5.2 表格，双端（后端 JUnit + 前端 vitest）各跑一遍。G-7/G-9/G-14 额外要求 UI 抽样，G-10 额外要求端到端造数（见 PREC-AC14-b）。**无覆盖缺口**。

### 8.3 不变量（`api.md` §6，共 **7 条**，非需求方 §"六条"的说法——原文档实际列了 7 条，测试按实际条数覆盖，不因口头数字凑数）

| # | 内容 | 覆盖用例 | 状态 |
|---|---|---|---|
| 1 | 不得改动端点路径/方法/请求体结构/字段名 | EDGE-20 | ✅ 覆盖 |
| 2 | 不得把数值改成字符串传输 | EDGE-20 | ✅ 覆盖 |
| 3 | 不得对 JSONB 内取数列做规整 | EDGE-11 | ✅ 覆盖 |
| 4 | 不得删除 `/formulas/evaluate` 的 `templateId` 透传 | EDGE-19 | ✅ 覆盖 |
| 5 | 不得改变 null/除零/空值参与运算的既有语义 | PREC-G08、PREC-G09、EDGE-05、EDGE-06 | ✅ 覆盖 |
| 6 | 不得改动折扣率/税率字段精度 | FUNC-09、EDGE-14 | ✅ 覆盖 |
| 7 | 存量已冻结快照只读不重算，接口不得"顺手"按新精度改写 | FUNC-10 | ✅ 覆盖（样本量限制同 AC-10） |

### 8.4 明确的覆盖缺口清单（如实列出，不假装覆盖）

| 缺口 | 影响的验收项 | 原因 | 处理建议 |
|---|---|---|---|
| 12 位小数取数列现网无真实非零样本 | AC-8（部分） | `production_energy.unit_price` 声明 scale=12，但现网所有行第 7~12 位小数均为 0，无法用真实数据证伪"12 位被压到 6 位"这类回归 | FUNC-08b 已给出两条路径（构造测试数据 / 退化到单测），执行前必须二选一，不能跳过 |
| 亿级金额（annual_volume 几十万件）现网数据完全不存在 | AC-14（核心风险项） | `quotation_line_item.annual_volume` 现网 max=1 | PREC-AC14-b 已给出具体造数步骤，是本次测试计划里**工作量最大、最不能省略**的一条 |
| 已提交/已冻结单样本仅 2 张，且产生时间较新 | AC-10 / 不变量 7 | 现网仅 `QT-20260726-0016`/`QT-20260726-0018` 为非 DRAFT 状态 | 已指名单号 + 要求改动前采基线；测试结论需诚实注明"样本不足，仅证明短期未被静默重算"，不宣称"验证了历史所有冻结单" |
| EP-06（`TabJoinPlanEvaluator` 页签连表公式）、EP-11（`ExcelView.tsx` Excel 视图公式）的 UI 层验证依赖特定模板配置 | AC-11（11 求值点其中 2 个的 UI 层） | 当前 fixture 模板是否配置了"页签连表公式"/核价侧 Excel 视图公式未逐一确认 | 单测层面强制覆盖（无缺口）；UI 层执行前需先确认可用模板，没有则现场配置最小示例，而不是跳过 |
| EDGE-13（导出超 15 位有效数字写字符串） | 无独立 AC，属 backtask B6 已知限制 | 依赖 PREC-AC14-b 造出真正的 15 位有效数字场景才能触发 | 若 AC-14-b 因数据原因未能精确造出 15 位场景，这条应如实标注"未验证"，不要虚报通过 |
| 前端 `PAYLOAD_NORMALIZE_SCALE` 口径在两份任务文档间不一致（`fronttask.md` 写 10 位小数，技术总监已改为 `toPrecision(15)` 有效数字口径） | AC-8 / AC-14 / EDGE-12 / EDGE-12b | 文档尚未同步更新 | 执行 FUNC-08b/EDGE-12（防截断）/EDGE-12b（防噪声，2026-08-01 新增）前先确认代码实际实现的是哪个口径，若仍是旧的"按小数位数"口径判这些用例 FAIL 并要求前端澄清文档矛盾 |

---

## 9. 交付要求

- 每组用例执行完成后填写"实际结果"与"通过/失败"列（本文档暂不含该列，执行时在副本中补充，或另建执行记录表）；
- 发现 Bug 按 CLAUDE.md 标准格式（现象/预期/复现/环境/影响/建议）单独登记，不混在本用例表格里；
- 全部执行完成后，将 §8 覆盖率自查表的"状态"列更新为实测结果（PASS/FAIL/BLOCKED），BLOCKED 项必须写明阻塞原因；
- 执行前务必先完成 §5.5 FUNC-10-baseline（**必须在合并前做**，错过时机无法补救）。
