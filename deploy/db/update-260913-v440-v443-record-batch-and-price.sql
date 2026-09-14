-- =====================================================================================
--  CPQ 内网数据库增量升级脚本
--
--  源迁移版本  : V440 / V441 / V442 / V443
--  升级区间    : Flyway 基线 V439  ->  V443
--  生成日期    : 2026-09-13
--  适用前置    : 已用 deploy/db/cpq-init-empty.sql（基线 V439）建库
--  执行环境    : Navicat（PG 16.13），整份文件一次性执行
--
--  本次改了什么（四件事）
--    V440  核价侧元素取价改为客户级单键，不再按销售料号连接（修正核价总额系统性偏低）
--    V441  语义图新增行级维度角色 ROW_SCOPE，标记在 CUSTOMER_PART(QUOTE).customer_product_no
--    V442  13 张 ds_quote_*_record 表：quotation_id 放开可空，新增 import_batch_id + 索引
--    V443  取价函数 f_material_element_price 补 _record 分支（新料号元素单价不再整列 NULL）
--
--  幂等性：全文可重复执行。DDL 一律 IF NOT EXISTS / IF EXISTS，配置更新带守卫条件
--  执行顺序：配置 -> 表结构 -> Flyway 基线 -> 函数 -> 自检（只读）
--            函数区刻意放在所有写操作之后，把唯一的高风险点隔离在末尾
--
--  🚨 本脚本含一条 DELETE（第 1 节第 3 段），命中面 2 行，已带精确 WHERE
--     逆操作（重新 INSERT 两行边键）写在该段注释里
-- =====================================================================================

SET search_path = public;


-- =====================================================================================
-- 第 1 节 · V440 —— 核价侧元素取价改客户级单键
-- =====================================================================================

-- 1.1 两个核价方言的 FUNCTION 节点换函数签名（QUOTE 方言不在命中面内）
UPDATE semantic_node
   SET func_signature = 'f_customer_element_price(:customerCode, :priceBaseDate)',
       display_name   = '价格策略 f_customer_element_price',
       note = 'repair-260910（A0-1，用户 2026-09-10 裁决）：别名固定为 cep。'
              || '取价键已从「element_code + sales_material_no 双键」降为「element_code 单键」，'
              || '函数由 f_material_element_price 换为客户级的 f_customer_element_price。'
              || '根因：核价 BOM 树里的中间件/内部件业务上没有销售料号，桥接列恒为 NULL，'
              || '第二键恒不成立，元素单价恒空。',
       updated_at = now()
 WHERE node_key = 'FUNC_ELEMENT_PRICE'
   AND dialect IN ('COST_BASIC', 'COST_DETAIL');

-- 1.2 两条核价 PRICE 边的注释同步更正
UPDATE semantic_edge e
   SET note = 'repair-260910：' || fn.dialect || ' ELEMENT_BOM -> FUNC_ELEMENT_PRICE（单键：element_code）。'
              || '原双键（element_code + sales_material_no）已降为单键，见 A0-1 裁决。',
       updated_at = now()
  FROM semantic_node fn, semantic_node tn
 WHERE fn.id = e.from_node_id
   AND tn.id = e.to_node_id
   AND e.edge_kind = 'PRICE'
   AND fn.node_key = 'ELEMENT_BOM'
   AND tn.node_key = 'FUNC_ELEMENT_PRICE'
   AND fn.dialect IN ('COST_BASIC', 'COST_DETAIL');

-- 1.3 删除两条核价 PRICE 边的 seq=1 边键（sales_material_no -> material_no）
--     影响面：2 行（COST_BASIC 与 COST_DETAIL 各 1 行），seq=0 保留，QUOTE 侧不受影响
--     幂等：重复执行删 0 行
--     逆操作：向 semantic_edge_key 重新插入这两条边的 seq=1 行
--             (left_column='sales_material_no', right_column='material_no')
DELETE FROM semantic_edge_key k
 WHERE k.seq = 1
   AND k.edge_id IN (
        SELECT e.id
          FROM semantic_edge e
          JOIN semantic_node fn ON fn.id = e.from_node_id
          JOIN semantic_node tn ON tn.id = e.to_node_id
         WHERE e.edge_kind = 'PRICE'
           AND fn.node_key = 'ELEMENT_BOM'
           AND tn.node_key = 'FUNC_ELEMENT_PRICE'
           AND fn.dialect IN ('COST_BASIC', 'COST_DETAIL'));


