-- ============================================================
-- V255: v1.2 组件 SQL 视图创建 — 20 个 component_sql_view 行
--
-- 全部 scope=COMPONENT，status=ACTIVE
-- sql_template FROM V6 表（material_master / material_bom_item / element_bom_item /
--   labor_rate / production_energy / auxiliary_energy / tooling_cost /
--   production_consumable / packaging_consumable / unit_price / fee_config / plating_scheme）
--
-- 幂等：ON CONFLICT (component_id, sql_view_name) DO UPDATE
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V255: 开始创建 20 个 v1.2 组件 SQL 视图'; END $$;

-- ── 2.1 v12_part_mapping（客户料号对应关系）─────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_part_mapping'; END $$;
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

-- ── 2.2 v12_raw_bom（来料BOM）──────────────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_raw_bom'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_raw_bom',
    $SQL$SELECT
    bi.material_no                                              AS hf_part_no,
    cc.id                                                       AS customer_id,
    bi.seq_no                                                   AS seq_no,
    bi.component_no                                             AS input_material_no,
    bi.operation_no                                             AS process_no,
    pm.process_name                                             AS process_name,
    bi.composition_qty                                          AS input_qty,
    bi.issue_unit                                               AS input_unit,
    bi.base_qty                                                 AS output_qty,
    bi.issue_unit                                               AS output_unit,
    CAST(COALESCE(bi.scrap_rate, 0) * 100 AS NUMERIC(10,4))    AS output_loss_rate,
    bi.fixed_scrap                                              AS fixed_loss_qty,
    CAST(COALESCE(bi.scrap_rate, 0) * 100 AS NUMERIC(10,4))    AS loss_rate
