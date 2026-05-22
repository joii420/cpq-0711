-- V210: 修 mat_process UNIQUE index 缺失 quotation_line_item_id 维度 + 清理历史累积重复行
--
-- 背景 (遗留 Bug):
--   V206 给 mat_process 加了 quotation_line_item_id 列，用于按报价单上下文隔离工序行。
--   但 V153 定义的 uq_mat_process_current UNIQUE index:
--     (customer_id, hf_part_no, part_version, seq_no, sub_seq_no) WHERE is_current=true
--   未包含 quotation_line_item_id，导致：
--     同一 (hf_part_no, seq_no) 在不同 lineItemId 下可以各自 is_current=true，
--     每次 configure 调用 resolvePart DELETE 老行 + INSERT 新行但只删本 lineItemId 的，
--     历史残留其他 lineItemId 的 is_current=true 行永久积累。
--   结果：mat_process 里 3120012574 seq_no=1 可能积累 80+ 条 is_current=true 行，
--   batch-expand 查全部 is_current=true 行返回全量 → 工序 Tab 显示所有工序+重复行。
--
-- 修复方案:
--   Step 1: 清理历史重复 — 同 (cust, hf, ver, seq, sub, lineItemId) 组仅保留最新行
--   Step 2: DROP 老 index + 建含 quotation_line_item_id 的新 index
--   Step 3: 自检验证无重复
--
-- 语义约定:
--   quotation_line_item_id IS NULL  = 主数据行（来自 Excel 导入，全局共享）
--   quotation_line_item_id IS NOT NULL = 该报价行的专属工序（仅在该上下文可见）
--
-- 注意:
--   PG 的 UNIQUE index 对 NULL 不参与唯一性 (NULL <> NULL)，
--   所以 index 表达式里用 COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::UUID)
--   让 NULL 统一折叠为哨兵值参与唯一性约束。

-- ════════════════════════════════════════════════════════════════════════════
-- Step 1: 清理历史 is_current=true 重复行
--         同 (customer_id, hf_part_no, part_version, seq_no, sub_seq_no, quotation_line_item_id) 组
--         只保留 created_at DESC, id DESC 最新一行（is_current=true），其余标 false
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_cleaned INT;
BEGIN
    WITH duplicates AS (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY
                       customer_id,
                       hf_part_no,
                       part_version,
                       seq_no,
                       COALESCE(sub_seq_no::TEXT, '__NULL__'),
                       COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::UUID)
                   ORDER BY created_at DESC, id DESC
               ) AS rn
        FROM mat_process
        WHERE is_current = true
    ),
    to_clean AS (
        SELECT id FROM duplicates WHERE rn > 1
    )
    UPDATE mat_process SET is_current = false
    WHERE id IN (SELECT id FROM to_clean);

    GET DIAGNOSTICS v_cleaned = ROW_COUNT;
    RAISE NOTICE 'V210 Step 1: 清理 % 条历史 is_current=true 重复行', v_cleaned;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- Step 2: DROP 老 UNIQUE index + 建含 quotation_line_item_id 的新 index
-- ════════════════════════════════════════════════════════════════════════════

-- 2.1 DROP 老 index (V153 建，不含 quotation_line_item_id)
DROP INDEX IF EXISTS uq_mat_process_current;

-- 2.2 建新 UNIQUE index，含 COALESCE(quotation_line_item_id, sentinel) 让 NULL 也参与唯一性
--     约束语义：同一 (客户, 料号, 版本, 序号, 二级序号, lineItemId) 只能有一行 is_current=true
--     其中 lineItemId=NULL (主数据) 和 lineItemId=非NULL (报价专属) 各自独立约束
CREATE UNIQUE INDEX uq_mat_process_current
    ON mat_process (
        customer_id,
        hf_part_no,
        part_version,
        seq_no,
        COALESCE(sub_seq_no, -1),
        COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::UUID)
    )
    WHERE is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- Step 3: 自检 — 确认无 is_current=true 重复组残留
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_remaining_dup INT;
    v_total_current INT;
BEGIN
    -- 检查重复组数
    SELECT COUNT(*) INTO v_remaining_dup FROM (
        SELECT
            customer_id, hf_part_no, part_version, seq_no,
            COALESCE(sub_seq_no, -1)                                           AS sub_no,
            COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::UUID) AS lid,
            COUNT(*) AS c
        FROM mat_process
        WHERE is_current = true
        GROUP BY 1, 2, 3, 4, 5, 6
        HAVING COUNT(*) > 1
    ) t;

    -- 统计当前 is_current=true 行数（供人工核对）
    SELECT COUNT(*) INTO v_total_current FROM mat_process WHERE is_current = true;

    IF v_remaining_dup > 0 THEN
        RAISE EXCEPTION 'V210 自检失败: 仍有 % 组 is_current=true 重复 (总当前行数=%), 请人工排查',
            v_remaining_dup, v_total_current;
    END IF;

    RAISE NOTICE 'V210 自检通过: 无 is_current=true 重复 (当前行总数=%)', v_total_current;
END $$;
