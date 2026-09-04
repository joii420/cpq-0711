-- =====================================================================
-- task-260903 · 阶段 B（渲染读取侧）· B-1 / B-2 / B-4
--   （B-3 = 135 段组件 SQL 表名替换，拆到 V411，理由见 V411 抬头）
--
-- 选配主数据从 V6 五表迁往 ds_quote_* 新表体系。B 阶段先让渲染层「两边都能读」：
--   B-1/B-2  建三张兼容视图 = V6 存量 UNION ALL 新表投影（列名/列序/类型逐字对齐 V6）
--   B-4      3 个 v_composite_child_* PG 视图改读兼容视图
--
-- 🚨 三条实施铁律（改本文件前必读）
-- 1) 兼容视图每一列都显式 CAST 到 V6 原始类型「含 typmod」。PG 的 UNION 类型解析只在
--    所有分支 typmod 一致时保留 typmod，否则退化成无长度的 character varying。
--    实测：varchar(20) ∪ varchar(20) → varchar(20)；varchar(20) ∪ varchar(128) → varchar。
--    退化会让 B-4 的 CREATE OR REPLACE VIEW 因「cannot change data type of view column」失败。
-- 2) 新表侧带**反连接**（NOT EXISTS V6 同键行）。需求文档 §4.1 B-AC-1 假设「新表侧为空」，
--    2026-09-03 实测**不成立**：ds_quote_material 已有 42 行 IMPORT 数据且 42/42 与
--    material_master 料号重叠。裸 UNION ALL 会让这 42 个料号在 132 段 SQL 里整体出双份行。
--    反连接 = 存量优先，保证 B-AC-1「与直接查 V6 表逐字相同」在今天就成立。
-- 3) 表名替换用 \m...\M 词边界。PG 正则把下划线算作词内字符，
--    所以 \mmaterial_bom\M 不会命中 material_bom_item，\mmaterial_master\M 也不会
--    命中 v_compat_material_master —— 替换天然幂等，重复执行不会套娃。
-- =====================================================================

