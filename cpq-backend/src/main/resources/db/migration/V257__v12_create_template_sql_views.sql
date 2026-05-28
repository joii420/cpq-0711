-- ============================================================
-- V257: v1.2 模板 SQL 视图创建 — 7 个 template_sql_view 行
--
-- 替换 v1.1 中的 v_costing_summary_full / v_c_summary_agg PG 视图直引
-- 全部 scope=LOCAL，status=ACTIVE
-- 按 (template_id, sql_view_name) 幂等（ON CONFLICT DO UPDATE）
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V257: 开始创建 7 个 v1.2 模板 SQL 视图'; END $$;

DO $$
DECLARE
    v_id UUID;
BEGIN
    SELECT id INTO v_id
    FROM template
    WHERE template_series_id = 'ca11f33d-6424-437d-af3f-25ea01674fcd'
      AND version = 'v1.2'
      AND status  = 'DRAFT'
    LIMIT 1;

    IF v_id IS NULL THEN
        RAISE NOTICE 'V257: v1.2 模板未找到，跳过（V256 可能未执行或已存在）';
        RETURN;
    END IF;

    RAISE NOTICE 'V257: 使用 v1.2 模板 id=%', v_id;

    -- ── 3.1 summary_part（packaging_fee / incoming_fixed_fee / outsource_fee /
    --                      freight_fee / customs_fee / currency_label / weight_unit_label）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_part'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_part',
        $SQL$WITH base_parts AS (
    SELECT material_no AS hf_part_no FROM material_master
),
pkg_agg AS (
    SELECT pk.material_no AS hf_part_no, SUM(up.pricing_price) AS packaging_fee
    FROM packaging_consumable pk
    JOIN unit_price up
      ON up.system_type = 'PRICING'
     AND up.price_type  = 'CONSUMABLE'
     AND up.code        = pk.consumable_no
     AND COALESCE(up.expire_date, DATE '9999-12-31') >= CURRENT_DATE
    GROUP BY pk.material_no
),
ifix_agg AS (
    SELECT material_no AS hf_part_no, SUM(value) AS incoming_fixed_fee
    FROM fee_config
    WHERE system_type  = 'PRICING'
      AND biz_type     = 'OTHER'
      AND charge_basis IN ('FIXED', 'PER_UNIT', 'PER_KG')
      AND dim_input_material_no IS NOT NULL
    GROUP BY material_no
),
outsource_agg AS (
    SELECT finished_material_no AS hf_part_no, SUM(pricing_price) AS outsource_fee
    FROM unit_price
    WHERE system_type = 'PRICING'
      AND price_type  = 'COMPONENT'
      AND cost_type   = '外加工费'
    GROUP BY finished_material_no
),
ffix_agg AS (
    SELECT
        material_no AS hf_part_no,
        SUM(CASE WHEN dim_element_name LIKE '%运费%' THEN value ELSE 0 END) AS freight_fee,
        SUM(CASE WHEN dim_element_name LIKE '%清关%' THEN value ELSE 0 END) AS customs_fee
    FROM fee_config
    WHERE system_type  = 'PRICING'
      AND biz_type     IN ('FREIGHT', 'CUSTOMS')
      AND charge_basis IN ('FIXED', 'PER_UNIT', 'PER_KG')
    GROUP BY material_no
)
SELECT
    bp.hf_part_no,
    COALESCE(pkg.packaging_fee,       0)   AS packaging_fee,
    COALESCE(ifix.incoming_fixed_fee, 0)   AS incoming_fixed_fee,
    COALESCE(o.outsource_fee,         0)   AS outsource_fee,
    COALESCE(ff.freight_fee,          0)   AS freight_fee,
    COALESCE(ff.customs_fee,          0)   AS customs_fee,
    'CNY'::varchar(10)                     AS currency_label,
    'KG'::varchar(10)                      AS weight_unit_label
