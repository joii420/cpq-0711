# 报价系统版本号 · 视图/SQL模板 is_current 过滤 实现计划（Task 9+10 独立后续任务）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans / subagent-driven-development。Steps 用 `- [ ]` 跟踪。
>
> **来源**：`docs/table/报价系统版本号统一升版规则-实现计划.md` V2.1 Phase 4 决议 C（2026-06-01 把 Task 9/10 从写入任务剥离）。**前置**：Phase 1-3（写入路径）已交付（11 commit，47 测试，`b2bde30`→`0919657`）。

**Goal:** 让所有读「版本化 QUOTE 行」的 PG 视图 + 配置 SQL 模板（component_sql_view / template_sql_view）加 `is_current = TRUE` 过滤，使二次导入升版后只渲染当前生效版本；并通过两遍导入幂等 + E2E 验收。

**Architecture / 关键认知（执行前必读）:**
1. **只有读「版本化 QUOTE 行」的对象需要改**。本次只版本化了报价侧（`system_type='QUOTE'` 的 unit_price/element_bom/material_bom + `resource_group_no='QUOTE_ASSEMBLY'` 的 capacity + 全局 plating_scheme）。**核价（PRICING）行仍单版本**（V_DEFAULT），读 PRICING 行的视图无重复行问题、**不在本期范围**（与设计 §1.1「不覆盖核价」一致）。判定靠每个对象的 `system_type` / `resource_group_no` 谓词，**不能按视图名前缀拍脑袋**。
2. **两套不同机制**：
   - **PG 视图**：Flyway 迁移 `DROP VIEW ... CASCADE` + `CREATE VIEW`（或对已有 WHERE 追加），新增 `WHERE is_current=TRUE`（对应被版本化的表别名）。
   - **配置 SQL 模板**（`component_sql_view.sql_template` / `template_sql_view.sql_template`）：Flyway 迁移 `UPDATE ... SET sql_template=...` 注入 is_current 谓词。这是运行时 BNF 解释执行的字符串，非 PG 对象。
3. **强制重启 + 缓存纪律（CLAUDE.md 红线）**：任何视图 `DROP CASCADE`/重建后**必须 touch 一个 java 文件强制 Quarkus 重启**，否则 `ImplicitJoinRewriter.tableColumnsCache` / `CachedSqlCompiler` / `CachedPathParser` 进程级缓存残留 → 视图返全表 N 行 → "首值(共N项)"错乱（AP-22 家族）。配置 SQL 模板改动同理需让 `BnfTableMetaSyncer` 重新同步。
4. **强制 E2E**：driver/视图链路改动须跑 `quotation-flow.spec.ts`（CLAUDE.md + `docs/E2E测试方法.md`）。

**Tech Stack:** PostgreSQL 16 / Flyway / Quarkus 3.34 / Playwright E2E。**迁移号 V280**（V279 已占用）。**DB 连接（env-2）**：`host=10.177.152.12 port=5432 user=postgres db=cpq_db password=joii5231`。**存活验证（env-1）**：`curl /api/cpq/components` 期望 401（无 /q/health）。

---

## §0 范围清点（2026-06-02 活库 pg_views + 配置表实测，权威）

> 来源 = 活库当前定义（迁移文件含大量被覆盖旧定义，**不可作准**）。引用这 5 表的对象 **46 个**（19 PG 视图 + 24 component_sql_view + 3 template_sql_view），其中 **4 个 PG 视图已含 is_current**。

### A. PG 视图（19，引用表 + 是否已过滤）

