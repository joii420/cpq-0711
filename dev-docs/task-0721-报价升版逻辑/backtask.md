# 报价数据版本升级 · 后端任务（backtask.md）

> 依据 `需求说明.md` + `api.md`。技术栈 Java 17 + Quarkus + Hibernate Panache + PostgreSQL。
> **硬约束**：禁 N+1；改 `SqlViewExecutor`/`VersionedV6Writer`/`CardSnapshotService`/`ComponentDriverService` 属协议级改动，须跑 E2E（见文末自检）。
> **关键复用点（已勘定，勿另造）**：
> - 升版写入：`com.cpq.basicdata.v6.versioning.VersionedV6Writer` + `VersionedGroupSpec`
> - 视图执行与改写接缝：`com.cpq.datasource.sqlview.SqlViewExecutor`（`applyVersionFilter` / `executeAllRows` 的 `SELECT * FROM (template) inner_q`）
> - 报价上下文 ThreadLocal：`SqlViewRuntimeContext`（**已含 `quotationId` + `quotationStatus`**）
> - 核价通过入口：`QuotationService.costingApprove(id, comment, userId)`
> - 闸门查询：`ExistingProductService.list`
> - 导入编排：`QuoteImportService` + 各 `Q*Handler`

---

## 里程碑与顺序

```
B0(POC 固化) → B1(迁移:pending 列) → B2(导入写 pending) → B3(视图改写+启动校验)
   → B4(锚点入快照) → B5(回填服务) → B6(预览+token) → B7(闸门) → B8(状态机) → B9(主档暂存)
```
B3 是**技术核心且风险最高**，须先于 B5。B0 已由技术总监完成，工程师照抄结论即可。

---

## B0 · POC 结论（已验证，直接采用）

> 技术总监 2026-07-21 在真库 + 项目真实视图模板上实测通过，工程师**不必重跑**，按结论实现。

1. **列映射**：pgjdbc `org.postgresql.PGResultSetMetaData#getBaseTableName(i)` / `getBaseColumnName(i)`（对 `SELECT * FROM (<template>) _p LIMIT 0` 的 metadata）能准确返回每输出列的基表.基列。
   - 中文别名 / 多表 JOIN / 子查询包裹 / CTE(WITH) → **准确追踪**。
   - `COALESCE(...)` / 表达式 / 常量 / `UNION ALL` → 返回**空** → 判为不可回写（**安全降级，绝不错写**）。
2. **锚点**：`unit_price` / `element_bom_item` / `material_bom_item` / `capacity` 主键均 `id uuid`。注入 `<别名>.id AS __v6_id` 在有别名/无别名/裸列名三形态均通过。
3. **表替换**（换表不换谓词）：把 FROM/JOIN 的白名单表 token 换成 `(SELECT <显式列清单，is_current 位替换为 (t.is_current OR t.pending_quotation_id=:pq)> FROM <表> t WHERE t.is_current OR t.pending_quotation_id=:pq) <原别名>`。用户原谓词 `is_current=true`/`asy.is_current` 一字不改，经替换后对 pending 行自动成立。替换后 pgjdbc 仍准确追到基表。
4. **遮蔽**：朴素 `is_current OR pending` 会行数翻倍（实测 3→8）。正确：pending 优先 + 屏蔽同组旧 current（实测→5 正确）。遮蔽依据用 **`pending_supersedes`**（见 B1/B5），不按轴（`unit_price` 被 10 Handler 用不同轴，无法统一取轴）。

---

## B1 · Flyway 迁移：pending 列 + 索引

**文件**：`db/migration/V<next>__quote_versioning_pending_columns.sql`（版本号取当前 max+1，**勿手工 psql，交 Quarkus 启动跑**）。

