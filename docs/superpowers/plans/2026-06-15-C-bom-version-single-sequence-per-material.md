# C. BOM 版本按料号单一序列 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同一料号的 BOM 只有**一条版本序列**：组成件变化就 v2000→v2001 递增；料号被重分类 MATERIAL↔ASSEMBLY 不再让版本分叉重置。

**Architecture:** 决策 = 「按料号单一序列」。根因 = `MaterialBomMergeHandler` 的版本分组键 `masterGk` 含 `bom_type`+`characteristic`、`childGk` 含 `characteristic`，加 `flipReverse` 翻转反向 characteristic。把分组键**收敛为 `system_type+customer_no+material_no`**，`bom_type`/`characteristic` 降为**固定内容列**（仍写库、不进分组）；handler 已把 MATERIAL+ASSEMBLY 合并成单一 childRows，故收敛后 `writeVersionedMasterDetail` 的 `loadCurrentGroup`/`multisetEqual`/`flip`/`nextVersionOf` 天然按料号单序列工作，`flipReverse` 冗余可删。需同步审计 `is_current` 单当前行不变量（配置 SQL 视图 + Java 直查两侧）。

**Tech Stack:** Java 17 / Quarkus（`MaterialBomMergeHandler`、`VersionedV6Writer`）；PostgreSQL（约束 + component_sql_view）。

**复现基线（库内）：** 3120012530 两行 `material_bom` 都 v2000：MATERIAL(characteristic=NULL,1组成件,is_current=f) + ASSEMBLY(characteristic=ASSEMBLY,4组成件,is_current=t)。期望（重导后）：单序列，组成件变化 → v2000→v2001，仅一条 is_current=true。**存量按用户决策仅修代码、3120012530 由用户手工重导**。

---

## 根因（已确认，代码级）

- `MaterialBomMergeHandler.merge`(:122-135)：`masterGk{system_type,customer_no,material_no,bom_type,characteristic}` + `childGk{...,characteristic}` + `flipReverse`(:120,:146)。
- `VersionedV6Writer.writeVersionedMasterDetail`(:168-239)：按 `masterGroupKey`/`childGroupKey` 做 `loadCurrentGroup`(:200)→`multisetEqual`(:202)→`nextVersionOf`(:207)→`flip`(:210-211)→insert。分组键含 bom_type/characteristic ⇒ 重分类即新组、`nextVersionOf` 无历史 → 重置 2000。

---

## File Structure

- `cpq-backend/.../basicdata/v6/quote/MaterialBomMergeHandler.java`
  - `masterGk` 仅 `system_type+customer_no+material_no`；`bom_type`/`characteristic` 放进 `masterFixedColumns`（写库不分组）。
  - `childGk` 仅 `system_type+customer_no+material_no`；`characteristic` 作为每行固定内容（加进 childRows 或 child fixed）。
  - 删除 `flipReverse` 调用与方法（单组后 `flip(groupKey)` 已下线该料号全部旧当前行）。
- `cpq-backend/.../versioning/VersionedV6Writer.java` — 若 `masterFixedColumns` 之前恒空、需确认 `insertRowGeneric` 正确写入 bom_type/characteristic；`CHILD_UQ`/唯一约束目标确认（childVersionColumn 非空走多版本分支，不删历史）。
- DB 约束审计：`material_bom` / `material_bom_item` 现有唯一索引/部分唯一（grep Flyway `db/migration` 中相关 `UNIQUE`/`is_current`）。
- `is_current` 消费方审计：`component_sql_view.sql_template` + Java 直查 `material_bom*` 处过滤 `characteristic`/`bom_type` + `is_current` 的位置（见历史记忆 v6-child-multiversion-iscurrent-audit-scope）。
- 测试：`MaterialBomMergeHandlerTest`（或新建）+ `VersionedV6WriterTest`。

**不变量：** 同 (system_type, customer_no, material_no) 任一时刻 `material_bom` 仅一行 is_current=true；组成件 multiset 变化 → `bom_version` max+1；不变 → 不写。`material_bom_item` 历史版本 is_current=false 留存（不删）。

---

