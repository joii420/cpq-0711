# 导入报价单/核价单落库料号语义纠偏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一报价(QUOTE)/核价(PRICING)两条导入路径的料号语义 —— `material_no` 承载**销售料号**(主料号)、新增 `production_no` 承载生产料号、`element_bom`/`element_bom_item` 新增 `material_part_no`(材质料号)并纳入唯一键;废弃 V311 的反向 `sales_part_no` 设计。

**Architecture:** 新增 Flyway 迁移 V315 反做实际 DB 上的 `sales_part_no`(去唯一索引后缀 + DROP 列)+ 加 `production_no`/`material_part_no` 列 + element_bom 唯一键纳入 material_part_no。改造 `basicdata/v6/quote`(Q*) 与 `basicdata/v6/pricing`(P*) 两组 handler,按实测 Excel 列头读列落库。接口契约 0 改动、前端 0 改动。

**Tech Stack:** Java 17 / Quarkus / Hibernate Panache 原生 SQL / PostgreSQL 16 / Flyway。

---

## 背景关键事实(实现必读,已实测锁定 2026-07-08)

1. **V315 而非改写 V311**:共享 dev DB 已应用 V311(`flyway_schema_history` 有 311、success=t),原地改写会 checksum mismatch → **新增 V315**。且本分支从 master HEAD 建,**不含** V311 文件(V311 属未合并的 `feat/pricing-sales-part-no` 分支)。故 V315 必须**完全幂等自洽**(`IF EXISTS`/`IF NOT EXISTS`),对"跑过 V311 的 dev DB"和"全新库"都产出同一终态。

2. **反做以实际 DB 索引为准,不照抄 V311 文件**:未跟踪的 V311 文件内容(`ux_*` 命名)与实际应用到 DB 的索引(`uq_*` 命名)**不一致** —— 该文件是另一分支被改过的版本。以下为实测 DB 现状(post-V311)的 10 张表唯一索引,均以 `COALESCE(sales_part_no,'')` 结尾:
   - `uq_unit_price` ON unit_price (system_type, price_type, COALESCE(cost_type,''), version_no, code, COALESCE(customer_no,''), COALESCE(supplier_no,''), COALESCE(finished_material_no,''), COALESCE(operation_no,''), COALESCE(seq_no,0), COALESCE(discount_order,0), COALESCE(item_seq,0), COALESCE(effective_date,'1900-01-01'::date), **COALESCE(sales_part_no,'')**)
   - `uq_material_bom_v6` ON material_bom (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''), **COALESCE(sales_part_no,'')**)
   - `uq_material_bom_item` ON material_bom_item (system_type, customer_no, material_no, COALESCE(characteristic,''), COALESCE(bom_version,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''), **COALESCE(sales_part_no,'')**)
   - `uq_element_bom_v6` ON element_bom (system_type, customer_no, material_no, characteristic, **COALESCE(sales_part_no,'')**)
   - `uq_element_bom_item` ON element_bom_item (system_type, customer_no, material_no, characteristic, COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''), **COALESCE(sales_part_no,'')**)
   - `uq_capacity` ON capacity (system_type, material_no, process_no, resource_group_no, COALESCE(calc_version,''), **COALESCE(sales_part_no,'')**)
   - `uq_labor_rate` ON labor_rate (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,''), **COALESCE(sales_part_no,'')**)
   - `uq_production_energy` ON production_energy (material_no, process_no, COALESCE(equipment_no,''), COALESCE(calc_version,''), **COALESCE(sales_part_no,'')**)
   - `uq_auxiliary_energy` ON auxiliary_energy (material_no, process_no, COALESCE(calc_version,''), **COALESCE(sales_part_no,'')**)
   - `uq_tooling_cost` ON tooling_cost (material_no, process_no, seq_no, tooling_no, **COALESCE(sales_part_no,'')**)
   - `material_customer_map`:**无** sales_part_no 索引(其 sales_part_no 列只是 V311 ALTER 加的裸列),仅需 DROP 列。

3. **无视图/其它对象依赖 sales_part_no**(已查 pg_depend/pg_rewrite = 空)→ DROP 列安全,无 CASCADE 误伤。sales_part_no 列存在于全部 11 表。

4. **production_no 列现状**:仅 `material_customer_map` 有(V308 建)。其余需新增。`material_part_no` 列现处处无。