```sql
-- 7 张版本化表 + 占号表：加 pending 归属列
ALTER TABLE unit_price          ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_bom        ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_bom_item   ADD COLUMN pending_quotation_id uuid;
ALTER TABLE element_bom         ADD COLUMN pending_quotation_id uuid;
ALTER TABLE element_bom_item    ADD COLUMN pending_quotation_id uuid;
ALTER TABLE capacity            ADD COLUMN pending_quotation_id uuid;
ALTER TABLE plating_scheme      ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_customer_map ADD COLUMN pending_quotation_id uuid;

-- 遮蔽支撑：pending 行点名它取代的旧 current 行 id（数组，NULL/空=不取代任何行，即纯新增）
ALTER TABLE unit_price        ADD COLUMN pending_supersedes uuid[];
ALTER TABLE material_bom      ADD COLUMN pending_supersedes uuid[];
ALTER TABLE material_bom_item ADD COLUMN pending_supersedes uuid[];
ALTER TABLE element_bom       ADD COLUMN pending_supersedes uuid[];
ALTER TABLE element_bom_item  ADD COLUMN pending_supersedes uuid[];
ALTER TABLE capacity          ADD COLUMN pending_supersedes uuid[];
ALTER TABLE plating_scheme    ADD COLUMN pending_supersedes uuid[];

-- 物化期改写高频过滤，建部分索引
CREATE INDEX ix_unit_price_pending        ON unit_price(pending_quotation_id)        WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_material_bom_pending       ON material_bom(pending_quotation_id)       WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_material_bom_item_pending  ON material_bom_item(pending_quotation_id)  WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_element_bom_pending        ON element_bom(pending_quotation_id)        WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_element_bom_item_pending   ON element_bom_item(pending_quotation_id)   WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_capacity_pending          ON capacity(pending_quotation_id)          WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_plating_scheme_pending     ON plating_scheme(pending_quotation_id)     WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_mcm_pending                ON material_customer_map(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
```

- 存量全清重导（§6），不迁移历史行。若上线保留存量：存量 `pending_quotation_id` 默认 NULL 即「已生效」，无需额外迁移。
- Entity 同步加字段：`UnitPrice` / `MaterialBom(Item)` / `ElementBom(Item)` / `Capacity` / `PlatingScheme` / `MaterialCustomerMap`（`@Column(name="pending_quotation_id") public UUID pendingQuotationId;` + `uuid[]` 用 `@JdbcTypeCode(SqlTypes.ARRAY)` 或原生 SQL 处理）。
- **自检**：`SELECT version, success FROM flyway_schema_history WHERE version='<next>'` → `success=t`。

---

## B2 · 导入写 pending（延迟生效）

**目标**：报价侧导入的 7 张表新行落 `is_current=false` + `pending_quotation_id=本单`，**不翻旧组 is_current**；升版算法零改动。

**做法**（在 `VersionedV6Writer` 加 pending 模式，勿散落到各 Handler）：
1. `VersionedGroupSpec` 加可选字段 `UUID pendingQuotationId`（null=现状正式写入；非 null=pending 模式）。同理 `writeVersionedMasterDetail` 的 spec。
2. 写入器在 pending 模式下：
   - **比对基准仍是** `is_current=true AND pending_quotation_id IS NULL` 的正式组（POC 的表替换只影响渲染，写入器读的是真实正式组）。
   - 新组行：`is_current=false`、`pending_quotation_id=:pq`、`pending_supersedes = 被取代的正式 current 行 id 数组`（写入器本就要 load 当前组，把这些 id 记下）。
   - **不** flip 旧组 `is_current`（旧组保持 current，别人照常引用旧版本）。
   - 版本号仍按 `MAX(数字版本)+1` 生成（含 pending 与正式行一起取 max，避免重号）。
3. `QuoteImportService` 编排：把当前 `importRecordId` 对应的 `quotationId` 透传进 spec 的 `pendingQuotationId`。
   - ⚠️ 导入编排现按 sheet 独立事务（`REQUIRES_NEW`），pending 写入沿用即可；同报价单重导前先清旧 pending（见 B8 覆盖语义）。
4. **重导覆盖**：同 `pending_quotation_id` 再次导入前，先 `DELETE FROM <表> WHERE pending_quotation_id=:pq`（7 表 + mcm），再走 pending 写入。

**自检**：导入一张报价单后
```sql
SELECT is_current, count(*) FROM unit_price WHERE pending_quotation_id=:pq GROUP BY 1; -- 全 false
SELECT count(*) FROM unit_price WHERE is_current AND pending_quotation_id IS NULL;      -- 旧正式组不变
```

---

## B3 · 视图 pending 感知改写 + 启动校验（技术核心）

