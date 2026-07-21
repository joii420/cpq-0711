# material_bom_item.characteristic 三态统一 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `material_bom_item.characteristic` 统一为 `RECIPE`(材质) / `ASSEMBLY`(组成件·零件) / `OUTSOURCED`(外购件) 三态，让报价与核价两条线都以它作为唯一判别列。

**Architecture:** 核价侧 `P06MaterialBomHandler` 按 `calc_type` 映射（元素→RECIPE / 材料→ASSEMBLY）；报价侧 `MaterialBomMergeHandler` 从「组成件BOM」sheet 新增的「组成类型」列映射（零件→ASSEMBLY / 外购件→OUTSOURCED），缺列/空值/非法值/决策D冲突一律拒导该行。存量 109 行由 Flyway V344 回填（删 11 行矛盾数据 + 规则回填 + 命中表交叉校正），`cz_view` 核价分支谓词随迁移改为 `= 'RECIPE'`。

**Tech Stack:** Java 17 + Quarkus 3.23.3、Hibernate ORM Panache、Flyway、PostgreSQL 16、JUnit 5 + `@QuarkusTest`

**Spec:** `docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md`

---

## ⚠️ 执行前必读

### 顺序约束：代码先行，迁移后置

Spec §6 风险 B 指出"迁移与代码必须同批原子上线"。**在开发期，这落实为「先改代码（Task 1-4），最后跑迁移（Task 5）」**，理由：

- 本项目的 dev DB（`10.177.152.12/cpq_db`）是**全会话共享**的。若先跑迁移把 `characteristic` 填上值，而 `P06` 的 `childGk` 仍含 `characteristic=null`，则 `loadCurrentGroup`/`flip` 按 `characteristic IS NOT DISTINCT FROM NULL` 过滤 → 匹配不到已迁移的行 → **旧行与新行同时 `is_current=t`（双 current）**，污染共享库。
- 反向顺序（代码先行）是安全的：新代码的 `childGk` 已不含 `characteristic`，能正常匹配到存量 NULL 行并 `flip`；`characteristic` 进入 `CHILD_CONTENT` 后，NULL→新值被判为内容变化，正常升版。

对生产部署而言两者仍在同一次 merge 落地，满足 spec 的原子性要求。

### worktree 与 dev server

- 按 CLAUDE.md「开发流程规范」，本计划**必须**在隔离 worktree 分支内执行（Task 0）。
- worktree 只隔离 git 工作区，**dev server(8081/5174) / DB / node_modules 是共享的**。不要在 worktree 里另起 dev server。
- 后端测试必须在 **worktree 的 `cpq-backend/`** 目录下跑（`./mvnw` 在 `cpq-backend/` 不在仓库根），否则会测到主仓的树、报假绿。

### 已知会失败的存量测试

`MaterialBomMergeHandlerTest` 的 `asmRow` / `asmRow2` 夹具**不含「组成类型」列** → Task 2 落地后这些测试会因"拒导"而失败。这是**预期的语义变更**，不是回归。Task 4 专门处理。特别地：

- `ac5_assemblySheetRow_hitsMaterialNoSet_treatedAsRecipe_notRegistered`（`:249`）**断言的正是决策 D 的 RECIPE 分支**，而本设计把该分支改为拒导 → 该测试的语义必须**反转**，不是简单补列。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java` | 核价物料BOM 导入：按 `calc_type` 写 characteristic | 修改 |
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java` | 报价物料BOM+组成件BOM 合并：解析「组成类型」、校验、写三态、主表推导 | 修改 |
| `cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql` | 存量 109 行回填 + `cz_view` 谓词修正 | 新建 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandlerTest.java` | 核价侧回归（含风险 B） | 修改 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java` | 报价侧回归（含风险 A、决策D 语义反转） | 修改 |

**不在代码交付范围**：报价侧「组成件BOM」Excel 模板文件的「组成类型」列 —— 由业务侧提供新模板（spec §10）。

---

## Task 0: 创建隔离 worktree

**Files:** 无（环境准备）

- [ ] **Step 1: 从当前 HEAD 建 worktree**

⚠️ 本地 master 远领先 origin，**必须基于 `HEAD` 建**，不能用 fresh/origin 基线（否则丢提交）。

```bash
cd /home/joii/project/cpq
git worktree add ../cpq-wt-characteristic HEAD -b feat/bom-item-characteristic-tristate
```

Expected: `Preparing worktree (new branch 'feat/bom-item-characteristic-tristate')`

- [ ] **Step 2: 确认分支与基线**

```bash
cd /home/joii/project/cpq-wt-characteristic && git log --oneline -1 && git branch --show-current
```

Expected: 分支名 `feat/bom-item-characteristic-tristate`，commit 与主仓 HEAD 一致。

> 本计划纯后端改动，不需要软链 `node_modules`，也不需要另起前端 dev server。

---

## Task 1: 核价侧 P06 — 按 calc_type 写 characteristic

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java:39-42`（CHILD_CONTENT）、`:77-89`（childRow）、`:104-108` 与 `:136-140`（childGk）
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandlerTest.java`

- [ ] **Step 1: 写失败测试 — 元素行→RECIPE / 材料行→ASSEMBLY**

在 `P06MaterialBomHandlerTest` 中，先把 `row()` 夹具扩展成可指定 `计算类型`，再加测试。

在 `row(int seq, String qty)` 方法**下方**新增：

