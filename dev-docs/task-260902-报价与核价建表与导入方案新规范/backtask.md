# 后端任务分解 · 报价与核价建表与导入方案新规范

> 后端**只按本文件做**。接口契约见 [`api.md`](./api.md)，建表逐列依据见 [`字段矩阵.md`](./字段矩阵.md)，AC 原文见 [`需求文档.md`](./需求文档.md) —— **本文件只标 AC 编号，不复制原文**。
> 🚨 **闸门 A0 裁决 D-13：新建独立包 `com.cpq.dataset.*`，现有 `PricingSheetRegistry` / `Q01~Q19` / `P01~P24` / `PricingBasicDataMaintenanceResource` 一行都不许改。**
> 必读：`docs/rules/backend.md`（N+1 硬指标 · DDL 后必须重启 · 迁移纪律）、`docs/方案制定前必读.md`、`docs/rules/change-protocol.md`

## 包结构（建议）

```
com.cpq.dataset/
  ├ registry/    DatasetRegistry(接口) · QuoteRegistry · CostBasicRegistry · CostDetailRegistry · SheetDef · ColumnDef
  ├ fingerprint/ RowFingerprintCalculator · ValueNormalizer
  ├ versioning/  VersionedGroupWriter(升版判定 + 归档 + 写入)
  ├ importer/    DatasetImportService(两阶段) · DatasetImportValidator · SheetParser
  ├ service/     DatasetMaintenanceService(维护端读写)
  ├ resource/    DatasetResource(8 个端点)
  └ dto/
```

---

## B-1 · 45 张主表的 Flyway 迁移

| 服务的 AC | AC-1, AC-2, AC-3, AC-4, AC-47 |
|---|---|

- 逐表逐列照 `字段矩阵.md` 建，**标 ❌ 不建的列一律不建**（白底 = 主数据 JOIN 展示列）。
- 统一系统列：`id bigserial pk` / `source varchar(16) not null default 'IMPORT'` / `created_at timestamptz not null default now()` / `created_by varchar(64)` / `updated_at timestamptz` / `updated_by varchar(64)`。
- 带版本表（39 张）追加 `version_no integer not null` + `row_fingerprint char(64) not null`。
- 免版本表（6 张）**不得**有这两列，并按 `需求文档.md` R-2 的主键建唯一约束。
- 🚩 **D-18 变更（2026-09-03 开工后）**：`ds_quote_customer_part` **加一列 `customer_no varchar(20) not null`**，
  唯一约束 = **`(customer_no, customer_product_no)`**（不是原写的「客户产品编号 + 销售料号」）。
  Excel 尚未补该列 —— 字段矩阵中该行已由主线手工补入并标 🚩，**照它建**。服务 **AC-47**。
- 索引：每张带版本表建 `(轴列, version_no)` 复合索引；免版本表按主键建唯一索引。
- ⚠️ 迁移版本号从**建分支当时**主仓最大号顺延（当前 `V400`，共享库上是移动靶，见 `RECORD.md` 并发教训）。**不许改名、改号已应用的迁移。**
- ⚠️ 单文件迁移会很长（45 张表）。允许拆成 `V4xx__ds_quote_tables.sql` / `V4xx+1__ds_cost_basic_tables.sql` / `V4xx+2__ds_cost_detail_tables.sql` 三份。

## B-2 · 39 张 `_history` 表的 Flyway 迁移

| 服务的 AC | AC-1, AC-5 |
|---|---|

- 结构 = 主表全部列（主表 `id` → `origin_id bigint`，`_history` 自己有新 `id bigserial pk`）+ `archived_at timestamptz not null default now()` / `archived_by varchar(64)` / `archive_reason varchar(32)`。
- 索引：`(轴列, version_no)`。
- **无外键**（主表行会被删除，外键会挡住归档）。

## B-3 · 三套 Registry（Java 元数据）

| 服务的 AC | AC-2, AC-26 |
|---|---|

- `DatasetRegistry` 接口：`datasetKey()` / `axisColumn()` / `sheets()`；三个 `@ApplicationScoped` 实现。
- 每个 `SheetDef`：`sheetKey`（大写下划线，如 `MATERIAL_BOM`）/ `sheetName`（**必须与 Excel sheet 名逐字相等**，导入靠它匹配）/ `tableName` / `versioned` / `sortOrder` / `columns`。
- 每个 `ColumnDef`：`name`（DB 列名）/ `label`（Excel 中文列名，**逐字相等**）/ `role`（`AXIS`|`SUBDIM`|`VALUE`|`NAME`）/ `type` / `required` / `compared` / `dropdown` / `scale`。
- `NAME` 角色的列**不建 DB 字段**，只声明 `sourceTable` + `sourceCodeColumn` 供后端 JOIN 带出。
- 🚨 **Registry 与 B-1 的 DDL 必须同源**：写一个启动期自检（或单测），逐表比对 Registry 声明的列 与 `information_schema.columns`，不一致直接启动失败。
  > 理由：现有 `PricingSheetRegistry` 的类注释已自陈「改 handler 必须同步改这里，否则虚假升版 / 匹配错组」—— 这是**双写漂移**，本次用自检硬拦。

