-- ============================================================
-- V270: 修正 COMP-0019「组成件」的 zcj_view SQL 视图
--
-- 背景：COMP-0019 (code=COMP-0019, name=组成件) 的 component_sql_view
--   `zcj_view` 原模板把 height(组成重量) / price(单价) 硬编码为 0，
--   且 WHERE 缺少 customer_no 过滤 —— 违反 AP-53 §10「按料号查 V6 表
--   必须过滤 customer_no = :customerCode，否则跨客户数据叠加成 N 份」。
--
-- 本次修正（FROM V6 表，遵守 AP-53 / docs/方案制定前必读.md §V6 规则）：
--   1. height ← material_master.unit_weight（子件 component_no 的单重）
--   2. price  ← unit_price COMPONENT 口径单价：
--        system_type='QUOTE' AND price_type='COMPONENT'
--        AND code=子件料号 AND finished_material_no=父件料号
--        AND customer_no=:customerCode
--      用 LATERAL + LIMIT 1 取最新有效一行，**杜绝多行 unit_price 导致
--      BOM 行翻倍**（AP-22「(共N项)」反模式）。
--   3. WHERE 增加 asy.customer_no = :customerCode（AP-53 §10）。
--   4. jgf(加工费) 保持 0 —— 该字段 field_type=INPUT_NUMBER，由用户在报价单录入。
--
-- 字段→列映射（COMP-0019 fields.basic_data_path）：
--   原材料组成      $zcj_view.hf_part_no        ← material_bom_item.material_no
--   元件组成        $zcj_view.child_hf_part_no  ← material_bom_item.component_no
--   组成含量%/用量  $zcj_view.qty               ← material_bom_item.composition_qty
--   组成重量(g)     $zcj_view.height            ← material_master.unit_weight
--   单价            $zcj_view.price             ← unit_price.pricing_price (COMPONENT)
--   加工费          $zcj_view.jgf               ← 0 (INPUT_NUMBER 用户录入)
--   单位            $zcj_view.unit              ← material_bom_item.issue_unit
--
-- 幂等：ON CONFLICT (component_id, sql_view_name) DO UPDATE
-- 实施时间: 2026-05-29
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V270: 修正 COMP-0019 zcj_view（真实 height/price + customer 过滤）'; END $$;

INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns,
     required_variables, scope, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    c.id,
    'zcj_view',
    $SQL$SELECT
    asy.material_no                              AS hf_part_no,        -- 原材料组成(父件料号)
    asy.component_no                             AS child_hf_part_no,  -- 元件组成(子件料号)
    COALESCE(mm.material_name, asy.component_no) AS name,              -- 品名
    asy.composition_qty                          AS qty,               -- 组成含量%或用量PCS
    mm.unit_weight                               AS height,            -- 组成重量(g) ← 子件单重
    0::numeric                                   AS jgf,               -- 加工费(INPUT_NUMBER 用户录入)
    up.pricing_price                             AS price,             -- 单价(COMPONENT 口径)
    asy.issue_unit                               AS unit               -- 单位
FROM material_bom_item asy
LEFT JOIN material_master mm
       ON mm.material_no = asy.component_no
LEFT JOIN LATERAL (
    SELECT u.pricing_price
    FROM unit_price u
    WHERE u.system_type        = 'QUOTE'
      AND u.price_type         = 'COMPONENT'
      AND u.code               = asy.component_no
      AND u.finished_material_no = asy.material_no
      AND u.customer_no        = :customerCode
      AND COALESCE(u.expire_date, DATE '9999-12-31') >= CURRENT_DATE
    ORDER BY u.effective_date DESC NULLS LAST, u.version_no DESC NULLS LAST
    LIMIT 1
) up ON TRUE
WHERE asy.system_type    = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
  AND asy.customer_no    = :customerCode$SQL$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"child_hf_part_no","dataType":"varchar","nullable":true},{"name":"name","dataType":"varchar","nullable":true},{"name":"qty","dataType":"numeric","nullable":true},{"name":"height","dataType":"numeric","nullable":true},{"name":"jgf","dataType":"numeric","nullable":true},{"name":"price","dataType":"numeric","nullable":true},{"name":"unit","dataType":"varchar","nullable":true}]'::jsonb,
    ARRAY['customerCode']::text[],
    'COMPONENT',
    'ACTIVE',
    NOW(), NOW()
FROM component c
WHERE c.code = 'COMP-0019'
ON CONFLICT (component_id, sql_view_name) DO UPDATE
    SET sql_template       = EXCLUDED.sql_template,
        declared_columns   = EXCLUDED.declared_columns,
        required_variables = EXCLUDED.required_variables,
        status             = 'ACTIVE',
        updated_at         = NOW();
