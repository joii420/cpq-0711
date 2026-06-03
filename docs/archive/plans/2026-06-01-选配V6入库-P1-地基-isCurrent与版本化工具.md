# 选配 V6 入库 · P1 地基（is_current 全局加列 + 版本化写入工具）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为所有带版本的 V6 基础表统一新增 `is_current` 生效标志，并交付一个通用「版本化写入工具」（分组键相同、内容/行数不同则版本 max+1 + is_current 翻转），作为 P2（工序压 unit_price）/ P3（组合工艺压 capacity）/ P4（补主表）的依赖底座。

**Architecture:** ① 一支 Flyway 迁移 `V277` 给 17 张表**新增** `ADD COLUMN IF NOT EXISTS is_current`（不改名、不动任何现有列）；② 一个 CDI bean `VersionedV6Writer`，用原生 SQL 实现「组级版本化写入」（与 `ConfigureProductService` 现有 `em.createNativeQuery` 模式一致）；③ 排查 `is_effective` 引用面，确认**新增** `is_current` 与核价现有 `is_effective` 并存、互不干扰。本计划**不改变选配落库行为**，纯地基 + 测试。

**Tech Stack:** Java 17 / Quarkus 3.23.3 / Hibernate ORM (EntityManager 原生 SQL) / Flyway / PostgreSQL 16 / JUnit5 + `@QuarkusTest`。

**关联文档:** `docs/选配V6入库规范-设计方案.md`（§5 版本化、§5.2 加列清单、§11 R1 风险）。

---

## 设计决策（来自设计方案，本计划锁定）

- **新增**生效标志 `is_current BOOLEAN NOT NULL DEFAULT TRUE`（不是改名）；已有 `is_effective` 的表（`capacity`/`tooling_cost`/`material_version_mgmt`）**保留 `is_effective` 原样不动**（核价侧现有读写不受影响）。选配/报价侧用 `is_current` 判定生效；核价侧沿用 `is_effective`。两者并存、互不干扰（组合工艺写的是 `resource_group_no=QUOTE_ASSEMBLY` 行，核价不读）。统一到 `is_current` 的核价改造不在本期范围（子项2=C 的长期方向，另议）。
- `material_bom_item` 无独立版本列（`characteristic` 是语义标签），仍加 `is_current` 以便跟随主表 `material_bom` 生效翻转（§10 待澄清点1 取建议 a）。
- 版本号语义：VARCHAR 存数字字符串，起始 `'2000'`，递增取 `MAX(数字版本)+1`；非数字版本值（如导入的 `'V_DEFAULT'`）在选配版本计算中被忽略。
- 版本化粒度 = **组**（同一分组键下的整套行共享一个版本号；工序/组合工艺是「一组多行」）。

## 文件结构

- **Create:** `cpq-backend/src/main/resources/db/migration/V277__add_is_current_to_versioned_v6_tables.sql` — 17 张表加列。
- **Create:** `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java` — 通用组级版本化写入。
- **Create:** `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedGroupSpec.java` — 写入参数对象。
- **Create:** `cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedV6WriterTest.java` — `@QuarkusTest` 集成测试。
- **Create:** `docs/选配V6入库-is_effective引用面排查.md` — Task 1 产出。

---

## Task 1: 排查 `is_effective` 引用面（核价风险 R1）

**Files:**
- Create: `docs/选配V6入库-is_effective引用面排查.md`

- [ ] **Step 1: 全工程 grep `is_effective` / `isEffective` 引用**

Run:
```bash
cd /home/joii/project/cpq
echo "### SQL 迁移"; grep -rn "is_effective" cpq-backend/src/main/resources/db/migration/*.sql
echo "### Java"; grep -rn "is_effective\|isEffective" cpq-backend/src/main/java | grep -v /target/
echo "### component_sql_view 视图模板(渲染依赖)"; grep -rn "is_effective" cpq-backend/src/main/resources/db/migration/*.sql | grep -i "sql_template\|WHERE\|component_sql_view"
echo "### 前端"; grep -rn "is_effective\|isEffective" cpq-frontend/src 2>/dev/null | head
```
Expected: 命中集中在 `capacity` / `tooling_cost` / `material_version_mgmt` 三表的实体 + Handler（`P08CapacityHandler`/`P12ToolingCostHandler`/`P04PricingVersionHandler`/`Q14AssemblyProcessFeeHandler`），以及 V218/V220/V255 三处 SQL。