5. **实测测试文件列头(权威,§3.3 来源)**:
   - 报价 V3 (`docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx`):**主料号列已统一为 `销售料号`,已无 `宏丰料号`/`报价料号` 列**;`材质料号` 为独立列(物料BOM/物料与元素BOM/元素回收折扣);组件列 = `投入料号`/`组成件料号`;**报价侧无 `生产料号` 列 → production_no 恒 NULL**。
   - 核价 6.0 (`docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段-增加销售料号.xlsx`):主列 `销售料号` + `生产料号` 并存;`物料与元素BOM` 的材质料号列名仍是 **`物料料号`**(未改名);组件列 `组成料号`/`来料料号`;要素/工序列照旧。
   - **⚠️ §3.3-vs-实测 两处甄别(已按实测定,非阻塞,交付时向技术经理知会)**:
     - **核价「核价版本」「汇总」sheet**:实测列头只有 `生产料号`(无 `销售料号`),且落表 `pricing_version`/`costing_summary` **不在本次 11 张共享表内** → **本次不改,保持按生产料号**(P04PricingVersionHandler 保持读现列;§3.3 该两行的 material_no←销售料号 视为不适用)。
     - **报价「物料与元素BOM」(Q04)**:实测无 `投入料号` 列,现 handler 却用 `投入料号`+发号解析 material_no → 改为 material_no←`销售料号`、material_part_no←`材质料号`、component_no←`元素`;该 sheet 不再走投入料号发号(发号仅在仍含 `投入料号` 的 来料*/自制* sheet 保留,§1.5)。

6. **VersionedV6Writer 无需改**:纯通用,groupKey/content 列由 handler 传入,无硬编码 material_no。material_part_no 进 element_bom 唯一键 = 在 P07/Q04 的 masterGk/childGk 里加一列即可。production_no 作描述列 = 加入 content/insert 列。

7. **element_bom 版本模型**:`characteristic` 是版本列(取值 "2000"…),master groupKey=(system_type,customer_no,material_no)。加 material_part_no 后 groupKey=(system_type,customer_no,material_no,material_part_no) → **同一销售料号的多个材质料号各自独立成一份 BOM/版本序列**(§5.3 核心:防撞键覆盖)。故 P07/Q04 的分组维度必须从 `material_no` 改为 `(material_no, material_part_no)`。

---

## File Structure

- **Create** `cpq-backend/src/main/resources/db/migration/V315__unify_partno_semantics.sql` — 反做 sales_part_no + 加 production_no/material_part_no + 重建唯一键。
- **Modify** 报价 handler(`basicdata/v6/quote/`):主料号读 `销售料号` 优先;`Q04ElementBomHandler` 加 material_part_no 分组。
- **Modify** 核价 handler(`basicdata/v6/pricing/`):material_no 改读 `销售料号`;新增读 `生产料号`→production_no 落各成本表;`P07ElementBomHandler` 加 material_part_no 分组。
- **Modify** `basicdata/v6/repository/MaterialCustomerMapRepository.java` — `upsert(...)` 单行方法补 production_no。
- **Modify** 文档 `docs/table/报价系统Excel导入落库方案.md`、`docs/table/核价系统Excel导入落库方案.md`。
- **验证**:重导两测试文件 + DB 断言(无独立单测框架覆盖 import handler;以"清空重导 + SQL 断言"为验收,见每任务的验证步骤与 Task 9)。

---

## Task 1: V315 迁移 —— 反做 sales_part_no + 加 production_no/material_part_no + 重建唯一键

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V315__unify_partno_semantics.sql`

- [ ] **Step 1: 写迁移文件**(完整内容,逐表幂等)

```sql
-- V315: 统一报价/核价料号语义 —— material_no=销售料号, 新增 production_no=生产料号,
--       element_bom/element_bom_item 新增 material_part_no(材质料号)并纳入唯一键;
--       反做 V311 的 sales_part_no 反向设计(去唯一索引后缀 + DROP 列)。
-- 幂等自洽: 对"已应用 V311 的 dev DB"与"全新库"产出同一终态。
-- 反做以实测 DB 现状(uq_* 索引)为准, 非 V311 文件(ux_* 那版≠实际应用)。

-- ============ A. 加 production_no 描述列(不进唯一键) ============
ALTER TABLE unit_price        ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE material_bom      ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE element_bom       ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);  -- 留空(该Sheet无生产料号列)
ALTER TABLE element_bom_item  ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);  -- 留空
ALTER TABLE capacity          ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE labor_rate        ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE auxiliary_energy  ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE tooling_cost      ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
-- material_customer_map.production_no 由 V308 建, 不重复加。

-- ============ B. element_bom / element_bom_item 加 material_part_no(材质料号) ============
ALTER TABLE element_bom      ADD COLUMN IF NOT EXISTS material_part_no VARCHAR(32);
ALTER TABLE element_bom_item ADD COLUMN IF NOT EXISTS material_part_no VARCHAR(32);

-- ============ C. 逐表: 去唯一索引 sales 后缀 + DROP sales_part_no 列 ============
-- 手法: DROP INDEX(旧含sales / 或全新库的原索引) → DROP COLUMN sales_part_no →
--       CREATE 无sales(element表额外含material_part_no)的唯一索引。

