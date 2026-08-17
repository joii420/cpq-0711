# 报价单比对视图 · 后端任务文档（backtask.md）

> 面向：cpq-backend 工程师
> 关联：`api.md`（接口契约，唯一权威）、`需求说明.md §11`
> 技术栈：Java 17 + Quarkus 3.23.3 + Hibernate Panache + Flyway + PostgreSQL 16

---

## 0. 后端总目标与边界

**做什么**：
1. 新增「比对列配置」持久化（表 + 实体 + get/upsert 服务 + 2 个 config 端点）。
2. 新增 `meta` 端点：返回两侧「页签 → 可比对值」目录。
3. 新增 `data` 端点：返回逐销售料号、两侧、逐页签的取值矩阵。

**不做什么**：
- 不动旧 `buildComparison` / `GET /comparison` / `POST /comparison/export`（保留）。
- 不做导出（本期取消）。
- 不新增任何比对专用的取值 SQL / 公式路径 —— data 的所有数值必须**复用现有卡片取值服务**（防 AP-50 双源漂移，见 T3）。
- 不改组件/模板/字段类型（不触发 AP-44 联动协议）。

---

## 任务拆分（建议按序，T1→T2→T3→T4→T5）

### T1. Flyway 迁移 + 实体：`quotation_comparison_config`
- 迁移文件放 `cpq-backend/src/main/resources/db/migration/`，版本号取当前最大号 +1（**勿手工 psql，交 Quarkus 启动自动跑 Flyway**）。DDL 见 `api.md §7`。
- 实体 `QuotationComparisonConfig extends PanacheEntityBase`：`id(UUID)`、`quotationId(UUID)`、`bucket(String)`、`columns(String, @JdbcTypeCode(SqlTypes.JSON) jsonb)`、`createdAt/updatedAt(OffsetDateTime)` + `@PrePersist/@PreUpdate` 时间戳（参照 `Product.java` 写法）。
- **自检**：`SELECT version,success FROM flyway_schema_history WHERE version='NN'` → success=t。

### T2. 配置读写服务 + 端点（`config` GET/PUT）
- 新增 `ComparisonViewService`（或复用一个新 service 类），提供：
  - `getConfig(quotationId, bucket)` → 查 `(quotationId,bucket)`；无记录返回 `columns=null`（不自动种默认列，默认列由前端补齐，见 api.md §5.1）。
  - `upsertConfig(quotationId, bucket, columnsJson)` → 按唯一键 upsert，覆盖 `columns`。可做轻量结构校验（是数组、每项含 `id/kind/threshold`）；非法则 400。
