-- ============================================================
-- V43: BasicDataConfig 元数据 seed（14 张物理表中可 Excel 导入的表）
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §6.1
--      docs/superpowers/specs/2026-04-25-cpq-design-v5.md §5
-- 说明：
--   BasicDataConfig.sheet_name 用作物理表的识别键（即 Excel Sheet 名称约定）
--   description 填写表用途 + 粒度（GLOBAL/CUSTOMER）
--   跳过 element_daily_price（非 Excel 导入，v1 仅 MANUAL 写入）
--   跳过 element_price_source / element_price_fetch_rule（v2 启用，由管理员界面维护）
--   跳过 basic_data_change_log（系统自动写入，不通过 Excel 导入）
-- ============================================================

-- ============== 全局表 seed (4 张) ==============

-- mat_part：生产料号主档
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'mat_part', 0, 1, 2,
    '生产料号主档（GLOBAL）— 物理表 mat_part，跨客户共享，含单重/规格/产品分类',
    10, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- mat_bom：统一 BOM 表
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'mat_bom', 1, 1, 2,
    '统一 BOM 表（GLOBAL）— 物理表 mat_bom，合并来料(INCOMING)+元素(ELEMENT)，跨客户共享',
    20, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- mat_process：工艺基础（含 customer_id，BIZ-2 跨客户差异化）
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'mat_process', 2, 1, 2,
    '工艺基础（CUSTOMER）— 物理表 mat_process，含 customer_id，支持跨客户差异化（v5.1 §2.2 BIZ-2），含版本号',
    30, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- plating_plan：电镀方案
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'plating_plan', 3, 1, 2,
    '电镀方案（GLOBAL）— 物理表 plating_plan，跨客户共享，含镀层元素/面积/厚度/要求',
    40, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- ============== 客户级表 seed (6 张可导入) ==============

-- mat_fee：统一费用表
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'mat_fee', 4, 1, 2,
    '统一费用表（CUSTOMER）— 物理表 mat_fee，含 customer_id + version，覆盖 5 种费用类型',
    50, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- plating_fee：电镀费用
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'plating_fee', 5, 1, 2,
    '电镀费用（CUSTOMER）— 物理表 plating_fee，含 customer_id + version，关联电镀方案',
    60, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- mat_customer_part_mapping：跨客户料号映射
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'mat_customer_part_mapping', 6, 1, 2,
    '客户料号对照（CUSTOMER）— 物理表 mat_customer_part_mapping，映射客户产品编号到内部料号',
    70, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- element_price：客户元素价格配置
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'element_price', 7, 1, 2,
    '客户元素价格配置（CUSTOMER）— 物理表 element_price，v1 仅建 schema，source_id/fetch_rule_id nullable',
    80, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- exchange_rate：汇率
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'exchange_rate', 8, 1, 2,
    '汇率（CUSTOMER）— 物理表 exchange_rate，含 customer_id + effective_date + is_current',
    90, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- customer_tax：客户税率
INSERT INTO basic_data_config (
    sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, sort_order, status
) VALUES (
    'customer_tax', 9, 1, 2,
    '客户税率（CUSTOMER）— 物理表 customer_tax，含 customer_id + tax_type + is_current',
    100, 'ACTIVE'
) ON CONFLICT DO NOTHING;

-- ============================================================
-- 跳过说明：
--   element_daily_price  — 非 Excel 导入，v1 仅 MANUAL 写入，不建 BasicDataConfig
--   element_price_source — 管理员界面维护，不通过 Excel 导入
--   element_price_fetch_rule — 管理员界面维护，不通过 Excel 导入
--   basic_data_change_log — 系统自动写入，不通过 Excel 导入
-- ============================================================
