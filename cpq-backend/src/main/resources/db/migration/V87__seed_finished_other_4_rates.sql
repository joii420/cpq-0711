-- V87: 补齐「成品其他费用」(fee_type='FINISHED_OTHER') 的 4 类加价比例 demo 数据
--
-- 目的: 让「核价Excel视图模板（完整公式版）」(V83-V86) 里 H/J/L/N 4 个 BNF 引用列
--       能解析到真实数值, 加价 FORMULA(I/K/M/O) + 总成本(P/R/T/U) 不再为 0/null。
--
-- 来源: data/template/核价系统计算公式和取值（示例）.xlsx 行 48-51
--   行 48: 管理费 比例(%) = 0.6  → fee_ratio = 0.006 (DB 以小数存储)
--   行 49: 财务费 比例(%) = 0.5  → fee_ratio = 0.005
--   行 50: 利润   比例(%) = 5    → fee_ratio = 0.05
--   行 51: 税费   比例(%) = 13   → fee_ratio = 0.13
--
-- 现状(2026-05-06):
--   * 4 个 demo 料号(3100080003 / 3100090136 / 3120012574 / 3120012575)都属于
--     customer_id = '8de8f8b0-041c-4af1-aeb5-139fbb5484ca'
--   * 这 4 个料号在 fee_type='FINISHED_OTHER' 下已有 3-5 条 demo 数据
--     (dim_element_name = 财务管理费 / 回收费 / 材料管理费 / 包装费), 占用了 seq_no 1-4
--   * Excel 设计的 4 类(管理费/财务费/利润/税费) 不在现有数据中
--
-- 策略:
--   * 用 seq_no 10/11/12/13 (拉开间隔避免与现有 1-4 冲突), 插入 4×4=16 行
--   * dim_element_name 用 Excel 中的 4 类中文名
--   * 幂等: 用 WHERE NOT EXISTS 检查 (hf_part_no, dim_element_name, is_current=true)
--
-- 唯一索引(V70 重建): uq_mat_fee_current 覆盖
--   (customer_id, hf_part_no, fee_type, seq_no, dim_input_material_no,
--    dim_input_material_name, dim_element_name, dim_assembly_process, dim_sub_seq_no)
--   WHERE is_current=true
-- 我们用 dim_element_name 区分, 其它 dim_* 留 NULL 即可。

DO $$
DECLARE
    v_customer_id UUID := '8de8f8b0-041c-4af1-aeb5-139fbb5484ca';
    r RECORD;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('3100080003'),
        ('3100090136'),
        ('3120012574'),
        ('3120012575')
    ) AS t(part_no)
    LOOP
        -- 管理费 0.6% (= 0.006)
        INSERT INTO mat_fee (
            id, customer_id, hf_part_no, version, is_current, fee_type, seq_no,
            fee_ratio, dim_element_name, currency, price_unit
        )
        SELECT gen_random_uuid(), v_customer_id, r.part_no, 1, true, 'FINISHED_OTHER', 10,
               0.006, '管理费', 'CNY', '%'
        WHERE NOT EXISTS (
            SELECT 1 FROM mat_fee
            WHERE hf_part_no = r.part_no
              AND fee_type = 'FINISHED_OTHER'
              AND dim_element_name = '管理费'
              AND is_current = true
        );

        -- 财务费 0.5% (= 0.005)
        INSERT INTO mat_fee (
            id, customer_id, hf_part_no, version, is_current, fee_type, seq_no,
            fee_ratio, dim_element_name, currency, price_unit
        )
        SELECT gen_random_uuid(), v_customer_id, r.part_no, 1, true, 'FINISHED_OTHER', 11,
               0.005, '财务费', 'CNY', '%'
        WHERE NOT EXISTS (
            SELECT 1 FROM mat_fee
            WHERE hf_part_no = r.part_no
              AND fee_type = 'FINISHED_OTHER'
              AND dim_element_name = '财务费'
              AND is_current = true
        );

        -- 利润 5% (= 0.05)
        INSERT INTO mat_fee (
            id, customer_id, hf_part_no, version, is_current, fee_type, seq_no,
            fee_ratio, dim_element_name, currency, price_unit
        )
        SELECT gen_random_uuid(), v_customer_id, r.part_no, 1, true, 'FINISHED_OTHER', 12,
               0.05, '利润', 'CNY', '%'
        WHERE NOT EXISTS (
            SELECT 1 FROM mat_fee
            WHERE hf_part_no = r.part_no
              AND fee_type = 'FINISHED_OTHER'
              AND dim_element_name = '利润'
              AND is_current = true
        );

        -- 税费 13% (= 0.13)
        INSERT INTO mat_fee (
            id, customer_id, hf_part_no, version, is_current, fee_type, seq_no,
            fee_ratio, dim_element_name, currency, price_unit
        )
        SELECT gen_random_uuid(), v_customer_id, r.part_no, 1, true, 'FINISHED_OTHER', 13,
               0.13, '税费', 'CNY', '%'
        WHERE NOT EXISTS (
            SELECT 1 FROM mat_fee
            WHERE hf_part_no = r.part_no
              AND fee_type = 'FINISHED_OTHER'
              AND dim_element_name = '税费'
              AND is_current = true
        );
    END LOOP;

    RAISE NOTICE 'V87: 已为 4 个 demo 料号补齐 mat_fee FINISHED_OTHER 4 类加价比例 (管理费 0.006 / 财务费 0.005 / 利润 0.05 / 税费 0.13)';
END $$;
