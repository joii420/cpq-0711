# 选配模板方案 · Plan 3c：按 sales_part_no 落库 handler 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。Steps 用 `- [ ]`。
> **🔴 触碰核心基线 `ConfigureProductService` 落库 + 影响核价侧 sales_part_no 分组 → 强制 Playwright E2E 双 spec。**

**Goal:** 选配落库按**模板启用参数**动态分发（材质/元素/工序 handler），并给各 V6 QUOTE 行补 `sales_part_no` 维度（= 报价料号）；确认为报价料号建 `material_master` 行（`config_fingerprint=NULL`）防 AP-53 mirror 断链。

**Architecture / 基线依据：** 《选配运行时接入-集成设计》§3 + §4。**采用备选 A（轻量）**：不引 `SelPersistHandler` SPI，在 `resolvePart`/`configure` 落库处按 `enabledParams` 的 `paramTypeCode` `switch` 直调现役三方法并补 `sales_part_no`；SPI 化列入 BACKLOG。`V311` 已加 `sales_part_no` 列，本 Plan **不新增迁移、不改 mirror 视图 DDL**——只填已存在的列。

**Tech Stack:** Java 17 + Quarkus + Panache；测试 `@QuarkusTest` + Playwright E2E。

**依赖（硬前置）：** **Plan 3b 已合**（`quotePartNo` 来源 + `resolvePart`/`configure` 已换发号骨架 + `SalesConfigContext.enabledParams`）；`V311` sales_part_no 列存在。

**选配语境落库口径（集成设计 §3.4，硬约束）：** 选配 `material_master` 行 `material_no = sales_part_no = quotePartNo`（生产=报价同号，1:1 自洽）；**不复用**任何既有生产料号行；未来 ERP 生产号后补走 `material_customer_map.production_no`，不回改 material_master 主键。

---

## Task 0: 前置 + 勘查（不改代码）

- [ ] **Step 1**：确认 3b 已合（`grep SalesConfigContext cpq-backend/.../configure/` 存在、`resolvePart` 已用 `quoteAllocator`）。
- [ ] **Step 2**：确认 `sales_part_no` 列存在：`grep sales_part_no cpq-backend/src/main/resources/db/migration/V311*.sql`。
- [ ] **Step 3**：Read 现役落库方法真实代码：`insertElementBomV6(:410-424)`、`insertMaterialBomItemV6(:442-453)`、`insertProcessSimpleUnitPriceV6(:647-686)`、COMPOSITE `writeCombomaterialBomV6(:515)`/`insertProcessUnitPriceV6(:595)`/`insertCompositeProcessCapacityV6(:696)`、`insertMaterialMasterV6(:390)`；核价「销售料号维度落库」口径 `docs/table/核价落库-销售料号维度增补定义-V6.2.md` 对应 Sheet。**记录真实 INSERT 列/groupKey，改造以真实为准。**
- [ ] 无提交。

---

## Task 1: 落库分发（备选 A · switch）

**Files:** Modify `ConfigureProductService.resolvePart`（custom 未命中→已 mint 之后的落库段）

> 集成设计 §3.1。现役 custom 分支本就顺序调 `insertMaterialMasterV6`/`insertElementBomV6`/`insertMaterialBomItemV6`(+工序)。3c 改为**按 `salesCtx.enabledParamsFor(pr)` 决定调哪些**（模板没启用工序就不落工序），并把 `sales_part_no` 传入。

- [ ] **Step 1**：把落库段改为遍历该 part 的 `EnabledParam`，按 `paramTypeCode` 分发（`persist_handler_key` 语义对照）：
  - `MATERIAL`（`MATERIAL_RECIPE_BIND`）→ `insertMaterialMasterV6(quotePartNo,...,null)` + `insertMaterialBomItemV6(quotePartNo, customerCode, symbol, salesPartNo=quotePartNo)`
  - `ELEMENT`（`ELEMENT_OVERRIDE`）→ `insertElementBomV6(quotePartNo, customerCode, elements, salesPartNo=quotePartNo)`
  - `PROCESS`（`PROCESS_LIST`）→ `insertProcessSimpleUnitPriceV6(quotePartNo, processIds, customerCode, salesPartNo=quotePartNo)`
  - **material_master 建行必发生**（材质启用即建；即便只启用材质也要建，防断链 §3.3）。
  > 注：元素依附材质（需求 spec §2）——ELEMENT 启用必然 MATERIAL 也启用；分发顺序 MATERIAL→ELEMENT→PROCESS。
- [ ] **Step 2**：编译 + 后端单测不回归 → 提交。

---

## Task 2: 各落库方法补 `sales_part_no` 列

**Files:** Modify `insertElementBomV6` / `insertMaterialBomItemV6` / `insertProcessSimpleUnitPriceV6`（+ COMPOSITE 三方法）签名加 `String salesPartNo`，INSERT/groupKey 补该列

