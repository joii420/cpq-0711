-- =====================================================================================
--  CPQ 内网数据库增量升级脚本
--
--  源迁移版本  : V445（V445__task260916_element_price_scale9.sql）
--  升级区间    : Flyway 基线 V444  ->  V445
--  生成日期    : 2026-09-16
--  适用前置    : cpq-init-empty.sql（基线 V439）+ update-260913-v440-v443-record-batch-and-price.sql
--                + update-260916-lookup-name-recipe-fallback.sql
--  执行环境    : Navicat（PG 16.13），整份文件一次性执行
--
--  本次改了什么（一件事）
--    V445  客户元素单价取价函数 f_customer_element_price(text, date) 的结果
--          由保留 4 位小数改为保留 9 位小数（task-260916「元素价格支持 9 位小数」）
--          函数体只改 ROUND 的位数这一个数字；签名、返回列、STABLE、LANGUAGE sql 逐字不变
--
--  不做什么：不回写任何数据 —— 已冻结的价格版本、已建报价单的元素单价快照保持原值
--
--  幂等性：全文可重复执行（CREATE OR REPLACE + 固定值 UPDATE）
--  执行顺序：Flyway 基线 -> 函数（文件末尾）-> 自检（只读）
--  本脚本无 DELETE、无表结构改动
--
--  🚨 Navicat 兼容：函数体用单引号包裹而非美元引用，体内 0 个非 ASCII 字符、无 TAB；
--     体内原有的单引号字面量已按 SQL 规则成对转义（如 ''ACTIVE''）；全文 0 个美元符号
-- =====================================================================================

SET search_path = public;


-- =====================================================================================
-- 第 1 节 · Flyway 基线上调 V444 -> V445
--   内网库不跑 Flyway，本行是为了将来万一接上 Quarkus 时不重放中间迁移
-- =====================================================================================

UPDATE flyway_schema_history
   SET version = '445', script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1;


-- =====================================================================================
-- 第 2 节 · V445 —— 客户元素单价取价函数保留 9 位小数
--
--   为什么必须改：元素日价按 12 位存储，但本函数把「取值 × 系数 + 加价」舍入到 4 位，
--   报价单实时取价、生成价格版本时冻结的单价都从这里取值，导致 5 位以上小数的价格丢精度
--
--   兼容性：签名与返回列逐字不变，调用方（f_material_element_price 实时分支、
--   价格调整生成版本、价格对账）零改动，取数视图无需重建
-- =====================================================================================

CREATE OR REPLACE FUNCTION public.f_customer_element_price(p_customer_no text, p_base_date date)
 RETURNS TABLE(element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
 LANGUAGE sql
 STABLE
AS '
WITH def AS (
    SELECT * FROM element_price_strategy
     WHERE customer_no = p_customer_no AND element_code IS NULL AND status = ''ACTIVE''
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
            AND x.status = ''ACTIVE''
      LEFT JOIN def d ON TRUE
     WHERE e.status = ''ACTIVE''
       AND (x.id IS NOT NULL OR d.id IS NOT NULL)
),
win AS (
    SELECT eff.*,
           CASE WHEN eff.method = ''LATEST'' THEN NULL
                ELSE (p_base_date - (eff.window_num || '' '' ||
                       CASE eff.window_unit
                            WHEN ''DAY''   THEN ''day''   WHEN ''WEEK'' THEN ''week''
                            WHEN ''MONTH'' THEN ''month'' ELSE ''year'' END)::interval)::date
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
          WHEN ''LATEST'' THEN (
              SELECT dp.raw_price FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL AND dp.price_date <= p_base_date
               ORDER BY dp.price_date DESC LIMIT 1)
          WHEN ''AVG'' THEN (
              SELECT AVG(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          WHEN ''MAX'' THEN (
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
';


-- =====================================================================================
-- 第 3 节 · 自检（只读，逐条粘贴执行，期望值写在别名里）
-- =====================================================================================

-- 3.1 【期望 445】Flyway 基线已上调
SELECT version AS flyway_baseline_expect_445
  FROM flyway_schema_history
 WHERE installed_rank = 1;

-- 3.2 【期望 1 / 1 / 0】函数只有一个重载；体内含 9 位舍入；体内不再含 4 位舍入
SELECT count(*) AS fcep_overloads_expect_1,
       sum(CASE WHEN p.prosrc LIKE '%ROUND(agg.raw_value * w.factor + w.premium, 9)%' THEN 1 ELSE 0 END) AS scale9_expect_1,
       sum(CASE WHEN p.prosrc LIKE '%ROUND(agg.raw_value * w.factor + w.premium, 4)%' THEN 1 ELSE 0 END) AS scale4_expect_0
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public'
   AND p.proname = 'f_customer_element_price';

-- 3.3 【期望 t / t】签名与返回列未变
SELECT pg_get_function_identity_arguments('public.f_customer_element_price(text,date)'::regprocedure)
         = 'p_customer_no text, p_base_date date' AS args_unchanged_expect_t,
       pg_get_function_result('public.f_customer_element_price(text,date)'::regprocedure)
         = 'TABLE(element_code character varying, unit_price numeric, currency character varying, price_unit character varying)'
         AS result_unchanged_expect_t;

-- 3.4 【期望 1 行，值为 0】冒烟：函数可被真实调用且不报错（不存在的客户返回 0 行）
SELECT count(*) AS smoke_rows_expect_0
  FROM f_customer_element_price('__NO_SUCH_CUSTOMER__', CURRENT_DATE);
