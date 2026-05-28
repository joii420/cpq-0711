-- ============================================================
-- V260: 修复「选配-子配件清单」driver 源与字段 path 源不一致
--
-- 现象 (报价单 QT-20260527-1651)：
--   选配-子配件清单页签 数量列显示 "1 (共 5 项)"、单位列 "PCS (共 5 项)"，
--   子料号显示投料号 9997/9998 而非装配子件 8881~8885。
--
-- 根因 (AP-22 + AP-37 组合)：
--   - data_driver_path = $$COMP-CFG-MATERIAL-RECIPE.composite_child_materials_mirror
--     (materials_mirror 查 characteristic IS NULL 投料行 → 2 行 9997/9998)
--   - 字段 basic_data_path = $zcj_bom.* (zcj_bom 查 characteristic='ASSEMBLY' 装配子件 → 5 行 8881~8885)
--   - driver 与字段两个视图维度不一致：
--     · 序号/子料号 leafField(child_seq/child_hf_part_no) 在 materials_mirror driverRow 也有同名列
--       → 被短路劫持，显示投料号(错)
--     · 数量/单位 leafField(composition_qty/issue_unit) materials_mirror 无此列
--       → 不短路 → SqlViewExecutor 全量查 zcj_bom 返 5 行数组 → 前端 formatPathValue "(共 5 项)"
--
-- 修复：driver 与字段统一到 zcj_bom (装配子件维度)。driver 改 $zcj_bom 后返 5 行，
--   每字段从同行短路取单值，4 列全部正确。zcj_bom owner=CHILD-PARTS / scope=COMPONENT，
--   本组件引用 $zcj_bom 可解析。
--
-- 决策 (用户确认)：
--   - snapshot 范围：仅改配置一致的 PUBLISHED 模板 (data_driver_path 当前=materials_mirror 的)
--   - rowData：清空 QT-1651 的 CHILD-PARTS row_data，前端按新 driver 重算
-- ============================================================

-- ============== 1. component 表 ==============
-- 1a. data_driver_path 改本组件 SQL 视图引用
UPDATE component
SET data_driver_path = '$zcj_bom', updated_at = NOW()
WHERE code = 'COMP-CFG-CHILD-PARTS';

-- 1b. 「子料号名称」字段：INPUT_TEXT + V44 mat_bom 直引 → BASIC_DATA + $zcj_bom.child_part_name
--     jsonb || 合并覆盖 field_type + basic_data_path，保留 name/content/is_amount/is_subtotal
UPDATE component
SET fields = (
    SELECT jsonb_agg(
        CASE WHEN f->>'name' = '子料号名称'
            THEN f || '{"field_type":"BASIC_DATA","basic_data_path":"$zcj_bom.child_part_name"}'::jsonb
            ELSE f
        END
    )
    FROM jsonb_array_elements(fields) f
), updated_at = NOW()
WHERE code = 'COMP-CFG-CHILD-PARTS';

-- ============== 2. PUBLISHED 模板 snapshot 条件同步 ==============
-- 仅替换 snapshot 里 CHILD-PARTS 块且 data_driver_path 当前=materials_mirror 的模板
-- (老模板若是别的配置，CASE ELSE 原样保留，避免误伤)
UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN comp->>'componentCode' = 'COMP-CFG-CHILD-PARTS'
             AND comp->>'data_driver_path' = '$$COMP-CFG-MATERIAL-RECIPE.composite_child_materials_mirror'
            THEN jsonb_set(
                   comp || '{"data_driver_path":"$zcj_bom"}'::jsonb,
                   '{fields}',
                   (SELECT jsonb_agg(
                        CASE WHEN f->>'name' = '子料号名称'
                            THEN f || '{"field_type":"BASIC_DATA","basic_data_path":"$zcj_bom.child_part_name"}'::jsonb
                            ELSE f
                        END
                    ) FROM jsonb_array_elements(comp->'fields') f)
                 )
            ELSE comp
        END
    )
    FROM jsonb_array_elements(t.components_snapshot) comp
), updated_at = NOW()
WHERE t.status = 'PUBLISHED'
  AND t.components_snapshot::text LIKE '%COMP-CFG-CHILD-PARTS%'
  AND t.components_snapshot::text LIKE '%composite_child_materials_mirror%';

-- ============== 3. 清 QT-20260527-1651 已污染的 CHILD-PARTS rowData ==============
-- autoSave 把 driver(materials_mirror)+ 字段全量查 zcj_bom 的错误结果落库为数组脏值
-- 清空后前端按新 driver($zcj_bom 5 行)重算并 autoSave 正确值
-- 其它环境无此报价单 → 0 行更新，无害
UPDATE quotation_line_component_data
SET row_data = '[]'
WHERE component_id = (SELECT id FROM component WHERE code = 'COMP-CFG-CHILD-PARTS')
  AND line_item_id IN (
      SELECT id FROM quotation_line_item
      WHERE quotation_id = '2ab989bb-2a91-43b0-832a-81e2de48433a'
  );

-- ============== 4. 自检日志 ==============
DO $body$
DECLARE
    comp_path  TEXT;
    tpl_cnt    INT;
BEGIN
    SELECT data_driver_path INTO comp_path FROM component WHERE code = 'COMP-CFG-CHILD-PARTS';
    SELECT COUNT(*) INTO tpl_cnt FROM template
        WHERE status='PUBLISHED'
          AND components_snapshot::text LIKE '%"data_driver_path": "$zcj_bom"%';
    RAISE NOTICE 'V260 done: CHILD-PARTS driver=% , PUBLISHED 模板含 $zcj_bom snapshot % 张', comp_path, tpl_cnt;
END $body$;