FROM base_parts bp
LEFT JOIN pkg_agg       pkg  ON pkg.hf_part_no  = bp.hf_part_no
LEFT JOIN ifix_agg      ifix ON ifix.hf_part_no = bp.hf_part_no
LEFT JOIN outsource_agg o    ON o.hf_part_no    = bp.hf_part_no
LEFT JOIN ffix_agg      ff   ON ff.hf_part_no   = bp.hf_part_no$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"packaging_fee","dataType":"numeric","nullable":true},{"name":"incoming_fixed_fee","dataType":"numeric","nullable":true},{"name":"outsource_fee","dataType":"numeric","nullable":true},{"name":"freight_fee","dataType":"numeric","nullable":true},{"name":"customs_fee","dataType":"numeric","nullable":true},{"name":"currency_label","dataType":"varchar","nullable":false},{"name":"weight_unit_label","dataType":"varchar","nullable":false}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.2 summary_material（pure_material_cost / material_loss_cost /
    --                          process_fee_total / incoming_other_fee /
    --                          incoming_process_fee / recycle_cost）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_material'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_material',
        $SQL$WITH element_priced AS (
    SELECT
        ebi.material_no                              AS hf_part_no,
        ebi.component_no                             AS element_code,
        COALESCE(ebi.content,     0)                 AS composition_pct,
        COALESCE(ebi.scrap_rate,  0)                 AS loss_rate,
        up.pricing_price                             AS element_price
    FROM element_bom_item ebi
    LEFT JOIN unit_price up
           ON up.system_type = 'PRICING'
          AND up.price_type  = 'ELEMENT'
          AND up.code        = ebi.component_no
          AND COALESCE(up.expire_date, DATE '9999-12-31') >= CURRENT_DATE
    WHERE ebi.system_type = 'QUOTE'
),
pure_agg AS (
    SELECT
        hf_part_no,
        SUM(composition_pct * element_price)              AS pure_material_cost,
        SUM(composition_pct * loss_rate * element_price)  AS material_loss_cost
    FROM element_priced
    GROUP BY hf_part_no
),
proc_agg AS (
    SELECT finished_material_no AS hf_part_no, SUM(pricing_price) AS process_fee_total
    FROM unit_price
    WHERE system_type = 'PRICING'
      AND price_type  = 'COMPONENT'
      AND cost_type   IN ('来料加工费', '自制加工费', '组装加工费')
    GROUP BY finished_material_no
),
incoming_other_agg AS (
    SELECT material_no AS hf_part_no, SUM(ratio) AS incoming_other_fee
    FROM fee_config
    WHERE system_type = 'PRICING'
      AND biz_type    = 'OTHER'
      AND charge_basis = 'RATE'
      AND dim_input_material_no IS NOT NULL
    GROUP BY material_no
),
incoming_proc_agg AS (
    SELECT finished_material_no AS hf_part_no, SUM(pricing_price) AS incoming_process_fee
    FROM unit_price
    WHERE system_type = 'PRICING'
      AND price_type  = 'COMPONENT'
      AND cost_type   = '来料加工费'
    GROUP BY finished_material_no
),
recycle_agg AS (
    SELECT material_no AS hf_part_no, 0::numeric AS recycle_cost
    FROM element_bom_item
    WHERE system_type = 'QUOTE'
    GROUP BY material_no
)
SELECT
    mm.material_no                                 AS hf_part_no,
    COALESCE(pa.pure_material_cost,   0)           AS pure_material_cost,
    COALESCE(pa.material_loss_cost,   0)           AS material_loss_cost,
    COALESCE(prc.process_fee_total,   0)           AS process_fee_total,
    COALESCE(io.incoming_other_fee,   0)           AS incoming_other_fee,
    COALESCE(ip.incoming_process_fee, 0)           AS incoming_process_fee,
    COALESCE(r.recycle_cost,          0)           AS recycle_cost
