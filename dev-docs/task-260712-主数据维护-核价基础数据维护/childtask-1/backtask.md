# childtask-1 后端任务（backtask）

> 后端工程师进场基准。开工前先读同目录 `需求说明.md` + `api.md`。
> 守则：方案 **B（导入不写主表）**、**不改核价 10 个 P-handler**、遵 CLAUDE.md「修改后强制自检」。
> 探代码优先用 codegraph（`.codegraph/` 已建）。

---

## 任务总览

| 编号 | 任务 | 类型 | 规模 |
|---|------|------|------|
| **B1** | 工序主数据批量导入（service + 端点 + 模板） | 新增 | M |
| **B2** | 维护页「材质名」两跳 join | 改动 | S |
| **B3** | 核对元素/料号覆盖（不改代码，出验收结论） | 核对 | S |

> ⚠️ **无 Flyway 迁移**：`process_master(process_no)` 唯一索引 `uq_process_master_no` 已存在（`V218:142`），`ON CONFLICT (process_no)` 直接可用。**不要**再加索引迁移。

---

## B1 · 工序主数据批量导入

**目标**：在「主数据维护→工序管理」提供 xlsx 批量导入，把工序编号+名称（+选填列）upsert 进 `process_master`，供维护页「工序名」join 解析。

### B1.1 新增 `ProcessMasterImportService`
路径：`cpq-backend/src/main/java/com/cpq/basicdata/v6/service/ProcessMasterImportService.java`
样板：`configure/service/MaterialRecipeImportService.java`（解析 xlsx + 批量 upsert + 报告）。

职责：
1. 入参 `byte[] xlsx`；用现有 POI/Excel 解析工具读**首个 sheet**（或名为「工序」的 sheet）。
2. 表头列名 → 字段映射（按中文列名读，兼容常见别名）：

   | Excel 列名 | 目标字段 | 必填 | 说明 |
   |---|---|---|---|
   | `工序编号` | `process_no` | ✅ | 唯一键；空则该行跳过并记 skipped |
   | `工序名称` | `process_name` | ✅ | 空则跳过（`process_name` NOT NULL），记 skipped |
   | `工序类别` | `process_category` | ✖ | 选填 |
   | `是否外协` | `is_outsource` | ✖ | 选填，解析「是/否/true/false」 |
   | `标准币种` | `standard_currency` | ✖ | 选填 |
   | `标准单位` | `standard_unit` | ✖ | 选填 |
   | `默认不良率` | `default_defect_rate` | ✖ | 选填，DECIMAL(10,4) |

3. **去重**：同一 xlsx 内同 `process_no` 多行 → **首行胜出**（对齐需求 Q1=a），后续行记 skipped「重复工序编号，已取首行」。
4. **批量 upsert（写入语义 = 覆盖）**：一次性 native SQL，禁止逐行连库（对齐样板「全批量落库」）：
   ```sql
   INSERT INTO process_master
     (id, process_no, process_name, process_category, is_outsource,
      standard_currency, standard_unit, default_defect_rate,
      created_by, updated_by)
   VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?), ...
   ON CONFLICT (process_no) DO UPDATE SET
     process_name        = EXCLUDED.process_name,
     process_category    = COALESCE(EXCLUDED.process_category, process_master.process_category),
     is_outsource        = COALESCE(EXCLUDED.is_outsource, process_master.is_outsource),
     standard_currency   = COALESCE(EXCLUDED.standard_currency, process_master.standard_currency),
     standard_unit       = COALESCE(EXCLUDED.standard_unit, process_master.standard_unit),
     default_defect_rate = COALESCE(EXCLUDED.default_defect_rate, process_master.default_defect_rate),
     updated_by          = EXCLUDED.updated_by,
     updated_at          = NOW();
   ```
   - `process_name` 直接覆盖（这是导入的主目标）；选填列用 `COALESCE(EXCLUDED.x, 原值)`——**模板留空不清掉已有值**（避免选填列空白覆盖人工维护值）。
   - `created_at/created_by` 仅新建时写（`ON CONFLICT` 不动 created_*）。
5. 返回 `ProcessMasterImportReportDTO`（见 B1.3）：`totalRows / upsertedCount(新增+更新合计) / insertedCount / updatedCount / skippedRowCount / skipped[] / durationMs`。
   - 新增 vs 更新计数：upsert 前先 `SELECT process_no FROM process_master WHERE process_no IN (:nos)` 取已存在集合，据此分 inserted/updated。
6. `@Transactional`（整批一个事务）。
7. **`generateTemplate()`**：生成干净模板 xlsx（表头行 = 上表 7 列，前 2 列必填标注），供下载端点用。样板 `MaterialRecipeImportService.generateTemplate`。

### B1.2 `ProcessMasterResource` 加两个端点
路径：`cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/ProcessMasterResource.java`（已存在，加方法）。
样板：`MaterialRecipeResource#importLibrary` / `#downloadTemplate`。

```java
@POST @Path("/import")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@RoleAllowed({"SYSTEM_ADMIN"})     // 与材质库导入同权限口径
public ApiResponse<ProcessMasterImportReportDTO> importProcesses(@RestForm("file") FileUpload file) { ... }

@GET @Path("/import/template")
@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
public Response downloadTemplate() { ... }  // filename="process_master_template.xlsx"
```
- 读 `FileUpload` → `Files.readAllBytes(file.uploadedFile())` → `service.importProcesses(bytes, ctx.currentUserId())`。
- 现有 `ProcessMasterResource` 返回类型用 `ApiResponse<T>` 包装（对齐该类现有 list/create），保持一致。