**位置**：`SqlViewExecutor`。现状 `executeAllRows` 拼 `SELECT * FROM (applyVersionFilter(sqlTemplate)) inner_q`；`execute` 类似。改写要在**模板执行前**对 `sqlTemplate` 做「表替换 + 锚点注入」，且**仅当** `SqlViewRuntimeContext.get()` 的 owner 是报价侧且 `quotationId != null` 且 `quotationStatus` 属可编辑态（DRAFT 等，非 frozen）时启用。

### B3.1 新建改写器 `QuotePendingRewriter`（纯函数 + DB 元数据）

输入：`sqlTemplate`、`quotationId`、`java.sql.Connection`（取元数据用）。输出：改写后 SQL + `Map<String,String> colToBase`（输出列 → `表.列`，供 B5 回填复用，可缓存）。

步骤：
1. **定位白名单基表 token**：白名单 `{unit_price, material_bom, material_bom_item, element_bom, element_bom_item, capacity, plating_scheme}`。用词法扫描找 `FROM <table> [alias]` / `JOIN <table> [alias]`（**避开**字符串字面量、`--`/`/* */` 注释、同名 CTE 定义）。非白名单表（`material_master`/`material_recipe`/`process_master`/`global_variable_value` 等维表）**不替换**。
2. **生成替换体**：对每个命中表 `T`（别名 `a`）：
   ```
   (SELECT <T 的全部列，其中 is_current 列替换为 (t.is_current OR t.pending_quotation_id = :pq) AS is_current>
    FROM T t
    WHERE t.is_current OR t.pending_quotation_id = :pq) a
   ```
   列清单从 `pg_attribute`（`attrelid=T::regclass AND attnum>0 AND NOT attisdropped ORDER BY attnum`）取，结果按表缓存（表结构稳定）。保留原别名 `a`（无别名时用表名作别名，见 zh_view POC-D）。
3. **遮蔽**：替换体的 WHERE 再加「pending 优先」——本行是本单 pending，或本行是正式 current 且**未被本单任一 pending 行 supersede**：
   ```sql
   WHERE t.pending_quotation_id = :pq
      OR (t.is_current AND t.pending_quotation_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM T p
                          WHERE p.pending_quotation_id = :pq
                            AND t.id = ANY(p.pending_supersedes)))
   ```
   （POC ShadowProbe 验证此形态正确，3→5 行。）
4. **锚点注入**：在最外层 `SELECT` 列表补 `<主位表别名>.id AS __v6_id`。主位表 = FROM 后第一个白名单表。若主位表非白名单（如 `cp_view` 主位 `material_customer_map`）→ **不注入锚点、不参与回填**（该页签只读展示）。
5. **绑定参数**：`:pq = quotationId`。SqlViewExecutor 的 named-param 机制沿用（`toNamedParams` 补 `pq`）。

### B3.2 挂接 SqlViewExecutor

- 在 `executeAllRows` / `execute` 拼 SQL 前，若启用 pending 模式，用 `QuotePendingRewriter` 产出的改写 SQL 替换原 `sqlTemplate`（`applyVersionFilter` 仍先跑，二者叠加：先版本宏、后 pending 替换；注意 `plating_scheme` 等无宏的直接跑 pending 替换）。
- **frozen / 非报价 owner**：不改写，走现状（保证核价侧 AC-17 零回归 + 已提交单冻结）。

### B3.3 启动期硬校验（fail-fast）

- `@Startup`（或 `StartupEvent` observer）：枚举所有报价侧 `component_sql_view`（`EXISTS`(QUOTATION 模板引用)），对每个跑「改写（用一个占位 `:pq=随机 uuid`）→ `LIMIT 0` 执行 → 校验：主位白名单表 → 输出含 `__v6_id` 且其 base=该表.id；每个可回写列 base∈白名单」。
- 任一失败 → 抛异常**阻断启动**，日志指名 `component/view/reason`。
- 结果快照存内存，`GET /admin/quote-backfill/view-validation` 返回（api.md §4）。

**自检**：故意把某视图主位表改成非白名单 → 启动应报错指名该视图。

---

## B4 · 锚点写入快照

**位置**：物化链路（`ConfigureSnapshotService` / `ComponentDriverService` 写 `snapshot_rows` 处）。

