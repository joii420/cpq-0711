# 后端任务文档 · 废弃业务与表清洗（task-0723）

> 隶属：`dev-docs/task-0723-废弃业务与表清洗`
> 契约以同目录 `api.md` 为准；审计事实以 `需求说明.md` §4 为准。
> **后端是本任务主战场。** 7 阶段一次性交付，同一 worktree 分支，技术总监统一验收后合并。
> 开发在独立 worktree 分支进行。技术总监负责验收与合并，不代写代码。

---

## B0 · 开工前必读

### 本任务的核心手法：DROP → RENAME `_drop`（可逆软删除）

**所有表和视图不执行 `DROP`，一律 `ALTER ... RENAME TO xxx_drop`。**

| 好处 | 说明 |
|---|---|
| 可逆 | 出问题 `ALTER ... RENAME TO xxx`（去掉 `_drop`）即回滚，数据零损失 |
| 大声报错 | 遗漏的活代码触发 `relation "xxx" does not exist`，不是静默取空 |
| 可验证 | 配合 `pg_stat_reset()` 能拿到"无活代码读"的**正向硬证据** |

**Java 代码**（Resource/Service/Entity）该删仍真删——代码没有"改名软删"一说。

### 三条排查纪律（违反 = 误判）

1. **`/usr/bin/grep -a`**，不用裸 `grep`（ugrep 把中文大文件静默当二进制返空，记忆 `cpq-grep-ugrep-binary-pitfall`）。
2. **BNF 引用用点号语法** `逻辑名\.列` 正则，不用裸子串（需求说明 §10 记录过三次误判：「生产料号」字段显示名 / 「电镀费用」页签名 / `plating_mat_fee` 别名）。
3. **实体消费方用类名查 + `codegraph_impact` 复核**（Panache 走类名，grep 表名字符串看不到别名/间接引用）。

### 已核实的现状（技术总监 2026-07-23 亲验，可直接采信）

| # | 事实 | 对本任务的约束 |
|---|---|---|
| 1 | 漂移检测 `referenced_versions` **0/321 有值**，现役键组合命中 `mat_fee`/`mat_process` **0/22** | 从未生效，整体下线不影响任何用户可见功能 |
| 2 | `part_version_locked` **138/138 = 2000**（默认值） | 版本族从未生效，整族下线 |
| 3 | 客户料号 V6 命中 21/22（全键 `customer.code + material_no`），V44 命中 0/22 | 改读 V6 是净收益 |
| 4 | `costing_template` **17/17 零绑定**（含 7 行测试夹具） | 整体退役 |
| 5 | 3 死全局变量在 live 配置载体 **0 引用**；254 核价单 frozen_dto 仅元数据引用 | 先停用观察再改名 |
| 6 | `basic_data_config` **58 条 ACTIVE** 指向废弃表；`component_sql_view.sql_template` **0 命中** | 渲染主链路干净，损害全在辅助元数据 |
| 7 | 4 视图依赖 `mat_*`（`v_q_part_info_merged`/`v_c_summary_agg`/`v_costing_summary_full`/`v_part_material_recipe`），正好是第 1 层待改名视图 | 视图必须先于底表改名（§B0 依赖顺序） |
| 8 | `PostgreSQL RENAME 不破坏视图`（OID 绑定） | 改名验证法对视图是盲的，靠施工顺序 5→7 规避 |

### 严格依赖顺序（不可打乱）

```
阶段1 摘按钮（前端）
  ↓
阶段2 报价热路径改 V6（漂移下线 / 客户料号 / 版本族下线）  ← 须 E2E
  ↓
阶段3 V5/import-session 退役
  ↓
阶段4 旧核价引擎退役
  ↓
阶段5 视图改名 + 全局变量停用 + 配置 INACTIVE
  ↓
阶段6 SchemaContext 删映射
  ↓
阶段7 表改名 _drop + 三重验证    ← 视图必须已在阶段5改名；映射必须已在阶段6删除
```

