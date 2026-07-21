-- V345: 清理无子行的 material_bom 空壳主表 + 校验 V344 的 cz_view 谓词确已生效
-- spec: docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md
--
-- 背景：V344 步骤 1 删除了 11 行语义矛盾的子行（PRICING calc_type='元素' 但 component_no
-- 命中料号主档、不命中材质库），但未同步处理因此变空的主表，留下 5 行孤儿：
--   2120011658 / 2120011659 / 3110520789（is_current=t，当前生效却无任何子行）
--   S-2120011659 / S-3110520789（is_current=f，历史版本）
--
-- 空壳主表不是合法状态：VersionedV6Writer.writeVersionedMasterDetail 显式拒绝空 childRows
-- （"childRows 为空;整组下线请用专门 API"），故主表存在即应有子行。这里按该不变量做通用清理，
-- 不硬编码上述 5 个料号，任何环境重放都能自愈。

-- ── 步骤 1: 删除无子行的主表行 ──
DELETE FROM material_bom b
WHERE NOT EXISTS (
    SELECT 1 FROM material_bom_item i
    WHERE i.system_type = b.system_type
      AND i.customer_no = b.customer_no
      AND i.material_no = b.material_no
      AND i.bom_version = b.bom_version
);

-- ── 步骤 2: 校验 V344 步骤 4 的 cz_view 谓词确已生效 ──
-- V344 用 `UPDATE ... WHERE sql_template LIKE '%AND asy.characteristic IS NULL%'` 做替换，
-- 不命中时会静默 no-op。而在 characteristic 已回填的库上，旧谓词 `IS NULL` 恒不匹配
-- → 核价「材质」页签会全空且无任何报错。这个静默失败必须挡在部署阶段而不是用户面前。
--
-- 此处做事后校验：若 cz_view 仍残留旧谓词（说明 V344 的替换没生效，可能因并发会话改过模板
-- 导致 LIKE 不命中），直接让迁移失败，逼出人工处理。
DO $$
DECLARE
    stale_count INT;
BEGIN
    SELECT count(*) INTO stale_count
    FROM component_sql_view
    WHERE sql_template LIKE '%asy.characteristic IS NULL%';

    IF stale_count > 0 THEN
        RAISE EXCEPTION
            'cz_view 仍残留 % 条旧谓词 "asy.characteristic IS NULL"；V344 步骤4 的 replace 未生效。'
            ' characteristic 已回填后该谓词恒不命中，核价「材质」页签将全空。'
            ' 请人工核对 component_sql_view 模板后重跑。', stale_count;
    END IF;
END $$;
