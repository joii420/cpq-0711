# material_bom_item 版本化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `material_bom_item` 加 `bom_version` 版本列，实现子表与 `material_bom` 主表版本对齐 + going-forward 多版本保留（历史明细可回溯），并让报价/核价/选配三条写入链与全部读取点同步对齐。

**Architecture:** 子表改用 `VersionedV6Writer` 已有的"版本列多版本插入"分支（与 `element_bom_item` 同款，仅版本列名不同：material_bom_item 用 `bom_version`、element_bom_item 用 `characteristic`）。版本作用域保持 per-`(料号, characteristic)` 不变（选配 COMBO 双 current 行依赖）。所有读 `material_bom_item` 的组件配置 SQL 补 `is_current` 过滤防 AP-22 重复行。

**Tech Stack:** Java 17 + Quarkus 3.23.3、Hibernate/Panache、PostgreSQL 16、Flyway、JUnit5 `@QuarkusTest`、Playwright E2E。

**Spec:** `docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md`

---

## 范围说明（重要）

- 本计划只做**版本化基础设施**：schema + writer 调用切换 + 9 个配置视图 `is_current` + 三条写入链 `childVersionColumn` 切换。
- **不在本计划内**：V3.2 去重合并 `MaterialBomMergeHandler` 的完整构建（独立计划）。本计划只在 Task 7 落一条集成约束——V3.2 第 4 步在保留模型下必须用 **FLIP（is_current=false）而非 DELETE**，并回写 V3.2 文档。
- `material_bom_item` 改 `childVersionColumn=null → "bom_version"` 后，writer **无需逻辑改动**（版本路径已通用，element_bom_item 在用）；唯一 writer 改动是移除已变死代码的 `material_bom_item` null-path 登记（Task 3）。

## 文件结构

| 文件 | 责任 | 动作 |
|------|------|------|
| `db/migration/V293__material_bom_item_versioning.sql` | 加列 + 重建 uq + 存量当前行对齐 | Create |
| `db/migration/V294__bom_item_views_is_current.sql` | 9 个配置视图补 is_current | Create |
| `VersionedV6Writer.java` | 移除 material_bom_item null-path 死登记 | Modify |
| `Q03MaterialBomHandler.java` / `Q12AssemblyBomHandler.java` | childVersionColumn → "bom_version" | Modify |
| `P06MaterialBomHandler.java` | childVersionColumn → "bom_version" | Modify |
| `ConfigureProductService.java` | 两处 childVersionColumn → "bom_version" | Modify |
| `VersionedV6WriterTest.java` | material_bom_item 多版本保留单测 | Modify |

## 部署纪律

- V293 + V294 **同一次部署**落库（schema 与读取侧 is_current 原子上线），生产永不出现"多版本写入但读取未护"窗口。
- 任何 `.sql` 落 `db/migration/` 后 **touch 一个 java 文件**触发 Quarkus 重启（Flyway migrate-at-start + `BnfTableMetaSyncer` 清 BNF 缓存）。不要手工 `psql -f`。

---

### Task 1: 数据模型迁移（加 bom_version + 重建 uq + 存量对齐）

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V293__material_bom_item_versioning.sql`

- [ ] **Step 1: 写迁移 SQL**

```sql
-- V293: material_bom_item 加 bom_version 版本列，子表多版本保留（对齐 material_bom 主表）
-- 设计: docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md
-- 版本作用域仍 per-(料号, characteristic)；is_current 由 V277 已加。

-- 1) 加列（可空，容纳存量行）
ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS bom_version VARCHAR(20);

-- 2) 重建唯一索引：版本维度并入 uq
DROP INDEX IF EXISTS uq_material_bom_item;
CREATE UNIQUE INDEX uq_material_bom_item ON material_bom_item(
    system_type, customer_no, material_no,
    COALESCE(characteristic,''),
    COALESCE(bom_version,''),
    COALESCE(seq_no,0),
    COALESCE(component_no,''),
    COALESCE(part_no,'')
);

-- 3) 存量当前行一次性对齐（不补历史）：子行 bom_version = 对应 master 当前 bom_version
UPDATE material_bom_item ci
SET bom_version = m.bom_version
FROM material_bom m
WHERE m.is_current = TRUE
  AND m.system_type = ci.system_type
  AND m.customer_no = ci.customer_no
  AND m.material_no = ci.material_no
  AND m.characteristic IS NOT DISTINCT FROM ci.characteristic
  AND ci.bom_version IS NULL;