-- 1. unit_price
DROP INDEX IF EXISTS uq_unit_price;
ALTER TABLE unit_price DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_unit_price ON unit_price
  (system_type, price_type, COALESCE(cost_type,''::varchar), version_no, code,
   COALESCE(customer_no,''::varchar), COALESCE(supplier_no,''::varchar),
   COALESCE(finished_material_no,''::varchar), COALESCE(operation_no,''::varchar),
   COALESCE(seq_no,0), COALESCE(discount_order,0), COALESCE(item_seq,0),
   COALESCE(effective_date,'1900-01-01'::date));

-- 2. material_bom
DROP INDEX IF EXISTS uq_material_bom_v6;
ALTER TABLE material_bom DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_bom_v6 ON material_bom
  (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''::varchar));

-- 3. material_bom_item
DROP INDEX IF EXISTS uq_material_bom_item;
ALTER TABLE material_bom_item DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_bom_item ON material_bom_item
  (system_type, customer_no, material_no, COALESCE(characteristic,''::varchar),
   COALESCE(bom_version,''::varchar), COALESCE(seq_no,0),
   COALESCE(component_no,''::varchar), COALESCE(part_no,''::varchar));

-- 4. element_bom(唯一键纳入 material_part_no)
DROP INDEX IF EXISTS uq_element_bom_v6;
ALTER TABLE element_bom DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_element_bom_v6 ON element_bom
  (system_type, customer_no, material_no, COALESCE(material_part_no,''::varchar), characteristic);

-- 5. element_bom_item(唯一键纳入 material_part_no)
DROP INDEX IF EXISTS uq_element_bom_item;
ALTER TABLE element_bom_item DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_element_bom_item ON element_bom_item
  (system_type, customer_no, material_no, COALESCE(material_part_no,''::varchar), characteristic,
   COALESCE(seq_no,0), COALESCE(component_no,''::varchar), COALESCE(part_no,''::varchar));

-- 6. capacity
DROP INDEX IF EXISTS uq_capacity;
ALTER TABLE capacity DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_capacity ON capacity
  (system_type, material_no, process_no, resource_group_no, COALESCE(calc_version,''::varchar));

-- 7. labor_rate
DROP INDEX IF EXISTS uq_labor_rate;
ALTER TABLE labor_rate DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_labor_rate ON labor_rate
  (version_no, process_no, COALESCE(material_no,''::varchar), COALESCE(labor_grade,''::varchar));

-- 8. production_energy
DROP INDEX IF EXISTS uq_production_energy;
ALTER TABLE production_energy DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_energy ON production_energy
  (material_no, process_no, COALESCE(equipment_no,''::varchar), COALESCE(calc_version,''::varchar));

-- 9. auxiliary_energy
DROP INDEX IF EXISTS uq_auxiliary_energy;
ALTER TABLE auxiliary_energy DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_auxiliary_energy ON auxiliary_energy
  (material_no, process_no, COALESCE(calc_version,''::varchar));

-- 10. tooling_cost
DROP INDEX IF EXISTS uq_tooling_cost;
ALTER TABLE tooling_cost DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tooling_cost ON tooling_cost
  (material_no, process_no, seq_no, tooling_no);

-- 11. material_customer_map(无 sales 索引, 仅 DROP 列)
ALTER TABLE material_customer_map DROP COLUMN IF EXISTS sales_part_no;
```

- [ ] **Step 2: 触发 Quarkus 自动迁移 + 重启**(禁手工 psql -f;schema DDL 后必重启清进程级缓存)

```bash
cd /home/joii/project/cpq/cpq-backend
touch src/main/java/com/cpq/basicdata/v6/pricing/P07ElementBomHandler.java
# 等 6-8s 让共享 dev server(8081, 主工作区运行)重编译 + migrate-at-start
```
> ⚠️ 共享 dev server 从**主工作区** `/home/joii/project/cpq` 运行(见 CLAUDE.md worktree 共享约束)。本 worktree 新增的迁移文件不会被主工作区 Quarkus 看到。**落地方式**:把 V315 也放到主工作区同路径(`cp` 到 `/home/joii/project/cpq/cpq-backend/src/main/resources/db/migration/`)再 touch 主工作区一个 java 文件触发迁移;或在收尾合并到 master 后由主工作区跑。执行者按当前 dev server 归属选择,勿在 worktree 另起 server。

- [ ] **Step 3: 验证迁移落地**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c \
 "SELECT version,success FROM flyway_schema_history WHERE version='315';"
# 期望: 315|t
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c \
 "SELECT count(*) FROM information_schema.columns WHERE column_name='sales_part_no' AND table_name IN ('unit_price','material_bom','material_bom_item','element_bom','element_bom_item','capacity','labor_rate','production_energy','auxiliary_energy','tooling_cost','material_customer_map');"
# 期望: 0
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c \
 "SELECT indexdef FROM pg_indexes WHERE indexname='uq_element_bom_v6';"
# 期望: 含 material_part_no, 不含 sales_part_no
```
Expected: `315|t`;sales_part_no 列计数 0;element_bom 唯一索引含 material_part_no。

