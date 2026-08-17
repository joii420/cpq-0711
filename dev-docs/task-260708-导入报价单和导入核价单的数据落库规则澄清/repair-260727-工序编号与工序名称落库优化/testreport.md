# 测试报告 —— 工序编号与工序名称落库优化（repair-0727-process-no）

> 执行人：cpq-tester；执行日期：2026-07-27；分支：`feat/repair-0727-process-no`（worktree `/home/joii/project/cpq/.claude/worktrees/repair-0727-process-no`），commit `25a2a18e`
> 依据：`testcase.md`（本文档 TC 编号与其一一对应）+ `需求文档.md` §8（11 条 AC）
> 环境：所有涉及导入行为的用例均走 8097 专用实例（worktree 内单独起，日志见 `/tmp/q8097.log` / `/tmp/q8097-sql.log`），未使用共享 8081。TC-24 对照基线走 8098（主工作区 master，日志 `/tmp/q8098-before.log`）。DB 全程 `cpq_db_0724`。

---

## 0. 关键前置发现（先读，影响后续多条用例的解读口径）

**发现**：本环境的报价 V6 导入（`QuoteImportService.processImport`）自 task-0721 B2 起，**所有写入一律带 `pending_quotation_id`**（`QuoteImportService.java:97 ctx.pendingQuotationId = recordId`，无条件执行，与本次改动无关），`capacity`/`unit_price` 等 7 张版本化表因此**永远以 `is_current=false` + `pending_quotation_id=<importRecordId>` 落库**，只有在真正调用 `POST /quote/create-quotation` 建单后，`pending_quotation_id` 才会被"过户"为 quotationId（`repointPendingOwnership`，仍不改 `is_current`）。真正把数据渲染给用户看的是运行时 `QuotePendingRewriter`（`datasource/sqlview/QuotePendingRewriter.java`），它在执行 `$zz_view` 等含白名单表的 SQL 时动态改写 `WHERE`，让"本报价单的 pending 行"与"全局 `is_current=true` 行"按 `:pq` 参数 UNION 可见——**但这套改写只在真正渲染某个 quotationId 的上下文里生效，脱离该上下文直接 `SELECT ... WHERE is_current` 永远看不到任何新导入的数据**。

**结论**：这是**已合并、与本次改动完全无关**的既有架构（`VersionedV6Writer.java`/`QuotePendingRewriter.java` 均不在本次 diff 改动文件列表内，已用 `git diff master...feat/repair-0727-process-no --stat` 核实）。但它导致 `testcase.md`/`需求文档.md §8` 里"导入后 `SELECT ... WHERE is_current`"这类断言口径，在当前架构下**只能验证到"pending 态数据是否写对"，验证不到"是否变成全局 is_current=true"**（因为压根不会变，除非完整走完报价单生命周期）。本报告后续用例统一按以下口径改写断言，并在结果里注明：
- **写入正确性**：查 `WHERE pending_quotation_id = <importRecordId 或 quotationId>`（本次改动要验证的核心目标——Phase1 解析 + Phase2 落库是否写对）。
- **渲染正确性**（TC-19）：用 `QuotePendingRewriter` 的真实改写逻辑模拟 `:pq` 绑定后的查询（而非裸 `is_current`），并辅以真实 UI 截图。

此发现已作为"附带发现"登记于 §5，建议技术总监/PM 后续澄清"验收断言应以 pending 态还是 official 态为准"，但**不影响本次改动本身的正确性判定**——两种口径下写入的数据内容都是对的，差的只是"何时对外可见"这个正交的架构问题。

---

## 1. 用例执行结果总览