| 视图 | 引用表 | 已 is_current | 初判段 |
|------|--------|--------------|--------|
| v_composite_child_elements | element_bom | - | **报价**（待 9a 确认 QUOTE 谓词） |
| v_composite_child_materials | material_bom | - | **报价** |
| v_composite_child_processes | unit_price, material_bom | - | **报价**（实测 `system_type='QUOTE'`）|
| v_composite_child_weights | material_bom | - | **报价** |
| v_c_consumable_prod_merged | unit_price | - | 核价? 9a 判 |
| v_c_depreciation_merged | unit_price | - | 核价? |
| v_c_energy_aux_merged | unit_price | - | 核价? |
| v_c_energy_prod_merged | unit_price | - | 核价? |
| v_c_finished_proc_merged | unit_price | - | 核价? |
| v_c_incoming_proc_merged | unit_price | - | 核价?（实测无 system_type 谓词→需细看 price_type）|
| v_c_labor_cost_merged | unit_price | - | 核价? |
| v_c_outsource_merged | unit_price | - | 核价? |
| v_c_packaging_merged | unit_price | - | 核价? |
| v_c_raw_bom_priced | unit_price | - | 核价? |
| v_c_tooling_merged | unit_price | - | 核价? |
| v_c_summary_agg | unit_price | ✅HAS | 复核过滤的是不是这 5 表 |
| v_costing_summary_full | unit_price | ✅HAS | 复核 |
| v_q_component_merged | unit_price | ✅HAS | 复核 |
| v_q_siemens_class1_costs | unit_price | ✅HAS | 复核 |

### B. component_sql_view 配置模板（24，全部未过滤）

| sql_view_name | 引用 | 初判 |
|---|---|---|
| gx_view | UP | **报价**（实测 `system_type='QUOTE' AND price_type='MATERIAL'`）|
| composite_child_elements_mirror / materials_mirror / processes_mirror / weights_mirror | EB/MB | **报价**（mirror 系列）|
| cz_view / zcj_view / zcj_bom / zpj_view / ys_view | UP/MB/EB | 9a 判（读 QUOTE?）|
| v12_consumable_prod / depreciation_cost / energy_aux_cost / energy_prod_cost / finished_proc / incoming_proc / labor_cost / outsource_cost / packaging / tooling_cost | UP | 9a 判（v12 系列，疑核价成本组件）|
| v12_plating_cost | UP, PS | **含 plating_scheme** → 报价? |
| v12_plating_scheme | PS | **plating_scheme** → 报价? |
| v12_raw_bom | MB | 9a 判 |
| v12_raw_element_bom | EB, MB | 9a 判 |

### C. template_sql_view 配置模板（3，全部未过滤）

| sql_view_name | 引用 | 初判 |
|---|---|---|
| summary_material | UP, EB | 9a 判 |
| summary_part | UP | 9a 判 |
| summary_plating_cost | UP | 9a 判 |

### D. 特例
- **capacity：无任何 PG 视图 / 配置模板引用**（`FROM/JOIN capacity` 实测 0 命中）。说明 capacity（QUOTE_ASSEMBLY）经 **Java driver**（如 `ComponentDriverService` / `data_driver_path`）消费 → is_current 过滤点在**代码或 driver 路径**，非视图（Task 9c 专项）。
- **plating_scheme：全局表无 system_type**；读它的 `v12_plating_cost` / `v12_plating_scheme` / `summary_plating_cost` 必须加 is_current（无论报价/核价，因 Q16 已版本化全局表）。

---

## Phase A：精确分段（决定 worklist，必做且最关键）

### Task 9a: 逐对象分类「是否读版本化 QUOTE 行 + 过滤注入点」

**Files:** 只读核查（活库 + 反模式文档）。产出 `docs/table/视图is_current-worklist.md`。