> **为什么表改名放最后**：`mat_part` 等表被 `ImplicitJoinRewriter`/`BnfPathLinter`/`SchemaContext`/`basic_data_config` 引用。只有阶段 2（版本族下线）+ 阶段 5（配置 INACTIVE）+ 阶段 6（映射删除）全做完，这些表才真正无活引用，改名才不会触发运行时报错。

---

## B1 · 阶段 2 · 漂移检测整体下线

### 删除

| 对象 | 处置 |
|---|---|
| `quotation/service/DriftDetectionService.java` | **整个类删除** |
| `QuotationService` 内调用点 | `:696` / `:727` / `:784` / `:2502` 四处 `collectReferencedVersions(...)` 调用删除；`detect(...)` 调用删除（getById 路径） |
| `refresh-versions` 端点 | `QuotationResource` 内对应方法删除 |
| `QuotationDTO.driftDetection` 字段 | 删除，或恒置 `{hasDrift:false, driftedRecords:[]}`（与前端 F2 对齐，倾向直接删字段） |
| `DriftedRecordDTO` / `RefVersionEntry` | 确认无其他消费方后删除 |

### 保留

- `quotation.referenced_versions` **列保留**（全空，历史兼容，不写 Flyway 删列）
- `saveDraft` / `submit` 的其余逻辑不动，只摘掉漂移采集那几行

### 验证

```bash
/usr/bin/grep -a -rn "DriftDetection\|collectReferencedVersions\|referencedVersions" cpq-backend/src/main/java/
# 期望：仅剩 referenced_versions 列的实体字段声明（若保留），无 service 调用
```

---

## B2 · 阶段 2 · 客户料号三字段改读 V6

### 改造 `QuotationService.loadLineItems`（`:2491` 附近）

现状 SQL：
```sql
SELECT hf_part_no, customer_part_name, customer_product_no, customer_drawing_no
FROM mat_customer_part_mapping
WHERE customer_id = :cid AND hf_part_no IN (:pns)
```

改为读 V6 `material_customer_map`，全键匹配：
```sql
SELECT v.material_no, v.customer_material_name, v.customer_product_no, v.customer_drawing_no
FROM material_customer_map v
JOIN customer c ON c.code = v.customer_no
WHERE c.id = :cid AND v.material_no IN (:pns)
```

### 字段映射

| V44 列 | V6 列 |
|---|---|
| `customer_part_name` | `customer_material_name` |
| `customer_product_no` | `customer_product_no`（同名） |
| `customer_drawing_no` | `customer_drawing_no`（同名） |
| `customer_id`(UUID) 匹配 | `customer_no`(文本) 经 `customer.code` 桥接 |
| `hf_part_no` 匹配 | `material_no` |

### 纪律

- **全键严格匹配，不兜底**。不做"仅 `material_no`"降级——会重现森萨塔跨客户串号（记忆 `sensata-crosscustomer-is-repair2`）。
- 匹配不到 → 三字段留空（与现状一致）。
- **验收改为「取数正确」而非「非空」**：`material_customer_map` 现有数据稀（127 行仅 1 行有客户品名），非空不可达；验证口径 = 能匹配到的行值与表一致，匹配不到显示空。

---

## B3 · 阶段 2 · 料号版本族整族下线

### 删除

| 对象 | 处置 |
|---|---|
| `partversion/PartVersionService.java` | 整类删除（读写 `mat_customer_part_mapping.current_version` + `mat_part_version_log`） |
| `partversion/PartVersionPredicateBuilder.java` | 确认仅版本族使用后删除 |
| `part-version/**` 端点 | 对应 Resource 删除 |
| `QuotationService` 内切版本端点 | 报价内切版本逻辑删除（前端 6 处入口同步删，见 fronttask F3） |

### 保留

- `quotation_line_item.part_version_locked` **列保留**（138/138=2000，历史兼容）

