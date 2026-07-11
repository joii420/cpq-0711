-- V322: repair-2(报价单) · 材质料号不入料号表 + characteristic=RECIPE 的渲染视图放宽
-- (原编号 V321，因与并发 worktree 的 V321__material_recipe_name_default_symbol.sql 撞号而重命名；
--  flyway_schema_history 对应行已同步 UPDATE version 321→322，内容/校验和不变)
--
-- 背景(architecture-review.md §2)：MaterialBomMergeHandler 改为材质料号 component_no
--   恒 characteristic='RECIPE'(不再是 NULL)、且不再登记 material_master(决策 A/B/C)。
--   v_composite_child_materials(选配-材质 driver 物理视图)与其镜像模板
--   composite_child_materials_mirror(component_sql_view)原用 `characteristic IS NULL`
--   挑选材料行 —— 若不放宽，RECIPE 材料行会从视图整条消失(不是空名，是整行不返)。
--
-- 统一修法：过滤谓词从 `characteristic IS NULL` 收敛为
--   `characteristic IS DISTINCT FROM 'ASSEMBLY'`（旧 NULL + 新 RECIPE 都命中，排除 ASSEMBLY，
--   对存量未重导数据 100% 向后兼容）；并追加 LEFT JOIN material_recipe 品名/规格/牌号兜底
--   (join key = material_recipe.code = component_no，2026-07-09 决策 E 已用真实数据确认)。
-- 不新增视图列签名（不加新列，只把原本恒 NULL 的 recipe_id/chemical_symbol 等换成真值）。
--
-- ── T1: v_composite_child_materials（PG 物理视图，V303 终态基础上重建）──
-- 与 V303 相比：①两处 characteristic 谓词放宽；②追加 material_recipe 兜底 join；
-- ③ child_part_name / material_name / spec_label / chemical_symbol / recipe_id / recipe_type
--   的 COALESCE 链追加材质库兜底，末尾仍保留 component_no，任何情况下品名非空。
-- ⚠️ 实测发现：不能用 CREATE OR REPLACE —— COALESCE(mm.specification[varchar(100)],
--   mr.spec_label[varchar(64)], ...) 引入不同长度的 varchar 后，PG 推导 spec_label 列类型从
--   varchar(100) 变成不限长度的 varchar，CREATE OR REPLACE 禁止改列类型会报错
--   "cannot change data type of view column spec_label"。改用 V303 同款 DROP+CREATE（本视图当前
--   无任何 ACTIVE 组件/模板引用，DROP 无级联影响，详见交付报告的孤儿视图核实）。

DROP VIEW IF EXISTS v_composite_child_materials;

CREATE VIEW v_composite_child_materials AS
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
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe mr ON mr.code::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic IS DISTINCT FROM 'ASSEMBLY'
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
   FROM material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text
            AND asy2.characteristic IS DISTINCT FROM 'ASSEMBLY'
            AND asy2.is_current = true
            AND asy2.component_no::text = mm.material_no::text));

COMMENT ON VIEW v_composite_child_materials IS
  'repair-2(V321): 材料行谓词 characteristic IS NULL → IS DISTINCT FROM ''ASSEMBLY''(兼容 RECIPE)；'
  '追加 material_recipe 品名/规格/牌号兜底(mr.code = component_no)。向后兼容：存量 NULL 行行为不变。';

-- ── T2: composite_child_materials_mirror（component_sql_view 镜像模板）──
-- 同 T1 过滤 + material_recipe 兜底；不新增 declared_columns（列签名零改动）。
-- ⚠️ 实现时核对发现：该行当前(2026-07-09)在本环境 component_sql_view 表中已不存在
--   （2026-06-11 组合产品/选配测试数据清理时，其挂载的组件 COMP-CFG-MATERIAL-RECIPE 已被删除，
--   ON DELETE CASCADE 级联删除了本行 —— 详见交付报告）。此 UPDATE 现为 0 行生效的防御性占位：
--   若未来该选配组件/模板被重新创建（沿用 V223 同款 INSERT INTO component_sql_view ...
--   sql_view_name='composite_child_materials_mirror'），需确保新建内容采用本文件的过滤+兜底写法，
--   而不是重新退回 V228/V263 时期的旧写法。
UPDATE component_sql_view
   SET sql_template = $SQL$SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, mr.name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    mr.id            AS recipe_id,
    asy.component_no AS material_code,
    mr.symbol        AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name) AS material_name,
    COALESCE(mm.specification, mr.spec_label, asy.component_usage_type) AS spec_label,
    COALESCE(asy.component_usage_type, mr.recipe_type) AS recipe_type
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
LEFT JOIN material_recipe mr ON mr.code = asy.component_no
WHERE asy.system_type = 'QUOTE' AND asy.is_current = true
  AND asy.characteristic IS DISTINCT FROM 'ASSEMBLY'
  AND asy.customer_no = :customerCode$SQL$,
       updated_at = NOW()
 WHERE sql_view_name = 'composite_child_materials_mirror';

-- ── T3: V263 selopt「选配-材质」视图确认 ──
-- selopt 5 个视图之一即本文件 T2 目标(composite_child_materials_mirror，同一行)，已按上方处理。
-- 其余 4 个 selopt 视图(zcj_bom / composite_child_processes_mirror / composite_child_elements_mirror /
-- composite_child_weights_mirror)均不使用 `characteristic IS NULL` 挑材料行(zcj_bom/processes/weights
-- 用 `= 'ASSEMBLY'` 挑真组成件、elements 走 element_bom_item 无 characteristic 谓词)，RECIPE 改动
-- 不影响其行为，本迁移不touch。经确认，5 个 selopt 视图当前(2026-07-09)均未被任何 ACTIVE
-- component/template_component 引用(component_sql_view 表中 composite_child_*_mirror /
-- zcj_bom 相关行 2026-06-11 已随组件清理级联删除)——选配/组合产品渲染链路目前是孤儿状态，
-- 不在 quotation-flow.spec.ts 覆盖范围内(该 E2E 用 SIMPLE 产品 + 报价模板0608)。

DO $$ BEGIN RAISE NOTICE 'V321: v_composite_child_materials 视图已放宽 + material_recipe 兜底；composite_child_materials_mirror UPDATE 已执行(当前环境 0 行匹配，见迁移注释)'; END $$;
