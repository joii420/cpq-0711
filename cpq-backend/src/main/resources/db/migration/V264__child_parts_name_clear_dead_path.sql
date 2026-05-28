-- ============================================================
-- V264: 清「选配-子配件清单 / 子料号名称」字段残留的死 basic_data_path
--
-- 背景：用户把「子料号名称」改为 INPUT_TEXT（纯手动填写），但字段仍残留
--   basic_data_path = $zcj_bom.child_part_name。
-- 问题：INPUT_TEXT 字段渲染 <input>，值只来自 row[key]（用户手填），
--   **完全不读 basic_data_path**（那是 BASIC_DATA 字段专用）。残留的 path 是死配置，
--   留着会误导后人以为该列会自动带出视图值。
-- 修复：清空 basic_data_path（设为空串），让"纯手填"语义干净。
--   注意：本迁移不改变报价单渲染行为（INPUT_TEXT 本就不读 path，仍是空输入框供手填），
--   仅消除矛盾配置。
-- ============================================================

-- ============== 1. component 表 ==============
UPDATE component
SET fields = (
    SELECT jsonb_agg(
        CASE WHEN f->>'name' = '子料号名称'
            THEN f || '{"basic_data_path":""}'::jsonb
            ELSE f
        END
    )
    FROM jsonb_array_elements(fields) f
), updated_at = NOW()
WHERE code = 'COMP-CFG-CHILD-PARTS';

-- ============== 2. PUBLISHED 模板 snapshot 同步 ==============
UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN comp->>'componentCode' = 'COMP-CFG-CHILD-PARTS'
            THEN jsonb_set(comp, '{fields}',
                   (SELECT jsonb_agg(
                        CASE WHEN f->>'name' = '子料号名称'
                            THEN f || '{"basic_data_path":""}'::jsonb
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
  AND t.components_snapshot::text LIKE '%$zcj_bom.child_part_name%';

-- ============== 3. 自检日志 ==============
DO $body$
DECLARE comp_ok BOOLEAN; tpl_remain INT;
BEGIN
    SELECT NOT (fields::text LIKE '%$zcj_bom.child_part_name%') INTO comp_ok
        FROM component WHERE code='COMP-CFG-CHILD-PARTS';
    SELECT COUNT(*) INTO tpl_remain FROM template
        WHERE status='PUBLISHED' AND components_snapshot::text LIKE '%$zcj_bom.child_part_name%';
    RAISE NOTICE 'V264 done: component 已清死 path=% , 仍残留 child_part_name 的 PUBLISHED 模板 % 张(应 0)', comp_ok, tpl_remain;
END $body$;