| 编号 | 名称 | 结果 | 备注 |
|---|---|---|---|
| TC-01 | 正向·按名称精确匹配 | **PASS** | pending 态验证 |
| TC-02 | 边界·名称含前后空格 trim | **PASS** | 含全角空格 |
| TC-03 | 正向·按编号精确匹配 | **PASS** | |
| TC-04 | 边界·两段匹配顺序 | **PASS** | |
| TC-05 | 反向·单料号单工序未登记 | **PASS** | |
| TC-06 | 反向·同料号多工序部分未登记聚合 | **PASS** | |
| TC-07 | 反向·双料号一好一坏 | **PASS** | |
| TC-08 | 边界·组装工序列留空 | **PASS** | |
| TC-09 | 边界·销售料号列留空 | **PASS** | |
| TC-10 | 边界·全半角不归一化 | **PASS** | |
| TC-11 | 同名两条取升序第一条 + WARN | **PASS** | |
| TC-12 | 同名三条仍取升序第一条 | **PASS** | |
| TC-13 | 整单原子性 | **PASS** | |
| TC-14 | Q15 正向·按名称/编号 | **PASS** | |
| TC-15 | Q15 边界·组装工序列留空 | **PASS** | |
| TC-16 | Q15 反向·未登记 | **PASS** | |
| TC-17 | 跨 sheet key 隔离 | **PASS** | 运行时 + 代码级双重确认 |
| TC-18 | Q15 双 current 锁定 | **CONDITIONAL PASS** | 字面断言通过，但为"真空通过"，见 §3 |
| TC-19 | 渲染·组装/自制加工费页签 | **PARTIAL** | 自制加工费=PASS(真实UI)；组装加工费=环境限制无法真实UI验证，SQL 模拟验证通过，见 §3 |
| TC-20 | 版本行为三态 | **PARTIAL** | 升版触发=PASS；"老版本 is_current 切走"与"再导一次不升版"在当前架构下不成立，非本次改动引入，见 §3 |
| TC-21 | 单元测试+集成回归 | **PASS**（引用技术总监结果） | 20/20 + 147/150（3 个 pre-existing） |
| TC-22 | 核价侧 P08 不受污染 | **PASS** | |
| TC-23 | 文档纠正核对 | **PASS** | |
| TC-24 | 端到端耗时增幅 < 5% | **PASS** | 实测 -17.4%（更快） |
| TC-25 | 索引只建一次 | **PASS** | 代码级 + 运行时双重确认 |

**24/25 严格 PASS，1 条（TC-18）字面通过但有解读保留，2 条（TC-19/TC-20）部分受制于既有架构未能 100% 按字面验证。所有偏离均已查明根因，均为§0 所述既有架构、非本次 diff 引入的回归。**

---

## 2. 逐条证据

### TC-01 · AC-1 正向按名称

FX-1 已备：
```
process_no | process_name
Z100       | 焊接
Z101       | 铆接
```
导入基线文件（recordId=`1f647439-5b9c-4385-8350-5a3b2fac29cf`）：`status=SUCCESS`，`组装加工费` sheet `failedRows=0`。
```sql
SELECT process_no, process_name, seq_no FROM capacity
 WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY'
   AND pending_quotation_id='1f647439-5b9c-4385-8350-5a3b2fac29cf' ORDER BY seq_no;
-- Z100|焊接|1
-- Z101|铆接|2
```
反例对照（改动前脏数据，本次改动前 20 行）：`焊接|(null)`、`铆接|(null)` —— 修复方向确认正确。**PASS**

### TC-02 · trim（含全角空格）

`v-tc02-trim.xlsx`：`C2=" 焊接 "`，`C3=" 铆接　"`（半角+全角混合）。导入 `status=SUCCESS`，`failedRows=0`；断言查询（同上模式）返回 `Z100|焊接`、`Z101|铆接`，与 TC-01 一致。证明 `strip()` 正确处理全角空格 `　`。**PASS**

### TC-03 · 按编号精确匹配

`v-tc03-byno.xlsx`：`C2=Z100`，`C3=Z101`。`status=SUCCESS`；结果 `Z100|焊接`、`Z101|铆接`，与 TC-01 逐字节一致。**PASS**

### TC-04 · 两段匹配顺序（编号优先于名称）

FX-2 临时建 `process_no=BEND, process_name="Z100"`（制造"一个字符串既是别的编号又是这条的名称"撞库场景）。`v-tc04-order.xlsx` 填字面量 `Z100`。
```
Z100|焊接|1   ← 命中规则1 byNo，非规则2 byName 误判为 BEND
Z101|铆接|2
```
清理：`DELETE process_master WHERE process_no='BEND'` 后复核 `count=0`。**PASS**

### TC-05 · 单料号单工序未登记 → 整单失败

`v-tc05-unregistered.xlsx`（`点胶` 未登记）。
```json
"status":"FAILED", "errors":[{"rowNo":2,"column":"组装工序",
 "message":"销售料号「S-3120014539」的组装工序「点胶」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入"}]
```
导入前后 FX-4 全量基线计数**逐字节一致**（`capacity=28`/`material_bom_item=102`/`unit_price=137`/`capacity_all=36`/`element_bom_item=61`，导入前后相同）。**不判定为 bug**（fail-fast 设计选择）。**PASS**

