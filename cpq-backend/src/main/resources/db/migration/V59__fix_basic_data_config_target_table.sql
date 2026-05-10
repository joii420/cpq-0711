-- V59: 修复 V58_5 因 ON CONFLICT DO NOTHING 导致生产 sheet 配置的 target_table 未设置
-- 根因：V58_5 首次执行时 basic_data_config 中已存在这些 sheet_name 行（无 target_table），
--       ON CONFLICT DO NOTHING 阻止了后续含 target_table 的 INSERT，导致字段为 null。
-- 修复方案：用 UPDATE 补齐所有已存在行；同时用 INSERT ... ON CONFLICT DO NOTHING 补充缺失行。
-- Ref: RECORD.md V58_5 data issue investigation

-- ════════════════════════════════════════════════════════════════════
-- Part 1: UPDATE 生产模板 16 个中文 sheet 的 target_table / target_discriminator
-- ════════════════════════════════════════════════════════════════════

UPDATE basic_data_config SET target_table = 'mat_part',                    target_discriminator = NULL                                        WHERE sheet_name = '单重'             AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = '{"bom_type":"INCOMING"}'::jsonb             WHERE sheet_name = '来料BOM'          AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = '{"bom_type":"ELEMENT"}'::jsonb              WHERE sheet_name = '元素BOM'          AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_process',                 target_discriminator = NULL                                        WHERE sheet_name = '组成件BOM及单价'   AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_FIXED"}'::jsonb       WHERE sheet_name = '来料固定加工费'   AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_OTHER"}'::jsonb       WHERE sheet_name = '来料其他费用'     AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"FINISHED_FIXED"}'::jsonb       WHERE sheet_name = '成品固定加工费'   AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"FINISHED_OTHER"}'::jsonb       WHERE sheet_name = '成品其他费用'     AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_ANNUAL_DOWN"}'::jsonb  WHERE sheet_name = '来料年降'         AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ASSEMBLY_PROCESS"}'::jsonb     WHERE sheet_name = '组装加工费'       AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ASSEMBLY_ANNUAL_DOWN"}'::jsonb  WHERE sheet_name = '组装加工费年降'   AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ANNUAL_REDUCTION_FACTOR"}'::jsonb WHERE sheet_name = '年降系数'      AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'plating_fee',                 target_discriminator = NULL                                        WHERE sheet_name = '电镀费用'         AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'plating_plan',                target_discriminator = NULL                                        WHERE sheet_name = '电镀方案'         AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_customer_part_mapping',   target_discriminator = NULL                                        WHERE sheet_name = '客户料号与宏丰料号的关系' AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = NULL,                          target_discriminator = NULL                                        WHERE sheet_name = '元素单价'         AND status = 'ACTIVE';

-- Part 2: UPDATE 旧测试兼容 sheet 的 target_table
UPDATE basic_data_config SET target_table = 'mat_part',                    target_discriminator = NULL    WHERE sheet_name = '料号主档'     AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = NULL    WHERE sheet_name = 'BOM清单'      AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_process',                 target_discriminator = NULL    WHERE sheet_name = '组成件BOM'    AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = NULL    WHERE sheet_name = '费用清单'     AND status = 'ACTIVE';
UPDATE basic_data_config SET target_table = 'mat_customer_part_mapping',   target_discriminator = NULL    WHERE sheet_name = '客户料号映射' AND status = 'ACTIVE';

-- ════════════════════════════════════════════════════════════════════
-- Part 3: INSERT 补充缺失的生产 sheet 配置行（若 V58_5 确实未插入某些行）
-- 使用 ON CONFLICT DO NOTHING（无冲突目标）— 兼容 partial unique index uq_bdc_sheet_name
-- ════════════════════════════════════════════════════════════════════

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('单重', 1, 2, '生产料号主档（GLOBAL）', 110, 'ACTIVE', 'mat_part', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料BOM', 1, 2, '来料BOM（GLOBAL）bom_type=INCOMING', 120, 'ACTIVE', 'mat_bom', '{"bom_type":"INCOMING"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('元素BOM', 1, 2, '元素BOM（GLOBAL）bom_type=ELEMENT', 130, 'ACTIVE', 'mat_bom', '{"bom_type":"ELEMENT"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组成件BOM及单价', 1, 2, '工艺基础（CUSTOMER）', 140, 'ACTIVE', 'mat_process', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料固定加工费', 1, 2, '来料固定加工费（CUSTOMER）', 150, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_FIXED"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料其他费用', 1, 2, '来料其他费用（CUSTOMER）', 160, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_OTHER"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('成品固定加工费', 1, 2, '成品固定加工费（CUSTOMER）', 170, 'ACTIVE', 'mat_fee', '{"fee_type":"FINISHED_FIXED"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('成品其他费用', 1, 2, '成品其他费用（CUSTOMER）', 180, 'ACTIVE', 'mat_fee', '{"fee_type":"FINISHED_OTHER"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料年降', 1, 2, '来料年降（CUSTOMER）', 190, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_ANNUAL_DOWN"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组装加工费', 1, 2, '组装加工费（CUSTOMER）', 200, 'ACTIVE', 'mat_fee', '{"fee_type":"ASSEMBLY_PROCESS"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组装加工费年降', 1, 2, '组装加工费年降（CUSTOMER）', 210, 'ACTIVE', 'mat_fee', '{"fee_type":"ASSEMBLY_ANNUAL_DOWN"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('年降系数', 1, 2, '年降系数（CUSTOMER）', 220, 'ACTIVE', 'mat_fee', '{"fee_type":"ANNUAL_REDUCTION_FACTOR"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('电镀费用', 1, 2, '电镀费用（CUSTOMER）', 230, 'ACTIVE', 'plating_fee', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('电镀方案', 1, 2, '电镀方案（GLOBAL）', 240, 'ACTIVE', 'plating_plan', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('客户料号与宏丰料号的关系', 1, 2, '客户料号对照（CUSTOMER）', 250, 'ACTIVE', 'mat_customer_part_mapping', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('元素单价', 1, 2, '元素单价（CUSTOMER，v1 跳过）', 260, 'ACTIVE', NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('料号主档', 1, 2, '旧测试兼容：mat_part（GLOBAL）', 310, 'ACTIVE', 'mat_part', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('BOM清单', 1, 2, '旧测试兼容：mat_bom（GLOBAL）', 320, 'ACTIVE', 'mat_bom', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组成件BOM', 1, 2, '旧测试兼容：mat_process（CUSTOMER）', 330, 'ACTIVE', 'mat_process', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('费用清单', 1, 2, '旧测试兼容：mat_fee（CUSTOMER）', 340, 'ACTIVE', 'mat_fee', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('客户料号映射', 1, 2, '旧测试兼容：mat_customer_part_mapping（CUSTOMER）', 350, 'ACTIVE', 'mat_customer_part_mapping', NULL)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE basic_data_config IS
    'V59: 修复 V58_5 ON CONFLICT DO NOTHING 导致生产 sheet target_table 为 null 的问题';