- B3 改写后 driverRow 会多出 `__v6_id` 列 → 确保它**原样写进** `snapshot_rows.driverRow`（不被 normalize/白名单过滤掉）。
- 树页签（递归 SQL 路径，`CostingTreeRenderService`/树任务的报价侧配置）同样要把行的 `__v6_id` 注入 driverRow（回填不分叉的前提）。与树任务工程师对齐注入点。
- **不可回写行**（计算列主位 / 非白名单主位）：`__v6_id=null`，回填自动跳过。

**自检**：物化后 `snapshot_rows` 里每个可回写页签行含非空 `__v6_id`。

---

## B5 · 回填服务（核价通过时，方案丙）

**新建** `QuoteBackfillService`，被 `QuotationService.costingApprove` 在事务内调用。

### B5.1 收集有效行集
- 读该报价单所有 line item 的 `quotation_line_component_data`（`snapshot_rows` + `row_data` + `deleted_row_keys` + 树墓碑 `deleted_tree_nodes`）。**一次 IN 查整单**（复用 `CardSnapshotService` 的 `compDataByLine` 预取模式，禁逐行）。
- 每页签 → 目标 V6 组：
  - 有 `__v6_id` 的行 = 改值候选（取用户最终值）。
  - `__manual=true` 无 `__v6_id` = 新增行。
  - 被 `deleted_row_keys`/`deleted_tree_nodes` 命中 = 墓碑（排除出有效集）。
  - spine 空行（无业务数据的骨架）= 排除。
- **DAG 去重**：按 V6 身份（`material_bom_item`=父 material_no+component_no+seq 等）去重，不按树 occurrence。

### B5.2 jsonb→V6 列映射
- 复用 B3 的 `colToBase`（输出列→表.列）。对每个可回写列，把 `snapshot_rows` 里该列的值映射到 V6 物理列。
- 计算列（`colToBase` 无归属）跳过。
- 新增行轴列合成：`system_type='QUOTE'`、`customer_no`=本单客户、`material_no`=该行料号、`characteristic`=页签类型映射（`材质元素→RECIPE`/`零件→ASSEMBLY`/`外购件→OUTSOURCED`，来自树任务 `tab_type`）、父链来自树 `__parentNo`。未暴露可空列 NULL 或从同组代表行继承。

### B5.3 交 VersionedV6Writer 升版
- 每组构造 `VersionedGroupSpec`（**正式模式**，`pendingQuotationId=null`）：`groupKeyColumns`=该组轴、`contentColumns`=该表既有内容列（**复用对应 Handler 的声明**，勿另立口径，防 AP-52 语义错配）、`newRows`=B5.1 有效行集映射结果。
- 调 `writeVersionedGroup` / `writeVersionedMasterDetail` → 升版、`is_current=true`、旧组 `is_current=false` 留存。
- 主子表（material_bom/element_bom）走 `writeVersionedMasterDetail` 保证主子同步（AC-14）。
- **清理**：`DELETE FROM <表> WHERE pending_quotation_id=:pq`（7 表；升版已产生正式行，pending 草稿使命完成）。

### B5.4 事务边界
- 整个回填 + 主档 upsert(B9) + 闸门 flip(B7) + 状态机(costingApprove 现有逻辑) **同一 `@Transactional`**。任一失败整体回滚（报价单保持 SUBMITTED，pending 保留，可重试）。
- ⚠️ 若 `VersionedV6Writer` 内部用 `pg_advisory_xact_lock`，回填批量组会串行加锁——注意锁顺序（按表名+轴排序）避免死锁。

**自检**：核价通过后
```sql
SELECT is_current,count(*) FROM unit_price WHERE code=:someCode GROUP BY 1;   -- 1 current + N 历史(false)
SELECT count(*) FROM unit_price WHERE pending_quotation_id=:pq;                -- 0(已清)
```

---

## B6 · 预览 + previewToken

**新建** `QuoteBackfillPreviewService`。
- `preview(quotationId)`：跑 B5.1+B5.2 的**只读** dry-run（不写库），产出 api.md §1.1 结构。
- `previewToken` = 对**规范化影响清单**（组+行+op+值，稳定排序后 JSON）算 SHA-256。
- `costingApprove` 入口先重算 token 比对，不一致抛 409（api.md §1.2）。
- **性能**：dry-run 与真回填共用同一收集逻辑，避免两套。整单一次聚合，无 N+1。