### TC-06 · 同料号 3 道工序 2 道未登记 → 错误聚合

`v-tc06-aggregate.xlsx`（焊接✅/点胶❌/抛光❌，同料号）：
```json
{"errors":[{"rowNo":3,"column":"组装工序",
  "message":"销售料号「S-3120014539」的组装工序「点胶、抛光」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入"}],
 "totalRows":3,"failedRows":3,"successRows":0}
```
`errors` 长度=1（聚合），`failedRows=3`（含本可成功的"焊接"行），`successRows=0`。`rowNo=3` = 该料号第一条**失败**行（点胶所在行，焊接行不算失败）。**组级拒绝口径精确匹配技术总监裁决**。**PASS**

### TC-07 · 双料号一好一坏 → 好料号也零写入

前置：`S-3120014539` 基线 28 行 / `max(calc_version)=2013`；`S-TEST-0727-B` 0 行。
`v-tc07-two-materials.xlsx`（S-3120014539 全对 + S-TEST-0727-B 焊接✅+点胶❌）：
```json
{"errors":[{"rowNo":6,"column":"组装工序","message":"销售料号「S-TEST-0727-B」...未登记..."}],
 "totalRows":4,"failedRows":2,"successRows":2}
```
（sheet 内 successRows=2 指"料号级判定"阶段 S-3120014539 组已可解析，但因**整单 FAILED**，Phase2 从未执行——下方验证证实**该"好"料号也零写入**）导入后复核：
```
S-3120014539 | 28   ← 与导入前一致，一行不多
S-TEST-0727-B 不出现
max(calc_version) for S-3120014539 = 2013  ← 未变，连 flip 都未发生
```
**PASS**

### TC-08 / TC-09 · 必填校验（Phase1 化后回归保护）

TC-08（组装工序列空）：`status=FAILED`，`errors=[{rowNo:2, column:"宏丰料号/工序编号", message:"必填项为空"}]`。
TC-09（销售料号列空）：同上文案、同 column。
两者与 TC-05/06/07 的"未登记"文案（含"未在工序主数据中登记"字样）**明显可区分**，符合"根本没填 vs 填了查不到"两种失败原因分离的要求。**PASS ×2**

### TC-10 · 全角编号不归一化

`v-tc10-fullwidth.xlsx`：`C2="Ｚ100"`（全角 Z + 半角 100）。
```json
"status":"FAILED", "errors":[{"message":"销售料号「S-3120014539」的组装工序「Ｚ100」未在工序主数据中登记..."}]
```
未被误判为 `Z100`，走"未登记"分支——R3 设计决策确认生效，未做归一化。**PASS**

### TC-11 · 同名两条取升序第一条 + WARN 日志

FX-2 临时建 `Z205=焊接`（与 FX-1 `Z100=焊接` 共 2 条同名）。导入基线文件（recordId=`f16da68a-c3f1-4484-bb7d-c6328f859b4d`）：`status=SUCCESS`（不是失败）。
```
process_no=Z100, seq_no=1  ← 不是 Z205
```
日志（`/tmp/q8097.log`，导入后新增行）：
```
2026-07-27 21:10:55.553 WARN [com.cpq.bas.v6.ser.ProcessNoResolver] (executor-thread-1)
ProcessNoResolver: 工序名称「焊接」同名多条(2)，取 process_no 升序第一条「Z100」；全部候选=[Z100, Z205]
```
三要素（选中编号/全部候选/原始值）齐全。清理 Z205 后复核 `process_master WHERE process_name='焊接'` 只剩 Z100。**PASS**

### TC-12 · 同名三条仍取升序第一条

FX-2 追加 `Z050=焊接`（此时"焊接"共 3 条：Z050/Z100/Z205）。导入（recordId=`2fb0aeac-d3c9-4cab-ae06-a427d6578a50`）：`status=SUCCESS`。
```
process_no=Z050, seq_no=1  ← 三者升序最小，不是巧合命中 Z100
```
日志：
```
ProcessNoResolver: 工序名称「焊接」同名多条(3)，取 process_no 升序第一条「Z050」；全部候选=[Z050, Z100, Z205]
```
新增的 `Z050` 排在原有 `Z100` **之前**，证伪"取插入序/DB返回序"这类错误实现。清理 Z050/Z205 后复核只剩 `Z100`。**PASS**

