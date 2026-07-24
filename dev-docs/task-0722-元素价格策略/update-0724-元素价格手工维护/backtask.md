# 后端任务文档 · 元素价格手工维护（update-0724）

> 隶属：`dev-docs/task-0722-元素价格策略` · update-0724
> 契约以同目录 `api.md` 为准；需求裁决以 `需求文档.md` §11（U0~U12）为准。
> 开发在独立 worktree 分支进行。技术总监负责验收与合并，不代写代码。

---

## B0 · 开工前必读（5 分钟，能省掉一整轮返工）

### 已核实的现状（技术总监 2026-07-23 亲验，可直接采信，不必重查）

| # | 事实 | 核实方式 |
|---|---|---|
| 1 | `element_daily_price` **没有 Panache 实体**，全库访问一律走 native SQL（`PriceTableService` / `PriceImportRowWriter`） | 全工程 `@Table(name="element_daily_price")` 0 命中 |
| 2 | 唯一键 `uq_element_daily = (element_name, COALESCE(source_id::text,''), price_date)` | `\d element_daily_price` |
| 3 | `fetch_status` CHECK 值域 = `SUCCESS`/`FAILED`/`MANUAL`/`IMPORT`，默认值 `'MANUAL'` | 同上 |
| 4 | 表上 `source_id` / `raw_price` / `currency` / `price_unit` **全部 nullable** | 同上 |
| 5 | `f_customer_element_price` 用 `dp.source_id = w.source_id` 严格匹配，**不过滤 `fetch_status`** | `pg_get_functiondef` 全文 |
| 6 | `element_price_strategy_log` 只有 `snapshot` 列，**无 `changes` 列** | `\d element_price_strategy_log` |
| 7 | 当前库内最大 Flyway 版本 = **`V358`** | `flyway_schema_history` |
| 8 | `PriceTableResource` 类级 `@RoleAllowed({"SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`，与 `PriceImportResource` 逐字一致 | 两文件源码 |
| 9 | `PriceTableResource` 的端点**直接返回 DTO，不包 `ApiResponse`** | 源码 |

### 三条不得违反的纪律

1. **不得用 `INSERT ... ON CONFLICT DO UPDATE`。** 那是导入侧（`PriceImportRowWriter:88`）的覆盖语义。手工新建撞键必须返 `409` 拒绝（U0 ②、api.md §1）。
2. **价格写入与留痕必须同一事务。** 需求 §4.3 规则 5 + 验收 11 专项验证。task-0722 §11.22 已在策略侧踩过「只落其一」的坑。
3. **Flyway 版本号实施时现取，不得预先写死。** 共享库 `cpq_db` 有 13 个活跃 worktree 并发，版本号是移动靶（历史教训见 `docs/RECORD.md` 及记忆 `cpq-shared-flyway-history-churn`）。写迁移文件前先跑：
   ```bash
   PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db \
     -tAc "SELECT MAX(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';"
   ```
   取结果 +1。**已应用的迁移禁止改名改号。**

---

## B1 · Flyway 迁移：建变更历史表

**文件**：`cpq-backend/src/main/resources/db/migration/V<NNN>__element_daily_price_log.sql`（`NNN` 按 B0 纪律 3 现取）

```sql
CREATE TABLE element_daily_price_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    price_id        UUID,                              -- 指向 element_daily_price.id，不建 FK
    element_name    VARCHAR(64)  NOT NULL,
    source_id       UUID,
    price_date      DATE         NOT NULL,
    action          VARCHAR(16)  NOT NULL,
    snapshot        JSONB        NOT NULL,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by      UUID,
    changed_by_name VARCHAR(100),
    CONSTRAINT chk_edpl_action CHECK (action IN ('CREATE','UPDATE','DELETE'))
);

CREATE INDEX idx_edpl_target ON element_daily_price_log
    (element_name, COALESCE(source_id::text, ''), price_date, changed_at DESC);
CREATE INDEX idx_edpl_time   ON element_daily_price_log (changed_at DESC);

COMMENT ON TABLE element_daily_price_log IS
  '元素价格变更历史（update-0724）。price_id 不建 FK：DELETE 后原行已不存在。
   键三元组冗余存储，保证删除后仍可追溯。changes 不入库，查询时比对相邻 snapshot 算出。';
```

