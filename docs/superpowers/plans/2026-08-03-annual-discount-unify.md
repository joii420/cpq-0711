# 年降三 Sheet 落库统一 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「来料年降」「组装加工费年降」「年降系数」三个 Excel Sheet 的落库从"两张表三套语义"收敛成单表 `annual_discount`，用 `discount_type` 区分，三类统一走组级版本化 + pending 隔离。

**Architecture:** 就地改造现有空表 `annual_discount`（0 行 0 读者）：加 `discount_type` 判别列 + 单一 `target_no` 泛化目标列（语义由 type 决定）+ `customer_no` + 版本/pending 列。三个 `SheetHandler` 保留（编排器按 sheet 名分发），各自只声明 `discount_type` 与 `target_no` 来源，共用新的 `AnnualDiscountWriter` 落库。`annual_discount` 加入"版本化 + pending 俱乐部"需登记 7 处。

**Tech Stack:** Java 17 / Quarkus 3.34 / Hibernate Panache / PostgreSQL 16 / Flyway / JUnit5 + `@QuarkusTest`

**上游文档：**
- 方案定稿 `docs/superpowers/specs/2026-08-03-annual-discount-unify-design.md`
- 需求立项 `dev-docs/task-260708-导入报价单和导入核价单的数据落库规则澄清/repair-260804-年降三sheet的入库规则/需求文档.md`（AC-1~AC-17）

---

## 环境须知（每个 Task 都适用，先读）

**工作区**：`/home/joii/project/cpq/.claude/worktrees/repair-0804-annual-discount`，分支 `fix/repair-0804-annual-discount-unify`。

**跑后端测试必须带 flyway 容错**（否则 Quarkus 启动即失败）：

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0804-annual-discount/cpq-backend
./mvnw test -Dtest='<pattern>' -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

原因：共享测试库 `cpq_db` 里 `V368/369/370/371/373/374` 是并发 task-0729 会话应用的，master 上没有对应文件，Flyway validate 会报 "Detected applied migration not resolved locally"。**这是环境漂移，不是本次回归，不要为它改仓库里的任何配置文件。**

**基线**（改动前已实测）：`Q08*,Q15*,Q19*,AnnualDiscount*,QuoteImportValidatorTest` → 14 tests, 0 failures, 0 errors。

**dev server 是全会话共享的**：后端 8081 / 前端 5174 已在跑，**不要在 worktree 里另起**，也不要 `npm install`。探活：

```bash
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
```

**⚠️ 关键推论：8081 跑的是主工作区（`/home/joii/project/cpq`）的代码，不是本 worktree 的。**
所以在 worktree 阶段：

- 本分支新增的 Flyway 迁移**不会**被 8081 应用到 dev 库 —— `touch` java 文件对它无效；
- 迁移的实际应用与验证走 **`./mvnw test`**：Quarkus 测试启动时对**测试库 `cpq_db`** 跑 `migrate-at-start`；
- 因此**所有 DDL 验证 SQL 都查 `cpq_db`（测试库），不是 `cpq_db_0724`**；
- **真实 Excel 导入的端到端验证（AC-3/4/6/8~12）留到合并 master 之后做**（见 Task 9 §B）—— 那时 8081 才会加载本次代码并把 V377 应用到 dev 库。

**两个库不要搞混**：dev 库 = `10.177.152.12:5432/cpq_db_0724`（8081 连的，**worktree 阶段不碰**）；测试库 = `10.177.152.12:5432/cpq_db`（`mvnw test` 连的，**worktree 阶段的验证对象**）。连库口令 `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d <db>`。

**Flyway 纪律**：**绝不**手工 `psql -f V*.sql`，一律让 Quarkus 的 `migrate-at-start` 跑。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-backend/src/main/resources/db/migration/V377__annual_discount_unify.sql` | 表结构改造 + 清退役存量 | 新建 |
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/AnnualDiscount.java` | 实体映射对齐新 schema | 改 |
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java` | **三 Sheet 共用**：读 content 七列 / 组装 groupKey / 调版本化写入器 | 新建 |
| `.../quote/Q08IncomingAnnualDiscountHandler.java` | 只声明 `INCOMING_MATERIAL` + `target_no`=投入料号 | 改 |
| `.../quote/Q15AssemblyAnnualDiscountHandler.java` | 只声明 `ASSEMBLY_PROCESS` + `target_no`=解析后工序编号 | 改 |
| `.../quote/Q19AnnualDiscountHandler.java` | 只声明 `FINISHED` + `target_no`=null | 改 |
| `.../repository/AnnualDiscountRepository.java` | 旧行级 upsert，无调用方 | **删** |
| `.../quote/QuoteImportValidator.java` | Phase 1 年降顺序必填（三类） | 改 |
| `.../versioning/VersionedV6Writer.java` | 登记点 1、2 | 改 |
| `.../datasource/sqlview/QuotePendingRewriter.java` | 登记点 3 | 改 |
| `.../quotation/service/backfill/QuoteTableAxis.java` | 登记点 4 | 改 |
| `.../basicdata/v6/service/V6QuotationCommitService.java` | 登记点 5 | 改 |
| `.../basicdata/v6/quote/QuoteImportService.java` | 登记点 6 | 改 |
| `.../quotation/service/QuotationService.java` | 登记点 7 | 改 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/AnnualDiscountBatchUpsertEquivTest.java` | 测的是被删的 upsert 路径 | **删** |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandlerTest.java` | 断言改指 `annual_discount` | 改 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandlerTest.java` | 断言改指 `annual_discount` | 改 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandlerTest.java` | 新建（原来没有 Q19 测试） | 新建 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountRegistrationPointsTest.java` | 7 登记点的可执行断言 | 新建 |
| `docs/table/报价系统Excel导入落库方案.md` | §8 / §15 / §19 改写 | 改 |
| `docs/RECORD.md` | 开发记录 | 改 |

**⚠️ 任务顺序有硬依赖**：Task 1 落 DDL 后会删掉 `discount_strategy` 列，而旧 `AnnualDiscountRepository` 的 INSERT 仍写该列 —— **「年降系数」的导入路径从 Task 1 结束到 Task 4 完成期间是坏的**。这是有意为之（DDL 与代码不可能原子切换），Task 1 同时删掉唯一会因此变红的测试，保证单测始终绿。**不要在 Task 1~3 之间去 dev 环境跑年降系数导入。**

---

### Task 1: Flyway 迁移 —— `annual_discount` 表结构改造

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V377__annual_discount_unify.sql`
- Delete: `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/AnnualDiscountBatchUpsertEquivTest.java`

- [ ] **Step 1: 确认迁移编号没被抢占**

共享 Flyway 历史是移动靶。先查两个库的最大版本号：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT max(version::numeric) FROM flyway_schema_history;"
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA \
  -c "SELECT max(version::numeric) FROM flyway_schema_history;"
ls cpq-backend/src/main/resources/db/migration/V37*.sql
```

预期：两库均为 `376`，本地文件最大 `V376`。**若已有人占用 377，改用下一个空号并同步改本 Task 后续所有命令里的编号。**

- [ ] **Step 2: 写迁移文件**

创建 `cpq-backend/src/main/resources/db/migration/V377__annual_discount_unify.sql`：

