-- V131: 清理 mat_fee 中 "OTHER 类" 脏数据 (FINISHED_OTHER / INCOMING_OTHER)
-- 起因:
--   1) 早期 import 没填 dim_element_name 维度, 残留 NULL 行（行 #1）
--   2) 历史用过不同 Excel 模板, 残留 (seq=10/11/12/13, name=管理费/财务费/利润/税费) 孤儿行（行 #2-5）
-- 策略 A: 删除 FINISHED_OTHER / INCOMING_OTHER 中 dim_element_name IS NULL 的行
--           (合法 OTHER 费用必有要素名称, NULL 必为脏行)
-- 策略 B: mark-and-sweep 清孤儿: 同 (customer_id, hf_part_no, fee_type) 下,
--           保留最新 5 分钟内 updated_at 的行, 更早的视为旧 import 残留并删除

DO $$
DECLARE
    v_a INT;
    v_b INT;
BEGIN
    -- A. dim_element_name IS NULL 的 OTHER 类脏行 (全表)
    DELETE FROM mat_fee
    WHERE fee_type IN ('FINISHED_OTHER', 'INCOMING_OTHER')
      AND dim_element_name IS NULL;
    GET DIAGNOSTICS v_a = ROW_COUNT;
    RAISE NOTICE 'V131-A: 删除 dim_element_name IS NULL 的 OTHER 行: %', v_a;

    -- B. mark-and-sweep 孤儿行 (per customer+part+fee_type, 保留最新 import batch)
    --    逻辑: 同三元组下, 凡 updated_at 比最新行早 5 分钟以上的行视为旧 import 残留
    WITH latest AS (
        SELECT customer_id, hf_part_no, fee_type, MAX(updated_at) AS max_ts
        FROM mat_fee
        WHERE is_current = true
          AND fee_type IN ('FINISHED_OTHER', 'INCOMING_OTHER')
        GROUP BY customer_id, hf_part_no, fee_type
    )
    DELETE FROM mat_fee m
    USING latest l
    WHERE m.is_current = true
      AND m.fee_type IN ('FINISHED_OTHER', 'INCOMING_OTHER')
      AND m.customer_id = l.customer_id
      AND m.hf_part_no = l.hf_part_no
      AND m.fee_type = l.fee_type
      AND m.updated_at < l.max_ts - INTERVAL '5 minutes';
    GET DIAGNOSTICS v_b = ROW_COUNT;
    RAISE NOTICE 'V131-B: 删除 mark-and-sweep 孤儿行 (5 分钟外): %', v_b;
END $$;