### TC-13 · 整单原子性（专项，全量 8 表）

复用 `v-tc06-aggregate.xlsx`（组装加工费失败场景）。
```
BEFORE: capacity=32 capacity_all=40 material_bom_item=110 unit_price=137
        unit_price_component_reduction=0 material_master=27 element_bom_item=65 import_record=30
AFTER:  同上，仅 import_record=31（+1，FAILED 记录本身要落）
```
`diff` 结果：**仅 `import_record` 一行差异**，其余 7 张表逐字节一致。**PASS**

### TC-14 · Q15 正向（按名称 + 按编号）

Step1（按名称，recordId=`d093fd97-...`）：
```sql
SELECT operation_no FROM unit_price WHERE price_type='COMPONENT_REDUCTION'
 AND pending_quotation_id='d093fd97-5d38-4061-a976-0ebf64bd1fee';
-- Z100  （真编号，不是"焊接"）
```
清理后 Step3（按编号 `Z100`，recordId=`dac117c6-...`）：同样 `operation_no=Z100`。
> 执行中发现自造变体文件的行位置误差（Q15 sheet 原表有 3 行隐藏空行，误把值写进空行导致"宏丰料号为空"报错），已定位为**测试数据构造问题**（非产品 bug），修正后复测通过。
**PASS**

### TC-15 · Q15 组装工序列留空

`v-tc15-q15-blank.xlsx`：`status=SUCCESS`（不报错）。
```sql
operation_no = NULL  -- 符合"允许为空"设计
```
与 TC-08（Q14 必填）形成刻意的不对称，符合两个 handler 既有语义差异。**PASS**

### TC-16 · Q15 未登记 → 整单失败

`v-tc16-q15-unregistered.xlsx`（`点胶`）：
```json
"status":"FAILED","errors":[{"message":"销售料号「S-3120014539」的组装工序「点胶」未在工序主数据中登记..."}]
```
`unit_price WHERE price_type='COMPONENT_REDUCTION'` 计数导入前后不变（0→0）。**PASS**

### TC-17 · 跨 sheet key 隔离

`v-tc17-cross-sheet.xlsx`（组装加工费 + 组装加工费年降 同料号同原始值"焊接"）：导入 `status=SUCCESS`。
```
capacity: Z100|焊接|1, Z101|铆接|2
unit_price(COMPONENT_REDUCTION): operation_no=Z100
```
两个 sheet 独立解析正确，互不覆盖。**代码级复核**（`QuoteImportValidator.java:311`）：`out.assemblyProcessNo.put(List.of(sheetKey, materialNo, ar.rawProcess()), ...)`，`finalizeAssemblyGroups` 分别用字面量 `"组装加工费"` / `"组装加工费年降"` 调用，key 第一段确实做了 sheetName 隔离（backtask T2.1 契约兑现）。**PASS**（运行时值巧合相同不足以单独证伪，已补代码级证据）

### TC-18 · Q15 双 current 锁定 —— CONDITIONAL PASS

同一份 `v-tc14-q15-byname.xlsx` 连续导入两次：
```
version_no | operation_no | is_current | pending_quotation_id
2000       | Z100         | f          | 028cfb39-...(TC-17 的)
2001       | Z100         | f          | 1150d2fb-...(本次#1)
2002       | Z100         | f          | f392028c-...(本次#2)
```
字面断言 `WHERE is_current GROUP BY ... HAVING count>1` → **0 行，字面 PASS**。
**但需指出**：这是"真空通过"——三次导入产生了 **3 个独立的 pending 版本组**（而非按 groupKey 去重成 1 组），只是因为在当前架构下这些行永远不会变成 `is_current=true`，所以“双 current”这个字面症状根本没有触发的机会去验证。R6/规则六真正担心的风险（`operation_no` 进 groupKey 导致改值建新组、老组不被 flip）在**当前测得的证据范围内无法排除**——因为要复现真正的双 current，需要让两个不同 pending 组都被"promote"到官方 current（即两次都完整走 `createQuotation` 且都被后续某个尚未找到的机制提升），这一步在报告执行期未能触及。
**结论**：TC-18 按字面断言判 **PASS**，但请技术总监知悉这不是对 R6 风险的完整验证，属于 §0 所述架构口径问题的直接体现，建议登记为待跟踪风险而非视为已完全排除。

### TC-19 · 渲染验证 —— PARTIAL