```java
    /** 可指定「计算类型」的行夹具（三态统一）。 */
    private SheetRow rowCalc(int seq, String qty, String calcType) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("组成料号", "COMP" + seq); m.put("项次", String.valueOf(seq));
        m.put("组成用量", qty); m.put("损耗率", "0.01"); m.put("不良率", "0.02");
        m.put("计算类型", calcType);
        return new SheetRow(seq, m);
    }

    /** 取当前生效子行的 (component_no, characteristic)，按 component_no 排序。 */
    private List<Object[]> currentChildren() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT component_no, characteristic FROM material_bom_item " +
            "WHERE material_no=:m AND system_type='PRICING' AND is_current=true ORDER BY component_no")
            .setParameter("m", MAT).getResultList();
        return rows;
    }

    private long childCurrentCount() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND system_type='PRICING' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }
```

在类末尾新增测试：

```java
    // ===== 三态统一（spec 2026-07-20）=====

    @Test void elementRow_isRecipe_materialRow_isAssembly() {
        handler.handle(List.of(rowCalc(1, "1.0", "元素"), rowCalc(2, "2.0", "材料")), ctx());

        List<Object[]> rows = currentChildren();
        assertEquals(2, rows.size());
        assertEquals("COMP1", rows.get(0)[0]);
        assertEquals("RECIPE", rows.get(0)[1], "calc_type='元素' → characteristic=RECIPE");
        assertEquals("COMP2", rows.get(1)[0]);
        assertEquals("ASSEMBLY", rows.get(1)[1], "calc_type='材料' → characteristic=ASSEMBLY");
    }

    @Test void nullCalcType_defaultsToAssembly() {
        handler.handle(List.of(row(1, "1.0")), ctx());   // row() 不带「计算类型」
        assertEquals("ASSEMBLY", currentChildren().get(0)[1], "calc_type 缺省按 ASSEMBLY 处理");
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=P06MaterialBomHandlerTest#elementRow_isRecipe_materialRow_isAssembly -q
```

Expected: FAIL — `expected: <RECIPE> but was: <null>`（当前 characteristic 恒 NULL）

- [ ] **Step 3: 实现 — CHILD_CONTENT 加入 characteristic**

`P06MaterialBomHandler.java:39-42`：

```java
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "operation_no", "component_usage_type",
        "composition_qty", "issue_unit", "base_qty", "scrap_rate", "fixed_scrap",
        "defect_rate", "calc_type", "production_no",
        // 三态统一：必须参与内容比较，否则 calc_type 未变而 characteristic 变时
        // multisetEqual 判"无变化"→ 完全不写库（spec §6 风险 A）。
        "characteristic");
```

- [ ] **Step 4: 实现 — childRow 携带 characteristic**

`P06MaterialBomHandler.java` 在 `c.put("production_no", prodNo);`（`:89`）**之后**插入：

```java
            // 三态统一(spec §2)：核价侧判别列由 calc_type 收敛到 characteristic。
            // '元素' → RECIPE(材质)；其余(含 '材料' 与 null) → ASSEMBLY(组成件)。
            c.put("characteristic", "元素".equals(calcType) ? "RECIPE" : "ASSEMBLY");
```

- [ ] **Step 5: 实现 — 从 childGk 移除 characteristic（两处）**

`:104-108`（setBased 分支）删掉 `childGk.put("characteristic", null);` 这一行，改为：

```java
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "PRICING");
                childGk.put("customer_no", PRICING_CUSTOMER);
                childGk.put("material_no", materialNo);
                // 三态统一：characteristic 降为 per-row 内容列，不再作分组键。
                // 若保留，迁移后 loadCurrentGroup/flip 按 IS NOT DISTINCT FROM NULL 过滤将
                // 匹配不到任何行 → 旧行不被下线 → 双 current（spec §6 风险 B）。
```

`:136-140`（非 setBased 分支）同样删掉 `childGk.put("characteristic", null);`：

```java
                    Map<String, Object> childGk = new LinkedHashMap<>();
                    childGk.put("system_type", "PRICING");
                    childGk.put("customer_no", PRICING_CUSTOMER);
                    childGk.put("material_no", materialNo);
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=P06MaterialBomHandlerTest -q
```

Expected: 全部 PASS（原有 3 个 + 新增 2 个 = 5 个）

- [ ] **Step 7: 加风险 B 回归测试（存量 NULL 行必须被 flip）**

在 `P06MaterialBomHandlerTest` 末尾追加：

```java
    @Transactional void forceChildCharacteristicNull() {
        em.createNativeQuery(
            "UPDATE material_bom_item SET characteristic = NULL " +
            "WHERE material_no=:m AND system_type='PRICING'")
            .setParameter("m", MAT).executeUpdate();
    }

    /**
     * spec §6 风险 B 回归：模拟"存量 characteristic=NULL 的行遇到新代码"。
     * childGk 若仍含 characteristic=null，flip 匹配不到已有值的行 → 双 current。
     * 此处反向验证：把行抹成 NULL 后再导，新代码必须能匹配并下线旧行。
     */
    @Test void legacyNullCharacteristicRows_areFlipped_noDoubleCurrent() {
        handler.handle(List.of(rowCalc(1, "1.0", "材料")), ctx());
        assertEquals(1L, childCurrentCount());

        forceChildCharacteristicNull();          // 退回迁移前状态
        handler.handle(List.of(rowCalc(1, "1.0", "材料")), ctx());

        assertEquals(1L, childCurrentCount(),
            "风险B: 旧 NULL 行必须被 flip 下线，不能与新行并存为双 current");
        assertEquals("ASSEMBLY", currentChildren().get(0)[1]);
    }
```

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=P06MaterialBomHandlerTest -q
```

Expected: 6 个测试全 PASS

- [ ] **Step 9: 提交**

```bash
cd /home/joii/project/cpq-wt-characteristic
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandlerTest.java
git commit -m "feat(v6import): 核价物料BOM 按 calc_type 写 characteristic 三态(元素→RECIPE/材料→ASSEMBLY)

