-- ============================================================================
-- task-260909 · V6 老表退役 · 批次 1（B-2 + B-3）
--   B-2：3 个 v_composite_child_* 视图改为直读 ds_quote_*，摘掉对
--        v_compat_material_master / v_compat_material_bom_item /
--        v_compat_element_bom_item 三个兼容视图的依赖。
--   B-3：修 costing_bom_tree_config 两行 sql_template（其中一行是 V411
--        表名替换时漏改的 FROM material_bom_item 直连老表）。
--
-- 设计纪律（务必保留，改这段之前先读 dev-docs/task-260909-V6老表退役/backtask.md）：
--  1) 全部用 CREATE OR REPLACE VIEW —— 输出列名/列序/列类型逐列不变。
--     本迁移**不含任何 DROP**（DROP VIEW v_compat_* 是 CLAUDE.md §3.2 红线，
--     须用户逐个批准后单独执行，不进迁移文件）。
--  2) 内联子查询原样搬 v_compat_* 的「ds 分支」，包括那些看起来多余的
--     ::character varying(20) 截断转换 —— 兼容视图本来就这么截，
--     照抄是为了**不引入本任务范围外的数据变化**（实测 ds_quote_element_bom
--     有 23 行 material_no 长度 > 20，行为必须与退役前一致）。
--  3) 相对兼容视图，这里去掉的只有两样：
--       · NOT EXISTS(老表同 key 行) 的「老表遮蔽新表」语义
--       · cust_scope 白名单（ds_quote_customer_part 限定）
--     这正是需求文档 §④ 批次 1 描述的预期数据变化面。
--  4) DISTINCT ON / COALESCE 兜底逻辑一律照抄，禁止顺手"优化"。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. v_composite_child_materials
-- ---------------------------------------------------------------------------
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
   FROM ( SELECT 'QUOTE'::character varying(10) AS system_type,
                 b.customer_no,
                 b.material_no::character varying(20) AS material_no,
                 b.output_material_type::character varying(100) AS characteristic,
                 b.item_seq AS seq_no,
                 b.input_material_no::character varying(20) AS component_no,
                 mrx.symbol::character varying(100) AS component_usage_type,
                 true AS is_current
            FROM ds_quote_material_bom b
                 LEFT JOIN material_recipe mrx ON mrx.code::text = b.input_material_no::text) asy
     LEFT JOIN ( SELECT m.material_no::character varying(20) AS material_no,
                        m.material_name::character varying(100) AS material_name,
                        m.specification::character varying(100) AS specification,
                        m.material_type::character varying(50) AS material_type
                   FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                                 ds_quote_material.material_name,
                                 ds_quote_material.specification,
                                 ds_quote_material.material_type,
                                 ds_quote_material.customer_no
                            FROM ds_quote_material
                           ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
       ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe mr ON mr.code::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text
    AND asy.characteristic::text IS DISTINCT FROM 'OUTSOURCED'::text
    AND asy.is_current = true
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
   FROM ( SELECT m.material_no::character varying(20) AS material_no,
                 m.material_name::character varying(100) AS material_name,
                 m.specification::character varying(100) AS specification,
                 m.material_type::character varying(50) AS material_type
            FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                          ds_quote_material.material_name,
                          ds_quote_material.specification,
                          ds_quote_material.material_type,
                          ds_quote_material.customer_no
                     FROM ds_quote_material
                    ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM ds_quote_material_bom asy2
          WHERE asy2.output_material_type::text IS DISTINCT FROM 'ASSEMBLY'::text
            AND asy2.material_no::text = mm.material_no::text));