- [ ] **Step 2: 判定每处引用是否属于核价主线，写成排查清单**

把每处命中按下表记录进 `docs/选配V6入库-is_effective引用面排查.md`：

```markdown
# is_effective 引用面排查（P1 Task 1）
> 日期：2026-06-01 | 目的：确认**新增** is_current 与核价现有 is_effective 并存、不冲突（不改 is_effective）

| # | 文件:行 | 表 | 用途(核价/报价/导入) | 新增 is_current 后是否影响此处 | 结论 |
|---|---------|----|--------------------|------------------------------|------|
| 1 | ... | capacity | ... | 不影响(此处继续读 is_effective) | 保持不动 |

## 结论
- is_effective 现有读写：保持原样，本期不改
- 核价侧是否受新增 is_current 影响：是/否（预期否——仅新增列，DEFAULT TRUE，不改现有查询）
- 选配/报价侧用 is_current 判定生效与核价 is_effective 是否存在同表冲突：是/否（若是，列出表+视图，移交对应写入计划 P2/P3）
```

- [ ] **Step 3: Commit**

```bash
cd /home/joii/project/cpq
git add docs/选配V6入库-is_effective引用面排查.md
git commit -m "docs: 选配V6入库 P1 — is_effective 引用面排查清单"
```

---

## Task 2: Flyway V277 — 17 张表新增 `is_current`

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V277__add_is_current_to_versioned_v6_tables.sql`

- [ ] **Step 1: 写迁移 SQL**

写入 `cpq-backend/src/main/resources/db/migration/V277__add_is_current_to_versioned_v6_tables.sql`：

```sql
-- V277: 给所有「带版本的 V6 基础表」统一新增生效标志 is_current。
-- 设计方案 §5.2。已有 is_effective 的表(capacity/tooling_cost/material_version_mgmt)保留该列不改名，
-- 但后续生效判定统一以 is_current 为权威。material_bom_item 无版本列，加列以跟随主表生效翻转(§10#1)。
-- 幂等：ADD COLUMN IF NOT EXISTS；存量行 DEFAULT TRUE 即视为当前生效。

ALTER TABLE unit_price            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE capacity              ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_bom          ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_bom_item     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE element_bom           ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE element_bom_item      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE plating_scheme        ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE production_energy     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE auxiliary_energy      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tooling_cost          ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE production_consumable ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE packaging_consumable  ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE electricity_price     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE labor_rate            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE exchange_rate_v6      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE fee_config            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_version_mgmt ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;

-- 部分索引：加速「分组键 + 当前生效」查询（版本化写入工具按此过滤）
CREATE INDEX IF NOT EXISTS idx_unit_price_current
  ON unit_price (finished_material_no, operation_no) WHERE is_current = TRUE;
CREATE INDEX IF NOT EXISTS idx_capacity_current
  ON capacity (material_no, process_no, resource_group_no) WHERE is_current = TRUE;
```

- [ ] **Step 2: touch Java 触发 Quarkus dev 重启跑 Flyway**

Run:
```bash
cd /home/joii/project/cpq/cpq-backend
touch src/main/java/com/cpq/configure/service/ConfigureProductService.java
sleep 8
```
Expected: 无报错（dev mode 自动 migrate-at-start）。

- [ ] **Step 3: 验证迁移成功 + 列存在**

Run（用项目实际 PG 连接参数，参见 `application.properties` 的 `quarkus.datasource.*`）：
```bash
PGPASSWORD="$CPQ_DB_PASS" psql -h "$CPQ_DB_HOST" -U "$CPQ_DB_USER" -d "$CPQ_DB_NAME" \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='277';" \
  -c "SELECT table_name FROM information_schema.columns WHERE column_name='is_current' AND table_name IN ('unit_price','capacity','material_bom_item','element_bom_item') ORDER BY table_name;"
```
Expected: `277 | t`；4 张表名全部列出。

> 注：连接参数从 `cpq-backend/src/main/resources/application.properties` 读取；不要手工 `psql -f` 跑迁移文件（CLAUDE.md 纪律），只让 Quarkus 自动跑、psql 仅用于只读验证。

- [ ] **Step 4: Commit**

```bash
cd /home/joii/project/cpq
git add cpq-backend/src/main/resources/db/migration/V277__add_is_current_to_versioned_v6_tables.sql
git commit -m "feat(v6): V277 给 17 张带版本基础表统一新增 is_current 生效标志"
```

---

## Task 3: 通用版本化写入工具 `VersionedV6Writer`

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedGroupSpec.java`
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/VersionedV6WriterTest.java`

### 契约

`writeVersionedGroup(spec)`：在 `spec.tableName` 中，对由 `spec.groupKeyColumns` 唯一确定的「一组行」做版本化写入——
1. 读当前生效组：`SELECT <contentColumns> FROM <table> WHERE <groupKeys> AND is_current=TRUE ORDER BY seq_no`；
2. 与 `spec.newRows` 逐行（按 `contentColumns`，字符串规范化）比较；行数 + 内容全等 → 返回现有版本号，不写；
3. 否则：`newVersion = max(数字版本)+1`（无数字版本→`"2000"`）；先 `UPDATE … SET is_current=FALSE WHERE <groupKeys> AND is_current=TRUE`；再逐行 `INSERT`（带 `<versionColumn>=newVersion, is_current=TRUE`）；返回 `newVersion`。

- [ ] **Step 1: 写参数对象 `VersionedGroupSpec`**

写入 `.../versioning/VersionedGroupSpec.java`：

```java
package com.cpq.basicdata.v6.versioning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组级版本化写入参数。groupKeyColumns 唯一确定「哪一组」；newRows 是该组本次要写的整套行内容。 */
public class VersionedGroupSpec {
    /** 目标表名（白名单校验，防注入）。 */
    public final String tableName;
    /** 版本列名，如 "version_no" / "calc_version" / "characteristic"。 */
    public final String versionColumn;
    /** 分组键：列名→值（用于定位现有生效组 + 写入每行）。保持插入顺序。 */
    public final Map<String, Object> groupKeyColumns;
    /** 参与「内容是否相同」比较、且逐行可能不同的列（如 code/operation_no/seq_no）。 */
    public final List<String> contentColumns;
    /** 本次要写的整组行：每行 = 列名→值（含 contentColumns；不含 version/is_current/id）。 */
    public final List<Map<String, Object>> newRows;

    public VersionedGroupSpec(String tableName, String versionColumn,
                              Map<String, Object> groupKeyColumns,
                              List<String> contentColumns,
                              List<Map<String, Object>> newRows) {
        this.tableName = tableName;
        this.versionColumn = versionColumn;
        this.groupKeyColumns = new LinkedHashMap<>(groupKeyColumns);
        this.contentColumns = List.copyOf(contentColumns);
        this.newRows = List.copyOf(newRows);
    }
}
```

- [ ] **Step 2: 写失败测试（先于实现）**

写入 `.../versioning/VersionedV6WriterTest.java`。测试目标用真实 `unit_price` 表（P1 已加 `is_current`），固定 `finished_material_no='TEST-VER-0001'` 便于清理：

```java
package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VersionedV6WriterTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    static final String FMN = "TEST-VER-0001";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no = :f")
          .setParameter("f", FMN).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private VersionedGroupSpec spec(List<Map<String, Object>> rows) {
        return new VersionedGroupSpec(
            "unit_price", "version_no",
            new java.util.LinkedHashMap<>(Map.of(
                "system_type", "QUOTE", "price_type", "MATERIAL",
                "cost_type", "自制加工费", "finished_material_no", FMN, "code", FMN)),
            List.of("operation_no", "seq_no"),
            rows);
    }

    private List<Map<String, Object>> rows(String... ops) {
        java.util.ArrayList<Map<String, Object>> r = new java.util.ArrayList<>();
        int seq = 1;
        for (String op : ops) {
            r.add(new java.util.LinkedHashMap<>(Map.of("operation_no", op, "seq_no", seq++)));
        }
        return r;
    }

    @Test @Transactional
    void firstWrite_startsAt2000() {
        String v = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2000", v);
        Number cnt = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=TRUE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, cnt.longValue());
    }

    @Test @Transactional
    void sameContent_reusesVersion_noNewRows() {
        writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2000", v2);
        Number total = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, total.longValue(), "内容相同不应新增版本行");
    }

    @Test @Transactional
    void differentContent_bumpsVersion_flipsIsCurrent() {
        writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2", "OP3")));
        assertEquals("2001", v2);
        Number current = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=TRUE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(3L, current.longValue(), "当前生效=新版本3行");
        Number old = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=FALSE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, old.longValue(), "旧版本2行被下线");
    }
}
```

- [ ] **Step 3: 运行测试，确认编译失败/测试失败（工具未实现）**

Run:
```bash
cd /home/joii/project/cpq/cpq-backend
./mvnw -q test -Dtest=VersionedV6WriterTest 2>&1 | tail -30
```
Expected: 编译错误 `cannot find symbol: class VersionedV6Writer` 或测试 FAIL。

- [ ] **Step 4: 实现 `VersionedV6Writer`**

写入 `.../versioning/VersionedV6Writer.java`：

```java
package com.cpq.basicdata.v6.versioning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.util.*;

