# 后端任务文档 · 主数据维护-核价基础数据维护（task-0712）

> 配套 `需求说明.md`（C1–C13）、`api.md`。执行前**必读** `docs/方案制定前必读.md`、`docs/RECORD.md`、tesk-0709《版本升级规则文档》。
> 角色：`cpq-backend`。技术栈：Quarkus 3.23 + Hibernate Panache + PostgreSQL 16。
> **贯穿纪律**：① 禁止 for 循环嵌套查库，一次查询内存装配；② registry 列定义**必须与 `VersionedV6Writer` 各 `P*Handler` 登记同源**；③ 复用 `VersionedV6Writer`，不重写升版算法。

---

## 0. 现状与复用点（先读代码，勿臆造）

| 复用件 | 位置 | 用途 |
|--------|------|------|
| `VersionedV6Writer` | `com.cpq.basicdata.v6.versioning.VersionedV6Writer` | 升版写入器：`writeVersionedGroup(s)`（单表）/ `writeVersionedMasterDetail(s)`（主从 BOM）。`ALLOWED_TABLES` 白名单。**保存直接复用**。 |
| `VersionedGroupSpec` | 同包 | 轴/值/触发列/版本列 spec 记录 |
| `P06–P23 Handler` | `com.cpq.basicdata.v6.pricing.*` | 各版本组的 groupKey/content **现有登记 = registry 真源** |
| `IncomingOtherMergeHandler` / `FinishedOtherMergeHandler` | 同包 | P16+P17 / P19+P20 合并组口径 |
| `PricingPriceType` | 同包 | price_type 枚举 |
| 表结构 | `material_master` / `process_master` / `element` / 16 版本化表 | 见 `api.md §8` |

> 版本列现状：`unit_price`=`version_no`、`capacity`/`production_energy`/`auxiliary_energy`/`tooling_cost`=`calc_version`、`labor_rate`=`version_no`、`material_bom`=`bom_version`、`element_bom`=`characteristic`。`is_current` 已由 V277 补全；`price_type`/`system_type`/`unit_price`（production_energy）、`calc_version`/`system_type`（tooling_cost）已由 V323/V325/V326 补全。

---

## B1 · DDL 迁移：加 `source` 列（C11）

**新迁移** `V3xx__pricing_maintenance_source_column.sql`（取当前最大 V 号 +1，勿手工 psql，靠 Quarkus dev `migrate-at-start` 跑）。

- 为下列版本化表加 `source VARCHAR(16) NOT NULL DEFAULT 'IMPORT'`：
  `unit_price, capacity, labor_rate, production_energy, auxiliary_energy, tooling_cost, material_bom, element_bom`
  （主从 BOM 的 `source` 落**主表**行即可；子表不加。）
- `unit_price` 为 QUOTE/PRICING 共用表，加列 default `IMPORT` 对报价侧无副作用（护栏：不改报价 handler）。
- 迁移只 `ADD COLUMN IF NOT EXISTS`，默认值安全、不清表。
- 自检：`SELECT version,success FROM flyway_schema_history WHERE version='3xx'` → `success=t`；touch 一个 java 文件触发重启。

---

## B2 · `PricingSheetRegistry`（元数据登记，核心）

新建 `com.cpq.basicdata.v6.maintenance.PricingSheetRegistry`（ApplicationScoped 或静态常量）。

**每个 sheetKey 登记一条 `PricingSheetDef`**，字段：
```
sheetKey, tabName, group, order, tableName, versionColumn, priceTypeConst(可空),
salesPartAnchor(code/finished_material_no/material_no), masterDetail(bool),
axisColumns[], contentColumns[], subDimCodeColumns[],
columns[]  // 面向前端的完整列定义（api.md §2 的 columns：name/label/type/role/editable/dropdown）
```

**强约束（同源）**：`axisColumns` / `contentColumns` **逐列对齐**对应 `P*Handler` 里传给 `VersionedGroupSpec` 的 `groupKeyColumns` / `contentColumns`。实现时**逐 handler 读源码抄列**，并在类注释标注「对应 PxxHandler 第 N 行」。核价侧无触发列（§5.4），不配 `versionTriggerColumns`。