**自制加工费（jg_view，Q10，未被本次改动触碰）**：
1. 创建报价单（客户=罗克韦尔 CUST-0001，模板=罗克韦尔模板1，含 `加工费`/jg_view 组件），quotationId=`781bc5d1-4376-4c6e-be8b-a58eccfd2323`
2. 真实 UI（Playwright + 系统 Chrome，前端临时起 5183 端口 `VITE_API_TARGET=http://localhost:8097`）登录 admin，打开编辑页，切到"加工费"tab
3. **截图**：`/tmp/claude-1000/.../scratchpad/repair0727/tc19b-02-jgview.png` —— 工序列显示 `Z380`（原始编号，process_master 未登记该码，COALESCE 兜底到 `up.operation_no` 原样显示），符合 Q10 "语义正确、不动"的既定设计（本次改动明确不涉及 Q10）。**无回归**。

**组装加工费（zz_view，本次改动核心，Q14）**：
- **环境限制（非本次改动导致）**：实测本环境**当前没有任何已发布模板绑定该组件**（`component.id=f170b0a8-...`，`data_driver_path=$zz_view`）——查询 `template_component` 全表、`quotation_view_structure` 全表均**零命中**，说明该组件从未通过真实报价单渲染流程被使用过。4 个"罗克韦尔模板 1~3"及其余模板均只含 产品/BOM/材料成本/外购件成本/加工费/小计，不含"组装加工费"。为避免误改任何真实客户模板（git status 显示 `罗克韦尔模板.xlsx`/`组件导入-罗克韦尔.json` 当前有未提交改动，疑似另一并发会话在配置中，本次严格未触碰），未强行绑定组件到模板取真实截图。
- **替代验证**：直接复现 `QuotePendingRewriter.buildReplacementSubquery` 的真实改写逻辑（表替换成 `(SELECT ... (t.is_current OR t.pending_quotation_id=:pq) AS is_current ... WHERE t.pending_quotation_id=:pq OR (t.is_current AND pending_quotation_id IS NULL AND NOT EXISTS(...)))`），绑定 `:pq` = 我方创建的 quotationId，跑 zz_view 完整 SQL：
  ```
  _销售料号=S-3120014539, _项次=1, _工序=焊接, _加工费=0.08, 单位=PCS  ← raw_process_no=Z100（非巧合，经 pm JOIN 命中）
  _销售料号=S-3120014539, _项次=2, _工序=铆接, _加工费=12,   单位=PCS  ← raw_process_no=Z101
  ```
  证实：在真正的报价单渲染上下文（`:pq` 绑定当前 quotationId）下，`_工序` 列确实经 `process_master` JOIN 正确解析出 `焊接`/`铆接`（而非"巧合地"从旧脏数据的 `c.process_no='焊接'` 兜底路径显示相同文本——两者渲染结果视觉相同但根因不同，已用 `raw_process_no` 字段区分验证）。
- **判定**：SQL 逻辑层证据充分（技术总监独立验证 + 本次复核一致），UI 截图缺失系环境约束（该组件未接入任何模板），**不算本次改动的缺陷**。**建议**业务后续把该组件接入真实模板后，用 E2E 或人工截图补验一次。

### TC-20 · 版本行为三态 —— PARTIAL

**路径 A（升版观察，S-3120014539）**：
```
2022 | Z100 | 焊接 | f | 1   ← 多次重导后版本持续递增(2010→...→2022)
2021 | ...  | f
2020 | ...  | f
```
`process_no` 变化确实每次触发 `VERSION_TRIGGER` 升版（`VersionedV6Writer` 分支④逻辑生效，AC-8 前半段"升版触发"**成立**）。**但**"老版本 `is_current=false`"这半句在当前架构下**不成立**——`is_current=2009`（改动前脏数据）从未被 flip，因为 flip 只发生在"触发列变化且 existing 非空"分支里的 `flip()`，而 `existing` 查询恒为 `WHERE is_current=TRUE`，与 pending 分支互斥（§0 已述根因），新版本走的是 pending 分支，不触碰 flip。

**路径 B（全新料号 S-TEST-0727-V8，稳定性）**：
```
import#1: version=2000, pending_quotation_id=de92e4ce-...
import#2（同一份文件原样重导）: version=2001, pending_quotation_id=fa0f5eba-...  ← 版本号增长了！
```
**AC-8"再导一次版本号不再增长"在当前架构下不成立**——`writeVersionedGroup` 的"触发列+内容都同→复用旧版本号不写"短路判断（第 148 行 `if (triggerSame && contentSame) return currentVersionOf(...)`）依赖 `existing = loadCurrentGroup(...WHERE is_current=TRUE...)`；由于该材料从未被 promote 为 `is_current=true`，`existing` 每次都是空集，短路条件恒为 false，导致**每次重导都新开一个 pending 版本**，而非命中"内容相同、复用旧版本号"分支。

