-- task-0813: basic data numeric columns retain 12 decimal places.
-- Widen only; historical values are neither recalculated nor rewritten.
-- Integer capacity preserved: new_precision = old_precision - old_scale + 12.

DROP VIEW v_composite_child_elements;

ALTER TABLE auxiliary_energy
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN non_production_energy_price TYPE numeric(24,12),
    ALTER COLUMN total_hours TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE capacity
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN default_defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_cost TYPE numeric(24,12),
    ALTER COLUMN fixed_lead_time TYPE numeric(24,12),
    ALTER COLUMN variable_time TYPE numeric(24,12),
    ALTER COLUMN variable_time_batch TYPE numeric(24,12);

ALTER TABLE electricity_price
    ALTER COLUMN price TYPE numeric(24,12);

ALTER TABLE element_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN content TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE element_daily_price
    ALTER COLUMN raw_close TYPE numeric(26,12),
    ALTER COLUMN raw_high TYPE numeric(26,12),
    ALTER COLUMN raw_low TYPE numeric(26,12),
    ALTER COLUMN raw_open TYPE numeric(26,12),
    ALTER COLUMN raw_price TYPE numeric(26,12);

ALTER TABLE element_price
    ALTER COLUMN premium_price TYPE numeric(26,12);

ALTER TABLE element_price_strategy
    ALTER COLUMN premium TYPE numeric(26,12);

ALTER TABLE element_price_version_item
    ALTER COLUMN change_rate TYPE numeric(18,12),
    ALTER COLUMN current_price TYPE numeric(26,12),
    ALTER COLUMN previous_price TYPE numeric(26,12);

ALTER TABLE exchange_rate
    ALTER COLUMN rate TYPE numeric(24,12);

ALTER TABLE exchange_rate_v6
    ALTER COLUMN rate TYPE numeric(22,12),
    ALTER COLUMN ref_rate TYPE numeric(22,12);

ALTER TABLE fee_config
    ALTER COLUMN ratio TYPE numeric(18,12),
    ALTER COLUMN value TYPE numeric(24,12);

ALTER TABLE labor_rate
    ALTER COLUMN standard_labor_rate TYPE numeric(24,12);

ALTER TABLE material_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN material_ratio TYPE numeric(24,12),
    ALTER COLUMN net_weight TYPE numeric(26,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN rough_weight TYPE numeric(26,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE material_customer_map
    ALTER COLUMN exchange_rate TYPE numeric(22,12);

ALTER TABLE material_master
    ALTER COLUMN unit_weight TYPE numeric(24,12);

ALTER TABLE material_recipe_element
    ALTER COLUMN default_pct TYPE numeric(16,12),
    ALTER COLUMN max_pct TYPE numeric(16,12),
    ALTER COLUMN min_pct TYPE numeric(16,12);

ALTER TABLE packaging_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE plating_fee
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN plating_material_fee TYPE numeric(26,12),
    ALTER COLUMN plating_process_fee TYPE numeric(26,12);

ALTER TABLE plating_scheme
    ALTER COLUMN density TYPE numeric(24,12),
    ALTER COLUMN element_usage TYPE numeric(24,12),
    ALTER COLUMN plating_area TYPE numeric(24,12),
    ALTER COLUMN plating_thickness TYPE numeric(24,12),
    ALTER COLUMN surface_area TYPE numeric(24,12);

ALTER TABLE process_master
    ALTER COLUMN default_defect_rate TYPE numeric(18,12);

ALTER TABLE production_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE production_energy
    ALTER COLUMN batch_size TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN round_step TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE tooling_cost
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cycle_output TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_cost TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_price TYPE numeric(22,12);

ALTER TABLE unit_price
    ALTER COLUMN base_value TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fetched_price TYPE numeric(24,12),
    ALTER COLUMN market_ref_price TYPE numeric(24,12),
    ALTER COLUMN material_fixed_increase TYPE numeric(24,12),
    ALTER COLUMN material_increase_ratio TYPE numeric(18,12),
    ALTER COLUMN premium_fee TYPE numeric(24,12),
    ALTER COLUMN pricing_price TYPE numeric(24,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12);

-- 重建视图（V202 智能视图，属 docs/三大核心模块基线.md §5.1 锁定范围；定义须与迁移前逐字节等价，
-- 仅 ebi.content 的类型随上方 ALTER 从 numeric(18,6) 变为 numeric(24,12)，composition_pct 输出随之扩容）。
CREATE VIEW v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM element_bom_item ebi
     LEFT JOIN material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text
    AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = ((
        SELECT max(ebi2.characteristic::text) AS max
          FROM element_bom_item ebi2
         WHERE ebi2.system_type::text = ebi.system_type::text
           AND ebi2.customer_no::text = ebi.customer_no::text
           AND ebi2.material_no::text = ebi.material_no::text));
