-- V260: 修复 v1.30 「选配产品标准报价模板-组合产品」的 process_info 模板 SQL 视图谓词聚合
--
-- 背景:
--   v1.30 模板 (id=27fab96b-77ff-47ed-a74f-de4bb93670e5) 的 process_info 视图原 SQL:
--     SELECT material_no AS hf_part_no, NULL::integer AS seq_no FROM material_bom_item WHERE FALSE
--     UNION ALL
--     SELECT hf_part_no, seq_no FROM mat_process
--
--   问题: 每个 partNo 在 mat_process 含多客户 × 多版本 × 多工序号 × 多 sub_seq_no
--         实际 partNo=3120012574 返 372 行 (1~9 seq_no 重复). 前端 formatPathValue
--         截首 + "(共N项)" → [H]工序数 列显示 "1(共372项)" — 业务期望单值.
--
--   修复: GROUP BY hf_part_no 聚合返每个料号 1 行,seq_no 列存 COUNT(DISTINCT seq_no)
--         即"工序数". 增加 is_current=true + status='ACTIVE' 过滤,只取当前生效工序.
--
--   AP-53 负债: 仍 FROM V44 mat_process. V6 material_bom_item.operation_no 当前
--   全 NULL(数据未到位); BasicDataImportServiceV5 V6 backfill PR 落地后,本视图
--   应改写为 FROM material_bom_item GROUP BY material_no.
--
--   v1.30 templateSqlViewsSnapshot 当前为 NULL,lookupForResolver 实时读 →
--   本 UPDATE 改完立即生效,无需重启或重 publish.
--
-- 触发原因: 用户上一轮报告 "[H]工序数 列 1(共300+项)" 是修复 buildEvalKey 4 段
--          key 协议(2026-05-27 BUG-FIX 条目)后新暴露的次级问题. 见 docs/RECORD.md.

DO $$
DECLARE
  v_template_id UUID := '27fab96b-77ff-47ed-a74f-de4bb93670e5'::uuid;
  v_row_count   INT;
BEGIN
  UPDATE template_sql_view
  SET sql_template = $TPL$
SELECT
    hf_part_no,
    COUNT(DISTINCT seq_no)::int AS seq_no
FROM mat_process
WHERE is_current = true
  AND status = 'ACTIVE'
GROUP BY hf_part_no
$TPL$,
      declared_columns = '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"seq_no","dataType":"int4","nullable":true}]'::jsonb,
      description = '组合产品模板 v1.30 - 工序数 (V260 修单值聚合). 每 hf_part_no 1 行, seq_no=COUNT(DISTINCT seq_no). 兜底 V44 mat_process(is_current=true + status=ACTIVE). AP-53 待迁: BasicDataImportServiceV5 V6 backfill 落地后改 FROM material_bom_item GROUP BY material_no.',
      updated_at = NOW()
  WHERE template_id = v_template_id
    AND sql_view_name = 'process_info';

  GET DIAGNOSTICS v_row_count = ROW_COUNT;
  RAISE NOTICE 'V260: process_info sql_template updated, rows = %', v_row_count;

  IF v_row_count = 0 THEN
    RAISE NOTICE 'V260: WARNING — process_info row not found for template % (skipped, no error)', v_template_id;
  END IF;
END $$;
