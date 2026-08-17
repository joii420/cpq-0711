# 后端任务文档 · 元素单价维护与价格策略（task-0722）

> **权威依据**：`需求说明.md` §11（尤其 §11.24 交付物对照矩阵）、`api.md`（前后端契约）、`元素价格策略-原型图.html`（v9 定稿）。
> **配套**：`fronttask.md`、`单价字段配置规则.md`。
> **纪律**：契约以 `api.md` 为准，需要改字段先改 `api.md` 并知会前端，不得单边改。

---

## 0. 开工前必读（踩过的坑，逐条确认）

| # | 事项 | 依据 |
|---|------|------|
| 1 | **禁止手工 `psql -f V_xx.sql`**。迁移文件放进 `db/migration/` 后 `touch` 一个 java 文件让 Quarkus dev 自动跑 Flyway；手工跑会导致 checksum mismatch | CLAUDE.md |
| 2 | **Flyway 版本号是移动靶**：本文写作时线上最大 `V350`，本任务预留 `V351`。**开工前重新查一次** `SELECT max(version::int) FROM flyway_schema_history`，被占用就顺延，**已应用的迁移禁止改名改号** | `cpq-shared-flyway-history-churn` |
| 3 | **任何 DDL（建表/建函数/改视图）后必须 `touch` java 文件重启 Quarkus**，清 `ImplicitJoinRewriter.tableColumnsCache` 等进程级缓存 | CLAUDE.md |
| 4 | 本机 `curl` 探本地服务一律加 `--noproxy '*'`；后端健康判据是**业务端点返 401**（`/q/health` 是 404，不是探针） | CLAUDE.md |
| 5 | 本环境 `grep` 是 `ugrep`，对中文注释多的大文件会静默返空。据 grep 空结果下"无引用"结论前，用 `/usr/bin/grep -a` 复核 | `cpq-grep-ugrep-binary-pitfall` |
| 6 | worktree 只隔离 git 工作区，**dev server(8081/5174) / DB / node_modules 是共享的**。不要在 worktree 里另起 dev server | CLAUDE.md |
| 7 | 只 `git add` 本次明确改动的文件，**严禁 `git add -A`**（多会话并发会交错提交） | CLAUDE.md |

---

## 1. 任务总览

| 任务 | 内容 | 依赖 | 规模 |
|------|------|------|------|
| B1 | Flyway 迁移：策略表 + 历史表 + `fetch_status` 扩枚举 | — | S |
| B2 | `f_customer_element_price` 表函数（取价核心） | B1 | M |
| B3 | `:priceBaseDate` 运行时注入 + 缓存维度 | — | M |
| B4 | 价格源 CRUD | B1 | S |
| B5 | 价格导入（模板 + 导入 + 部分成功） | B1 | M |
| B6 | 价格表查询（明细 / 矩阵 / 双导出） | B1 | M |
| B7 | 元素侧增强（各源最新价 + 元素列表 `lastModifiedAt`） | B1 | S |
| B8 | 策略 CRUD + 历史写入（同事务） | B1 | M |
| B9 | 历史查询 + 差异计算 | B8 | S |
| B10 | 策略试算 | B2 | S |
| B11 | 两个标杆组件取数配置接通（**数据配置，非代码**） | B2 B3 | S |
| B12 | 整体自检与回归 | 全部 | S |

---

## B1 · Flyway 迁移

**文件**：`cpq-backend/src/main/resources/db/migration/V351__element_price_strategy.sql`（版本号开工前复查）