### B1.3 新增 `ProcessMasterImportReportDTO`
路径：`cpq-backend/src/main/java/com/cpq/basicdata/v6/dto/ProcessMasterImportReportDTO.java`
样板：`configure/dto/MaterialImportReportDTO`。
```java
public class ProcessMasterImportReportDTO {
    public int totalRows;
    public int insertedCount;
    public int updatedCount;
    public int skippedRowCount;
    public List<SkippedRow> skipped = new ArrayList<>();
    public long durationMs;
    public static class SkippedRow { public Integer row; public String reason; public String raw; }
}
```

---

## B2 · 维护页「材质名」两跳 join

**目标**：核价维护页 `ELEMENT_BOM` sheet 的 `material_part_no`（材质料号）旁带出「材质名」= `material_recipe.name`。

### 背景（务必先懂）
- 现状：`PricingSheetRegistry.java:203` `ColumnDef.subDimReadonly("material_part_no", "材质料号")` —— **只读、无名称**。
- `readRows`（`PricingMaintenanceService.java:198-204`）现有 MASTER join 是**单跳**：`LEFT JOIN <master> ON <master>.<codeCol> = a.<codeCol>`，取不出两跳的材质名。
- 材质名路径是**两跳**：`material_part_no → material_master.material_recipe_id → material_recipe.name`。

### B2.1 Registry：给 material_part_no 增名称列
在 `PricingSheetRegistry` ELEMENT_BOM 定义里，`material_part_no` 旁新增一个名称列（不改它自身的只读子维度语义）：
- 新增 `ColumnDef.nameCol("material_recipe_name", "材质名")`（对齐现有 `operation_name`/`component_name` 的 `nameCol` 用法，第 48/206 行）。
- 因两跳无法走通用 MASTER map（map 假设单跳、且 codeCol 直接匹配），**不要**往 `MASTER` map 里加 `material_recipe`。改用 B2.2 的特判 join。
- 建议给 `material_part_no` 的列定义打一个标记（如新 role `SUBDIM_MASTER_2HOP` 或在 ColumnDef 加 `bridgeTable/bridgeKey/nameTable/nameCol` 字段），供 readRows 识别并生成两跳 join。选实现最干净的一种，避免散落 magic string。

### B2.2 readRows：生成两跳 LEFT JOIN
在 `PricingMaintenanceService.readRows`（约 `:198-204`）的 join 构造处，对材质列走特判，生成：
```sql
LEFT JOIN material_master  mpm ON mpm.material_no       = a.material_part_no
LEFT JOIN material_recipe  mpr ON mpr.id                = mpm.material_recipe_id
-- select:
mpr.name AS material_recipe_name
```
- 别名唯一（`mpm`/`mpr`），避免与现有 `mm`（第 55/65 行 material_master 别名）冲突。
- 该列 `IS NULL` 时前端显示「未绑定」（前端处理，后端返 null 即可）。
- **不进** `validateMasters`（B 项目：材质料号是料号、不是 material_recipe code，保存校验维持现状不动）。

### B2.3 lookup 不受影响
`material_part_no` 仍是只读子维度、无下拉，`lookup` 端点/`MASTER` map 不动。

---

## B3 · 核对元素/料号覆盖（不改代码，出结论）

**只核对、写进验收报告，不改代码**（除非发现键错配需最小修，需先报技术总监）：
- **元素**：确认核价导入的 `component_no`（P07 读「元素代码」）与 `element.element_code`（材质库导入 `syncElementMaster` 写的符号）**同域可 join**。
  - 抽 SQL：`SELECT DISTINCT eb.component_no FROM element_bom_item eb LEFT JOIN element e ON e.element_code = eb.component_no WHERE e.element_code IS NULL`（返空 = 全覆盖）。
- **料号**：确认 `来料料号`（`unit_price.finished_material_no` 域的 code）在 `material_master.material_no` 有覆盖，抽查带出 `material_name` 非 null。
- 缺口**显式记录**在 `test-report`（守记忆 `cpq-unverifiable-feature-masks-gap`：不得把缺数据当已实现）。

---

## 强制自检（完成前必跑，写进交付说明）

1. `touch` 一个 java 文件触发 Quarkus 热重启，等 5-7s。
2. 导入端点实测（多部件表单）：
   ```bash
   curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
     -F "file=@<工序模板>.xlsx" http://localhost:8081/api/cpq/v6/process-master/import   # 期望 200，返报告
   ```
3. 模板下载：`GET /api/cpq/v6/process-master/import/template` → 200 + xlsx。
4. 维护页 readRows 返回含 `material_recipe_name` 字段（取一个有材质料号的销售料号 + ELEMENT_BOM sheet 实测）。
5. 幂等：同一 xlsx 连导两次 → `SELECT count(*) FROM process_master` 两次一致、无重复 process_no。
6. 守 B 证明：`git diff --stat` 不含任何 `pricing/P*Handler.java`；导入核价数据后 `process_master`/`element`/`material_recipe` 行数不因**核价导入**增长。
7. 交付说明附一行「已自检」声明（TS/后端 endpoint/幂等/守B 各一句）。

## 影响文件清单（预估）

- 新增：`v6/service/ProcessMasterImportService.java`、`v6/dto/ProcessMasterImportReportDTO.java`
- 改：`v6/resource/ProcessMasterResource.java`（+import/+template）
- 改：`v6/maintenance/PricingSheetRegistry.java`（ELEMENT_BOM 加材质名列）
- 改：`v6/maintenance/PricingMaintenanceService.java`（readRows 两跳 join；可能加 ColumnDef 字段）
- 可能改：`v6/maintenance/ColumnDef.java`（新增两跳 join 描述字段/工厂方法）
- **不改**：`pricing/P*Handler.java`（守 B）、Flyway（唯一索引已存在）