-- ---------------------------------------------------------------------------
-- 2. v_composite_child_elements
-- ---------------------------------------------------------------------------
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
   FROM ( SELECT 'QUOTE'::character varying(10) AS system_type,
                 e.customer_no,
                 e.material_no::character varying(20) AS material_no,
                 '2000'::character varying(100) AS characteristic,
                 e.element_code::character varying(20) AS component_no,
                 e.item_seq AS seq_no,
                 e.content_pct::numeric(24,12) AS content,
                 e.material_no::character varying(20) AS hf_part_no,
                 true AS is_current,
                 e.material_part_no::character varying(32) AS material_part_no
            FROM ds_quote_element_bom e) ebi
     LEFT JOIN ( SELECT m.material_no::character varying(20) AS material_no,
                        m.material_name::character varying(100) AS material_name
                   FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                                 ds_quote_material.material_name,
                                 ds_quote_material.customer_no
                            FROM ds_quote_material
                           ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
       ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text
    AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = (( SELECT max(ebi2.characteristic::text) AS max
           FROM ( SELECT 'QUOTE'::character varying(10) AS system_type,
                         e.customer_no,
                         e.material_no::character varying(20) AS material_no,
                         '2000'::character varying(100) AS characteristic,
                         e.material_part_no::character varying(32) AS material_part_no
                    FROM ds_quote_element_bom e) ebi2
          WHERE ebi2.system_type::text = ebi.system_type::text
            AND ebi2.customer_no::text = ebi.customer_no::text
            AND ebi2.material_no::text = ebi.material_no::text
            AND NOT ebi2.material_part_no::text IS DISTINCT FROM ebi.material_part_no::text));

-- ---------------------------------------------------------------------------
-- 3. v_composite_child_processes
--    一阶来源 unit_price 本期保留（unit_price 不在退役对象里）；
--    只把两处 v_compat_material_master / v_compat_material_bom_item 换成 ds_quote_*。
-- ---------------------------------------------------------------------------
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
     LEFT JOIN ( SELECT m.material_no::character varying(20) AS material_no,
                        m.material_name::character varying(100) AS material_name
                   FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                                 ds_quote_material.material_name,
                                 ds_quote_material.customer_no
                            FROM ds_quote_material
                           ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
       ON mm.material_no::text = up.finished_material_no::text
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
   FROM ( SELECT 'QUOTE'::character varying(10) AS system_type,
                 b.customer_no,
                 b.material_no::character varying(20) AS material_no,
                 b.output_material_type::character varying(100) AS characteristic,
                 b.item_seq AS seq_no,
                 b.input_material_no::character varying(20) AS component_no,
                 NULL::character varying(20) AS operation_no,
                 true AS is_current
            FROM ds_quote_material_bom b) asy
     LEFT JOIN ( SELECT m.material_no::character varying(20) AS material_no,
                        m.material_name::character varying(100) AS material_name
                   FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                                 ds_quote_material.material_name,
                                 ds_quote_material.customer_no
                            FROM ds_quote_material
                           ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
       ON mm.material_no::text = asy.component_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = asy.operation_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic::text = 'ASSEMBLY'::text
    AND asy.is_current = true
    AND asy.operation_no IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. B-3 · costing_bom_tree_config —— 只改 QUOTE 那一行（d6defaa0）
--
-- 🚨 另一行 82612f2b（name=「核价BOM树-PRICING口径v1」, usage=COSTING）**故意不动**。
--    backtask.md B-3 要求把它的 FROM material_bom_item 换成 ds_quote_material_bom，
--    但那是**跨数据集的错配**，实测依据：
--      · 该行 WHERE 写死 system_type='PRICING' AND customer_no='_GLOBAL_'，
--        而 material_bom_item 里 PRICING 行有 68 行（QUOTE 只有 17 行）—— 是活数据；
--      · ds_quote_material_bom 是 QUOTE 数据集，**没有** system_type / customer_no='_GLOBAL_'
--        语义，也**没有** bom_version / component_no 列 ⇒ 换过去不是"值变了"，是 SQL 直接报错；
--      · 核价侧的新表是 ds_cost_basic_material_bom(23 行) 与 ds_cost_detail_material_bom(14 行)
--        —— 两个方言两张表，与这里"一行配置"是 1:2，且都没有 :versionFilter 需要的
--        is_current / bom_version 列。
--    ⇒ 已停下报主线（契约问题不自行改）。AC-4 的「FROM material_bom_item 命中 0」本迁移未达成。
-- ---------------------------------------------------------------------------
UPDATE costing_bom_tree_config
   SET sql_template = $tpl$WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    NULL::text AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    COALESCE(:customerCode::varchar,
      (SELECT bc.customer_no FROM ds_quote_material_bom bc WHERE bc.material_no=p ORDER BY bc.customer_no LIMIT 1)) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.input_material_no::text,
    NULL::text,
    ch.material_no::text, (b.node_path||'/'||ch.input_material_no)::text, b._cust
  FROM ds_quote_material_bom ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.input_material_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom$tpl$,
       updated_at = now()
 WHERE id = 'd6defaa0-354f-4e92-8e89-4bc8454888c3'::uuid
   AND sql_template LIKE '%v_compat_material_bom_item%';

-- 收工断言：QUOTE 那一行不许再有 v_compat_ 引用（幂等：重跑时 UPDATE 影响 0 行，断言仍成立）
DO $$
DECLARE leftover int;
BEGIN
    SELECT count(*) INTO leftover
      FROM costing_bom_tree_config
     WHERE sql_template LIKE '%v_compat_%';
    IF leftover <> 0 THEN
        RAISE EXCEPTION 'B-3 未收敛：costing_bom_tree_config 仍有 % 行引用 v_compat_*', leftover;
    END IF;
END $$;