```sql
-- ============ 1. 策略表 ============
-- 🔒 客户维度用 customer_no VARCHAR，禁止 customer_id UUID 外键（需求 §11.11.4）
--    原因：核价侧走 '_GLOBAL_'，它不是一条真实 customer 记录，UUID 外键无处安放。
CREATE TABLE element_price_strategy (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no   VARCHAR(64)   NOT NULL,      -- 真实客户编码(CUST-1269) 或 '_GLOBAL_'
    element_code  VARCHAR(64),                 -- NULL = 客户级默认行；非空 = 元素级例外
    source_id     UUID          NOT NULL REFERENCES element_price_source(id),
    method        VARCHAR(16)   NOT NULL,
    window_num    INT,
    window_unit   VARCHAR(8),
    factor        NUMERIC(10,4) NOT NULL DEFAULT 1,
    premium       NUMERIC(18,4) NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by    UUID,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by    UUID,
    CONSTRAINT chk_eps2_method CHECK (method IN ('LATEST','AVG','MAX','MIN')),
    CONSTRAINT chk_eps2_unit   CHECK (window_unit IS NULL OR window_unit IN ('DAY','WEEK','MONTH','YEAR')),
    CONSTRAINT chk_eps2_status CHECK (status IN ('ACTIVE','DISABLED')),
    -- LATEST 不带窗口；其余三种窗口必填且 > 0（需求 §11.7）
    CONSTRAINT chk_eps2_window CHECK (
        (method =  'LATEST' AND window_num IS NULL AND window_unit IS NULL) OR
        (method <> 'LATEST' AND window_num > 0     AND window_unit IS NOT NULL)
    ),
    CONSTRAINT chk_eps2_factor CHECK (factor > 0)
);

-- 同一客户下：默认行至多 1 条、每个元素例外至多 1 条
CREATE UNIQUE INDEX uq_eps2_cust_elem
    ON element_price_strategy (customer_no, COALESCE(element_code, ''));
CREATE INDEX idx_eps2_customer ON element_price_strategy (customer_no);

COMMENT ON TABLE  element_price_strategy IS 'task-0722 客户元素价格策略；element_code IS NULL 为客户级默认行';
COMMENT ON COLUMN element_price_strategy.customer_no IS '真实客户编码或 _GLOBAL_（核价成本口径）';

-- ============ 2. 变更历史表 ============
-- 不加 FK 到 strategy：策略删除后历史必须留存（需求 §11.14F.2）
CREATE TABLE element_price_strategy_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id     UUID,
    customer_no     VARCHAR(64)  NOT NULL,
    element_code    VARCHAR(64),                -- NULL = 客户级默认策略
    action          VARCHAR(16)  NOT NULL,
    snapshot        JSONB        NOT NULL,      -- 变更后完整快照；DELETE 存删除前快照
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    changed_by      UUID,
    changed_by_name VARCHAR(100),               -- 冗余姓名，历史查询免 join user
    CONSTRAINT chk_epsl_action CHECK (action IN ('CREATE','UPDATE','DELETE'))
);
CREATE INDEX idx_epsl_cust_time ON element_price_strategy_log (customer_no, changed_at DESC);
CREATE INDEX idx_epsl_target    ON element_price_strategy_log (customer_no, COALESCE(element_code,''), changed_at DESC);

COMMENT ON TABLE element_price_strategy_log IS 'task-0722 价格策略变更历史（存快照、展示差异；只读、不做回滚）';

-- ============ 3. element_daily_price 扩 fetch_status 枚举 ============
-- 既有 CHECK 只允许 SUCCESS/FAILED/MANUAL，本期批量导入写 IMPORT
ALTER TABLE element_daily_price DROP CONSTRAINT IF EXISTS chk_edp_fetch_status;
ALTER TABLE element_daily_price ADD  CONSTRAINT chk_edp_fetch_status
    CHECK (fetch_status IN ('SUCCESS','FAILED','MANUAL','IMPORT'));

-- 明细/矩阵查询按 (source_id, price_date) 过滤，补索引
CREATE INDEX IF NOT EXISTS idx_edp_source_date ON element_daily_price (source_id, price_date DESC);
CREATE INDEX IF NOT EXISTS idx_edp_elem_date   ON element_daily_price (element_name, price_date DESC);
```

**验收**：`SELECT version, success FROM flyway_schema_history WHERE version='351'` → `success=t`。

**注意**：`element_daily_price.element_name` 列**存的是元素符号**（`Ag`），与 `element.element_code` 对齐。列名是 V44 历史遗留，**不要改名**（既有 `ElementPriceService` 在用），但所有新代码/DTO 一律用 `elementCode` 命名。

---

## B2 · `f_customer_element_price` 表函数（取价核心）

**文件**：同 B1 迁移或独立 `V352__f_customer_element_price.sql`。

**契约**（`单价字段配置规则.md` §1 已发给配置 agent，**不可随意改签名**）：