COMMENT ON COLUMN material_bom_item.bom_version IS 'V293 子表版本号，对齐 material_bom.bom_version，多版本保留 + is_current 标当前';
```

- [ ] **Step 2: 触发 Flyway 并验证迁移成功**

Run:
```bash
cd cpq-backend && touch src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java && sleep 7
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c "SELECT version, success FROM flyway_schema_history WHERE version='293';"
```
Expected: `293|t`

- [ ] **Step 3: 验证列与 uq 存在、存量当前行已对齐**

Run:
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c "
SELECT count(*) AS null_ver_current
FROM material_bom_item ci JOIN material_bom m
  ON m.is_current AND m.system_type=ci.system_type AND m.customer_no=ci.customer_no
 AND m.material_no=ci.material_no AND m.characteristic IS NOT DISTINCT FROM ci.characteristic
WHERE ci.is_current AND ci.bom_version IS NULL;"
```
Expected: `0`（所有当前子行已对齐 bom_version）

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V293__material_bom_item_versioning.sql
git commit -m "feat(db): V293 material_bom_item 加 bom_version 版本列 + 重建 uq + 存量当前行对齐"
```

---

### Task 2: 三条导入/选配写入链切到 bom_version 版本路径

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandler.java:87-89`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandler.java:74-76`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java:83-85`
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java:568-573,584-589`

> 仅把第 6 个实参 `childVersionColumn` 从 `null` 改成 `"bom_version"`。其余参数不动。

- [ ] **Step 1: 改 Q03（物料BOM，characteristic=NULL）**

`Q03MaterialBomHandler.java` 把：
```java
                writer.writeVersionedMasterDetail(
                    "material_bom", "bom_version", masterGk, Map.of(),
                    "material_bom_item", null, childGk, CHILD_CONTENT, childRows);
```
改为：
```java
                writer.writeVersionedMasterDetail(
                    "material_bom", "bom_version", masterGk, Map.of(),
                    "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows);
```

- [ ] **Step 2: 改 Q12（组成件BOM，characteristic=ASSEMBLY）**

`Q12AssemblyBomHandler.java` 同样把 `"material_bom_item", null,` 改为 `"material_bom_item", "bom_version",`。

- [ ] **Step 3: 改 P06（核价物料BOM）**

`P06MaterialBomHandler.java` 同样把 `"material_bom_item", null,` 改为 `"material_bom_item", "bom_version",`。

- [ ] **Step 4: 改选配两处（ConfigureProductService ASSEMBLY + MATERIAL）**

`:571` 与 `:587` 两处的 `"material_bom_item", null,` 均改为 `"material_bom_item", "bom_version",`。

- [ ] **Step 5: 触发重启并验证后端起得来**

Run:
```bash
cd cpq-backend && touch src/main/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandler.java && sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: `200`

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java \
        cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
git commit -m "feat(versioning): material_bom_item 三条写入链切 bom_version 多版本路径(报价Q03/Q12+核价P06+选配)"
```

---

### Task 3: 写入器单测（多版本保留）+ 移除 null-path 死登记

**Files:**
- Modify: `cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedV6WriterTest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java:51-56`

- [ ] **Step 1: 写失败测试（material_bom_item 升版保留历史）**

在 `VersionedV6WriterTest` 类内新增（同时在 `cleanup()` 里加对应删除）：

```java
    static final String MBV_CUST = "TEST-MBV-CUST";
    static final String MBV_MAT  = "TEST-MBV-0001";

    @Test @Transactional
    void materialBomItem_keepsHistory_onVersionBump() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MBV_MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MBV_MAT).executeUpdate();

        java.util.LinkedHashMap<String,Object> masterGk = new java.util.LinkedHashMap<>();
        masterGk.put("system_type","QUOTE"); masterGk.put("customer_no",MBV_CUST);
        masterGk.put("material_no",MBV_MAT); masterGk.put("bom_type","MATERIAL");
        masterGk.put("characteristic", null);
        java.util.LinkedHashMap<String,Object> childGk = new java.util.LinkedHashMap<>();
        childGk.put("system_type","QUOTE"); childGk.put("customer_no",MBV_CUST);
        childGk.put("material_no",MBV_MAT); childGk.put("characteristic", null);
        List<String> content = List.of("seq_no","component_no","composition_qty");

        String v1 = writer.writeVersionedMasterDetail("material_bom","bom_version",masterGk,Map.of(),
            "material_bom_item","bom_version",childGk,content,
            List.of(childRow(1,"C1","1")));
        assertEquals("2000", v1);

        String v2 = writer.writeVersionedMasterDetail("material_bom","bom_version",masterGk,Map.of(),
            "material_bom_item","bom_version",childGk,content,
            List.of(childRow(1,"C1","2")));   // 内容变 → 升版
        assertEquals("2001", v2);

        Number cur = (Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MBV_MAT).getSingleResult();
        assertEquals(1L, cur.longValue(), "当前子行唯一");

        Number old = (Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=FALSE AND bom_version='2000'")
            .setParameter("m", MBV_MAT).getSingleResult();
        assertEquals(1L, old.longValue(), "2000 版历史子行保留为 is_current=false");

        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MBV_MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MBV_MAT).executeUpdate();
    }

    private java.util.LinkedHashMap<String,Object> childRow(int seq, String comp, String qty) {
        java.util.LinkedHashMap<String,Object> r = new java.util.LinkedHashMap<>();
        r.put("seq_no", seq); r.put("component_no", comp);
        r.put("composition_qty", new java.math.BigDecimal(qty));
        return r;
    }