- [ ] **Step 1:** 对 §0 A/B/C 每个**未过滤**对象，导出其完整定义，判定 3 件事：
  - (i) 它读的目标表别名是否带 **`system_type='QUOTE'`**（unit_price/element_bom/material_bom）或 **`resource_group_no='QUOTE_ASSEMBLY'`**（capacity）或就是 **plating_scheme**（全局，一律需要）；
  - (ii) 若**不带** QUOTE 谓词且只读 PRICING/混合 → 标 **OUT（核价，本期不改）**，但记录原因备查；
  - (iii) 注入点：该表别名 + 应加 `AND <alias>.is_current = TRUE` 的具体 WHERE 子句位置（注意 LEFT JOIN 要放 ON 而非 WHERE，避免把外连接变内连接漏行）。
  导出命令：
  ```bash
  PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA \
    -c "SELECT definition FROM pg_views WHERE viewname='<v>';"
  PGPASSWORD=joii5231 psql ... -c "SELECT sql_template FROM component_sql_view WHERE sql_view_name='<n>';"
  ```
- [ ] **Step 2:** 复核 4 个 **已 HAS is_current** 的视图：确认过滤的就是这 5 表的对应别名（而非别的联表），无遗漏的第二处引用。
- [ ] **Step 3:** 产出最终 **IN-scope worklist**（对象 → 表别名 → 注入子句 → PG视图/配置模板），分「PG 视图」「component_sql_view」「template_sql_view」「capacity/driver」四组。**LEFT JOIN 的 is_current 必须进 ON 子句**单独标注。
- [ ] **Step 4:** 把 worklist 给人确认范围（报价侧必改；核价侧本期 OUT）后再进 Phase B。

> ⚠️ **判定纪律**：`v_c_*` / `v12_*` 名字像核价，但 `gx_view` 实测读 QUOTE。**一律以 SQL 里的 system_type/resource_group_no 谓词为准**，不靠命名。

---

## Phase B：PG 视图加 is_current（迁移 V280）

### Task 9b-1: 报价侧 PG 视图（v_composite_child_* 等 IN-scope）

**Files:** Create `db/migration/V280__quote_views_is_current.sql`

- [ ] **Step 1:** 对 worklist 中每个 IN-scope PG 视图，`DROP VIEW IF EXISTS <v> CASCADE; CREATE VIEW <v> AS <原定义 + is_current 过滤>;`
  - 内连接：`WHERE ... AND <alias>.is_current = TRUE`。
  - 外连接（LEFT JOIN 这 5 表）：把 `AND <alias>.is_current = TRUE` 放进 **ON 子句**，不放 WHERE（否则 LEFT→INNER 漏行）。
  - 保留视图原有列顺序/语义/列名（下游 declared_columns 依赖）。
- [ ] **Step 2（CASCADE 链）:** `DROP ... CASCADE` 会级联删依赖视图；迁移内**按依赖顺序重建所有被级联删的视图**（先底层后聚合）。用 `SELECT ... FROM pg_depend` 或先跑一遍看报错补齐。
- [ ] **Step 3:** touch 一个 java 文件 → Quarkus 重启跑 Flyway；`flyway_schema_history` version='280' success=t（env-2 连接）。
- [ ] **Step 4:** 验证：含 BNF 路径的 driver 端点返**单值非数组**（`v_xxx.col` 不出现 "(共N项)"）。

### Task 9b-2: 核价侧（若 9a 判定有 IN-scope，否则跳过）

- [ ] 仅当 Task 9a 发现某 `v_c_*` 实际读 QUOTE 行才纳入；否则记 OUT 跳过。

---

## Phase C：配置 SQL 模板加 is_current（迁移 V280 续 / V281）

### Task 9c-1: component_sql_view / template_sql_view 模板注入

**Files:** Create `db/migration/V281__quote_sql_templates_is_current.sql`（与 V280 分开，便于回滚）

- [ ] **Step 1:** 对 worklist 中每个 IN-scope 配置模板，`UPDATE component_sql_view SET sql_template = <注入 is_current 后的全文>, updated_at=NOW() WHERE sql_view_name=:n;`（template_sql_view 同）。注入规则同 9b（内连接进 WHERE、外连接进 ON）。
- [ ] **Step 2:** 必须配 `declared_columns` 不变（只动谓词，不改列）。
- [ ] **Step 3:** touch java 重启 → `BnfTableMetaSyncer` startup sync OK；`CachedSqlCompiler`/`CachedPathParser` 清空。
- [ ] **Step 4:** 验证：报价单渲染对应组件 driver 返当前版本行数（非累加）。