```sql
CREATE OR REPLACE FUNCTION f_customer_element_price(
    p_customer_no TEXT,
    p_base_date   DATE
) RETURNS TABLE (
    element_code VARCHAR,
    unit_price   NUMERIC,
    currency     VARCHAR,
    price_unit   VARCHAR
) LANGUAGE sql STABLE AS $$
WITH def AS (           -- 客户级默认策略（至多 1 条）
    SELECT * FROM element_price_strategy
     WHERE customer_no = p_customer_no AND element_code IS NULL AND status = 'ACTIVE'
     LIMIT 1
),
eff AS (   -- 每个启用元素的生效策略：有例外 → 整行用例外；无例外 → 整行用默认
    -- ⚠️ 用 CASE WHEN x.id IS NOT NULL 而非逐字段 COALESCE：
    --    例外是【完整独立策略行】，不与默认做字段级混合（见下方「例外语义」说明）。
    --    逐字段 COALESCE 在当前 schema 下行为相同（factor/premium 有 DB DEFAULT 恒非 NULL），
    --    但语义误导，且一旦将来去掉列默认值就会静默变成"半继承"，故禁止使用。
    SELECT e.element_code,
           CASE WHEN x.id IS NOT NULL THEN x.source_id   ELSE d.source_id   END AS source_id,
           CASE WHEN x.id IS NOT NULL THEN x.method      ELSE d.method      END AS method,
           CASE WHEN x.id IS NOT NULL THEN x.window_num  ELSE d.window_num  END AS window_num,
           CASE WHEN x.id IS NOT NULL THEN x.window_unit ELSE d.window_unit END AS window_unit,
           CASE WHEN x.id IS NOT NULL THEN x.factor      ELSE d.factor      END AS factor,
           CASE WHEN x.id IS NOT NULL THEN x.premium     ELSE d.premium     END AS premium
      FROM element e
      LEFT JOIN element_price_strategy x
             ON x.customer_no  = p_customer_no
            AND x.element_code = e.element_code
            AND x.status = 'ACTIVE'
      LEFT JOIN def d ON TRUE
     WHERE e.status = 'ACTIVE'
       -- 既无例外也无默认策略的元素直接排除（需求 §11.15：不兜底）
       AND (x.id IS NOT NULL OR d.id IS NOT NULL)
),
win AS (                -- 算出滚动窗口起点（LATEST 无窗口）
    SELECT eff.*,
           CASE WHEN eff.method = 'LATEST' THEN NULL
                ELSE (p_base_date - (eff.window_num || ' ' ||
                       CASE eff.window_unit
                            WHEN 'DAY'   THEN 'day'   WHEN 'WEEK' THEN 'week'
                            WHEN 'MONTH' THEN 'month' ELSE 'year' END)::interval)::date
           END AS win_from
      FROM eff
)
SELECT w.element_code,
       ROUND(agg.raw_value * w.factor + w.premium, 4) AS unit_price,
       agg.currency,
       agg.price_unit
  FROM win w
  CROSS JOIN LATERAL (
      SELECT
        CASE w.method
          WHEN 'LATEST' THEN (
              SELECT dp.raw_price FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL AND dp.price_date <= p_base_date
               ORDER BY dp.price_date DESC LIMIT 1)
          WHEN 'AVG' THEN (
              SELECT AVG(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          WHEN 'MAX' THEN (
              SELECT MAX(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          ELSE (
              SELECT MIN(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
        END AS raw_value,
        -- 货币/单位取窗口内最新一条（需求 §11.10：一并带出、不换算）
        (SELECT dp.currency   FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS currency,
        (SELECT dp.price_unit FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS price_unit
  ) agg
 WHERE agg.raw_value IS NOT NULL;   -- 窗口内无价 → 该元素不返回（需求 §11.5 留空、不兜底）
$$;
```

### 硬性语义（逐条写单测）

| # | 语义 | 依据 |
|---|------|------|
| 1 | 窗口内**无价的元素不出现在结果里**（返回行数变少），**不是**返回 0 或 NULL 行 | §11.5 |
| 2 | 窗口是**滚动区间**（基准日往前推 N 天/周/月/年），不是自然日历月 | §11.9 |
| 3 | `LATEST` 取 **≤ 基准日** 的最近一条，不看窗口 | §11.7 |
| 4 | 最终价 = `raw × factor + premium`，**先乘后加**，不可调换 | §11.8 |
| 5 | 元素级例外是**完整独立策略行**：某元素有例外 → **整行用例外的 5 个字段**；无例外 → 整行用默认。**不做字段级混合**（2026-07-22 测试用例评审裁决，见下方说明） | §11.1 |
| 6 | 既无例外也无默认策略的元素**直接排除**，不兜底 | §11.15 |
| 7 | `p_customer_no` 传 `'_GLOBAL_'` 时走全局策略，与真实客户完全同构 | §11.11 |
| 8 | 结果保留 **4 位小数** | 小数口径 |

