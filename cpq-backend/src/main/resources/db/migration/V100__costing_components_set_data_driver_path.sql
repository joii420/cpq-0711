-- V100: 给 V98/V99 创建的核价组件配置 data_driver_path (V65 加的"行驱动"机制)
--
-- 问题: V99 给字段配了 BASIC_DATA path 后, BNF 解析返回的是 N 行数组(每条 BOM/工序 一行)。
--       但组件的 data_driver_path 为空, UI 把数组挤压到 1 行渲染, 触发 LinkedExcelView
--       formatPathValue 的兜底逻辑 "2000140001 (共 3 项)"。
--
-- 修复: 给 10 个多行组件设 data_driver_path = 它的"主表"BNF 路径。
--       UI 会按 driver 查询出 N 行, 组件展开 N 行; 字段查询自动 implicit JOIN driver 行的同名列
--       (例: driver 行 process_no='Z053' 时, 字段路径 [cost_type='DEPRECIATION'].unit_price 自动加 process_no='Z053' 谓词)。
--
-- 4 个单行组件保持 data_driver_path=NULL: PLATING-SCHEME / WEIGHT / EXCHANGE-RATE / FINISHED-OTHER 的 4 行不需 driver
-- (FINISHED-OTHER 实际可走 driver 但只 4 项, 单行展示也 OK)。让我也把 FINISHED-OTHER 设上 driver, 让用户看到 4 行。

UPDATE component SET data_driver_path = 'costing_part_material_bom',                          updated_at = now() WHERE code = 'COMP-V4-RAW-BOM';
UPDATE component SET data_driver_path = 'mat_bom[bom_type=''ELEMENT'']',                       updated_at = now() WHERE code = 'COMP-V4-ELEMENT-BOM';
UPDATE component SET data_driver_path = 'costing_part_process_cost[cost_type=''LABOR'']',     updated_at = now() WHERE code = 'COMP-V4-PROCESS-COST';
UPDATE component SET data_driver_path = 'costing_part_tooling_cost',                          updated_at = now() WHERE code = 'COMP-V4-TOOLING';
UPDATE component SET data_driver_path = 'costing_part_process_cost[cost_type=''CONSUMABLE'']', updated_at = now() WHERE code = 'COMP-V4-CONSUMABLE';
UPDATE component SET data_driver_path = 'costing_part_process_cost[cost_type=''MATERIAL_PROC'']',   updated_at = now() WHERE code = 'COMP-V4-INCOMING-FEE';
UPDATE component SET data_driver_path = 'mat_fee[fee_type=''INCOMING_OTHER'']',                updated_at = now() WHERE code = 'COMP-V4-INCOMING-OTHER';
UPDATE component SET data_driver_path = 'costing_part_process_cost[cost_type=''SEMI_FINISHED_PROC'']', updated_at = now() WHERE code = 'COMP-V4-FINISHED-FEE';
UPDATE component SET data_driver_path = 'mat_fee[fee_type=''FINISHED_OTHER'']',                updated_at = now() WHERE code = 'COMP-V4-FINISHED-OTHER';
UPDATE component SET data_driver_path = 'plating_fee',                                         updated_at = now() WHERE code = 'COMP-V4-PLATING-COST';
UPDATE component SET data_driver_path = 'costing_part_process_cost[cost_type=''POST_PROC'']',  updated_at = now() WHERE code = 'COMP-V4-OUTSOURCE';

-- 单行组件 (driver=NULL): COMP-V4-WEIGHT / COMP-V4-EXCHANGE-RATE / COMP-V4-PLATING-SCHEME (跨表无法驱动)

-- ============================================================
-- 同步更新模板 components_snapshot 反映 data_driver_path 新值
-- (V99 publish 时冻结的 snapshot 已不含新 data_driver_path)
-- ============================================================
DO $$
DECLARE v_template_id UUID;
BEGIN
    SELECT id INTO v_template_id FROM template
    WHERE name = '核价-完整公式版-组件版' AND template_kind = 'COSTING';
    IF v_template_id IS NULL THEN
        RAISE NOTICE 'V100: 模板不存在, 跳过 snapshot 重建';
        RETURN;
    END IF;
    UPDATE template
    SET components_snapshot = (
            SELECT jsonb_agg(jsonb_build_object(
                'id', tc.id,
                'componentId', tc.component_id,
                'tabName', tc.tab_name,
                'sortOrder', tc.sort_order,
                'componentCode', c.code,
                'componentName', c.name,
                'componentType', c.component_type,
                'data_driver_path', c.data_driver_path,
                'fields', c.fields,
                'formulas', c.formulas
            ) ORDER BY tc.sort_order)
            FROM template_component tc
            JOIN component c ON c.id = tc.component_id
            WHERE tc.template_id = v_template_id
        ),
        updated_at = now()
    WHERE id = v_template_id;
    RAISE NOTICE 'V100: 已重建模板 components_snapshot, 含新 data_driver_path';
END $$;
