# task-0728 主数据维护版式优化 · 后端任务

> 上游：[`需求说明.md`](./需求说明.md) · 契约：[`api.md`](./api.md)
> 本任务后端改动**全部是加法式**：新参数可选、不传时行为逐字节不变；新端点独立。**无 Flyway、无表结构变更、无数据迁移。**

---

## 总览

| 任务 | 内容 | 主要文件 | 规模 |
|---|---|---|---|
| B1 | 料号核价列表加 `sortBy`/`sortOrder`/`configured` | `PricingMaintenanceService` + `PricingBasicDataMaintenanceResource` | S |
| B2 | 工序列表加 `sortBy`/`sortOrder`/`isOutsource`/`processCategory` | `ProcessMasterReadService` + `ProcessMasterResource` | S |
| B3 | 新增工序分类去重端点 | 同 B2 两文件 | XS |
| B4 | 新增核价基础数据 24 Sheet 模板下载端点 | `BasicDataImportV6Resource` + 新建 `PricingTemplateService`（或并入 `PricingImportService`） | M |

B1/B2/B3 互不依赖，可并行；B4 独立。

---

## 通用铁律（三条，违反即打回）

1. **`ORDER BY` 一律走白名单 `Map` 查表**，命中取映射值、未命中回退默认序。
   ❌ `"ORDER BY " + sortBy` ——**任何形式的前端原串拼接都不接受**，包括「先正则校验再拼」。
   ✅ `String col = SORT_WHITELIST.get(sortBy); if (col == null) return DEFAULT_ORDER_BY;`
   `sortOrder` 同样不拼原串：`"desc".equalsIgnoreCase(sortOrder) ? " DESC" : " ASC"`。

2. **过滤条件必须同时作用于 count 查询与分页查询**。两处不同步 → 「共 N 条」与实际行数对不上，是本任务最容易犯的错（B1 的 count 与 page 是两条独立 SQL）。

3. **不传新参数时，SQL 必须与改造前逐字相同**。写完对着 `git diff` 确认：`sortBy == null` 分支产出的 SQL 字符串没有多一个空格。

---

## B1 · 料号核价列表加排序与配置状态过滤

**文件**
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance/PricingMaintenanceService.java#listParts`（现约 `:42-88`）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance/PricingBasicDataMaintenanceResource.java#parts`

**现状**（读代码时对照）

```java
public PartListPage listParts(String keyword, int page, int size) {
    ...
    String countSql = "SELECT COUNT(*) FROM (" +
        "  SELECT a.mno FROM (SELECT mno, COUNT(DISTINCT sk) c, MAX(uat) u FROM (" + cfg +
        "  ) cfg WHERE mno IS NOT NULL GROUP BY mno) a" +
        "  LEFT JOIN material_master mm ON mm.material_no = a.mno" + kwClause + ") t";
    String pageSql = "SELECT a.mno, mm.material_name, mm.specification, mm.dimension, a.c, a.u FROM (...) a" +
        "  LEFT JOIN material_master mm ON mm.material_no = a.mno" + kwClause +
        " ORDER BY a.u DESC NULLS LAST, a.mno" +
        " LIMIT :lim OFFSET :off";
}
```

**要做的**

1. 方法签名扩为 `listParts(String keyword, int page, int size, String sortBy, String sortOrder, Boolean configured)`。
   > 若该 service 有其它调用方，保留原 6 参重载委托到新签名（传 null），避免波及。**先 `codegraph_callers` 确认调用方**。

2. 定义白名单常量（按 `api.md` A1）：

```java
private static final Map<String, String> PARTS_SORT_WHITELIST = Map.of(
    "materialName",    "mm.material_name",
    "materialNo",      "a.mno",
    "specification",   "mm.specification",
    "dimension",       "mm.dimension",
    "configuredCount", "a.c",
    "lastUpdatedAt",   "a.u"
);
private static final String PARTS_DEFAULT_ORDER = " ORDER BY a.u DESC NULLS LAST, a.mno";
```

3. 拼 `ORDER BY`：

```java
String orderBy = PARTS_DEFAULT_ORDER;
String col = sortBy == null ? null : PARTS_SORT_WHITELIST.get(sortBy);
if (col != null) {
    String dir = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
    orderBy = " ORDER BY " + col + " " + dir + " NULLS LAST, a.mno";  // a.mno 兜底稳定次序
}
```

