-- =================================================================
-- V150 反向回滚 SQL (手工脚本, 不入 Flyway 迁移管理)
--
-- 用法: psql -h <host> -U postgres -d cpq_db -f data/rollback-v150.sql
--
-- 适用场景:
--   - V150 误覆盖了 template.excel_view_config 的重要数据
--   - 想完全撤回到 V150 跑之前的状态, 重新设计合并策略
--
-- 跑完后:
--   1. template.excel_view_config 被合并的行清回 '[]'
--   2. template.is_default / referenced_variables 字段被 DROP
--   3. costing_template_legacy_backup 表保留 (手工 DROP 才删)
--   4. costing_template 原表未动
--
-- 让 Flyway 能重新跑 V150 (按需):
--   DELETE FROM flyway_schema_history WHERE version = '150';
--
-- 注意: 此 SQL 默认对所有 V150 影响过的 template 行都清空, 包括已发布、已归档.
--       如果某行已被你手工修改, 也会被清掉.
-- =================================================================

BEGIN;

-- 1. 反向清空 V150 合并写入的 template 行
WITH ranked_ct_at_v150 AS (
    SELECT linked_template_id,
           ROW_NUMBER() OVER (
               PARTITION BY linked_template_id
               ORDER BY
                   CASE status WHEN 'PUBLISHED' THEN 1 WHEN 'DRAFT' THEN 2 WHEN 'ARCHIVED' THEN 3 ELSE 9 END,
                   CASE WHEN columns IS NULL OR columns::text IN ('null','[]') THEN 0
                        ELSE jsonb_array_length(columns::jsonb) END DESC,
                   COALESCE(version, '') DESC
           ) AS rn
    FROM costing_template_legacy_backup
    WHERE linked_template_id IS NOT NULL
      AND columns IS NOT NULL
      AND columns::text NOT IN ('null', '[]', '{}')
)
UPDATE template t
SET excel_view_config    = '[]'::jsonb,
    updated_at           = now()
FROM ranked_ct_at_v150 r
WHERE r.linked_template_id = t.id
  AND r.rn = 1;

-- 2. DROP V150 加的字段
ALTER TABLE template DROP COLUMN IF EXISTS is_default;
ALTER TABLE template DROP COLUMN IF EXISTS referenced_variables;

-- 3. 校验输出
DO $$
DECLARE
    v_template_with_evc INT;
    v_backup_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_template_with_evc
    FROM template
    WHERE excel_view_config IS NOT NULL
      AND excel_view_config::text NOT IN ('null','[]','{}');

    SELECT COUNT(*) INTO v_backup_cnt FROM costing_template_legacy_backup;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V150 反向回滚完成';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'template 表中仍有 excel_view_config 内容: % 行 (原本 V150 前部分模板就有内容)', v_template_with_evc;
    RAISE NOTICE '备份表 costing_template_legacy_backup 保留: % 行', v_backup_cnt;
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '后续:';
    RAISE NOTICE '  - 让 Flyway 重跑 V150: DELETE FROM flyway_schema_history WHERE version=''150'';';
    RAISE NOTICE '  - 永久放弃备份: DROP TABLE costing_template_legacy_backup;';
END $$;

COMMIT;