### 性能

一次调用返回该客户全部有价元素（数量级：几十行）。驱动视图 `LEFT JOIN` 它、**一次求值**，不是逐行调用，无 N+1。若后续发现慢，先看 `idx_edp_source_date` 是否命中。

---

## B3 · `:priceBaseDate` 运行时注入

**改动点**：`cpq-backend/src/main/java/com/cpq/datasource/sqlview/SqlViewExecutor.java`

**做法**：**完全比照既有 `enrichCustomerCode()`**（`:417`）的加法式模式新增 `enrichPriceBaseDate()`：

```java
/** quotationId → 报价单创建日期(基准日) 的进程级缓存。created_at 不可变，缓存安全。 */
private final Map<UUID, LocalDate> priceBaseDateCache = new ConcurrentHashMap<>();

/**
 * 补充 :priceBaseDate 命名占位符（task-0722）。
 * 基准日 = 该报价单的创建日期（需求 §11.2）；无 quotationId 上下文（配置期预览/试算）回退当天。
 * 注意 quotation 表无独立"单据日期"列，取 created_at::date。
 */
private void enrichPriceBaseDate(Map<String, Object> namedParams) {
    if (namedParams.containsKey("priceBaseDate")) return;   // 上层显式给了则不覆盖
    UUID qid = SqlViewRuntimeContext.get().quotationId;
    LocalDate d = (qid == null) ? LocalDate.now()
                                : priceBaseDateCache.computeIfAbsent(qid, this::queryQuotationDate);
    namedParams.put("priceBaseDate", d);
}
```

**接入位置**：与 `enrichCustomerCode(namedParams)` 的调用点并列，**同一处调用**。

### ⚠️ 三个必须确认的点

1. **加法式**：不改任何既有视图；未使用 `:priceBaseDate` 的视图行为逐字节不变。改完跑一遍现有报价单，确认卡片值无变化。
2. **🔴 缓存维度（最高风险）**：新增了"基准日"这个求值维度后，凡按 `$view` 结果做缓存的地方，**缓存键必须含客户 + 基准日**，否则**跨报价单串价**（A 单算出的 Ag 价被 B 单复用）。逐一排查并在 PR 里列出结论：
   - `ComponentDriverService` 的 `expandCache`（`key` 构造处）
   - `DataLoader.resultCache`
   - 前端 `useDriverExpansions` 的 `driverExpansionKey`（前端侧由 fronttask 负责，但后端要确认后端侧）
   - 历史教训：`cpq-sqlview-cache-key-needs-component-dim`、`AP-37`（cache key 缺维度导致串号）
   - **验证方法**：两张**不同创建日期**的报价单，同一客户同一料号，故意让两个日期的价格不同 → 两单卡片值必须不同。若相同即为串价。
3. **回退当天**的场景：策略试算、组件配置期预览。确保这些路径不因缺 `quotationId` 报错。

---

## B4 · 价格源 CRUD

**契约**：`api.md` §1。

**新增**：
- `com.cpq.elementprice.source.PriceSourceResource`（`@Path("/api/cpq/element-price/sources")`）
- `PriceSourceService`、`PriceSourceDTO`、`PriceSourceUpsertRequest`

**要点**：
- 实体可直接用 Panache 映射既有 `element_price_source` 表。
- `sourceType` **后端固定写 `MANUAL`**，不接受前端传值（§11.13）。
- **不提供物理删除**，只有 `POST /{id}/status` 切换 `ACTIVE`/`DISABLED`（§11.13.1）。
- 唯一键冲突（`uq_eps_name_url`）捕获后抛 `BusinessException(409, "源名称 + 网址 已存在")`。
- 列表排序：启用优先 → `updated_at` 倒序。

**停用语义（三条都要，写单测）**：
1. 停用后 `GET /sources?status=ACTIVE` 不再返回它 → 策略/导入的下拉自然选不到；
2. 该源的历史价格在 §3 价格表、§4.1 各源最新价中**照常返回**；
3. **已引用该源的存量策略继续按原样取价**——即 `f_customer_element_price` **不过滤源状态**。⚠️ 这条最容易做错：不要在表函数里加 `AND s.status='ACTIVE'`，否则停用一个源会让一批客户报价突然无价。

