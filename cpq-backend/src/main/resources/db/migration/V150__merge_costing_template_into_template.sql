-- ============================================================
-- V150: 方案 C Stage 1 — 数据合并 (costing_template → template.excel_view_config)
--
-- 背景: 历史上 costing_template 是"业务模板的 Excel 视图配置层" (1:N 反向引用 template.id),
--       与 template 表本身的 excel_view_config 字段功能重叠. 用户在 2 个页面分别维护,
--       数据不一致是当前痛点. 本次合并:
--
--   - costing_template.columns          → template.excel_view_config (覆盖式)
--   - costing_template.is_default       → template.is_default
--   - costing_template.referenced_variables → template.referenced_variables
--
--   冲突优先级 (同一 linked_template_id 多行时):
--     1. status='PUBLISHED' 优先于 DRAFT 优先于 ARCHIVED
--     2. col_cnt 多者优先
--     3. version 字典序大者优先
--
-- 安全:
--   - 全表备份到 costing_template_legacy_backup, V151 可反向还原
--   - 单事务包裹 (Flyway 默认行为, 失败自动回滚)
--   - 不 DROP 任何表/列/外键 (代码层 Stage 2/3 才动)
--   - 不读不写业务表 (quotation/costing_*/mat_*等)
--
-- 校验:
--   - RAISE NOTICE 输出 备份行数 / 孤儿行 (linked_template_id=NULL) / template 命中数
-- ============================================================

-- 1. 全表备份 (V151 可从此还原)
CREATE TABLE IF NOT EXISTS costing_template_legacy_backup AS
    SELECT * FROM costing_template;

COMMENT ON TABLE costing_template_legacy_backup IS
    'V150: costing_template 表合并前的完整快照, V151 可基于此回滚. Stage 3 完成后可手工 DROP.';

-- 2. template 表加缺失字段 (与 costing_template 对齐)
ALTER TABLE template ADD COLUMN IF NOT EXISTS is_default BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE template ADD COLUMN IF NOT EXISTS referenced_variables JSONB DEFAULT '[]'::jsonb;

COMMENT ON COLUMN template.is_default IS
    'V150: 是否系列默认模板 (从 costing_template.is_default 合并而来)';
COMMENT ON COLUMN template.referenced_variables IS
    'V150: 引用变量集 (从 costing_template.referenced_variables 合并而来)';

-- 3. 覆盖式合并: 按 linked_template_id 配对, 多行时按优先级选优
WITH ranked_ct AS (
    SELECT
        linked_template_id,
        columns,
        is_default,
        referenced_variables,
        status,
        ROW_NUMBER() OVER (
            PARTITION BY linked_template_id
            ORDER BY
                CASE status
                    WHEN 'PUBLISHED' THEN 1
                    WHEN 'DRAFT'     THEN 2
                    WHEN 'ARCHIVED'  THEN 3
                    ELSE 9
                END,
                CASE WHEN columns IS NULL OR columns::text IN ('null','[]')
                     THEN 0
                     ELSE jsonb_array_length(columns::jsonb)
                END DESC,
                COALESCE(version, '') DESC
        ) AS rn
    FROM costing_template
    WHERE linked_template_id IS NOT NULL
      AND columns IS NOT NULL
      AND columns::text NOT IN ('null', '[]', '{}')
)
UPDATE template t
SET
    excel_view_config    = r.columns::jsonb,
    is_default           = COALESCE(r.is_default, false),
    referenced_variables = COALESCE(r.referenced_variables, '[]'::jsonb),
    updated_at           = now()
FROM ranked_ct r
WHERE r.linked_template_id = t.id
  AND r.rn = 1;

-- 4. 校验输出
DO $$
DECLARE
    v_backup_cnt        INT;
    v_orphan_cnt        INT;
    v_empty_cols_cnt    INT;
    v_template_with_evc INT;
    v_template_default  INT;
    v_costing_kind_cnt  INT;
BEGIN
    SELECT COUNT(*) INTO v_backup_cnt FROM costing_template_legacy_backup;

    SELECT COUNT(*) INTO v_orphan_cnt
    FROM costing_template
    WHERE linked_template_id IS NULL;

    SELECT COUNT(*) INTO v_empty_cols_cnt
    FROM costing_template
    WHERE linked_template_id IS NOT NULL
      AND (columns IS NULL OR columns::text IN ('null','[]','{}'));

    SELECT COUNT(*) INTO v_template_with_evc
    FROM template
    WHERE excel_view_config IS NOT NULL
      AND excel_view_config::text NOT IN ('null','[]','{}');

    SELECT COUNT(*) INTO v_template_default FROM template WHERE is_default = true;

    SELECT COUNT(*) INTO v_costing_kind_cnt FROM template WHERE template_kind = 'COSTING';

    RAISE NOTICE '═══════════════════════════════════════════════════';
    RAISE NOTICE 'V150 数据合并完成';
    RAISE NOTICE '═══════════════════════════════════════════════════';
    RAISE NOTICE '备份表 costing_template_legacy_backup 行数: %', v_backup_cnt;
    RAISE NOTICE '孤儿行 (linked_template_id=NULL) 已跳过: %', v_orphan_cnt;
    RAISE NOTICE '空 columns 行 (无内容) 已跳过: %', v_empty_cols_cnt;
    RAISE NOTICE 'template 表中 COSTING 类型: %', v_costing_kind_cnt;
    RAISE NOTICE 'template 表中有 excel_view_config 内容: %', v_template_with_evc;
    RAISE NOTICE 'template 表中 is_default=true: %', v_template_default;
    RAISE NOTICE '═══════════════════════════════════════════════════';
    RAISE NOTICE '回滚方法:';
    RAISE NOTICE '  - 完整回滚: 跑 V151 (反向 SQL)';
    RAISE NOTICE '  - 验证备份: SELECT * FROM costing_template_legacy_backup';
    RAISE NOTICE '═══════════════════════════════════════════════════';
END $$;
