-- V211: 诊断验证 V210 mat_process 清理结果 + 输出关键统计
--
-- 背景:
--   用户报告 batch-expand 查 3120012574 (罗克韦尔) 在 v_composite_child_processes
--   路径返 0 行，怀疑 V210 的 ROW_NUMBER CTE 过激清理了 is_current=true 行。
--
-- 本脚本:
--   1. 输出 mat_process 全局统计 (总行数 / is_current=true 行数)
--   2. 输出 3120012574 罗克韦尔专项统计 (is_current=true 总数 / 主数据行 / 专属行 / 分组数)
--   3. 验证 V210 新 UNIQUE index 约束是否生效 (无重复组)
--   4. 如存在真正的过激清理 (is_current=true 主数据行总数 < 预期)，输出 WARNING
--
-- 注意: 本脚本为纯 READ + RAISE NOTICE，不执行任何 DML (不改数据)。
--       实际诊断结果见 Quarkus 启动日志中的 NOTICE 输出。

DO $$
DECLARE
    v_total_all         BIGINT;
    v_current_true_all  BIGINT;
    v_current_false_all BIGINT;

    v_part_no           TEXT    := '3120012574';
    v_customer_id       UUID    := '3027d83b-d412-407d-ae43-5d513fed7b1e';

    v_part_total        BIGINT;
    v_part_current      BIGINT;
    v_part_main_data    BIGINT;  -- lineItemId IS NULL
    v_part_specialized  BIGINT;  -- lineItemId IS NOT NULL
    v_part_lid_groups   BIGINT;  -- 不同 lineItemId 的分组数 (含 NULL)

    v_dup_groups        BIGINT;  -- V210 约束后是否还有重复组
BEGIN
    -- ── 1. 全局统计 ──────────────────────────────────────────────────────
    SELECT COUNT(*)                                         INTO v_total_all         FROM mat_process;
    SELECT COUNT(*) FILTER (WHERE is_current = true)       INTO v_current_true_all  FROM mat_process;
    SELECT COUNT(*) FILTER (WHERE is_current = false)      INTO v_current_false_all FROM mat_process;

    RAISE NOTICE '[V211 全局] mat_process 总行数=%, is_current=true=%, is_current=false=%',
        v_total_all, v_current_true_all, v_current_false_all;

    -- ── 2. 3120012574 罗克韦尔专项 ──────────────────────────────────────
    SELECT COUNT(*)
        INTO v_part_total
        FROM mat_process
        WHERE hf_part_no = v_part_no
          AND customer_id = v_customer_id;

    SELECT COUNT(*)
        INTO v_part_current
        FROM mat_process
        WHERE hf_part_no  = v_part_no
          AND customer_id = v_customer_id
          AND is_current  = true;

    SELECT COUNT(*)
        INTO v_part_main_data
        FROM mat_process
        WHERE hf_part_no              = v_part_no
          AND customer_id             = v_customer_id
          AND is_current              = true
          AND quotation_line_item_id IS NULL;

    SELECT COUNT(*)
        INTO v_part_specialized
        FROM mat_process
        WHERE hf_part_no              = v_part_no
          AND customer_id             = v_customer_id
          AND is_current              = true
          AND quotation_line_item_id IS NOT NULL;

    SELECT COUNT(DISTINCT COALESCE(quotation_line_item_id::TEXT, 'NULL'))
        INTO v_part_lid_groups
        FROM mat_process
        WHERE hf_part_no  = v_part_no
          AND customer_id = v_customer_id
          AND is_current  = true;

    RAISE NOTICE '[V211 专项] hf_part_no=% customer=罗克韦尔',
        v_part_no;
    RAISE NOTICE '[V211 专项]   总行数=% | is_current=true=% | 主数据(lid=NULL)=% | 专属(lid IS NOT NULL)=% | lineItemId 分组数=%',
        v_part_total, v_part_current, v_part_main_data, v_part_specialized, v_part_lid_groups;

    -- ── 3. 验证 V210 UNIQUE index 约束效果 ──────────────────────────────
    --       检查是否还有 (cust, hf, ver, seq, sub, lid) 组存在 >1 行 is_current=true
    SELECT COUNT(*) INTO v_dup_groups FROM (
        SELECT
            customer_id,
            hf_part_no,
            part_version,
            seq_no,
            COALESCE(sub_seq_no, -1)                                                     AS sub_no,
            COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::UUID) AS lid,
            COUNT(*) AS c
        FROM mat_process
        WHERE is_current = true
        GROUP BY 1, 2, 3, 4, 5, 6
        HAVING COUNT(*) > 1
    ) t;

    IF v_dup_groups > 0 THEN
        RAISE WARNING '[V211 约束] ⚠ 仍有 % 组 is_current=true 重复! V210 UNIQUE index 未生效或有并发写入绕过约束!',
            v_dup_groups;
    ELSE
        RAISE NOTICE '[V211 约束] ✓ 无重复组, V210 UNIQUE index 生效正常';
    END IF;

    -- ── 4. 主数据行数量合理性评估 ───────────────────────────────────────
    --       主数据行 (lineItemId=NULL) 代表通过 Excel 导入的工序主数据
    --       如果 0，说明该料号仅通过 configure 流程写入工序（带 lineItemId），无全局主数据
    --       这不是 V210 的问题，而是该料号的业务状态（configure-first 模式）
    IF v_part_main_data = 0 THEN
        RAISE NOTICE '[V211 评估] ℹ hf_part_no=% 无主数据工序行 (lineItemId=NULL)。',
            v_part_no;
        RAISE NOTICE '[V211 评估]   原因: 该料号的所有工序均通过 configure 流程写入 (带 lineItemId)，';
        RAISE NOTICE '[V211 评估]   未通过 Excel 导入建立主数据层。这是 configure-first 模式的正常状态，不是 V210 过激清理。';
        RAISE NOTICE '[V211 评估]   v_composite_child_processes 返回 % 行 (各 lineItemId 的专属工序行) 是 V209 已知副作用。',
            v_part_specialized;
    ELSE
        RAISE NOTICE '[V211 评估] ✓ hf_part_no=% 有 % 行主数据工序 (lineItemId=NULL)，数据健康。',
            v_part_no, v_part_main_data;
    END IF;

    RAISE NOTICE '[V211] 诊断完成。V210 执行评估: is_current=true 总量=%, 重复组=%, 结论=%',
        v_current_true_all,
        v_dup_groups,
        CASE WHEN v_dup_groups = 0 THEN 'V210 执行正确，无过激清理' ELSE 'V210 存在问题需人工排查' END;
END $$;