**自检**：预览后手动改一条 `snapshot_rows` → 提交应 409。

---

## B7 · 闸门（ExistingProductService）

- `ExistingProductService.list` 的 `where` 追加：`AND mcm.pending_quotation_id IS NULL`。
- `customer-part-candidates`（`CustomerPartCandidateService`）同源加同谓词。
- 单表谓词，**零 N+1**（AC-16）。

**自检**：一张未通过报价单的料号，另一张单的「从已有产品添加」查不到；核价通过后查得到。

---

## B8 · 状态机

| 场景 | 实现 |
|---|---|
| 驳回（`costingReject`）| 现状即保留 pending（回填只在 approve 触发）；无需改动，仅确认 pending 不被清 |
| 撤回（`withdraw`）已 APPROVED | 现有逻辑允许；**不加回滚**（明确不动 V6）。补注释说明 |
| 重新提交 / 重导 | B2 已含「同 pq 先 DELETE pending 再写」；确认 submit→重回 DRAFT→重导路径也走清理 |
| 报价单删除 | 删单服务加：`DELETE FROM <7表+mcm> WHERE pending_quotation_id=:qid` |

**自检**：删单后 `SELECT count(*) ... WHERE pending_quotation_id=:qid` 全 0。

---

## B9 · 主档暂存（material_master，方案甲）

- **暂存结构**：新建 `pending_material_master` 表（`quotation_id`, `material_no`, `field`, `value` 或整行 jsonb），导入时 Q18/Q02/Q04/Q13/MaterialBomMerge 对 `material_master` 的 upsert **改为写暂存**（报价侧路径；核价侧 PRICING 维持直接 upsert）。
  - 或轻量方案：暂存挂在报价单 metadata jsonb（`pending_master_updates`）。二选一，backtask 实现者按简洁优先，**须与树任务不冲突**。
- **通过时**（B5 同事务）：把本单暂存的主档变更 upsert 进 `material_master`（覆盖式，无历史）。
- **删单/重导**：清本单主档暂存。

**自检**：导入改了单重 → 通过前 `material_master.unit_weight` 不变；通过后变为新值。

---

## 修改后强制自检（本任务必跑）

1. **后端编译/重启**：`touch` 一个 java → 等 5-7s → `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 401。
2. **Flyway**：`SELECT version,success FROM flyway_schema_history WHERE version='<B1>'` → `success=t`。
3. **视图 DDL 无涉**（本任务不 DROP VIEW），但改了 `SqlViewExecutor` 属进程级缓存相关 → 重启 Quarkus 清 `CachedSqlCompiler`/`ImplicitJoinRewriter.tableColumnsCache`。
4. **协议级 E2E**（改了 `SqlViewExecutor`/`CardSnapshotService`/`ComponentDriverService`）：
   ```
   cd cpq-frontend
   npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
   ```
   期望 `passed` + `'加载中' final count = 0` + 全 Tab `'加载中'=0`。
   ⚠️ 干净 master 该 spec 恒 3 失败（夹具缺产品分类，见记忆 task0712）——判回归须与打主仓 A/B 同型对比，勿误归因。
5. **回填端到端**：导入→物化(pending 可见)→他单隔离→核价通过(升版+闸门)→新单引用一致（AC-8）全链路手验 + SQL 断言。
   - **含 `plating_scheme` 跨客户专项（AC-18）**：客户A 改电镀方案 S 未通过前，客户B 新建单引用同 `scheme_no` 拿旧版（`WHERE scheme_no=:s AND is_current AND pending_quotation_id IS NULL` 仍为旧版本、行数不翻倍）；A 通过后 B 下次引用拿新版。此表轴无 `customer_no`，pending 隔离全靠 `pending_quotation_id`，务必单独断言。
6. **核价侧零回归**（AC-17）：一张 PRICING 侧核价单渲染逐位与改动前对比。

> **完成宣告必须含「已自检」声明行**（TS/编译/Flyway/E2E/SQL 断言逐项 ✅），否则视为未完成。
