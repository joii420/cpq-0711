-- V282: composite_child_elements_mirror 下钻分支 JOIN 改 material_no（选配 COMBO 元素渲染修复）
--
-- 背景：COMBO 元素 Tab 通过 composite_child_elements_mirror 第二个（下钻）分支聚合各子配件的元素。
--   原下钻 JOIN element_bom_item ebi ON ebi.hf_part_no = parent.component_no AND ebi.hf_part_no IS NOT NULL。
--   但 V6 原生导入子件（如 10110002/10110003）的 element_bom_item.hf_part_no = NULL（元素只按 material_no 标识）
--   → 下钻 JOIN 永不命中 + IS NOT NULL 显式排除 → COMBO 元素 Tab 对导入子件恒空。
-- 修法：下钻 JOIN 改 ON ebi.material_no = parent.component_no（material_no 一定有值，对导入/选配自定义子件都鲁棒），
--   去掉 ebi.hf_part_no IS NOT NULL，补 ebi.is_current = true（V281 只给第一分支加了 is_current），
--   并修 MAX(characteristic) 关联子查询里的 ebi2.hf_part_no = ebi.hf_part_no（hf_part_no=NULL 时该条件恒 false
--   → 子查询返空 → 永不命中）—— 改按 material_no 分组（已含 customer/system 维度足够）。
-- 第一分支（part 渲染自身元素，hf_part_no = 渲染料号）保持不变。
-- 全文替换比链式 regexp_replace 更稳（单分支多处联动改动）。
-- 部署：落 db/migration/ 后 touch java 触发 Quarkus 重启 → Flyway + BnfTableMetaSyncer 重新同步（清缓存）。

UPDATE component_sql_view
SET sql_template = $MIRROR$
SELECT
    ebi.hf_part_no                              AS hf_part_no,
    NULL::uuid                                  AS line_item_id,
    ebi.material_no                             AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                                           AS child_seq,
    ebi.seq_no                                  AS seq_no,
    ebi.component_no                            AS element_name,
    ebi.content                                 AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current = true
  AND ebi.hf_part_no IS NOT NULL
  AND ebi.customer_no = :customerCode
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
        AND ebi2.hf_part_no = ebi.hf_part_no
  )

UNION ALL

SELECT
    parent.material_no                          AS hf_part_no,
    NULL::uuid                                  AS line_item_id,
    ebi.material_no                             AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                                           AS child_seq,
    ebi.seq_no                                  AS seq_no,
    ebi.component_no                            AS element_name,
    ebi.content                                 AS composition_pct
FROM material_bom_item parent
JOIN element_bom_item ebi ON ebi.material_no = parent.component_no
                          AND ebi.customer_no = parent.customer_no
                          AND ebi.system_type = parent.system_type
                          AND ebi.is_current = true
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE parent.system_type = 'QUOTE'
  AND parent.characteristic = 'ASSEMBLY'
  AND parent.customer_no = :customerCode
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
  )
$MIRROR$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';