**要点**
- `price_id` **不建 FK**（U5、需求 §4.4）。建了会阻止 `DELETE`，或级联删掉历史——两者都违背留痕目的。
- 键三元组 `element_name` / `source_id` / `price_date` **冗余存储**，这是删除后仍能追溯的唯一依据。
- 索引 `idx_edpl_target` 的第二列用 `COALESCE(source_id::text,'')`，与 `uq_element_daily` 的表达式**保持一致**，便于按"价格身份"分组做 diff。
- 表形状逐字比照 `element_price_strategy_log`（B0 事实 6），**不加 `changes` 列**。

**验收**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='<NNN>';"   # success=t
```

> ⚠️ **不要手工 `psql -f` 跑这个 SQL**。让 Quarkus dev 的 `migrate-at-start` 自己执行（`touch` 一个 java 文件触发重启）。手工跑会导致 checksum 对不上而启动失败（CLAUDE.md「修改后强制自检」）。

---

## B2 · `ElementDailyPriceLog` 实体

**新建**：`cpq-backend/src/main/java/com/cpq/elementprice/pricetable/ElementDailyPriceLog.java`

Panache 实体，形状逐字比照 `com/cpq/elementprice/strategy/ElementPriceStrategyLog.java`（直接打开对照着写，字段类型/注解风格保持一致）：

```java
@Entity
@Table(name = "element_daily_price_log")
public class ElementDailyPriceLog extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "price_id")        public UUID priceId;
    @Column(name = "element_name")    public String elementName;
    @Column(name = "source_id")       public UUID sourceId;
    @Column(name = "price_date")      public LocalDate priceDate;
    public String action;
    @Column(columnDefinition = "jsonb") public String snapshot;   // 与策略侧一致：存 String，服务层用 ObjectMapper 序列化
    @Column(name = "changed_at")      public OffsetDateTime changedAt;
    @Column(name = "changed_by")      public UUID changedBy;
    @Column(name = "changed_by_name") public String changedByName;
}
```

> `snapshot` 的 Java 侧类型**照抄策略侧实体的写法**（String + 服务层 `ObjectMapper`，或已有的 jsonb 映射方式），不要自创。两边同构是本期的明确目标（U5）。

---

## B3 · 补齐 `ElementPriceRowDTO` 契约缺口 ⚠️ 优先做，前端强依赖

**这是需求文档未覆盖的缺口，技术总监审阅时发现。**

### 问题

`ElementPriceRowDTO` 当前 11 个字段里**既没有 `id` 也没有 `fetchStatus`**（已核实源码 + `PriceTableService.listDetail:61-62` 的 SELECT 列表）。后果：

- 无 `id` → 前端勾选行后拿不到主键，`PUT`/`DELETE` 根本无从调用
- 无 `fetchStatus` → 验收 1/3「列表出现该行 `fetch_status='MANUAL'`」在 UI 上无法验证

### 改动

**1) `pricetable/ElementPriceRowDTO.java` 加两个字段**

```java
public UUID id;            // element_daily_price.id
public String fetchStatus; // SUCCESS / FAILED / MANUAL / IMPORT
```

**2) `PriceTableService.listDetail` 的 SELECT 补两列**（`:61-62` 附近）

```java
"SELECT edp.id, edp.element_name, e.element_name, edp.price_date, edp.source_id, s.source_name, s.status, " +
"       edp.raw_price, edp.currency, edp.price_unit, edp.fetch_status, u.full_name, edp.updated_at" +
```

⚠️ **该方法用的是 native query + 按下标取值的 `Object[]` 映射**。加列会让**后续所有列的下标整体位移**——必须逐个核对映射代码，漏改一处就是全列错位（且不报编译错）。建议把新列**加在末尾**以最小化位移，或加完后逐字段打印一行验证。

**3) 导出复用同一路径**

`exportDetail` 内部调 `listDetail`（`:181`），无需单独改；但要确认 Excel 列头是否需要同步加"数据来源"列 —— **本期不加**（需求未要求，属加法项）。

### 验收

```bash
curl -s --noproxy '*' -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/cpq/element-price/prices?page=0&size=1" | head -c 500
# 期望 content[0] 含非空 id 与 fetchStatus
```

---

## B4 · 价格写服务（新建 / 修改 / 删除）

**新建**：`pricetable/PriceMaintenanceService.java`
（也可并入 `PriceTableService`；**建议独立**——`PriceTableService` 已 250+ 行且职责是查询，读写分离更清晰）

### 通用实现约定

- **走 native SQL**，与该包既有风格一致（B0 事实 1）
- 三个方法各自 `@Transactional`（默认 `REQUIRED`），**价格写入与日志写入在同一方法内完成**
- 操作人从会话取（比照 `StrategyService` 取 `changed_by` / `changed_by_name` 的现成写法，直接复用）

### B4.1 `create(CreatePriceRequest req)`

```
1. 校验：price > 0；currency/priceUnit 非空；priceDate 非空
2. 校验 elementCode 存在且 ACTIVE  → 否则 400
3. 校验 sourceId  存在且 ACTIVE   → 否则 400
4. 查重：SELECT id FROM element_daily_price
         WHERE element_name=:e AND COALESCE(source_id::text,'')=:s AND price_date=:d
   命中 → throw BusinessException(409, "该元素在该源该日期已存在价格，请改用编辑")