4. `configured` 过滤 —— `a.c` 是聚合子查询的输出列，条件加在**外层** `WHERE`（与 `kwClause` 同层）：
   - `configured == TRUE` → `a.c >= :totalSheets`
   - `configured == FALSE` → `a.c < :totalSheets`
   - `configured == null` → 不加
   `:totalSheets` 绑 `registry.all().size()`（与 DTO 里 `it.totalSheets` 同源，务必用同一个值，别写死数字）。
   ⚠️ `kwClause` 当前形如 `" WHERE a.mno ILIKE :kw OR ..."` —— **注意它没有括号**，直接 `AND` 拼上去会因 `OR`/`AND` 优先级出错。重构为「收集 predicate 列表 → `String.join(" AND ", ...)`」，并给关键字条件**自己加一层括号**：`(a.mno ILIKE :kw OR COALESCE(mm.material_name,'') ILIKE :kw)`。
   > 这是本任务后端最容易踩的坑：现状 `kwClause` 是裸 `OR`，一旦叠加 `configured` 条件而不加括号，过滤结果会静默变多。

5. 两条 SQL（count + page）都要加同一份 predicate 与参数绑定。

6. Resource 层加三个 `@QueryParam`，直接透传（不做校验，非法值由 service 回退）：

```java
@QueryParam("sortBy") String sortBy,
@QueryParam("sortOrder") @DefaultValue("asc") String sortOrder,
@QueryParam("configured") Boolean configured
```

**测试（新建 `PricingMaintenanceServiceSortFilterTest`）**
- 不传新参数 → 结果与改造前一致（可对同一库跑两次断言首行相同）；
- `sortBy=materialNo&sortOrder=asc` 首行 `materialNo` ≤ 末行；`desc` 反之；
- **跨页断言**：`page=1` 末行与 `page=2` 首行满足排序关系（证明是全表排序）；
- `sortBy=不存在的值` → 与不传时结果相同；
- `configured=true` 的 `total` + `configured=false` 的 `total` == 不传时的 `total`；
- `configured=false&keyword=<某关键字>` 两条件同时生效（验括号问题）。

---

## B2 · 工序列表加排序与过滤

**文件**
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/ProcessMasterReadService.java#list`（现约 `:34`）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/ProcessMasterResource.java#list`（现约 `:56`）

**要做的**

1. `list(int page, int size, String keyword)` → `list(int page, int size, String keyword, String sortBy, String sortOrder, Boolean isOutsource, String processCategory)`；同样先查调用方决定是否留重载。

2. 白名单（按 `api.md` A2 八个字段）：`processNo/processName/processCategory/isOutsource/standardCurrency/standardUnit/defaultDefectRate/updatedAt` → 对应蛇形列名。

3. 排序细则：
   - 默认序：**先读现状代码确认**（`api.md` 假设为 `process_no ASC`）；若与假设不符，以现状为准，并把真实默认序回填到 `api.md` A2 与 `需求说明.md` §4.5 表格；
   - 指定排序时尾部追加 `, process_no`；
   - 一律 `NULLS LAST`。

4. 过滤：
   - `isOutsource != null` → `AND is_outsource = :isOutsource`（**不是 `IS NOT TRUE`**，NULL 行不归入任何一侧，见 `api.md`）；
   - `processCategory` 非空 → `AND process_category = :cat`（精确匹配，不 ILIKE）；
   - 同样两条 SQL（count/page）同步。
   > 若该 service 用的是 Panache 而非原生 SQL，则用 `PanacheQuery` 的参数化 `find(query, Sort, params)`，`Sort.by(col, dir)` 的 `col` 仍须来自白名单。

5. Resource 加四个 `@QueryParam` 透传。

**测试（扩现有工序测试类或新建）**
- 与 B1 同型：不传→不变、升降序、跨页断言、非法 sortBy 回退；
- `isOutsource=true`/`false` 的 total 之和 **≤** 不传时 total（差额 = `is_outsource IS NULL` 的行数），并显式断言 NULL 行两侧都不出现；
- `processCategory=制造` 精确命中，不误命中「制造二部」类子串。

---

## B3 · 工序分类去重端点（新增）

```java
@GET
@Path("/categories")
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public ApiResponse<List<String>> categories() {
    return ApiResponse.success(service.listCategories());
}
```

Service：

```sql
SELECT DISTINCT process_category FROM process_master
WHERE process_category IS NOT NULL AND process_category <> ''
ORDER BY 1
```

