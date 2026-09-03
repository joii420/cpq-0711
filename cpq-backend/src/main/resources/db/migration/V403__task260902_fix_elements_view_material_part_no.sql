-- V403 (task-260902 · B-15): v_composite_child_elements 的版本相关子查询补 material_part_no 维度
--
-- 🔴 缺陷（评审 P0-5，读出侧静默吞组）：原视图的相关子查询
--     AND ebi.characteristic = (SELECT max(ebi2.characteristic)
--                               FROM element_bom_item ebi2
--                               WHERE system_type/customer_no/material_no 相同)
-- **缺 material_part_no 维度**。而 characteristic 在 element_bom_item 上是 VersionedV6Writer 的
-- **版本列**（实测取值 2000~2018），并且是**按 groupKey 各自独立递增**的 —— groupKey 含
-- material_part_no。
-- ⇒ 三层模型下一个销售料号有 N 个材质组（material_part_no 各不同）：只改其中一个材质重新提交时
--   该组升到 2001、另一组仍停在 2000，max() 取全局最大 2001 ⇒ **另一个材质的元素整组从页签消失，
--   无任何报错**（AP-22 静默吞行族）。
-- 📌 存量扫描 0 组命中（单材质时每个料号只有一个 material_part_no，max() 恰好等价）
--   ⇒ 潜伏缺陷，本任务的多材质是第一次给它通电。
--
-- 同时把 material_part_no **暴露为视图列**：三层模型下两组元素的 seq_no 都从 1 开始，
-- 前端没有归属标签就无法把元素行分回各自的材质（AC-3 断言③）。
-- CREATE OR REPLACE 仅在末尾追加新列，既有列名/顺序/类型不变（非破坏性）。

CREATE OR REPLACE VIEW v_composite_child_elements AS
SELECT ebi.hf_part_no,
    ebi.material_no                              AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no)  AS child_part_name,
    0                                            AS child_seq,
    ebi.seq_no,
    ebi.component_no                             AS element_name,
    ebi.content                                  AS composition_pct,
    c.id                                         AS customer_id,
    NULL::uuid                                   AS quotation_line_item_id,
    ebi.material_part_no                         AS material_part_no
   FROM element_bom_item ebi
     LEFT JOIN material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text
    AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = ((SELECT max(ebi2.characteristic::text) AS max
           FROM element_bom_item ebi2
          WHERE ebi2.system_type::text = ebi.system_type::text
            AND ebi2.customer_no::text = ebi.customer_no::text
            AND ebi2.material_no::text = ebi.material_no::text
            -- ⬇⬇ 本次修复：版本列按 (…, material_part_no) 分组求 max，NULL 安全
            AND ebi2.material_part_no IS NOT DISTINCT FROM ebi.material_part_no));

COMMENT ON VIEW v_composite_child_elements IS
    'task-260902 V403：characteristic(版本列) 的 max() 子查询补 material_part_no 维度，'
    '防多材质分组各自升版时 max() 跨组取值把落后那组整组吞掉；并暴露 material_part_no 供前端按材质分组。';