- 新增 `ComparisonViewResource`（`@Path("/api/cpq/quotations")`）：
  - `GET /{id}/comparison-view/config?bucket=` → `ComparisonConfigDTO`
  - `PUT /{id}/comparison-view/config?bucket=` （body `{columns:[...]}`）→ 回显 `ComparisonConfigDTO`
  - `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
  - `bucket` 只接受 `SALES`/`FINANCE`，其余 400。
- **契约严格对齐 `api.md §3/§4/§5`**（字段名、null 语义、信封）。

### T3. `data` 端点：逐料号两侧取值矩阵（本任务技术核心）
- `GET /{id}/comparison-view/data[?frozen=]` → `ComparisonDataDTO`（结构见 `api.md §2`）。
- 计算三类值，**全部复用现有卡片取值服务，禁止另写取数**：
  - `productTotal`（产品卡片总计）：复用产品卡片总计 / 折扣基数取值口径（`LineDiscountService.discountBaseOf` 或 SUBTOTAL 组件求值路径）。
  - `tabs[componentId].subtotals[field]`（字段小计）+ `tabTotal`（页签合计）：复用 `ComponentDataEffectiveRows.compute` + `FormulaCalculator`（口径同前端 `computeTabSubtotalsByColumn`：逐 `is_subtotal` 列求和；`tabTotal`=各列之和）。
  - 组件元数据（金额列集/公式）从 `Component` 实体加载，保持与 `CardSnapshotService` / `ExcelViewService` 现有 Meta 构造点一致。
- **料号并集 + presence**：报价侧 line items 料号 ∪ 核价侧料号；`presence` = BOTH/QUOTE_ONLY/COSTING_ONLY；缺侧 `quote`/`costing` 置 `null`。
- **frozen 口径**：
  - `frozen=false`（编辑态）：读当前有效卡片值（与报价单/核价单 Tab 编辑态展示一致）。
  - `frozen=true`（详情/已提交核价单）：读冻结快照（与只读卡片展示一致）。
  - 两种口径都要与对应场景 Tab 展示的值**逐值一致**（这是验收硬指标，见 §验收 AC-3）。
- **性能**：一张单可能多 line item × 2 侧 × 多页签，注意批量加载组件/避免 N+1；可参考现有卡片渲染批量路径。**勿并行化 expand/公式求值层**（返回可变缓存对象引用，非线程安全 —— 见历史教训 `cpq-expand-layer-not-threadsafe`）。

### T4. DTO 定型
- `ComparisonMetaDTO` / `ComparisonDataDTO` / `ComparisonConfigDTO`（字段严格照 `api.md`）。
- meta 的 `metrics` 从两侧卡片结构快照的 `is_subtotal` 字段生成 + 末尾追加 `__TAB_TOTAL__`。

### T5. 测试
- `ComparisonViewResourceTest`（`@QuarkusTest`）：
  - config：PUT 后 GET 回显一致；不同 bucket 互不影响（**AC-1**：同一 quotationId 的 SALES/FINANCE 各存各的）；未保存过返回 `columns=null`。
  - meta：两侧页签数、每页签 metrics 含各 `is_subtotal` 字段 + 一条 `__TAB_TOTAL__`。
  - data：presence 正确（构造单边料号）；productTotal / tabTotal / subtotals 数值与直接调卡片服务的取值一致（**AC-3 单源一致**）。
- 用真实测试数据（`docs/table/核价测试数据/*.xlsx` 已导入的销售料号，如 3120018220）跑一遍真值核对。

---

## 验收标准（AC，技术总监据此核验）

- **AC-1｜桶隔离**：同一 quotationId，PUT `bucket=SALES` 与 `bucket=FINANCE` 各存一行，互不覆盖；GET 各取各。SQL 核验 `SELECT bucket,jsonb_array_length(columns) FROM quotation_comparison_config WHERE quotation_id=…`。
- **AC-2｜配置往返无损**：PUT 的 columns（含 threshold/sortOrder/labels/metric）GET 原样回显。
- **AC-3｜单源一致（关键）**：`data` 端点的 `productTotal`/`tabTotal`/`subtotals`，与同一料号在 报价单/核价单 Tab（对应 frozen 口径）展示的值**逐值相等**。抽 ≥3 个料号、含单边料号各 1，人工/SQL 核对。
- **AC-4｜presence 正确**：报价有核价无 → `presence=QUOTE_ONLY`、`costing=null`；反之亦然；两侧都有 → `BOTH`。
- **AC-5｜meta 目录正确**：metrics 覆盖两侧各页签所有 `is_subtotal` 字段 + `__TAB_TOTAL__`；`componentId` 与列配置引用键一致。
- **AC-6｜旧端点无回归**：`GET /comparison`、`POST /comparison/export` 行为不变（回归跑 `CostingComparisonResourceTest`）。

---

## 强制自检（完成前必跑，写进"已自检"声明）

1. `touch` 一个 java 文件触发 Quarkus 重启 → 等 5–7s。
2. `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/quotations/<某id>/comparison-view/meta` → 期望 200/401（非 500）。
3. Flyway：`SELECT version,success FROM flyway_schema_history WHERE version='NN'` → success=t。
4. 后端单测：**在 worktree 的 `cpq-backend/` 目录**跑 `./mvnw test -Dtest=ComparisonViewResourceTest`（勿 cd 主仓，见历史教训 `cpq-worktree-maven-test-tree`）。
5. 回归：`./mvnw test -Dtest=CostingComparisonResourceTest` 绿。

> ⚠️ 并发纪律：只 `git add` 本次明确改动文件，**严禁 `git add -A`**；共享 Flyway 历史不改名改号（见 `cpq-shared-flyway-history-churn`）。

---

## 交接给前端的契约（务必与 api.md 一致）
- data 一次返回**所有页签所有小计** → 前端新增/删列**不重拉 data**，纯客户端重映射。
- config 全量覆盖语义（非增量）。
- 默认列（PRODUCT_TOTAL）由前端补齐/携带，后端只存不解释。