### Task 9c-2: capacity / driver 路径（特例）

**Files:** 排查 `ComponentDriverService.java` / `component_sql_view` 中 capacity 消费点。

- [ ] **Step 1:** `grep -rniE "capacity|QUOTE_ASSEMBLY" cpq-backend/src/main/java` 找 capacity 读取点（Q14 写 QUOTE_ASSEMBLY，谁读）。
- [ ] **Step 2:** 若有 SQL 视图/模板读 capacity → 加 `is_current=TRUE`；若 Java 直查 → 加谓词。若当前**无消费方** → 记录"capacity 暂无渲染消费方，is_current 写入已就绪，待消费方接入时过滤"，本期不动。

---

## Phase D：验收（Task 10）

### Task 10-1: 后端两遍导入幂等 + 升版断言

- [ ] **Step 1:** `./mvnw test`：本任务无新单测则跑既有 versioning + handler 47 例确认未回归（视图改动不影响 EntityManager 单测，但跑一遍兜底）。
- [ ] **Step 2:** 真实 Excel 导两遍：各表 `COUNT(is_current=true)` 稳定、版本不变；**改一处再导** → 对应组版本 +1、旧 is_current=false 保留。
- [ ] **Step 3（核心新增）:** 升版后**查每个 IN-scope 视图**：`SELECT count(*)` 与「业务应有行数」一致（**不因 is_current=false 旧行翻倍**）。这是本任务的主验收点。

### Task 10-2: E2E

- [ ] **Step 1:** 按 `docs/E2E测试方法.md` 跑 `quotation-flow.spec.ts`，断言 `'加载中' final count=0`、8 Tab `'加载中'=0`、无 "(共N项)"。
- [ ] **Step 2（升版场景）:** 导入→渲染→改值重导(升版)→**强刷重渲染**：断言渲染值=新版本、行数不翻倍。
- [ ] **Step 3:** 自检声明：V280/V281 success=t（env-2）/ 业务端点 401（env-1）/ E2E 1 passed `'加载中'=0` / 视图行数断言通过。
- [ ] **Step 4:** 提交 + 更新 `docs/RECORD.md`。

---

## 风险与回滚

- **回滚**：V280（PG 视图）与 V281（配置模板）分开，便于单独回滚；保留每个对象改动前定义于迁移注释。
- **CASCADE 漏重建**：DROP CASCADE 后必须重建所有被级联删视图——用 `pg_views` 改动前快照对账「改动后视图总数 = 改动前」。
- **LEFT JOIN 陷阱**：is_current 放 WHERE 会把外连接变内连接 → 主行被滤掉 → 渲染缺行。**一律先判 JOIN 类型**。
- **缓存未清**：改完未重启 → 进程级缓存残留 → "(共N项)"。**每次 DDL/模板改动后必重启 + 验证单值**。
- **范围误扩**：误改核价（PRICING）视图不会立刻报错但可能改变核价取数 → 严格按 9a 的 system_type 谓词判定，OUT 的不碰。

## Self-Review（覆盖核对）
- §0 实测 46 对象（19 PG + 24 csv + 3 tsv，4 已过滤）→ Task 9a 精确分段 ✅
- 报价/核价分段靠 system_type 谓词（gx_view 实测 QUOTE 反例）→ 9a Step 1 ✅
- 两套机制（PG 视图 DROP+CREATE / 配置模板 UPDATE）→ Phase B / C ✅
- capacity 无视图消费 → 9c-2 专项 ✅
- 重启/缓存/LEFT JOIN/E2E 红线 → Architecture + 风险段 ✅
- 迁移 V280/V281（V279 已占用）✅