### ⚠️ 依赖注意

`ImplicitJoinRewriter` / `BnfPathLinter` 也 grep 命中 `mat_part`，但那是**基础设施枚举表名**，不是版本族，**不要**在本步删。它们随阶段 6/7 处理。

---

## B4 · 阶段 2 · SnapshotCollectorService 客户料号块

`snapshot/SnapshotCollectorService.java`（`:315`）读 `mat_customer_part_mapping` 的"客户料号映射"块 → 改读 `material_customer_map`，全键匹配（复用 B2 的 SQL 范式）。

其余快照采集逻辑不动。

---

## B5 · 阶段 3 · V5 / import-session 死链路退役

### ⚠️ 退役前置核查（阻塞项，不通过不能删）

用 `codegraph_trace` 从 V6 导入 Drawer（`QuoteBasicDataImportV6Drawer`）追到后端，确认正式导入落点是 `VersionedV6Writer`，**不经过** `ImportSessionService` / `StagingWriter` / `BasicDataImportServiceV5`。

> `StagingWriter:63` 复用了 `BasicDataImportServiceV5.parseExcel`，注释称"V6 staging flow 复用"——但那是 **import-session 自己的 staging 流程**（僵尸链路），随 import-session 一起退役，不是 `basicdata.v6` 正式路径。核查通过后一起删。

### 删除清单

| 模块/文件 | 处置 |
|---|---|
| `importsession/`（整个包，15 文件：Resource + Service + StagingWriter + StagingMerger + DiffDetector + entity×2 + dto×8） | 整包删除 |
| `importexcel/resource/BasicDataImportV5Resource.java` | 删除 |
| `importexcel/service/BasicDataImportServiceV5.java` | 删除（含 `importBasicDataV5`/`VersionedWriter`/`doImportInTx`/`parseExcel`/`detectCustomerDataConflicts`） |
| `importexcel/service/FieldMetaCache.java` | 删除（V5+import-session 专用） |
| `importexcel/parser/ParsedBasicData.java` | 确认仅 V5/import-session 使用后删除 |
| `QuotationService.reimportBasicData` + `reimport-basic-data` 端点 | 删除 |

### 保留

- `basicdata.v6` 全部（Q/P handler + `VersionedV6Writer`）
- `importexcel` 包内 V6 正式导入用到的解析器（若 `ParsedBasicData` 被 V6 复用则保留，核查后定）

### 验证

```bash
/usr/bin/grep -a -rn "ImportSession\|BasicDataImportServiceV5\|FieldMetaCache\|import-session\|basic-data/v5" cpq-backend/src/main/java/
# 期望 0 命中（除非 ParsedBasicData 被 V6 复用而保留）
```

---

## B6 · 阶段 4 · 旧核价引擎退役

### 后端删除（3 整模块 + 精确摘除）

| 模块/文件 | 处置 |
|---|---|
| `costingpart/`（11 文件：Resource + Service + entity×9） | 整包删除 |
| `costingsummary/`（5 文件：Resource + Service + entity×3） | 整包删除 |
| `costingbasic/`（11 文件：Resource + Service + entity×4 + dto×5） | 整包删除 |
| `costing/service/CostingTemplateService.java` + `resource/CostingTemplateResource.java` + `entity/CostingTemplate.java` + `dto/CostingTemplateDTO.java` + `dto/CreateCostingTemplateRequest.java` | 删除（17/17 零绑定） |
| `costing/service/CostingSheetService.java` + `entity/CostingSheet.java` + `dto/CostingSheetDTO.java` | 删除（`costing_sheet` 0 行，前端 0 引用） |

### ⚠️ 方法级精确摘除（勿删整文件）

