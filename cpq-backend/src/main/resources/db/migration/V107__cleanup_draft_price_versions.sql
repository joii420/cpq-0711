-- V107: 清理 DRAFT 价格版本数据 (P4: 收尾)
--
-- 背景: V103 之前由「核价基础数据」UI 创建的 2 个 ELEMENT DRAFT 版本.
--   - v11 (928a7cd5...): 空, 0 明细行
--   - v2001 (90e9024c...): 3 行 element_price 明细
-- 决策依据 (用户已确认):
--   #5 选项 A: 直接 DELETE 连同明细一起清理
--
-- 安全前提 (V107 编写时已验证):
--   - 无任何 costing_summary 引用这 2 个 version_id (FK 检查通过)
--   - 实际删除按 status='DRAFT' 过滤, 不会误碰 PUBLISHED/ARCHIVED 版本
--
-- 幂等: DELETE 已不存在 → 0 行影响, 重复执行无副作用.

DO $$
DECLARE
    deleted_details INT;
    deleted_versions INT;
    safety_check INT;
BEGIN
    -- 安全门: 检查是否有 costing_summary 引用 DRAFT 版本; 有则 ABORT
    SELECT COUNT(*) INTO safety_check
    FROM costing_summary cs
    WHERE cs.element_version_id  IN (SELECT id FROM costing_price_version WHERE status='DRAFT')
       OR cs.material_version_id IN (SELECT id FROM costing_price_version WHERE status='DRAFT')
       OR cs.exchange_version_id IN (SELECT id FROM costing_price_version WHERE status='DRAFT');
    IF safety_check > 0 THEN
        RAISE EXCEPTION 'V107 ABORT: 存在 % 个核价单引用 DRAFT 版本, 拒绝清理', safety_check;
    END IF;

    -- 1. 清明细 (3 张明细表 × DRAFT version_id)
    WITH del AS (
        DELETE FROM costing_element_price
        WHERE version_id IN (SELECT id FROM costing_price_version WHERE status = 'DRAFT')
        RETURNING 1
    )
    SELECT COUNT(*) INTO deleted_details FROM del;
    RAISE NOTICE 'V107: 删除 element_price 明细 % 行', deleted_details;

    DELETE FROM costing_material_price
    WHERE version_id IN (SELECT id FROM costing_price_version WHERE status = 'DRAFT');

    DELETE FROM costing_exchange_rate
    WHERE version_id IN (SELECT id FROM costing_price_version WHERE status = 'DRAFT');

    -- 2. 清 DRAFT 版本本身
    WITH del AS (
        DELETE FROM costing_price_version WHERE status = 'DRAFT' RETURNING 1
    )
    SELECT COUNT(*) INTO deleted_versions FROM del;
    RAISE NOTICE 'V107: 删除 DRAFT 版本 % 个', deleted_versions;
END $$;