```

- [ ] **Step 2: 运行测试，确认通过**

Run:
```bash
cd cpq-backend && ./mvnw -q test -Dtest=VersionedV6WriterTest#materialBomItem_keepsHistory_onVersionBump
```
Expected: PASS（v1=2000、v2=2001、当前唯一、2000 版历史保留）。
> 若 FAIL 报 uq 冲突 → 确认 Task 1 的 V293 已生效（uq 含 bom_version）。

- [ ] **Step 3: 移除 writer 中 material_bom_item 的 null-path 死登记**

`VersionedV6Writer.java` 的 `CHILD_UQ`（约 `:51-56`）只服务 `childVersionColumn==null` 分支。material_bom_item 已全部切 "bom_version"，该登记变死代码。把：
```java
    private static final Map<String, ChildUq> CHILD_UQ = Map.of(
        "material_bom_item", new ChildUq(
            "(system_type, customer_no, material_no, COALESCE(characteristic,''), "
                + "COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))",
            Set.of("system_type", "customer_no", "material_no",
                   "characteristic", "seq_no", "component_no", "part_no")));
```
改为空登记（保留机制，后续如有新 null-path 子表再加）：
```java
    private static final Map<String, ChildUq> CHILD_UQ = Map.of();
```
并同步更新类 javadoc 中"material_bom_item，uq 不含版本→upsert 覆盖"等过时描述为"material_bom_item 已版本化(V293)，走 bom_version 多版本路径"。

- [ ] **Step 4: 全量跑 writer 测试，确认无回归**

Run:
```bash
cd cpq-backend && ./mvnw -q test -Dtest=VersionedV6WriterTest
```
Expected: 全部 PASS（含原有 unit_price/capacity 用例 + 新增 material_bom_item 用例）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedV6WriterTest.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java
git commit -m "test(versioning): material_bom_item 多版本保留单测 + 移除 null-path 死登记"
```

---

### Task 4: 9 个组件配置视图补 is_current（读取侧护栏）

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V294__bom_item_views_is_current.sql`

> 仅改 `component_sql_view.sql_template`（BNF 运行时模板），不碰 PG `CREATE VIEW`。锚点已逐个核对（spec §9.1）。

- [ ] **Step 1: 写迁移 SQL（每视图 regexp_replace，'g' 全局）**

```sql
-- V294: 9 个读 material_bom_item 的组件配置 SQL 补 is_current（material_bom_item 多版本化后防 AP-22 重复行）
-- 范围仅 component_sql_view；锚点见 spec §9.1。'g' 全局；多处出现的 ys_view/weights 一并覆盖。

-- 1) v12_raw_bom (bi)：锚 system_type
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'bi\.system_type\s*=\s*''QUOTE''', 'bi.system_type = ''QUOTE'' AND bi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'v12_raw_bom';

-- 2) zcj_bom (asy)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'zcj_bom';

-- 3) zcj_view (asy)（其 unit_price up 已自带 is_current，不受影响）
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'zcj_view';

-- 4) v12_raw_element_bom (mbi)：锚 JOIN ON 的 system_type
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'mbi\.system_type\s*=\s*''QUOTE''', 'mbi.system_type = ''QUOTE'' AND mbi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'v12_raw_element_bom';

-- 5) ys_view (mbi 左连接处；mbt 子查询已自带 is_current 不动)：锚 mbi.characteristic='ASSEMBLY'
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'mbi\.characteristic\s*=\s*''ASSEMBLY''', 'mbi.characteristic = ''ASSEMBLY'' AND mbi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'ys_view';

-- 6) composite_child_materials_mirror (asy)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_materials_mirror';

-- 7) composite_child_processes_mirror (bom)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'bom\.system_type\s*=\s*''QUOTE''', 'bom.system_type = ''QUOTE'' AND bom.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_processes_mirror';