-- =====================================================================================
-- 第 2 节 · V441 —— 语义图新增行级维度角色 ROW_SCOPE
--   只对 CUSTOMER_PART(QUOTE).customer_product_no 这一列追加角色，既有值一个都不动
--   幂等：NOT ('ROW_SCOPE' = ANY(roles)) 守卫，重复执行不会追加出两个 ROW_SCOPE
-- =====================================================================================

UPDATE semantic_node_column c
   SET roles      = array_append(c.roles, 'ROW_SCOPE'),
       updated_by = 'task-260911',
       updated_at = now()
  FROM semantic_node n
 WHERE c.node_id = n.id
   AND n.node_key = 'CUSTOMER_PART'
   AND n.dialect  = 'QUOTE'
   AND c.db_column = 'customer_product_no'
   AND NOT ('ROW_SCOPE' = ANY(c.roles));


-- =====================================================================================
-- 第 3 节 · V442 —— 13 张 ds_quote_*_record 表的两段式归属
--   ① quotation_id 放开可空：导入落库时本单还不存在
--   ② 新增 import_batch_id uuid + 索引：对应 import_record.id，建单时回填为 quotation_id
--   🚫 刻意不建外键到 import_record，避免删导入记录级联掉业务数据
--
--   ⚠️ 原迁移用 DO 块动态遍历，本脚本按 Navicat 兼容规则展开为 13 组静态语句
--      表清单取自源库实查（cpq_db_0724，2026-09-13），与 Registry 里 versioned=true 的
--      sheet 的 recordTable() 一一对应
-- =====================================================================================

ALTER TABLE ds_quote_annual_discount_record      ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_annual_discount_record      ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_annual_discount_record_batch      ON ds_quote_annual_discount_record (import_batch_id);

ALTER TABLE ds_quote_assembly_fee_annual_record  ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_assembly_fee_annual_record  ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_assembly_fee_annual_record_batch  ON ds_quote_assembly_fee_annual_record (import_batch_id);

ALTER TABLE ds_quote_assembly_fee_record         ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_assembly_fee_record         ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_assembly_fee_record_batch         ON ds_quote_assembly_fee_record (import_batch_id);

ALTER TABLE ds_quote_element_bom_record          ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_element_bom_record          ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_element_bom_record_batch          ON ds_quote_element_bom_record (import_batch_id);

ALTER TABLE ds_quote_finished_other_fee_record   ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_finished_other_fee_record   ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_finished_other_fee_record_batch   ON ds_quote_finished_other_fee_record (import_batch_id);

ALTER TABLE ds_quote_incoming_annual_record      ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_incoming_annual_record      ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_incoming_annual_record_batch      ON ds_quote_incoming_annual_record (import_batch_id);

ALTER TABLE ds_quote_incoming_fixed_fee_record   ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_incoming_fixed_fee_record   ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_incoming_fixed_fee_record_batch   ON ds_quote_incoming_fixed_fee_record (import_batch_id);

ALTER TABLE ds_quote_incoming_other_fee_record   ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_incoming_other_fee_record   ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_incoming_other_fee_record_batch   ON ds_quote_incoming_other_fee_record (import_batch_id);

ALTER TABLE ds_quote_incoming_recovery_record    ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_incoming_recovery_record    ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_incoming_recovery_record_batch    ON ds_quote_incoming_recovery_record (import_batch_id);

ALTER TABLE ds_quote_material_bom_record         ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_material_bom_record         ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_material_bom_record_batch         ON ds_quote_material_bom_record (import_batch_id);

ALTER TABLE ds_quote_plating_fee_record          ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_plating_fee_record          ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_plating_fee_record_batch          ON ds_quote_plating_fee_record (import_batch_id);

ALTER TABLE ds_quote_self_process_fee_record     ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_self_process_fee_record     ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_self_process_fee_record_batch     ON ds_quote_self_process_fee_record (import_batch_id);

ALTER TABLE ds_quote_sub_component_fee_record    ALTER COLUMN quotation_id DROP NOT NULL;
ALTER TABLE ds_quote_sub_component_fee_record    ADD COLUMN IF NOT EXISTS import_batch_id uuid;
CREATE INDEX IF NOT EXISTS idx_ds_quote_sub_component_fee_record_batch    ON ds_quote_sub_component_fee_record (import_batch_id);