/**
 * 组级版本化写入工具（设计方案 §5）。
 * 版本列为 VARCHAR 存数字字符串，起始 "2000"；max+1 时忽略非数字版本值（如导入的 'V_DEFAULT'）。
 * 表名/列名走白名单校验防注入；值用命名参数绑定。
 */
@ApplicationScoped
public class VersionedV6Writer {

    private final EntityManager em;

    public VersionedV6Writer(EntityManager em) { this.em = em; }

    /** 允许写入的表（白名单）。新增表在此登记。 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "unit_price", "capacity", "element_bom", "element_bom_item",
        "material_bom", "material_bom_item");

    /** 列名白名单校验：只允许 [a-z_][a-z0-9_]* 标识符。 */
    private static String safeIdent(String id) {
        if (id == null || !id.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符: " + id);
        }
        return id;
    }

    /**
     * 对一组行做版本化写入。
     * @return 本组当前生效使用的版本号（复用时为旧版本，否则为新版本）。
     */
    public String writeVersionedGroup(VersionedGroupSpec spec) {
        if (!ALLOWED_TABLES.contains(spec.tableName)) {
            throw new IllegalArgumentException("表未登记白名单: " + spec.tableName);
        }
        safeIdent(spec.tableName);
        safeIdent(spec.versionColumn);
        spec.groupKeyColumns.keySet().forEach(VersionedV6Writer::safeIdent);
        spec.contentColumns.forEach(VersionedV6Writer::safeIdent);

        // 1) 读当前生效组内容
        List<Map<String, Object>> existing = loadCurrentGroup(spec);

        // 2) 内容相同 → 复用现有版本，不写
        if (rowsEqual(existing, spec)) {
            return currentVersion(spec);
        }

        // 3) 计算新版本号
        String newVersion = nextVersion(spec);

        // 4) 旧组下线
        if (!existing.isEmpty()) {
            buildWhere(em.createNativeQuery(
                "UPDATE " + spec.tableName + " SET is_current = FALSE WHERE "
                    + whereClause(spec) + " AND is_current = TRUE"), spec)
                .executeUpdate();
        }

        // 5) 写新组
        for (Map<String, Object> row : spec.newRows) {
            insertRow(spec, row, newVersion);
        }
        return newVersion;
    }