---

## B5 · 价格导入

**契约**：`api.md` §2。

### 模板下载
Apache POI 生成 4 列表头（`元素符号*` / `单价*` / `货币` / `计价单位`）+ 1 行示例 + 填写说明。参考既有 `ProcessMasterImportService` 的模板产出方式（BL-0045 已有同类实现，可抄）。

### 导入
**🔒 事务边界（§11.3.2，最重要）**

- **逐行独立处理，失败行不阻断其他行入库**。
- **严禁**把整个导入包在一个 `@Transactional` 里"任一行失败即回滚"。
- 推荐实现：外层方法不开事务，**逐行调用一个 `@Transactional(REQUIRES_NEW)` 的单行写入方法**，捕获单行异常记入结果集继续下一行。
  > 该模式与既有 V6 导入一致（记忆：*V6 导入按 sheet 独立事务非原子（REQUIRES_NEW）*），可参照 `VersionedV6Writer` 相关实现。

**行级校验**（§11.3.1）：

| 校验 | 失败 message |
|------|-------------|
| 元素符号非空 | `"元素符号不能为空"` |
| 元素符号在 `element` 表存在 | `"元素符号「X」在元素管理中不存在"` |
| 该元素 `status='ACTIVE'` | `"元素「X」已停用，不可导入价格"` |
| 单价非空且 > 0 | `"单价必须大于 0"` |
| 货币/单位可空 | 空则取 `CNY` / `元/kg` |

**写库**：`INSERT ... ON CONFLICT (element_name, COALESCE(source_id::TEXT,''), price_date) DO UPDATE`，
`fetch_status='IMPORT'`，`updated_by`/`updated_at` 更新。覆盖场景需**先读旧值**以便在结果里回显 `原值 X → 新值 Y`。

**前置校验**（整批级，这些失败才整批拒绝）：文件为空 / 非 xlsx / > 5MB / 表头不匹配 / `sourceId` 非 `ACTIVE`。

---

## B6 · 价格表查询

**契约**：`api.md` §3。

- **明细**：分页 + 三项筛选，排序 `price_date DESC, element_code ASC`。`operatorName` 由 `updated_by` join `"user".full_name` 取（注意 `user` 是保留字，要加双引号）。
- **矩阵**：
  - `sourceId` **必填**，缺失 `400`；
  - 跨度 **> 90 天返 `400`**（`"矩阵视图日期跨度最长 90 天，请收窄区间"`）；
  - 🔴 **`dates` 必须是「稠密日期轴」**：= 请求区间 `from ~ to` 内的**每一天**（升序），**不是**"只含有数据的那些天"。
    - 实现：用 PG `generate_series(from, to, '1 day')` 生成日期主轴，再 `LEFT JOIN` 价格数据，缺失天填 `null`。
    - `prices` 与 `dates` **等长、按下标对齐**；无记录填 `null`（**不要补 0**）。
    - **依据**：原型屏 4 矩阵表头是 `07-16 ~ 07-22` 七个**连续**日期，其中 07-19 / 07-22 显示「—」。若返回稀疏日期，用户选 30 天可能只看到 3 列，"日期区间选择"这个交互失去意义，且前端的「—」渲染分支永远收不到 `null`、成为死代码。
    - ⚠️ 本条初版曾误写为"区间内实际有数据的日期"（自相矛盾：只含有数据的天，何来"无记录的 null"），2026-07-23 测试阶段发现并更正。
- **两个导出**：走当前筛选的**全量**结果（不分页），POI 生成 xlsx。

---

## B7 · 元素侧增强

### 7.1 各源最新价（新端点）
`GET /api/cpq/element-price/latest-by-source?elementCode=Cu` — 每个有过记录的源返一行（该源 `price_date` 最大的一条），**含已停用的源**（带 `sourceStatus` 让前端置灰）。

### 7.2 元素列表 `lastModifiedAt`（改造既有端点）
**改动**：`ElementService.list()` / `ElementDTO`