- CHILD_CONTENT 加入 characteristic(spec §6 风险A: 否则内容比较漏检致静默不写库)
- childGk 移除 characteristic 两处(spec §6 风险B: 否则迁移后 flip 失效产生双 current)"
```

---

## Task 2: 报价侧 —「组成类型」列解析与校验

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java:104-154`（组成件分支）+ 新增私有方法
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java`

- [ ] **Step 1: 写失败测试 — 组成类型映射与拒导**

在 `MaterialBomMergeHandlerTest` 的 `asmRow(...)` 方法**下方**新增夹具：

```java
    /** 带「组成类型」的组成件行夹具（三态统一）。kind 传 null 表示该列缺失。 */
    private SheetRow asmRowKind(int rowNo, int seq, String comp, String qty, String kind) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        if (kind != null) m.put("组成类型", kind);
        return new SheetRow(rowNo, m);
    }

    /** 取当前生效子行的 characteristic（断言唯一行时用）。 */
    private String currentChildCharacteristic() {
        return (String) em.createNativeQuery(
            "SELECT characteristic FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult();
    }
```

在类末尾追加测试：

```java
    // ===== 三态统一（spec 2026-07-20）=====

    @Test
    void componentKind_零件_mapsToAssembly() {
        cleanupRepair2Master("TRI-PART-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-PART-1", "1", "零件")), ctx());
            assertEquals("ASSEMBLY", currentChildCharacteristic(), "组成类型=零件 → ASSEMBLY");
        } finally {
            cleanupRepair2Master("TRI-PART-1");
        }
    }

    @Test
    void componentKind_外购件_mapsToOutsourced() {
        cleanupRepair2Master("TRI-OUT-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-OUT-1", "1", "外购件")), ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(), "组成类型=外购件 → OUTSOURCED");
        } finally {
            cleanupRepair2Master("TRI-OUT-1");
        }
    }

    @Test
    void componentKind_missing_rejectsRow() {
        SheetImportResult r = handler.merge(
            List.of(), List.of(asmRowKind(1, 1, "TRI-MISS-1", "1", null)), ctx());
        assertTrue(r.failedRows >= 1, "组成类型列缺失应拒导");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"),
            "拒导行不应落库");
    }

    @Test
    void componentKind_illegalValue_rejectsRow() {
        SheetImportResult r = handler.merge(
            List.of(), List.of(asmRowKind(1, 1, "TRI-BAD-1", "1", "半成品")), ctx());
        assertTrue(r.failedRows >= 1, "组成类型非法值应拒导");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest#componentKind_外购件_mapsToOutsourced -q
```

Expected: FAIL — `expected: <OUTSOURCED> but was: <ASSEMBLY>`

- [ ] **Step 3: 实现 — 新增映射工具方法**

在 `MaterialBomMergeHandler.java` 的 `isCfg(...)` 方法（`:291-293`）**下方**插入：

```java
    /**
     * 「组成类型」→ characteristic 映射（三态统一，spec §3）。
     * 值域仅 {零件, 外购件}；null / 空 / 非法值统一返回 null，由调用方按拒导处理。
     */
    static String kindToCharacteristic(String kind) {
        if (kind == null) return null;
        String t = kind.trim();
        if ("零件".equals(t))   return "ASSEMBLY";
        if ("外购件".equals(t)) return "OUTSOURCED";
        return null;
    }
```

- [ ] **Step 4: 实现 — 组成件分支读列 + 校验 + 决策D 拒导**

把 `MaterialBomMergeHandler.java:109-131` 这段：

```java
            String componentName = row.getStr("组成件名称");
            String rawAssemblyComponent = row.exact("组成件料号");
            // 决策 D：组成件料号命中"本次导入材质料号集"(物料BOM ∪ 物料与元素BOM 的材质料号) →
            // 按材质料号处理(原始码/不登记 master/RECIPE)，不再 resolve/铸号；否则维持原真组成件路径。
            boolean isMaterialHit = rawAssemblyComponent != null && matNoSet.contains(rawAssemblyComponent);
            String componentNo;
            String childCharacteristic;
            if (isMaterialHit) {
                componentNo = rawAssemblyComponent;
                childCharacteristic = "RECIPE";
            } else {
                try {
                    componentNo = materialNoResolver.resolve(rawAssemblyComponent, componentName, batch);
                } catch (MaterialNoUnresolvableException ex) {
                    result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
                } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                    result.recordError(row.rowNo, "组成件料号", "报价料号跨客户串号"); continue;
                }
                // §12 料号表同步：组成件 material_type 固定存汉字「组成件」，已存在保留原值（preserveDescriptive=true）。延后批量。
                accMaterialMaster(mmAcc, componentNo, componentName, "组成件");
                result.recordWrite("material_master", 1);
                childCharacteristic = "ASSEMBLY";
            }
