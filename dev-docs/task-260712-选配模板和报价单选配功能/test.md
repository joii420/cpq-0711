# 选配模板和报价单选配功能（task-0712）测试用例与验收文档

> **文档性质**：测试用例设计文档（当前处于开发文档阶段，实现代码尚未产出）。本文档只**设计**用例与严格预期结果，不含执行记录；开发完成后按每条用例补录【实际结果】【通过/失败】【证据（截图/SQL输出/HTTP响应）】。
> **权威依据**：`需求说明.md`（§4.5 3D 契约 / §4.6 选配落库 / §9 D1–D16 + R1–R4）、`api.md`、`backtask.md`（B1–B6）、`UI设计说明.md`、`prototypes/*.html`（4 个原型）、《报价系统Excel导入落库方案 V3.4》（`docs/table/`）§2/§3/§4/§10/§14/§三·通用落库规则、`docs/E2E测试方法.md`。
> **代码事实核对**：本文档所有 DB 表结构（`material_master`/`material_bom`/`material_bom_item`/`element_bom`/`element_bom_item`/`unit_price`/`capacity`/`sel_part_signature`/`quote_customer_code`/`quote_material_no_seq`）、`SalesFingerprintCalculator`（指纹算法）、`QuoteMaterialNoAllocator`（发号规则）均已用 codegraph + 实际迁移文件源码核对（非凭空假设），版本落点见文末引用。

## v1.1 变更记录（2026-07-14，对齐 `架构评审.md`）

> `架构评审.md`（cpq-architect，2026-07-14）就 F001/F003/F004 三个开放点逐条核实现役代码 + DB 现状后给出定稿决策，**F 区对应条目已升级为正式硬用例**，不再是占位。变更清单：

| 决策 | 定稿 | 新增/改动用例 | 原 F 区条目 |
|---|---|---|---|
| 决策1 单料号 qty=2 | **选项 A**：判定按 `Σquantity`（=1→SIMPLE，≥2→COMPOSITE）；单行 qty≥2 = 父 COMPOSITE + **1 个去重子件** `composition_qty=qty`（非展开多子件）；指纹 `COMBO=P:qty`（非 `P:1,P:1`）；`validateRequest` 两处硬校验放开 | A403（重写）、A310/A311（新增，指纹）、A405/A406/A407（新增，校验闸门+判定矩阵+后端兜底裁决）、C316（新增，前端口径对齐） | ~~F001~~ 已转正 |
| 决策2 组合工艺双轨收敛 | **选项 2A**：候选取 `process_master WHERE process_category='ASSEMBLY'`（DB 实值，非'组合工艺'）；标识锚点 = `process_master.process_no`，候选/前端选值/指纹 CPROC/`capacity.process_no`/`quotation_line_composite_process.def_code` 五处一致；`capacity` 计量单位列订正为 `capacity_unit`（非 `unit`）；`composite_process_def` 不删表但选配侧三处解绑，放弃 `param_schema`（需 PM 确认） | B601（重写）、B602（重写，五处一致）、B603（新增，currency/capacity_unit 兜底）；A203 列名订正；alias 表 `ASSY01` 定义订正 | ~~F004~~ 已转正 |
| 决策3 规格字段来源 | **选项 3A**：`spec ← material_master.specification`，LEFT JOIN `material_no`（唯一索引，无 N+1）；测试期 `COALESCE(NULLIF(specification,''), dimension)` 兜显示 | B305（重写）、B311/B312（新增，两 JOIN 无 N+1 + 过滤命中） | ~~F003~~ 已转正 |
| 附带（架构评审附录④） | 元素 `characteristic` 递增口径：D11 多材质料号场景下，各子件是**独立 `material_no`**，各自挂 `characteristic` 分桶（非全挤 `'2000'`）——COMPOSITE 子件互不干扰，但同一料号"同主件不同组成"仍需真正 +1 | A206（新增） | — |

`F002`（`computeSimple` token 顺序文档/代码不一致）与 `F005`（`material_customer_map` 占位行过滤）**不在本次 3 个决策范围内，维持待定**；另据 `架构评审.md` 末尾"留给 PM 的两个业务确认点"新增 `F007`/`F008`（低风险，architect 已给可执行兜底值，不阻塞用例执行）。

---

## 0. 验收总纲（本任务必过的硬性验收门，逐条对应下方用例编号）

| # | 硬性验收门 | 判据 | 对应用例 |
|---|---|---|---|
| G1 | **选配落库六处齐全**（核心，原实现只写子表缺头表） | SIMPLE 选配后 `material_master`✓`material_bom`(头)✓`material_bom_item`✓`element_bom`(头)✓`element_bom_item`✓`unit_price(PROCESS/自制加工费)`✓ 六表 COUNT 均 >0 | A101–A108 |
| G2 | **COMPOSITE 组合工艺落库** | 父 `material_master(COMPOSITE)` + `capacity`(组装加工费，`resource_group_no='QUOTE_ASSEMBLY'`，计量单位列为 `capacity_unit`) + 各子件六处齐全 + 各子件 `element_bom` 各自独立 `material_no` 分桶不混挤 | A201–A204, A206 |
| G3 | **幂等** | 同客户同配置重复提交 `quote_part_no` 复用、`sel_part_signature` 不新增行、六表不重复插入/不累加 | A303, A501 |
| G4 | **客户维度指纹隔离** | 不同客户同配置各自独立报价料号 | A304 |
| G5 | **顺序无关 + 去尾零** | 工序集合顺序无关、元素含量去尾零同指纹（`12` = `12.0`） | A302, A305, A306 |
| G6 | **分隔符 fail-fast** | 码值含 `\| = , : ∅` → 400，不静默产生碰撞指纹 | A307, A308 |
| G7 | **自制加工费两空行 fail-fast** | 同成品 ≥2 条"投入料号+名称皆空"行 → 400（落库方案 §10 规则 3） | A309 |
| G8 | **N+1 禁令** | 已有产品列表 / 3D 预览 / 规格(`material_master`) / 候选 / 指纹全批量化，无逐行查询 | B309, B310, B311, D006 |
| G9 | **3D 契约（D15）** | `subject_type+subject_key` 同源同码；仅取 `is_current`；缺失不阻断（`data=null`）；维护后即时生效不陈旧 | B501–B510, C202–C203, D005 |
| G10 | **3D 展示边界（D9）** | 3D 仅出现在两个添加抽屉，不进报价卡片/Excel/PDF | C501–C503 |
| G11 | **有效模板兜底链（D6）** | 客户行业模板 → `__DEFAULT__` → `hasTemplate=false`（200，非报错） | B201–B203 |
| G12 | **SIMPLE/COMPOSITE 阈值（D11/D12，2026-07-14 架构评审决策1定稿）** | 判定按 `Σquantity`（=1→SIMPLE，≥2→COMPOSITE，含**单行 qty≥2 = 父COMPOSITE+1 去重子件**）；组合工艺区随 Σqty 出现；后端按 Σqty 兜底裁决不盲信前端 `productType` | A401, A402, A403, A405, A406, A407, C309, C316 |
| G13 | **事务不变量** | 指纹登记与完整落库同事务，签名可见⇔数据可见，无"Tab 空"并发窗口 | A501, A502 |
| G14 | **前端与原型一致** | 4 个原型的关键交互（明细表/过滤/3D 实时预览/空态文案）在实现中逐一复现 | C 区全部 |
| G15 | **回归零影响** | 报价单其它功能、导入链路、三大核心模块基线、既有 E2E 双 spec 不受影响 | E 区全部 |

---

## 1. 测试数据别名表 / 环境前置

> 具体客户码/配方码/工序码需在执行前按当时数据库实际种子数据核对替换（本文档产出于开发前，无法预知最终种子值）。核对方法附在每个别名后。

| 别名 | 含义 | 核对 SQL / 来源 |
|---|---|---|
| `CUST_A` | 有对应行业选配模板（`sel_template.status='ACTIVE'`）的测试客户，建议复用 E2E 既有 fixture 客户"苏州西门子"（见 `cpq-e2e-quotation-flow-test-data` 记忆） | `SELECT customer_no, industry_code FROM customer WHERE name='苏州西门子'` |
| `CUST_A_CODE` | `CUST_A` 对应 V6 落库用 `customer_no`（非 UUID，AP-53 续 6 约定） | 同上 `customer_no` 列 |
| `IND_A` | `CUST_A` 所属行业码 | `customer.industry_code` |
| `CUST_B` | 另一测试客户（不同行业或同行业但不同客户），用于验证客户维度隔离 | — |
| `CUST_C` | 所属行业无专属模板，但存在 `__DEFAULT__` 模板 | — |
| `CUST_D` | 所属行业无模板且 `__DEFAULT__` 也不存在/已停用 | — |
| `RCP_A` / `RCP_B` | 材质配方码（`material_recipe.code`），`RCP_A.symbol` 记为 `SYM_A` | `SELECT code, symbol FROM material_recipe WHERE status='ACTIVE' LIMIT 2` |
| `PRC01` / `PRC02` | 工序编号（`process_master.process_no`），及其对应 UUID `PRC01_ID` | `SELECT id, process_no FROM process_master LIMIT 2` |
| `ASSY01` | 组合工艺工序（**订正，2026-07-14 架构评审决策2**：`process_master.process_category='ASSEMBLY'`，DB 实值，非旧文档写的中文'组合工艺'；现网已有 4 行种子：总装配/部件装配/螺栓连接/焊接装配，无需另补种子） | `SELECT process_no FROM process_master WHERE process_category='ASSEMBLY'`（应恰返回 4 行） |
| `{QID}` | 草稿状态报价单 ID，客户=`CUST_A`，已绑报价模板 | — |
| `{QN}` | 某次 SIMPLE 选配提交后返回的报价料号（形如 `0007-2607000001`，见 A101 断言） | 由用例执行产生 |
| `{PQN}` | 某次 COMPOSITE 选配提交后返回的父报价料号（2 行明细，各 `quantity=1`） | 由用例执行产生 |
| `{PQN2}` | 某次 COMPOSITE 选配提交后返回的父报价料号（**单行 `quantity=2`** 场景，决策1，见 A403） | 由用例执行产生 |

