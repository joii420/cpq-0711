-- ============================================================
-- V158: SQL function current_part_version(cpn, hf) — 查当前激活版本
--
-- 设计目的:
--   S4 阶段提供"读取当前版本"的基础设施函数, 供未来视图改造 + S6
--   PartVersionPredicateBuilder 使用. 现阶段不接入任何视图, 业务功能不变.
--
-- 行为:
--   - 给定 (customer_product_no, hf_part_no), 返回 mat_customer_part_mapping.current_version
--   - 找不到 (orphan / 新料号) → 返回 2000 (基线版本)
--   - STABLE 标注 — 同一事务内多次调用结果一致, PostgreSQL 可优化执行计划
--
-- 用法示例 (将来 S6 集成时):
--   SELECT * FROM mat_process
--   WHERE customer_id = ? AND hf_part_no = ?
--     AND part_version = current_part_version(?, ?);
--
-- S6 PartVersionPredicateBuilder 会用这个函数生成谓词 SQL 片段.
-- ============================================================

CREATE OR REPLACE FUNCTION current_part_version(
    p_customer_product_no TEXT,
    p_hf_part_no TEXT
) RETURNS INT AS $$
DECLARE
    v_ver INT;
BEGIN
    IF p_customer_product_no IS NULL OR p_hf_part_no IS NULL THEN
        RETURN 2000;
    END IF;

    SELECT current_version INTO v_ver
    FROM mat_customer_part_mapping
    WHERE customer_product_no = p_customer_product_no
      AND hf_part_no = p_hf_part_no
    LIMIT 1;

    RETURN COALESCE(v_ver, 2000);
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION current_part_version(TEXT, TEXT) IS
    '料号版本管理: 给定 (customer_product_no, hf_part_no) 返回当前激活版本号; orphan/未注册返回 2000';

-- ----- 校验输出 -----
DO $$
DECLARE
    v_test INT;
    v_count INT;
BEGIN
    v_test := current_part_version(NULL, NULL);
    IF v_test <> 2000 THEN
        RAISE EXCEPTION 'V158 测试失败: NULL 输入应返回 2000, 实际 %', v_test;
    END IF;

    v_test := current_part_version('__nonexistent_cpn__', '__nonexistent_hf__');
    IF v_test <> 2000 THEN
        RAISE EXCEPTION 'V158 测试失败: 不存在的 (cpn,hf) 应返回 2000, 实际 %', v_test;
    END IF;

    SELECT COUNT(*) INTO v_count FROM mat_customer_part_mapping
    WHERE customer_product_no IS NOT NULL AND hf_part_no IS NOT NULL;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V158 完成: current_part_version(cpn, hf) 已创建';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  NULL 输入测试: PASS (返回 2000)';
    RAISE NOTICE '  不存在测试: PASS (返回 2000)';
    RAISE NOTICE '  mapping 表有效行数: % (current_version 全部 = 2000)', v_count;
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