```

**整段替换为**：

```java
            String componentName = row.getStr("组成件名称");
            String rawAssemblyComponent = row.exact("组成件料号");

            // 三态统一(spec §3)：「组成类型」必填，值域 {零件, 外购件}。
            // exact() 对"整列缺失"与"单元格为空"均返 null，两者都按拒导处理。
            String compKind = row.exact("组成类型");
            String childCharacteristic = kindToCharacteristic(compKind);
            if (childCharacteristic == null) {
                result.recordError(row.rowNo, "组成类型",
                    compKind == null ? "为空或该列缺失(必填，值域: 零件/外购件)"
                                     : "非法值: " + compKind + "(值域: 零件/外购件)");
                continue;
            }

            // 决策 D 冲突(spec §4.2-2)：料号命中"本次导入材质料号集"说明它是材质料号，
            // 而「组成类型」值域不含材质 → 语义矛盾，拒导该行。
            // repair-2 的防线仍有效(材质料号不会被铸报价料号/不登记 material_master)，
            // 只是从"静默纠正为 RECIPE"变为"显式报错"。
            if (rawAssemblyComponent != null && matNoSet.contains(rawAssemblyComponent)) {
                result.recordError(row.rowNo, "组成件料号",
                    "该料号是本次导入的材质料号，不能出现在组成件BOM(组成类型=" + compKind + ")");
                continue;
            }

            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(rawAssemblyComponent, componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                result.recordError(row.rowNo, "组成件料号", "报价料号跨客户串号"); continue;
            }
            // §12 料号表同步：组成件 material_type 固定存汉字「组成件」，已存在保留原值（preserveDescriptive=true）。延后批量。
            accMaterialMaster(mmAcc, componentNo, componentName, "组成件");
            result.recordWrite("material_master", 1);
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest#componentKind_零件_mapsToAssembly+componentKind_外购件_mapsToOutsourced+componentKind_missing_rejectsRow+componentKind_illegalValue_rejectsRow -q
```

Expected: 4 个新测试全 PASS（存量测试此时仍会失败，Task 4 处理）

> **不要改动** `:192-196` 与 `:250-254` 的决策 D 归并兜底（`matNoSet.contains(key) → 强制 RECIPE`）。
> 组成件行命中材质料号集后已在行级被拒导，不会进入 `asmByMat`；物料BOM 行本就是 RECIPE。
> 该兜底因此成为纯防御性代码，保留无害（spec §4.2-5）。

- [ ] **Step 6: 提交**

```bash
cd /home/joii/project/cpq-wt-characteristic
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java
git commit -m "feat(v6import): 报价组成件BOM 新增「组成类型」列解析(零件→ASSEMBLY/外购件→OUTSOURCED)