| 文件 | 只删 | 保留 |
|---|---|---|
| `costing/resource/CostingSheetResource.java`（`@Path("/api/cpq/quotations")`） | `getCostingSheet` 方法（`/{id}/costing-sheet`） | `getComparison`（`/{id}/comparison`）、`exportComparison`（比对视图，task-0717 活功能）——**删完这文件里还剩比对方法，文件本身保留** |
| `component/service/ComponentService.java`（`:100` / `:137`） | 拆掉 `assertNotReferencedByCostingTemplate()` 调用与方法（删组件前检查是否被核价模板引用的护栏，`costing_template` 没了护栏也没了） | 其余护栏逻辑不动 |

### 全保留（活功能，误删即事故）

- `costing/**` 的 `ComparisonView*` / `ComparisonExport*`（比对视图）
- `CostingOrder*`（财务核价工作台，286 单活跃）+ 菜单 `/costing-summary`
- `TemplateSqlViewService` / `TemplateSqlViewRepository`（V249 起替代旧 `CostingTemplateSqlView*`，注释里提到 CostingTemplate 但已是新实现）

> ⚠️ **命名撞名**：旧引擎 `costing_summary` 表 / `CostingSummaryResource` 要删；新引擎财务工作台 `costing_order` 表 / `CostingOrderListPage` / 菜单 `/costing-summary` **保留**。只是名字撞了，别连坐。

### ⚠️ 删 Java 源文件后的坑

删源文件后主仓 `target/` 可能残留旧 `.class` → Quarkus 启动 CDI `UnsatisfiedResolutionException`（worktree 绿、合并回主仓炸）。**合并后必须在主仓跑 `./mvnw clean test`**（记忆 `task0709-update0723-quote-import-template`）。

---

## B7 · 阶段 5/6/7 · DDL 清理（Flyway 迁移）

### Flyway 版本号纪律

共享库 `cpq_db` 有 13 个活跃 worktree 并发，版本号是移动靶。**每个迁移文件的版本号实施时现取**：
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db \
  -tAc "SELECT MAX(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';"
```
取结果 +1。**已应用的迁移禁止改名改号**（记忆 `cpq-shared-flyway-history-churn`）。

> ⚠️ task-0722 update-0724 正在并行占用版本号（建 `element_daily_price_log`）。写迁移前**现查**，别与它撞号。

### B7.1 阶段 5 迁移 · 视图改名 + 全局变量停用 + 配置 INACTIVE

`V<NNN>__task0723_stage5_deprecate_costing_views.sql`：

```sql
-- 第 1 层视图（4，零运行时引用）
ALTER VIEW v_costing_summary_full RENAME TO v_costing_summary_full_drop;
ALTER VIEW v_c_summary_agg        RENAME TO v_c_summary_agg_drop;
ALTER VIEW v_q_part_info_merged   RENAME TO v_q_part_info_merged_drop;
ALTER VIEW v_part_material_recipe RENAME TO v_part_material_recipe_drop;

-- 第 2 层价格视图（3，仅 frozen_dto 元数据引用）
ALTER VIEW v_costing_element_price  RENAME TO v_costing_element_price_drop;
ALTER VIEW v_costing_material_price RENAME TO v_costing_material_price_drop;
ALTER VIEW v_costing_exchange_rate  RENAME TO v_costing_exchange_rate_drop;

-- 死全局变量停用（不删行）
UPDATE global_variable_definition SET is_active = false
WHERE code IN ('ELEM_PRICE','MAT_PRICE','EXCHANGE_RATE');