-- ── 0. 替换前的 sql_template 原文备份（B-5 快照比对 + 回退用）────────────
CREATE TABLE IF NOT EXISTS component_sql_view_backup_260903 (
    id            uuid PRIMARY KEY,
    sql_view_name varchar(80),
    sql_template  text,
    backed_up_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO component_sql_view_backup_260903 (id, sql_view_name, sql_template)
SELECT id, sql_view_name, sql_template
  FROM component_sql_view
 WHERE sql_template ~ '\m(material_master|material_bom_item|element_bom_item)\M'
ON CONFLICT (id) DO NOTHING;

-- ── 1. B-1 · v_compat_material_master ────────────────────────────────
-- 🚩 material_type 恒 NULL —— ds_quote_material 尚无该列（依赖另一条线的 V409）。
--    V409 落地后必须补一条迁移把本视图的 material_type 改成 m.material_type，
--    否则 A-AC-7（外购件 material_type='外购件'）与「零件/成品」类型在渲染侧全空。
CREATE OR REPLACE VIEW v_compat_material_master AS
SELECT
    v.id,
    v.material_no,
    v.material_name,
    v.specification,
    v.dimension,
    v.old_material_no,
    v.material_type,
    v.usage_property,
    v.unit_weight,
    v.standard_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.material_recipe_id,
    v.config_fingerprint,
    v.production_no,
    v.pending_quotation_id
FROM material_master v
UNION ALL
SELECT
    (md5('dqm:' || m.material_no))::uuid AS id,
    (m.material_no)::character varying(20) AS material_no,
    (m.material_name)::character varying(100) AS material_name,
    (m.specification)::character varying(100) AS specification,
    (m.dimension)::character varying(100) AS dimension,
    (m.old_material_no)::character varying(50) AS old_material_no,
    NULL::character varying(50)        AS material_type,
    NULL::character varying(50)        AS usage_property,
    (m.unit_weight)::numeric(24,12) AS unit_weight,
    NULL::character varying(20)        AS standard_unit,
    (m.created_at)::timestamp(6) with time zone AS created_at,
    (COALESCE(m.updated_at, m.created_at))::timestamp(6) with time zone AS updated_at,
    NULL::uuid                         AS created_by,
    NULL::uuid                         AS updated_by,
    NULL::uuid                         AS material_recipe_id,
    NULL::character varying(80)        AS config_fingerprint,
    (m.production_no)::character varying(32) AS production_no,
    NULL::uuid                         AS pending_quotation_id
FROM ds_quote_material m
WHERE NOT EXISTS (
    SELECT 1 FROM material_master x WHERE x.material_no = m.material_no
);

COMMENT ON VIEW v_compat_material_master IS
  'task-260903 B-1：material_master 存量 UNION ALL ds_quote_material 投影（存量优先反连接）。列名/列序/类型逐字对齐 material_master。';

-- ── 2. B-1 / B-2 · v_compat_material_bom_item ────────────────────────
-- B-2：component_usage_type（材质名）由 LEFT JOIN material_recipe ON code = input_material_no
--      取 symbol。⚠️ 必须 LEFT —— 外购件的 input_material_no 不在 material_recipe 里，
--      INNER 会把外购件行整行吞掉（V6 侧那 24 段 characteristic='OUTSOURCED' 的 SQL 直接空表）。
CREATE OR REPLACE VIEW v_compat_material_bom_item AS
WITH cust_scope AS (
    -- 客户作用域。新表体系没有 customer_no 维度，V6 侧有且 104/135 段组件 SQL 按它过滤，
    -- 所以必须在这里把「哪个客户拥有这个料号」补出来：
    --   ① 客户产品编号直接指向该料号（ds_quote_customer_part.material_no）
    --   ② 该料号是某客户产品的投入料号 —— 组合产品的子件 / 零件的材质料号。
    --      V6 侧当年就是给这些子件各写一份带 customer_no 的行（insertMaterialBomItemV6 /
    --      writeCombomaterialBomV6 都以 customerCode 落库），这里等价还原。
    -- 🚩 api.md §2.1 只写了「JOIN ds_quote_customer_part 取 customer_no」，仅覆盖 ①；
    --    只做 ① 会让组合产品子件的材质/元素行取不到 customer_no 而整体消失（A-AC-8 必挂）。
    SELECT DISTINCT cp.customer_no, cp.material_no
      FROM ds_quote_customer_part cp
     WHERE cp.material_no IS NOT NULL
    UNION
    SELECT DISTINCT cp.customer_no, b.input_material_no AS material_no
      FROM ds_quote_customer_part cp
      JOIN ds_quote_material_bom b ON b.material_no = cp.material_no
     WHERE cp.material_no IS NOT NULL
       AND b.input_material_no IS NOT NULL
)
SELECT
    v.id,
    v.system_type,
    v.customer_no,
    v.material_no,
    v.characteristic,
    v.seq_no,
    v.component_no,
    v.part_no,
    v.effective_datetime,
    v.expire_datetime,
    v.operation_no,
    v.operation_seq,
    v.item_seq,
    v.issue_unit,
    v.composition_qty,
    v.base_qty,
    v.component_usage_type,
    v.feature_mgmt,
    v.upper_limit_pct,
    v.lower_limit_pct,
    v.scrap_batch,
    v.scrap_rate,
    v.fixed_scrap,
    v.issue_location,
    v.issue_storage,
    v.fas_group,
    v.plug_position,
    v.ref_rd_center,
    v.is_optional,
    v.wo_expand_option,
    v.is_purchase_replace,
    v.component_lead_time,
    v.main_substitute,
    v.attached_part,
    v.ecn_no,
    v.use_qty_formula,
    v.qty_formula,
    v.scrap_rate_type,
    v.is_backflush,
    v.is_customer_supply,
    v.defect_rate,
    v.calc_type,
    v.recovery_discount,
    v.recovery_currency,
    v.recovery_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.is_current,
    v.bom_version,
    v.rough_weight,
    v.net_weight,
    v.weight_unit,
    v.production_no,
    v.pending_quotation_id,
    v.pending_supersedes,
    v.material_ratio
FROM material_bom_item v
UNION ALL
SELECT
    (md5('dqmb:' || b.id::text || ':' || cs.customer_no))::uuid AS id,
    ('QUOTE')::character varying(10) AS system_type,
    (cs.customer_no)::character varying(20) AS customer_no,
    (b.material_no)::character varying(20) AS material_no,
    (b.output_material_type)::character varying(100) AS characteristic,
    (b.item_seq)::integer          AS seq_no,
    (b.input_material_no)::character varying(20) AS component_no,
    NULL::character varying(20)        AS part_no,
    NULL::timestamp(6) with time zone  AS effective_datetime,
    NULL::timestamp(6) with time zone  AS expire_datetime,
    NULL::character varying(20)        AS operation_no,
    NULL::character varying(20)        AS operation_seq,
    NULL::integer                      AS item_seq,
    NULL::character varying(20)        AS issue_unit,
    (b.component_qty)::numeric(24,12) AS composition_qty,
    NULL::numeric(24,12)               AS base_qty,
    (mr.symbol)::character varying(100) AS component_usage_type,
    NULL::character varying(20)        AS feature_mgmt,
    NULL::numeric(18,12)               AS upper_limit_pct,
    NULL::numeric(18,12)               AS lower_limit_pct,
    NULL::numeric(24,12)               AS scrap_batch,
    NULL::numeric(18,12)               AS scrap_rate,
    NULL::numeric(24,12)               AS fixed_scrap,
    NULL::character varying(50)        AS issue_location,
    NULL::character varying(50)        AS issue_storage,
    NULL::character varying(20)        AS fas_group,
    NULL::character varying(50)        AS plug_position,
    NULL::character varying(50)        AS ref_rd_center,
    NULL::boolean                      AS is_optional,
    NULL::character varying(20)        AS wo_expand_option,
    NULL::boolean                      AS is_purchase_replace,
    NULL::numeric(24,12)               AS component_lead_time,
    NULL::character varying(20)        AS main_substitute,
    NULL::character varying(20)        AS attached_part,
    NULL::character varying(30)        AS ecn_no,
    NULL::boolean                      AS use_qty_formula,
    NULL::character varying(500)       AS qty_formula,
    NULL::character varying(20)        AS scrap_rate_type,
    NULL::boolean                      AS is_backflush,
    NULL::boolean                      AS is_customer_supply,
    NULL::numeric(18,12)               AS defect_rate,
    NULL::character varying(20)        AS calc_type,
    NULL::numeric(18,12)               AS recovery_discount,
    NULL::character varying(10)        AS recovery_currency,
    NULL::character varying(20)        AS recovery_unit,
    (b.created_at)::timestamp(6) with time zone AS created_at,
    (COALESCE(b.updated_at, b.created_at))::timestamp(6) with time zone AS updated_at,
    NULL::uuid                         AS created_by,
    NULL::uuid                         AS updated_by,
    (true)::boolean                AS is_current,
    NULL::character varying(20)        AS bom_version,
    NULL::numeric(26,12)               AS rough_weight,
    NULL::numeric(26,12)               AS net_weight,
    NULL::character varying(20)        AS weight_unit,
    NULL::character varying(32)        AS production_no,
    NULL::uuid                         AS pending_quotation_id,
    NULL::uuid[]                       AS pending_supersedes,
    (b.material_ratio)::numeric(24,12) AS material_ratio
FROM ds_quote_material_bom b
JOIN cust_scope cs ON cs.material_no = b.material_no
LEFT JOIN material_recipe mr ON mr.code = b.input_material_no
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item x
     WHERE x.system_type = 'QUOTE' AND x.is_current
       AND x.material_no = b.material_no
       AND x.customer_no = cs.customer_no
);