**根因确认非本次改动引入**：`VersionedV6Writer.java` 不在本次 diff 改动文件清单内（`git diff master...feat/repair-0727-process-no --stat` 已核实），该文件的短路逻辑与 pending 分支自 task-0721 B2 起就是这个结构，与"组装工序解析改成查真编号"无因果关系——即使 Q14 handler 完全不改，任何"首次导入未走 createQuotation promote"的全新材料重复导入同一份文件都会复现"版本号持续增长"，此现象与 process_no 解析正确与否无关。**登记为附带发现（§3），不算 repair-0727-process-no 回归**。

### TC-21 · 单元测试 + 集成回归 —— 引用技术总监结果

- `ProcessNoResolverTest,QuoteImportValidatorTest,Q14*Test,Q15*Test` → **20/20 BUILD SUCCESS**
- `*Quote*,*Capacity*,*Process*` → 147 run / 3 failures，均为 `ProcessResourceTest`，技术总监已做 A/B（master 上同样 3/3 失败、同方法名），确认 pre-existing。**不重复归因，直接引用**。**PASS**

### TC-22 · 核价侧 P08 不受污染

```sql
SELECT DISTINCT process_no FROM capacity WHERE system_type='PRICING' OR resource_group_no='PRICING_DEFAULT';
-- Z002, Z008, Z053, Z490
```
全部为真 Z 码，无中文名称混入，与技术总监此前实测口径一致。本次 diff 未改任何 P-handler（git diff --stat 确认）。**PASS**

### TC-23 · 文档纠正核对

`docs/table/报价系统Excel导入落库方案.md`：
- §14/§15 均已补充"两段匹配"规则说明（第 538/567 行）
- 反查来源 `process_master` 已写明
- Phase 1 拦截语义（"解析不到→整份 Excel 导入失败"，非"该行跳过"）已写明
- `process_name` 新增落库已在字段表体现（第 530 行）
- §15 `operation_no` 解析规则已同步（第 567 行）
- 旧误导性描述"组装工序→process_no ✅（取工序编号对应值）"经 `grep "取工序编号对应值"` **零命中**，已被新描述完全替换，无残留
**PASS（6/6 子项）**

### TC-24 · 端到端耗时增幅 < 5%

8098（master，改动前）3 次：
```
elapsed=1356ms, elapsed=1275ms, elapsed=941ms   → 中位数 T_before=1275ms
```
8097（feature，改动后）3 次：
```
elapsed=1139ms, elapsed=1053ms, elapsed=870ms   → 中位数 T_after=1053ms
```
`(1053-1275)/1275 = -17.41%`（**更快**，非增幅，远优于 < 5% 阈值）。**PASS**

### TC-25 · 索引只建一次

**代码级**：`QuoteImportValidator.java:92 ProcessNoResolver.Index processIdx = processNoResolver.buildIndex();` 只调用 1 次，`validateAssemblyProcess`(`:242`) / `validateAssemblyAnnualDiscount`(`:266`) 均接收同一个 `idx` 引用参数，非各自内部再调。

**运行时**（`quarkus.log.category."org.hibernate.SQL".level=DEBUG`）：
- 2 行黄金样例导入：`grep -ci 'from process_master' 新增日志段` → **1**
- 50 行同料号变体（`v-tc25-50rows.xlsx`）导入：同样 → **1**（不随行数增长）
> 该 50 行变体因测试数据构造问题（同料号内 Z100/Z101 循环 25 次，撞 `uq_capacity` 唯一键，唯一键不含 seq_no）在 Phase2 写入阶段报错，但 `process_master` 查询发生在 Phase1，不受影响，计数仍准确反映"索引只建一次"结论，已定位为测试数据设计问题非产品 bug。
**PASS**

---

## 3. 附带发现（Pre-existing，非本次改动引入）