**环境自检**（执行前必跑，对齐 CLAUDE.md）：
```
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/                  # 期望 200
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components # 期望 401
```

---

## A. 后端单测 / DB 落库

### A1. SIMPLE 落库完整性（核心验收点，对应 backtask B2.5）

**A101** | 接口+DB | 前置：`CUST_A` 有 `sel_template`（行业=`IND_A`，MATERIAL/ELEMENT/PROCESS 均 enabled），`RCP_A` 存在，`PRC01`/`PRC01_ID` 存在 | 步骤：`POST /api/cpq/configure-product/quotations/{QID}` body `{"productType":"SIMPLE","parts":[{"recipeCode":"RCP_A","elements":[{"elementCode":"Ni","pct":8.1},{"elementCode":"Cr","pct":18.2}],"processIds":["PRC01_ID"],"unitWeightGrams":12.5,"quantity":1}]}` | 严格预期：HTTP 200；响应体 `parts[0].hfPartNo` 非空且匹配正则 `^\d{4}-\d{4}\d{6}$`（`QuoteMaterialNoAllocator.mintAndRegister` 格式：4 位客户码-4 位年月+6 位流水）；记为 `{QN}`。

**A102** | DB SQL | 前置：A101 已执行 | 步骤：`SELECT material_no, material_type, material_recipe_id, unit_weight, config_fingerprint FROM material_master WHERE material_no='{QN}'` | 严格预期：返回恰 1 行；`material_type = 'SYM_A'`（= `material_recipe.symbol`，**不是** `recipeCode`/`code`——B2.1① 明确"材质符号"）；`material_recipe_id` 等于 `RCP_A` 对应 `material_recipe.id`；`unit_weight = 12.500000`；`config_fingerprint IS NULL`（客户维度发号不写生产侧全局指纹，避免撞 `uq_material_master_fingerprint`）。

**A103** | DB SQL | 同上 | 步骤：`SELECT COUNT(*) FROM material_bom WHERE system_type='QUOTE' AND bom_type='MATERIAL' AND material_no='{QN}' AND customer_no='{CUST_A_CODE}'` | 严格预期：**= 1**（头表存在——原实现此处恒为 0，是本次改造的核心缺口，必须 >0 才算过）。

**A104** | DB SQL | 同上 | 步骤：`SELECT seq_no, component_no, component_usage_type, rough_weight, net_weight, weight_unit, scrap_rate, defect_rate FROM material_bom_item WHERE system_type='QUOTE' AND material_no='{QN}' AND customer_no='{CUST_A_CODE}'` | 严格预期：返回 ≥1 行；每行 `component_usage_type` 若非空，取值 ∈ {`银点类`,`非银点类`,`组成件`,`边角料`}（纯中文，不含"N."编号前缀，对齐落库方案 §3 备注）。

**A105** | DB SQL | 同上 | 步骤：`SELECT bom_type, characteristic FROM element_bom WHERE system_type='QUOTE' AND material_no='{QN}'` | 严格预期：返回恰 1 行；`bom_type='MATERIAL'`；`characteristic='2000'`（首次落库默认版本，落库方案 §4 通用规则）。

**A106** | DB SQL | 同上 | 步骤：`SELECT component_no, content, issue_unit FROM element_bom_item WHERE material_no='{QN}' AND characteristic='2000'` | 严格预期：恰 2 行；`component_no` 集合 = `{'Ni','Cr'}`；`component_no='Ni'` 行 `content` 数值等于 `8.1`（`ROUND(content,1)=8.1`，不做字符串尾零比较，因 DB 列为 `DECIMAL(18,6)` 原样存储非指纹归一化文本）；`component_no='Cr'` 行 `content` 数值等于 `18.2`。

**A107** | DB SQL | 同上 | 步骤：`SELECT operation_no, pricing_price, cost_ratio, currency, unit FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' AND cost_type='自制加工费' AND finished_material_no='{QN}' AND customer_no='{CUST_A_CODE}'` | 严格预期：返回 ≥1 行；`operation_no='PRC01'`（**必须是工序编号字符串，不是请求里传的 `PRC01_ID` UUID**——即后端已完成 processId→工序码的解析映射，非原样落 UUID）。

**A108（六处齐全汇总，G1 核心断言）** | DB SQL | 同上 | 步骤：
```sql
SELECT
 (SELECT COUNT(*) FROM material_master WHERE material_no='{QN}') AS c_master,
 (SELECT COUNT(*) FROM material_bom WHERE system_type='QUOTE' AND material_no='{QN}') AS c_bom_head,
 (SELECT COUNT(*) FROM material_bom_item WHERE system_type='QUOTE' AND material_no='{QN}') AS c_bom_item,
 (SELECT COUNT(*) FROM element_bom WHERE system_type='QUOTE' AND material_no='{QN}') AS c_elem_head,
 (SELECT COUNT(*) FROM element_bom_item WHERE material_no='{QN}') AS c_elem_item,
 (SELECT COUNT(*) FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' AND cost_type='自制加工费' AND finished_material_no='{QN}') AS c_process;
```
严格预期：六列**全部 >0**（`c_master=1`；其余 ≥1）。任一为 0 即判 FAIL，直接对应需求说明 R1/backtask B2.5"原实现头表为空"的核心缺口是否已修复。

**A109（processId→工序码解析一致性，新发现风险点）** | DB SQL | 前置：A101 | 步骤：`SELECT config_signature_text FROM sel_part_signature WHERE quote_part_no='{QN}'` | 严格预期：`config_signature_text` 中含子串 `PRC=PRC01`（工序**码**），**不含** `PRC01_ID`（工序 UUID 原样落入）——若指纹用 UUID 而不是码计算，会导致"同工序不同 UUID 环境（如跨库迁移后 UUID 变化）指纹跟着变"的隐患，需在实现阶段显式做 processId→process_no 映射后再喂给 `SalesFingerprintCalculator`。

---

### A2. COMPOSITE 落库

**A201** | 接口 | 前置：`CUST_A` 有 `RCP_A`/`RCP_B` 两个材质候选，`ASSY01`（`process_category='ASSEMBLY'`，见决策2）存在 | 步骤：`POST /configure-product/quotations/{QID}` body `parts=[{recipeCode:RCP_A,...,quantity:1},{recipeCode:RCP_B,...,quantity:1}]`（合计 2），`compositeProcesses=[{defCode:'ASSY01',participatingPartIndexes:[0,1]}]`，`productType='COMPOSITE'` | 严格预期：HTTP 200；响应含父级 `hfPartNo`（记 `{PQN}`）+ 2 个子件 `hfPartNo`（记 `{QN1}`,`{QN2}`）。

**A202** | DB SQL | 同上 | 步骤：`SELECT material_type FROM material_master WHERE material_no='{PQN}'` | 严格预期：恰 1 行，`material_type='COMPOSITE'`。

**A203** | DB SQL | 同上 | 步骤：`SELECT process_no, fixed_cost, currency, capacity_unit, default_defect_rate FROM capacity WHERE material_no='{PQN}' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true`（**⚠️ 计量单位列是 `capacity_unit`，不是 `unit`——`capacity` 表无 `unit` 列，2026-07-14 架构评审决策2订正**） | 严格预期：恰 1 行；`process_no='ASSY01'`（= `process_master.process_no`，与候选值同源，见 B601/B602 五处一致断言）；`fixed_cost`/`capacity_unit`/`default_defect_rate` 若源候选（`process_master` ASSEMBLY 行）对应列为空，按 B603 兜底规则落值，不得为 SQL `NULL` 且下游渲染报错。

**A204** | DB SQL | 同上 | 步骤：对 `{QN1}`、`{QN2}` 各自重复 A108 六表 COUNT 查询 | 严格预期：两个子件**各自**六表齐全（子件独立完整落库，不是只有父件+空壳子件）。

**A205（边界：子件复用已有料号）** | 接口+DB | 前置：`{QN1}` 已在某次 SIMPLE 提交中生成过 | 步骤：本次 COMPOSITE 提交的 `parts[0]` 复用相同配置（触发 A303 幂等复用） | 严格预期：响应子件 `hfPartNo[0] == {QN1}`（复用而非新铸）；`{QN1}` 六表数据不因本次父级提交而重复。

**A206（元素 characteristic 各自分桶，架构评审附录④）** | DB SQL | 前置：A201 已提交（`{QN1}`≠`{QN2}`，两个子件材质/元素含量**不同**：如 `{QN1}` 含 Ni/Cr，`{QN2}` 含 Zn/Sn） | 步骤：`SELECT material_no, characteristic, component_no, content FROM element_bom_item WHERE material_no IN ('{QN1}','{QN2}') ORDER BY material_no, component_no` | 严格预期：① `SELECT DISTINCT material_no FROM element_bom WHERE material_no IN ('{QN1}','{QN2}')` 恰 2 行（**每个材质料号子件是独立 `material_no`**，非共享一行）；② `{QN1}` 对应行的 `component_no` 集合只含 `{Ni,Cr}`，`{QN2}` 对应行的 `component_no` 集合只含 `{Zn,Sn}`（**互不混挤**，两子件元素分属各自 `material_no` 下的 `characteristic` 分桶，不因同属一个 COMPOSITE 父件就落进同一桶）；③ 两行 `characteristic` 均可为 `'2000'`（各自首版本，互不冲突——因主键含 `material_no`，同 `characteristic='2000'` 值在不同 `material_no` 下不构成碰撞）。**此为 B2 落库改造最容易被漏的点**（架构评审：现役 `insertElementBomV6` 固定写 `'2000'`，本用例验证"固定 2000 但各自不同 `material_no`"不产生数据串号；若后续同一 `material_no` 出现"同主件不同组成"，须真正 `characteristic+1`，属另一独立场景不在本用例覆盖范围）。

---

### A3. 指纹匹配 + 幂等