COMMENT ON VIEW v_compat_material_bom_item IS
  'task-260903 B-1/B-2：material_bom_item 存量 UNION ALL ds_quote_material_bom 投影。characteristic←output_material_type / seq_no←item_seq / component_no←input_material_no / component_usage_type←material_recipe.symbol(LEFT) / composition_qty←component_qty / is_current 恒 true。';

-- ── 3. B-1 · v_compat_element_bom_item ───────────────────────────────
-- characteristic 在 V6 是 NOT NULL 的「版本列」（QUOTE 侧实测取值 2000~2018，由
-- VersionedV6Writer 从 2000 起分配）。新表用 version_no 承担版本，无对应列 ⇒ 取常量 '2000'。
-- v_composite_child_elements 按 max(characteristic) 选当前组，常量下恒选中；
-- 实测无任何组件 SQL 对 element_bom_item.characteristic 做常量过滤，故安全。
-- hf_part_no ← material_no：选配侧 V6 一直写自指（insertElementBomV6 的 childGk.hf_part_no=partNo），
-- 而 v_composite_child_elements 第一分支要求 hf_part_no IS NOT NULL，留空该视图直接空表。
CREATE OR REPLACE VIEW v_compat_element_bom_item AS
WITH cust_scope AS (
    -- 客户作用域。新表体系没有 customer_no 维度，V6 侧有且 104/135 段组件 SQL 按它过滤，
    -- 所以必须在这里把「哪个客户拥有这个料号」补出来：
    --   ① 客户产品编号直接指向该料号（ds_quote_customer_part.material_no）
    --   ② 该料号是某客户产品的投入料号 —— 组合产品的子件 / 零件的材质料号。
    --      V6 侧当年就是给这些子件各写一份带 customer_no 的行（insertMaterialBomItemV6 /
    --      writeCombomaterialBomV6 都以 customerCode 落库），这里等价还原。
    -- 🚩 api.md §2.1 只写了「JOIN ds_quote_customer_part 取 customer_no」，仅覆盖 ①；
    --    只做 ① 会让组合产品子件的材质/元素行取不到 customer_no 而整体消失（A-AC-8 必挂）。
    SELECT DISTINCT cp.customer_no, cp.material_no
      FROM ds_quote_customer_part cp
     WHERE cp.material_no IS NOT NULL
    UNION
    SELECT DISTINCT cp.customer_no, b.input_material_no AS material_no
      FROM ds_quote_customer_part cp
      JOIN ds_quote_material_bom b ON b.material_no = cp.material_no
     WHERE cp.material_no IS NOT NULL
       AND b.input_material_no IS NOT NULL
)
SELECT
    v.id,
    v.system_type,
    v.customer_no,
    v.material_no,
    v.characteristic,
    v.component_no,
    v.part_no,
    v.effective_datetime,
    v.expire_datetime,
    v.operation_no,
    v.operation_seq,
    v.seq_no,
    v.issue_unit,
    v.composition_qty,
    v.base_qty,
    v.component_usage_type,
    v.feature_mgmt,
    v.content,
    v.upper_limit_pct,
    v.lower_limit_pct,
    v.scrap_batch,
    v.scrap_rate,
    v.defect_rate,
    v.fixed_scrap,
    v.issue_location,
    v.issue_storage,
    v.fas_group,
    v.plug_position,
    v.ref_rd_center,
    v.is_optional,
    v.wo_expand_option,
    v.is_purchase_replace,
    v.component_lead_time,
    v.main_substitute,
    v.attached_part,
    v.ecn_no,
    v.use_qty_formula,
    v.qty_formula,
    v.scrap_rate_type,
    v.is_backflush,
    v.is_customer_supply,
    v.recovery_discount,
    v.recovery_currency,
    v.recovery_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.hf_part_no,
    v.is_current,
    v.production_no,
    v.material_part_no,
    v.pending_quotation_id,
    v.pending_supersedes