```sql
-- =====================================================================
-- V377: repair-0804 年降三 Sheet 落库统一（annual_discount 单表化）
-- ---------------------------------------------------------------------
-- 背景：「来料年降」「组装加工费年降」落 unit_price（price_type=
--   INCOMING_MATERIAL_REDUCTION / COMPONENT_REDUCTION），「年降系数」落
--   annual_discount 且无版本化/无 pending 隔离/无客户维度。三者业务同构，
--   本次收敛到单表 annual_discount，用 discount_type 区分。
--
-- 前提（2026-08-03 实测）：annual_discount 0 行；unit_price 两个退役
--   price_type 共 1 行；三条路径均无任何读取方（SQL 视图/组件/Java 皆 0）。
--   故无数据迁移，直接改结构 + 清退役存量。
--
-- 详见 docs/superpowers/specs/2026-08-03-annual-discount-unify-design.md
-- =====================================================================

-- ---- 1) 判别列：biz_type → discount_type，取值细化为三个 Sheet ----
ALTER TABLE annual_discount DROP CONSTRAINT IF EXISTS chk_annual_discount_biz_type;
ALTER TABLE annual_discount RENAME COLUMN biz_type TO discount_type;
ALTER TABLE annual_discount ALTER COLUMN discount_type TYPE VARCHAR(30);
ALTER TABLE annual_discount ADD CONSTRAINT chk_annual_discount_type
    CHECK (discount_type IN ('INCOMING_MATERIAL', 'ASSEMBLY_PROCESS', 'FINISHED'));

COMMENT ON COLUMN annual_discount.discount_type IS
    'INCOMING_MATERIAL=来料年降 / ASSEMBLY_PROCESS=组装加工费年降 / FINISHED=年降系数(整单级)';

-- ---- 2) 新增列 ----
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS system_type VARCHAR(10) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE annual_discount ALTER COLUMN system_type DROP DEFAULT;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS customer_no VARCHAR(20);
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS target_no   VARCHAR(30);
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS seq_no      INTEGER;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS version_no  VARCHAR(20) NOT NULL DEFAULT '2000';
ALTER TABLE annual_discount ALTER COLUMN version_no DROP DEFAULT;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS is_current  BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS pending_quotation_id UUID;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS pending_supersedes   UUID[];

COMMENT ON COLUMN annual_discount.target_no IS
    '年降挂载目标，语义由 discount_type 决定：INCOMING_MATERIAL=材质料号 / ASSEMBLY_PROCESS=工序编号 / FINISHED=NULL。名称不冗余存，由视图 JOIN material_recipe / process_master 取';

-- ---- 3) 删除无写入方无读取方的列 ----
ALTER TABLE annual_discount DROP COLUMN IF EXISTS discount_strategy;  -- 被 discount_type 取代
ALTER TABLE annual_discount DROP COLUMN IF EXISTS discount_base;      -- 建表至今从未写入

-- ---- 4) 唯一键重建（口径同 uq_unit_price：groupKey ∪ 版本列 ∪ 组内行区分列） ----
DROP INDEX IF EXISTS uq_annual_discount;
CREATE UNIQUE INDEX uq_annual_discount ON annual_discount(
    system_type,
    discount_type,
    material_no,
    COALESCE(customer_no, ''),
    COALESCE(target_no, ''),
    version_no,
    COALESCE(discount_order, 0)
);
COMMENT ON INDEX uq_annual_discount IS
    'V6 annual_discount 业务唯一键（7 维）；discount_order 为组内行区分列，其余为 groupKey + 版本列';

-- ---- 5) pending 部分索引（对齐 V349 给 7 张版本化表建的那批） ----
CREATE INDEX IF NOT EXISTS ix_annual_discount_pending
    ON annual_discount(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;

-- ---- 6) 清除 unit_price 中两个退役 price_type 的存量 ----
-- CHECK 约束 chk_unit_price_type 里保留这两个枚举值不动（动 CHECK 收益为零）
DELETE FROM unit_price WHERE price_type IN ('INCOMING_MATERIAL_REDUCTION', 'COMPONENT_REDUCTION');
```

- [ ] **Step 3: 删掉即将失效的护栏测试**

`AnnualDiscountBatchUpsertEquivTest` 测的是 `AnnualDiscountRepository.upsertOne/upsertBatch` 的等价性，其 SQL 写 `discount_strategy` 列 —— DDL 一落它必红，而该 repository 本就要在 Task 4 整体删除。

```bash
git rm cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/AnnualDiscountBatchUpsertEquivTest.java
```

- [ ] **Step 4: 让 Quarkus 测试启动时自动跑迁移**

worktree 里唯一会加载本分支代码的 Quarkus 进程就是测试进程，所以用一次测试把 V377 应用到**测试库 `cpq_db`**（8081 跑的是主工作区代码，`touch` 对它无效 —— 见开头「环境须知」）：

```bash
cd cpq-backend && ./mvnw test -Dtest='QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：日志出现 `Migrating schema "public" to version "377 - annual discount unify"`，测试通过。

- [ ] **Step 5: 验证迁移成功（AC-1 / AC-2 / AC-13）**

**查测试库 `cpq_db`**（不是 `cpq_db_0724` —— dev 库要等合并后由 8081 应用）：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA -F' | ' -c "
SELECT version, success FROM flyway_schema_history WHERE version='377';
SELECT column_name, is_nullable FROM information_schema.columns
 WHERE table_name='annual_discount' ORDER BY ordinal_position;
SELECT indexdef FROM pg_indexes WHERE indexname IN ('uq_annual_discount','ix_annual_discount_pending');
SELECT count(*) FROM unit_price WHERE price_type IN ('INCOMING_MATERIAL_REDUCTION','COMPONENT_REDUCTION');"
```

期望：
- `377 | t`
- 列清单含 `discount_type / system_type / customer_no / target_no / seq_no / version_no / is_current / pending_quotation_id / pending_supersedes`；**不含** `biz_type / discount_strategy / discount_base`
- 两个索引都在，`uq_annual_discount` 是 7 列表达式唯一索引
- 最后一行 `count = 0`

- [ ] **Step 6: 确认单测仍绿**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q08*,Q15*,QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：13 tests（14 减去已删的 1 个），0 failures, 0 errors。
（Q08/Q15 此刻仍写 `unit_price`，与本次 DDL 无关，故仍绿。）

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V377__annual_discount_unify.sql
git commit -m "feat(repair-0804): V377 annual_discount 单表化 DDL + 清 unit_price 退役存量

discount_type 判别 + target_no 泛化目标列 + customer_no + 版本/pending 列；
删 discount_strategy/discount_base；重建 7 维唯一键 + pending 部分索引。
同时删除测被废弃 upsert 路径的 AnnualDiscountBatchUpsertEquivTest。" -- \
  cpq-backend/src/main/resources/db/migration/V377__annual_discount_unify.sql \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/AnnualDiscountBatchUpsertEquivTest.java
```

---

### Task 2: `AnnualDiscount` 实体对齐新 schema

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/AnnualDiscount.java`

- [ ] **Step 1: 改写实体**

把整个文件替换为：

```java
package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * V6 §23 年降表（repair-0804 单表化）：三个报价 Sheet 共用。
 *
 * <p>groupKey = (system_type, customer_no, discount_type, material_no, target_no)；
 * 组内行区分列 = discount_order；其余为 content。版本化 + pending 语义与
 * unit_price / capacity 等 7 张表一致，见 {@code VersionedV6Writer}。
 */
@Entity
@Table(name = "annual_discount")
public class AnnualDiscount extends V6BaseEntity {

    /** QUOTE / PRICING（当前只有 QUOTE 写入，核价侧无年降 Sheet）。 */
    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    /** INCOMING_MATERIAL=来料年降 / ASSEMBLY_PROCESS=组装加工费年降 / FINISHED=年降系数。 */
    @Column(name = "discount_type", nullable = false, length = 30)
    public String discountType;

    @Column(name = "customer_no", length = 20)
    public String customerNo;

    /** 销售料号（主轴）。 */
    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    /**
     * 年降挂载目标，语义由 {@link #discountType} 决定：
     * INCOMING_MATERIAL → 材质料号；ASSEMBLY_PROCESS → 工序编号；FINISHED → null。
     * 名称不冗余存，由视图 JOIN material_recipe / process_master 取。
     */
    @Column(name = "target_no", length = 30)
    public String targetNo;

    @Column(name = "seq_no")
    public Integer seqNo;

    @Column(name = "discount_order", nullable = false)
    public Integer discountOrder;

    @Column(name = "discount_ratio", precision = 10, scale = 4)
    public BigDecimal discountRatio;

    @Column(name = "fixed_discount_value", precision = 18, scale = 6)
    public BigDecimal fixedDiscountValue;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "discount_times")
    public Integer discountTimes;

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "is_current", nullable = false)
    public Boolean isCurrent;

    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;
}
```

> 📌 `pending_supersedes`（`UUID[]`）不映射为实体字段 —— 与 `UnitPrice` 等 7 张表的现有做法一致，该列只由 `VersionedV6Writer` 的原生 SQL 读写。

