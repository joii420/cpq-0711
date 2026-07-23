-- task-0722 元素单价维护与价格策略 · 测试返修项2
-- f_customer_element_price(客户编码, 基准日) —— 例外/默认合并逻辑由逐字段 COALESCE 改为 CASE WHEN 整行判定。
--
-- 【为什么改】需求说明.md §11.1.1 + backtask.md B2 硬性语义表第 5 条明确：元素例外是【完整独立策略行】——
-- 有例外则整行用例外的 5 个字段，无例外则整行用默认，不做字段级混合。
-- 原 V357 用 COALESCE(x.field, d.field) 之所以结果碰巧正确，是因为 factor/premium 两列有 DB DEFAULT
-- (factor DEFAULT 1 / premium DEFAULT 0)，令例外侧恒非 NULL，COALESCE 恒短路到例外侧。
-- 一旦将来去掉列默认值，会静默退化为"半继承"（部分字段取例外、部分取默认）且不报错，属于依赖列默认值
-- 而非显式语义的技术债。本迁移改用 CASE WHEN x.id IS NOT NULL 显式整行判定，语义与实现一致，签名不变。
--
-- 【签名不变】p_customer_no TEXT, p_base_date DATE → element_code, unit_price, currency, price_unit
-- （已发给配置 agent，见 单价字段配置规则.md §1，不可改签名）。
--
-- 【验证要求】改前/改后取价结果必须逐位一致（COALESCE 与 CASE WHEN 在当前 schema 下等价，
-- 只是显式性不同）：例外优先 / 无例外走默认 / 无策略客户 0 行 / 窗口内无价元素不出现。

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
eff AS (                -- 每个启用元素的生效策略：有例外 → 整行用例外；无例外 → 整行用默认。
                         -- 不做字段级混合（需求 §11.1.1 / backtask B2 硬性语义表第 5 条）。
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
不过滤 source.status（停用源不影响存量策略取价）；窗口内无价的元素不出现在结果集中（不返回 0/NULL 行）。
例外/默认合并用 CASE WHEN x.id IS NOT NULL 整行判定（非逐字段 COALESCE），语义 = 例外存在则整行取例外，
否则整行取默认，不做字段级混合（2026-07-23 测试返修，V358 更正 V357 的 COALESCE 写法）。';
