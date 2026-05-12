-- ============================================================
-- V157: 撤销 V154 把 customer_product_no 加入 UNIQUE 的改动
--
-- 设计背景:
--   V154 给 mat_process / mat_fee / mat_plating_fee 加 customer_product_no
--   并把它加入了 UNIQUE 约束 (COALESCE(customer_product_no, '') 在 key 中).
--
--   但 VersionedWriter.insertNewRow 写新行时不带 customer_product_no 列,
--   新行 cpn=NULL → COALESCE='' 桶, 与 V154 回填行 cpn='X' 不同桶,
--   导致同 (customer_id, hf_part_no, seq_no, sub_seq_no) 出现两个 is_current=true,
--   破坏业务约束.
--
--   S1 阶段的目标是"加列不影响现有功能", 不应在 VersionedWriter 不感知 cpn 的
--   情况下把 cpn 加进 UNIQUE.
--
-- 处理:
--   - 撤销 V154 重建的 UNIQUE, 恢复为 V153 形式 (含 part_version, 不含 cpn)
--   - customer_product_no 列保留 (V154 已加且已回填)
--   - S2 阶段统一改 VersionedWriter 写入 cpn 后, 再把 cpn 加进 UNIQUE
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_process — UNIQUE 回退到 V153 形式
-- ════════════════════════════════════════════════════════════════════════════
DROP INDEX IF EXISTS uq_mat_process_row;
DROP INDEX IF EXISTS uq_mat_process_current;

CREATE UNIQUE INDEX uq_mat_process_row
    ON mat_process(customer_id, hf_part_no, part_version, version, seq_no, sub_seq_no);

CREATE UNIQUE INDEX uq_mat_process_current
    ON mat_process(customer_id, hf_part_no, part_version, seq_no, sub_seq_no)
    WHERE is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- 2. mat_fee — UNIQUE 回退到 V153 形式
-- ════════════════════════════════════════════════════════════════════════════
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
-- 3. mat_plating_fee — UNIQUE 回退到 V153 形式
-- ════════════════════════════════════════════════════════════════════════════
DROP INDEX IF EXISTS uq_mat_plating_fee_current;
ALTER TABLE mat_plating_fee DROP CONSTRAINT IF EXISTS uq_mat_plating_fee_current;

CREATE UNIQUE INDEX uq_mat_plating_fee_current
    ON mat_plating_fee (
        customer_id, hf_part_no, part_version,
        COALESCE(plating_plan_code, ''),
        COALESCE(plan_version, '')
    )
    WHERE is_current = true;

-- ----- 校验输出 -----
DO $$
BEGIN
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V157 完成: 撤销 cpn 加入 UNIQUE 的改动';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mat_process: uq_mat_process_row / uq_mat_process_current 回到 V153 形式 (含 part_version 不含 cpn)';
    RAISE NOTICE '  mat_fee: uq_mat_fee_current 回到 V153 形式';
    RAISE NOTICE '  mat_plating_fee: uq_mat_plating_fee_current 回到 V153 形式';
    RAISE NOTICE '  customer_product_no 列保留 (V154 已加且回填), S2 才会真正用上';
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
