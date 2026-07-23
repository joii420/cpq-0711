-- task-0722 元素单价维护与价格策略 · B2
-- f_customer_element_price(客户编码, 基准日) 表函数 —— 取价核心，签名固定不可随意改
-- （已发给配置 agent，接入组件见 单价字段配置规则.md）
--
-- 【版本号变更说明】本迁移原为 V352，因共享库版本号被并发会话占用改到 V357（同 V356 头注说明，
-- CREATE OR REPLACE FUNCTION / COMMENT ON 本身即幂等，无需额外 IF NOT EXISTS 改造）。

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
eff AS (                -- 每个启用元素的生效策略：例外优先，否则默认
    SELECT e.element_code,
           COALESCE(x.source_id,   d.source_id)   AS source_id,
           COALESCE(x.method,      d.method)      AS method,
           COALESCE(x.window_num,  d.window_num)  AS window_num,
           COALESCE(x.window_unit, d.window_unit) AS window_unit,
           COALESCE(x.factor,      d.factor)      AS factor,
           COALESCE(x.premium,     d.premium)     AS premium
      FROM element e
      LEFT JOIN element_price_strategy x
             ON x.customer_no  = p_customer_no
            AND x.element_code = e.element_code
            AND x.status = 'ACTIVE'
      LEFT JOIN def d ON TRUE
     WHERE e.status = 'ACTIVE'
       -- 既无例外也无默认策略的元素直接排除（需求 §11.15：不兜底）
       AND COALESCE(x.source_id, d.source_id) IS NOT NULL
       -- ⚠️ 不过滤源状态（source.status）：停用一个源不能让存量策略突然无价（需求 §11.13.1 第 3 条）
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

COMMENT ON FUNCTION f_customer_element_price(TEXT, DATE) IS
'task-0722 客户元素价格取价核心表函数。p_customer_no 接受真实客户编码或字面量 ''_GLOBAL_''（核价成本口径）。
不过滤 source.status（停用源不影响存量策略取价）；窗口内无价的元素不出现在结果集中（不返回 0/NULL 行）。';
