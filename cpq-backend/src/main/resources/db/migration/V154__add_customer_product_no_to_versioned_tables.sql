-- ============================================================
-- V154: mat_process / mat_fee / mat_plating_fee 加 customer_product_no
--
-- 设计背景:
--   版本主键 = (customer_product_no, hf_part_no) 全局唯一.
--   这 3 张表原本只有 customer_id + hf_part_no, 需补 customer_product_no
--   才能加入版本管理.
--
-- 用户决策 #2 (复用语义, 旧数据只关联一个):
--   - 加 customer_product_no 列 (保留 customer_id, 与旧字段共存)
--   - 回填策略: 每行 (customer_id, hf_part_no) 取 mat_customer_part_mapping
--     最早 created_at + 最小 id 的那一行的 customer_product_no
--   - 一个 hf_part_no 可能对应同客户多个 customer_product_no, 旧数据仅关联首个
--   - 后续报价单按 customer_product_no 拆产品卡片, 实际只会匹配到具体一个
--
-- Orphan 处理: 若 mapping 表无对应行, customer_product_no 留 NULL.
--   这些 orphan 行不参与版本管理 (S2 PartVersionService 会跳过).
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_process — 加 customer_product_no + 回填
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_process ADD COLUMN IF NOT EXISTS customer_product_no VARCHAR(64);

UPDATE mat_process p
SET customer_product_no = (
    SELECT m.customer_product_no
    FROM mat_customer_part_mapping m
    WHERE m.customer_id = p.customer_id
      AND m.hf_part_no = p.hf_part_no
      AND m.customer_product_no IS NOT NULL
    ORDER BY m.created_at ASC, m.id ASC
    LIMIT 1
)
WHERE p.customer_product_no IS NULL;

-- 重建 V153 刚建的 UNIQUE, 把 customer_product_no 加入关键字段
DROP INDEX IF EXISTS uq_mat_process_row;
DROP INDEX IF EXISTS uq_mat_process_current;
CREATE UNIQUE INDEX uq_mat_process_row
    ON mat_process(
        customer_id, hf_part_no,
        COALESCE(customer_product_no, ''),
        part_version, version, seq_no, sub_seq_no
    );
CREATE UNIQUE INDEX uq_mat_process_current
    ON mat_process(
        customer_id, hf_part_no,
        COALESCE(customer_product_no, ''),
        part_version, seq_no, sub_seq_no
    )
    WHERE is_current = true;

COMMENT ON COLUMN mat_process.customer_product_no IS
    '料号版本管理: 与 hf_part_no 组合成版本主键. 回填策略: 取 mat_customer_part_mapping 最早一行的 cpn';

-- ════════════════════════════════════════════════════════════════════════════
-- 2. mat_fee — 加 customer_product_no + 回填
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_fee ADD COLUMN IF NOT EXISTS customer_product_no VARCHAR(64);

UPDATE mat_fee f
SET customer_product_no = (
    SELECT m.customer_product_no
    FROM mat_customer_part_mapping m
    WHERE m.customer_id = f.customer_id
      AND m.hf_part_no = f.hf_part_no
      AND m.customer_product_no IS NOT NULL
    ORDER BY m.created_at ASC, m.id ASC
    LIMIT 1
)
WHERE f.customer_product_no IS NULL;

DROP INDEX IF EXISTS uq_mat_fee_current;
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS uq_mat_fee_current;
CREATE UNIQUE INDEX uq_mat_fee_current
    ON mat_fee (
        customer_id, hf_part_no,
        COALESCE(customer_product_no, ''),
        part_version, fee_type, seq_no,
        COALESCE(dim_input_material_no, ''),
        COALESCE(dim_input_material_name, ''),
        COALESCE(dim_element_name, ''),
        COALESCE(dim_assembly_process, ''),
        COALESCE(dim_sub_seq_no, -1)
    )
    WHERE is_current = true;

COMMENT ON COLUMN mat_fee.customer_product_no IS
    '料号版本管理: 与 hf_part_no 组合成版本主键';

-- ════════════════════════════════════════════════════════════════════════════
-- 3. mat_plating_fee — 加 customer_product_no + 回填
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_plating_fee ADD COLUMN IF NOT EXISTS customer_product_no VARCHAR(64);

UPDATE mat_plating_fee pf
SET customer_product_no = (
    SELECT m.customer_product_no
    FROM mat_customer_part_mapping m
    WHERE m.customer_id = pf.customer_id
      AND m.hf_part_no = pf.hf_part_no
      AND m.customer_product_no IS NOT NULL
    ORDER BY m.created_at ASC, m.id ASC
    LIMIT 1
)
WHERE pf.customer_product_no IS NULL;

DROP INDEX IF EXISTS uq_mat_plating_fee_current;
ALTER TABLE mat_plating_fee DROP CONSTRAINT IF EXISTS uq_mat_plating_fee_current;
CREATE UNIQUE INDEX uq_mat_plating_fee_current
    ON mat_plating_fee (
        customer_id, hf_part_no,
        COALESCE(customer_product_no, ''),
        part_version,
        COALESCE(plating_plan_code, ''),
        COALESCE(plan_version, '')
    )
    WHERE is_current = true;

COMMENT ON COLUMN mat_plating_fee.customer_product_no IS
    '料号版本管理: 与 hf_part_no 组合成版本主键';

-- ════════════════════════════════════════════════════════════════════════════
-- 校验输出
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_process_total INT; v_process_orphan INT;
    v_fee_total INT;     v_fee_orphan INT;
    v_pfee_total INT;    v_pfee_orphan INT;
BEGIN
    SELECT COUNT(*), COUNT(*) FILTER (WHERE customer_product_no IS NULL)
        INTO v_process_total, v_process_orphan FROM mat_process;
    SELECT COUNT(*), COUNT(*) FILTER (WHERE customer_product_no IS NULL)
        INTO v_fee_total, v_fee_orphan FROM mat_fee;
    SELECT COUNT(*), COUNT(*) FILTER (WHERE customer_product_no IS NULL)
        INTO v_pfee_total, v_pfee_orphan FROM mat_plating_fee;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V154 完成: customer_product_no 已加且回填';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mat_process       : 总 % / orphan(无 mapping) %', v_process_total, v_process_orphan;
    RAISE NOTICE '  mat_fee           : 总 % / orphan(无 mapping) %', v_fee_total, v_fee_orphan;
    RAISE NOTICE '  mat_plating_fee   : 总 % / orphan(无 mapping) %', v_pfee_total, v_pfee_orphan;
    RAISE NOTICE '  Orphan 行 customer_product_no 留 NULL, 不参与版本管理';
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