5. INSERT（普通 INSERT，禁用 ON CONFLICT），fetch_status='MANUAL'，
   manually_filled_by/created_by/updated_by = 当前用户
6. writeLog(priceId, 'CREATE', 变更后完整值)
7. 返回该行的 ElementPriceRowDTO
```

> 第 4 步的查重与第 5 步的 INSERT 之间存在理论竞态。**依赖数据库唯一键做最终保证**：捕获 `ConstraintViolationException`/`PSQLException`（唯一键冲突 SQLState `23505`）后同样转成 `409`，不要让 500 冒出去。

### B4.2 `update(UUID id, UpdatePriceRequest req)`

```
1. 校验：price > 0；currency/priceUnit 非空
2. SELECT 原行（含键三元组，写日志要用） → 不存在 throw 404
3. UPDATE element_daily_price
   SET raw_price=:p, currency=:c, price_unit=:u,
       fetch_status='MANUAL',            -- ← 无条件翻转，含原本 IMPORT 的行
       updated_by=:uid, updated_at=now()
   WHERE id=:id
4. writeLog(id, 'UPDATE', 变更后完整值)
5. 返回修改后的 ElementPriceRowDTO
```

> **`UpdatePriceRequest` 里不得声明 `elementCode`/`sourceId`/`priceDate` 三个字段**（api.md §2）。不是"声明后忽略"——是根本不存在，让 Jackson 直接丢弃。验收 4 会构造请求强行传这三个字段验证键不变。

### B4.3 `delete(UUID id)`

```
1. SELECT 原行完整值 → 不存在 throw 404
2. DELETE FROM element_daily_price WHERE id=:id
3. writeLog(id, 'DELETE', 删除前的完整值)   -- ← 顺序关键：先读后删再写日志
```

### B4.4 `writeLog(...)` 私有方法

```java
ElementDailyPriceLog log = new ElementDailyPriceLog();
log.priceId       = priceId;
log.elementName   = elementName;      // 键三元组冗余
log.sourceId      = sourceId;
log.priceDate     = priceDate;
log.action        = action;           // CREATE / UPDATE / DELETE
log.snapshot      = mapper.writeValueAsString(snapshotMap);
log.changedAt     = OffsetDateTime.now();
log.changedBy     = currentUserId;
log.changedByName = currentUserName;
log.persist();
```

`snapshot` 的 JSON 内容至少含：`price` / `currency` / `priceUnit` / `fetchStatus`，供历史 Tab 做 diff 与全量展示。

---

## B5 · 变更历史查询

**方法**：`PriceTableService.listHistory(sourceId, from, to, keyword, page, size)`
**DTO**：`pricetable/PriceHistoryDTO.java` + 复用或平行新建 `PriceChangeDTO`（形状见 api.md §5）

### 算法（比照 `StrategyService.listHistory:505-548`）

```
1. 按筛选条件取出候选日志
2. 按「价格身份」= (element_name, COALESCE(source_id,''), price_date) 分组
3. 组内按 changed_at ASC 遍历，用相邻两条 snapshot 求 diff 填 changes
   - action=UPDATE 且存在前序快照 → changes = diffSnapshots(prev, cur)
   - action=CREATE/DELETE → changes = []
   - prevSnap = "DELETE".equals(action) ? null : snap      ← 照抄策略侧这一行