- [ ] **Step 2: 编译验证**

```bash
cd cpq-backend && ./mvnw -q compile
```

期望：BUILD SUCCESS（无输出即成功）。

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(repair-0804): AnnualDiscount 实体对齐单表化后的 schema" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/AnnualDiscount.java
```

---

### Task 3: `AnnualDiscountWriter` —— 三 Sheet 共用的读列与写入器

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriterTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriterTest.java`：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.SheetRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0804：共享读列口径的纯函数单测（不需要 CDI/DB）。
 * 关键点：三个 Sheet 的表头不同名（年降系数（%） vs 年降系数（%/年）、
 * 单次固定年降值 vs 单次固定年降金额），靠 SheetRow 的 contains 匹配用一套 key 全覆盖。
 */
class AnnualDiscountWriterTest {

    private SheetRow row(Map<String, String> cells) {
        return new SheetRow(1, new LinkedHashMap<>(cells));
    }

    @Test void readContent_incomingSheetHeaders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-001");
        m.put("项次", "3");
        m.put("投入料号", "AgNi11");
        m.put("投入料号名称", "银镍11");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        m.put("单次固定年降值", "12.5");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "3");

        Map<String, Object> c = AnnualDiscountWriter.readContent(row(m));

        assertEquals(1, c.get("discount_order"));
        assertEquals(0, new BigDecimal("5.5").compareTo((BigDecimal) c.get("discount_ratio")));
        assertEquals(0, new BigDecimal("12.5").compareTo((BigDecimal) c.get("fixed_discount_value")));
        assertEquals("CNY", c.get("currency"));
        assertEquals("PCS", c.get("unit"));
        assertEquals(3, c.get("discount_times"));
        assertEquals(3, c.get("seq_no"));
    }

    @Test void readContent_finishedSheetHeaders_differentColumnNames() {
        // 年降系数 sheet：列名是「年降系数（%/年）」「单次固定年降金额」，且没有「项次」列
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-001");
        m.put("年降顺序", "2");
        m.put("年降系数（%/年）", "3.25");
        m.put("单次固定年降金额", "8");
        m.put("货币", "USD");
        m.put("计价单位", "KG");
        m.put("降价次数", "5");

        Map<String, Object> c = AnnualDiscountWriter.readContent(row(m));

        assertEquals(2, c.get("discount_order"));
        assertEquals(0, new BigDecimal("3.25").compareTo((BigDecimal) c.get("discount_ratio")));
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) c.get("fixed_discount_value")));
        assertEquals("USD", c.get("currency"));
        assertEquals(5, c.get("discount_times"));
        assertNull(c.get("seq_no"), "年降系数 sheet 无「项次」列，seq_no 必须为 null");
    }

    @Test void readContent_keysMatchContentColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("年降顺序", "1");
        assertEquals(
            new java.util.HashSet<>(AnnualDiscountWriter.CONTENT),
            AnnualDiscountWriter.readContent(row(m)).keySet(),
            "readContent 的键集必须与 CONTENT 列清单完全一致，否则写入器会漏列或抛非法列");
    }

    @Test void groupKey_hasAllFiveAxisColumns() {
        Map<String, Object> g = AnnualDiscountWriter.groupKey(
            "INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        assertEquals("QUOTE", g.get("system_type"));
        assertEquals("C001", g.get("customer_no"));
        assertEquals("INCOMING_MATERIAL", g.get("discount_type"));
        assertEquals("S-001", g.get("material_no"));
        assertEquals("AgNi11", g.get("target_no"));
        assertEquals(5, g.size(), "groupKey 必须恰好 5 列，多一列会把本该同组的行拆散");
    }

    @Test void groupKey_targetNoNullable() {
        Map<String, Object> g = AnnualDiscountWriter.groupKey("FINISHED", "C001", "S-001", null);
        assertNull(g.get("target_no"));
        assertTrue(g.containsKey("target_no"), "target_no 为 null 也必须在 key 里（NULL 安全比较靠 IS NOT DISTINCT FROM）");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='AnnualDiscountWriterTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：编译失败 —— `cannot find symbol: class AnnualDiscountWriter`。

- [ ] **Step 3: 实现 `AnnualDiscountWriter`**

创建 `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java`：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * repair-0804：「来料年降」「组装加工费年降」「年降系数」三个 Sheet 共用的落库写入器。
 *
 * <p>三者业务同构，差异只有两点，由各自 Handler 声明：
 * <ul>
 *   <li>{@code discount_type}：INCOMING_MATERIAL / ASSEMBLY_PROCESS / FINISHED</li>
 *   <li>{@code target_no}：投入料号（材质料号） / 解析后的工序编号 / null</li>
 * </ul>
 * 其余（读 content 七列、组装 groupKey、组级版本化写入、pending 归属）全在本类。
 *
 * <p><b>为什么一套读列 key 能吃三个 Sheet</b>：{@link SheetRow#getStr} 是 contains 匹配，
 * 故 {@code "年降系数"} 同时命中「年降系数（%）」与「年降系数（%/年）」，
 * {@code "单次固定年降"} 同时命中「单次固定年降值」与「单次固定年降金额」。
 */
@ApplicationScoped
public class AnnualDiscountWriter {

    @Inject VersionedV6Writer writer;

    @ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    public static final String TABLE = "annual_discount";
    public static final String VERSION_COLUMN = "version_no";

    /** 组内逐行可能不同的列。行集维度 = discount_order（在 uq_annual_discount 内）。 */
    public static final List<String> CONTENT = List.of(
        "discount_order", "discount_ratio", "fixed_discount_value",
        "currency", "unit", "discount_times", "seq_no");

    /** 读一行的 content 七列。键集必须恒等于 {@link #CONTENT}。 */
    public static Map<String, Object> readContent(SheetRow row) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("discount_order", row.getInt("年降顺序"));
        c.put("discount_ratio", row.getDecimal("年降系数"));
        c.put("fixed_discount_value", row.getDecimal("单次固定年降"));  // 比例/固定二选一，空值留 NULL
        c.put("currency", row.getStr("货币"));
        c.put("unit", row.getStr("计价单位"));
        c.put("discount_times", row.getInt("降价次数"));
        c.put("seq_no", row.getInt("项次"));
        return c;
    }

    /** 组装 5 列 groupKey。{@code targetNo} 允许为 null（FINISHED / 组装工序留空）。 */
    public static Map<String, Object> groupKey(String discountType, String customerNo,
                                               String materialNo, String targetNo) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("system_type", "QUOTE");
        g.put("customer_no", customerNo);
        g.put("discount_type", discountType);
        g.put("material_no", materialNo);
        g.put("target_no", targetNo);
        return g;
    }

    /**
     * 把 Handler 攒好的分组写入 {@code annual_discount}。
     * {@code versionTriggerColumns} 传 null = 任何内容变化即升版（与 Q08/Q15 改造前口径一致）。
     *
     * @param groupKeyOf Handler 内部 key → groupKey 列值
     * @param contentOf  Handler 内部 key → 该组的行集
     */
    public void write(Map<List<Object>, Map<String, Object>> groupKeyOf,
                      Map<List<Object>, List<Map<String, Object>>> contentOf,
                      SheetImportResult result, UUID pendingQuotationId) {
        if (contentOf.isEmpty()) return;
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            }
            try {
                writer.writeVersionedGroups(TABLE, VERSION_COLUMN, CONTENT, null,
                    List.of(), groups, pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values()) {
                    result.recordWrite(TABLE, groupRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        TABLE, VERSION_COLUMN, groupKeyOf.get(e.getKey()), CONTENT,
                        e.getValue(), null, pendingQuotationId));
                    result.recordWrite(TABLE, e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
    }

    /** Handler 攒分组用的小工具：把一行塞进对应的组。 */
    public static void accumulate(Map<List<Object>, Map<String, Object>> groupKeyOf,
                                  Map<List<Object>, List<Map<String, Object>>> contentOf,
                                  List<Object> key, Map<String, Object> gk, Map<String, Object> content) {
        groupKeyOf.putIfAbsent(key, gk);
        contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(content);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='AnnualDiscountWriterTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 5: 把 `annual_discount` 加进写入器白名单（登记点 1、2）**

不加白名单，Task 4~6 的写入会直接抛 `表未登记白名单`。

修改 `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java`：

`ALLOWED_TABLES` 加一项（原为 12 张表）：

```java
    /** 允许写入的表（白名单）。新增表在此登记。 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "unit_price", "capacity", "plating_scheme",
        "element_bom", "element_bom_item",
        "material_bom", "material_bom_item",
        "labor_rate", "production_energy", "auxiliary_energy", "tooling_cost", "exchange_rate_v6",
        "annual_discount");
```

`SYSTEM_TYPE_SCOPED` 加一项：

```java
    /** 必须按 system_type 维度隔离的表：groupKey 缺 system_type 会导致 flip/版本号跨 QUOTE/PRICING 污染。 */
    private static final Set<String> SYSTEM_TYPE_SCOPED = Set.of(
        "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme",
        "labor_rate", "production_energy", "auxiliary_energy", "tooling_cost",
        "annual_discount");
```

- [ ] **Step 6: 编译验证**

```bash
cd cpq-backend && ./mvnw -q compile
```

期望：BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriterTest.java
git commit -m "feat(repair-0804): AnnualDiscountWriter 三 Sheet 共用读列与版本化写入 + 写入器白名单登记

一套 contains key 覆盖三个 Sheet 的异名表头（年降系数（%）/（%/年）、
单次固定年降值/金额）；groupKey 5 列；登记点 1、2（ALLOWED_TABLES +
SYSTEM_TYPE_SCOPED）。" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriterTest.java \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java
```

---

### Task 4: Q19「年降系数」切到新表 + 删除 `AnnualDiscountRepository`

> 先切 Q19：它是 Task 1 的 DDL 唯一打断的运行时路径，尽早修复。

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandler.java`
- Delete: `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/AnnualDiscountRepository.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandlerTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

创建 `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandlerTest.java`：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** repair-0804：Q19 年降系数 → annual_discount(discount_type=FINISHED) 版本化。 */
@QuarkusTest
class Q19AnnualDiscountHandlerTest {

    @Inject Q19AnnualDiscountHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q19-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000019");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no=:m")
          .setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int order, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("年降顺序", String.valueOf(order));
        m.put("年降系数（%/年）", ratio);
        m.put("单次固定年降金额", "");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "3");
        return new SheetRow(order, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM annual_discount WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM annual_discount WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Transactional
    @Test void writesFinishedTypeWithNullTarget() {
        handler.handle(List.of(row(1, "5.5")), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, system_type, customer_no, target_no, discount_times, seq_no " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult();

        assertEquals("FINISHED", r[0]);
        assertEquals("QUOTE", r[1]);
        assertEquals("C1", r[2], "年降系数以前没有客户维度，本次必须补上");
        assertNull(r[3], "FINISHED 类型无挂载目标，target_no 必须为 null");
        assertEquals(3, ((Number) r[4]).intValue());
        assertNull(r[5], "年降系数 sheet 无「项次」列");
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }

    @Transactional
    @Test void changeValue_bumpsVersion() {
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        handler.handle(List.of(row(1, "5.5"), row(2, "2.0")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total(), "老组保留但 is_current=false");
    }

    @Transactional
    @Test void differentCustomers_coexist() {
        ImportContext c1 = ctx();
        ImportContext c2 = ctx(); c2.customerNo = "C2";
        handler.handle(List.of(row(1, "5.5")), c1);
        handler.handle(List.of(row(1, "9.9")), c2);
        assertEquals(2L, total(), "同料号不同客户必须并存，不得互相覆盖（改造前会覆盖）");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q19AnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：全部 4 个失败 —— 旧 handler 走 `AnnualDiscountRepository` 写 `discount_strategy` 列，该列已被 V377 删除，SQL 报错。

- [ ] **Step 3: 改写 Q19 handler**

把 `Q19AnnualDiscountHandler.java` 整个文件替换为：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q19 年降系数 → annual_discount（{@code discount_type=FINISHED}，整单级年降）。
 *
 * <p>repair-0804：原走 {@code AnnualDiscountRepository} 的行级 upsert（空值不覆盖、无版本化、
 * 无 pending 隔离、无客户维度），现统一为组级版本化写入。
 * <p>groupKey=(QUOTE, customer_no, FINISHED, material_no, target_no=null)；行集维度=discount_order。
 */
@ApplicationScoped
public class Q19AnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "年降系数"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            // 年降顺序必填由 Phase 1 拦截；此处只做兜底（Phase 1 已全量校验，走到这里属竞态）
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }
            // FINISHED 为整单级年降，无挂载目标
            List<Object> key = Arrays.asList(materialNo, null);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("FINISHED", ctx.customerNo, materialNo, null),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
```

- [ ] **Step 4: 删除 `AnnualDiscountRepository`**

```bash
git rm cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/AnnualDiscountRepository.java
```

确认无残留引用：

```bash
/usr/bin/grep -arn "AnnualDiscountRepository" cpq-backend/src --include=*.java
```

期望：**无输出**。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q19AnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandlerTest.java
git commit -m "feat(repair-0804): Q19 年降系数切到 annual_discount 组级版本化 + 删 AnnualDiscountRepository

补齐 customer_no 客户维度（改造前同料号跨客户会静默覆盖）；写入语义由
行级 upsert（空值不覆盖）改为组级整组替换；接入 pending 隔离。" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandler.java \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/AnnualDiscountRepository.java \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandlerTest.java
```

---

### Task 5: Q08「来料年降」切到新表

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandlerTest.java`

- [ ] **Step 1: 改写测试（断言改指 `annual_discount`）**

把 `Q08IncomingAnnualDiscountHandlerTest.java` 整个文件替换为：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** repair-0804：Q08 来料年降 → annual_discount(discount_type=INCOMING_MATERIAL, target_no=投入料号)。 */
@QuarkusTest
class Q08IncomingAnnualDiscountHandlerTest {

    @Inject Q08IncomingAnnualDiscountHandler handler;
    @Inject EntityManager em;

    static final String TARGET = "TEST-Q08-CODE";
    static final String MAT = "TEST-Q08-FMN";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000008");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no=:m")
          .setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int order, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("项次", String.valueOf(order));
        m.put("投入料号", TARGET);
        m.put("投入料号名称", "不导入的名称");
        m.put("年降顺序", String.valueOf(order));
        m.put("年降系数（%）", ratio);
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "2");
        return new SheetRow(order, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM annual_discount WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM annual_discount WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Transactional
    @Test void writesIncomingTypeWithTargetNo() {
        handler.handle(List.of(row(1, "5.5")), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, target_no, customer_no, seq_no, discount_times " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult();

        assertEquals("INCOMING_MATERIAL", r[0]);
        assertEquals(TARGET, r[1], "target_no 存投入料号（材质料号）原样，不 resolve 不铸号");
        assertEquals("C1", r[2]);
        assertEquals(1, ((Number) r[3]).intValue(), "项次此前被丢弃，本次必须落库");
        assertEquals(2, ((Number) r[4]).intValue(), "降价次数此前被丢弃，本次必须落库");
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }

    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        handler.handle(List.of(row(1, "0.95"), row(2, "0.80")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }

    @Transactional
    @Test void blankInputPartNo_recordsError() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("投入料号", "");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        var result = handler.handle(List.of(new SheetRow(1, m)), ctx());
        assertEquals(1, result.failedRows);
        assertEquals(0L, total());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q08IncomingAnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：全部失败 —— 旧 handler 仍写 `unit_price`，`annual_discount` 查不到行。

- [ ] **Step 3: 改写 Q08 handler**

把 `Q08IncomingAnnualDiscountHandler.java` 整个文件替换为：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q08 来料年降 → annual_discount（{@code discount_type=INCOMING_MATERIAL}）。
 *
 * <p>groupKey=(QUOTE, customer_no, INCOMING_MATERIAL, material_no=销售料号,
 * target_no=投入料号)；行集维度=discount_order。
 *
 * <p>task-0717 扩围：投入料号=材质料号，恒按材质处理 —— 原始码直接作 {@code target_no}，
 * 不 resolve、不铸号、不登记 material_customer_map、不登记 material_master
 * （名称走 material_recipe 兜底，年降表不冗余存名称）。
 */
@ApplicationScoped
public class Q08IncomingAnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "来料年降"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            // exact 而非 getStr：避开 contains 命中「投入料号名称」列
            String targetNo = row.exact("投入料号");
            if (targetNo == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
            String materialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }
            List<Object> key = Arrays.asList(materialNo, targetNo);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", ctx.customerNo, materialNo, targetNo),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q08IncomingAnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(repair-0804): Q08 来料年降切到 annual_discount，target_no 存投入料号

补回此前丢弃的 seq_no（项次）与 discount_times（降价次数）。" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandlerTest.java
```

---

### Task 6: Q15「组装加工费年降」切到新表

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandlerTest.java`

- [ ] **Step 1: 先读现有测试，保留其工序解析用例**

```bash
cat cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandlerTest.java
```

现有 3 个用例覆盖 repair-0727 的工序解析（从 `ctx.sharedCache["assemblyProcessNo"]` 取值），**这部分语义本次不变，必须原样保留**，只把断言的目标表从 `unit_price` 改成 `annual_discount`、列名从 `operation_no` 改成 `target_no`、`code` 改成 `material_no`。

- [ ] **Step 2: 改写测试断言**

在保留原有 3 个用例结构的前提下做这些替换（逐处）：

| 原 | 改为 |
|---|---|
| `DELETE FROM unit_price WHERE code=:c` | `DELETE FROM annual_discount WHERE material_no=:m` |
| `SELECT version_no FROM unit_price WHERE code=:c AND is_current=true` | `SELECT version_no FROM annual_discount WHERE material_no=:m AND is_current=true` |
| `SELECT count(*) FROM unit_price WHERE code=:c` | `SELECT count(*) FROM annual_discount WHERE material_no=:m` |
| 断言 `operation_no` | 断言 `target_no` |

并新增一个用例，钉住类型与空工序：

```java
    @Transactional
    @Test void writesAssemblyTypeAndAllowsNullProcess() {
        // 「组装工序」列留空 → target_no 为 null（允许），discount_type 恒 ASSEMBLY_PROCESS
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("项次", "1");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "2");
        handler.handle(List.of(new SheetRow(1, m)), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, target_no, customer_no, seq_no, discount_times " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult();

        assertEquals("ASSEMBLY_PROCESS", r[0]);
        assertNull(r[1], "组装工序允许为空 → target_no 为 null");
        assertEquals("C1", r[2]);
        assertEquals(1, ((Number) r[3]).intValue(), "项次此前被丢弃，本次必须落库");
        assertEquals(2, ((Number) r[4]).intValue(), "降价次数此前被丢弃，本次必须落库");
    }
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q15AssemblyAnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：全部失败 —— 旧 handler 仍写 `unit_price`。

- [ ] **Step 4: 改写 Q15 handler**

把 `Q15AssemblyAnnualDiscountHandler.java` 整个文件替换为：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.ProcessNoResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q15 组装加工费年降 → annual_discount（{@code discount_type=ASSEMBLY_PROCESS}）。
 *
 * <p>groupKey=(QUOTE, customer_no, ASSEMBLY_PROCESS, material_no=销售料号,
 * target_no=真工序编号)；行集维度=discount_order。
 *
 * <p>repair-0727：「组装工序」列原始值已在 Phase 1
 * （{@code QuoteImportValidator#validateAssemblyAnnualDiscount}）解析为真工序编号，本 handler
 * 只从 {@code ctx.sharedCache["assemblyProcessNo"]} 取回落 {@code target_no}，<b>不写名称</b>
 * （名称由视图 JOIN process_master 取）。该列<b>允许为空</b> → {@code target_no} 为 null。
 */
@ApplicationScoped
public class Q15AssemblyAnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "组装加工费年降"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        @SuppressWarnings("unchecked")
        Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo =
            (Map<List<String>, ProcessNoResolver.Resolved>) ctx.sharedCache.get("assemblyProcessNo");
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }

            String rawProcess = row.getStr("组装工序");
            String targetNo = null;
            if (rawProcess != null) {
                ProcessNoResolver.Resolved resolved = assemblyProcessNo == null ? null
                    : assemblyProcessNo.get(List.of("组装加工费年降", materialNo.strip(), rawProcess.strip()));
                if (resolved == null) {
                    result.recordError(row.rowNo, "组装工序",
                        "工序「" + rawProcess + "」未在 Phase 1 解析结果中找到（Phase 1 理论上已全量拦截，"
                            + "此处出现属竞态/数据不一致），导入中止");
                    continue;
                }
                targetNo = resolved.processNo();
            }

            List<Object> key = Arrays.asList(materialNo, targetNo);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("ASSEMBLY_PROCESS", ctx.customerNo, materialNo, targetNo),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
```

> ⚠️ 与改造前的一处语义变化：原 handler 把「销售料号」同时写进 `code` 与 `finished_material_no` 两列（因 `unit_price.code` NOT NULL）；新表只有 `material_no` 一列，冗余消失。Phase 1 缓存 key 的第二段改用 `materialNo`（原为 `code`，两者本就是同一个值）。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='Q15AssemblyAnnualDiscountHandlerTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(repair-0804): Q15 组装加工费年降切到 annual_discount，target_no 存真工序编号

保留 repair-0727 的 Phase 1 工序解析语义（两段匹配 + 组级作废 + 允许为空）。" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandler.java \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandlerTest.java
```

---

### Task 7: Phase 1 —— 年降顺序三类统一必填

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorTest.java`

- [ ] **Step 1: 写失败的测试**

在 `QuoteImportValidatorTest.java` 的类体末尾追加（沿用该文件已有的 `validator` / `ctx()` / `sheetRow` 构造惯例；若辅助方法名不同，按文件里现成的写法调整）：

```java
    @Test void annualDiscountOrder_blank_isRejectedInPhase1() {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("销售料号", "S-001");
        bad.put("投入料号", "AgNi11");
        bad.put("年降顺序", "");            // ← 空
        bad.put("年降系数（%）", "5.5");

        Map<String, List<SheetRow>> sheets = new LinkedHashMap<>();
        sheets.put("来料年降", List.of(new SheetRow(2, bad)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertTrue(out.hasErrors(), "年降顺序为空必须在 Phase 1 拦截（零写库）");
        SheetImportResult r = out.bySheet.get("来料年降");
        assertEquals(1, r.totalRows);
        assertEquals(0, r.successRows);
        assertEquals(1, r.failedRows);
        assertEquals(r.totalRows, r.successRows + r.failedRows,
            "totalRows == successRows + failedRows 不变量必须成立");
    }

    @Test void annualDiscountOrder_blank_rejectedForAllThreeSheets() {
        for (String sheet : List.of("来料年降", "组装加工费年降", "年降系数")) {
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put("销售料号", "S-001");
            bad.put("投入料号", "AgNi11");
            bad.put("年降顺序", "");
            Map<String, List<SheetRow>> sheets = new LinkedHashMap<>();
            sheets.put(sheet, List.of(new SheetRow(2, bad)));

            QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());
            assertTrue(out.hasErrors(), sheet + "：年降顺序为空必须被拦截");
            assertEquals(1, out.bySheet.get(sheet).failedRows, sheet + "：失败行数应为 1");
        }
    }

    @Test void annualDiscountOrder_present_passes() {
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("销售料号", "S-001");
        ok.put("年降顺序", "1");
        ok.put("年降系数（%/年）", "3.0");

        Map<String, List<SheetRow>> sheets = new LinkedHashMap<>();
        sheets.put("年降系数", List.of(new SheetRow(2, ok)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());
        assertEquals(0, out.bySheet.get("年降系数").failedRows);
        assertEquals(1, out.bySheet.get("年降系数").successRows);
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：新增 3 个用例失败 —— 目前「来料年降」「年降系数」落在 `validate()` 末尾的"仅计数不深校验"兜底循环里，`failedRows` 恒 0。

- [ ] **Step 3: 新增校验方法**

在 `QuoteImportValidator.java` 里，`validateAssemblyAnnualDiscount` 方法之后加入：

```java
    /**
     * repair-0804：年降顺序三类统一必填。{@code discount_order} 是 annual_discount 组内
     * <b>唯一的行区分维度</b>（在 uq_annual_discount 内），为空则同组多行撞唯一键。
     *
     * <p>「组装加工费年降」不走本方法 —— 它已有 {@link #validateAssemblyAnnualDiscount} 占用
     * 自己的 sheet 结果桶，年降顺序校验内联在那里（见该方法），避免同一 sheet 被两个校验器
     * 各记一次 totalRows 导致计数翻倍。
     */
    private void validateAnnualDiscountOrder(String sheetName, List<SheetRow> rows, Outcome out) {
        SheetImportResult r = result(out, sheetName);
        for (SheetRow row : rows) {
            r.totalRows++;
            if (row.getInt("年降顺序") == null) {
                r.recordError(row.rowNo, "年降顺序", "为空（年降顺序是同一组年降内区分多行的唯一维度，必填）");
                continue;
            }
            r.successRows++;
        }
    }
```

- [ ] **Step 4: 在 `validate()` 里挂上两个 Sheet**

在 `validate()` 方法里，紧跟 `validateAssemblyAnnualDiscount(...)` 那一行之后插入：

```java
        // repair-0804：年降顺序三类统一必填（组装加工费年降的同名校验内联在上面那个方法里）
        validateAnnualDiscountOrder("来料年降", sheetsByName.getOrDefault("来料年降", List.of()), out);
        validateAnnualDiscountOrder("年降系数", sheetsByName.getOrDefault("年降系数", List.of()), out);
```

- [ ] **Step 5: 给「组装加工费年降」内联同一校验**

在 `validateAssemblyAnnualDiscount` 方法里，`materialNo == null` 判断之后、`rawProcess` 读取之前插入：

```java
            if (row.getInt("年降顺序") == null) {
                r.recordError(row.rowNo, "年降顺序", "为空（年降顺序是同一组年降内区分多行的唯一维度，必填）");
                continue;
            }
```

> ⚠️ 这样写保持 `finalizeAssemblyGroups` 的计数不变量：被 `continue` 掉的行不进 `byMaterial` 分组，各自贡献 1 次 `recordError`，与既有的 `materialNo == null` 分支同型，`totalRows == successRows + failedRows` 仍然成立。

- [ ] **Step 6: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 11, Failures: 0, Errors: 0`（原 8 + 新 3）。

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(repair-0804): Phase 1 年降顺序三类统一必填

discount_order 是 annual_discount 组内唯一的行区分维度，为空则撞唯一键。
改造前只有 Q19 强制非空，Q08/Q15 允许为空属遗漏。" -- \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorTest.java
```

---

### Task 8: 剩余 5 个登记点（pending 生命周期）

> 登记点 1、2 已在 Task 3 完成。本 Task 补 3~7。**漏一处不报编译错，只在某条路径上静默失效** —— 所以先写可执行断言。

**Files:**
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountRegistrationPointsTest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/datasource/sqlview/QuotePendingRewriter.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/backfill/QuoteTableAxis.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`

- [ ] **Step 1: 写失败的测试**

创建 `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountRegistrationPointsTest.java`：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.datasource.sqlview.QuotePendingRewriter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0804：把 annual_discount 纳入「版本化 + pending」体系需登记 7 处，
 * 除 VersionedV6Writer.ALLOWED_TABLES 外漏登记都不会报编译错、只会静默失效。
 * 本测试用反射把每一处钉成可执行断言。
 */
class AnnualDiscountRegistrationPointsTest {

    private static final String T = "annual_discount";

    @SuppressWarnings("unchecked")
    private static <C> C readStatic(Class<?> owner, String fieldName) throws Exception {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (C) f.get(null);
    }

    @Test void point1_versionedWriterAllowedTables() throws Exception {
        Set<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.versioning.VersionedV6Writer"), "ALLOWED_TABLES");
        assertTrue(v.contains(T), "漏登记 → 写入器直接抛「表未登记白名单」");
    }

    @Test void point2_versionedWriterSystemTypeScoped() throws Exception {
        Set<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.versioning.VersionedV6Writer"), "SYSTEM_TYPE_SCOPED");
        assertTrue(v.contains(T), "漏登记 → 护栏失效，将来核价侧接入时跨 QUOTE/PRICING 污染版本号");
    }

    @Test void point3_pendingRewriterWhitelist() {
        assertTrue(QuotePendingRewriter.WHITELIST_TABLES.contains(T),
            "漏登记 → SQL 视图读年降表时 pending 行完全不可见，且拿不到 __v6_id 锚点");
    }

    @Test void point4_quoteTableAxisRegistered() throws Exception {
        Class<?> axis = Class.forName("com.cpq.quotation.service.backfill.QuoteTableAxis");
        List<String> all = readStatic(axis, "ALL_MANAGED_TABLES");
        List<String> scan = readStatic(axis, "SCAN_TABLES");
        assertTrue(all.contains(T), "漏登记 → B5 回填扫不到纯 pending 组");
        assertTrue(scan.contains(T), "漏登记 → pending 行永远转不了正");

        java.lang.reflect.Method of = axis.getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        assertNotNull(of.invoke(null, T), "QuoteTableAxis.of(\"annual_discount\") 必须返回 Spec");
    }

    @Test void point5_commitServicePendingTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.service.V6QuotationCommitService"), "PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 导入记录到报价单的 pending 过户漏这张表，行成孤儿");
    }

    @Test void point6_importServicePendingTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.basicdata.v6.quote.QuoteImportService"), "PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 重导时上一次 pending 残留不清，行数翻倍");
    }

    @Test void point7_quotationServiceCleanupTables() throws Exception {
        List<String> v = readStatic(
            Class.forName("com.cpq.quotation.service.QuotationService"), "B8_PENDING_TABLES");
        assertTrue(v.contains(T), "漏登记 → 删报价单时年降 pending 行残留");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw test -Dtest='AnnualDiscountRegistrationPointsTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`point1` / `point2` 通过（Task 3 已做），`point3`~`point7` **5 个失败**。

- [ ] **Step 3: 登记点 3 —— `QuotePendingRewriter`**

修改 `WHITELIST_TABLES`（注意：注释里的"7 张"要改成"8 张"）：

```java
    /** 8 张版本化表白名单（占号表 material_customer_map 不参与，见 backtask B3.1 明确排除）。
     *  repair-0804：annual_discount 并入。 */
    public static final Set<String> WHITELIST_TABLES = Set.of(
        "unit_price", "material_bom", "material_bom_item",
        "element_bom", "element_bom_item", "capacity", "plating_scheme",
        "annual_discount");
```

- [ ] **Step 4: 登记点 4 —— `QuoteTableAxis`**

在 `PLATING_SCHEME` 之后加新 Spec：

```java
    /** repair-0804：年降三 Sheet 统一落库表（单表，非主从）。 */
    static final Spec ANNUAL_DISCOUNT = new Spec("annual_discount", "version_no", List.of(
        "system_type", "customer_no", "discount_type", "material_no", "target_no"),
        List.of("discount_order", "discount_ratio", "fixed_discount_value",
            "currency", "unit", "discount_times", "seq_no"),
        null);
```

在 `of(String table)` 的 switch 里加分支（该方法在 `ALL_MANAGED_TABLES` 上方，`default -> null;` 之前）：

```java
            case "annual_discount" -> ANNUAL_DISCOUNT;
```

`ALL_MANAGED_TABLES` 与 `SCAN_TABLES` 各加一项，并把注释里的"7 张"改成"8 张"：

```java
    /** 8 张受管表全集（回填 B5.1 用于扫描"无 snapshot 表征"的纯 pending 组，路径②）。 */
    static final List<String> ALL_MANAGED_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "annual_discount");

    /** 路径②/③扫描对象：单表或"子表代表主从组"的 8 张受管表清单（子表齐全即代表整组，主表不单独扫描）。 */
    static final List<String> SCAN_TABLES = List.of(
        "unit_price", "material_bom_item", "element_bom_item", "capacity", "plating_scheme",
        "annual_discount");
```

- [ ] **Step 5: 登记点 5 —— `V6QuotationCommitService`**

```java
    private static final List<String> PENDING_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "annual_discount", "material_customer_map", "material_master");
```

- [ ] **Step 6: 登记点 6 —— `QuoteImportService`**

```java
    private static final List<String> PENDING_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "annual_discount", "material_customer_map");
```

- [ ] **Step 7: 登记点 7 —— `QuotationService`**

```java
    private static final java.util.List<String> B8_PENDING_TABLES = java.util.List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "annual_discount", "material_customer_map");
```

- [ ] **Step 8: 跑测试确认全部通过**

```bash
cd cpq-backend && ./mvnw test -Dtest='AnnualDiscountRegistrationPointsTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望：`Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 9: 确认视图启动期硬校验没被打破**

`QuoteViewValidationService` 在应用启动时校验所有 SQL 视图能被 pending 改写。把 `annual_discount` 加进 `QuotePendingRewriter.WHITELIST_TABLES` 会改变改写行为，必须确认没有视图因此校验失败。

worktree 里靠**测试进程**的启动日志看（8081 是主工作区的，看不到本分支改动）：

```bash
cd cpq-backend && ./mvnw test -Dtest='QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true 2>&1 \
  | /usr/bin/grep -a "QuoteViewValidationService"
```

期望：`[QuoteViewValidationService] 校验通过 total=50 ok=50`（**`ok` 必须等于 `total`**；改动前实测基线也是 `total=50 ok=50`，数字可随库里视图增减变化，关键是 ok==total 且应用正常启动）。

- [ ] **Step 10: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountRegistrationPointsTest.java
git commit -m "feat(repair-0804): annual_discount 登记进 pending 生命周期 5 处 + 反射断言钉死 7 个登记点

QuotePendingRewriter 白名单 / QuoteTableAxis Spec+两份清单 /
V6QuotationCommitService 过户 / QuoteImportService 重导清理 /
QuotationService 删单清理。漏登记不报编译错只静默失效，故加可执行断言。" -- \
  cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AnnualDiscountRegistrationPointsTest.java \
  cpq-backend/src/main/java/com/cpq/datasource/sqlview/QuotePendingRewriter.java \
  cpq-backend/src/main/java/com/cpq/quotation/service/backfill/QuoteTableAxis.java \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java \
  cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
```

---

### Task 9: 全量回归（worktree 阶段）

**Files:** 无代码改动（只跑验证；如发现问题回到对应 Task 修）

- [ ] **Step 1: 跑本次涉及的全部测试**

```bash
cd cpq-backend && ./mvnw test \
  -Dtest='Q08*,Q15*,Q19*,AnnualDiscount*,QuoteImportValidatorTest' \
  -DfailIfNoSpecifiedTests=false -Dquarkus.flyway.ignore-missing-migrations=true
```

期望构成：Q08 4 + Q15 4 + Q19 4 + `AnnualDiscountWriterTest` 5 + `AnnualDiscountRegistrationPointsTest` 7 + `QuoteImportValidatorTest` 11 = **35 tests**。
判定标准：**0 failures / 0 errors**（总数以实际为准，若与 35 不符要能解释清楚差在哪个类）。

- [ ] **Step 2: 跑后端全量测试确认无跨模块回归**

```bash
cd cpq-backend && ./mvnw test -Dquarkus.flyway.ignore-missing-migrations=true 2>&1 | tail -40
```

期望：`BUILD SUCCESS`。

若有失败，**必须先做 A/B 归因，不得直接认定为本次引入**：

```bash
cd /home/joii/project/cpq/cpq-backend   # 主工作区，master
./mvnw test -Dtest='<失败的测试类>' -DfailIfNoSpecifiedTests=false \
  -Dquarkus.flyway.ignore-missing-migrations=true
```

⚠️ 注意：主工作区的 master **没有 V377**，而测试库 `cpq_db` 此刻**已经**被 worktree 的测试跑上了 V377 —— 所以 master 侧跑测试时 Flyway 会报 "applied migration not resolved locally: 377"，同样要带 `-Dquarkus.flyway.ignore-missing-migrations=true`。这是 A/B 对照的已知噪声，不是回归。

- [ ] **Step 3: 确认测试库里的落库形态正确（AC-4 / AC-6 的自动化部分）**

三个 handler 的单测已经在测试库里造过数据，直接查形态：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA -F' | ' -c "
SELECT discount_type, material_no, COALESCE(target_no,'<NULL>') AS target_no,
       discount_order, seq_no, discount_times, version_no, is_current
  FROM annual_discount
 WHERE material_no LIKE 'TEST-Q%'
 ORDER BY discount_type, material_no, discount_order;"
```

> 📌 单测的 `@AfterEach` 会清数据，所以这条查询多半返回 0 行 —— 这本身就是"测试自清理正常"的证据。要看到数据就临时注释掉 `after()` 再跑一次。**真正的落库形态验证以 §B 的真实 Excel 导入为准。**

- [ ] **Step 4: Commit（若 Step 1~3 有修复才需要）**

```bash
git commit -m "fix(repair-0804): <具体修了什么>" -- <具体文件>
```

---

### Task 9B: 真实 Excel 端到端验证（**合并 master 之后**执行）

> ⚠️ **为什么必须放到合并后**：8081 加载的是主工作区代码，worktree 阶段它既看不到本次 handler 改动、也不会把 V377 应用到 dev 库 `cpq_db_0724`。合并到 master 后 Quarkus 热重载会自动跑 V377，端到端链路才真正可测。
>
> 合并前先确认 dev 库的 Flyway 状态，避免与并发会话抢号冲突：
> ```bash
> PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
>   -c "SELECT max(version::numeric) FROM flyway_schema_history;"
> ```

- [ ] **Step 1: 合并后确认迁移落到 dev 库**

```bash
sleep 12
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA -F' | ' -c "
SELECT version, success FROM flyway_schema_history WHERE version='377';
SELECT count(*) FROM unit_price WHERE price_type IN ('INCOMING_MATERIAL_REDUCTION','COMPONENT_REDUCTION');"
```

期望：`377 | t`，`count = 0`（AC-13）。

- [ ] **Step 2: 真实 Excel 导入（AC-3 / AC-4 / AC-6）**

报价单管理 → 从基础数据导入 → 选客户 → 上传 `docs/table/报价测试数据/v2/报价系统模板0723.xlsx`。导入后：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA -F' | ' -c "
SELECT discount_type, count(*), count(target_no) AS with_target,
       count(seq_no) AS with_seq, count(discount_times) AS with_times
  FROM annual_discount GROUP BY discount_type ORDER BY 1;
SELECT discount_type, material_no, COALESCE(target_no,'<NULL>'), discount_order, discount_ratio,
       fixed_discount_value, currency, unit, discount_times, seq_no, version_no, is_current
  FROM annual_discount ORDER BY discount_type, material_no, discount_order LIMIT 20;"
```

逐项核对：
- `INCOMING_MATERIAL` 行的 `target_no` = Excel「投入料号」原值
- `ASSEMBLY_PROCESS` 行的 `target_no` = **真工序编号**（形如 `Z100`，**不是中文名**）
- `FINISHED` 行的 `target_no` 全为 NULL
- `seq_no` / `discount_times` 在前两类上有值（Excel 有填时）

- [ ] **Step 3: pending 生命周期验证（AC-8 ~ AC-11）**

```bash
# 导入后、创建报价单前：应为 is_current=false + pending 非空
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA -F' | ' -c "
SELECT is_current, pending_quotation_id IS NOT NULL AS pending, count(*)
  FROM annual_discount GROUP BY 1,2 ORDER BY 1,2;"
```

- 创建报价单后：`pending_quotation_id` 从导入记录 id 过户为 quotationId（AC-8）
- 同一单**连续导入两次** → `pending_quotation_id` 非空的行数**不翻倍**（AC-10）
- 核价通过回填后：`is_current=true` + `pending_quotation_id IS NULL`，老组切走（AC-9）
- **无双 current** 自检：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA -F' | ' -c "
SELECT system_type, customer_no, discount_type, material_no, COALESCE(target_no,'-') AS tgt,
       discount_order, count(*) AS current_rows
  FROM annual_discount WHERE is_current
 GROUP BY 1,2,3,4,5,6 HAVING count(*) > 1;"
```

期望：**0 行**（同一 groupKey + discount_order 只能有一行 current）。

- 删除该报价单 → 该单的年降 pending 行为 0（AC-11）：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT count(*) FROM annual_discount WHERE pending_quotation_id = '<被删报价单的 id>';"
```

- [ ] **Step 4: 年降顺序必填拦截验证（AC-12）**

把模板里某行的「年降顺序」清空后导入。期望：整单 `FAILED`，导入报告展开可见该行错误（"年降顺序 为空…"），且**数据库零变动**：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT count(*) FROM annual_discount WHERE pending_quotation_id IS NOT NULL;"
```

期望：`0`。

- [ ] **Step 5: E2E 冒烟（AC-17）**

```bash
cd /home/joii/project/cpq/cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

⚠️ 该 spec 在干净 master 上**本来就恒 3 个失败**（夹具单缺产品分类，见历史记忆）。**必须与改造前 A/B 对照**：失败数一致即无回归，不得直接判为本次引入。

---

### Task 10: 文档纠正

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 改写 §8「来料年降」**

目标表从 `unit_price` 改为 `annual_discount`，固定写入字段改为 `system_type=QUOTE` / `discount_type=INCOMING_MATERIAL` / `customer_no` 系统提供；列映射表按需求文档 §4.5 重写，其中：
- 「项次 → `seq_no`」标 ✅（**本次真的落库了**，修正历史漂移 #1）
- 「投入料号 → `target_no`」
- 「降价次数 → `discount_times`」改标 ✅
- **删掉**顶部那条已失效的「2026-06-17 自动生成 9 字头料号并登记料号表」说明（漂移 #2），改为「投入料号恒按材质料号处理，原始码直接作 `target_no`，不 resolve / 不铸号 / 不登记 `material_customer_map` / `material_master`（task-0717）」

- [ ] **Step 2: 改写 §15「组装加工费年降」**

同上，`discount_type=ASSEMBLY_PROCESS`，「组装工序 → `target_no`」（保留 repair-0727 的工序解析规则说明），「项次 → `seq_no`」标 ✅。删掉原来那条 `unit_price.operation_no` 双 current 时序约束（已随迁表消失），替换为一句「本表已于 repair-0804 迁至 `annual_discount`」。

- [ ] **Step 3: 改写 §19「年降系数」**

目标表仍是 `annual_discount` 但结构已变：加 `discount_type=FINISHED` / `system_type` / `customer_no`；`material_no` 仍是销售料号；`target_no` 恒 NULL；写入语义从行级 upsert 改为**组级整组替换**（明确写出"重导时 Excel 留空的列不再保留旧值"）；补充 pending 隔离说明（漂移 #3）。

- [ ] **Step 4: 在文档顶部版本注记加一条**

```markdown
> **V3.6（2026-08-03，repair-0804）· 年降三 Sheet 落库统一**：§8「来料年降」、§15「组装加工费年降」原落 `unit_price`（`price_type=INCOMING_MATERIAL_REDUCTION` / `COMPONENT_REDUCTION`），§19「年降系数」原落 `annual_discount` 且无版本化/无 pending 隔离/无客户维度。本次三者收敛到单表 `annual_discount`，用 `discount_type`（`INCOMING_MATERIAL` / `ASSEMBLY_PROCESS` / `FINISHED`）区分，`target_no` 为泛化挂载目标（材质料号 / 工序编号 / NULL），统一走组级版本化 + pending 隔离，并补齐 `customer_no`、补回 `seq_no`「项次」与 `discount_times`「降价次数」。`unit_price` 两个退役 `price_type` 的存量已清除，CHECK 约束保留不动。详见 `dev-docs/task-260708-导入报价单和导入核价单的数据落库规则澄清/repair-260804-年降三sheet的入库规则/需求文档.md`。
```

- [ ] **Step 5: 追加 RECORD.md 开发记录**

在 `docs/RECORD.md` 末尾追加：

```markdown
[2026-08-03] 基础资料/Excel导入 - repair-0804 年降三 Sheet 落库统一（annual_discount 单表化）
| 涉及文件：V377__annual_discount_unify.sql / AnnualDiscountWriter(新) / Q08+Q15+Q19 三 handler /
  AnnualDiscountRepository(删) / QuoteImportValidator / VersionedV6Writer / QuotePendingRewriter /
  QuoteTableAxis / V6QuotationCommitService / QuoteImportService / QuotationService
| 关键决策：
  1) 三 Sheet 业务同构，收敛到单表 annual_discount，discount_type 判别 + 单一 target_no 泛化目标列
     （语义由 type 决定：材质料号/工序编号/NULL），名称不冗余存、由视图 JOIN 主数据取。
  2) 动手窗口：改造前三条路径均「只写不读」（0 SQL 视图 / 0 组件 / 0 Java 读者，存量仅 1 行），
     零迁移成本；一旦客户模板开始配年降页签，成本成倍上升。
  3) 年降系数写入语义由行级 upsert（空值不覆盖）改为组级整组替换；年降顺序三类统一必填
     （它是组内唯一行区分维度，为空则撞唯一键）。
  4) ⚠️ 新表进「版本化 + pending 俱乐部」需登记 7 处，除 ALLOWED_TABLES 外漏登记都不报编译错、
     只静默失效（pending 不可见 / 转不了正 / 残留孤儿 / 行数翻倍）。已加
     AnnualDiscountRegistrationPointsTest 用反射把 7 处钉成可执行断言 —— 后续再有表进这个体系，
     照抄这个测试即可。
  5) 环境坑：测试库 cpq_db 有并发会话应用的 V368~V374（master 无文件），跑 mvnw test 必须带
     -Dquarkus.flyway.ignore-missing-migrations=true。
```

- [ ] **Step 6: Commit**

```bash
git commit -m "docs(repair-0804): 落库方案 §8/§15/§19 改写 + RECORD 登记

含 3 处历史漂移修正：项次实际未落库、投入料号自动铸号说明已失效、
annual_discount 无 pending 隔离未记载。" -- \
  docs/table/报价系统Excel导入落库方案.md docs/RECORD.md
```

---

## 完成判据

全部 Task 打勾后，逐条对照需求文档 §8 的 AC-1~AC-17：

| AC | 由哪个 Task / Step 覆盖 |
|---|---|
| AC | 由哪个 Task / Step 覆盖 | 阶段 |
|---|---|---|
| AC-1 表结构 | Task 1 Step 5（查 `cpq_db`） | worktree |
| AC-2 唯一键 + pending 索引 | Task 1 Step 5 | worktree |
| AC-3 三 Sheet 落库正确 | Task 9B Step 2 | **合并后** |
| AC-4 `target_no` 语义 | Task 4/5/6 单测 + Task 9B Step 2 | 两阶段 |
| AC-5 客户维度 | Task 4 `differentCustomers_coexist` | worktree |
| AC-6 补回的列 | Task 5/6 单测 + Task 9B Step 2 | 两阶段 |
| AC-7 升版语义 | Task 4/5/6 `changeValue_bumps*` + Task 9B Step 3 无双 current | 两阶段 |
| AC-8~AC-11 pending 生命周期 | Task 8 反射断言（登记点）+ Task 9B Step 3（真实链路） | 两阶段 |
| AC-12 年降顺序必填拦截 | Task 7 单测 + Task 9B Step 4 | 两阶段 |
| AC-13 `unit_price` 存量清除 | Task 1 Step 5（`cpq_db`）+ Task 9B Step 1（`cpq_db_0724`） | 两阶段 |
| AC-14 退役代码已删 | Task 1 Step 3 + Task 4 Step 4 | worktree |
| AC-15 7 个登记点 | Task 8 Step 8 | worktree |
| AC-16 文档已纠正 | Task 10 | worktree |
| AC-17 无回归 | Task 9 Step 2 + Task 9B Step 5 | 两阶段 |

> ⚠️ **收尾顺序**：Task 1~10 在 worktree 完成 → 用户确认 → 合并 master → **立即执行 Task 9B** → 通过后才算真正交付。Task 9B 未跑完不得宣告完成。