## Task C1：单序列升版 失败测试（RED）

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java`（新建或补充）

- [ ] **Step 1: 读 handler + writer 现状**（MaterialBomMergeHandler:46-157、VersionedV6Writer:168-239）。
- [ ] **Step 2: 写失败测试** — 同一料号 M 两次 merge：第一次仅「物料BOM」(MATERIAL,1组成件)；第二次「组成件BOM」(ASSEMBLY,4组成件)。断言：

```java
// 第一次: v2000, 1 current
// 第二次(组成件变化, 被分类 ASSEMBLY): 期望同一料号 v2001, 且仅 1 行 is_current
long current = countCurrentBom("QUOTE", custNo, "M");
assertEquals(1, current);                              // 修复前: 可能 1(但分叉) 
String latest = latestBomVersion("QUOTE", custNo, "M");
assertEquals("2001", latest);                          // 修复前: 2000 (ASSEMBLY 组重置)
// 老 MATERIAL 行 is_current=false 留存(历史)
assertEquals(1, countNonCurrentBom("QUOTE", custNo, "M"));
```
（helper 用原生 SQL 查 `material_bom`；fixture 用 `SheetRow` 构造两 sheet，或直接调 `merge(materialRows, assemblyRows, ctx)` 两次。）

- [ ] **Step 3: 跑确认失败** → `cd cpq-backend && ./mvnw -q -Dtest=MaterialBomMergeHandlerTest test`，Expected: `latest=2000`（分叉）断言失败。
- [ ] **Step 4: 提交** `git commit -m "test(bom): 料号重分类应单序列升版 v2000→v2001 失败用例 (RED)"`

---

## Task C2：分组键收敛 + bom_type/characteristic 降为固定列（GREEN）

**Files:**
- Modify: `cpq-backend/.../basicdata/v6/quote/MaterialBomMergeHandler.java:120-135`

- [ ] **Step 1: 收敛 masterGk / childGk**

```java
// 分组键: 仅按料号 → 单一版本序列(不含 bom_type/characteristic)
Map<String, Object> masterGk = new LinkedHashMap<>();
masterGk.put("system_type", "QUOTE");
masterGk.put("customer_no", ctx.customerNo);
masterGk.put("material_no", materialNo);
// bom_type/characteristic 降为固定内容列(写库, 不进分组键)
Map<String, Object> masterFixed = new LinkedHashMap<>();
masterFixed.put("bom_type", bomType);
masterFixed.put("characteristic", targetChar);

Map<String, Object> childGk = new LinkedHashMap<>();
childGk.put("system_type", "QUOTE");
childGk.put("customer_no", ctx.customerNo);
childGk.put("material_no", materialNo);
// 每个 child 行写 characteristic(固定内容)
for (Map<String, Object> r : childRows) r.put("characteristic", targetChar);

writer.writeVersionedMasterDetail(
    "material_bom", "bom_version", masterGk, masterFixed,
    "material_bom_item", "bom_version", childGk,
    /* CHILD_CONTENT 不含 characteristic(它是分组外固定值, 不参与 multiset 比较) */ CHILD_CONTENT, childRows);