**A301** | 单测（`SalesFingerprintCalculatorTest`，补充用例） | 无 | 步骤：`computeSimple("C001", [MATERIAL(materialCode="SS304"), ELEMENT([Ni:8.10,Cr:18.20]), PROCESS(["PRC01"])])` | 严格预期：`signature.text()` 精确等于
```
v1|CUST=C001|ELE=Cr:18.2,Ni:8.1|MAT=SS304|PRC=PRC01
```
（`Comparator.comparing(EnabledParam::paramTypeCode)` 按字符串字母序排列 `ELEMENT`<`MATERIAL`<`PROCESS` → token 顺序 **ELE→MAT→PRC**；`stripTrailingZeros().toPlainString()` 使 `18.20`→`18.2`、`8.10`→`8.1`；元素内部按 `elementCode` 排序 Cr<Ni）。**⚠️ 该顺序与需求说明 §4.6/backtask B2.2 文档描述的"MAT\|ELE\|PRC"顺序不一致，见 F002。**

**A302（去尾零同指纹）** | 单测 | 无 | 步骤：分别以 `pct=new BigDecimal("12")` 与 `pct=new BigDecimal("12.0")` 调用 `computeSimple`（其余入参相同） | 严格预期：两次 `signature.hash()` **相等**。

**A303（同客户同配置幂等，G3）** | 接口+DB | 前置：A101 已提交（`{QN}`） | 步骤：用**完全相同**的 `productType`/`parts`/`compositeProcesses` 请求体，同客户 `CUST_A`，向另一草稿报价单 `{QID2}` 再次 `POST /configure-product/quotations/{QID2}` | 严格预期：HTTP 200；响应 `hfPartNo == {QN}`（复用非新铸）；`SELECT COUNT(*) FROM sel_part_signature WHERE quote_part_no='{QN}'` **=1**（未新增签名行）；重跑 A108 六表 COUNT，与首次提交后的值**逐列相等**（未重复插入/未累加）。

**A304（客户维度隔离，G4）** | 接口+DB | 前置：`CUST_B` 也具备相同的材质/元素/工序候选值 | 步骤：用与 A101 完全相同的选配投影，客户换成 `CUST_B`，提交 | 严格预期：返回 `hfPartNo != {QN}`（不同客户各自独立报价料号）；`SELECT COUNT(*) FROM sel_part_signature WHERE customer_no IN ('{CUST_A_CODE}','{CUST_B_CODE}')` **=2**。

**A305（工序集合顺序无关，G5）** | 单测 | 无 | 步骤：分别 `computeSimple(...,[PROCESS(["PRC02","PRC01"])])` 与 `computeSimple(...,[PROCESS(["PRC01","PRC02"])])` | 严格预期：两次 `hash()` **相等**。

**A306（提交层顺序无关集成验证）** | 接口 | 前置：A101 已提交 | 步骤：向新报价单提交同一材质+同一工序**集合**但 `processIds` 数组顺序颠倒的请求 | 严格预期：返回 `hfPartNo == {QN}`（复用，工序顺序在提交层同样不影响落库结果）。

**A307（分隔符 fail-fast，G6）** | 单测 | 无 | 步骤：`computeSimple("C001", [MATERIAL(materialCode="SS\|304")])` | 严格预期：抛 `IllegalArgumentException`，message 含 `"不能包含分隔符"` 及具体字符 `'|'`。

**A308（分隔符 fail-fast，接口层，覆盖 = , : ∅ 四种）** | 接口 | 无 | 步骤：分别构造 4 次 `POST /configure-product`，`parts[0].recipeCode` 依次含 `=`、`,`、`:`、`∅` 字符 | 严格预期：**均返回 HTTP 400**，`message` 含"码值非法"字样并列出具体字段名（对齐 api.md §6）；`SELECT COUNT(*) FROM sel_part_signature` 提交前后计数**不变**（拒绝的请求不留任何登记痕迹）。

**A309（自制加工费两空行 fail-fast，G7，落库方案 §10 规则 3）** | 单测/集成 | 前置：构造同一成品 `finished_material_no` 下两条"投入料号"与"投入料号名称"均为空的自制加工费行（触发 `code` 兜底规则 3，`(version_no, finished_material_no, customer_no, effective_date)` 维度重复） | 步骤：调用落库路径 | 严格预期：HTTP 400；message 含"存在多条无投入料号的自制加工费，数据非法"字样且列出该成品料号；该批数据**整体不落库**（回滚，非部分落）。

**A310（决策1：单行 qty≥2 去重指纹 `COMBO=P:qty`，单测）** | 单测（`SalesFingerprintCalculatorTest`） | 无 | 步骤：`computeComposite("C001", ["P"], [2], null)`（**1 个子件料号 + 其聚合数量 2**，不是把子件拆成 2 个数组元素各 qty=1） | 严格预期：`signature.text()` 精确等于 `v1|CUST=C001|COMBO=P:2|CPROC=∅`（**不是** `COMBO=P:1,P:1`）。依据架构评审事实核实：`computeComposite` 算法本身早已按"子件:聚合数量"配对（`SalesFingerprintCalculator.java:105-137`），**本用例是confirm 现有算法在"1 个子件"边界下依然正确，不要求改算法**——真正需要改的是下方 A405 的 `validateRequest` 闸门（此前该场景根本进不了这条代码路径）。

**A311（决策1：单行 qty≥2 集成层不拆分子件，DB 断言）** | DB SQL | 前置：A403（见下）已提交，父级 `{PQN2}` | 步骤：`SELECT config_signature_text FROM sel_part_signature WHERE quote_part_no='{PQN2}'` | 严格预期：文本中含子串 `COMBO=<子件hfPartNo>:2`；**不含** `<子件hfPartNo>:1,<子件hfPartNo>:1`（验证 `ConfigureProductService` 在单行 qty=2 场景下，PASS1 只对该 `PartRequest` 调用一次 `resolvePart` 产出 1 个子件料号，PASS2 把该子件料号与其聚合 `quantity=2` 一起喂给 `computeComposite`，不是错误地把 1 行拆成 2 次 `resolvePart` 调用产出 2 个子件料号）。

---

### A4. SIMPLE/COMPOSITE 阈值判定（D11/D12，2026-07-14 架构评审决策1定稿：选项 A）

**A401（合计=1→SIMPLE）** | 接口+DB | 前置：`parts` 仅 1 行 `quantity=1` | 步骤：`productType='SIMPLE'` 提交 | 严格预期：HTTP 200；`SELECT COUNT(*) FROM capacity WHERE material_no='{QN}'` **=0**（不生成组合工艺行）；`material_master.material_type != 'COMPOSITE'`。

**A402（合计≥2→COMPOSITE）** | 接口+DB | 前置：`parts` 2 行各 `quantity=1`（合计=2） | 步骤：`productType='COMPOSITE'` 提交（同 A201） | 严格预期：同 A202/A203（父 COMPOSITE + 1 条 capacity 行）。

**A403（单行 quantity=2 → 父COMPOSITE + 1 个去重子件，决策1选项A，已定稿）** | 接口+DB | 前置：`CUST_A`、`RCP_A` 存在；组合工艺工序 `ASSY01`（`process_category='ASSEMBLY'`）存在 | 步骤：`POST /configure-product/quotations/{QID}` body：
```json
{"productType":"COMPOSITE",
 "parts":[{"recipeCode":"RCP_A","elements":[{"elementCode":"Ni","pct":8.1}],"processIds":["PRC01_ID"],"unitWeightGrams":12.5,"quantity":2}],
 "compositeProcesses":[{"defCode":"ASSY01","participatingPartIndexes":[0]}]}
```
（**注意**：`parts` 数组只有 **1** 个元素、其 `quantity=2`；`participatingPartIndexes` 长度 **=1**（`[0]`），因去重子件只有 1 个逻辑条目，不是 `[0,1]`） | 严格预期：**HTTP 200**（不是 400——旧版 `validateRequest` 曾因 `parts.size()<2` 拒绝此请求，决策1已放开，见 A405）；响应 `parentHfPartNo` 非空（记 `{PQN2}`），子件 `hfPartNo` 数组长度 **=1**（去重，不是 2 个重复条目）；`SELECT material_type FROM material_master WHERE material_no='{PQN2}'` = `'COMPOSITE'`；`SELECT COUNT(*) FROM capacity WHERE material_no='{PQN2}' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true` **=1**；`SELECT bom_type, component_no, composition_qty FROM material_bom_item WHERE system_type='QUOTE' AND material_no='{PQN2}' AND bom_type='ASSEMBLY'` 恰 **1 行**，`component_no` = 该去重子件的 `hfPartNo`，`composition_qty = 2.000000`（**父件 ASSEMBLY BOM 用 1 行 `composition_qty=2` 记账，不是落 2 行同 `component_no` 各 1**，对齐落库方案 §3 导入语义"2 个相同件 = 1 行 composition_qty=2"）。

**A405（决策1：`validateRequest` 两处硬闸门放开回归）** | 接口 | 前置：同 A403 | 步骤：分别验证旧版会拒绝、新版必须放行的两处闸门——
(a) 旧版 `ConfigureProductService.validateRequest`（改造前 `ConfigureProductService.java:1067-1092`）对 `productType='COMPOSITE'` 硬性要求 `parts.size() ∈ [2,8]`；A403 请求 `parts.size()=1`。**改造后**：COMPOSITE 下限须改判 `Σquantity>=2`（`parts.size()` 上限仍 ≤8，含义变为"去重子件行数上限"），`parts.size()=1` 但 `quantity=2` 时**必须放行**。
(b) 旧版对 `compositeProcesses[].participatingPartIndexes` 硬性要求 `size()>=2`；A403 请求 `participatingPartIndexes=[0]`（长度 1）。**改造后**：须放开允许"单去重子件、该件 quantity≥2"场景下 `participatingPartIndexes` 长度 =1。
严格预期：A403 整体请求返回 **HTTP 200**；若任一闸门未放开，响应会是 **HTTP 400** 且 message 含"至少 2"/"parts"/"participatingPartIndexes"字样——出现即判 **FAIL**，需回退代码到本用例描述的新口径（架构评审"落地要点"1/2）。