4. 拍平 → 按 changed_at DESC 排序 → 内存分页
5. 补 elementName（JOIN element）/ sourceName（JOIN element_price_source）/ targetLabel
```

### ⚠️ 一个必须优化的点：不要照抄策略侧的「全表 load」

策略侧 `listHistory:505` 是 `find("customerNo = ?1 ...").list()` —— **按客户天然收敛**，量可控。
价格日志**没有客户维度**，全表 load 会随时间无界增长。

**要求**：先用 SQL 把候选集收敛到匹配筛选条件的**价格身份**，再取这些身份的完整时间线（diff 需要完整时间线，不能先按 `changed_at` 截断）：

```sql
-- 第一步：定位命中筛选的价格身份
WITH hit AS (
  SELECT DISTINCT l.element_name, COALESCE(l.source_id::text,'') AS sid, l.price_date
  FROM element_daily_price_log l
  LEFT JOIN element e ON e.element_code = l.element_name
  WHERE (:sourceId IS NULL OR l.source_id = :sourceId)
    AND (:from IS NULL OR l.changed_at >= :from)
    AND (:to   IS NULL OR l.changed_at <  :toExclusive)
    AND (:kw   IS NULL OR l.element_name ILIKE :kw OR e.element_name ILIKE :kw)
)
-- 第二步：取这些身份的完整时间线（含窗口外的前序记录，diff 需要）
SELECT l.* FROM element_daily_price_log l
JOIN hit ON hit.element_name = l.element_name
        AND hit.sid = COALESCE(l.source_id::text,'')
        AND hit.price_date = l.price_date
ORDER BY l.element_name, COALESCE(l.source_id::text,''), l.price_date, l.changed_at ASC;
```

> 第二步**故意不带 `changed_at` 过滤**：某条 `UPDATE` 落在筛选窗口内、它的前序记录在窗口外时，只有取到前序才能算出 `changes`。窗口过滤在**第 4 步拍平后**再做，与策略侧一致。

### 日期边界

`to` 是 `date` 类型但 `changed_at` 是 `timestamptz`。用 `changed_at < (to + 1 day)` 表达「含当天」，**不要**用 `<= to`（会漏掉当天 00:00 之后的所有记录）。

---

## B6 · `PriceTableResource` 挂 4 个端点

在 `PriceTableResource` 内新增（**不新建 Resource 类**——新建会脱离类级 `@RoleAllowed`，权限就跟导入不一致了）：

```java
@POST @Path("/prices") @Consumes(MediaType.APPLICATION_JSON)
public Response create(CreatePriceRequest req) { ... }        // 201 + DTO

@PUT @Path("/prices/{id}") @Consumes(MediaType.APPLICATION_JSON)
public ElementPriceRowDTO update(@PathParam("id") UUID id, UpdatePriceRequest req) { ... }

@DELETE @Path("/prices/{id}")
public Response delete(@PathParam("id") UUID id) { ... }       // 204

@GET @Path("/prices/history")
public PageResult<PriceHistoryDTO> history(
        @QueryParam("sourceId") UUID sourceId,
        @QueryParam("from") String from, @QueryParam("to") String to,
        @QueryParam("keyword") String keyword,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size) { ... }
```

**要点**
- **不包 `ApiResponse`**（api.md §0.2）
- 方法级**不加** `@RoleAllowed`，靠类级继承
- 日期字符串复用类内已有的 `parseDate(s, field)` 私有方法（`:86`），错误码与既有端点一致
- `/prices/history` 的路径**必须排在 `/prices/{id}` 之后不会冲突**——JAX-RS 字面量段优先于模板段，但仍建议实测一次 `GET /prices/history` 没有被 `{id}` 吞掉（传 `history` 当 UUID 会 400）

---

## B7 · v1 元素价格中心后端下线

### 删除清单

| 对象 | 处置 |
|---|---|
| `com/cpq/elementprice/ElementPriceResource.java` | **整个文件删除**（4 个端点全下线） |
| `com/cpq/elementprice/ElementPriceService.java` | 删 `getReference` / `listHistory` / `upsertManual` / `listAvailableElements`；若删完类为空则整类删除 |
| `com/cpq/elementprice/ElementReferenceDTO.java` | 确认无其他消费方后删除 |
| `com/cpq/elementprice/UpsertManualPriceRequest.java` | 同上 |

### 删除前必做的三步核查

1. **精确匹配复数命名空间**，不要用子串：
   ```bash
   /usr/bin/grep -a -rn "element-prices" cpq-backend/src cpq-frontend/src
   ```
   > 本机 `grep` 是 `ugrep`，会把中文注释多的大文件**静默当二进制返空**——必须用 `/usr/bin/grep -a`（记忆 `cpq-grep-ugrep-binary-pitfall`）。

2. **实体类名查消费方**：`ElementReferenceDTO` / `UpsertManualPriceRequest` 用类名 grep，不要用表名。

3. **`codegraph_impact` 复核**：对 `ElementPriceService` 的 4 个方法各跑一次，确认无遗漏调用边（比 grep 准，能跟到别名/间接引用）。

### ⚠️ 删 Java 源文件后的已知坑

删源文件后，**主仓 `target/` 里可能残留旧 `.class`**，导致 Quarkus 启动时 CDI 报 `UnsatisfiedResolutionException`（worktree 里绿、合并回主仓炸）。
**合并后必须在主仓跑一次 `./mvnw clean test`**（记忆 `task0709-update0723-quote-import-template` 的踩坑记录）。

---

## B8 · 强制自检（缺任一项 = 未完成）

### 编译与启动
```bash
cd cpq-backend && ./mvnw -q compile          # 0 错误
touch src/main/java/com/cpq/CpqApplication.java   # 触发 dev 重启，等 5-7s
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:8081/api/cpq/components          # 期望 401（应用在跑、鉴权正常）
```
> ⚠️ 探本机服务**必须加 `--noproxy '*'`**（本机 `http_proxy=127.0.0.1:7890` 会让 curl 走代理返 502）。
> ⚠️ `/q/health` **不是健康探针**（未装 smallrye-health，恒 404），别拿它判死活。

### Flyway
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='<NNN>';"   # success=t
```