FROM material_master mm
LEFT JOIN pure_agg           pa  ON pa.hf_part_no  = mm.material_no
LEFT JOIN proc_agg           prc ON prc.hf_part_no = mm.material_no
LEFT JOIN incoming_other_agg io  ON io.hf_part_no  = mm.material_no
LEFT JOIN incoming_proc_agg  ip  ON ip.hf_part_no  = mm.material_no
LEFT JOIN recycle_agg        r   ON r.hf_part_no   = mm.material_no$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"pure_material_cost","dataType":"numeric","nullable":true},{"name":"material_loss_cost","dataType":"numeric","nullable":true},{"name":"process_fee_total","dataType":"numeric","nullable":true},{"name":"incoming_other_fee","dataType":"numeric","nullable":true},{"name":"incoming_process_fee","dataType":"numeric","nullable":true},{"name":"recycle_cost","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.3 summary_mgmt_fee_ratio（管理费比例）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_mgmt_fee_ratio'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_mgmt_fee_ratio',
        $SQL$SELECT
    fc.material_no                         AS hf_part_no,
    COALESCE(fc.ratio, 0)                  AS mgmt_fee_ratio
FROM fee_config fc
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'OTHER'
  AND fc.fee_no       IN ('MGMT', 'MGMT_FEE', '管理费')
  AND fc.charge_basis = 'RATE'
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"mgmt_fee_ratio","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.4 summary_finance_fee_ratio（财务费比例）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_finance_fee_ratio'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_finance_fee_ratio',
        $SQL$SELECT
    fc.material_no                         AS hf_part_no,
    COALESCE(fc.ratio, 0)                  AS finance_fee_ratio
FROM fee_config fc
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     = 'BANK'
  AND fc.charge_basis = 'RATE'
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"finance_fee_ratio","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.5 summary_profit_tax_ratio（利润比例 + 税率）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_profit_tax_ratio'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_profit_tax_ratio',
        $SQL$SELECT
    fc.material_no                                                        AS hf_part_no,
    MAX(CASE WHEN fc.biz_type = 'PROFIT' THEN COALESCE(fc.ratio, 0) END) AS profit_ratio,
    MAX(CASE WHEN fc.biz_type = 'TAX'    THEN COALESCE(fc.ratio, 0) END) AS tax_ratio
FROM fee_config fc
WHERE fc.system_type  = 'PRICING'
  AND fc.biz_type     IN ('PROFIT', 'TAX')
  AND fc.charge_basis = 'RATE'
  AND COALESCE(fc.expire_date, DATE '9999-12-31') >= CURRENT_DATE
GROUP BY fc.material_no$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":true},{"name":"profit_ratio","dataType":"numeric","nullable":true},{"name":"tax_ratio","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.6 summary_plating_cost（电镀成本聚合）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_plating_cost'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_plating_cost',
        $SQL$SELECT
    up.finished_material_no                                                AS hf_part_no,
    MAX(CASE WHEN up.cost_type = '电镀加工费' THEN up.pricing_price END)  AS plating_process_fee,
    MAX(CASE WHEN up.cost_type = '电镀材料费' THEN up.pricing_price END)  AS plating_material_fee,
    COALESCE(MAX(up.defect_rate), 0)                                       AS plating_defect_rate
FROM unit_price up
WHERE up.system_type = 'PRICING'
  AND up.price_type  = 'COMPONENT'
  AND up.cost_type   IN ('电镀加工费', '电镀材料费')
GROUP BY up.finished_material_no$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"plating_process_fee","dataType":"numeric","nullable":true},{"name":"plating_material_fee","dataType":"numeric","nullable":true},{"name":"plating_defect_rate","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    -- ── 3.7 summary_unit_weight_rate（单重 + CNY/USD 汇率）
    DO $inner$ BEGIN RAISE NOTICE 'V257: 插入 summary_unit_weight_rate'; END $inner$;
    INSERT INTO template_sql_view
        (id, template_id, sql_view_name, sql_template, declared_columns,
         required_variables, scope, status, created_at, updated_at)
    VALUES (
        gen_random_uuid(), v_id, 'summary_unit_weight_rate',
        $SQL$SELECT
    mm.material_no                                  AS hf_part_no,
    mm.unit_weight                                  AS unit_weight_g,
    (SELECT er.rate
       FROM exchange_rate_v6 er
      WHERE er.base_currency   = 'CNY'
        AND er.target_currency = 'USD'
        AND COALESCE(er.expire_date, DATE '9999-12-31') >= CURRENT_DATE
      ORDER BY er.effective_date DESC NULLS LAST
      LIMIT 1)                                      AS exchange_rate_to_usd
FROM material_master mm$SQL$,
        '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"unit_weight_g","dataType":"numeric","nullable":true},{"name":"exchange_rate_to_usd","dataType":"numeric","nullable":true}]'::jsonb,
        ARRAY[]::text[],
        'LOCAL', 'ACTIVE', NOW(), NOW()
    )
    ON CONFLICT (template_id, sql_view_name) DO UPDATE
        SET sql_template     = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at       = NOW();

    RAISE NOTICE 'V257: 7 个 template SQL 视图创建完成，template_id=%', v_id;
END $$;