- [ ] **Step 4: Commit**

```bash
cd /home/joii/project/cpq/.claude/worktrees/task-0708-import-partno
git add cpq-backend/src/main/resources/db/migration/V315__unify_partno_semantics.sql
git commit -m "feat(partno): V315 反做sales_part_no + 加production_no/material_part_no + element_bom唯一键纳入material_part_no"
```

---

## Task 2: 核价 element_bom —— P07 加 material_part_no 分组(销售料号×材质料号独立成 BOM)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P07ElementBomHandler.java`

- [ ] **Step 1: 改读列 + 分组维度**。把 `handle` 内主循环与两条写入路径改为按 `(material_no, material_part_no)` 分组。核价「物料与元素BOM」列头:`销售料号 | 物料料号 | … | 元素代码 | 组成含量 | 损耗率`。material_no←`销售料号`(兼容回退 `物料料号`/`宏丰料号` 旧名)、material_part_no←`材质料号`(回退 `物料料号`)、component_no←`元素代码`(不变)。

替换第 44–102 行整段为:

```java
        // 分组维度 = (material_no=销售料号, material_part_no=材质料号); 组内按(项次,元素代码)去重
        Map<List<String>, Map<List<Object>, Map<String, Object>>> childByKey = new LinkedHashMap<>();
        Map<List<String>, String[]> keyMeta = new LinkedHashMap<>();   // key -> [materialNo, materialPartNo]
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "物料料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String materialPartNo = row.getStr("材质料号", "物料料号");   // 核价该列名仍为"物料料号"
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素代码");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            List<String> gkey = Arrays.asList(materialNo, materialPartNo == null ? "" : materialPartNo);
            keyMeta.putIfAbsent(gkey, new String[]{materialNo, materialPartNo});
            childByKey.computeIfAbsent(gkey, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);
            result.successRows++;
        }

        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "PRICING");
                masterGk.put("customer_no", CUSTOMER);
                masterGk.put("material_no", meta[0]);
                masterGk.put("material_part_no", meta[1]);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("element_bom", "characteristic",
                    Map.of("bom_type", "MATERIAL"), "element_bom_item", "characteristic",
                    CHILD_CONTENT, items);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "PRICING");
                    masterGk.put("customer_no", CUSTOMER);
                    masterGk.put("material_no", meta[0]);
                    masterGk.put("material_part_no", meta[1]);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                    writer.writeVersionedMasterDetail(
                        "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                        "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows);
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + meta[0] + "/part=" + meta[1] + ": " + ex.getMessage());
                }
            }
        }
```

> ⚠️ safeIdent 校验:`material_part_no` 是合法列名([a-z_][a-z0-9_]*),writer 通过。masterGk/childGk 里 material_part_no 值为 null 时,writer 用 `IS NOT DISTINCT FROM` 匹配、唯一索引用 `COALESCE(material_part_no,'')` —— null 与 '' 在索引层等价,分组层用 null 也一致(两侧都 null),不会双 current。

- [ ] **Step 2: 编译自检**

```bash
cd /home/joii/project/cpq/cpq-backend
touch src/main/java/com/cpq/basicdata/v6/pricing/P07ElementBomHandler.java
sleep 7
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/components   # 期望 401
```
Expected: 401(应用起来、无编译错)。若 500 → 看 Quarkus 控制台修错。

- [ ] **Step 3: Commit**

```bash
cd /home/joii/project/cpq/.claude/worktrees/task-0708-import-partno
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P07ElementBomHandler.java
git commit -m "feat(partno): P07核价element_bom 按(销售料号,材质料号)分组 + material_part_no进键"
```

---

## Task 3: 报价 element_bom —— Q04 material_no←销售料号 + material_part_no 分组(去投入料号发号)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q04ElementBomHandler.java`

- [ ] **Step 1: 改读列 + 分组维度**。报价「物料与元素BOM」列头:`销售料号 | 材质料号 | 材质料号名称 | 项次 | 元素 | 组成含量 | 损耗率% | 毛用量 | 毛用量单位 | 净用量 | 净用量单位`。**无投入料号列 → 不再走 materialNoResolver/发号**;material_no←`销售料号`、material_part_no←`材质料号`、component_no←`元素`。material_master 同步用销售料号 + `材质料号名称` 作 name。