**A406（决策1：Σqty 判定矩阵，后端基线）** | 接口+DB | 分别构造 4 种 `parts` 组合提交 | 严格预期按下表逐行核对（判定公式严格是 `Σ(parts[].quantity)`，**不是** `parts.length`）：

| 场景 | `parts` 组合 | Σquantity | 后端 `productType` 判定 | `capacity` 行数 | `material_bom_item(bom_type='ASSEMBLY')` 行数 |
|---|---|---|---|---|---|
| ① | 1 行 `quantity=1` | 1 | SIMPLE | 0 | 0 |
| ② | 1 行 `quantity=2`（=A403） | 2 | COMPOSITE | 1 | 1（`composition_qty=2`） |
| ③ | 2 行各 `quantity=1` | 2 | COMPOSITE | 1 | 2（各 `composition_qty=1`） |
| ④ | 2 行 `quantity=1+3` | 4 | COMPOSITE | 1 | 2（`composition_qty` 分别=1、3） |

**A407（决策1：后端按 Σqty 兜底裁决，不盲信前端 `productType` 字段）** | 接口 | 前置：无 | 步骤：请求体显式声明 `"productType":"SIMPLE"`，但 `parts=[{...,"quantity":2}]`（Σqty=2，与声明的 SIMPLE 自相矛盾——模拟前端口径未对齐/客户端缓存陈旧的异常输入） | 严格预期：后端**不得**盲信请求体里的 `productType` 字段静默按 SIMPLE 落库（那会丢失 capacity/ASSEMBLY BOM，产生"2 件但无组装费"的错价）。以下两种后端行为**任一成立**均判通过，**唯独"静默按 SIMPLE 处理成功返回 200 且无 capacity/ASSEMBLY 行"判 FAIL**：① 后端按 `Σqty` 自行纠正为 COMPOSITE 语义落库（此时按 A403 断言）；② 后端发现 `productType` 与 `Σqty` 不一致返回 HTTP 400，message 提示口径矛盾，要求客户端按 `Σqty` 重新声明。

**A404（明细为空边界）** | 接口 | `parts=[]` | 步骤：`POST /configure-product/quotations/{QID}` | 严格预期：HTTP 400；message 提示"选配明细不能为空"或等价护栏文案（若当前实现未加此护栏，视为需补的必做项，非可选）。

---

### A5. 事务不变量（G13）

**A501（并发指纹败者不留 Tab 空）** | 并发集成测试 | 前置：同客户 `CUST_A`、完全相同的选配配置（指纹 `{FP}`）尚未提交过 | 步骤：两个线程/请求**几乎同时**发起 `POST /configure-product`（不同报价单，同客户同配置） | 严格预期：两个响应的 `hfPartNo` **相同**；`SELECT COUNT(*) FROM sel_part_signature WHERE customer_no='{CUST_A_CODE}' AND config_fingerprint='{FP}'` **=1**（`uq_sel_part_signature` + `ON CONFLICT` 生效，无重复行）；败者请求返回的 `hfPartNo` 立即执行 A108 六表 COUNT，**六项均 >0**（不出现"签名已可见但数据未提交"的 Tab 空窗口）。

**A502（事务回滚原子性）** | 故障注入测试 | 前置：在落库链路最后一步（如 `unit_price` 插入）人为注入异常（测试专用 fault-injection 开关或 mock） | 步骤：触发异常后检查 | 严格预期：`SELECT COUNT(*) FROM sel_part_signature WHERE config_fingerprint='{FP}'` **=0**（本次未提交的部分与已登记的指纹一起回滚，不残留"半成品"数据）。

---

### A6. `material_customer_map` 占位行隔离（测试设计阶段发现的落库联动风险点）

> 背景（已用 codegraph 核实 `QuoteMaterialNoAllocator.mintAndRegister` 源码）：每次选配发号都会向 `material_customer_map` 插入一条 `system_type='QUOTE', customer_product_no=NULL, production_no=NULL` 的占位行（纯粹为了给报价料号占位）。而 B3「已有产品列表」的数据源正是 `material_customer_map`。若 B3 端点未过滤 `customer_product_no IS NULL`，选配一次就会在"从已有产品添加"列表里多出一条空品名/空客户产品编号的脏行。api.md/backtask.md 均未显式写出该过滤条件，需在此提前验证。

**A601** | DB SQL | 前置：A101 已执行，`{QN}` 已由 `mintAndRegister` 写入 `material_customer_map` | 步骤：`SELECT customer_product_no, production_no FROM material_customer_map WHERE material_no='{QN}'` | 严格预期：恰 1 行，`customer_product_no IS NULL`（确认占位行真实存在，为 A602/B301 的前置事实）。

**A602（占位行不得污染已有产品列表，G1/G8 联动风险）** | 接口 | 同上 | 步骤：`GET /api/cpq/quotations/{QID}/existing-products`（`CUST_A`，不带过滤） | 严格预期：返回列表**不包含** `materialNo='{QN}'` 这条记录（即 B3 端点必须显式过滤 `customer_product_no IS NOT NULL`，否则每选配一次"从已有产品添加"列表就多一条空白脏行）。**若该用例失败，判定为 P0 级 Bug**，因为它会让选配功能的副作用直接污染另一个功能的列表数据。

---

## B. 接口测试

### B1. 选配模板管理（`api.md §1`）

**B101** | 接口 | `sel_template` 表为空 | `GET /api/cpq/sel-templates` | HTTP 200；`data` 为数组（可为 `[]`）。

**B102** | 接口 | 无 | `POST /api/cpq/sel-templates` body `{"industryCode":"IND_A","name":"测试模板","status":"ACTIVE","items":[{"paramTypeCode":"MATERIAL","enabled":true,"allowedValues":["RCP_A"]},{"paramTypeCode":"ELEMENT","enabled":true},{"paramTypeCode":"PROCESS","enabled":true,"allowedValues":["PRC01"]}]}` | HTTP 200，返回含 `id`；`GET /sel-templates/{id}` 回读 `items` 与请求逐项一致（3 条，`enabled`/`allowedValues` 均匹配）。

**B103（一行业一套 UNIQUE，D7）** | 接口 | 前置：B102 已建 `IND_A` | 再次 `POST` 相同 `industryCode="IND_A"`、不同 `name` | HTTP **非 200**（4xx）；`SELECT COUNT(*) FROM sel_template WHERE industry_code='IND_A'` 仍为 **1**（未产生第二条记录）。

**B104（编辑锁行业，D7）** | 接口 | 前置：B102 已建 | `POST` 携带已有 `id`，`industryCode` 改为 `"IND_B"` | 以下二选一必须成立（实现二选一，本用例断言"至少一个成立"）：① HTTP 4xx 拒绝改行业；② HTTP 200 但后端忽略 `industryCode` 变更。硬断言：`SELECT industry_code FROM sel_template WHERE id='{id}'` 提交后仍为 `'IND_A'`。

**B105（停用/启用两态）** | 接口 | 前置：B102 | `POST` upsert `status='INACTIVE'` | HTTP 200；`GET /sel-templates` 该行 `status='INACTIVE'`；随后 `GET /sel-templates/effective?customerNo=<IND_A客户>` 不应再把已停用的 `IND_A` 模板判为有效（`hasTemplate` 转 false 或落到 `__DEFAULT__`，取决于是否存在默认模板——本用例仅断言"不再返回已停用模板的 `templateId`"）。

**B106（删除）** | 接口 | 前置：B102 | `DELETE /sel-templates/{id}` | HTTP 200；`GET /sel-templates` 不再含该 `id`。

**B107（参数池种子）** | 接口 | 无 | `GET /sel-param-types` | HTTP 200；`data` 恰含 3 项，`code` 集合 = `{MATERIAL,ELEMENT,PROCESS}`；`MATERIAL.valueMode='single'`；`ELEMENT.valueMode='adjust'`；`PROCESS.valueMode='multi'`。

**B108（材质候选）** | 接口 | 无 | `GET /sel-param-types/MATERIAL/candidates` | HTTP 200；`data` 为 `[{key,label}]` 数组；`key` 全集 = `SELECT code FROM material_recipe WHERE status='ACTIVE'` 结果全集（数量与值均一致）。

**B109（adjust 型无候选）** | 接口 | 无 | `GET /sel-param-types/ELEMENT/candidates` | HTTP 200；`data=[]`（非报错，非 404）。

---

### B2. 有效模板解析（`api.md §1.4`，G11）

**B201（客户行业模板命中）** | 接口 | `CUST_A` 行业=`IND_A` 且 `IND_A` 模板 `ACTIVE` | `GET /sel-templates/effective?customerNo=CUST_A` | HTTP 200；`resolvedIndustryCode='IND_A'`；`usedDefault=false`；`hasTemplate=true`；`templateId` 等于 `IND_A` 模板 id。

**B202（`__DEFAULT__` 兜底）** | 接口 | `CUST_C` 所属行业无对应模板，但存在 `industryCode='__DEFAULT__'` 的 `ACTIVE` 模板 | `GET effective?customerNo=CUST_C` | HTTP 200；`resolvedIndustryCode='__DEFAULT__'`；`usedDefault=true`；`hasTemplate=true`。

**B203（完全无模板，200 非报错）** | 接口 | `CUST_D` 所属行业无模板，`__DEFAULT__` 也不存在或非 ACTIVE | `GET effective?customerNo=CUST_D` | HTTP **200**（不是 4xx/5xx，对齐 api.md §6"缺少选配模板→200+hasTemplate=false"）；`hasTemplate=false`；`params` 为空数组或省略。

**B204（客户不存在）** | 接口 | `customerNo="NOT_EXIST_999"` | `GET effective?customerNo=NOT_EXIST_999` | 严格预期：**HTTP 非 500**（具体是 400 还是"视为无模板返 200+hasTemplate=false"需 backtask 明确，本用例先断言"非服务端异常"，待补充见 F 区）。

---

