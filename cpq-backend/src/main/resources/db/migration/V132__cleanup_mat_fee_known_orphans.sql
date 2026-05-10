-- V132: 一次性清理已知 mat_fee 孤儿 (V131-B mark-and-sweep 没捕获到的)
-- 起因: customer=施耐德 hf_part_no=3120012574 fee_type=INCOMING_FIXED 残留 seq_no=3
--       + dim_input_material_no IS NULL 行，dim_input_material_name=XXXAg触点
--       同样模式可能扩散到其它 partNo / fee_type
-- 策略: 删除 dim_input_material_no IS NULL AND dim_element_name IS NULL
--       AND dim_assembly_process IS NULL 但 dim_input_material_name IS NOT NULL
--       的 INCOMING_FIXED row
--       合法 INCOMING_FIXED 应有 dim_input_material_no；
--       "只有 name 没 no" 明显是历史脏数据

DO $$
DECLARE v_n INT;
BEGIN
    DELETE FROM mat_fee
    WHERE is_current = true
      AND fee_type = 'INCOMING_FIXED'
      AND dim_input_material_no IS NULL
      AND dim_element_name IS NULL
      AND dim_assembly_process IS NULL
      AND dim_input_material_name IS NOT NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RAISE NOTICE 'V132: 清理 INCOMING_FIXED 孤儿 (input_no NULL but name 非空): %', v_n;
END $$;