**16 组来源对照**（精确列以 handler 为准）：
- FEE 8 组（unit_price）：`P13/P14/P15、IncomingOtherMergeHandler、P18、FinishedOtherMergeHandler、P22、P23`。轴 = `system_type, price_type, <锚>`；子维度/值参见《规则文档 §5.1 B 组》。
- MATERIAL_BOM：`P06MaterialBomHandler`（主从，`writeVersionedMasterDetail`）。
- ELEMENT_BOM：`P07ElementBomHandler`（主从）。
- CAPACITY：`P08CapacityHandler`（去触发列）。
- LABOR_RATE：`P08` 关联的 `labor_rate` 独立版本线。
- DEPRECIATION / ENERGY：`P09/P10` → `production_energy`，**registry 各带 `priceTypeConst`=DEPRECIATION/ENERGY 作固定过滤**。
- AUX_ENERGY：`P11AuxiliaryEnergyHandler`。
- TOOLING：`P12ToolingCostHandler`。

**列 `role` 判定**：出现在 `groupKeyColumns` 且非 `material_no/price_type/system_type` 锚 → AXIS（本模块 AXIS 恒锁）；`subDimCodeColumns`（工序/来料料号/元素）→ SUBDIM + dropdown(MASTER)；其余 content → VALUE；join 出的名称 → NAME(editable=false)。

**dropdown 归属**（§4.4.0 / C13）：
- MASTER：工序号→process、元素`component_no`(ELEMENT_BOM)→element、来料料号`code`(INCOMING_*)→material。
- ENUM：currency/unit/calc_type/production_type/component_usage_type/is_effective（options 从 handler 校验或 CHECK 约束取，如 capacity.production_type ∈ UNIT/BATCH/BATCH_FIXED）。
- FREE：cost_type(要素,P16/17/19/20)、tooling_no、material_bom.component_no；P22 cost_type = 固定二选一（电镀加工费/电镀材料费）用 ENUM。

---

## B3 · `PricingMaintenanceService`（读侧，零 N+1）

新建 service，方法对应 api.md §1/§3/§4/§5/§7：

1. **`listParts(keyword,page,size)`**（§1）：一条 SQL `UNION ALL` 聚合 8+ 张表的 `(material_no, sheetKey_tag, updated_at)`（仅 `is_current=true`）→ group by material_no 求 `configuredCount`（distinct sheetKey 数）+ `max(updated_at)` → join `material_master` 带品名/规格/尺寸 + keyword 过滤 + 分页。**一次查询**。
2. **`overview(materialNo)`**（§3）：类似 UNION ALL，按 sheetKey 聚出每组 `hasData/currentVersion/versionCount/lastUpdatedAt`，内存补齐 16 项（无数据 hasData=false）。
3. **`readRows(materialNo,sheetKey,version?)`**（§4）：按 registry 取表/锚/price_type 过滤当前或历史版；NAME 列**一次 join** 主表带出（IN 集合 join，禁止逐行查名）。主从 BOM 返子表 + masterInfo。
4. **`versions(materialNo,sheetKey)`**（§5）：distinct 版本 + is_current + source + updated_by→用户名 + updated_at。
5. **`lookup(masterType,keyword,limit)`**（§7）：process_master/element/material_master 单查询。

> 所有多表聚合优先用原生 SQL（`EntityManager.createNativeQuery`）一次取回，Java 内存装配 DTO。**严禁**在循环里查库。

---

## B4 · `PricingMaintenanceService.saveGroup(...)`（写侧，复用写入器）

对应 api.md §6，核心流程：