空表返 `[]`（不是 null）。

> ⚠️ 路径 `/categories` 不能与 `@Path("/{id}")` 冲突 —— 现有 `PUT/DELETE /{id}` 是 `UUID` 类型参数，`GET /categories` 是 GET 且现无 `GET /{id}`，正常不冲突；实现后**用 curl 实测一次** `GET /v6/process-master/categories` 确认没被误路由。

---

## B4 · 核价基础数据 24 Sheet 模板下载（新增）

**文件**
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java`（加端点）
- 生成逻辑放 `com.cpq.basicdata.v6.pricing` 下，新建 `PricingTemplateService` 或并入 `PricingImportService`

**端点**（契约见 `api.md` A4）

```java
@GET
@Path("/pricing/template")
@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public Response pricingTemplate() {
    byte[] xlsx = pricingTemplateService.generateTemplate();
    return Response.ok(xlsx)
        .header("Content-Disposition", "attachment; filename=\"pricing_basic_data_template.xlsx\"")
        .build();
}
```

**生成规则（核心约束）**

1. **Sheet 名必须遍历 handler 注册表取**，禁止手写常量数组：

```java
for (PricingSheetHandler h : handlers) {          // 与 PricingImportService 用同一份 handler 集合
    Sheet sheet = wb.createSheet(h.sheetName());
    // 第 1 行写表头
}
```
   `PricingImportService` 现在就是用 `wb.getSheet(h.sheetName())` 匹配的（约 `:129`）——模板生成必须复用**同一个 handler 集合、同一个 `sheetName()`**，否则改名时静默失配。

2. **表头列名**：24 个 handler（`P01ElementPricingPriceHandler` … `P24UnitWeightHandler`）目前各自持有中文列名常量用于解析。做法二选一，**优先 (a)**：
   - **(a)** 在 handler 接口加 `default List<String> templateHeaders() { return List.of(); }`，逐个 handler 覆写返回其解析用的中文列名（顺序＝Excel 中的自然列序）。返回空列表的 handler，其 sheet 只建空 sheet 不写表头（并 `Log.warn` 提示待补）。
   - **(b)** 若 handler 已有可复用的列名声明（如某个 `ColumnDef` / 常量数组），直接读它，不新增接口方法。
   **实现前先读 2~3 个 handler 判断走哪条**，并在 PR 说明里写清选了哪个及原因。

3. Sheet 顺序 = handler 注册顺序（P01 → P24）；数据行为空；表头建议加粗 + 冻结首行（可选，不强制）。

4. 参考实现：`ProcessMasterImportService#generateTemplate`（约 `:211`）、`MaterialRecipeImportService#generateTemplate`。POI 用法照抄。

**测试（新建 `PricingTemplateServiceTest`）—— 这是 B4 的验收关键**

```
① 生成的 workbook sheet 数 == handler 数（24）
② 逐个断言 wb.getSheetAt(i).getSheetName().equals(handlers.get(i).sheetName())
③ 【闭环测试】把生成的字节流直接喂给 PricingImportService 的导入入口，
   断言不出现「缺少 Sheet / sheet 不存在」类错误，各 sheet 均按 0 数据行正常返回
```
③ 是本任务唯一能证明「模板真能用」的测试，**不允许省略**。

---

## 交付自检（CLAUDE.md 后端口径）

- [ ] `cd cpq-backend && ./mvnw test` —— 相关模块全绿，附通过数
- [ ] `touch` 一个 java 文件强制 Quarkus 重启，等 5-7s
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 401（应用在跑、鉴权正常）
- [ ] 四个变更点各 curl 一次实测（带 cookie / 或看 401 而非 500）：
  - `GET /pricing-basic-data/parts?sortBy=materialNo&sortOrder=desc&configured=false`
  - `GET /v6/process-master?sortBy=processName&sortOrder=desc&isOutsource=true`
  - `GET /v6/process-master/categories`
  - `GET /basic-data-import/v6/pricing/template` → 200 + `Content-Disposition` 头 + 响应体是 xlsx 魔数 `PK`
- [ ] **无 Flyway 迁移文件被新增**（本任务不该有）
- [ ] `git diff` 复核：不传新参数时 SQL 字符串与改造前逐字相同

> 无数据库 DDL，故不涉及「视图重建后必须重启」那条规则；但改完 java 仍要按上面 touch 重启一次再 curl。