## B-4 · 行指纹计算器

| 服务的 AC | AC-16, AC-17, AC-18 |
|---|---|

- 按 `需求文档.md` R-3 实现：只取 `compared=true` 的列，按 **Registry 声明顺序**（= 建表字段顺序），用 `0x1F` 连接后 SHA-256 → 64 位小写 hex。
- `ValueNormalizer`：NULL / 空串 / 纯空白三者同值为空；数值走 `BigDecimal.stripTrailingZeros().toPlainString()`；文本 `trim()` 保留大小写；布尔 `true`/`false`。
- ⚠️ 小数口径**必须复用** `PrecisionPolicy.java`，不许自造（见 `RECORD.md`「小数口径三层」）。
- 单测必须含：`1` / `1.0` / `1.00` / `1.000000` 四者指纹相等；`"a"` 与 `"A"` 指纹不等；`null` 与 `""` 与 `"  "` 三者相等。

## B-5 · 通用版本化写入器 `VersionedGroupWriter`

| 服务的 AC | AC-12, AC-13, AC-14, AC-15, AC-16, AC-17, AC-19, AC-20, AC-37 |
|---|---|

- 入参：`SheetDef` + 轴值 + 目标行集合（来自导入或维护端保存）。出参：`UNCHANGED` / `UPGRADED` / `CREATED` + 新版本号。
- 判定按 R-4：先比行数，再比**指纹多重集**（`Map<fingerprint, count>` 相等，不看顺序）。
- `UPGRADED` 的三步（同一事务）：归档旧行进 `_history` → 删主表旧行 → 以 `max(历史最大版本号, 当前版本号) + 1` 插入新行。
  > ⚠️ 版本号取 `max` 而非「当前 + 1」：`_history` 里可能已有更大的号（同 `RECORD.md`「BOM 主子表版本失步致导入撞 uq」的教训）。
- `UNCHANGED` **必须一行不写**（不许「反正值一样」就 UPDATE 一遍 —— 那会污染 `updated_at`，且 AC-13 会红）。
- 🚫 **不许**对未出现在本次入参里的轴值做任何操作（R-6 增量语义，AC-20）。

## B-6 · 导入 Phase 1：解析 + 全量校验（零写库）

| 服务的 AC | AC-6, AC-7, AC-8, AC-9, AC-10, AC-34, AC-39, AC-40, AC-45, AC-46 |
|---|---|

- 校验项按 `需求文档.md` R-7；`reason` 取值必须落在 `api.md §1` 的封闭集内。
- 🚨 **必须收集全部错误后一次返回**，不许 fail-fast（AC-10）。
- 🚨 **本阶段绝对零写库** —— 不许「先建物料再校验 BOM」。
  > 这是 `RECORD.md` task0709 的成熟模式（Phase1 零写库 → Phase2 单事务），也是 AC-6/AC-10 「导入前后 `count(*)` 逐值相等」能成立的唯一实现方式。
- sheet 匹配：Excel sheet 名 ∉ 本数据集 Registry → 报 `sheet「X」不属于{数据集中文名}数据集`（AC-34）。表头列名与 `ColumnDef.label` 不一致 → 报缺列。
- 空 sheet（只有表头）合法，产出 `轴值数 0`，**不得**视为清空（AC-39）。
- 长度校验按 DDL 的 `varchar(n)`，超长必须报错，**禁止静默截断**（AC-40）。
- 主数据存在性：`element` / `process` / `material_recipe` / `customer` **只读**查询。
- 🚩 **D-19 变更**：报价 `客户料号` sheet 的 **`customer_no` 必须存在于 `customer.code`，查不到整份拒收**，
  `reason` 用 `客户编号未在客户档案中登记`（`api.md §1` 已加入封闭集）。服务 **AC-45 / AC-46**。
  ⚠️ 原写的「客户产品编号 ∈ customer」是笔误，已在 `需求文档.md` R-7 改正 —— **校验的是客户编号，不是客户产品编号**。
  ⚠️ 必须**批量预取**（一次 `IN (...)` 查全部待校验编码），不许逐行查（B-12 / AC-44）。

## B-7 · 导入 Phase 2：单事务写入

| 服务的 AC | AC-11, AC-21, AC-22, AC-23 |
|---|---|