```
> 注意：`characteristic` 不要进 `CHILD_CONTENT`（multisetEqual 比较列），否则 NULL↔ASSEMBLY 会被当成组成件变化误升版。它只作为写入值。确认 `insertRowGeneric` 把 child 行里的 `characteristic` 字段落库（VersionedV6Writer:221-227 `all.putAll(row)`）。

- [ ] **Step 2: 删除 flipReverse 调用(:120) 与方法(:146-157)** — 收敛后 `writeVersionedMasterDetail` 内 `flip(masterTable, masterGk)`/`flip(childTable, childGk)`（:210-211）按料号下线全部旧当前行，无需再翻反向 characteristic。
- [ ] **Step 3: 跑 C1** → PASS（v2001、单 current、旧行留存）。
- [ ] **Step 4: 提交** `git commit -m "fix(bom): 版本分组键收敛为料号单序列, bom_type/characteristic 降为固定列, 删 flipReverse (GREEN)"`

---

## Task C3：DB 约束审计（避免收敛后违反/漏挡唯一性）

**Files:**
- 审计: `cpq-backend/src/main/resources/db/migration/` 中 `material_bom`/`material_bom_item` 的 UNIQUE / partial index。

- [ ] **Step 1: 查现有约束** → `grep -rn "material_bom" cpq-backend/src/main/resources/db/migration/ | grep -i "unique\|index\|is_current"`，并 `psql ... -c "\d material_bom"` / `\d material_bom_item` 看实际索引。
- [ ] **Step 2: 判定** — 若存在 `UNIQUE(system_type,customer_no,material_no,bom_type) WHERE is_current` 这类**含 bom_type 的当前唯一约束**，收敛后同料号只应一行 current，需改为 `UNIQUE(system_type,customer_no,material_no) WHERE is_current=true`（新 Flyway `V*`）。若约束本就不含 bom_type 则记录确认、无需迁移。
- [ ] **Step 3: 若需迁移** — 新增 Flyway（放 `db/migration/`，touch java 触发，勿手工 psql -f）；`SELECT version,success FROM flyway_schema_history WHERE version='NN'` = t。
- [ ] **Step 4: 提交**（含迁移则一并）`git commit -m "chore(bom): 当前唯一约束对齐料号单序列(如需)"`

---

## Task C4：is_current 消费方审计（配置 SQL 视图 + Java 直查）

**Files:**
- 审计: `component_sql_view`（DB 表，`sql_template`）+ Java 直查 material_bom* 处。

- [ ] **Step 1: 列出过滤点** → `psql ... -c "SELECT sql_view_name FROM component_sql_view WHERE sql_template ILIKE '%material_bom%';"`，再逐个看 sql_template 是否含 `characteristic = 'ASSEMBLY'` / `bom_type=` 之类硬过滤；Java 侧 `grep -rn "material_bom" cpq-backend/src/main/java | grep -i "characteristic\|bom_type\|is_current"`。
- [ ] **Step 2: 修正** — 收敛后单料号只一行 current（bom_type 可能是 ASSEMBLY 或 MATERIAL）。凡按 `characteristic='ASSEMBLY'` 取当前 BOM 的视图/查询，改为**只按 `is_current=true`（不再按 characteristic 硬过滤）**，否则 MATERIAL-分类的当前料号会查不到（渲染断链, AP-53 类）。
- [ ] **Step 3: 视图 DDL 改动后重启 Quarkus**（touch java，等 5-7s；schema DDL/CASCADE 后必重启，见 CLAUDE.md）。用含该 BOM 路径的 endpoint 验证返单值非数组。
- [ ] **Step 4: 提交** `git commit -m "fix(bom): is_current 消费方去除 characteristic 硬过滤, 对齐单序列"`

---

## Task C5：writer 不回归 + 后端自检 + 复测

- [ ] **Step 1: 跑相关后端测试** → `./mvnw -q -Dtest=MaterialBomMergeHandlerTest,VersionedV6WriterTest test`，全 PASS。
- [ ] **Step 2: 后端自检** — touch java 重启；`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 200。
- [ ] **Step 3: 真机复测（合并后, 用户重导）** — 用户重导 3120012530：第一次组成件 N 个 → v2000；改组成件再导 → v2001，单 is_current；BOM 查询/报价渲染该料号正常。
- [ ] **Step 4: 提交**（仅测试/记录）

---

## Self-Review

- **Spec 覆盖：** 单序列(C2) / 升版正确(C1) / 约束(C3) / is_current 消费方(C4) / 不回归(C5)。✓
- **multiset 纪律：** characteristic 不进 CHILD_CONTENT（否则误判升版）；只组成件内容列参与比较。✓
- **is_current 不变量：** 删 flipReverse 后由 `flip(料号 groupKey)` 保证单 current；C4 审计消费方。参见 [[v6-child-multiversion-iscurrent-audit-scope]]。
- **存量：** 仅修代码，3120012530 由用户手工重导（用户决策）；不写迁移数据脚本。
- **风险：** AP-53（V6 表/视图 is_current 过滤）邻域；DDL 后必重启 Quarkus。禁 `git add -A`。