FROM element_bom_item v
UNION ALL
SELECT
    (md5('dqeb:' || e.id::text || ':' || cs.customer_no))::uuid AS id,
    ('QUOTE')::character varying(10) AS system_type,
    (cs.customer_no)::character varying(20) AS customer_no,
    (e.material_no)::character varying(20) AS material_no,
    ('2000')::character varying(100) AS characteristic,
    (e.element_code)::character varying(20) AS component_no,
    NULL::character varying(20)        AS part_no,
    NULL::timestamp(6) with time zone  AS effective_datetime,
    NULL::timestamp(6) with time zone  AS expire_datetime,
    NULL::character varying(20)        AS operation_no,
    NULL::character varying(20)        AS operation_seq,
    (e.item_seq)::integer          AS seq_no,
    NULL::character varying(20)        AS issue_unit,
    NULL::numeric(24,12)               AS composition_qty,
    NULL::numeric(24,12)               AS base_qty,
    NULL::character varying(100)       AS component_usage_type,
    NULL::character varying(20)        AS feature_mgmt,
    (e.content_pct)::numeric(24,12) AS content,
    NULL::numeric(18,12)               AS upper_limit_pct,
    NULL::numeric(18,12)               AS lower_limit_pct,
    NULL::numeric(24,12)               AS scrap_batch,
    NULL::numeric(18,12)               AS scrap_rate,
    NULL::numeric(18,12)               AS defect_rate,
    NULL::numeric(24,12)               AS fixed_scrap,
    NULL::character varying(50)        AS issue_location,
    NULL::character varying(50)        AS issue_storage,
    NULL::character varying(20)        AS fas_group,
    NULL::character varying(50)        AS plug_position,
    NULL::character varying(50)        AS ref_rd_center,
    NULL::boolean                      AS is_optional,
    NULL::character varying(20)        AS wo_expand_option,
    NULL::boolean                      AS is_purchase_replace,
    NULL::numeric(24,12)               AS component_lead_time,
    NULL::character varying(20)        AS main_substitute,
    NULL::character varying(20)        AS attached_part,
    NULL::character varying(30)        AS ecn_no,
    NULL::boolean                      AS use_qty_formula,
    NULL::character varying(500)       AS qty_formula,
    NULL::character varying(20)        AS scrap_rate_type,
    NULL::boolean                      AS is_backflush,
    NULL::boolean                      AS is_customer_supply,
    NULL::numeric(18,12)               AS recovery_discount,
    NULL::character varying(10)        AS recovery_currency,
    NULL::character varying(20)        AS recovery_unit,
    (e.created_at)::timestamp(6) with time zone AS created_at,
    (COALESCE(e.updated_at, e.created_at))::timestamp(6) with time zone AS updated_at,
    NULL::uuid                         AS created_by,
    NULL::uuid                         AS updated_by,
    (e.material_no)::character varying(20) AS hf_part_no,
    (true)::boolean                AS is_current,
    NULL::character varying(32)        AS production_no,
    (e.material_part_no)::character varying(32) AS material_part_no,
    NULL::uuid                         AS pending_quotation_id,
    NULL::uuid[]                       AS pending_supersedes
