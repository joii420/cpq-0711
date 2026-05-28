-- V261: V260 follow-up — 同步更新 v1.30 模板的 template_sql_views_snapshot JSONB
--
-- 背景:
--   V260 只 UPDATE 了 template_sql_view 源表的 sql_template, 但 v1.30 是 PUBLISHED 模板,
--   其 template_sql_views_snapshot JSONB 持有发布时的冻结副本.
--   渲染走 TemplateSqlViewService.lookupForResolver 优先读 snapshot, 命中后不回退源表.
--   日志确认: "[TemplateSqlViewService] hit snapshot templateId=27fab96b... name=process_info"
--   导致 V260 改源表不生效.
--
--   修复: 用 jsonb_set 把 process_info entry 的 sqlTemplate + declaredColumns 改为聚合形态.
--
--   注意: GET /api/cpq/templates/{id} 返回 templateSqlViewsSnapshot=null 是 DTO 序列化没暴露,
--         不代表实际 null. Template entity 字段非 null, 默认 '{}'::jsonb.

DO $$
DECLARE
  v_template_id UUID := '27fab96b-77ff-47ed-a74f-de4bb93670e5'::uuid;
  v_new_sql TEXT := E'\nSELECT\n    hf_part_no,\n    COUNT(DISTINCT seq_no)::int AS seq_no\nFROM mat_process\nWHERE is_current = true\n  AND status = ''ACTIVE''\nGROUP BY hf_part_no\n';
  v_new_declared JSONB := '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"seq_no","dataType":"int4","nullable":true}]'::jsonb;
  v_row_count INT;
  v_has_key BOOLEAN;
BEGIN
  -- 先查是否含 process_info key (snapshot 可能为空对象或缺该 key)
  SELECT COALESCE(template_sql_views_snapshot ? 'process_info', false)
    INTO v_has_key
  FROM template WHERE id = v_template_id;

  IF NOT v_has_key THEN
    RAISE NOTICE 'V261: template_sql_views_snapshot does NOT have process_info key — skipped.';
    RETURN;
  END IF;

  UPDATE template
  SET template_sql_views_snapshot =
        jsonb_set(
          jsonb_set(
            template_sql_views_snapshot,
            '{process_info,sqlTemplate}',
            to_jsonb(v_new_sql)
          ),
          '{process_info,declaredColumns}',
          v_new_declared
        ),
      updated_at = NOW()
  WHERE id = v_template_id;

  GET DIAGNOSTICS v_row_count = ROW_COUNT;
  RAISE NOTICE 'V261: template_sql_views_snapshot.process_info updated, rows = %', v_row_count;
END $$;