### B3. 已有产品列表（`api.md §2.1`，backtask B3）

**B301** | 接口 | 报价单 `{QID}` 客户=`CUST_A`；`material_customer_map` 中 `CUST_A` 名下有 3 条 `customer_product_no` 非空记录（且不含 A601 类占位行） | `GET /api/cpq/quotations/{QID}/existing-products` | HTTP 200；`data.total=3`；每条 item 含 `materialNo`/`customerProductNo`/`productName`/`spec`/`customerMaterialName`/`has3d`/`thumbnailUrl` 全部 7 个字段；所有 `customerProductNo` 非空。

**B302（过滤-客户产品编号）** | 接口 | 同上，某条 `customerProductNo='CPN-8899'` | `GET ...?customerProductNo=CPN-8899` | HTTP 200；`total=1`；返回项 `customerProductNo='CPN-8899'`。

**B303（过滤-销售料号）** | 接口 | 同上 | `GET ...?salesPartNo=<某materialNo精确值>` | HTTP 200；`total=1`。

**B304（过滤-品名模糊）** | 接口 | 同上，某条 `customer_material_name` 含"阀体" | `GET ...?productName=阀体` | HTTP 200；`total≥1`；所有返回项 `productName` 含"阀体"子串。

**B305（规格取值断言，2026-07-14 架构评审决策3定稿：选项 3A）** | 接口+DB | 前置：`CUST_A` 名下某条产品 `materialNo` 对应的 `material_master.specification='DN50'`；另一条 `specification=''`（空串）但 `dimension='3.5×3.5×0.6'` | 步骤：`GET /quotations/{QID}/existing-products`（不带过滤） | 严格预期：返回项的 `spec` 字段值精确等于 `COALESCE(NULLIF(material_master.specification,''), material_master.dimension)`（LEFT JOIN 键 `material_customer_map.material_no = material_master.material_no`）——即第一条 `spec='DN50'`（`specification` 非空优先），第二条 `spec='3.5×3.5×0.6'`（`specification` 为空串按 `NULLIF` 归一后取 `dimension` 兜底）；`material_master` 无匹配行（JOIN 未命中）时 `spec=null`（不得报错/不得整行丢失）。

**B312（规格过滤命中）** | 接口 | 同 B305 数据 | 步骤：`GET ...?spec=DN50` | 严格预期：HTTP 200；返回项**恰为** `COALESCE(NULLIF(specification,''),dimension)` 模糊匹配 `'DN50'` 的记录（对同一 COALESCE 表达式做 `ILIKE '%DN50%'` 或等价模糊匹配，非仅精确匹配 `specification` 原始列，即"`dimension` 兜底出的值"若含 `DN50` 子串也应命中）。

**B306（AND 组合）** | 接口 | 同上 | `GET ...?customerProductNo=CPN-8899&productName=阀体` | HTTP 200；返回**同时满足两条件的交集**（非并集），可用 DB 反证 SQL 核对总数一致。

**B307（分页）** | 接口 | `CUST_A` 名下 ≥25 条产品（需构造种子） | `GET ...?page=1&size=10` | HTTP 200；`data.items.length=10`；`data.total`=实际总数（不受 `size` 影响）。

**B308（客户隔离，`api.md §2.1` 明示"前端不传客户"）** | 接口 | 报价单 `{QID2}` 客户=`CUST_B` | `GET /quotations/{QID2}/existing-products`（不带任何过滤参数） | HTTP 200；返回项全部属于 `CUST_B` 名下，**不含** `CUST_A` 数据。

**B309（N+1 硬指标，G8）** | 接口 | `CUST_A` 名下 ≥5 条产品，其中 3 条已配 `model_config(SALES_PART, is_current=true)` | `GET existing-products`，同时开 SQL 日志（`quarkus.hibernate-orm.log.sql=true`） | 严格预期：本次 HTTP 请求期间针对 `model_config` 表**只产生 1 条** SQL（LEFT JOIN 或等价批量子查询），**不出现 N 条**（N=返回行数）独立 `SELECT`；返回的 3 条 `has3d=true` 且 `thumbnailUrl` 非空，其余 2 条 `has3d=false` 且 `thumbnailUrl=null`。

**B310（3D 关联正确性）** | 接口 | 同上 | 步骤：核对 `has3d=true` 的 `materialNo` 集合 | 严格预期：与 `SELECT subject_key FROM model_config WHERE subject_type='SALES_PART' AND is_current=true` 结果集合**完全一致**（无遗漏、无多余）。

**B311（两 JOIN 无 N+1，架构评审决策3"单条 SQL"要求）** | 接口 | 前置：同 B309（`CUST_A` 名下 ≥5 条产品，其中 3 条已配 3D），并附加 B305 的规格数据（`specification`/`dimension` 均有值的行至少各 1 条） | 步骤：`GET existing-products`，开 SQL 日志（与 B309 同一次请求可合并观测） | 严格预期：本次 HTTP 请求期间针对 `model_config`**只产生 1 条**批量 SQL（同 B309），针对 `material_master`**也只产生 1 条**批量 SQL（`material_customer_map` LEFT JOIN `model_config` LEFT JOIN `material_master`，JOIN 键分别是 `subject_key=material_no`(部分唯一索引) 与 `material_no=material_no`(`uq_material_master_no` 唯一索引)）；**不出现** N 条独立 `SELECT ... FROM material_master WHERE material_no=?`（N=返回行数）。B309 + B311 须在同一次抓取里**同时**满足（一次请求、两个 LEFT JOIN、零逐行查询）。

---

### B4. 选配提交 + 指纹预查（`api.md §3`）

**B401（lookup-fingerprint 未命中）** | 接口 | 全新配置，未提交过 | `POST /configure-product/lookup-fingerprint` body（客户+选配投影） | HTTP 200；响应不含命中标记（`reusedFromExisting` 为 `false`/`null`），不返回既有销售料号。

**B402（lookup-fingerprint 命中）** | 接口 | 前置：A101 已提交（`{QN}`） | 用完全相同投影 `POST lookup-fingerprint` | HTTP 200；`reusedFromExisting=true`；命中料号 `=={QN}`；返回该料号快照数据非空（供前端汇总步回填）。

**B403（无客户不能发号）** | 接口 | 报价单未绑定客户（若系统允许构造此状态；否则改走单测直接校验对应方法） | `POST /configure-product/quotations/{QID_NO_CUST}` | HTTP **400**；`message` 含"选配需客户"字样（对齐 api.md §6）。

**B404（无模板客户提交，需 backtask 定稿）** | 接口 | 客户 `CUST_D`（B203 场景，`hasTemplate=false`） | `POST /configure-product/quotations/{QID_D}` 携带任意选配 body | 严格预期：**待定**——若前端已在无模板时禁止发起提交（抽屉空态阻断），后端仍应做兜底校验，建议 HTTP 400 且 message 含"缺少选配模板"；本用例执行前需与 backtask 确认是否已实现该兜底（见 F 区）。

**B405（提交后 line item 追加）** | 接口 | 前置：A101 | `GET /api/cpq/quotations/{QID}` | HTTP 200；报价单 line items 中存在与 `{QN}` 关联的新增行（具体字段以 `QuotationLineItem` 实体核对，最低断言：存在 1 条新增行且其料号字段值 `=={QN}`）。

---

### B5. 3D 模型配置端点（`api.md §4`，backtask B5，G9）

**B501（上传首版）** | 接口 | multipart：`subjectType=MATERIAL`，`subjectKey=RCP_A`，`label='测试材质v1'`，`glbFile=<1KB .glb>`，`thumbnailFile` 缺省，`setCurrent=true` | `POST /model-configs` | HTTP 200；返回 `ModelConfigDTO`：`version=1`，`isCurrent=true`，`glbUrl` 非空，`thumbnailUrl=null`（缺省允许为空）。

**B502（版本递增 + 旧版降级）** | 接口+DB | 前置：B501 | 再次 `POST` 同 `subjectType`/`subjectKey`，`setCurrent=true` | HTTP 200；返回 `version=2, isCurrent=true`；`SELECT version,is_current FROM model_config WHERE subject_type='MATERIAL' AND subject_key='RCP_A' ORDER BY version` = `[(1,false),(2,true)]`（旧版自动降级）。

**B503（仅上传为历史版本）** | 接口+DB | 前置：B502（当前 v2） | `POST` 上传第 3 版，`setCurrent=false` | HTTP 200；返回 `version=3, isCurrent=false`；DB 中 v2 仍 `is_current=true`（未被降级）。

**B504（设为当前，`uq_model_config_current` 不冲突）** | 接口+DB | 前置：B503（v1=false, v2=true, v3=false） | `PUT /model-configs/{v3的id}/set-current` | HTTP 200；`SELECT version,is_current FROM model_config WHERE subject_type='MATERIAL' AND subject_key='RCP_A'` = `[(1,false),(2,false),(3,true)]`（仅 1 条 `is_current`，部分唯一索引未冲突）。

**B505（历史版本查询）** | 接口 | 同上 | `GET /model-configs/versions?subjectType=MATERIAL&subjectKey=RCP_A` | HTTP 200；返回 3 条，`version` 1/2/3 齐全。

**B506（运行端查当前，D15 核心契约）** | 接口 | 同上（当前 v3） | `GET /model-configs/current?subjectType=MATERIAL&subjectKey=RCP_A` | HTTP 200；`data.version=3`；`data.glbUrl` 等于 v3 上传的 URL（**非** v1/v2 的陈旧值）。

**B507（缺失占位，非阻断）** | 接口 | `subjectKey='NOT_EXIST_RECIPE'` 从未上传过 | `GET /model-configs/current?subjectType=MATERIAL&subjectKey=NOT_EXIST_RECIPE` | HTTP **200**（非 404/非 500）；`data=null`（对齐 api.md §6）。

**B508（删除 + 级联清文件）** | 接口+DB | 前置：B504 后存在 3 条历史（v1,v2,v3） | `DELETE /model-configs/{v1的id}`（非当前版本） | HTTP 200；`GET versions` 剩 2 条（v2,v3）；`SELECT COUNT(*) FROM model_config_file WHERE model_config_id='{v1id}'` **=0**（`ON DELETE CASCADE` 级联清子表）。