替换第 51–141 行(从 `MaterialNoResolver.BatchState batch...` 到两条写入分支结束)为:

```java
        // §P1-A 料号表 upsert 延后批量: material_no -> [name, type]
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        // 分组维度 = (material_no=销售料号, material_part_no=材质料号); 组内按(项次,元素)去重
        Map<List<String>, Map<List<Object>, Map<String, Object>>> childByKey = new LinkedHashMap<>();
        Map<List<String>, String[]> keyMeta = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String materialPartNo = row.getStr("材质料号");
            MaterialMasterRepository.accNameType(mmAcc, materialNo, row.getStr("材质料号名称"), "成品");
            result.recordWrite("material_master", 1);
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("composition_qty", row.getDecimal("毛用量"));
            String netUnit = row.getStr("净用量单位");
            c.put("issue_unit", netUnit != null ? netUnit : row.getStr("毛用量单位"));
            c.put("base_qty", row.getDecimal("净用量"));
            List<String> gkey = Arrays.asList(materialNo, materialPartNo == null ? "" : materialPartNo);
            keyMeta.putIfAbsent(gkey, new String[]{materialNo, materialPartNo});
            childByKey.computeIfAbsent(gkey, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);
            result.successRows++;
        }

        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> me : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(me.getKey(), me.getValue()[0], me.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true);
        }

        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", meta[0]);
                masterGk.put("material_part_no", meta[1]);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("element_bom", "characteristic",
                    Map.of("bom_type", "MATERIAL"), "element_bom_item", "characteristic",
                    CHILD_CONTENT, items);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "QUOTE");
                    masterGk.put("customer_no", ctx.customerNo);
                    masterGk.put("material_no", meta[0]);
                    masterGk.put("material_part_no", meta[1]);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                    writer.writeVersionedMasterDetail(
                        "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                        "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows);
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + meta[0] + "/part=" + meta[1] + ": " + ex.getMessage());
                }
            }
        }
```

- [ ] **Step 2: 清理未用 import**。删除不再使用的 `MaterialNoResolver`/`MaterialNoUnresolvableException`/`QuoteMaterialNoAllocator` import(第 8–10 行)与 `@Inject MaterialNoResolver materialNoResolver;`(第 35 行)。跑 `npx`? 否 —— 用 touch + curl 验证编译(下步)。

- [ ] **Step 3: 编译自检**

