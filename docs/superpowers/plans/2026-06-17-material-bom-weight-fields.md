# 物料BOM 子表毛重/净重/单位独立落库 + 产出料号类型存汉字 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把报价「物料BOM」Sheet 的材料毛重/净重/单位改存到 `material_bom_item` 专用新列 `rough_weight`/`net_weight`/`weight_unit`，并把「产出料号类型」统一存汉字标签。

**Architecture:** 后端 Quarkus + Flyway。新增 3 列（DDL + 实体 + 写入器内容列白名单 `CHILD_CONTENT`），改 `MaterialBomMergeHandler` 物料BOM 循环的字段映射，新增 `labelOnly` helper 剥离类型编号。本次只改落库，不动显示视图 `v12_raw_bom`，不写存量迁移。

**Tech Stack:** Java 17、Quarkus 3.23、Hibernate Panache、PostgreSQL 16、Flyway、JUnit5 + `@QuarkusTest`。

**关联 spec：** `docs/superpowers/specs/2026-06-17-material-bom-weight-fields-design.md`

---

## 文件结构（创建/修改清单）

- **创建** `cpq-backend/src/main/resources/db/migration/V300__material_bom_item_weight_fields.sql` — 加 3 列
- **创建** `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/LabelOnlyTest.java` — `labelOnly` 纯单测
- **修改** `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/MaterialBomItem.java` — 加 3 实体字段
- **修改** `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java` — `CHILD_CONTENT` + 物料BOM 循环映射 + 组成件 material_type + 新 helper
- **修改** `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java` — 加新列断言
- **修改** `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java` — 更新 3 处 material_type 期望
- **修改** `docs/table/报价系统Excel导入落库方案.md` — §3 备注回写
- **修改** `docs/RECORD.md` — 开发记录

> **重要环境纪律（CLAUDE.md / 记忆 `cpq-worktree-maven-test-tree`）：**
> - 所有 Maven 命令在**本 worktree 的 `cpq-backend/`** 内跑（`./mvnw` 在 `cpq-backend/`，不在仓库根），不要 cd 主仓。
> - 不在 worktree 另起 dev server / 重装依赖（共享主工作区实例）。`@QuarkusTest` 会自起独立测试实例并跑 Flyway，是本计划的主验证手段。
> - schema DDL 改动后若要手测 dev server，需 `touch` 一个 java 文件强制重启——但本计划以 `@QuarkusTest` 为准，不依赖共享 dev server。

---

## Task 1: 新增 3 列（迁移 + 实体）

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V300__material_bom_item_weight_fields.sql`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/MaterialBomItem.java`（在 `base_qty` 字段后，约第 58 行后）

- [ ] **Step 1: 写迁移 SQL**

创建 `V300__material_bom_item_weight_fields.sql`，内容：

```sql
-- V300: 物料BOM 子表新增材料毛重/净重/重量单位专用列
-- 关联: docs/superpowers/specs/2026-06-17-material-bom-weight-fields-design.md
-- 背景: 原先这三列借住 composition_qty/base_qty/issue_unit，本次改存专用列。
ALTER TABLE material_bom_item
    ADD COLUMN IF NOT EXISTS rough_weight DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS net_weight   DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS weight_unit  VARCHAR(20);

COMMENT ON COLUMN material_bom_item.rough_weight IS '材料毛重（物料BOM Sheet，V300 前借住 composition_qty）';
COMMENT ON COLUMN material_bom_item.net_weight   IS '材料净重（物料BOM Sheet，V300 前借住 base_qty）';
COMMENT ON COLUMN material_bom_item.weight_unit  IS '重量单位（物料BOM Sheet，V300 前借住 issue_unit）';
```

> 注：动手前用 `ls cpq-backend/src/main/resources/db/migration/ | grep -oP '^V\d+' | sort -t V -k2 -n | tail -1` 确认最大版本仍是 `V299`；若已被其他分支占用则顺延为下一个空号。

- [ ] **Step 2: 加实体字段**

在 `MaterialBomItem.java` 的 `baseQty` 字段（`@Column(name = "base_qty", ...) public BigDecimal baseQty;`）之后插入：

```java
    @Column(name = "rough_weight", precision = 18, scale = 6)
    public BigDecimal roughWeight;

    @Column(name = "net_weight", precision = 18, scale = 6)
    public BigDecimal netWeight;

    @Column(name = "weight_unit", length = 20)
    public String weightUnit;
```