**B509（缓存不得陈旧，D15）** | 接口 | 前置：`GET current` 已调用过一次（若实现有进程级缓存，此时缓存已建立） | 执行 B504 `set-current` 后**立即**再次 `GET /model-configs/current?...` | 严格预期：返回**新版本**（v3）而非旧缓存值（v2）——不允许陈旧数据；若实现未加缓存，本用例天然通过，仍作为回归哨兵长期保留。

**B510（SALES_PART 类型）** | 接口 | `subjectType=SALES_PART`，`subjectKey='{QN}'`（选配产生的销售料号） | `POST /model-configs` 上传 | HTTP 200；`GET /model-configs?subjectType=SALES_PART&keyword={QN}` 能检索到该条。

---

### B6. 组合工艺候选收敛（D13/R4，2026-07-14 架构评审决策2定稿：选项 2A）

> 决策2 已用 DB 实查订正：`process_master.process_category` **无 '组合工艺' 这个中文值（0 行）**，真实值域是 `SURFACE_TREATMENT/MACHINING/INSPECTION/ASSEMBLY/HEAT_TREATMENT/PACKAGING`；候选取 **`process_category='ASSEMBLY'`**（现网 4 行现成种子：总装配/部件装配/螺栓连接/焊接装配，无需补种子）。标识锚点统一为 **`process_master.process_no`**；`composite_process_def`（旧 6 条参数化种子）**不删表**（v0.4 configurator 仍可能依赖），但选配侧三处解绑改读 `process_master`，并**放弃 `param_schema` 参数化录入**（需 PM 确认，见 F007）。

**B601（候选端点改读 ASSEMBLY，DTO 字段收敛）** | 接口 | `process_master` 中 `process_category='ASSEMBLY'` 现有 4 行（总装配/部件装配/螺栓连接/焊接装配） | `GET /api/cpq/composite-processes`（候选端点，backtask B6 改造后应改读 `process_master`，不再读 `composite_process_def`） | 严格预期：HTTP 200；返回候选**恰 4 条**，与 `SELECT process_no,process_name FROM process_master WHERE process_category='ASSEMBLY'` 精确对应（数量、`process_no`/`process_name` 值逐一一致）；DTO 字段语义按架构评审落地要点收敛：`code = process_no`（**不是** `composite_process_def.defCode`）、`name = process_name`、`currency`/`unit`/`defectRate` 分别取 `process_master.standard_currency`/`standard_unit`/`default_defect_rate`（可为 null，见 B603）、`icon`/`paramSchema` 字段应置空或从响应移除（不再承载参数化）。

**B602（五处标识一致性，架构评审"PR 自检硬项"）** | 接口+DB | 前置：B601 候选含 `ASSY01`（= 某条 `process_master.process_no` 真实值） | 步骤：提交 COMPOSITE 选配（同 A201/A403 场景之一）选中 `ASSY01`，随后依次核对以下 **5 处**取值 | 严格预期：以下 5 个值**逐一相等**，等于 `ASSY01`（即该 `process_master.process_no`）——漏一处即判组合工艺静默错配、FAIL：
1. **候选端点**返回的 `code`（B601）；
2. **前端选中值**（UI 层送出的 `compositeProcesses[].defCode` 请求字段值——字段名沿用 `defCode` 不变，但其**值域**已从旧 `composite_process_def.code` 切换为 `process_master.process_no`）；
3. **指纹 CPROC**：`SELECT config_signature_text FROM sel_part_signature WHERE quote_part_no='{PQN}'`，文本含 `CPROC=ASSY01`（**不是**旧 `composite_process_def.code` 值，如原 6 条种子的"铆接/电阻焊"等编码）；
4. **`capacity.process_no`**：`SELECT process_no FROM capacity WHERE material_no='{PQN}' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true`；
5. **`quotation_line_composite_process.def_code`**：`SELECT def_code FROM quotation_line_composite_process WHERE line_item_id = (对应本次选配生成的 line_item.id)`（该表 2026-07-14 前既有，`def_code` 语义本次由"`composite_process_def.code`"变更为"`process_master.process_no`"）。

**B603（决策2：`process_master` ASSEMBLY 行空列兜底）** | 接口+DB | 前置：`process_master` ASSEMBLY 4 行现状 `standard_currency`/`standard_unit`/`default_defect_rate` 均为空（架构评审 DB 实查结论） | 步骤：用这 4 行中任一条选配提交 COMPOSITE，落库后查 `capacity` 对应行 | 严格预期：`SELECT currency, capacity_unit, default_defect_rate FROM capacity WHERE material_no='{PQN}' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true` **不因源列为空而写入 SQL NULL 导致下游渲染报错**——`currency` 应有兜底值（架构评审建议 `'CNY'`，与自制加工费口径一致；实现若采用其它兜底值需在此用例回填）；`capacity_unit` 允许为空字符串或兜底值（不强制非 null，但不得是导致前端渲染抛错的非法类型）；`fixed_cost` 允许为 `NULL`（架构评审明示"单价由 INPUT 层维护"，非本用例阻断项）。

---

### B7. 错误码通用（`api.md §6`）

**B701** | 接口 | 请求不带 `Authorization` header | `GET /api/cpq/sel-templates` | HTTP **401**；message="未授权"（非 404）。

**B702** | 接口 | 同上 | `POST /model-configs` | HTTP **401**。

**B703** | 接口 | 同上 | `GET /quotations/{QID}/existing-products` | HTTP **401**。

---

## C. 前端 / UI（对照 4 个原型，UI设计说明.md）

### C1. 选配模板管理页（原型 `原型-选配模板管理.html`）

**C101** | UI | 登录 admin，进入配置中心→选配模板管理（`/config/sel-templates`） | 打开页面 | 页面标题"选配模板管理"；工具栏含"+ 新建模板"按钮；表格列头依次为 归属行业/模板名/启用参数数/状态（4 列，对齐 UI设计说明 §1.1）。

**C102（空态）** | UI | `sel_template` 表为空 | 打开列表页 | 表格区显示"暂无选配模板，点击右上「+ 新建模板」创建"。

**C103（工具栏动作 enabledWhen，列表操作规范）** | UI | 未勾选任何行 | 查看"编辑"按钮 | `disabled=true`，hover 显示原因提示（**不得**用 `if(...) return null` 隐藏按钮，对齐 `docs/列表操作规范.md`）。

**C104（抽屉规格）** | UI | 点击"+ 新建模板" | 打开 | Ant Design `Drawer`，`placement="right"`，宽度 **720px**；标题"新建选配模板"。

**C105（参数区联动）** | UI | 抽屉内，未勾选"材质"参数 Checkbox | 查看该参数下方多选下拉框 | `disabled=true`；勾选后下拉**立即**变为可用。

**C106（行业码编辑锁定）** | UI | 编辑已有模板抽屉 | 查看"归属行业" Select | `disabled=true`（对齐 D7）。

**C107（一行业一套保存报错）** | UI | 已存在 `IND_A` 模板 | 新建模板选归属行业=`IND_A` 并保存 | `message.error` 提示（含"已存在"/"重复"字样）；抽屉不关闭；列表不新增该行。

---

### C2. 报价单 · 从已有产品添加抽屉（原型 `原型-报价单-从已有产品添加.html`）

**C201（布局）** | UI | 草稿报价单 Step2，点"添加产品▾"→"从已有产品添加" | 打开 | `Drawer` 宽度 **960px**；上方过滤条含 4 个输入框（客户产品编号/销售料号/品名/规格）+ 查询按钮；左侧多选表格 + 右侧 3D 预览区两栏布局。

**C202（3D 占位，G9）** | UI | 选中列表某行（该料号无 `model_config` 记录） | 单击该行 | 右侧预览区显示"该料号未配置 3D 模型"（原型原文案）；不阻断继续勾选/加入。

**C203（3D 带出，G9）** | UI | 选中某行（该料号已配 `model_config(SALES_PART, is_current)`） | 单击该行 | 右侧预览区显示该料号缩略图 + 标题含该销售料号号。

**C204（多选计数）** | UI | 勾选 3 行 | 查看底部 | 底部文案"已选 3 项"；"加入报价单"按钮可点。

**C205（加入，D8）** | UI | 已选 3 项，点"加入报价单" | 点击后 | `message` 成功提示含"已加入 3 个产品"字样；抽屉关闭；报价单产品卡片区新增 3 张卡片，按**当前客户报价模板**渲染（非选配模板）。

**C206（过滤不清除已选，D14 反向验证）** | UI | 已勾选 2 行，随后在过滤条输入内容并查询 | 查询后 | 之前勾选的 2 行（即便不在新过滤结果中）勾选状态**保持**，底部"已选 N 项"数字不因过滤而清零。

---

### C3. 报价单 · 选配添加抽屉（明细表格式，原型 `原型-报价单-选配添加.html`，D11）

**C301（无模板空态）** | UI | 客户所属行业+`__DEFAULT__` 均无模板 | 打开"选配添加" | 抽屉显示："缺少选配模板 —— 请先在「配置中心 → 选配模板管理」为该客户所属行业或默认模板配置选配参数。" + "→ 去配置选配模板" 链接；**不显示**明细表/新增按钮。

**C302（初始空表）** | UI | 客户有有效模板 | 打开"选配添加" | 明细表初始 0 行；顶部"+ 新增材质料号"按钮可点；表下方"数量合计：0"。

**C303（材质候选过滤，D14）** | UI | 点"+新增材质料号"，材质候选过滤框输入"304" | 输入后 | 候选列表**实时**过滤为仅含"304"子串匹配项，未匹配项隐藏。

**C304（选材质→3D 实时预览，D15）** | UI | 材质候选中单选某材质（已配 3D） | 选中 | 右侧 3D 预览区**立即**切为该材质预览图 + 标题含"材质"字样。

