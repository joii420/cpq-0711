-- V404 (task-260902 · B-7 修正): v_composite_child_materials 排除 OUTSOURCED 行
--
-- 🔴 缺陷：B-7 让外购件落 material_bom_item.characteristic='OUTSOURCED'（该值此前全表 0 行，
-- 是从未通电的路径）。而本视图第一分支的过滤是**排除法**：
--     WHERE asy.characteristic IS DISTINCT FROM 'ASSEMBLY'
-- 排除法对「新出现的枚举值」天然是放行的 ⇒ OUTSOURCED 行满足它，于是外购件被当成「材质」
-- 渲染进了「选配-材质」页签。
--
-- ✅ 用户裁决（2026-09-03）：**外购件不是材质，不该出现在材质页签**。
--
-- 修法：第一分支的谓词补上 characteristic <> 'OUTSOURCED'。
-- ⚠️ **第二 UNION 分支（material_master 无 BOM 行的兜底）的 NOT EXISTS 谓词故意不动**：
--    它仍把 OUTSOURCED 行算作「该料号已有 BOM 行」，从而把外购件料号也挡在第二分支之外。
--    两处若一起改，外购件会从第二分支重新冒出来，material_name 取 COALESCE(mm.material_type,…)
--    = 「外购件」—— 那还是出现在材质页签里，等于没修。
--
-- 📌 教训（排除法 vs 白名单）：`IS DISTINCT FROM 'ASSEMBLY'` 这类写法在枚举扩容时会**静默放行新值**。
--    本次是 RECIPE/ASSEMBLY 两态时代写的谓词，遇上第三态 OUTSOURCED 就漏了。
--    这里保持最小改动（显式排除），不改写成白名单 —— 改白名单会连带影响导入侧写入的
--    characteristic 为 NULL 的历史行（实测全表有 1 行 NULL），超出本任务范围。

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
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe mr ON mr.code::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text
    -- ⬇⬇ 本次修复：外购件不是材质（用户裁决 2026-09-03）
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
   FROM material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text
            -- ⚠️ 这里**故意保持排除法原样**（含 OUTSOURCED 计入「已有 BOM 行」），
            --    否则外购件会从本分支重新冒出来，material_name 显示成「外购件」。
            AND asy2.characteristic::text IS DISTINCT FROM 'ASSEMBLY'::text
            AND asy2.is_current = true
            AND asy2.material_no::text = mm.material_no::text));

COMMENT ON VIEW v_composite_child_materials IS
    'task-260902 V404：第一分支排除 characteristic=OUTSOURCED（外购件不是材质，用户裁决 2026-09-03）；'
    '第二分支的 NOT EXISTS 故意仍计入 OUTSOURCED，防止外购件从兜底分支重新冒出来。';