- [ ] **Step 3: 编译 + Flyway 验证（跑一个轻量 @QuarkusTest 触发启动）**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest=MaterialBomMergeHandlerTest#cfgPrefixMaterial_rejected test
```
Expected: BUILD SUCCESS（Quarkus 测试实例启动 = Flyway V300 跑通；该用例与新列无关，仅用于验证 schema/实体映射不报错）。

若需独立确认列存在，可用项目 psql（凭据见 RECORD.md / 既有脚本）:
```bash
# 期望返回 3 行
psql ... -c "SELECT column_name FROM information_schema.columns WHERE table_name='material_bom_item' AND column_name IN ('rough_weight','net_weight','weight_unit');"
```

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V300__material_bom_item_weight_fields.sql \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/MaterialBomItem.java
git commit -m "feat(material-bom): material_bom_item 新增 rough_weight/net_weight/weight_unit 列(V300+实体)"
```

---

## Task 2: `labelOnly` helper + 纯单测

**Files:**
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/LabelOnlyTest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`（在 `digitsOnly` helper 旁，约第 187 行附近）

- [ ] **Step 1: 写失败单测**

创建 `LabelOnlyTest.java`（纯单测，不加 `@QuarkusTest`，毫秒级）：

```java
package com.cpq.basicdata.v6.quote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LabelOnlyTest {

    @Test
    void stripsLeadingNumberAndSeparator() {
        assertEquals("银点类",   MaterialBomMergeHandler.labelOnly("1.银点类"));
        assertEquals("非银点类", MaterialBomMergeHandler.labelOnly("2.非银点类"));
    }

    @Test
    void keepsPlainChineseAsIs() {
        assertEquals("组成件", MaterialBomMergeHandler.labelOnly("组成件"));
        assertEquals("边角料", MaterialBomMergeHandler.labelOnly("边角料"));
    }

    @Test
    void pureNumberStaysAsIs() {
        // "1" 剥掉数字后无剩余 → 原样返回 "1"（与历史纯数字数据兼容）
        assertEquals("1", MaterialBomMergeHandler.labelOnly("1"));
    }

    @Test
    void nullAndBlank() {
        assertNull(MaterialBomMergeHandler.labelOnly(null));
        assertEquals("", MaterialBomMergeHandler.labelOnly(""));
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译失败=未定义方法）**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest=LabelOnlyTest test
```
Expected: 编译失败 `cannot find symbol: method labelOnly` —— 这是预期的 RED。

- [ ] **Step 3: 实现 `labelOnly`（package-private static）**

在 `MaterialBomMergeHandler.java` 的 `digitsOnly` 方法旁加入（**package-private**，供同包单测访问）：

```java
    /**
     * 「产出料号类型」只存汉字：剥离前导数字 + 紧随的一个分隔符，保留其后标签。
     * "1.银点类"→"银点类"；"2.非银点类"→"非银点类"；"组成件"→"组成件"；"1"→"1"；null→null。
     */
    static String labelOnly(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = 0;
        while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
        if (i > 0 && i < t.length()) {
            char sep = t.charAt(i);
            if (sep == '.' || sep == '。' || sep == '、' || sep == '．'
                    || sep == '/' || sep == '／' || sep == ' ' || sep == '\t') i++;
        }
        String rest = t.substring(i).trim();
        return rest.isEmpty() ? t : rest;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest=LabelOnlyTest test
```
Expected: BUILD SUCCESS，4 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/LabelOnlyTest.java
git commit -m "feat(material-bom): 新增 labelOnly helper(产出料号类型剥编号存汉字) + 单测"
```

---

## Task 3: 改导入器映射 + CHILD_CONTENT + 组成件 type + 同步集成测试

> 本任务一次性完成行为改动并把受影响的两个 `@QuarkusTest` 测试改到新期望，保证提交即绿。

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`
  - `CHILD_CONTENT`（约第 43 行）
  - 物料BOM 循环（约第 73~82 行）
  - 组成件循环 material_type（约第 104 行）
- Modify: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java`（加新列断言）
- Modify: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java`（改 3 处期望）

- [ ] **Step 1: 在 `MaterialBomMergeHandlerTest` 加失败的新断言**

在 `MaterialBomMergeHandlerTest.java` 末尾（`cfgPrefixMaterial_rejected` 之后、类结束 `}` 之前）加入：

```java
    @Test
    void materialOnly_writesWeightColumns_notLegacyAndTypeAsLabel() {
        // matRow 默认 产出料号类型="2.非银点类", 材料毛重=qty, 重量单位=KG（净重缺省=null）
        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT rough_weight, net_weight, weight_unit, composition_qty, base_qty, issue_unit, component_usage_type " +
            "FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult();

        assertEquals(0, new java.math.BigDecimal("0.5").compareTo((java.math.BigDecimal) r[0]), "rough_weight 应=0.5");
        assertEquals("KG", r[2], "weight_unit 应=KG");
        assertNull(r[3], "composition_qty 旧字段不再写");
        assertNull(r[4], "base_qty 旧字段不再写");
        assertNull(r[5], "issue_unit 旧字段不再写");
        assertEquals("非银点类", r[6], "component_usage_type 应存汉字");
    }
```

- [ ] **Step 2: 跑确认失败（RED）**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest=MaterialBomMergeHandlerTest#materialOnly_writesWeightColumns_notLegacyAndTypeAsLabel test
```
Expected: FAIL —— 当前代码把毛重写进 composition_qty、类型存 "2.非银点类"，新断言不满足。

- [ ] **Step 3: 改 `CHILD_CONTENT` 加 3 列**

把 `MaterialBomMergeHandler.java` 第 43~46 行的列表：

```java
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq");
```

改为：

```java
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq",
        "rough_weight", "net_weight", "weight_unit");
```

- [ ] **Step 4: 改物料BOM 循环映射（约第 73~82 行）**

将 material_master upsert 的 `digitsOnly(componentUsageType)` 改为 `labelOnly(componentUsageType)`，并把毛重/净重/单位改写新列、去掉旧三列、类型存汉字：

```java
            // material_master：产出料号类型只存汉字（labelOnly）
            materialMasterRepo.upsertByMaterialNo(componentNo, componentName,
                null, null, null, labelOnly(componentUsageType), null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("component_no", componentNo);
            c.put("component_usage_type", labelOnly(componentUsageType));
            c.put("rough_weight", row.getDecimal("材料毛重", "毛重"));
            c.put("net_weight",   row.getDecimal("材料净重", "净重"));
            c.put("weight_unit",  row.getStr("重量单位"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
```

> 注意：删除原来的 `c.put("composition_qty", ...)`、`c.put("base_qty", ...)`、`c.put("issue_unit", ...)` 三行（物料BOM 侧不再写旧字段）。`seq_no`/`component_no`/`scrap_rate`/`defect_rate` 保持不变。

- [ ] **Step 5: 改组成件循环 material_type（约第 104 行）**

把组成件循环里 `materialMasterRepo.upsertByMaterialNo(..., "3", ...)` 的固定 `"3"` 改为 `"组成件"`：

```java
            // §12 料号表同步：组成件 material_type 固定存汉字「组成件」，已存在保留原值（preserveDescriptive=true）
            materialMasterRepo.upsertByMaterialNo(componentNo, componentName,
                null, null, null, "组成件", null, null, null, ctx.importedBy, true);
```

- [ ] **Step 6: 更新 `AssemblyBomMaterialSyncTest` 的 3 处期望**

在 `AssemblyBomMaterialSyncTest.java` 改：

`newComponent_materialTypeIs3`（第 78~81 行）→ 期望「组成件」（顺手改方法名与文案）：
```java
    @Test
    void newComponent_materialTypeIsAssemblyLabel() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-NEW", "ASM-N1", "OP1", null)), ctx());
        assertEquals("组成件", typeOf("ASM-NEW"), "§12 新料件 material_type 固定汉字「组成件」（决策 Q5-A）");
    }
```

`crossing_materialKeepsSection3NumericType`（第 90~97 行）→ 物料BOM 先写，类型存汉字「非银点类」，组成件 preserve 不覆盖：
```java
    @Test
    void crossing_materialKeepsSection3ChineseType() {
        handler.merge(
            List.of(matRow(1, "ASM-CROSS", "ASM-C1", "2.非银点类")),
            List.of(asmRow(1, "ASM-CROSS", "ASM-C1", "OP1", null)),
            ctx());
        assertEquals("非银点类", typeOf("ASM-CROSS"), "交叉料件保留 §3 汉字类型（物料BOM 先写，组成件 preserve 不覆盖）");
    }
```

`emptyComponentNo_withName_generates`（第 111~115 行）→ 期望「组成件」：
```java
    @Test
    void emptyComponentNo_withName_generates() {
        handler.merge(List.of(), List.of(asmRow(1, null, "ASM-GEN", "OP1", null)), ctx());
        assertEquals("组成件", typeOf("9000000000"), "料号空+名称→生成 9 字头，type=组成件");
    }
```

> `existingComponent_keepsOriginalType`（第 84~88 行）**不改**：preserveDescriptive=true 保留既有 "1"，与本次改动无关，仍应绿。

- [ ] **Step 7: 跑整个 v6/quote 测试包确认全绿（GREEN + 回归）**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest='com.cpq.basicdata.v6.quote.*' test
```
Expected: BUILD SUCCESS。涵盖 `MaterialBomMergeHandlerTest`（含新用例）、`AssemblyBomMaterialSyncTest`（改后期望）、`MaterialNoImportIdempotencyTest`、`MaterialBomMaterialNoResolveTest`、`LabelOnlyTest` 全绿。

> 若 `MaterialNoImportIdempotencyTest` / `MaterialBomMaterialNoResolveTest` 出现 material_type/usage_type 相关失败：检查是否有未预料到的 type 值断言；按"存汉字"新规对齐期望（这两个文件经排查不断言这些值，正常情况下应直接绿）。

- [ ] **Step 8: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java
git commit -m "feat(material-bom): 毛重/净重/单位改存专用列+产出料号类型存汉字+组成件type=组成件(含测试)"
```

---

## Task 4: 回写落库方案文档

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`（§3 物料BOM，约第 154~169 行）

- [ ] **Step 1: 更新子表映射备注**

§3「物料BOM子表（material_bom_item）」表中：
- `材料毛重 | rough_weight | ✅ | 毛重` （目标字段由说明确认为 `rough_weight`，备注补「V300 起专用列，原借住 composition_qty」）
- `材料净重 | net_weight | ✅ | 净重（V300 起专用列，原借住 base_qty）`
- `重量单位 | weight_unit | ✅ | 重量单位（V300 起专用列，原借住 issue_unit）`
- `产出料号类型 | component_usage_type | ✅` 备注由现状改为：「只存汉字（剥离 N. 编号）：银点类/非银点类/组成件/边角料」

§3「料号表（material_master）同步」表 + 末尾 📌 说明：
- 把「`material_type`，只写入数字」改为「`material_type`，**只写汉字**（剥离 N. 编号）；组成件BOM 侧固定写「组成件」」。

- [ ] **Step 2: Commit**

```bash
git add "docs/table/报价系统Excel导入落库方案.md"
git commit -m "docs(import): 回写物料BOM 毛重/净重/单位专用列 + 产出料号类型存汉字规则"
```

---

## Task 5: 开发记录 + 最终自检

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 跑后端相关测试包做最终回归**

Run（在 `cpq-backend/`）:
```bash
./mvnw -q -Dtest='com.cpq.basicdata.v6.quote.*' test
```
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 2: psql 抽查（可选但推荐）**

按 §6 自检：导入一份含「组成件/边角料」无编号类型 + 毛重/净重/单位的物料BOM，确认 `material_bom_item` 新列有值、旧三列空、`component_usage_type` 为汉字；`material_master.material_type` 为汉字。
（如无现成导入夹具，可依赖 Task 3 集成测试覆盖的等价断言，跳过手动 psql。）

- [ ] **Step 3: 追加 RECORD.md**

在 `docs/RECORD.md` 追加一行（格式：`[日期] 模块 - 简要描述 | 涉及文件 | 关键决策`）：
```
[2026-06-17] 报价导入/物料BOM - 材料毛重/净重/单位改存专用列 rough_weight/net_weight/weight_unit(V300)；产出料号类型(component_usage_type + material_master.material_type)统一存汉字(labelOnly)，组成件侧 material_type 3→组成件 | V300 + MaterialBomMergeHandler.java + MaterialBomItem.java + 测试×3 + 报价系统Excel导入落库方案.md | Q6-B 本次只改落库不改 v12_raw_bom(来料Tab 毛重/净重/单位暂空)；Q7-A 不写存量迁移靠重导自愈
```

- [ ] **Step 4: Commit + 自检声明**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 物料BOM 毛重/净重/单位专用列 + 产出料号类型存汉字 开发记录"
```

自检声明（向用户汇报时附上）：
> `./mvnw -Dtest='com.cpq.basicdata.v6.quote.*' test` BUILD SUCCESS（含 LabelOnlyTest + 新列断言 + AssemblyBomMaterialSyncTest 改后期望全绿）✅；Flyway V300 随测试启动跑通 ✅；新列落库/旧列留空/类型存汉字均经集成测试断言 ✅。

---

## 收尾（用户确认后，按 CLAUDE.md 自动执行）

走 `superpowers:finishing-a-development-branch`「合并并清理」：切回 master → merge → 跑测试 → 合并后在共享 dev server 上跑 `quotation-flow.spec.ts` E2E（注意：报价"来料BOM"Tab 毛重/净重/单位列变空是 Q6-B 预期，不计回归失败，只确认无"加载中"残留）→ `git worktree remove` + 删分支。