```bash
cd /home/joii/project/cpq/cpq-backend
touch src/main/java/com/cpq/basicdata/v6/quote/Q04ElementBomHandler.java
sleep 7
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/components   # 期望 401
```

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q04ElementBomHandler.java
git commit -m "feat(partno): Q04报价element_bom material_no←销售料号 + material_part_no进键(去投入料号发号)"
```

---

## Task 4: 报价 Q* 主料号读列统一 → 销售料号(除 Q04 已处理)

> 规则:凡读取**成品/主料号**(落 material_no 或 unit_price.code 或 finished_material_no)的 `row.getStr("宏丰料号"...)`/`row.getStr("报价料号"...)`/`row.getStr("料号"...)`,改为 `销售料号` 优先、旧名回退。**不动**组件/投入列(`投入料号`/`组成件料号`)与其发号解析(§1.5)。报价侧 production_no 恒 NULL,不读。

**Files(逐个,已用 grep 定位 file:line;实现时打开确认上下文再改):**

- [ ] `Q02CustomerMapHandler.java:72` —— `row.getStr("报价料号","宏丰料号")` → `row.getStr("销售料号","报价料号","宏丰料号")`(客户料号与宏丰料号的关系 sheet 主列=销售料号;customer_product_no 已读)。
- [ ] `MaterialBomMergeHandler.java:70,104` —— `row.getStr("宏丰料号")` → `row.getStr("销售料号","宏丰料号")`(物料BOM 主列=销售料号)。isCfg 守卫保留。
- [ ] `Q06FixedProcessFeeHandler.java:72`、`Q07IncomingOtherFeeHandler.java:69`、`Q08IncomingAnnualDiscountHandler.java:68`、`Q09IncomingRecoveryHandler.java:66`、`Q10SelfProcessFeeHandler.java:62`、`Q13ComponentOtherFeeHandler.java:70` —— `finishedMaterialNo = row.getStr("宏丰料号","成品料号")` → `row.getStr("销售料号","宏丰料号","成品料号")`(成品/主料号列现为销售料号)。**保留**这些 handler 里对 `投入料号`/`组成件料号` 的 resolver/发号调用不变。
- [ ] `Q10SelfProcessFeeHandler.java:71` 错误提示串 `"投入料号、投入料号名称、宏丰料号均为空…"` 可保留(仅文案)。
- [ ] `Q11FinishedOtherFeeHandler.java:46`、`Q14AssemblyProcessFeeHandler.java:52`、`Q15AssemblyAnnualDiscountHandler.java:48,50`、`Q17PlatingCostHandler.java:53`、`Q19AnnualDiscountHandler.java:36` —— `code/materialNo = row.getStr("宏丰料号")` → `row.getStr("销售料号","宏丰料号")`。
- [ ] `Q18UnitWeightHandler.java:35` —— `row.getStr("料号","宏丰料号")` → `row.getStr("销售料号","料号","宏丰料号")`(单重 sheet 主列=销售料号)。
- [ ] `Q16PlatingSchemeHandler.java:46` —— `方案编号`,**不改**(电镀方案按方案编号,无销售料号)。
- [ ] Q05ElementRecoveryHandler —— 元素回收折扣 sheet 列头=`销售料号 | 材质料号 | … | 元素 | 回收折扣`。打开确认其主料号读法并同规则改为 `销售料号` 优先(grep 未命中 = 可能走 resolver 或其它;按实际读列处理,落 element_bom_item.recovery_* 时 material_no 用销售料号、若该 handler 也写 element_bom 需一并按 (material_no, material_part_no) 分组——打开核对)。

- [ ] **Step 编译自检 + Commit**(整组改完后一次)

```bash
cd /home/joii/project/cpq/cpq-backend
for f in Q02CustomerMapHandler MaterialBomMergeHandler Q06FixedProcessFeeHandler Q07IncomingOtherFeeHandler Q08IncomingAnnualDiscountHandler Q09IncomingRecoveryHandler Q10SelfProcessFeeHandler Q11FinishedOtherFeeHandler Q13ComponentOtherFeeHandler Q14AssemblyProcessFeeHandler Q15AssemblyAnnualDiscountHandler Q17PlatingCostHandler Q18UnitWeightHandler Q19AnnualDiscountHandler Q05ElementRecoveryHandler; do touch src/main/java/com/cpq/basicdata/v6/quote/$f.java; done
sleep 8
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/components   # 期望 401
git add -p   # 仅本次改动文件
git commit -m "feat(partno): 报价Q* 主料号读列统一为销售料号优先(旧名回退)"
```

---

## Task 5: 核价 P* material_no←销售料号 + 新增读生产料号→production_no

> 规则:核价成品/主料号读 `row.getStr("宏丰料号")` → `row.getStr("销售料号","宏丰料号")`;**新增**读 `生产料号`→ 落各成本表 `production_no`(描述列)。组件/工序/要素列不变。落表机制两类:
> - **VersionedV6Writer 单表**(capacity/production_energy/auxiliary_energy/tooling_cost 走 writeVersionedGroup(s)):把 `"production_no"` 加进该 handler 的 `CONTENT` 列表,并在每行 map `c.put("production_no", productionNo)`。production_no 不进 VERSION_TRIGGER(不因生产料号变化单独升版)。
> - **原生 INSERT**(labor_rate、unit_price 类、material_bom 主从):在 INSERT 列清单与占位/绑定处加 `production_no`。
> **每个 handler 打开后按其实际写入结构注入**(下列给出源列与目标,注入点以文件为准)。

**Files(核价, `basicdata/v6/pricing/`):**

- [ ] `P05CustomerMapHandler.java:34` —— materialNo←`row.getStr("销售料号","宏丰料号")`;新增 `String productionNo = row.getStr("生产料号");`;`mapRepo.upsert(...)` 传 production_no(见 Task 6 给 upsert 加形参)。masterRepo.upsertByMaterialNo 的 materialNo 随之为销售料号。
- [ ] `P06MaterialBomHandler.java:54` —— materialNo←`销售料号`;读 `生产料号`;material_bom 主从写入把 production_no 落 material_bom(及/或 material_bom_item)。组件列 `组成料号`→component_no 不变。打开确认主从写入(writeVersionedMasterDetail masterFixedColumns 或 childContent),把 production_no 注入 master 侧写入列。
- [ ] `P08CapacityHandler.java:58` —— materialNo←`销售料号`;读 `生产料号`;`CONTENT` 加 `"production_no"`,行 map 加 `c.put("production_no", productionNo)`(setBased 与非 setBased 两路的 `capByMat` 行构造处)。labor_rate 的 INSERT(第 138、181 行两处 SQL)列清单加 `production_no`,绑定 `:pn`/对应参数。
- [ ] `P09EquipmentDepreciationHandler.java:99,119` —— 同 P08 结构(设备折旧→capacity?打开确认落表)。materialNo←销售料号;生产料号→production_no 注入其写入。
- [ ] `P10ProductionEnergyHandler.java:99,119` —— materialNo←销售料号;production_no 注入 production_energy 写入(CONTENT 或 INSERT)。
- [ ] `P11AuxiliaryEnergyHandler.java:94,114` —— 同上,落 auxiliary_energy。
- [ ] `P12ToolingCostHandler.java:114,139` —— materialNo←销售料号;production_no 注入 tooling_cost 写入。
- [ ] `P13ProductionConsumableHandler.java:30`、`P14PackagingConsumableHandler.java:30` —— `code=row.getStr("宏丰料号")`→`销售料号`;production_no 注入 tooling_cost 写入(生产耗材/包装材料 BOM 落 tooling_cost)。
- [ ] `P15IncomingProcessFeeHandler.java:34`、`P16IncomingOtherRatioFeeHandler.java:38`、`P17IncomingOtherFixedFeeHandler.java:38` —— `finishedMaterialNo=row.getStr("宏丰料号","成品料号")`→`销售料号` 优先;production_no 注入 unit_price 写入。`来料料号`→component_no 不变。
- [ ] `P18SelfProcessAssemblyFeeHandler.java:30`、`P19FinishedOtherRatioFeeHandler.java:30`、`P20FinishedOtherFixedFeeHandler.java:30`、`P23OutsourceProcessFeeHandler.java:30` —— `code=row.getStr("宏丰料号")`→`销售料号`;production_no 注入 unit_price 写入。
- [ ] `P22PlatingCostHandler.java:31` —— `code=row.getStr("宏丰料号")`→`销售料号`;production_no 注入(电镀成本落表以文件为准)。
- [ ] `P24UnitWeightHandler.java:29` —— `row.getStr("宏丰料号","料号")`→`row.getStr("销售料号","宏丰料号","料号")`;production_no 注入单重写入。
- [ ] `P21PlatingSchemeHandler` —— 电镀方案按 `方案编号`,**不改**(无销售料号列)。
- [ ] `P04PricingVersionHandler.java` —— **不改**(核价版本 sheet 主列=生产料号,落 pricing_version 非 11 表;见背景 §5 甄别)。
- [ ] `P01/P02/P03`(元素/材料价格、汇率)—— **不改**(§1.4 不在 11 表)。

- [ ] **Step 编译自检 + Commit**(整组)

```bash
cd /home/joii/project/cpq/cpq-backend
for f in P05CustomerMapHandler P06MaterialBomHandler P08CapacityHandler P09EquipmentDepreciationHandler P10ProductionEnergyHandler P11AuxiliaryEnergyHandler P12ToolingCostHandler P13ProductionConsumableHandler P14PackagingConsumableHandler P15IncomingProcessFeeHandler P16IncomingOtherRatioFeeHandler P17IncomingOtherFixedFeeHandler P18SelfProcessAssemblyFeeHandler P19FinishedOtherRatioFeeHandler P20FinishedOtherFixedFeeHandler P22PlatingCostHandler P23OutsourceProcessFeeHandler P24UnitWeightHandler; do touch src/main/java/com/cpq/basicdata/v6/pricing/$f.java; done
sleep 9
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/components   # 期望 401
git commit -am "feat(partno): 核价P* material_no←销售料号 + 生产料号→production_no落各成本表"
```

---

## Task 6: MaterialCustomerMapRepository.upsert 补 production_no

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialCustomerMapRepository.java:27-65`

