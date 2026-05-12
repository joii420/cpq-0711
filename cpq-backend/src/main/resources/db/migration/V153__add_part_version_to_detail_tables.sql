-- ============================================================
-- V153: 14 张数据导入目标表加 part_version 列, 重建 UNIQUE 约束
--
-- 目标:
--   - 每张明细表新增 part_version INT NOT NULL DEFAULT 2000
--   - 所有现有行回填 part_version = 2000 (基线版本)
--   - 旧 UNIQUE 约束扩展加入 part_version 字段
--
-- 排除:
--   - mat_part: 是物料主档, 被 6 张表外键引用 (part_no), 改 PK 影响面太大;
--             料号属性变更通过下游 BOM/工艺等表升版反映
--   - mat_customer_part_mapping: V151 已加 current_version (主版本载体)
--   - quotation_line_item: V155 加 part_version_locked (不同语义)
--
-- 14 张目标表:
--   报价侧: mat_bom, mat_process, mat_fee, mat_plating_plan, mat_plating_fee
--   核价侧: costing_part_process_cost, costing_part_tooling_cost,
--           costing_part_material_bom, costing_part_element_bom,
--           costing_part_quality_check, costing_part_plating,
--           costing_part_plating_fee, costing_part_design_cost,
--           costing_part_weight
--
-- 后续:
--   V154 给 mat_process/mat_fee/mat_plating_fee 加 customer_product_no 并重建 UNIQUE
--   V155 给 quotation_line_item 加 part_version_locked
--   V156 初始化 mat_part_version_log 基线
--   S2 上线 PartVersionService 时, 写库 INSERT 才会真正用上 part_version > 2000
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_bom — 统一 BOM 表 (来料 + 元素)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_bom ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;

DROP INDEX IF EXISTS uq_mat_bom_row;
CREATE UNIQUE INDEX uq_mat_bom_row ON mat_bom(
    bom_type, hf_part_no, seq_no,
    COALESCE(input_material_no, ''),
    COALESCE(element_name, ''),
    part_version
);

-- ════════════════════════════════════════════════════════════════════════════
-- 2. mat_process — 工艺基础 (含 customer_id, BIZ-2)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_process ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;

DROP INDEX IF EXISTS uq_mat_process_row;
DROP INDEX IF EXISTS uq_mat_process_current;
CREATE UNIQUE INDEX uq_mat_process_row
    ON mat_process(customer_id, hf_part_no, part_version, version, seq_no, sub_seq_no);
CREATE UNIQUE INDEX uq_mat_process_current
    ON mat_process(customer_id, hf_part_no, part_version, seq_no, sub_seq_no)
    WHERE is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- 3. mat_fee — 费用 (含 customer_id + dim_* 维度)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_fee ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;

DROP INDEX IF EXISTS uq_mat_fee_current;
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS uq_mat_fee_current;
CREATE UNIQUE INDEX uq_mat_fee_current
    ON mat_fee (
        customer_id, hf_part_no, part_version, fee_type, seq_no,
        COALESCE(dim_input_material_no, ''),
        COALESCE(dim_input_material_name, ''),
        COALESCE(dim_element_name, ''),
        COALESCE(dim_assembly_process, ''),
        COALESCE(dim_sub_seq_no, -1)
    )
    WHERE is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- 4. mat_plating_plan — 报价侧电镀方案 (V125)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_plating_plan ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;

