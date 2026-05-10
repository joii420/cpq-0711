-- ============================================================
-- V145: 模板公式层基础设施 (Stage 1 / 共 4 阶段)
-- ============================================================
-- 目标：把"公式"作为模板的延伸功能 — 每个模板可以定义多个公式，
--       公式能引用同模板内的组件字段、其他模板公式（DAG）、全局变量。
--       求值结果用于 Excel 视图（excel_view_config FORMULA 字段引用 [公式名]），
--       让用户在 UI 改公式立即生效，不需要写 SQL 迁移。
--
-- 范围（Stage 1）：基础设施 + 简单算术（不含聚合 SUM_OVER，留给 Stage 2）
--
-- JSONB 数组结构：
--   [
--     {
--       "name": "管理费",
--       "expression": "([纯材料成本] + [加工费] + [电镀成本]) * @管理费比例",
--       "data_type": "DECIMAL(18,4)",
--       "depends_on": ["纯材料成本", "加工费", "电镀成本", "@管理费比例"],
--       "description": "管理费 = 加价基数 × 管理费比例"
--     }
--   ]
--
-- 引用类型解析优先级（FormulaEngine 改造点）：
--   1. [名称]      → 模板公式（template.formulas） → col_key（excel_view_config） → 兜底
--   2. @名称       → 全局变量（global_variable_definition）
--   3. [组件code.字段] → 组件字段（component_field）
--
-- 设计约束：
--   * 仅 DRAFT 模板可改 formulas（跟 addComponent 一致）
--   * expression 长度上限 5000 字符（防滥用，由后端校验）
--   * 拓扑排序检测循环依赖：A→B→A 直接报错拒绝保存
--   * Stage 1 不允许聚合函数（SUM_OVER 等），明确报错"暂不支持聚合，请等 Stage 2"
-- ============================================================

ALTER TABLE template
    ADD COLUMN IF NOT EXISTS formulas JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN template.formulas IS
    '模板公式数组（V145，Stage 1）: [{name, expression, data_type, depends_on, description}]。'
    '求值优先级：[名称]→template.formulas → col_key → component_field；@名称→global_variable。'
    '仅 DRAFT 状态可改；保存时拓扑排序检测循环依赖。';

-- 给现有 NULL 值兜底（IF NOT EXISTS 不会重复执行 DEFAULT，但保证幂等）
UPDATE template SET formulas = '[]'::jsonb WHERE formulas IS NULL;

-- 自检 + 报告
DO $$
DECLARE
    total_templates INT;
    templates_with_formulas INT;
BEGIN
    SELECT COUNT(*) INTO total_templates FROM template;
    SELECT COUNT(*) INTO templates_with_formulas
        FROM template
        WHERE formulas IS NOT NULL AND jsonb_array_length(formulas) > 0;

    RAISE NOTICE '[V145] template.formulas column ready. total templates=%, with formulas=%',
        total_templates, templates_with_formulas;
    RAISE NOTICE '[V145] Stage 1 (infrastructure + simple arithmetic) complete. SUM_OVER/FILTER/MAP reserved for Stage 2.';
END $$;