- [ ] **Step 1**:给单行 `upsert(...)` 方法加 `String productionNo` 形参,INSERT 列加 `production_no`、VALUES 加 `:productionNo`、DO UPDATE SET 加 `production_no = COALESCE(EXCLUDED.production_no, material_customer_map.production_no)`,并 `.setParameter("productionNo", productionNo)`。参考同文件 `upsertQuote`(第 200 行)已含 production_no 的写法逐字对齐。P05(Task 5)调用处补传 productionNo 实参。

- [ ] **Step 2 编译自检**:touch P05 + curl 401。

- [ ] **Step 3 Commit**:`git commit -am "feat(partno): MaterialCustomerMapRepository.upsert 补 production_no"`

---

## Task 7: 实体同步(按需)

**Files:**
- Check: `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/ElementBom.java` / `ElementBomItem.java` / `MaterialCustomerMap.java`

- [ ] **Step 1**:确认这些实体是否被**实体方式**读写 material_part_no/production_no。本次写路径全走 VersionedV6Writer 原生 SQL 与 Repository 原生 SQL(MaterialCustomerMap 自 V308 起仅原生 SQL),**不经 Panache 持久化这些新列** → 实体无需加字段即可正确落库。但若 `ElementBom`/`ElementBomItem` 实体被其它读路径(如渲染/查询)投影且需要 material_part_no,则补 `@Column(name="material_part_no") public String materialPartNo;`。用 codegraph 确认引用面:

