-- V412__task260819_v9_cost_all_version_views.sql
-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。
-- 视图张数：26（ds_cost_basic_* 9 + ds_cost_detail_* 17）
-- task-260819 · v9 · B-44① / S-31 / D-84（AC-109 / AC-125 / AC-126）
--
-- 为核价两套的带版本主表各建一张 v_<主表>_all 全版本视图：
--     SELECT <主表列>, true  AS is_current FROM <主表>
--     UNION ALL
--     SELECT <同列>,   false AS is_current FROM <主表>_history
-- 编译器据此发 :versionFilter(<别名>.is_current, <别名>.version_no::text, <别名>.<轴列>)。
--
-- 🚫 报价侧那 13 张带版本表不建视图 —— 版本切换是核价侧独有功能（S-31）。
-- ⚠️ 视图建在 task-260902 的表上：不改表结构、不加索引、不加约束。
-- 🚨 UNION 显式列举列 ⇒ 对方给主表加列时视图**不报错、只静默丢列**。
--    这条静默失败由启动期自检 com.cpq.builder.selfcheck.CostAllVersionViewSelfCheck 拦住（AC-125）。

-- 1) ds_cost_basic_material_bom（物料BOM）
CREATE VIEW v_ds_cost_basic_material_bom_all AS
SELECT id, production_no, item_seq, component_no, operation_no, usage_characteristic, component_qty, component_qty_unit, base_qty, base_qty_unit, material_loss_rate, material_fixed_loss, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_material_bom
UNION ALL
SELECT id, production_no, item_seq, component_no, operation_no, usage_characteristic, component_qty, component_qty_unit, base_qty, base_qty_unit, material_loss_rate, material_fixed_loss, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_material_bom_history;
COMMENT ON VIEW v_ds_cost_basic_material_bom_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_material_bom UNION ALL ds_cost_basic_material_bom_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_material_bom_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_material_bom_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 2) ds_cost_basic_element_bom（物料与元素BOM）
CREATE VIEW v_ds_cost_basic_element_bom_all AS
SELECT id, production_no, material_part_no, item_seq, element_code, content_pct, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_element_bom
UNION ALL
SELECT id, production_no, material_part_no, item_seq, element_code, content_pct, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_element_bom_history;
COMMENT ON VIEW v_ds_cost_basic_element_bom_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_element_bom UNION ALL ds_cost_basic_element_bom_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_element_bom_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_element_bom_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 3) ds_cost_basic_incoming_process_fee（来料加工费）
CREATE VIEW v_ds_cost_basic_incoming_process_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, process_fee, currency, unit, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_incoming_process_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, process_fee, currency, unit, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_incoming_process_fee_history;
COMMENT ON VIEW v_ds_cost_basic_incoming_process_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_incoming_process_fee UNION ALL ds_cost_basic_incoming_process_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_incoming_process_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_incoming_process_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 4) ds_cost_basic_incoming_other_fee（来料其他费用）
CREATE VIEW v_ds_cost_basic_incoming_other_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, ratio_pct, fee, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_incoming_other_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, ratio_pct, fee, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_incoming_other_fee_history;
COMMENT ON VIEW v_ds_cost_basic_incoming_other_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_incoming_other_fee UNION ALL ds_cost_basic_incoming_other_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_incoming_other_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_incoming_other_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 5) ds_cost_basic_incoming_other_fixed_fee（来料其他固定费用）
CREATE VIEW v_ds_cost_basic_incoming_other_fixed_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_incoming_other_fixed_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_incoming_other_fixed_fee_history;
COMMENT ON VIEW v_ds_cost_basic_incoming_other_fixed_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_incoming_other_fixed_fee UNION ALL ds_cost_basic_incoming_other_fixed_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_incoming_other_fixed_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_incoming_other_fixed_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 6) ds_cost_basic_process_assembly_fee（加工费&组装费）
CREATE VIEW v_ds_cost_basic_process_assembly_fee_all AS
SELECT id, production_no, operation_no, process_fee, currency, unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_process_assembly_fee
UNION ALL
SELECT id, production_no, operation_no, process_fee, currency, unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_process_assembly_fee_history;
COMMENT ON VIEW v_ds_cost_basic_process_assembly_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_process_assembly_fee UNION ALL ds_cost_basic_process_assembly_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_process_assembly_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_process_assembly_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 7) ds_cost_basic_outsourced_process（其他外加工成本）
CREATE VIEW v_ds_cost_basic_outsourced_process_all AS
SELECT id, production_no, operation_no, outsourced_fee, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_outsourced_process
UNION ALL
SELECT id, production_no, operation_no, outsourced_fee, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_outsourced_process_history;
COMMENT ON VIEW v_ds_cost_basic_outsourced_process_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_outsourced_process UNION ALL ds_cost_basic_outsourced_process_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_outsourced_process_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_outsourced_process_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 8) ds_cost_basic_finished_ratio_fee（成品其他比例费用）
CREATE VIEW v_ds_cost_basic_finished_ratio_fee_all AS
SELECT id, production_no, item_seq, element_name, ratio_pct, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_finished_ratio_fee
UNION ALL
SELECT id, production_no, item_seq, element_name, ratio_pct, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_finished_ratio_fee_history;
COMMENT ON VIEW v_ds_cost_basic_finished_ratio_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_finished_ratio_fee UNION ALL ds_cost_basic_finished_ratio_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_finished_ratio_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_finished_ratio_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 9) ds_cost_basic_finished_fixed_fee（成品其他固定费用）
CREATE VIEW v_ds_cost_basic_finished_fixed_fee_all AS
SELECT id, production_no, item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_basic_finished_fixed_fee
UNION ALL
SELECT id, production_no, item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_basic_finished_fixed_fee_history;
COMMENT ON VIEW v_ds_cost_basic_finished_fixed_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_basic_finished_fixed_fee UNION ALL ds_cost_basic_finished_fixed_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_basic_finished_fixed_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_basic_finished_fixed_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 10) ds_cost_detail_material_bom（物料BOM）
CREATE VIEW v_ds_cost_detail_material_bom_all AS
SELECT id, production_no, item_seq, component_no, operation_no, usage_characteristic, component_qty, component_qty_unit, base_qty, base_qty_unit, material_loss_rate, material_fixed_loss, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_material_bom
UNION ALL
SELECT id, production_no, item_seq, component_no, operation_no, usage_characteristic, component_qty, component_qty_unit, base_qty, base_qty_unit, material_loss_rate, material_fixed_loss, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_material_bom_history;
COMMENT ON VIEW v_ds_cost_detail_material_bom_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_material_bom UNION ALL ds_cost_detail_material_bom_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_material_bom_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_material_bom_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 11) ds_cost_detail_element_bom（物料与元素BOM）
CREATE VIEW v_ds_cost_detail_element_bom_all AS
SELECT id, production_no, material_part_no, item_seq, element_code, content_pct, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_element_bom
UNION ALL
SELECT id, production_no, material_part_no, item_seq, element_code, content_pct, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_element_bom_history;
COMMENT ON VIEW v_ds_cost_detail_element_bom_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_element_bom UNION ALL ds_cost_detail_element_bom_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_element_bom_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_element_bom_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 12) ds_cost_detail_capacity（产能）
CREATE VIEW v_ds_cost_detail_capacity_all AS
SELECT id, production_no, operation_no, labor_std_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_capacity
UNION ALL
SELECT id, production_no, operation_no, labor_std_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_capacity_history;
COMMENT ON VIEW v_ds_cost_detail_capacity_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_capacity UNION ALL ds_cost_detail_capacity_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_capacity_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_capacity_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 13) ds_cost_detail_depreciation（设备折旧成本）
CREATE VIEW v_ds_cost_detail_depreciation_all AS
SELECT id, production_no, operation_no, depreciation_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_depreciation
UNION ALL
SELECT id, production_no, operation_no, depreciation_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_depreciation_history;
COMMENT ON VIEW v_ds_cost_detail_depreciation_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_depreciation UNION ALL ds_cost_detail_depreciation_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_depreciation_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_depreciation_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 14) ds_cost_detail_production_energy（生产设备能耗）
CREATE VIEW v_ds_cost_detail_production_energy_all AS
SELECT id, production_no, operation_no, production_energy_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_production_energy
UNION ALL
SELECT id, production_no, operation_no, production_energy_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_production_energy_history;
COMMENT ON VIEW v_ds_cost_detail_production_energy_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_production_energy UNION ALL ds_cost_detail_production_energy_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_production_energy_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_production_energy_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 15) ds_cost_detail_auxiliary_energy（辅助设备能耗）
CREATE VIEW v_ds_cost_detail_auxiliary_energy_all AS
SELECT id, production_no, operation_no, auxiliary_energy_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_auxiliary_energy
UNION ALL
SELECT id, production_no, operation_no, auxiliary_energy_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_auxiliary_energy_history;
COMMENT ON VIEW v_ds_cost_detail_auxiliary_energy_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_auxiliary_energy UNION ALL ds_cost_detail_auxiliary_energy_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_auxiliary_energy_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_auxiliary_energy_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 16) ds_cost_detail_tooling（模具工装成本）
CREATE VIEW v_ds_cost_detail_tooling_all AS
SELECT id, production_no, operation_no, item_seq, tooling_no, tooling_cost, tooling_life, cycle_output, tooling_unit_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_tooling
UNION ALL
SELECT id, production_no, operation_no, item_seq, tooling_no, tooling_cost, tooling_life, cycle_output, tooling_unit_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_tooling_history;
COMMENT ON VIEW v_ds_cost_detail_tooling_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_tooling UNION ALL ds_cost_detail_tooling_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_tooling_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_tooling_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 17) ds_cost_detail_consumable（生产耗材BOM）
CREATE VIEW v_ds_cost_detail_consumable_all AS
SELECT id, production_no, operation_no, consumable_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_consumable
UNION ALL
SELECT id, production_no, operation_no, consumable_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_consumable_history;
COMMENT ON VIEW v_ds_cost_detail_consumable_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_consumable UNION ALL ds_cost_detail_consumable_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_consumable_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_consumable_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 18) ds_cost_detail_packaging（包装材料BOM）
CREATE VIEW v_ds_cost_detail_packaging_all AS
SELECT id, production_no, operation_no, packaging_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_packaging
UNION ALL
SELECT id, production_no, operation_no, packaging_price, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_packaging_history;
COMMENT ON VIEW v_ds_cost_detail_packaging_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_packaging UNION ALL ds_cost_detail_packaging_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_packaging_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_packaging_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 19) ds_cost_detail_incoming_process_fee（来料加工费）
CREATE VIEW v_ds_cost_detail_incoming_process_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, process_fee, currency, unit, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_incoming_process_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, process_fee, currency, unit, loss_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_incoming_process_fee_history;
COMMENT ON VIEW v_ds_cost_detail_incoming_process_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_incoming_process_fee UNION ALL ds_cost_detail_incoming_process_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_incoming_process_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_incoming_process_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 20) ds_cost_detail_incoming_other_fee（来料其他费用）
CREATE VIEW v_ds_cost_detail_incoming_other_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, ratio_pct, fee, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_incoming_other_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, ratio_pct, fee, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_incoming_other_fee_history;
COMMENT ON VIEW v_ds_cost_detail_incoming_other_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_incoming_other_fee UNION ALL ds_cost_detail_incoming_other_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_incoming_other_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_incoming_other_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 21) ds_cost_detail_incoming_other_fixed_fee（来料其他固定费用）
CREATE VIEW v_ds_cost_detail_incoming_other_fixed_fee_all AS
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_incoming_other_fixed_fee
UNION ALL
SELECT id, production_no, item_seq, incoming_material_no, element_item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_incoming_other_fixed_fee_history;
COMMENT ON VIEW v_ds_cost_detail_incoming_other_fixed_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_incoming_other_fixed_fee UNION ALL ds_cost_detail_incoming_other_fixed_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_incoming_other_fixed_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_incoming_other_fixed_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 22) ds_cost_detail_process_assembly_fee（加工费&组装费）
CREATE VIEW v_ds_cost_detail_process_assembly_fee_all AS
SELECT id, production_no, operation_no, process_fee, currency, unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_process_assembly_fee
UNION ALL
SELECT id, production_no, operation_no, process_fee, currency, unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_process_assembly_fee_history;
COMMENT ON VIEW v_ds_cost_detail_process_assembly_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_process_assembly_fee UNION ALL ds_cost_detail_process_assembly_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_process_assembly_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_process_assembly_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 23) ds_cost_detail_outsourced_process（其他外加工成本）
CREATE VIEW v_ds_cost_detail_outsourced_process_all AS
SELECT id, production_no, operation_no, outsourced_fee, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_outsourced_process
UNION ALL
SELECT id, production_no, operation_no, outsourced_fee, currency, unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_outsourced_process_history;
COMMENT ON VIEW v_ds_cost_detail_outsourced_process_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_outsourced_process UNION ALL ds_cost_detail_outsourced_process_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_outsourced_process_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_outsourced_process_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 24) ds_cost_detail_plating_cost（电镀成本）
CREATE VIEW v_ds_cost_detail_plating_cost_all AS
SELECT id, production_no, plating_scheme_no, plating_version, plating_process_fee, plating_material_fee, currency, pricing_unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_plating_cost
UNION ALL
SELECT id, production_no, plating_scheme_no, plating_version, plating_process_fee, plating_material_fee, currency, pricing_unit, defect_rate, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_plating_cost_history;
COMMENT ON VIEW v_ds_cost_detail_plating_cost_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_plating_cost UNION ALL ds_cost_detail_plating_cost_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_plating_cost_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_plating_cost_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 25) ds_cost_detail_finished_ratio_fee（成品其他比例费用）
CREATE VIEW v_ds_cost_detail_finished_ratio_fee_all AS
SELECT id, production_no, item_seq, element_name, ratio_pct, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_finished_ratio_fee
UNION ALL
SELECT id, production_no, item_seq, element_name, ratio_pct, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_finished_ratio_fee_history;
COMMENT ON VIEW v_ds_cost_detail_finished_ratio_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_finished_ratio_fee UNION ALL ds_cost_detail_finished_ratio_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_finished_ratio_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_finished_ratio_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