> 集成设计 §3.2 表（以 Task 0 真实 INSERT 为准）。**选配语境 salesPartNo = quotePartNo**（显式填，不留 NULL 靠 COALESCE 兜底）。

- [ ] **Step 1**：逐方法改（真实列名/顺序以 Task 0 为准）：
  | 方法 | 改点 |
  |---|---|
  | `insertElementBomV6:414` | INSERT 列表加 `sales_part_no`，`.setParameter` 传 partNo |
  | `insertMaterialBomItemV6:444` | INSERT 加 `sales_part_no = :p` |
  | `insertProcessSimpleUnitPriceV6:677` | `VersionedGroupSpec` 的 groupKey `put("sales_part_no", hfPartNo)`（进版本组，与核价 §3.1 一致；`unit_price` 已在 `VersionedV6Writer` 白名单） |
  | COMPOSITE `writeCombomaterialBomV6`/`insertProcessUnitPriceV6`/`insertCompositeProcessCapacityV6` | 各 groupKey `put("sales_part_no", childPn / parentHfPartNo)`（子件行=子件报价料号，组合工艺行=父报价料号） |
- [ ] **Step 2**：调用点同步传 salesPartNo（Task 1 已传 quotePartNo）。
- [ ] **Step 3**：编译 + 单测 → 提交。

---

## Task 3: material_master 建行 fp=NULL + 主键归属校验

**Files:** 确认 `insertMaterialMasterV6` 调用（3b 已把 fp 实参改 NULL）+ 新增校验测试

- [ ] **Step 1**：确认 `insertMaterialMasterV6` 对选配报价料号：`material_no=quotePartNo`、`config_fingerprint=NULL`、`material_recipe_id` 绑定；复用命中不重复 INSERT（`ON CONFLICT DO NOTHING` 幂等）。
- [ ] **Step 2**：写主键归属断言测试（并入 Task 5）——见 Task 5 T3。
- [ ] **Step 3**：无独立代码改动则跳过提交（校验在 Task 5 测试里）。

---

## Task 4: BACKLOG SPI 化技术债登记

- [ ] **Step 1**：在 `BACKLOG.md` 加一条 P2：「选配落库 `SelPersistHandler` SPI 化」——来源集成设计 §3.1/§3.5 备选 B；推迟原因=一期固定 3 类、switch 足够；前置=加第 4 类参数时。
- [ ] **Step 2**：提交 `docs`。

---

## Task 5: E2E + 单测（强制）

**Files:** `ConfigureProductServiceSalesLandingTest`（后端）+ Playwright 双 spec

- [ ] **Step 1: 后端单测**（清理同 3b，独特料号）：
  - **T1 四 Tab 有数据**：SIMPLE 选配（材质+元素+工序）后，查 `material_master`（material_no=quotePartNo, config_fingerprint IS NULL）、`element_bom_item`（sales_part_no=quotePartNo, 元素齐）、`material_bom_item`（sales_part_no=quotePartNo）、`unit_price`（工序行 sales_part_no=quotePartNo）均有行。
  - **T2 模板未启用工序 → 不落工序**：用只启用 材质+元素 的模板选配，断言 `unit_price` 无该料号工序行（分发按 enabled 生效）。
  - **T3 主键归属**（集成设计 §3.4）：选配 material_master 行 material_no=quotePartNo、不等于任何既有生产料号行；各 V6 QUOTE 行 material_no=sales_part_no=quotePartNo。
  - **T4 核价侧不「共 N 项」**：选配料号在按 sales_part_no 分组的核价视图里返单值（非数组）——守 AP-22/AP-53。
- [ ] **Step 2: Playwright E2E**（主树 dev server 跑，worktree 跑不了）：`quotation-flow.spec.ts` + `composite-product-flow.spec.ts` → 所有 test passed + 8 Tab `'加载中'=0` + **选配产品卡片四 Tab（材质/元素/工序）真有数据**（截图 qf-19 + qf-21~28 作证据）。
- [ ] **Step 3: 提交 + 一行「已自检」声明**（后端 T1-T4 断言 + E2E 双 spec + 四 Tab 数据截图）。

---

## Self-Review（对照集成设计）
- §3.1 分发（备选 A switch，按 enabled）→ T1 ✅
- §3.2 补 sales_part_no（各方法 + COMPOSITE）→ T2 ✅
- §3.3 material_master 建行 fp=NULL 防断链 → T3 + T5.T1 ✅
- §3.4 主键归属（material_no=sales_part_no=quotePartNo，不复用生产料号行）→ T5.T3 ✅
- §3.5 SPI 化入 BACKLOG → T4 ✅
- §4.2 E2E 双 spec + 四 Tab 数据 → T5 ✅（强制）
- 不含：迁移（复用 V311 列）、mirror 视图（不动 DDL）、指纹/发号（3b）
- **风险**：R2 mirror 断链（material_master 未建/sales_part_no 未填/customer_no≠customerCode）—— T5.T1/T4 断言把关