```
codegraph_impact ElementBom ; codegraph_callers ElementBomItem
```
- [ ] **Step 2**:若无实体读依赖 → 本任务标注"无需改",跳过。若有 → 补字段 + touch + curl 401 + commit。

---

## Task 8: 文档纠正

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`
- Modify: `docs/table/核价系统Excel导入落库方案.md`

- [ ] **报价文档**:新增/修订"销售料号(主料号,落 material_no)/材质料号(落 element_bom.material_part_no)"章节;把过时的"宏丰料号/报价料号"列名更正为"销售料号(旧名回退)";注明报价侧无生产料号列(production_no 恒 NULL)、customer_product_no 可空、system_type='QUOTE'、发号服务保留。
- [ ] **核价文档**:把"material_no=生产料号 + sales_part_no 维度"整体改写为"material_no=销售料号 + production_no=生产料号";`物料与元素BOM` 标注 material_part_no 例外(源列名仍为"物料料号")+唯一键含 material_part_no;删除全部 sales_part_no 相关描述;注明核价版本/汇总仍按生产料号(不在 11 表)。
- [ ] **Commit**:`git commit -am "docs(partno): 报价/核价落库方案文档同步销售料号/生产料号/材质料号口径"`

---

## Task 9: 端到端自检(清空重导两测试文件 + DB 断言)

> 无 handler 层单测框架;以真实重导 + SQL 断言为验收(对应 backtask §6)。共享 dev server 需已加载本次全部改动(见 Task 1 Step 2 关于 worktree/主工作区 dev server 归属)。

- [ ] **Step 1: 后端存活**
```bash
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/basic-data-import/v6/00000000-0000-0000-0000-000000000000   # 401/404, 非500
```

- [ ] **Step 2: 报价重导**(清空 → POST /v6/quote 报价V3 → 轮询 SUCCESS/failedRows=0)
```bash
curl -s --noproxy '*' -F "file=@docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx" http://localhost:8081/api/cpq/basic-data-import/v6/quote
# 取返回 importId 轮询: GET /v6/{importId} 直到 status=SUCCESS
```
断言:`material_customer_map` 存在 system_type='QUOTE' 且 material_no=销售料号值的行;`material_bom` 有对应行;`element_bom` 的 material_part_no=材质料号、production_no IS NULL。

- [ ] **Step 3: 核价重导**(清空 → POST /v6/pricing 核价6.0 → sheets[] 全成功)
```bash
curl -s --noproxy '*' -F "file=@docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段-增加销售料号.xlsx" http://localhost:8081/api/cpq/basic-data-import/v6/pricing
```
断言 SQL:
```sql
-- 任一成本表 material_no=销售料号、production_no=生产料号
SELECT material_no, production_no FROM capacity WHERE system_type='PRICING' LIMIT 5;
-- element_bom: material_no=销售料号、material_part_no=材质料号、production_no IS NULL
SELECT material_no, material_part_no, production_no FROM element_bom WHERE system_type='PRICING' AND is_current LIMIT 5;
-- 同一销售料号多材质料号行均在(未撞键覆盖)
SELECT material_no, count(DISTINCT material_part_no) FROM element_bom WHERE system_type='PRICING' AND is_current GROUP BY material_no HAVING count(DISTINCT material_part_no)>1;
```

- [ ] **Step 4: 升版唯一性**:同一核价文件重导两次,断言每 (material_no, material_part_no) 组 `is_current=TRUE` 行的 characteristic 只有一个值(不多 current、版本正确递增):
```sql
SELECT material_no, material_part_no, count(*) FROM element_bom WHERE system_type='PRICING' AND is_current GROUP BY material_no, material_part_no HAVING count(*)>1;
-- 期望 0 行
```

- [ ] **Step 5: 交付自检声明**(必附一行):
> flyway V315 success=t ✅ / sales_part_no 列=0 ✅ / 报价 failedRows=0 ✅ / 核价 sheets 全成功 ✅ / element_bom material_part_no 正确+production_no NULL ✅ / 多材质料号未撞键 ✅ / is_current 唯一 ✅

---

## Self-Review 覆盖对照(backtask §7 交付物)

- [x] 迁移(V315):反 sales_part_no + 加 production_no + element_bom 加 material_part_no + 唯一键重建 → Task 1
- [x] 报价 Q* handler:主料号读销售料号、材质料号落 material_part_no → Task 3(Q04)+ Task 4(其余)
- [x] 核价 P* handler:material_no 改存销售料号、生产料号→production_no、P07 材质料号→material_part_no → Task 2 + Task 5
- [x] 实体/Repository 字段与形参同步 → Task 6(repo)+ Task 7(实体按需)
- [x] 两份 docs/table 落库方案文档纠正 → Task 8
- [x] 自检证据齐全 → Task 9