-- 废弃配置置 INACTIVE（不删行，保留追溯）
UPDATE basic_data_config SET status = 'INACTIVE'
WHERE target_table LIKE 'mat\_%' OR target_table LIKE 'costing\_%';
```

> ⚠️ 视图改名会临时改变依赖拓扑。执行后**必须 `touch` 一个 java 文件强制 Quarkus 重启**，清 `ImplicitJoinRewriter.tableColumnsCache` 等进程级缓存（CLAUDE.md「视图 DROP CASCADE/重建后必须重启」，改名同理）。

### B7.2 阶段 6 · SchemaContext 删映射（Java，非 Flyway）

`datapath/sql/SchemaContext.java` 的 `defaultContext()`（`:143-151`）删除 8 条：

```java
// 删除以下 8 条 tableMapping（指向废弃表）：
.tableMapping("元素BOM",    "mat_bom")
.tableMapping("来料BOM",    "mat_bom")
.tableMapping("组成件BOM",  "mat_bom")
.tableMapping("生产料号",    "mat_part")
.tableMapping("工序资料",    "mat_process")
.tableMapping("料号费用",    "mat_fee")
.tableMapping("客户料号对应", "mat_customer_part_mapping")
.tableMapping("电镀方案",    "plating_plan")
// 保留：电镀费用→plating_fee（V6活）、汇率→exchange_rate、客户税率→customer_tax
```

同步删除这 8 个逻辑名下的 `columnMapping`（`:157` 起，避免悬空列映射）。

> 影响：删除后配 `元素BOM.xxx` 路径会立即报「未知逻辑名」——潜伏陷阱变显式报错。配置层实测 0 命中，无现存破坏。

### B7.3 阶段 7 迁移 · 表改名 `_drop`

**⚠️ 本迁移的表清单必须基于三重验证的 pg_stat 探针结果编写——只把 scan=0 的表写进来。** 见 B8 工作流。

37 张候选表（`V<NNN>__task0723_stage7_rename_deprecated_tables.sql`）：

```sql
-- mat_* V44（18 张，含 staging）
ALTER TABLE mat_bom                          RENAME TO mat_bom_drop;
ALTER TABLE mat_bom_staging                  RENAME TO mat_bom_staging_drop;
ALTER TABLE mat_composite_process            RENAME TO mat_composite_process_drop;
ALTER TABLE mat_customer_part_mapping        RENAME TO mat_customer_part_mapping_drop;
ALTER TABLE mat_customer_part_mapping_staging RENAME TO mat_customer_part_mapping_staging_drop;
ALTER TABLE mat_fee                          RENAME TO mat_fee_drop;
ALTER TABLE mat_fee_staging                  RENAME TO mat_fee_staging_drop;
ALTER TABLE mat_part                         RENAME TO mat_part_drop;
ALTER TABLE mat_part_model                   RENAME TO mat_part_model_drop;
ALTER TABLE mat_part_source_file             RENAME TO mat_part_source_file_drop;
ALTER TABLE mat_part_staging                 RENAME TO mat_part_staging_drop;
ALTER TABLE mat_part_version_log             RENAME TO mat_part_version_log_drop;
ALTER TABLE mat_plating_fee                  RENAME TO mat_plating_fee_drop;
ALTER TABLE mat_plating_fee_staging          RENAME TO mat_plating_fee_staging_drop;
ALTER TABLE mat_plating_plan                 RENAME TO mat_plating_plan_drop;
ALTER TABLE mat_plating_plan_staging         RENAME TO mat_plating_plan_staging_drop;
ALTER TABLE mat_process                      RENAME TO mat_process_drop;
ALTER TABLE mat_process_staging              RENAME TO mat_process_staging_drop;

-- 旧核价引擎（16 张）
ALTER TABLE costing_element_price      RENAME TO costing_element_price_drop;
ALTER TABLE costing_exchange_rate      RENAME TO costing_exchange_rate_drop;
ALTER TABLE costing_material_price     RENAME TO costing_material_price_drop;
ALTER TABLE costing_price_version      RENAME TO costing_price_version_drop;
ALTER TABLE costing_part_design_cost   RENAME TO costing_part_design_cost_drop;
ALTER TABLE costing_part_element_bom   RENAME TO costing_part_element_bom_drop;
ALTER TABLE costing_part_material_bom  RENAME TO costing_part_material_bom_drop;
ALTER TABLE costing_part_plating       RENAME TO costing_part_plating_drop;
ALTER TABLE costing_part_plating_fee   RENAME TO costing_part_plating_fee_drop;
ALTER TABLE costing_part_process_cost  RENAME TO costing_part_process_cost_drop;
ALTER TABLE costing_part_quality_check RENAME TO costing_part_quality_check_drop;
ALTER TABLE costing_part_tooling_cost  RENAME TO costing_part_tooling_cost_drop;
ALTER TABLE costing_part_weight        RENAME TO costing_part_weight_drop;
ALTER TABLE costing_summary            RENAME TO costing_summary_drop;
ALTER TABLE costing_summary_override   RENAME TO costing_summary_override_drop;
ALTER TABLE costing_summary_result     RENAME TO costing_summary_result_drop;