缺列/空值/非法值一律拒导; 决策D 冲突(料号命中本次材质料号集)由静默纠正改为显式报错"
```

---

## Task 3: 报价侧 — 主表推导纳入 OUTSOURCED + CHILD_CONTENT

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java:47-51`（CHILD_CONTENT）、`:201`、`:261`（主表推导）、`:257-260`（失效注释）
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java`

- [ ] **Step 1: 写失败测试 — 风险 A 回归 + 纯外购件主表推导**

在 `MaterialBomMergeHandlerTest` 末尾追加：

```java
    /**
     * spec §6 风险 A 回归：仅「组成类型」变化、其余列全同。
     * characteristic 若不在 CHILD_CONTENT，multisetEqual 判"无变化"→ 完全不写库 → 改动静默丢失。
     */
    @Test
    void componentKindChange_partToOutsourced_isDetectedAsContentChange() {
        cleanupRepair2Master("TRI-CHG-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-CHG-1", "1", "零件")), ctx());
            assertEquals("ASSEMBLY", currentChildCharacteristic());

            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-CHG-1", "1", "外购件")), ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(),
                "风险A: characteristic 必须在 CHILD_CONTENT 内，否则内容比较判'无变化'而完全不写库");
        } finally {
            cleanupRepair2Master("TRI-CHG-1");
        }
    }

    /**
     * spec §4.2-3：只有外购件子行的料号，主表仍应判 ASSEMBLY，
     * 使走主表 characteristic 的 ll_view(来料) 能捞到它。
     */
    @Test
    void outsourcedOnly_masterStillAssembly() {
        cleanupRepair2Master("TRI-OUT-ONLY");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-OUT-ONLY", "1", "外购件")), ctx());

            Object[] master = (Object[]) em.createNativeQuery(
                "SELECT bom_type, characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("ASSEMBLY", master[0], "纯外购件料号 bom_type 应为 ASSEMBLY");
            assertEquals("ASSEMBLY", master[1], "纯外购件料号主表 characteristic 应为 ASSEMBLY");
        } finally {
            cleanupRepair2Master("TRI-OUT-ONLY");
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest#componentKindChange_partToOutsourced_isDetectedAsContentChange+outsourcedOnly_masterStillAssembly -q
```

Expected: 两个都 FAIL —
- 前者 `expected: <OUTSOURCED> but was: <ASSEMBLY>`（内容比较漏检，未写库）
- 后者 `expected: <ASSEMBLY> but was: <MATERIAL>`（主表推导只认 ASSEMBLY）

- [ ] **Step 3: 实现 — CHILD_CONTENT 加入 characteristic**

`MaterialBomMergeHandler.java:47-51` 替换为：

```java
    /** 合并后子表内容列 = 物料BOM ∪ 组成件BOM。 */
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq",
        "rough_weight", "net_weight", "weight_unit",
        // 三态统一：必须参与内容比较，否则仅「组成类型」变化时
        // multisetEqual 判"无变化"→ 完全不写库（spec §6 风险 A）。
        "characteristic");
```

- [ ] **Step 4: 实现 — 主表推导纳入 OUTSOURCED（两处）**

`:201`（setBased 分支）：

```java
                // 主表级 characteristic/bom_type：按"归并后是否含真实组成件子行"判定(§3.2)。
                // 三态统一：外购件(OUTSOURCED)同样算组成件，使纯外购件料号仍被 ll_view(走主表)捞到。
                boolean isAssembly = childRows.stream().anyMatch(r ->
                    "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
```

`:261`（非 setBased 分支）同样改：

```java
                    // 三态统一：外购件(OUTSOURCED)同样算组成件（spec §4.2-3）。
                    boolean isAssembly = childRows.stream().anyMatch(r ->
                        "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
```

- [ ] **Step 5: 实现 — 更新已失效的注释**

`:257-260` 的注释解释的是"characteristic 为何不加入 CHILD_CONTENT"，现已反转。替换为：

```java
                    // per-component characteristic：每个子行已在构建阶段(matByMat/asmByMat)携带自身
                    // characteristic（RECIPE/ASSEMBLY/OUTSOURCED），此处不再整体覆盖（决策 C）。
                    // 三态统一后 characteristic **已加入** CHILD_CONTENT：存量 NULL 由 V344 迁移一次性回填，
                    // 原先"怕 NULL→新值触发空升版"的顾虑消失；反之若不纳入，
                    // 仅「组成类型」变化会被判为无内容变化而静默丢失（spec §6 风险 A）。
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest#componentKindChange_partToOutsourced_isDetectedAsContentChange+outsourcedOnly_masterStillAssembly -q
```

Expected: 两个都 PASS

- [ ] **Step 7: 提交**

```bash
cd /home/joii/project/cpq-wt-characteristic
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java
git commit -m "feat(v6import): 外购件纳入主表 ASSEMBLY 推导 + characteristic 进 CHILD_CONTENT

- 主表推导两处改为 ASSEMBLY||OUTSOURCED,使纯外购件料号仍被 ll_view(走主表)捞到
- CHILD_CONTENT 加入 characteristic(spec §6 风险A 回归测试覆盖)"
```

---

## Task 4: 存量测试语义对齐

**Files:**
- Modify: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java:50-55`（asmRow）、`:120-125`（asmRow2）、`:248-277`（ac5 语义反转）

⚠️ 这些失败是**预期的语义变更**，不是回归。修改前先跑一遍确认失败清单，避免把真回归混进来。

- [ ] **Step 1: 记录当前失败清单（A/B 归因基线）**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest -q 2>&1 | tail -40
```

Expected: 失败的应**只有**这 5 个（全部因组成件行缺「组成类型」被拒导）：
`sameMaterialInBothSheets_collapsesToOneAssemblyCurrentRow`、`materialOnlyThenBoth_flipsNullToHistory`、`reclassify_materialToAssembly_shouldBumpVersion_notReset`、`ac5_assemblySheetRow_hitsMaterialNoSet_treatedAsRecipe_notRegistered`、`ac6_assemblySheetRow_realComponent_stillAssembly_registeredAndResolved`、`ac4_mixedRecipeAndAssembly_perComponentCharacteristic_notOverwritten`

若出现清单外的失败 → 是真回归，先停下来查。

- [ ] **Step 2: 给 asmRow 夹具补「组成类型」默认值**

`:50-55` 替换为：

```java
    private SheetRow asmRow(int rowNo, int seq, String comp, String qty) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        m.put("组成类型", "零件");   // 三态统一：该列必填，默认零件以保持原测试语义(ASSEMBLY)
        return new SheetRow(rowNo, m);
    }
```

`:120-125` 的 `asmRow2` 同样补：

```java
    private SheetRow asmRow2(int seq, String comp, String qty) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("宏丰料号", MAT2); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        m.put("组成类型", "零件");   // 三态统一：该列必填
        return new SheetRow(seq, m);
    }
```

- [ ] **Step 3: 反转 ac5 测试语义（决策 D 由纠正改为拒导）**

`:248-277` 的 `ac5_assemblySheetRow_hitsMaterialNoSet_treatedAsRecipe_notRegistered` **整个方法**替换为：

```java
    @Test
    void ac5_assemblySheetRow_hitsMaterialNoSet_isRejected() {
        cleanupRepair2Master("R2-MAT-992");
        try {
            // 三态统一(spec §4.2-2)：组成件BOM 里出现的组件码若命中"本次导入材质料号集"，
            // 与「组成类型」值域(零件/外购件)语义矛盾 → 拒导该行。
            // 原 repair-2 行为是静默纠正为 RECIPE；现改为显式报错，防线等价但可见。
            ImportContext c = ctxWithMatSet("R2-MAT-992");
            SheetImportResult r = handler.merge(
                List.of(), List.of(asmRow(1, 1, "R2-MAT-992", "1")), c);

            assertTrue(r.failedRows >= 1, "命中材质料号集的组成件行应被拒导");
            assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"),
                "拒导行不应落库");

            long masterCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no=:c")
                .setParameter("c", "R2-MAT-992").getSingleResult()).longValue();
            assertEquals(0L, masterCount,
                "repair-2 防线仍有效: 材质料号不得登记 material_master(森萨塔跨客户串号根因)");
        } finally {
            cleanupRepair2Master("R2-MAT-992");
        }
    }
```

- [ ] **Step 4: 修正 ac4 测试（混合场景的材质行来源改为物料BOM）**

`ac4_mixedRecipeAndAssembly_perComponentCharacteristic_notOverwritten`（`:302`）的 `matRow` 走物料BOM（仍产 RECIPE）、`asmRow` 走组成件BOM（Step 2 后带「组成类型=零件」→ ASSEMBLY），**语义不变，无需改动**。Step 2 补列后应自动转绿。

本步骤仅需验证：

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest#ac4_mixedRecipeAndAssembly_perComponentCharacteristic_notOverwritten -q
```

Expected: PASS