### 端点冒烟（附到交付说明里）
```bash
# 1. 新建
curl -s --noproxy '*' -X POST http://localhost:8081/api/cpq/element-price/prices \
  -H 'Content-Type: application/json' -H "Authorization: Bearer <token>" \
  -d '{"elementCode":"Ag","sourceId":"<src>","priceDate":"2026-07-24","price":5860,"currency":"CNY","priceUnit":"kg"}'
# 2. 撞键 → 期望 409 且原值不变
# 3. 改导入行 → SQL 直查 fetch_status
PGPASSWORD=joii5231 psql ... -c "SELECT fetch_status, raw_price FROM element_daily_price WHERE id='<id>';"
# 4. 强行传键字段 → 键不变
curl -X PUT .../prices/<id> -d '{"price":6000,"currency":"CNY","priceUnit":"kg","elementCode":"Cu","priceDate":"2020-01-01"}'
# 5. 删除 → 204，历史表有 DELETE 记录且 snapshot 是删除前的值
```

### 取价链路硬验证（验收 7/8，**核心项，不可跳过**）
```sql
-- 为某客户配好策略后，手工新建一条该源该元素的价
SELECT * FROM f_customer_element_price('<客户编码>', CURRENT_DATE);
-- 期望：能取到该价（含系数加价换算后的值）→ 证明手工价与导入价等价
-- 再删掉该价，重新调用 → 结果按剩余数据重算（AVG 均值变 / LATEST 回退前一条）
```

### 事务原子性（验收 11）
构造历史写入失败（例如临时把 `snapshot` 写成超长值触发约束，或在 `writeLog` 内注入异常），确认：
- 价格**没有**被写入/修改/删除
- 不出现「价改了但没留痕」或「留痕了但价没改」

### 单元测试
新增测试覆盖：撞键 409、`price<=0` 拒绝、键字段被忽略、`IMPORT→MANUAL` 翻转、三种动作各写一条日志、`DELETE` 的 snapshot 是删除前值、事务回滚。

> ⚠️ **测试必须在 worktree 的 `cpq-backend/` 里跑**（`mvnw` 在 `cpq-backend/` 不在仓库根）。子代理若 `cd` 到主仓跑会测错代码树、报假绿（记忆 `cpq-worktree-maven-test-tree`）。

### 交付说明必须包含这一行
> "编译 0 错误 ✅；`/api/cpq/components` → 401 ✅；`V<NNN>` success=t ✅；撞键 409 + 原值未覆盖（SQL 直查）✅；`IMPORT→MANUAL` 翻转（SQL 直查）✅；`f_customer_element_price` 取到手工价 ✅；事务回滚验证通过 ✅；`element-prices`（复数）全工程 0 命中 ✅"

**没有这一行的"完成"= 未完成。**

---

## 任务清单与依赖

| 任务 | 依赖 | 规模 | 前端是否阻塞 |
|---|---|:--:|---|
| B1 建日志表迁移 | — | S | 否 |
| B2 日志实体 | B1 | XS | 否 |
| **B3 DTO 补 `id`/`fetchStatus`** | — | S | **是（前端 F2/F3 强依赖，建议最先做并先行联调）** |
| B4 写服务 | B2 | M | 是（F3） |
| B5 历史查询 | B2 | M | 是（F4） |
| B6 Resource 挂端点 | B3/B4/B5 | S | 是 |
| B7 v1 下线 | — | S | 需与前端 F5 配对（前端先删入口，后端再删端点，避免中间态 404） |
| B8 自检 | 全部 | M | — |

**建议顺序**：B3 →（B1→B2）→ B4 → B5 → B6 → B7 → B8。
B3 单独先行合入可解除前端阻塞。
