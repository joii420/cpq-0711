-- ============================================================
-- V310: v12_part_mapping 虚拟视图（component_sql_view 行）加 system_type 隔离
--
-- 背景（Spec 1 §5 Chain-1/Chain-5 更正）：V308 给 material_customer_map 加了
-- system_type 列区分 QUOTE（报价）/ PRICING（核价）两条主线，并且报价单侧
-- 因甲登记流程会写入大量 customer_product_no=NULL 的"组件登记行"。
-- v12_part_mapping（component_sql_view.sql_view_name='v12_part_mapping'，
-- 对应组件 COMP-V5-PART-MAPPING-V12）原 LEFT JOIN 未区分 system_type，
-- 会把 PRICING 行和甲登记行也当作候选客户产品映射带出来，需要收窄。
--
-- 说明：v12_part_mapping 不是真实 Postgres VIEW（工程内已确认全库无
-- `CREATE VIEW v12_part_mapping` / `v_c_part_mapping_merged`，V285 已把历史
-- 同名真实视图清理干净），而是 component_sql_view 表里的一行"虚拟视图"配置，
-- 由应用层 SQL 编译器在查询时以 `$v12_part_mapping` 形式展开（详见
-- docs/配置中心架构.md）。因此本迁移不做 DROP VIEW CASCADE，而是沿用
-- V255 同款 UPSERT 手法原地更新 sql_template。
--
-- 全工程 grep 确认：没有其它 component_sql_view.sql_template 把
-- `$v12_part_mapping` 当嵌套子视图引用（V254 里出现的 '$v12_part_mapping'
-- 只是 v1.1→v1.2 字段路径文本替换的目标串，不是 SQL FROM 引用），
-- 故无下游虚拟视图需要级联重建。
--
-- 幂等：与 V255 同款 ON CONFLICT (component_id, sql_view_name) DO UPDATE；
-- 若 COMP-V5-PART-MAPPING-V12 组件在当前环境尚不存在（本仓库落地时确认
-- 尚未存在，属未激活的 v1.2 并行分支），本迁移的 INSERT ... SELECT 匹配
-- 0 行，安全空跑，不影响任何现存数据。
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V310: 更新 v12_part_mapping sql_template，加 system_type 过滤'; END $$;

INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_part_mapping',
    $SQL$SELECT
    mm.material_no                          AS hf_part_no,
    cc.id                                   AS customer_id,
    mcm.customer_material_name              AS customer_part_name,
    mcm.customer_product_no                 AS customer_product_no,
    mcm.customer_drawing_no                 AS customer_drawing_no,
    mcm.payment_method                      AS payment_method,
    mcm.base_currency                       AS base_currency,
    mcm.quote_currency                      AS quote_currency
FROM material_master mm
LEFT JOIN material_customer_map mcm ON mcm.material_no = mm.material_no
                                    AND mcm.system_type = 'QUOTE'
                                    AND mcm.customer_product_no IS NOT NULL
LEFT JOIN customer               cc  ON cc.code        = mcm.customer_no$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"customer_part_name","dataType":"varchar","nullable":true},{"name":"customer_product_no","dataType":"varchar","nullable":true},{"name":"customer_drawing_no","dataType":"varchar","nullable":true},{"name":"payment_method","dataType":"varchar","nullable":true},{"name":"base_currency","dataType":"varchar","nullable":true},{"name":"quote_currency","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-PART-MAPPING-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();
