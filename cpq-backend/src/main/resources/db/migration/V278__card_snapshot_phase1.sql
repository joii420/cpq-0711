-- ===========================================================================
-- V278: 报价单整份快照 Phase 1
--   1. quotation_view_structure — 报价单级 4 份结构快照（grain = quotation × view_kind）
--   2. quotation_line_item — 产品行级 4 份值快照列 + 时间戳列
--   3. component — 组件级行键配置列 row_key_fields
--   4. 存量多行可编辑核心组件的行键预填（IS NULL 守卫，幂等）
-- ===========================================================================

-- --------------------------------------------------------------------------
-- 1. 报价单级 4 份结构快照表
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quotation_view_structure (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id  UUID        NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
    view_kind     TEXT        NOT NULL CHECK (view_kind IN ('QUOTE_CARD','QUOTE_EXCEL','COSTING_CARD','COSTING_EXCEL')),
    structure     JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_quotation_view_structure UNIQUE (quotation_id, view_kind)
);
CREATE INDEX IF NOT EXISTS idx_qvs_quotation ON quotation_view_structure (quotation_id);

-- --------------------------------------------------------------------------
-- 2. 产品行级 4 份值快照列（P2 物理分开）
-- --------------------------------------------------------------------------
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS quote_card_values    JSONB;
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS quote_excel_values   JSONB;
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS costing_card_values  JSONB;
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS costing_excel_values JSONB;
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS card_snapshot_at     TIMESTAMPTZ;
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS quote_values_at      TIMESTAMPTZ;

-- --------------------------------------------------------------------------
-- 3. 组件级行键配置
-- --------------------------------------------------------------------------
ALTER TABLE component ADD COLUMN IF NOT EXISTS row_key_fields JSONB;

-- --------------------------------------------------------------------------
-- 4. 存量核心多行可编辑组件行键预填（IS NULL 守卫，不覆盖已有配置）
--    字段名以上方 SELECT 核对的真实 fields[].name 为准
-- --------------------------------------------------------------------------

-- 选配-元素含量（driver=$composite_child_elements_mirror，可编辑=组成用量）
-- 复合键：子件 + 元素（同一元素跨子件重复出现，必须复合唯一）
UPDATE component
SET row_key_fields = '["子件","元素"]'::jsonb
WHERE id = 'dae85db8-cf47-44df-890d-516625a598da'
  AND row_key_fields IS NULL;

-- 选配-工序列表（driver=$composite_child_processes_mirror，可编辑=成材率（LIST_FORMULA））
-- 复合键：子件 + 工序代码
UPDATE component
SET row_key_fields = '["子件","工序代码"]'::jsonb
WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f'
  AND row_key_fields IS NULL;

-- 选配-组合工艺（driver=$composite_process_mirror，可编辑=工艺单价）
-- 单字段键：工艺代码（单产品场景，工艺代码在单产品内唯一）
UPDATE component
SET row_key_fields = '["工艺代码"]'::jsonb
WHERE id = '3bbde78f-718c-4544-85f2-0a25397b7eaa'
  AND row_key_fields IS NULL;

-- 工序（driver=$gx_view，可编辑=单价）
-- 复合键：子件 + 工序代码
UPDATE component
SET row_key_fields = '["子件","工序代码"]'::jsonb
WHERE id = '5c47fb41-f092-4ef8-a960-bce07c93ded0'
  AND row_key_fields IS NULL;