FROM ds_quote_element_bom e
JOIN cust_scope cs ON cs.material_no = e.material_no
WHERE NOT EXISTS (
    SELECT 1 FROM element_bom_item x
     WHERE x.system_type = 'QUOTE' AND x.is_current
       AND x.material_no = e.material_no
       AND x.customer_no = cs.customer_no
);

COMMENT ON VIEW v_compat_element_bom_item IS
  'task-260903 B-1：element_bom_item 存量 UNION ALL ds_quote_element_bom 投影。component_no←element_code / content←content_pct / seq_no←item_seq / hf_part_no←material_no / characteristic 常量 2000 / is_current 恒 true。';

-- ── 4. B-4 · 3 个 v_composite_child_* 改读兼容视图 ────────────────────
-- 用 CREATE OR REPLACE（不是 DROP + CREATE）：DROP VIEW 属 CLAUDE.md §3.2 红线，
-- 且 v_composite_child_* 是渲染主链路。REPLACE 要求输出列名/类型/列序完全不变 ——
-- 兼容视图逐列 CAST 回 V6 typmod 正是为了让这一步能过。
-- 视图体除表名外逐字照抄改造前的 pg_get_viewdef 输出。

CREATE OR REPLACE VIEW v_composite_child_materials AS
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, mr.name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    mr.id AS recipe_id,
    asy.component_no AS material_code,
    mr.symbol AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name) AS material_name,
    COALESCE(mm.specification, mr.spec_label, asy.component_usage_type) AS spec_label,
    COALESCE(asy.component_usage_type, mr.recipe_type) AS recipe_type,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM v_compat_material_bom_item asy
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe mr ON mr.code::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text AND asy.characteristic::text IS DISTINCT FROM 'OUTSOURCED'::text AND asy.is_current = true
UNION ALL
 SELECT mm.material_no AS hf_part_no,
    mm.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0 AS child_seq,
    NULL::uuid AS recipe_id,
    NULL::character varying AS material_code,
    NULL::character varying AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type,
    NULL::uuid AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM v_compat_material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM v_compat_material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text AND asy2.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text AND asy2.is_current = true AND asy2.material_no::text = mm.material_no::text));

CREATE OR REPLACE VIEW v_composite_child_processes AS
 SELECT up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    row_number() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM ( SELECT DISTINCT unit_price.customer_no,
            unit_price.finished_material_no,
            unit_price.operation_no
           FROM unit_price
          WHERE unit_price.system_type::text = 'QUOTE'::text AND unit_price.is_current = true AND (unit_price.cost_type::text = ANY (ARRAY['自制加工费'::character varying::text, '组装加工费'::character varying::text, '来料加工费'::character varying::text])) AND unit_price.operation_no IS NOT NULL AND unit_price.finished_material_no IS NOT NULL) up
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = up.finished_material_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = up.operation_no::text
     LEFT JOIN customer c ON c.code::text = up.customer_no::text
UNION ALL
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    row_number() OVER (PARTITION BY asy.material_no, c.id, asy.component_no ORDER BY asy.seq_no, asy.operation_no) AS seq_no,
    asy.operation_no AS process_code,
    COALESCE(pm.process_name, asy.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM v_compat_material_bom_item asy
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = asy.operation_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text = 'ASSEMBLY'::text AND asy.is_current = true AND asy.operation_no IS NOT NULL;

CREATE OR REPLACE VIEW v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id,
    ebi.material_part_no
   FROM v_compat_element_bom_item ebi
     LEFT JOIN v_compat_material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text AND ebi.hf_part_no IS NOT NULL AND ebi.is_current = true AND ebi.characteristic::text = (( SELECT max(ebi2.characteristic::text) AS max
           FROM v_compat_element_bom_item ebi2
          WHERE ebi2.system_type::text = ebi.system_type::text AND ebi2.customer_no::text = ebi.customer_no::text AND ebi2.material_no::text = ebi.material_no::text AND NOT ebi2.material_part_no::text IS DISTINCT FROM ebi.material_part_no::text));