**C305（元素含量微调）** | UI | 已选材质，进入元素含量步骤 | 修改某元素含量数值 | 数字输入框可编辑；确认回填后明细表该行"元素含量"列摘要反映修改后的值。

**C306（工序候选过滤，D14）** | UI | 进入工序步骤，过滤框输入关键字 | 输入后 | 工序候选实时过滤，未匹配项隐藏；支持多选。

**C307（回填明细表）** | UI | 完成材质→元素→工序，点确认 | 点击后 | 明细表新增 1 行，材质/元素含量/工序 三列均反映刚选内容；数量列默认值 **=1**；"数量合计"相应 **+1**。

**C308（行内编辑数量）** | UI | 已有 1 行（数量=1） | 将数量输入框改为 3 | 该行数量=3；"数量合计"文案实时更新为 **3**。

**C309（组合工艺阈值触发，D12 核心）** | UI | 明细表当前 1 行（合计=1，组合工艺区隐藏/灰置） | 新增第 2 行（数量默认 1，合计变 2） | 组合工艺区**立即**从隐藏/灰置变为可交互出现，"数量合计≥2 时需选择组合工艺"提示消失。

**C310（组合工艺候选来源，D13，2026-07-14 架构评审决策2订正）** | UI | 数量合计≥2 | 展开组合工艺候选列表 | 候选项集合与 `process_master WHERE process_category='ASSEMBLY'` 一致（**非**中文'组合工艺'，与 B601 同源，恰 4 项：总装配/部件装配/螺栓连接/焊接装配）；候选带过滤框；前端选中后送出的值必须是 `process_no`（与 B602 五处一致锚点对齐，不得回退成旧 `composite_process_def` 的 `defCode`/参数化表单）。

**C311（组合工艺过滤，D14）** | UI | 同上 | 过滤框输入关键字 | 候选列表实时过滤。

**C312（数量合计回退，边界，待定见 F006）** | UI | 已触发组合工艺（合计=2）且已勾选组合工艺项，删除其中 1 行使合计回到 1 | 删除后 | 组合工艺区回退为隐藏/灰置状态；**已勾选的组合工艺选择是清除还是保留提交需 backtask/PM 补充交互细则**（原型未覆盖此边界），执行前需先确认口径。

**C313（汇总-未命中）** | UI | 明细表配置为全新组合（未提交过） | 查看汇总区 | 显示"🆕 将新建选配产品"（或等价文案），不展示已有料号。

**C314（汇总-指纹命中，G9 3D 切换）** | UI | 明细表配置与已提交过的 `{QN}` 完全一致 | 查看汇总区（前端应已调用 `lookup-fingerprint`） | 显示"✅ 匹配到已有销售料号 `{QN}`，将带出其内容与 3D"；右侧 3D 预览从"材质 3D"**切换为**"`{QN}` 的料号 3D"。

**C315（确认加入）** | UI | 点"确认加入" | 点击后 | `toast`"已加入选配产品"（命中场景含料号，如"已加入选配产品（命中销售料号 SP-2048）"）；抽屉关闭；产品卡片区新增对应卡片并按当前客户报价模板正常渲染。

**C316（决策1：单行数量提升到≥2 同样触发组合工艺区，前后端 Σqty 口径一致）** | UI | 明细表当前恰 **1 行**，数量=1（组合工艺区隐藏/灰置，同 C302 初始态） | 不新增行，直接把该行的数量输入框由 1 改为 2（C308 场景的延伸，此时行数仍=1） | 严格预期：组合工艺区**同样立即**从隐藏/灰置变为可交互出现（**不能**要求"必须新增第 2 行"才触发——前端判定阈值必须是 `Σquantity`，不是"表格行数"，与后端 A403/A405 的 `Σqty>=2` 口径严格对齐，架构评审"前后端判定口径必须对齐为 Σqty"落地要点3）；随后走完组合工艺选择+确认加入，产品卡片渲染应与 A403 的落库形态一致（父级 COMPOSITE + 1 个子件展示行）。

---

### C4. 配置中心 · 3D 模型配置页（原型 `原型-配置中心-3D模型配置.html`）

**C401（Tab 分区）** | UI | 进入 配置中心→3D 模型配置 | 打开页面 | 顶部 2 个 Tab"销售料号模型"/"材质模型"，分别对应 `subjectType` SALES_PART/MATERIAL。

**C402（列表列）** | UI | 材质模型 Tab | 查看表头 | 含 材质配方码/材质名/模型名/当前版本/缩略图/大小/上传时间（7 列，对齐 UI设计说明 §4.2）。

**C403（.glb 必填校验）** | UI | 打开"上传模型"抽屉，不选文件直接点上传 | 点击后 | 前端拦截提交，提示"请先选择 .glb 模型文件"（或等价必填提示），**不发起** HTTP 请求。

**C404（仅支持 .glb）** | UI | 选择一个非 `.glb` 文件（如 `.obj`） | 选择后 | `message.error`"仅支持 .glb 格式的模型文件"，文件未被接受为待上传状态。

**C405（绑定对象过滤，D14）** | UI | 材质 Tab 上传抽屉，绑定对象输入框输入部分配方码 | 输入后 | 候选列表（datalist/可搜索下拉）实时过滤匹配项，**非**纯静态 `<select>`。

**C406（上传成功刷新）** | UI | 完成上传并选"上传并设为当前" | 提交后 | 抽屉关闭，列表刷新，新版本行置顶且"当前"状态标记显示在新版本上，旧版本标记消失。

**C407（危险动作二次确认）** | UI | 勾选 1 条记录，点"删除" | 点击后 | 弹出二次确认（Modal/Popconfirm）列出所选项，需再次确认才真正删除。

---

### C5. 3D 展示边界（D9，G10）

**C501** | UI/回归 | 已通过 C205 或 C315 加入产品的报价单 | 查看产品卡片各 Tab 渲染 | 卡片渲染区（`.qt-cost-table` 等）**不出现**任何 3D 预览/`.glb`/缩略图元素。

**C502（Excel 导出）** | 回归 | 同上 | 导出 Excel | 导出文件表头+数据列**不含** 3D 相关列/图片对象。

**C503（PDF 导出）** | 回归 | 同上 | 导出 PDF | PDF 渲染页**不含** 3D 图片/模型引用。

---

## D. E2E（Playwright，对齐 `docs/E2E测试方法.md`）

**D001（从已有产品添加，主流程）** | E2E | admin 登录，`CUST_A`（复用"苏州西门子"+"报价模板0608 v1.9"既有 fixture）| 新建/打开草稿报价单→添加产品▾→从已有产品添加→过滤→多选 2 项→加入报价单→逐一切换 8 个 Tab | 全流程无红色 error toast；**`'加载中'` 最终计数 = 0**（沿用现有 E2E 指标）；2 个新卡片在全部 Tab 正常渲染字段值（非"—"/`undefined`）。

**D002（选配添加，SIMPLE）** | E2E | 同上环境 | 打开"选配添加"→确认模板存在→新增 1 行材质（单料号 `quantity=1`，合计=1）→确认组合工艺区全程未出现→确认加入→提交 | 抽屉内组合工艺区**全程未出现**；产品卡片渲染后`'加载中'`计数=0；随后按 A108 SQL 核对六表齐全。

**D003（选配添加，COMPOSITE）** | E2E | 同上环境 | 选配添加→新增 2 行材质（合计=2）→组合工艺区出现→勾选 1 项组合工艺→汇总确认→提交 | 走 `composite-product-flow.spec.ts` 同款断言口径（`'加载中'`计数=0）；产品卡片父级+子件 Tab 均有数据。

**D004（缺模板空态）** | E2E | 构造种子客户：所属行业+`__DEFAULT__` 均无模板 | 打开选配添加 | F12 Network 面板确认**无** `/configure-product` 相关 `POST` 发出（用户未能进入可提交状态）；页面显示空态文案（同 C301）。

**D005（指纹命中 3D 切换，G9）** | E2E | 前置：D002 已提交过 `{QN}`，且 `{QN}` 已配 `SALES_PART` 3D | 用相同配置再次打开选配添加走到汇总步 | 3D 预览区图片 `src` 属性从"选材质步骤"时的材质 3D URL 变为 `{QN}` 的 `SALES_PART` 3D URL（前后 `src` 属性值不同，可断言）。

**D006（N+1 Network 实证，G8）** | E2E | 打开"从已有产品添加"抽屉（客户名下 ≥5 条产品） | F12 Network 面板过滤 XHR/fetch | 仅 **1 条** `GET .../existing-products` 请求；无额外逐行 `GET /model-configs/current?...` 请求（3D 应随行内 `thumbnailUrl` 字段随列表一次直出）。

**D007（既有双 spec 回归，明细表 UI 改造后的必修项）** | E2E | 无 | 运行现有 `composite-product-flow.spec.ts` 与 `quotation-flow.spec.ts` | **若旧 spec 仍用 P1-P5 步骤/"料号搜索结果卡片"/"工序+添加按钮"等旧选择器（`docs/E2E测试方法.md` §2.3，对应旧向导式 `ConfigureProductDrawer`），因 D11 已将选配添加改为明细表格式，旧选择器必然失效——必须在同一 PR 内同步更新为新明细表选择器**，更新后两 spec 均 `1 passed`，`'加载中'` final=0。**本用例失败 = PR 不完整，不得合并。**

**D008（截图证据）** | E2E | D002/D003 执行过程 | 按 CLAUDE.md 要求截图 qf-19（确认添加后）+ qf-21~28（8 Tab） | 产出 9 张截图，均无"加载中"/空白/`undefined` 可见文本。

---

## E. 回归

**E001** | 回归 | 现有"从基础数据导入"生成产品的报价单（非本次新增路径） | 走完整 Step1-5 流程提交 | 全流程行为与本次改动前一致，产品卡片/Excel/PDF 渲染无变化。

**E002（AP-50 只读/编辑视图一致性）** | 回归 | 含选配产品的报价单详情页（只读，`ReadonlyProductCard`） | 打开只读视图 | 与 Step2 编辑视图渲染结果一致，不出现"编辑视图有值、详情页空值"的分裂。