- [ ] **Step 5: 跑全量测试确认全绿**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMergeHandlerTest -q
```

Expected: 全部 PASS（原 8 个 + 新增 6 个 = 14 个，其中 ac5 语义已反转）

- [ ] **Step 6: 跑相邻测试类确认无溢出影响**

```bash
cd /home/joii/project/cpq-wt-characteristic/cpq-backend
./mvnw test -Dtest=MaterialBomMaterialNoResolveTest+P06MaterialMasterSyncTest -q
```

Expected: 全 PASS（这两个类只用 `matRow`/物料BOM 路径，不受组成件分支改动影响）

- [ ] **Step 7: 提交**

```bash
cd /home/joii/project/cpq-wt-characteristic
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java
git commit -m "test(v6import): 存量测试对齐三态语义(asmRow 补组成类型 + ac5 决策D 由纠正反转为拒导)"
```

---

## Task 5: Flyway V344 存量迁移 + cz_view 谓词

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql`

⚠️ **禁止手工 `psql -f`**。让 Quarkus dev 自己跑 Flyway（`migrate-at-start=true` 已配置）。

⚠️ V344 是本会话开始时的空号（DB 已应用最大为 V343）。**执行前先复核**——共享 flyway 历史是移动靶，可能已被并发会话占用。

- [ ] **Step 1: 复核版本号未被占用**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -t \
  -c "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
ls /home/joii/project/cpq-wt-characteristic/cpq-backend/src/main/resources/db/migration/ | grep -E '^V34' | sort
```

Expected: 最大版本 < 344 且 `V344__*` 文件不存在。若已被占用，改用下一个空号并同步更新本任务后续所有引用。

- [ ] **Step 2: 记录迁移前基线（用于 Step 6 比对）**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT count(*) AS 总行数 FROM material_bom_item;
SELECT system_type, coalesce(characteristic,'(NULL)') AS ch, count(*)
FROM material_bom_item GROUP BY 1,2 ORDER BY 1,2;"
```

Expected（本计划撰写时的基线，实际以运行结果为准）：总行数 109；PRICING (NULL) 64；QUOTE ASSEMBLY 13 / (NULL) 30 / RECIPE 2。

- [ ] **Step 3: 创建迁移文件**

创建 `cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql`：

```sql
-- V344: material_bom_item.characteristic 三态统一（RECIPE/ASSEMBLY/OUTSOURCED）
-- spec: docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md
--
-- 存量回填 + cz_view 核价分支谓词修正。
-- ⚠️ 必须与同批代码改动（P06 childGk 移除 characteristic）一同上线，
--    否则迁移后 loadCurrentGroup/flip 匹配不到行 → 双 current（spec §6 风险 B）。

-- ── 步骤 1: 删除语义矛盾的存量行（spec §5.1）──
-- PRICING + calc_type='元素' 但 component_no 命中料号主档、不命中材质库。
-- 已核实这批码既不在 material_recipe 也不在 element 主表，名称为"料2/料10"等测试数据，
-- 规则(元素→RECIPE)与命中表校正(料号→ASSEMBLY)给出相反答案 → 不猜，直接删。
DELETE FROM material_bom_item i
WHERE i.system_type = 'PRICING'
  AND i.calc_type = '元素'
  AND i.component_no IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no)
  AND EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no);

-- ── 步骤 2: 规则回填（spec §5.2）──
-- 核价：calc_type 权威（'元素'→RECIPE，其余含 NULL→ASSEMBLY）
UPDATE material_bom_item
SET characteristic = CASE WHEN calc_type = '元素' THEN 'RECIPE' ELSE 'ASSEMBLY' END
WHERE system_type = 'PRICING'
  AND component_no IS NOT NULL;

-- 报价：存量 NULL 行来自 repair-2 之前的物料BOM 导入，语义等同 RECIPE
UPDATE material_bom_item
SET characteristic = 'RECIPE'
WHERE system_type = 'QUOTE'
  AND characteristic IS NULL
  AND component_no IS NOT NULL;

-- ── 步骤 3: 命中表交叉校正（spec §5.3，仅报价侧）──
-- ⚠️ uq_material_bom_item 含 COALESCE(characteristic,'')。存量有 3 组历史重复行
--   （同一 component 同时在物料BOM 与组成件BOM，归并逻辑上线前写成两行，
--     靠 characteristic 不同才不撞键：0317-2607000006/1、0363-2607000009/1、0363-2607000009/2）。
--   若无差别校正，NULL 行会被校正成 ASSEMBLY 与兄弟行同键 → 唯一键冲突、迁移失败。
-- 规则：交叉校正只作用于"唯一键去掉 characteristic 后无兄弟行"的行；
--   有兄弟行者保持步骤 2 的 RECIPE，与兄弟行的 ASSEMBLY 天然区分。
-- 实际洗净 2 行：S-3120014539 的 991/992（标 ASSEMBLY 但在材质库）→ RECIPE。
-- OUTSOURCED 不参与校正（存量为 0，防御性排除，避免误翻新导入的外购件行）。
UPDATE material_bom_item i
SET characteristic = CASE
    WHEN EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no) THEN 'RECIPE'
    WHEN EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no) THEN 'ASSEMBLY'
    ELSE i.characteristic
  END
WHERE i.system_type = 'QUOTE'
  AND i.component_no IS NOT NULL
  AND i.characteristic IS DISTINCT FROM 'OUTSOURCED'
  AND NOT EXISTS (
    SELECT 1 FROM material_bom_item j
    WHERE j.id <> i.id
      AND j.system_type = i.system_type
      AND j.customer_no = i.customer_no
      AND j.material_no = i.material_no
      AND COALESCE(j.bom_version, '')  = COALESCE(i.bom_version, '')
      AND COALESCE(j.seq_no, 0)        = COALESCE(i.seq_no, 0)
      AND COALESCE(j.component_no, '') = COALESCE(i.component_no, '')
      AND COALESCE(j.part_no, '')      = COALESCE(i.part_no, '')
  );

-- 注：component_no IS NULL 的空壳历史行（is_current=f）不参与迁移，characteristic 保持 NULL（spec §5.4）。

-- ── 步骤 4: cz_view 核价分支谓词（spec §7）──
-- 改前谓词 characteristic IS NULL：核价全部行都是 NULL → 等价"全通过"，
-- 致「材质」页签混显元素行+材料行。回填后该谓词恒不命中 → 页签会空。
-- 收敛为 = 'RECIPE'，「材质」页签只显示元素行。
-- 全库仅 1 个模板含该串（已核实），replace 安全。
UPDATE component_sql_view
SET sql_template = replace(sql_template,
        'AND asy.characteristic IS NULL',
        'AND asy.characteristic = ''RECIPE'''),
    updated_at = now()
WHERE sql_view_name = 'cz_view'
  AND sql_template LIKE '%AND asy.characteristic IS NULL%';
```