DROP INDEX IF EXISTS uq_mat_plating_plan_row;
CREATE UNIQUE INDEX uq_mat_plating_plan_row
    ON mat_plating_plan(plan_code, version, seq_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 5. mat_plating_fee — 报价侧电镀费用 (V125)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_plating_fee ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;

DROP INDEX IF EXISTS uq_mat_plating_fee_current;
ALTER TABLE mat_plating_fee DROP CONSTRAINT IF EXISTS uq_mat_plating_fee_current;
CREATE UNIQUE INDEX uq_mat_plating_fee_current
    ON mat_plating_fee (
        customer_id, hf_part_no, part_version,
        COALESCE(plating_plan_code, ''),
        COALESCE(plan_version, '')
    )
    WHERE is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- 6. costing_part_process_cost — 工序级单价
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_process_cost ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_process_cost DROP CONSTRAINT IF EXISTS uq_process_cost;
ALTER TABLE costing_part_process_cost
    ADD CONSTRAINT uq_process_cost UNIQUE (hf_part_no, process_no, cost_type, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 7. costing_part_tooling_cost — 模具工装成本
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_tooling_cost ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_tooling_cost DROP CONSTRAINT IF EXISTS uq_tooling;
ALTER TABLE costing_part_tooling_cost
    ADD CONSTRAINT uq_tooling UNIQUE (hf_part_no, process_no, seq_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 8. costing_part_material_bom — 材料 BOM
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_material_bom ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_material_bom DROP CONSTRAINT IF EXISTS uq_material_bom;
ALTER TABLE costing_part_material_bom
    ADD CONSTRAINT uq_material_bom UNIQUE (hf_part_no, seq_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 9. costing_part_element_bom — 元素 BOM
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_element_bom ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_element_bom DROP CONSTRAINT IF EXISTS uq_element_bom;
ALTER TABLE costing_part_element_bom
    ADD CONSTRAINT uq_element_bom UNIQUE (input_material_no, seq_no, element_code, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 10. costing_part_quality_check — 质量检验
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_quality_check ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_quality_check DROP CONSTRAINT IF EXISTS uq_qc;
ALTER TABLE costing_part_quality_check
    ADD CONSTRAINT uq_qc UNIQUE (hf_part_no, stage, primary_seq_no, seq_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 11. costing_part_plating — 成品电镀
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_plating ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_plating DROP CONSTRAINT IF EXISTS uq_plating;
ALTER TABLE costing_part_plating
    ADD CONSTRAINT uq_plating UNIQUE (plating_no, version_number, seq_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 12. costing_part_plating_fee — 核价侧电镀费用 (V125)
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_plating_fee ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_plating_fee DROP CONSTRAINT IF EXISTS uq_costing_part_plating_fee;
ALTER TABLE costing_part_plating_fee
    ADD CONSTRAINT uq_costing_part_plating_fee UNIQUE
        (hf_part_no, plating_plan_code, plan_version, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 13. costing_part_design_cost — 设计成本
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_design_cost ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_design_cost DROP CONSTRAINT IF EXISTS uq_design_cost;
ALTER TABLE costing_part_design_cost
    ADD CONSTRAINT uq_design_cost UNIQUE (hf_part_no, design_drawing_no, version_number, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 14. costing_part_weight — 料号重量
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE costing_part_weight ADD COLUMN IF NOT EXISTS part_version INT NOT NULL DEFAULT 2000;
ALTER TABLE costing_part_weight DROP CONSTRAINT IF EXISTS costing_part_weight_hf_part_no_key;
ALTER TABLE costing_part_weight
    ADD CONSTRAINT uq_costing_part_weight UNIQUE (hf_part_no, part_version);

-- ════════════════════════════════════════════════════════════════════════════
-- 列注释 (统一)
-- ════════════════════════════════════════════════════════════════════════════
COMMENT ON COLUMN mat_bom.part_version IS                    '料号版本管理: 关联 mat_customer_part_mapping.current_version, 默认 2000';
COMMENT ON COLUMN mat_process.part_version IS                '料号版本管理: 与旧 version 列共存 — version=VersionedWriter批次版, part_version=料号版本';
COMMENT ON COLUMN mat_fee.part_version IS                    '料号版本管理: 与旧 version 列共存';
COMMENT ON COLUMN mat_plating_plan.part_version IS           '料号版本管理: 与旧 version 列(电镀方案版本号)共存';
COMMENT ON COLUMN mat_plating_fee.part_version IS            '料号版本管理: 与旧 version 列共存';
COMMENT ON COLUMN costing_part_process_cost.part_version IS  '料号版本管理: 默认 2000';
COMMENT ON COLUMN costing_part_tooling_cost.part_version IS  '料号版本管理: 默认 2000';
COMMENT ON COLUMN costing_part_material_bom.part_version IS  '料号版本管理: 默认 2000';
COMMENT ON COLUMN costing_part_element_bom.part_version IS   '料号版本管理: 默认 2000';
COMMENT ON COLUMN costing_part_quality_check.part_version IS '料号版本管理: 默认 2000';
COMMENT ON COLUMN costing_part_plating.part_version IS       '料号版本管理: 与 version_number(电镀方案版本号)共存';
COMMENT ON COLUMN costing_part_plating_fee.part_version IS   '料号版本管理: 与 plan_version 共存';
COMMENT ON COLUMN costing_part_design_cost.part_version IS   '料号版本管理: 与 version_number 共存';
COMMENT ON COLUMN costing_part_weight.part_version IS        '料号版本管理: 默认 2000';

-- ════════════════════════════════════════════════════════════════════════════
-- 校验输出
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_total_rows INT := 0;
    v_table_cnt INT := 0;
    r RECORD;
BEGIN
    FOR r IN
        SELECT t::text AS tname FROM unnest(ARRAY[
            'mat_bom','mat_process','mat_fee','mat_plating_plan','mat_plating_fee',
            'costing_part_process_cost','costing_part_tooling_cost','costing_part_material_bom',
            'costing_part_element_bom','costing_part_quality_check','costing_part_plating',
            'costing_part_plating_fee','costing_part_design_cost','costing_part_weight'
        ]) AS t
    LOOP
        EXECUTE format('SELECT COUNT(*) FROM %I WHERE part_version = 2000', r.tname) INTO v_table_cnt;
        v_total_rows := v_total_rows + v_table_cnt;
        RAISE NOTICE '  % part_version=2000: % 行', r.tname, v_table_cnt;
    END LOOP;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V153 完成: 14 张表 part_version=2000 总行数 = %', v_total_rows;
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