**E003（field_type 未变动，D10）** | 回归 | 检查本次 PR diff | 静态核对 | 未出现对 `VALID_FIELD_TYPES`/`field_type` 枚举的改动（不触发 AP-44 十七处协议联动）。

**E004（三大核心模块基线未破坏）** | 回归 | 检查组件管理/模板管理/报价渲染管线代码路径 | 静态核对 | `ComponentDriverService`/`FormulaCalculationService`/`TemplateService` 核心方法签名未变（除非 backtask 明确需要的最小适配，需单独说明）。

**E005（`material_customer_map` 占位行不影响既有下游功能）** | 回归 | 已知 `mintAndRegister` 会写入 `customer_product_no=NULL` 占位行（见 A601） | 检查现有读取 `material_customer_map` 的功能（如客户料号关系报表、其它下拉候选） | 均不受占位行影响（原本已有 NULL 容错，或 backtask 已补统一过滤），无因新增占位行导致的空行/计数错误。

**E006（`unit_price` 唯一键无碰撞）** | 回归 | 本次新增 PROCESS/自制加工费选配落库行 与 既有 Excel 导入的同类行（同 `customer_no`）并存 | `SELECT system_type,price_type,version_no,code,COALESCE(customer_no,''),COALESCE(supplier_no,''),COALESCE(effective_date,DATE '1900-01-01'), COUNT(*) FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' GROUP BY 1,2,3,4,5,6,7 HAVING COUNT(*)>1` | 返回 **0 行**（`uq_unit_price` 未被静默覆盖/碰撞）。

**E007（`capacity` 唯一键无碰撞）** | 回归 | 本次 COMPOSITE 新增 `capacity` 行 与 既有导入组装加工费 `capacity` 行 | `SELECT material_no,process_no,resource_group_no,COALESCE(calc_version,''),COUNT(*) FROM capacity GROUP BY 1,2,3,4 HAVING COUNT(*)>1` | 返回 **0 行**（`uq_capacity` 未被破坏）。

**E008（小数精度口径不变）** | 回归 | 选配产品卡片渲染的计算列 | 查看小数位 | 计算列/列小计/页签合计 4 位，最终产品小计+导出 2 位，与既有非选配产品一致（`cpq-decimal-display-policy`，D10 未改渲染管线）。

**E009（既有 Excel 导入链路不受影响）** | 回归 | 用既有《报价系统Excel导入落库方案》测试数据跑一次完整导入 | 对照改动前结果 | 各 Sheet 落库行数/关键字段与本次改动前**完全一致**（`ConfigureProductService` 改造不影响独立的 Q02-Q19 系列 Handler 链路）。

**E010（v0.4 独立 3D 选配器并存不动，D1）** | 回归 | 无 | 访问 v0.4 独立 3D 选配器（`configurator`/`product_config_*` 相关路由） | 功能正常，不受 `model_config` 新表/新页影响。

---

## F. 待定项（本轮测试暂不可给出唯一严格预期）

> **2026-07-14 更新**：原 F001（单料号 qty=2 归属）/ F003（规格来源）/ F004（组合工艺分类值）已由 `架构评审.md` 裁定，**全部转正为正式硬用例**，不再列于本区：
> - ~~F001~~ → A403 / A310 / A311 / A405 / A406 / A407 / C316（决策1）
> - ~~F003~~ → B305 / B311 / B312（决策3）
> - ~~F004~~ → B601 / B602 / B603（决策2）
>
> F002 / F005 / F006 **不在本次 3 个决策范围内，维持待定**；另据架构评审末尾"留给 PM 的两个业务确认点"新增 F007 / F008。

**F002** | `SalesFingerprintCalculator.computeSimple` 实际 token 顺序（代码实测：按 `paramTypeCode` 字母序 **ELE→MAT→PRC**，见 A301；`架构评审.md`"核实到的关键代码/数据事实"表第 3 行也独立核实同一结论，仅陈述现状未给裁定）与需求说明 §4.6/backtask B2.2 文档描述的顺序（**MAT→ELE→PRC**）**不一致** | 已用 codegraph 核实 `SalesFingerprintCalculator.java:139-175` 源码。需 PM/architect 二选一裁定：①判定为文档笔误，更新需求说明/backtask 顺序描述为实际 `ELE\|MAT\|PRC`；②判定顺序有业务含义须改代码——但该类属 🟢 现有代码，改动会使**历史已登记的 `sel_part_signature` 全部失效**（指纹值随之改变），需同步评估数据修复计划。**不可保留矛盾放行。**

**F005** | `material_customer_map` 占位行（A601/A602/E005）过滤规则——backtask 未显式写出 `customer_product_no IS NOT NULL` 过滤条件，属本轮测试设计阶段发现的落库联动风险 | 已用 codegraph 核实 `QuoteMaterialNoAllocator.mintAndRegister` 源码确实写 `customer_product_no=NULL` 占位行。建议 backtask B3 补充明确该过滤条件；A602 是本文档新增的验收硬性用例，不因待定而豁免执行。

**F006** | C312：组合工艺已勾选后数量合计回退到 1 时，已选组合工艺是"清除"还是"保留"提交——D12/D11 及 4 个原型均未覆盖该边界交互，`架构评审.md` 决策1未涉及该 UI 边界 | 待 PM/UI 设计补充交互细则后回填 C312 严格预期。

**F007（架构评审"留给PM的确认点a"，低风险不阻塞执行）** | 决策2取舍：组合工艺放弃 `param_schema` 参数化录入（铆接压力/焊接电流等，旧 `composite_process_def` 6 条种子自带）是否可接受 | architect 推荐"符合需求的减法"（需求 §4.1 只说"选择组合工艺"未提参数录入）。**本文档 B601/C310 已按"放弃参数化"编写为硬用例**；若 PM 推翻该取舍，需求需扩范围给 `process_master` 加 `param_schema` 列（本次架构评审范围外），届时 B601 的 DTO 字段断言（`icon`/`paramSchema` 置空）需回退修改。

**F008（架构评审"留给PM的确认点b"，低风险不阻塞执行）** | 决策3取舍："规格"语义最终映射 `material_master.specification` 还是 `dimension` | architect 已给出可执行兜底：`COALESCE(NULLIF(specification,''), dimension)`（**测试期两列都在同一 JOIN 里，B305/B311/B312 用该 COALESCE 表达式即可直接执行，不阻塞**）；仅当 PM 未来明确"规格"就是 `specification`（不兜底 `dimension`）或反之，才需要回调整 B305 的精确取值断言与优先级顺序。

---

## Definition of Done

- [ ] 验收总纲 G1–G15 全部有对应用例执行通过（不允许"部分通过视为完成"）
- [ ] A 区（后端单测/DB 落库）37 条用例：SIMPLE 六处齐全（A108）、COMPOSITE 父子件齐全（A204）、**单行 qty≥2 去重子件+校验闸门放开+Σqty矩阵+后端兜底裁决**（A403/A405/A406/A407，决策1）、指纹去重确认（A310/A311）、元素 characteristic 各自分桶（A206）、幂等（A303/A501）、客户隔离（A304）、顺序无关+去尾零（A302/A305/A306）、分隔符 fail-fast（A307/A308）、两空行 fail-fast（A309）全部通过
- [ ] B 区（接口）46 条用例：模板 CRUD+一行业一套（B101-B109）、有效模板兜底链三态（B201-B203）、已有产品列表 4 过滤+分页+客户隔离+**规格 COALESCE 取值+两 JOIN 无 N+1**（B301-B312，决策3）、选配提交+指纹预查（B401-B405）、3D 模型配置全生命周期+缓存不陈旧（B501-B510）、**组合工艺候选改读 ASSEMBLY+五处标识一致+空列兜底**（B601-B603，决策2）、401 覆盖（B701-B703）全部通过
- [ ] C 区（前端/UI）39 条用例：4 个原型的关键交互（明细表回填/数量合计联动/组合工艺阈值含**单行qty提升同样触发**(C316)/3D 实时切换/空态文案/过滤保留已选/危险动作二次确认）与实现逐一比对一致
- [ ] D 区 E2E 8 条：`quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 均 `1 passed`，`'加载中'` final=0，且**新明细表 UI 已同步更新旧选择器**（D007）；建议补跑一次单行 qty=2（A403/C316 对应场景）的手工/E2E 复核
- [ ] E 区回归 10 条：既有导入链路、三大核心模块基线、`unit_price`/`capacity` 唯一键、小数精度口径、v0.4 独立选配器均无回归
- [ ] F 区剩余 5 条待定项（F002/F005/F006/F007/F008）**F002 指纹顺序矛盾、F005 占位行过滤为高优先级，必须在合并前裁定，不得遗留到上线后**；F007/F008 为架构评审留给 PM 的业务确认点，低风险不阻塞本轮用例执行，但需在上线前拿到 PM 一句话确认（否则 B601/B305 的取舍口径可能在正式上线口径下回退修改）
- [ ] **决策1/2/3 五处/多处一致性硬检查**（2026-07-14 架构评审新增）：A403 单行 qty=2 落库形态、A405/A406/A407 校验闸门与判定口径、B602 五处标识一致（候选/前端值/CPROC/capacity.process_no/quotation_line_composite_process.def_code）、A203/B603 均使用 `capacity_unit` 而非 `unit`、B305/B311/B312 规格 COALESCE 表达式与 N+1，全部通过
- [ ] 3D 展示边界（C501-C503）：报价卡片各视图/Excel/PDF 均不含 3D 元素
- [ ] N+1 双证据：接口级 SQL 日志（B309/B311）+ E2E Network 面板（D006）均确认无逐行查询
- [ ] 所有"发号"（`{QN}`/`{PQN}`/`{PQN2}`）格式统一匹配 `^\d{4}-\d{4}\d{6}$`，未出现旧 `sales_part_no` 落库法残留（对齐 V315 统一料号语义）
- [ ] RECORD.md 已由实现方追加本次开发记录（测试方不代写，仅在验收通过后核对记录存在）
