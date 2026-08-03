-- repair-0802：电镀费用/电镀成本增加「投入料号」「投入料号名称」列后的配套迁移。
--
-- 背景：unit_price 的两列语义是 code=零件料号(该费用项针对的零件)、finished_material_no=成品料号
-- （见 dev-docs/rule-0724-组件模板配置/4-页签属性与树.md §零件，2026-07-23 用户澄清）。电镀两个
-- handler 此前把销售料号写进 code 且不写 finished_material_no，与全表其余 14 个 handler 口径不一致。
--
-- 本迁移做三件事：①清理旧语义存量；②dj_view 改按新口径取数并暴露投入料号/名称；③电镀成本组件加两列。
-- 无 DDL：finished_material_no 早在 V276 的 uq_unit_price 13 维唯一键内。

-- ---- 1. 清理旧语义存量 ----
-- 旧行 finished_material_no IS NULL，与新 groupKey 不同组：重导时不会被下线，两组 is_current=true
-- 并存会让视图出重复行。需求说明 §6 已授权清空重导。
DELETE FROM unit_price
WHERE price_type = 'PLATING'
  AND finished_material_no IS NULL;

-- ---- 2. dj_view：material_no/hf_part_no 改绑成品轴，新增投入料号与名称 ----
-- :versionFilter 第 3 实参是「料号键列」（版本切换 override 的匹配键）。核价侧版本按销售料号切换，
-- 故随主轴一起从 up.code 改为 up.finished_material_no。
-- input_part_name 取 material_master.material_name，材质类料号回退 material_recipe.name
-- （与 wl_bom_view 的 coalesce(mm.material_name, mr.name) 同构）。
UPDATE component_sql_view
SET sql_template = $view$select
  up.finished_material_no as material_no,
  up.finished_material_no as hf_part_no,
  up.version_no        as view_version,
  max(up.production_no) as production_no,
  up.code              as input_part_no,
  max(coalesce(mm.material_name, mr.name)) as input_part_name,
  up.plating_scheme_no as plating_scheme_no,
  max(case when up.cost_type = '电镀加工费' then up.pricing_price end) as plating_proc_fee,
  max(case when up.cost_type = '电镀材料费' then up.pricing_price end) as plating_mat_fee,
  max(up.currency)     as currency,
  max(up.unit)         as unit,
  max(up.defect_rate)  as defect_rate
from unit_price up
  left join material_master mm on mm.material_no = up.code
  left join material_recipe mr on mr.code = up.code
where up.system_type = 'PRICING'
  and up.price_type = 'PLATING'
  and :versionFilter(up.is_current, up.version_no, up.finished_material_no)
group by up.finished_material_no, up.code, up.version_no, up.plating_scheme_no$view$,
    updated_at = now()
WHERE sql_view_name = 'dj_view';

-- ---- 3. 电镀成本组件 fields：新增「投入料号」「投入料号名称」 ----
-- ⚠️ 定位用 data_driver_path='$dj_view' 这个**语义锚点**，不用组件 code：component.code 是各库
-- 独立自增的配置数据，同一个 code 在不同库指向不同组件（实测 dev 库 cpq_db_0724 的电镀成本是
-- COMP-0063，而 test 库 cpq_db 的 COMP-0063 是「材质/元素/材料成本」组件，COMP-0057 才是电镀成本）。
-- 按 code 硬编码会在别的库里改错组件。
--
-- 插入位置同样动态计算：锚定「销售料号」所在下标 +0.1/+0.2（两库该组件的字段序不同，硬编码序号
-- 会插错位）。先剔除同名字段再插入 → 重复执行结果一致（幂等）。
UPDATE component c
SET fields = (
        SELECT jsonb_agg(elem ORDER BY ord)
        FROM (
            SELECT elem, ord::numeric AS ord
            FROM jsonb_array_elements(c.fields) WITH ORDINALITY AS t(elem, ord)
            WHERE elem->>'name' NOT IN ('投入料号', '投入料号名称')
            UNION ALL
            SELECT jsonb_build_object(
                       'name', '投入料号', 'notes', '', 'content', '',
                       'is_amount', false, 'field_type', 'BASIC_DATA', 'is_subtotal', false,
                       'basic_data_path', '$dj_view.input_part_no'),
                   (SELECT coalesce(max(o), 1) FROM jsonb_array_elements(c.fields)
                        WITH ORDINALITY AS a(e, o) WHERE e->>'name' = '销售料号')::numeric + 0.1
            UNION ALL
            SELECT jsonb_build_object(
                       'name', '投入料号名称', 'notes', '', 'content', '',
                       'is_amount', false, 'field_type', 'BASIC_DATA', 'is_subtotal', false,
                       'basic_data_path', '$dj_view.input_part_name'),
                   (SELECT coalesce(max(o), 1) FROM jsonb_array_elements(c.fields)
                        WITH ORDINALITY AS a(e, o) WHERE e->>'name' = '销售料号')::numeric + 0.2
        ) s
    ),
    column_count = (
        SELECT count(*) + 2 FROM jsonb_array_elements(c.fields) e
        WHERE e->>'name' NOT IN ('投入料号', '投入料号名称')
    ),
    updated_at = now()
WHERE trim(coalesce(c.data_driver_path, '')) = '$dj_view';