-- 旧核价模板/明细表（2）+ SchemaContext 电镀方案表（1）
ALTER TABLE costing_template RENAME TO costing_template_drop;
ALTER TABLE costing_sheet    RENAME TO costing_sheet_drop;
ALTER TABLE plating_plan     RENAME TO plating_plan_drop;
```

> ⚠️ **`plating_plan`（3 行冻结）vs `plating_fee`（V6 活表）别搞混**——只改 `plating_plan`，`plating_fee` 保留。
> ⚠️ 改名后同样 `touch` java 重启清缓存。

**本任务不做真 `DROP`**（`_drop` 表留在库里观察；真删是后续独立批次，需求说明 §2 明确）。

---

## B8 · 三重验证工作流（阶段 7 的通过标准）

**这是本任务验收的核心。三重全绿后交技术总监，技术总监亲验通过才算交付。**

### 第 1 重 · 静态审计（改名迁移编写前）

对 37 张候选表，逐张双验无活代码引用：
```bash
# grep 硬验（-a 规避 ugrep 坑）
/usr/bin/grep -a -rn "\b<表名>\b" cpq-backend/src/main/java/
```
- 命中的只应是：注释、`_drop` 后缀无关词、已删模块的残留（应已在阶段2~6清掉）
- 对有 `@Table` 的实体，用 `codegraph_impact` 跑实体类，确认调用边为 0

### 第 2 重 · 运行时探针（改名迁移编写前，**决定哪些表进迁移**）

```sql
-- 1. 清零计数器
SELECT pg_stat_reset();
```
```
-- 2. 跑一遍完整业务动作（技术总监会配合，工程师提供 checklist）：
--    报价单：新建 → 打开 → 存草稿 → 提交 → 导出 Excel → 比对视图
--    核价单：打开财务工作台 → 打开一张单 → 提交
--    V6 导入：导一张基础资料表
--    模板渲染：打开 PUBLISHED 核价通用模板渲染
```
```sql
-- 3. 查废弃表的扫描计数
SELECT relname, seq_scan, idx_scan
FROM pg_stat_user_tables
WHERE relname LIKE 'mat\_%' OR relname LIKE 'costing\_part%'
   OR relname LIKE 'costing\_summary%' OR relname IN
   ('costing_template','costing_sheet','plating_plan','costing_element_price',
    'costing_material_price','costing_exchange_rate','costing_price_version')
ORDER BY seq_scan + COALESCE(idx_scan,0) DESC;
```

**判定**：
- `seq_scan = 0 AND idx_scan = 0` 的表 → 无活代码读，**写进阶段 7 改名迁移**
- `scan ≠ 0` 的表 → **还有活消费方没清干净**，回到阶段 2~6 定位并清除，**该表暂不改名**，在交付说明里列出其残留消费方

> 这是"改名验证业务是否受影响"的正向硬证据——grep 只能证明"我没搜到"，`pg_stat` 能证明"运行时真没读"。

### 第 3 重 · 改名后回归（迁移应用后）

```
1. 应用阶段 7 改名迁移 → touch java 重启 Quarkus
2. 重跑第 2 重的同一套业务动作
3. 观察 Quarkus 日志：零 `relation "xxx" does not exist`
4. 跑 E2E：cd cpq-frontend && npx playwright test e2e/quotation-flow.spec.ts --reporter=list
   - A/B 同型对比：干净 master 已知恒 3 失败（夹具单缺产品分类，记忆 task0712-update071501-category-axis）
   - 判定 = 新增失败数 0，不是全绿