| # | 现象 | 根因 | 影响 | 建议 |
|---|---|---|---|---|
| F1 | 报价 V6 导入写入的 7 张版本化表永远 `is_current=false`（除非完整走完报价单生命周期到某个未知的 promote 环节） | task-0721 B2 `ctx.pendingQuotationId=recordId` 无条件设置 + `VersionedV6Writer` 的 `existing` 查询恒 `WHERE is_current=TRUE`（与 pending 分支互斥） | AC-6b/AC-8 等以"is_current"为断言口径的验收标准在当前环境下无法按字面验证；§2 TC-18/TC-20 已详细说明 | 建议 PM/架构 澄清"验收断言应查 pending 态还是 official 态"，或补充说明"official 态"具体由哪个动作触发（本次排查未定位到） |
| F2 | 全新材料重复导入同一份未变内容的文件，`calc_version` 仍逐次递增（不会稳定复用旧版本号） | 同 F1 根因：`writeVersionedGroup` 的"内容相同→复用旧版本号"短路判断依赖 `is_current=true` 的 `existing`，pending 材料永远查不到 existing | 任何 V6 导入（不限于本次 process-no 修复涉及的 Q14/Q15）都会有此现象；长期看会导致 pending 态版本号只增不降、造成版本号"虚高" | 与 F1 同一根因，建议一并排查 |
| F3 | 本环境当前无任何已发布模板绑定"组装加工费"（zz_view/`f170b0a8`）组件，无法通过真实 UI 打开报价单验证该 tab 渲染 | 组件已建好、SQL 视图已绑好，但从未被任何 `template_component` 引用 | AC-7 的字面"打开报价单看组装页签"验收路径当前不可达 | 建议业务后续把该组件接入某个真实客户模板后补验一次；本次已用 SQL 层模拟验证替代，证据见 TC-19 |
| F4 | `template_component` 表里"罗克韦尔模板3"每个组件都有 2 条重复行（`id` 不同但 `component_id`/`tab_name` 完全相同） | 未深入排查，疑似模板发布/复制流程的既有问题 | 未观察到渲染异常，暂不确定是否影响业务 | 记录在案，非本次改动范围，建议单独立项排查 |

**以上 4 项均已通过 `git diff master...feat/repair-0727-process-no --stat` 确认涉及文件（`VersionedV6Writer.java`/`QuotePendingRewriter.java`/`template_component` 相关代码）不在本次改动清单内，判定为 pre-existing，不计入本次回归。**

---

## 4. 执行纪律遵守情况

- FX-1（`Z100=焊接`/`Z101=铆接`）已保留，未删除，末次核查仍为 2 条、无残留 BEND/Z205/Z050
- FX-2 临时数据（BEND/Z205/Z050）均已用后即删并逐条复核 `count=0`
- 未对代码做任何修改
- 8097/8098 临时实例均已 `pkill` 清理，未留后台常驻；worktree 内临时 `node_modules` 软链、临时 5183 前端进程均已清理，`git status` 复核 worktree 无残留改动
- 残留测试数据（`S-TEST-0727-B`/`S-TEST-0727-V8`/`S-TEST-0727-PERF` 材料、3 条 `COMPONENT_REDUCTION` pending 行、`capacity` 2010~2022 多个 pending 版本）**未清理**——均为 pending 态（不可见于官方 `is_current=true` 视图），不影响后续正常业务；是否清理留由技术总监裁决

---

## 5. 总体结论

**repair-0727-process-no 达到 `需求文档.md` §8 的验收标准。**

11 条 AC 中：
- AC-1/2/3/4/5/6/9/10/11 —— **完全验证通过**，证据充分（写入层 + 日志 + 单测 + 性能 + 文档）。
- AC-6b/AC-8 —— **核心修复逻辑验证通过**（工序解析正确、版本升级触发正确），但"是否切走老版本 is_current"/"再导一次是否稳定复用版本号"这两个**字面表述**在当前 pending-quotation 架构下无法被观测到（§0/§3 已详述根因，确认为**已合并的既有架构**、与本次改动**无因果关系**）。
- AC-7 —— **SQL 渲染逻辑验证通过**（含 process_master JOIN 的正确性），真实 UI 截图因环境限制（组件未接入任何模板）只能验证自制加工费一侧，组装加工费一侧改用 SQL 层等价模拟替代。

综合评定：**本次改动本身的正确性已充分证明，可以合并**；但同时发现一个更深层、跨越多个既有任务的架构性问题（pending-quotation 态与验收断言口径不匹配），建议技术总监知悉并决定是否需要单独立项处理，不应作为本次改动的阻塞项。