    private String whereClause(VersionedGroupSpec spec) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String col : spec.groupKeyColumns.keySet()) {
            if (i++ > 0) sb.append(" AND ");
            sb.append(col).append(" = :g_").append(col);
        }
        return sb.toString();
    }

    private Query buildWhere(Query q, VersionedGroupSpec spec) {
        for (Map.Entry<String, Object> e : spec.groupKeyColumns.entrySet()) {
            q.setParameter("g_" + e.getKey(), e.getValue());
        }
        return q;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadCurrentGroup(VersionedGroupSpec spec) {
        String cols = String.join(", ", spec.contentColumns);
        Query q = em.createNativeQuery(
            "SELECT " + cols + " FROM " + spec.tableName
                + " WHERE " + whereClause(spec) + " AND is_current = TRUE ORDER BY "
                + spec.contentColumns.get(0));
        buildWhere(q, spec);
        List<Object> raw = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : raw) {
            Object[] arr = (spec.contentColumns.size() == 1) ? new Object[]{r} : (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < spec.contentColumns.size(); i++) m.put(spec.contentColumns.get(i), arr[i]);
            out.add(m);
        }
        return out;
    }

    /** 行数 + 逐行 contentColumns 规范化值全等。existing 已按 contentColumns[0] 排序；newRows 同序比较。 */
    private boolean rowsEqual(List<Map<String, Object>> existing, VersionedGroupSpec spec) {
        if (existing.size() != spec.newRows.size()) return false;
        List<Map<String, Object>> sortedNew = new ArrayList<>(spec.newRows);
        sortedNew.sort(Comparator.comparing(m -> norm(m.get(spec.contentColumns.get(0)))));
        List<Map<String, Object>> sortedOld = new ArrayList<>(existing);
        sortedOld.sort(Comparator.comparing(m -> norm(m.get(spec.contentColumns.get(0)))));
        for (int i = 0; i < sortedNew.size(); i++) {
            for (String c : spec.contentColumns) {
                if (!norm(sortedNew.get(i).get(c)).equals(norm(sortedOld.get(i).get(c)))) return false;
            }
        }
        return true;
    }

    /** 规范化：数字用 stripTrailingZeros，其余 toString；null→"" （防 '12' vs '12.0' / null 误判）。 */
    private static String norm(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }

    private String currentVersion(VersionedGroupSpec spec) {
        Query q = em.createNativeQuery(
            "SELECT " + spec.versionColumn + " FROM " + spec.tableName
                + " WHERE " + whereClause(spec) + " AND is_current = TRUE LIMIT 1");
        buildWhere(q, spec);
        List<?> r = q.getResultList();
        return r.isEmpty() ? "2000" : String.valueOf(r.get(0));
    }

    /** max(数字版本)+1；无数字版本则 "2000"。非数字版本值（'V_DEFAULT' 等）被正则过滤。 */
    private String nextVersion(VersionedGroupSpec spec) {
        Query q = em.createNativeQuery(
            "SELECT MAX(CASE WHEN " + spec.versionColumn + " ~ '^[0-9]+$' THEN "
                + spec.versionColumn + "::int END) FROM " + spec.tableName
                + " WHERE " + whereClause(spec));
        buildWhere(q, spec);
        Object max = q.getSingleResult();
        return (max == null) ? "2000" : String.valueOf(((Number) max).intValue() + 1);
    }

    private void insertRow(VersionedGroupSpec spec, Map<String, Object> contentRow, String version) {
        Map<String, Object> all = new LinkedHashMap<>(spec.groupKeyColumns);
        all.putAll(contentRow);
        all.put(spec.versionColumn, version);
        all.put("is_current", true);
        List<String> cols = new ArrayList<>(all.keySet());
        cols.forEach(VersionedV6Writer::safeIdent);
        String colSql = String.join(", ", cols);
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) ph.append(", "); ph.append(":v").append(i); }
        Query q = em.createNativeQuery(
            "INSERT INTO " + spec.tableName + " (" + colSql + ") VALUES (" + ph + ")");
        for (int i = 0; i < cols.size(); i++) q.setParameter("v" + i, all.get(cols.get(i)));
        q.executeUpdate();
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run:
```bash
cd /home/joii/project/cpq/cpq-backend
./mvnw -q test -Dtest=VersionedV6WriterTest 2>&1 | tail -20
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 6: 后端编译/健康自检（CLAUDE.md）**

Run:
```bash
cd /home/joii/project/cpq/cpq-backend
touch src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java
sleep 8
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: `200`（或 401，不可 500）。