- 新增字段 `lastModifiedAt = MAX(element.updated_at, 该元素所有价格记录的 updated_at)`。
- **🔒 不反写 `element.updated_at`**（§11.14B）：查询时取大值即可。理由：不污染主档语义、避免导入时批量回写产生行锁竞争、存量数据天然正确。
- **排序改为**：启用优先 → `lastModifiedAt` 倒序。
- 既有 `createdAt`/`updatedAt` 字段**保留不动**（其它调用方可能在用）。
- 实现建议：`LEFT JOIN (SELECT element_name, MAX(updated_at) mx FROM element_daily_price GROUP BY 1) p ON p.element_name = e.element_code`，一次查询搞定，勿逐元素 N+1。

**顺带闭合 BL-0069#5**：`ElementPriceService.listAvailableElements()` 现读**已废弃**的 `mat_bom`（自 2026-06-02 停写），改读 `element` 主表的 ACTIVE 元素。

---

## B8 · 策略 CRUD + 历史写入

**契约**：`api.md` §5。

**要点**：
- `customerNo` 全链路 **String**，`_GLOBAL_` 原样穿透，**任何一层都不要转 UUID**。
- 真实客户编码需校验存在于 `customer.code`；`_GLOBAL_` 跳过该校验。
- `sourceId` 必须 `ACTIVE`，否则 `400`。
- `method=LATEST` 时 `windowNum`/`windowUnit` 必须为 null（Java 侧先校验，DB CHECK 兜底）。
- 例外撞键（`uq_eps2_cust_elem`）→ `409`。

**🔴 历史写入（§11.22 列为风险）**：策略有 **5 条写入路径**——默认策略新建 / 默认策略修改 / 例外新建 / 例外修改 / 例外删除。**每条路径都必须写 log，且与策略写入同一个事务**（避免只落其一导致留痕断链）。

推荐做法：抽一个私有方法 `writeLog(action, strategy, userId)`，在 service 层每个写方法的**同一事务内**调用；PR 里逐路径列出调用点截图/行号自证无遗漏。

`snapshot` JSONB 内容（`DELETE` 时存删除前的）：
```json
{"sourceId":"...","sourceName":"上海有色网","method":"AVG","windowNum":30,
 "windowUnit":"DAY","factor":1.05,"premium":2.00}
```
> `sourceName` 冗余进快照：源可能被改名，历史要还原当时的名字。

---

## B9 · 历史查询 + 差异计算

**契约**：`api.md` §7。

**差异由后端算**（前端只渲染）：
1. 按 `(customer_no, COALESCE(element_code,''))` 分组，`changed_at` 排序；
2. 每条 `UPDATE` 记录与**同组上一条**的 `snapshot` 逐字段比对；
3. 只输出**有变化**的字段，附中文 `fieldLabel`（`sourceName→价格源` / `method→取值方式` / `windowNum`+`windowUnit`→合成"窗口" / `factor→系数` / `premium→加价`）；
4. `CREATE` / `DELETE` 的 `changes` 为空数组，前端改用 `snapshot` 展示全量。

`targetLabel` 后端拼好：`"客户级默认策略"` 或 `"元素例外 · Ag 银"`。

**筛选**：`elementCode` 传 `__DEFAULT__` 表示只看客户级默认（对应 `element_code IS NULL`）。

**只读，不提供任何写接口**（§11.24 D 节：明确不做回滚）。

---

## B10 · 策略试算

**契约**：`api.md` §6。

- 不传 `draft` → 直接调 `f_customer_element_price(customerNo, baseDate)`，但**还需要额外返回 `hitRule` / `rawValue` / `sampleDays`** 等表函数不返回的字段 → 试算走**独立 SQL**（可复用表函数的 CTE 逻辑，多 SELECT 几列），不要为了试算去改表函数签名（签名已发给配置 agent）。
- 传 `draft` → 用草稿策略参与计算（**不写库**），支持"改了没保存先试算"。实现上把 draft 转成 `VALUES` 临时集合参与同样的 CTE。
- 返回**全部已被策略覆盖的启用元素**，含无价的（`hasPrice=false`，前端标黄）。

---

## B11 · 两个标杆组件取数配置接通

⚠️ **这是数据配置，不是写 Java 代码**。严格照 `单价字段配置规则.md` 执行，逐条对照其 §5 自检清单。