- **整份一个事务**。任一异常整体回滚。
- 免版本表：按 R-2 主键 `INSERT … ON CONFLICT (主键) DO UPDATE`（AC-21/22/23）。
- 带版本表：按轴值分组后逐组调 B-5。
- 汇总结构见 `api.md §1`。
- ⚠️ **事务超时**：现有 JTA 默认 60s，2000 行的 AC-44 场景可能触顶。若需调整，按 `RECORD.md` repair-0727 的两段式事务经验处理，**并在 `test-report.md` 写明实测耗时**。

## B-8 · 导入端点 `POST /dataset/{dataset}/import`

| 服务的 AC | AC-11, AC-33, AC-36 |
|---|---|

- 三个 `{dataset}` 值共用同一实现，靠 Registry 分流；非法值 404。
- 登记进现有「导入历史」（若现有 `import_*` 表结构允许追加来源类型，则追加；否则本期不登记，**在 `test-report.md` 写明结论与理由**）。

## B-9 · 维护读端点（5 个）

| 服务的 AC | AC-25, AC-26, AC-29, AC-32 |
|---|---|

- `GET sheets` / `parts` / `overview` / `rows` / `versions` / `lookup`，契约见 `api.md §2~§6, §8`。
- `rows` 带 `version` 参数时读 `_history`，并置 `isLatest=false` / `readOnly=true`（AC-29）。
- `NAME` 角色列由后端 JOIN 主数据带出（`material` / `process` / `element` / `recipe`），**批量 JOIN，不许逐行查**。
- 无数据的 sheet 返回 `rows: []` + `versionNo: null`（AC-32），**不许抛 404**。
- `lookup` 可复用现有实现的查询逻辑，但**必须新开路径**（`api.md §8`）。

## B-10 · 保存端点 `PUT …/rows`

| 服务的 AC | AC-27, AC-28, AC-30, AC-41 |
|---|---|

- 入参 `rows` 是**整组全量**；删行 = 不出现在数组里。
- 先校验（同 B-6 的规则子集，`row` = 数组下标 + 1），再调 B-5。
- **乐观锁**：`baseVersion ≠ 库中当前版本` → 409 + `currentVersion`（AC-41）。校验必须在**同一事务内**读当前版本，避免检查-使用竞态。
- `source` 写 `MANUAL`，`archive_reason` 写 `MANUAL_UPGRADE`。

## B-11 · 权限

| 服务的 AC | AC-31 |
|---|---|

- 写端点（`POST import`、`PUT rows`）`@RolesAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`；读端点放开给 `SALES`。
- ⚠️ **测试 profile 的 RBAC 开关自相矛盾**（`INDEX.md` 已登记的既有环境缺陷）：`@QuarkusTest` 里不带 session 的请求恒 401。
  本任务**不修该缺陷**，用例里**自带 session** 规避，并在 `test-report.md` 点名。

## B-12 · 批量化（N+1 硬指标）

| 服务的 AC | AC-44 |
|---|---|

- 🚫 `backend.md` 硬指标：**单个业务操作的 SQL 条数必须是常数，与料号数 N 无关。循环体里出现查询 = 违规。**
- 具体要求：主数据存在性校验批量预取；`_history` 归档用 `INSERT … SELECT` 一条；主表删除按轴值批量；新行 `INSERT` 合批。
- 验证方式：开 `hibernate.show-sql`，导入 200 料号 vs 20 料号，**SQL 条数不得随料号数线性增长**。
  ⚠️ 这条要用 A/B 两组实测数字进 `test-report.md`，不许只写「已批量化」。

## B-13 · 回归保障（零改动证明）

| 服务的 AC | AC-42, AC-43 |
|---|---|

- 交付前必须给出证据：`git diff --stat` 显示 `PricingSheetRegistry.java` / `PricingBasicDataMaintenanceResource.java` / `com.cpq.basicdata.v6.quote.*` / `com.cpq.basicdata.v6.pricing.*` **改动行数为 0**。
- 跑现有后端测试全绿（含 `PricingSheetRegistry` 相关用例）。
- ⚠️ 若为了复用而**必须**动到现有类（例如抽取一个公共工具方法），**停下来报主线**，不许自行决定 —— 那会推翻闸门 A0 的 D-13。

---

## 🚨 红线（`CLAUDE.md` §3.2，不受任何授权豁免）

- 本任务**只新增表，不删不改任何既有表**。若发现必须 `DROP` / `ALTER` 既有表，**停下报告**，等用户批准。
- `mvnw test` 走的 `test` profile **实连共享开发库 `cpq_db_0724`**（`CLAUDE.md` profile 表已实证更正）。
  🚫 **不许写任何清库 / `TRUNCATE` / 无 `WHERE` `DELETE` 的测试**。夹具用 `TEST-DS-` 前缀，用完按前缀精确清理。