- [ ] **Step 4: 记录双 current 基线（迁移前，供 Task 6 做 A/B 对比）**

组成件BOM 允许同 `component_no` 多 occurrence（存量 `3120012530` 就有重复行），**迁移前该值可能已非 0**。必须先取基线，否则 Task 6 会把既有重复误判为本次引入。

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT count(*) AS 双current组数基线 FROM (
  SELECT system_type, customer_no, material_no, component_no
  FROM material_bom_item WHERE is_current
  GROUP BY 1,2,3,4 HAVING count(*) > 1
) t;"
```

把结果记下来，Task 6 Step 6 要用。

- [ ] **Step 5: 不要在本任务执行迁移**

⚠️ **本任务只写 SQL 文件，不跑迁移。**

理由：dev server(8081) 是全会话共享的，跑的是**主工作区 master 的旧代码**（`P06` 的 `childGk` 仍含 `characteristic=null`）。若此刻在 worktree 用临时端口把迁移跑掉，共享 DB 的数据就已迁移，而 8081 上任何一次导入都会命中 spec §6 风险 B → 双 current 污染共享库。

迁移的执行与验证统一放到 Task 6 合并之后，让代码与迁移**真正同批落地**（这正是 spec §6 要求的原子性）。

本步骤仅做静态检查：

```bash
cd /home/joii/project/cpq-wt-characteristic
test -f cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql && echo "文件已创建"
grep -c "UPDATE\|DELETE" cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql
```

Expected: `文件已创建`；语句数 `4`（1 DELETE + 3 UPDATE）

- [ ] **Step 6: 提交**

```bash
cd /home/joii/project/cpq-wt-characteristic
git add cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql
git commit -m "feat(migration): V344 characteristic 三态存量回填 + cz_view 核价分支谓词收敛为 RECIPE

删 11 行语义矛盾数据(元素行但料号命中主档不命中材质库) + 规则回填 + 报价侧命中表交叉校正洗净 5 行脏数据"
```

---

## Task 6: 集成验证与自检

**Files:** 无（验证）

- [ ] **Step 1: 合并回 master 并让共享 dev server 执行迁移**

⚠️ 严禁 `git add -A`（并发会话会交错）。前置：确认 Task 1-5 全部提交完毕。

```bash
cd /home/joii/project/cpq
git merge feat/bom-item-characteristic-tristate
touch cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java
```

等待 5-7 秒让 Quarkus dev 热重载 + 跑 Flyway。

- [ ] **Step 2: 确认 Flyway V344 已执行且成功**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -t -c \
  "SELECT version, success FROM flyway_schema_history WHERE version = '344';"
```

Expected: `344 | t`

若无输出，说明热重载未触发 Flyway —— 再 `touch` 一次 java 文件并等待 8 秒。

- [ ] **Step 3: 验证迁移结果（spec §5.5）**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
-- 期望 0：component_no 非空却仍 NULL
SELECT count(*) AS 残留NULL FROM material_bom_item
WHERE component_no IS NOT NULL AND characteristic IS NULL;

-- 期望（全量）：PRICING RECIPE=8 / ASSEMBLY=43；QUOTE RECIPE=34 / ASSEMBLY=11
SELECT system_type, characteristic, count(*) FROM material_bom_item
WHERE component_no IS NOT NULL GROUP BY 1,2 ORDER BY 1,2;

-- 期望（is_current=t）：PRICING RECIPE=5 / ASSEMBLY=18；QUOTE RECIPE=30 / ASSEMBLY=11
SELECT system_type, characteristic, count(*) FROM material_bom_item
WHERE component_no IS NOT NULL AND is_current GROUP BY 1,2 ORDER BY 1,2;

-- 期望 0：迁移不得引入唯一键冲突（uq_material_bom_item 含 COALESCE(characteristic,'')）
SELECT count(*) AS 撞键组数 FROM (
  SELECT 1 FROM material_bom_item WHERE component_no IS NOT NULL
  GROUP BY system_type, customer_no, material_no, COALESCE(characteristic,''),
           COALESCE(bom_version,''), COALESCE(seq_no,0),
           COALESCE(component_no,''), COALESCE(part_no,'')
  HAVING count(*) > 1
) t;

-- 期望：总行数 = Task 5 Step 2 基线 - 11
SELECT count(*) AS 迁移后总行数 FROM material_bom_item;