5. 报价单/核价单/详情页三视图人工复测无破坏
```

**三重全绿 → 交技术总监亲验（技术总监会重跑关键路径 + curl 下线端点确认 404 + curl 保留端点确认存活）。**

---

## B9 · 强制自检（每阶段结束前）

### 后端通用
```bash
cd cpq-backend && ./mvnw -q compile          # 0 错误
touch src/main/java/com/cpq/CpqApplication.java   # 触发 dev 重启，等 5-7s
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components  # 401
```
> ⚠️ `--noproxy '*'` 必加（本机 http_proxy 会返 502）；`/q/health` 不是健康探针（恒 404）。

### 每个 Flyway 迁移
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='<NNN>';"   # success=t
```
> ⚠️ 不要手工 `psql -f` 跑迁移，让 Quarkus `migrate-at-start` 自己跑（touch java 触发）。手工跑会 checksum mismatch 启动失败。
> ⚠️ 任何视图/表改名后**必须 touch java 重启**，清 `ImplicitJoinRewriter`/`CachedSqlCompiler`/`CachedPathParser` 进程级缓存。

### 下线端点返 404 + 保留端点存活（api.md §8）
```bash
# 下线端点
for p in "quotations/00000000-0000-0000-0000-000000000000/refresh-versions" \
         "costing-templates" "costing-part/list" "import-session/list"; do
  printf '%s -> ' "$p"
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST "http://localhost:8081/api/cpq/$p"
done   # 期望 404
# 保留端点（比对视图、财务工作台、价格导入）逐个确认非 404
```

### 测试
```bash
cd cpq-backend && ./mvnw test    # ⚠️ 必须在 worktree 的 cpq-backend/ 里跑（mvnw 在此，不在仓库根）
```
> 删了 3 整模块 + 若干类，测试树会大变。删模块时同步删/改引用它们的测试，别留编译不过的孤儿测试。

### 交付说明必含这一行
> "编译 0 错误 ✅；`/api/cpq/components` → 401 ✅；阶段5迁移 V<N1> success=t、阶段7迁移 V<N2> success=t ✅；pg_stat 探针：37 候选表 scan 全 0（附计数快照）✅；下线端点 11 个全 404 ✅；保留端点（比对/工作台/价格导入）全存活 ✅；E2E A/B 同型新增失败=0 ✅；`ImportSession`/`BasicDataImportServiceV5`/`DriftDetection`/`PartVersionService`/`Costing{Part,Summary,Basic,Template,Sheet}` 全工程 0 命中（`/usr/bin/grep -a` + codegraph）✅"

**没有这一行的"完成"= 未完成。**

---

## 任务清单与阶段依赖

| 阶段 | 后端任务 | 规模 | 前端配对 |
|---|---|:--:|---|
| 2 | B1 漂移下线 / B2 客户料号 V6 / B3 版本族下线 / B4 快照块 | L | F2 横幅+版本UI |
| 3 | B5 V5/import-session 退役 | M | F4 向导/service |
| 4 | B6 旧核价引擎退役（精确摘除） | L | F5 孤儿页 |
| 5 | B7.1 视图改名+变量停用+配置INACTIVE | S | — |
| 6 | B7.2 SchemaContext 删映射 | S | — |
| 7 | B7.3 表改名 + B8 三重验证 | M | 两端联合验证 |

**建议顺序严格按阶段 2→3→4→5→6→7**（依赖链不可打乱，见 B0）。
阶段 5/6/7 的 DDL 与 Java 改动**必须在阶段 2~4 代码退役完成后**做，否则改名触发运行时报错。