-- =====================================================================================
-- 第 4 节 · Flyway 基线上调 V439 -> V443
--   内网库不跑 Flyway，本行是为了将来万一接上 Quarkus 时不重放中间迁移
--   中间迁移里有非幂等语句，重放会因「表已存在 / 列不存在」导致服务起不来
-- =====================================================================================

UPDATE flyway_schema_history
   SET version = '443', script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1;


-- =====================================================================================
-- 第 5 节 · V443 —— 取价函数补 _record 分支
--
--   为什么必须改：带版本表的报价数据只落 ds_quote_*_record 不落主表，而本函数的
--   candidate_materials 是自己读表的，文本改写够不到编译在库里的函数体。同一次查询里
--   同一份 BOM 被读两遍，函数体里那遍只读主表，新料号在候选名单里整体缺席，
--   结果是元素单价整列 NULL，往下所有用单价的公式全塌
--
--   兼容性：签名与返回列逐字不变，调用方零改动。第三参为 NULL 时新增的两支恒零行，
--   核价侧与存量单的行为与改动前逐字节一致
--
--   🚨 Navicat 兼容：函数体用单引号包裹而非美元引用，体内 0 个非 ASCII 字符、无 TAB，
--      且整节放在文件最末尾。原迁移文件里的中文注释已全部移到本节外面
--      体内原有的两处 '_GLOBAL_' 已按 SQL 规则转义为 ''_GLOBAL_''
-- =====================================================================================

CREATE OR REPLACE FUNCTION public.f_material_element_price(
    p_customer_no text,
    p_base_date date,
    p_pending_quotation_id uuid)
 RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
 LANGUAGE sql
 STABLE
AS '
WITH pointers AS (
    SELECT material_no, version_id
      FROM material_price_version_ref
     WHERE customer_no = p_customer_no
),
versioned AS (
    SELECT p.material_no, i.element_code, i.current_price AS unit_price,
           i.currency, i.price_unit
      FROM pointers p
      JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL
),
candidate_materials AS (
    SELECT material_no FROM material_bom_item
     WHERE customer_no IN (p_customer_no, ''_GLOBAL_'')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, ''_GLOBAL_'')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    UNION
    SELECT material_no FROM ds_quote_material_bom
     WHERE customer_no = p_customer_no
    UNION
    SELECT material_no FROM ds_quote_element_bom
     WHERE customer_no = p_customer_no
    UNION
    SELECT material_no FROM ds_quote_material_bom_record
     WHERE quotation_id = p_pending_quotation_id
    UNION
    SELECT material_no FROM ds_quote_element_bom_record
     WHERE quotation_id = p_pending_quotation_id
),
realtime AS (
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm
      CROSS JOIN f_customer_element_price(p_customer_no, p_base_date) f
)
SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit
  FROM versioned v
UNION ALL
SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
  FROM realtime r
  LEFT JOIN versioned v2
    ON v2.material_no = r.material_no AND v2.element_code = r.element_code
 WHERE v2.material_no IS NULL;
';


-- =====================================================================================
-- 第 6 节 · 自检（全部只读，可整段粘贴执行，期望值写在每条的注释里）
--   任何一条对不上就说明该节没生效，不要继续往下用
-- =====================================================================================

-- 6.1 【期望 2】V440 核价两节点已换成客户级函数
SELECT count(*) AS v440_new_func_expect_2
  FROM semantic_node
 WHERE node_key = 'FUNC_ELEMENT_PRICE'
   AND dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND func_signature = 'f_customer_element_price(:customerCode, :priceBaseDate)';

-- 6.2 【期望 0】核价侧不应再残留旧的料号级函数
SELECT count(*) AS v440_old_func_expect_0
  FROM semantic_node
 WHERE node_key = 'FUNC_ELEMENT_PRICE'
   AND dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND func_signature LIKE '%f_material_element_price%';

