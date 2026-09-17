-- task-260916 element price scale 9 (B-1, AC-1~AC-6)
-- Redefine f_customer_element_price(text, date): unit_price ROUND(..., 4) -> ROUND(..., 9).
-- Base text = live definition from pg_get_functiondef (md5 0b55508cabf384f70437c58c0e8990d3 on cpq_db_0724 / cpq_db_test).
-- Only change: the ROUND scale digit. Signature, return columns, STABLE, LANGUAGE sql unchanged.
-- No data backfill: existing quotation snapshots and frozen price versions are intentionally untouched (AC-6, D-5, D-6).

CREATE OR REPLACE FUNCTION public.f_customer_element_price(p_customer_no text, p_base_date date)
 RETURNS TABLE(element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
 LANGUAGE sql
 STABLE
AS $function$
WITH def AS (
    SELECT * FROM element_price_strategy
     WHERE customer_no = p_customer_no AND element_code IS NULL AND status = 'ACTIVE'
     LIMIT 1
),
eff AS (
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
       AND (x.id IS NOT NULL OR d.id IS NOT NULL)
),
win AS (
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
       ROUND(agg.raw_value * w.factor + w.premium, 9) AS unit_price,
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
        (SELECT dp.currency   FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS currency,
        (SELECT dp.price_unit FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS price_unit
  ) agg
 WHERE agg.raw_value IS NOT NULL;
$function$;