```
1. 解析 sheetKey → PricingSheetDef；校验 materialNo 存在。
2. 乐观锁：读当前 is_current 版本号 curV；
   - body.expectedCurrentVersion != curV（含"库有当前版但前端传 null"）→ 抛 409。
3. 护栏：
   - rows 空 → 422（至少留一行，整组下线不在本期）。
   - 剔除/忽略 body 中 AXIS 列；用服务端 materialNo + priceTypeConst + system_type=PRICING 注入轴。
   - MASTER 列值校验存在于主表（批量 IN 校验，一次查询）；ENUM 值合法性校验。
   - 每行 set source='MANUAL'、updated_by=当前用户（SecurityIdentity）。
4. 构造 VersionedGroupSpec（groupKey/content 取自 def，同源）→
   - 非主从：VersionedV6Writer.writeVersionedGroup(spec)
   - 主从（MATERIAL_BOM/ELEMENT_BOM）：writeVersionedMasterDetail(...)（主/子表列取自 def）
5. 写入器返回：内容未变→复用旧版本号（result=UNCHANGED）；变→新版本号（UPGRADED）；空组首存→2000（CREATED）。
   依据"写入前 curV 是否存在 + 返回版本号是否变化"判定 result 三态并回传。
```

**注意**
- 写入器内部已做 `pg_advisory_xact_lock` 同轴串行 + multiset 指纹比对 + is_current 翻转，**不要另写升版逻辑**。
- 事务：整个 saveGroup 一个事务（`@Transactional`），乐观锁校验与写入同事务，避免 TOCTOU。
- production_energy 折旧/能耗：轴含 `price_type`，两 sheetKey 互不干扰（V325 唯一索引已含 price_type，勿退回旧唯一索引）。
- 主从 BOM 的 `source` 落主表；子表整批比对沿用 handler 的 childContentColumns。

---

## B5 · `PricingBasicDataMaintenanceResource`（端点）

新建 `com.cpq.basicdata.v6.maintenance.PricingBasicDataMaintenanceResource`，`@Path("/api/cpq/pricing-basic-data")`：

- GET `/parts`、`/sheets`、`/parts/{materialNo}/overview`、`/parts/{materialNo}/sheets/{sheetKey}/rows`、`/parts/{materialNo}/sheets/{sheetKey}/versions`、`/lookup/{masterType}` → `@RolesAllowed({"SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
- PUT `/parts/{materialNo}/sheets/{sheetKey}/rows` → `@RolesAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`
- DTO 放 `.maintenance.dto` 子包；异常映射 400/404/409/422（复用现有 ExceptionMapper 或按现网风格）。

---

## B6 · 测试（质量保证规范）

1. **单测**：`PricingMaintenanceServiceTest`
   - 保存内容未变 → UNCHANGED、无新版本、行数不变。
   - 改一个 value → UPGRADED、版本号 +1、旧组 is_current=false、新组 true、source=MANUAL。
   - 空 tab 从零存 → CREATED=2000、is_current=true。
   - 乐观锁：expectedCurrentVersion 过期 → 409。
   - 护栏：rows 空 → 422；AXIS 篡改被忽略（materialNo 以 path 为准）。
   - 主从 BOM（P06/P07）：子表整批改 → 主表 bom_version +1、子表多版本保留。
   - production_energy：折旧升版不影响能耗版本，反之亦然。
2. **零 N+1 断言**：列表/overview 用大数据量（≥100 料号）验证 SQL 次数恒定（可用日志/计数）。
3. **回归**：跑一遍核价重导（tesk-0709 §7 six-check 口径），确认本任务未带坏导入升版。

---

## B7 · 自检清单（完成前必跑，附到交付说明）

```
1) mvn 编译通过；touch java 触发 Quarkus 重启，等 6-7s
2) V3xx source 迁移 success=t（flyway_schema_history）
3) curl 401/200 探活：
   curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/pricing-basic-data/parts   # 期望 401(未带 token) 或 200
4) 带 admin token 打通 6 个 GET + 1 个 PUT，PUT 返回三态正确
5) 单测全绿；核价重导回归 failedRows=0
```

**完成宣告须含一行"已自检"声明**（TS/编译 0 错、迁移 success=t、端点 401/200、单测绿、回归 0 失败）。