-- 6.3 【期望 2】核价 PRICE 边键总数（两条边各剩 1 个 seq=0 键）
SELECT count(*) AS v440_cost_keys_expect_2
  FROM semantic_edge_key k
  JOIN semantic_edge e  ON e.id  = k.edge_id
  JOIN semantic_node fn ON fn.id = e.from_node_id
  JOIN semantic_node tn ON tn.id = e.to_node_id
 WHERE e.edge_kind = 'PRICE'
   AND fn.node_key = 'ELEMENT_BOM'
   AND tn.node_key = 'FUNC_ELEMENT_PRICE'
   AND fn.dialect IN ('COST_BASIC', 'COST_DETAIL');

-- 6.4 【期望 2】防误伤：QUOTE 侧边键必须原样保留两个
SELECT count(*) AS quote_keys_untouched_expect_2
  FROM semantic_edge_key k
  JOIN semantic_edge e  ON e.id  = k.edge_id
  JOIN semantic_node fn ON fn.id = e.from_node_id
  JOIN semantic_node tn ON tn.id = e.to_node_id
 WHERE e.edge_kind = 'PRICE'
   AND tn.node_key = 'FUNC_ELEMENT_PRICE'
   AND fn.dialect  = 'QUOTE';

-- 6.5 【期望 1】防误伤：QUOTE 侧仍用料号级函数
SELECT count(*) AS quote_func_untouched_expect_1
  FROM semantic_node
 WHERE node_key = 'FUNC_ELEMENT_PRICE'
   AND dialect  = 'QUOTE'
   AND func_signature = 'f_material_element_price(:customerCode, :priceBaseDate)';

-- 6.6 【期望 1】V441 ROW_SCOPE 已打上，且只打了一个（不是 {ROW_SCOPE,ROW_SCOPE}）
SELECT count(*) AS v441_rowscope_cols_expect_1,
       sum(CASE WHEN cardinality(array_positions(c.roles, 'ROW_SCOPE')) > 1 THEN 1 ELSE 0 END) AS dup_expect_0
  FROM semantic_node_column c
  JOIN semantic_node n ON n.id = c.node_id
 WHERE n.node_key  = 'CUSTOMER_PART'
   AND n.dialect   = 'QUOTE'
   AND c.db_column = 'customer_product_no'
   AND 'ROW_SCOPE' = ANY(c.roles);

-- 6.7 【期望 13 / 13 / 13】V442 三项：表数、可空的 quotation_id 数、import_batch_id 列数
SELECT count(*) FILTER (WHERE column_name = 'quotation_id')                            AS tables_expect_13,
       count(*) FILTER (WHERE column_name = 'quotation_id'    AND is_nullable = 'YES') AS nullable_expect_13,
       count(*) FILTER (WHERE column_name = 'import_batch_id')                         AS batch_col_expect_13
  FROM information_schema.columns
 WHERE table_schema = 'public'
   AND table_name LIKE 'ds\_quote\_%\_record'
   AND column_name IN ('quotation_id', 'import_batch_id');

-- 6.8 【期望 13】V442 批次索引全部建出
SELECT count(*) AS v442_batch_idx_expect_13
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND indexname LIKE 'idx\_ds\_quote\_%\_batch';

-- 6.9 【期望 443】Flyway 基线已上调
SELECT version AS flyway_baseline_expect_443
  FROM flyway_schema_history
 WHERE installed_rank = 1;

-- 6.10 【期望 2】V443 函数存在两个重载（两参薄壳 + 三参本体）
--      且三参版函数体里必须含 _record 两支，各出现 1 次
SELECT count(*) AS fmep_overloads_expect_2,
       sum(CASE WHEN pg_get_function_identity_arguments(p.oid) LIKE '%uuid%'
                 AND p.prosrc LIKE '%ds_quote_material_bom_record%'
                 AND p.prosrc LIKE '%ds_quote_element_bom_record%'
                THEN 1 ELSE 0 END) AS record_branch_expect_1
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public'
   AND p.proname = 'f_material_element_price';

-- 6.11 【期望 1 行，值为 0】冒烟：函数可被真实调用且不报错（传 NULL 走兼容分支）
--      用的是不存在的客户编码，所以计数为 0 才对。这里验的是「能执行」不是「有数据」
SELECT count(*) AS smoke_call_runs_ok
  FROM f_material_element_price('__SMOKE_TEST__', CURRENT_DATE, NULL::uuid);