-- 8) composite_child_weights_mirror (asy + asy2 两处，两条 replace；asy\. 不会误匹配 asy2.)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_weights_mirror';
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy2\.system_type\s*=\s*''QUOTE''', 'asy2.system_type = ''QUOTE'' AND asy2.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_weights_mirror';

-- 9) composite_child_elements_mirror (parent；ebi 已自带 is_current 不动)：锚 parent.system_type
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'parent\.system_type\s*=\s*''QUOTE''', 'parent.system_type = ''QUOTE'' AND parent.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_elements_mirror';
```

- [ ] **Step 2: 触发 Flyway 并验证迁移成功**

Run:
```bash
cd cpq-backend && touch src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java && sleep 7
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c "SELECT version, success FROM flyway_schema_history WHERE version='294';"
```
Expected: `294|t`

- [ ] **Step 3: 逐视图核对 mbi 别名已带 is_current（应全 t）**

Run:
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA <<'SQL'
WITH v AS (
  SELECT sql_view_name, sql_template,
         (regexp_matches(sql_template,'material_bom_item\s+(\w+)','g'))[1] AS alias
  FROM component_sql_view WHERE sql_template ILIKE '%material_bom_item%')
SELECT sql_view_name, alias, (sql_template ~ (alias||'\.is_current')) AS ok
FROM v ORDER BY sql_view_name, alias;
SQL
```
Expected: 所有行 `ok=t`（即每个 material_bom_item 别名都带 `<alias>.is_current`）。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V294__bom_item_views_is_current.sql
git commit -m "fix(db): V294 9个组件配置SQL补 material_bom_item.is_current(多版本化防AP-22重复行)"
```

---

### Task 5: 端到端回归（升版不返重复行 + 选配 4 Tab）

**Files:**
- 无新增；执行验证。

- [ ] **Step 1: 构造同料号两次升版，验证读取侧返当前唯一**

Run（用现网已知碰撞料号 3120018220/客户 8000137 作只读核对其当前子行唯一；若已被去重影响，换任一 QUOTE 物料BOM 料号）：
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c "
SELECT material_no, characteristic, count(*) FILTER (WHERE is_current) AS cur, count(*) AS total
FROM material_bom_item WHERE system_type='QUOTE' AND material_no='3120018220'
GROUP BY material_no, characteristic ORDER BY characteristic NULLS FIRST;"
```
Expected: 每个 characteristic 组 `cur` ≤ 当前子行数、`total ≥ cur`（多版本时 total>cur 即历史保留）。

- [ ] **Step 2: 报价 E2E**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`。

- [ ] **Step 3: 组合产品 E2E（选配 4 mirror Tab 回归）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: `passed`，选配 材质/元素/工序/单重 4 Tab `'加载中' final count = 0`（验证 mirror 视图加 is_current 后仍正确）。

- [ ] **Step 4: Commit（E2E 截图证据）**

```bash
git add cpq-frontend/e2e/screenshots/
git commit -m "test(e2e): material_bom_item 版本化回归 报价+组合产品双spec 加载中=0"
```

---

### Task 6: 文档回写（spec 状态 + RECORD + V3.2 集成约束）

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`（V3.2 第 4 步 DELETE→FLIP）
- Modify: `docs/RECORD.md`
- Modify: `docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md`（状态置"已实现"）

- [ ] **Step 1: 回写 V3.2 第 4 步语义（DELETE→FLIP）**

在 `报价系统Excel导入落库方案.md`【去重合并实现细则】第 4 步补一句：
> ⚠️ **保留模型更新（2026-06-04 material_bom_item 版本化后）**：第 4 步"下线反向 characteristic 旧行"由"物理 DELETE"改为"FLIP `is_current=false` 保留为历史"（主表 + 子表都翻），与子表多版本保留一致；仍只按单料号、不碰 `CFG-`。

- [ ] **Step 2: 追加 RECORD**

在 `docs/RECORD.md` 末尾追加：
```
[2026-06-04] material_bom_item 版本化 - 子表加 bom_version 多版本保留(对齐material_bom主表+element_bom_item机制) | V293(加列+重建uq+存量对齐) V294(9配置视图补is_current) Q03/Q12/P06/ConfigureProductService(childVersionColumn null→bom_version) VersionedV6Writer(移除null-path死登记) | 版本作用域仍per-(料号,characteristic)保选配双行;读取侧仅改组件配置SQL不碰PG视图;历史明细不backfill;V3.2去重第4步随之DELETE→FLIP
```

- [ ] **Step 3: spec 状态置"已实现"**

把 spec 文档头部 `状态：设计已评审，待写实现计划` 改为 `状态：已实现（计划 docs/superpowers/plans/2026-06-04-material_bom_item-版本化.md）`。

- [ ] **Step 4: Commit**

```bash
git add docs/table/报价系统Excel导入落库方案.md docs/RECORD.md docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md
git commit -m "docs(versioning): 回写V3.2第4步FLIP + RECORD + spec状态(material_bom_item版本化)"
```

---

## 自检声明模板（每个含代码 Task 完成后必带）

> "TS/编译 0 错误 ✅；V293/V294 success=t ✅；VersionedV6WriterTest PASS ✅；E2E 双 spec passed + '加载中'=0 ✅；选配 4 Tab 不空 ✅"
