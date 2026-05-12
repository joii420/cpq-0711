-- ============================================================
-- V151: 料号版本管理 — 主版本号挂载到 mat_customer_part_mapping
--
-- 设计背景: 引入 "料号版本管理" 功能, 版本主键 = (customer_product_no, hf_part_no)
--           为全局唯一. 主版本号 (current_version) 默认 2000, 后续每次升版 +1.
--
-- 本迁移做 3 件事:
--   1. mat_customer_part_mapping 加 current_version INT NOT NULL DEFAULT 2000
--   2. 加全局唯一约束 (customer_product_no, hf_part_no)
--      — 旧约束 (customer_id, customer_product_no) 保留, 因为同一客户对同一产品编号
--        应当只有一行 (业务真值约束)
--   3. 加数据一致性校验: 若现有数据违反新约束, 抛 RAISE EXCEPTION 阻止上线
--
-- 关联:
--   V152 建表 mat_part_version_log (PK = customer_product_no + hf_part_no + version)
--   V153 14 张明细表加 part_version 列
--   V154 mat_process/mat_fee/mat_plating_fee 加 customer_product_no
--   V155 quotation_line_item 加 part_version_locked
--   V156 初始化 mat_part_version_log 基线
-- ============================================================

-- ----- 1. 数据一致性预检 (失败立即终止, 保护已上线数据) -----
DO $$
DECLARE
    v_dup_cnt INT;
    v_null_cnt INT;
BEGIN
    -- 检查 (customer_product_no, hf_part_no) 是否已天然唯一
    SELECT COUNT(*) INTO v_dup_cnt FROM (
        SELECT customer_product_no, hf_part_no, COUNT(*) AS c
        FROM mat_customer_part_mapping
        WHERE customer_product_no IS NOT NULL AND hf_part_no IS NOT NULL
        GROUP BY customer_product_no, hf_part_no
        HAVING COUNT(*) > 1
    ) t;

    IF v_dup_cnt > 0 THEN
        RAISE EXCEPTION 'V151 ABORT: 检测到 % 组 (customer_product_no, hf_part_no) 重复, 违反"全局唯一"假设. 请先清理 mat_customer_part_mapping 数据再跑此迁移.', v_dup_cnt;
    END IF;

    SELECT COUNT(*) INTO v_null_cnt FROM mat_customer_part_mapping
    WHERE customer_product_no IS NULL OR hf_part_no IS NULL;

    RAISE NOTICE 'V151: 数据一致性 OK. customer_product_no/hf_part_no 为 NULL 的行: % (不参与版本管理)', v_null_cnt;
END $$;

-- ----- 2. 加 current_version 列 -----
ALTER TABLE mat_customer_part_mapping
    ADD COLUMN IF NOT EXISTS current_version INT NOT NULL DEFAULT 2000;

COMMENT ON COLUMN mat_customer_part_mapping.current_version IS
    '料号版本管理: (customer_product_no, hf_part_no) 维度的当前激活版本, 默认 2000, 每次升版 +1';

-- ----- 3. 加全局唯一约束 (customer_product_no, hf_part_no) -----
CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_cust_part_global
    ON mat_customer_part_mapping (customer_product_no, hf_part_no)
    WHERE customer_product_no IS NOT NULL AND hf_part_no IS NOT NULL;

-- ----- 4. 校验输出 -----
DO $$
DECLARE
    v_mapping_cnt INT;
    v_default_2000_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_mapping_cnt FROM mat_customer_part_mapping;
    SELECT COUNT(*) INTO v_default_2000_cnt FROM mat_customer_part_mapping WHERE current_version = 2000;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V151 完成';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mapping 总行数: %', v_mapping_cnt;
    RAISE NOTICE '  current_version = 2000 的行数: %', v_default_2000_cnt;
    RAISE NOTICE '  全局唯一索引 uq_mat_cust_part_global 已建立';
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