- [ ] **Step 7: Commit**

```bash
cd /home/joii/project/cpq
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/ \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/versioning/
git commit -m "feat(v6): 通用组级版本化写入工具 VersionedV6Writer + 集成测试"
```

---

## Self-Review（已执行）

- **Spec 覆盖:** 设计方案 §5.2「17 张表加 is_current」→ Task 2；§5.1「版本化语义」→ Task 3；§11 R1「is_effective 风险」→ Task 1。✅
- **占位符扫描:** 无 TBD/TODO；所有 SQL/Java/测试均为完整代码。✅（仅 DB 连接参数用 `$CPQ_DB_*` 环境变量占位，因密码不入库——执行者从 `application.properties` 取值）
- **类型一致性:** `writeVersionedGroup(VersionedGroupSpec)` 返回 `String`，测试与实现签名一致；`VersionedGroupSpec` 字段名（`tableName`/`versionColumn`/`groupKeyColumns`/`contentColumns`/`newRows`）测试与定义一致。✅

## 完成后移交
- P1 落地后，P2（工序压 `unit_price`）即可注入 `VersionedV6Writer`：`spec.tableName="unit_price"`、`versionColumn="version_no"`、`groupKeyColumns={system_type,price_type,cost_type,finished_material_no,code}`、`contentColumns=[operation_no,seq_no]`。
- `is_effective` 本期保持原样不动；P2/P3 改写工序/组合工艺 driver 视图时，新视图按 `WHERE is_current=true` 过滤选配写入的行（核价视图仍读 `is_effective`，不在本系列改动内）。