-- 26) ds_cost_detail_finished_fixed_fee（成品其他固定费用）
CREATE VIEW v_ds_cost_detail_finished_fixed_fee_all AS
SELECT id, production_no, item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, true  AS is_current FROM ds_cost_detail_finished_fixed_fee
UNION ALL
SELECT id, production_no, item_seq, element_name, fee, currency, pricing_unit, version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, false AS is_current FROM ds_cost_detail_finished_fixed_fee_history;
COMMENT ON VIEW v_ds_cost_detail_finished_fixed_fee_all IS 'task-260819 v9 · S-31/D-84 全版本视图：ds_cost_detail_finished_fixed_fee UNION ALL ds_cost_detail_finished_fixed_fee_history。唯一用途 = 核价单按料号切版本（production_no 粒度，costing_order_version_override）。';
COMMENT ON COLUMN v_ds_cost_detail_finished_fixed_fee_all.is_current IS '🚨 派生常量，不是存储列，**不可写**：行来自主表 → true，来自 ds_cost_detail_finished_fixed_fee_history → false。与 V6 同名列 same-name-different-source —— V6 的 is_current 是主表上的存储布尔列、可被 UPDATE、会漂移（见 RECORD.md「V6 子表多版本化 is_current 审计范围」）；本列没有任何写入路径，UPDATE/索引/触发器一概不适用。新数据模型下「主表只存当前版本、旧版整组移入 _history」，所以它退化成一个常量。';