FROM material_bom_item bi
LEFT JOIN process_master pm ON pm.process_no = bi.operation_no
LEFT JOIN customer        cc ON cc.code      = bi.customer_no
WHERE bi.system_type = 'QUOTE'
  AND (bi.characteristic IS NULL OR bi.characteristic <> 'ASSEMBLY')$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"input_material_no","dataType":"varchar","nullable":true},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"input_qty","dataType":"numeric","nullable":true},{"name":"input_unit","dataType":"varchar","nullable":true},{"name":"output_qty","dataType":"numeric","nullable":true},{"name":"output_unit","dataType":"varchar","nullable":true},{"name":"output_loss_rate","dataType":"numeric","nullable":true},{"name":"fixed_loss_qty","dataType":"numeric","nullable":true},{"name":"loss_rate","dataType":"numeric","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-RAW-BOM-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.3 v12_raw_element_bom（来料与元素BOM）────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_raw_element_bom'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_raw_element_bom',
    $SQL$SELECT
    mbi.material_no                                                AS hf_part_no,
    cc.id                                                          AS customer_id,
    mbi.seq_no                                                     AS material_seq_no,
    ebi.material_no                                                AS input_material_no,
    ebi.seq_no                                                     AS element_seq_no,
    ebi.component_no                                               AS element_code,
    CAST(COALESCE(ebi.content,    0) * 100 AS NUMERIC(10,4))      AS composition_pct,
    CAST(COALESCE(ebi.scrap_rate, 0) * 100 AS NUMERIC(10,4))      AS loss_rate
FROM element_bom_item ebi
JOIN material_bom_item mbi
  ON mbi.system_type = 'QUOTE'
 AND mbi.customer_no = ebi.customer_no
 AND mbi.component_no = ebi.material_no
LEFT JOIN customer cc ON cc.code = mbi.customer_no
WHERE ebi.system_type = 'QUOTE'$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"material_seq_no","dataType":"int4","nullable":true},{"name":"input_material_no","dataType":"varchar","nullable":true},{"name":"element_seq_no","dataType":"int4","nullable":true},{"name":"element_code","dataType":"varchar","nullable":true},{"name":"composition_pct","dataType":"numeric","nullable":true},{"name":"loss_rate","dataType":"numeric","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-RAW-ELEMENT-BOM-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.4 v12_labor_cost（人工成本）──────────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_labor_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_labor_cost',
    $SQL$SELECT
    lr.material_no                              AS hf_part_no,
    lr.process_no                               AS process_no,
    COALESCE(lr.process_name, pm.process_name)  AS process_name,
    lr.standard_labor_rate                      AS unit_price,
    lr.currency                                 AS currency,
    lr.unit                                     AS unit,
    lr.version_no                               AS ref_calc_version,
    NULL::text                                  AS notes
FROM labor_rate lr
LEFT JOIN process_master pm ON pm.process_no = lr.process_no
WHERE lr.material_no IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-LABOR-COST-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.5 v12_depreciation_cost（设备折旧）──────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_depreciation_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_depreciation_cost',
    $SQL$SELECT
    pe.material_no                               AS hf_part_no,
    pe.process_no                                AS process_no,
    COALESCE(pe.process_name, pm.process_name)   AS process_name,
    pe.depreciation_unit_price                   AS unit_price,
    pe.currency                                  AS currency,
    pe.unit                                      AS unit,
    pe.calc_version                              AS ref_calc_version,
    NULL::text                                   AS notes
FROM production_energy pe
LEFT JOIN process_master pm ON pm.process_no = pe.process_no
WHERE pe.depreciation_unit_price IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-DEPRECIATION-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.6 v12_energy_prod_cost（生产设备能耗）────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_energy_prod_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_energy_prod_cost',
    $SQL$SELECT
    pe.material_no                               AS hf_part_no,
    pe.process_no                                AS process_no,
    COALESCE(pe.process_name, pm.process_name)   AS process_name,
    pe.energy_unit_price                         AS unit_price,
    pe.currency                                  AS currency,
    pe.unit                                      AS unit,
    pe.calc_version                              AS ref_calc_version,
    NULL::text                                   AS notes
FROM production_energy pe
LEFT JOIN process_master pm ON pm.process_no = pe.process_no
WHERE pe.energy_unit_price IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-ENERGY-PROD-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.7 v12_energy_aux_cost（辅助设备能耗）─────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_energy_aux_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_energy_aux_cost',
    $SQL$SELECT
    ae.material_no                                AS hf_part_no,
    ae.process_no                                 AS process_no,
    COALESCE(ae.process_name, pm.process_name)    AS process_name,
    ae.non_production_energy_price                AS unit_price,
    ae.currency                                   AS currency,
    ae.unit                                       AS unit,
    ae.calc_version                               AS ref_calc_version,
    NULL::text                                    AS notes
FROM auxiliary_energy ae
LEFT JOIN process_master pm ON pm.process_no = ae.process_no$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-ENERGY-AUX-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.8 v12_tooling_cost（模具工装成本）────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_tooling_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_tooling_cost',
    $SQL$SELECT
    tc.material_no                                AS hf_part_no,
    tc.process_no                                 AS process_no,
    COALESCE(tc.process_name, pm.process_name)    AS process_name,
    tc.seq_no                                     AS seq_no,
    tc.tooling_no                                 AS tooling_no,
    tc.tooling_unit_cost                          AS tooling_unit_cost,
    tc.cycle_output                               AS process_count,
    tc.tool_life                                  AS cycle_count,
    tc.tooling_unit_price                         AS unit_price,
    tc.currency                                   AS currency,
    tc.unit                                       AS unit,
    NULL::text                                    AS notes
FROM tooling_cost tc
LEFT JOIN process_master pm ON pm.process_no = tc.process_no
WHERE COALESCE(tc.is_effective, true) = true$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"tooling_no","dataType":"varchar","nullable":true},{"name":"tooling_unit_cost","dataType":"numeric","nullable":true},{"name":"process_count","dataType":"numeric","nullable":true},{"name":"cycle_count","dataType":"int8","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-TOOLING-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.9 v12_consumable_prod（生产耗材）─────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_consumable_prod'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_consumable_prod',
    $SQL$SELECT
    pc.material_no                                AS hf_part_no,
    pc.process_no                                 AS process_no,
    COALESCE(pc.process_name, pm.process_name)    AS process_name,
    up.pricing_price                              AS unit_price,
    up.currency                                   AS currency,
    pc.usage_unit                                 AS unit,
    up.version_no                                 AS ref_calc_version,
    NULL::text                                    AS notes
FROM production_consumable pc
LEFT JOIN unit_price up
       ON up.system_type = 'PRICING'
      AND up.price_type  = 'CONSUMABLE'
      AND up.code        = pc.consumable_no
      AND COALESCE(up.expire_date, DATE '9999-12-31') >= CURRENT_DATE
LEFT JOIN process_master pm ON pm.process_no = pc.process_no$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-CONSUMABLE-PROD-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.10 v12_packaging（包装材料）──────────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_packaging'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_packaging',
    $SQL$SELECT
    pk.material_no                                AS hf_part_no,
    NULL::varchar                                 AS process_no,
    '包装'::text                                   AS process_name,
    up.pricing_price                              AS unit_price,
    up.currency                                   AS currency,
    pk.usage_unit                                 AS unit,
    up.version_no                                 AS ref_calc_version,
    NULL::text                                    AS notes
FROM packaging_consumable pk
LEFT JOIN unit_price up
       ON up.system_type = 'PRICING'
      AND up.price_type  = 'CONSUMABLE'
      AND up.code        = pk.consumable_no
      AND COALESCE(up.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"text","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-PACKAGING-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.11 v12_incoming_proc（来料加工费）────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_incoming_proc'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_incoming_proc',
    $SQL$SELECT
    up.finished_material_no                       AS hf_part_no,
    up.operation_no                               AS process_no,
    COALESCE(pm.process_name, up.name)            AS process_name,
    up.pricing_price                              AS unit_price,
    up.currency                                   AS currency,
    up.unit                                       AS unit,
    up.version_no                                 AS ref_calc_version,
    NULL::text                                    AS notes
FROM unit_price up
LEFT JOIN process_master pm ON pm.process_no = up.operation_no
WHERE up.system_type = 'PRICING'
  AND up.price_type  = 'COMPONENT'
  AND up.cost_type   = '来料加工费'
  AND up.finished_material_no IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":false},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-INCOMING-PROC-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.12 v12_incoming_other（来料其他费用，比例 ×100）──────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_incoming_other'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_incoming_other',
    $SQL$SELECT
    fc.material_no                                               AS hf_part_no,
    cc.id                                                        AS customer_id,
    NULL::int                                                    AS seq_no,
    fc.dim_input_material_no                                     AS dim_input_material_no,
    fc.dim_sub_seq_no                                            AS dim_sub_seq_no,
    fc.dim_element_name                                          AS dim_element_name,
    CAST(COALESCE(fc.ratio, 0) * 100 AS NUMERIC(10,4))          AS fee_ratio,
    fc.currency                                                  AS currency,
    fc.unit                                                      AS price_unit
FROM fee_config fc
LEFT JOIN customer cc ON cc.code = fc.customer_no
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'OTHER'
  AND fc.charge_basis = 'RATE'
  AND fc.material_no IS NOT NULL
  AND fc.dim_input_material_no IS NOT NULL
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"dim_input_material_no","dataType":"varchar","nullable":true},{"name":"dim_sub_seq_no","dataType":"int4","nullable":true},{"name":"dim_element_name","dataType":"varchar","nullable":true},{"name":"fee_ratio","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"price_unit","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-INCOMING-OTHER-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.13 v12_incoming_fixed_fee（来料其他固定费用）─────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_incoming_fixed_fee'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_incoming_fixed_fee',
    $SQL$SELECT
    fc.material_no                                               AS hf_part_no,
    cc.id                                                        AS customer_id,
    NULL::int                                                    AS seq_no,
    fc.dim_input_material_no                                     AS dim_input_material_no,
    fc.dim_sub_seq_no                                            AS dim_sub_seq_no,
    fc.dim_element_name                                          AS dim_element_name,
    fc.value                                                     AS fee_value,
    fc.currency                                                  AS currency,
    fc.unit                                                      AS price_unit,
    NULL::text                                                   AS price_floating,
    NULL::numeric                                                AS settlement_rise_ratio,
    NULL::numeric                                                AS fixed_rise_value
FROM fee_config fc
LEFT JOIN customer cc ON cc.code = fc.customer_no
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'OTHER'
  AND fc.charge_basis IN ('FIXED', 'PER_UNIT', 'PER_KG')
  AND fc.material_no IS NOT NULL
  AND fc.dim_input_material_no IS NOT NULL
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"dim_input_material_no","dataType":"varchar","nullable":true},{"name":"dim_sub_seq_no","dataType":"int4","nullable":true},{"name":"dim_element_name","dataType":"varchar","nullable":true},{"name":"fee_value","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"price_unit","dataType":"varchar","nullable":true},{"name":"price_floating","dataType":"text","nullable":true},{"name":"settlement_rise_ratio","dataType":"numeric","nullable":true},{"name":"fixed_rise_value","dataType":"numeric","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-INCOMING-FIXED-FEE-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.14 v12_finished_proc（成品加工费）────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_finished_proc'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_finished_proc',
    $SQL$SELECT
    up.finished_material_no                       AS hf_part_no,
    up.operation_no                               AS process_no,
    COALESCE(pm.process_name, up.name)            AS process_name,
    up.pricing_price                              AS unit_price,
    up.currency                                   AS currency,
    up.unit                                       AS unit,
    up.version_no                                 AS ref_calc_version,
    NULL::text                                    AS notes
FROM unit_price up
LEFT JOIN process_master pm ON pm.process_no = up.operation_no
WHERE up.system_type = 'PRICING'
  AND up.price_type  = 'COMPONENT'
  AND up.cost_type   IN ('自制加工费', '组装加工费')
  AND up.finished_material_no IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":false},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-FINISHED-PROC-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.15 v12_finished_other（成品其他比例费用，×100）───────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_finished_other'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_finished_other',
    $SQL$SELECT
    fc.material_no                                               AS hf_part_no,
    cc.id                                                        AS customer_id,
    NULL::int                                                    AS seq_no,
    fc.dim_element_name                                          AS dim_element_name,
    CAST(COALESCE(fc.ratio, 0) * 100 AS NUMERIC(10,4))          AS fee_ratio,
    fc.currency                                                  AS currency,
    fc.unit                                                      AS price_unit
FROM fee_config fc
LEFT JOIN customer cc ON cc.code = fc.customer_no
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'OTHER'
  AND fc.charge_basis = 'RATE'
  AND fc.material_no IS NOT NULL
  AND fc.dim_input_material_no IS NULL
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"dim_element_name","dataType":"varchar","nullable":true},{"name":"fee_ratio","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"price_unit","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-FINISHED-OTHER-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.16 v12_finished_fixed_fee（成品其他固定费用）─────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_finished_fixed_fee'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_finished_fixed_fee',
    $SQL$SELECT
    fc.material_no                                               AS hf_part_no,
    cc.id                                                        AS customer_id,
    NULL::int                                                    AS seq_no,
    fc.dim_element_name                                          AS dim_element_name,
    fc.value                                                     AS fee_value,
    fc.currency                                                  AS currency,
    fc.unit                                                      AS price_unit
FROM fee_config fc
LEFT JOIN customer cc ON cc.code = fc.customer_no
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'OTHER'
  AND fc.charge_basis IN ('FIXED', 'PER_UNIT', 'PER_KG')
  AND fc.material_no IS NOT NULL
  AND fc.dim_input_material_no IS NULL
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"customer_id","dataType":"uuid","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"dim_element_name","dataType":"varchar","nullable":true},{"name":"fee_value","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"price_unit","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-FINISHED-FIXED-FEE-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.17 v12_plating_scheme（电镀方案）──────────────────────────────────
-- 注：plating_scheme.hf_part_no 由 V253 加（NULL 允许）。
-- 数据未 backfill 前此 Tab 显示空（预期行为）。

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_plating_scheme'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_plating_scheme',
    $SQL$SELECT
    ps.hf_part_no                                 AS hf_part_no,
    ps.scheme_no                                  AS plan_code,
    ps.scheme_version                             AS plan_version,
    ps.seq_no                                     AS seq_no,
    ps.plating_element                            AS plating_element,
    ps.plating_area                               AS plating_area,
    ps.plating_thickness                          AS coating_thickness,
    ps.plating_requirement                        AS plating_requirement
FROM plating_scheme ps$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"plan_code","dataType":"varchar","nullable":false},{"name":"plan_version","dataType":"varchar","nullable":false},{"name":"seq_no","dataType":"int4","nullable":false},{"name":"plating_element","dataType":"varchar","nullable":false},{"name":"plating_area","dataType":"numeric","nullable":true},{"name":"coating_thickness","dataType":"numeric","nullable":false},{"name":"plating_requirement","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-PLATING-SCHEME-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.18 v12_plating_cost（电镀成本）────────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_plating_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_plating_cost',
    $SQL$SELECT
    up.finished_material_no                                       AS hf_part_no,
    MAX(up.plating_scheme_no)                                     AS plan_code,
    MAX(up.version_no)                                            AS plan_version,
    MAX(CASE WHEN up.cost_type = '电镀加工费' THEN up.pricing_price END) AS plating_process_fee,
    MAX(CASE WHEN up.cost_type = '电镀材料费' THEN up.pricing_price END) AS plating_material_fee,
    MAX(up.currency)                                              AS currency,
    MAX(up.unit)                                                  AS price_unit,
    CAST(COALESCE(MAX(up.defect_rate), 0) * 100 AS NUMERIC(10,4)) AS defect_rate
FROM unit_price up
WHERE up.system_type = 'PRICING'
  AND up.price_type  = 'COMPONENT'
  AND up.cost_type   IN ('电镀加工费', '电镀材料费')
  AND up.finished_material_no IS NOT NULL
GROUP BY up.finished_material_no$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"plan_code","dataType":"varchar","nullable":true},{"name":"plan_version","dataType":"varchar","nullable":true},{"name":"plating_process_fee","dataType":"numeric","nullable":true},{"name":"plating_material_fee","dataType":"numeric","nullable":true},{"name":"currency","dataType":"varchar","nullable":true},{"name":"price_unit","dataType":"varchar","nullable":true},{"name":"defect_rate","dataType":"numeric","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-PLATING-COST-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.19 v12_outsource_cost（其他外加工成本）────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_outsource_cost'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_outsource_cost',
    $SQL$SELECT
    up.finished_material_no                       AS hf_part_no,
    up.operation_no                               AS process_no,
    COALESCE(pm.process_name, up.name)            AS process_name,
    up.pricing_price                              AS unit_price,
    up.currency                                   AS currency,
    up.unit                                       AS unit,
    up.version_no                                 AS ref_calc_version,
    NULL::text                                    AS notes
FROM unit_price up
LEFT JOIN process_master pm ON pm.process_no = up.operation_no
WHERE up.system_type = 'PRICING'
  AND up.price_type  = 'COMPONENT'
  AND up.cost_type   = '外加工费'
  AND up.finished_material_no IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"process_no","dataType":"varchar","nullable":true},{"name":"process_name","dataType":"varchar","nullable":true},{"name":"unit_price","dataType":"numeric","nullable":false},{"name":"currency","dataType":"varchar","nullable":true},{"name":"unit","dataType":"varchar","nullable":true},{"name":"ref_calc_version","dataType":"varchar","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-OUTSOURCE-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

-- ── 2.20 v12_weight（单重）───────────────────────────────────────────────

DO $$ BEGIN RAISE NOTICE 'V255: 插入 v12_weight'; END $$;
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'v12_weight',
    $SQL$SELECT
    mm.material_no                                AS hf_part_no,
    mm.unit_weight                                AS weight_g_per_pcs,
    NULL::text                                    AS notes
FROM material_master mm
WHERE mm.unit_weight IS NOT NULL$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"weight_g_per_pcs","dataType":"numeric","nullable":true},{"name":"notes","dataType":"text","nullable":true}]'::jsonb,
    ARRAY[]::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-V5-WEIGHT-V12'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template = EXCLUDED.sql_template,
        declared_columns = EXCLUDED.declared_columns,
        updated_at = NOW();

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt
    FROM component_sql_view csv
    JOIN component c ON c.id = csv.component_id
    WHERE c.code LIKE 'COMP-V5-%-V12';
    RAISE NOTICE 'V255: 完成，component_sql_view 中 -V12 组件视图总数=%（期望 20）', v_cnt;
END $$;