| 组件 | 侧 | 改动 |
|------|----|------|
| `COMP-0029` | 报价（QUOTE） | 视图加 `LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate)`，输出 **`单价`/`货币` 两个**中文别名；这**两个**字段配 `default_source`（`INPUT_*` 类型**不变**）；`required_variables` = `["customerCode","priceBaseDate"]`<br>⛔ **「计价单位」字段不绑、不动**——它是 BOM 发料单位（`ebi.issue_unit`），被「毛用量」「净用量」通过 `unit_source_field` 引用参与单位换算，改指价格的 `元/kg` 会让换算读到非法 token（详见 §11.10.1 与配置规则 Step 1 红框） |
| `COMP-0040` | 核价（PRICING） | 同上但 **传字面量 `'_GLOBAL_'`**；`单价` 取数由 `global_variable_value(COST_ELEMENT)` 改为 `cep.unit_price`；`required_variables` = `["priceBaseDate"]`；**WHERE 里不要加 `customer_no` 条件** |

**🚫 三个高频错误**（规则文档 §3 硬约束）：
1. 用 `INNER JOIN` → 无价元素整行消失、行数变少、页签小计错；
2. `COALESCE(cep.unit_price, 0)` → 无价变 0 元成本，**静默算错且无报错**；
3. 核价侧加 `customer_no = :customerCode` → `_GLOBAL_ ≠ CUST-xxxx`，一行都查不到。

**其余 3 个元素组件副本**（`COMP-0029__imp1` / `COMP-0020__imp1` / `COMP-0020__imp1__imp1`）**本期不动**，由业务按规则文档自配（§11.19）。

---

## B12 · 自检与回归（缺一不可，无此声明 = 未完成）

### 编译与存活
```bash
# 后端改动后 touch 强制重启，等 5-7 秒
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
PGPASSWORD=... psql ... -c "SELECT version,success FROM flyway_schema_history WHERE version IN ('351','352')"  # 期望 success=t
```

### 功能自检（对应 §11.21 验收要点）
- [ ] #1 源：新建/编辑/停用；停用后策略与导入下拉均选不到；**历史价仍显示**；**存量策略仍能取到价**
- [ ] #2 #16 导入：3 新增 + 重导变 3 覆盖；含 1 错行时**成功行确已入库**（查 DB 计数，非整批回滚）
- [ ] #3 价格表：明细筛选正确；矩阵缺 `sourceId` 返 400、跨度 91 天返 400、无记录为 `null`
- [ ] #4 策略：默认 + 例外保存回读一致；`LATEST` 带窗口参数返 400
- [ ] #5 报价取价：报价单元素页签 Ag 单价 = 手工按策略算出的值（用价格表数据人工复核）
- [ ] #6 核价取价：核价元素单价按 `_GLOBAL_` 取；**换一个不同客户的报价单，核价元素单价不变**
- [ ] #7 可覆盖：改掉自动值 → 保存 → 重开仍是改后的值
- [ ] #8 留空：清掉窗口内价格 → 该行单价为空（不是 0、不是"加载中"）
- [ ] #9 无回归：未配策略客户的报价单、其他页签、其他客户模板逐位不变
- [ ] #10 `_GLOBAL_` 可配可读
- [ ] #11 历史：改系数 → 只列变化项；增删例外各产生一条；`_GLOBAL_` 同样入历史
- [ ] #12 最后变更信息与历史最新一条一致
- [ ] #13 **前端零改动核验**：`git diff --stat` 中报价/核价渲染相关 `.tsx` 为空
- [ ] #14 导入价格后元素列表 `lastModifiedAt` 更新并排最前，但 `element.updated_at` **不变**（SQL 直查确认）
- [ ] #15 各源最新价：3 源 3 行；停用源仍在且带 `sourceStatus=DISABLED`；无价元素返 `[]`

### 🔴 串价专项（B3 风险 2，必测）
造两张**创建日期不同**的报价单（同客户同料号），并让两个基准日的价格不同 → **两单的元素单价必须不同**。若相同 = 缓存键缺基准日维度，属阻断级缺陷。

### E2E
本期报价渲染层零改动，**不触发**「改 `QuotationStep2.tsx` 必跑 E2E」的强制条款；但仍建议跑一轮 `quotation-flow.spec.ts` 确认取数接通后无回归。
> 注意夹具现状：干净 master 上该 spec 已有 3 个失败（`task0712-update071501-category-axis` 记录的夹具漂移），**判断回归须与改动前 A/B 同型对比**，不要误归因。

### 完成声明格式
> "TS —（后端无前端改动）；`/api/cpq/element-price/sources` → 401 ✅；V351/V352 success=t ✅；16 项验收全绿 ✅；串价专项通过 ✅"
