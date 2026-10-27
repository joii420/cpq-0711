-- =====================================================================================
-- task-261026 · 物料增加可选字段「利润」
--
-- 只扩展报价侧 ds_quote_material 与 QUOTE/MATERIAL 语义节点；
-- 不修改价格计算、核价数据集、存量组件配置或已发布快照。
-- =====================================================================================

ALTER TABLE ds_quote_material
    ADD COLUMN IF NOT EXISTS profit numeric(26,12);

INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_by, created_at, updated_at, status)
SELECT
    '26102602-0000-4000-8000-000000000001'::uuid,
    n.id, 'profit', '利润', 'NUMBER', false, '{}', 9,
    'task-261026', now(), now(), 'ACTIVE'
FROM semantic_node n
WHERE n.node_key = 'MATERIAL' AND n.dialect = 'QUOTE'
ON CONFLICT DO NOTHING;

-- 落库即自检：物理列的精度/可空性与语义列的唯一性均必须精确成立。
DO $$
DECLARE
    n integer;
    problems text := '';
BEGIN
    SELECT count(*) INTO n
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'ds_quote_material'
      AND column_name = 'profit'
      AND data_type = 'numeric'
      AND numeric_precision = 26
      AND numeric_scale = 12
      AND is_nullable = 'YES';
    IF n <> 1 THEN
        problems := problems || format('[1] ds_quote_material.profit numeric(26,12) NULL 期望 1 列，实得 %s; ', n);
    END IF;

    SELECT count(*) INTO n
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name IN ('ds_cost_basic_material', 'ds_cost_detail_material')
      AND column_name = 'profit';
    IF n <> 0 THEN
        problems := problems || format('[2] 核价物料表不应有 profit，实得 %s 列; ', n);
    END IF;

    SELECT count(*) INTO n
    FROM semantic_node n0
    JOIN semantic_node_column c ON c.node_id = n0.id
    WHERE n0.node_key = 'MATERIAL'
      AND n0.dialect = 'QUOTE'
      AND n0.status = 'ACTIVE'
      AND n0.physical_table = 'ds_quote_material'
      AND c.db_column = 'profit'
      AND c.display_name = '利润'
      AND c.data_type = 'NUMBER'
      AND c.is_code = false
      AND c.roles = '{}'::text[]
      AND c.sort_order = 9
      AND c.status = 'ACTIVE';
    IF n <> 1 THEN
        problems := problems || format('[3] QUOTE/MATERIAL 利润语义列合格数期望 1，实得 %s; ', n);
    END IF;

    SELECT count(*) INTO n
    FROM semantic_node n0
    JOIN semantic_node_column c ON c.node_id = n0.id
    WHERE n0.node_key = 'MATERIAL'
      AND n0.dialect = 'QUOTE'
      AND c.db_column = 'profit';
    IF n <> 1 THEN
        problems := problems || format('[4] QUOTE/MATERIAL db_column=profit 期望恰好 1 条，实得 %s; ', n);
    END IF;

    IF problems <> '' THEN
        RAISE EXCEPTION 'task-261026 V454 自检失败：%', problems;
    END IF;
END $$;