-- 与 Task 5 Step 4 基线比对，必须相等（不是必须为 0）
SELECT count(*) AS 双current组数 FROM (
  SELECT system_type, customer_no, material_no, component_no
  FROM material_bom_item WHERE is_current
  GROUP BY 1,2,3,4 HAVING count(*) > 1
) t;

-- 期望 1：cz_view 谓词已改
SELECT count(*) AS cz_view已改 FROM component_sql_view
WHERE sql_view_name='cz_view' AND sql_template LIKE '%asy.characteristic = ''RECIPE''%';"
```

> ⚠️ 「双 current 组数」与 **Task 5 Step 4 的基线相等**即通过，不要求为 0。

- [ ] **Step 4: 后端存活自检**

```bash
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components
```

Expected: `401`（应用在跑、鉴权正常。**不要**用 `/q/health`——未装 smallrye-health，返 404 不是健康探针）

- [ ] **Step 5: 重启 Quarkus 清进程级缓存**

V344 改了 `component_sql_view` 模板，`CachedSqlCompiler` / `CachedPathParser` 是 ApplicationScoped 进程级缓存。

```bash
cd /home/joii/project/cpq
touch cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java
sleep 8
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components
```

Expected: `401`

- [ ] **Step 6: 跑全量相关测试**

```bash
cd /home/joii/project/cpq/cpq-backend
./mvnw test -Dtest='MaterialBomMergeHandlerTest+P06MaterialBomHandlerTest+MaterialBomMaterialNoResolveTest+P06MaterialMasterSyncTest' -q
```

Expected: 全 PASS

- [ ] **Step 7: 核价「材质」页签渲染验证**

用 `cz_view` 驱动的「材质」组件（4 个 ACTIVE）打开一张核价单，确认：
- 页签渲染行数 = **5**（`is_current=t` 的 RECIPE 行）
- 无「加载中…」残留
- 无「X (共N项)」错乱（AP-22）

- [ ] **Step 8: 报价侧回归验证**

打开一张报价单，确认「来料」「子配件」「元素」三个页签无回归（行数、值与迁移前一致）。

> 注：外购件本期在「来料」页签会与零件混显且不可区分（spec §9.1 已知取舍，转 BL-0064），这是**预期行为**不是缺陷。

- [ ] **Step 9: 重导幂等验证（风险 B 实证）**

用一份核价 Excel 走导入端点重导一次，随后：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT count(*) AS 双current组数 FROM (
  SELECT system_type, customer_no, material_no, component_no
  FROM material_bom_item WHERE is_current AND system_type='PRICING'
  GROUP BY 1,2,3,4 HAVING count(*) > 1
) t;"
```

Expected: `0` —— 若非 0，说明 Task 1 Step 5 的 childGk 改动未生效，**立即回滚排查**。

- [ ] **Step 10: 更新 RECORD.md**

按 CLAUDE.md「开发记录」要求追加一行：

```
[2026-07-20] 基础数据V6 - material_bom_item.characteristic 三态统一(RECIPE/ASSEMBLY/OUTSOURCED) | P06MaterialBomHandler.java / MaterialBomMergeHandler.java / V344 | 核价按calc_type映射;报价组成件BOM新增「组成类型」列严格校验;characteristic进CHILD_CONTENT(否则组成类型变更静默丢失);P06 childGk移除characteristic(否则迁移后双current);cz_view核价分支谓词→='RECIPE'致材质页签30→5行;外购件展示转BL-0064
```

- [ ] **Step 11: 提交并清理 worktree**

```bash
cd /home/joii/project/cpq
git add docs/RECORD.md && git commit -m "docs(record): characteristic 三态统一交付记录"
git worktree remove ../cpq-wt-characteristic
git branch -d feat/bom-item-characteristic-tristate
```

---

## 自检清单（对应 spec §10）

- [ ] V344 `success=t`
- [ ] 残留 NULL = 0（`component_no` 非空口径）
- [ ] 全量分布：PRICING RECIPE=8 / ASSEMBLY=43；QUOTE RECIPE=34 / ASSEMBLY=11
- [ ] 当前行分布：PRICING RECIPE=5 / ASSEMBLY=18；QUOTE RECIPE=30 / ASSEMBLY=11
- [ ] 唯一键撞键组数 = 0（`uq_material_bom_item` 含 `COALESCE(characteristic,'')`）
- [ ] 总行数差 = 11（仅删除，无意外增减）
- [ ] 双 current 组数与迁移前基线一致（A/B 对比，非绝对 0）
- [ ] Quarkus 重启后 `/api/cpq/components` → 401
- [ ] 核价「材质」页签 = 5 行，无「加载中…」
- [ ] 核价「来料」「子配件」「元素」页签无回归
- [ ] 重导核价 Excel 后 PRICING 侧双 current = 0
- [ ] 重导报价 Excel（新模板带「组成类型」）：ASSEMBLY/OUTSOURCED 正确落库
- [ ] 旧模板报价 Excel 导入：整表拒导且错误信息可读
- [ ] 4 个测试类全 PASS

**E2E**：本计划未触及 `docs/E2E测试方法.md` 列举的协议级前端文件（`useDriverExpansions.ts` / `QuotationStep2.tsx` 等），但改动了 BOM driver 数据源与视图谓词。建议跑 `quotation-flow.spec.ts` 做回归对照。

> ⚠️ 干净 master 上该 spec 已有 **3 个因夹具漂移的既有失败**（记忆 `task0712-update071501-category-axis`）。判定是否回归**必须 A/B 同型对比**（同一份 spec 在合并前后各跑一次），不要看到失败就归因本次改动。
