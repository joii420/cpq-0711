-- ============================================================
-- V42: BasicDataAttribute 扩字段 + element_price 字段确认
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §6.2 §6.3
-- ============================================================

-- ============== BasicDataAttribute 扩字段 §6.2 ==============
-- 字段重要性：CRITICAL / IMPORTANT / NORMAL
ALTER TABLE basic_data_attribute
    ADD COLUMN IF NOT EXISTS importance_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE basic_data_attribute
    ADD CONSTRAINT chk_bda_importance_level
        CHECK (importance_level IN ('CRITICAL','IMPORTANT','NORMAL'));

-- 计算影响标记：字段变更是否触发公式重算
ALTER TABLE basic_data_attribute
    ADD COLUMN IF NOT EXISTS affects_calculation BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN basic_data_attribute.importance_level IS 'v5.1 §6.2 字段重要性：CRITICAL/IMPORTANT/NORMAL';
COMMENT ON COLUMN basic_data_attribute.affects_calculation IS 'v5.1 §6.2 字段变更是否触发公式重算';

-- ============== element_price source_id/fetch_rule_id 确认 nullable §6.3 ==============
-- V41 已将 source_id / fetch_rule_id 建为 nullable（REFERENCES 不带 NOT NULL）
-- 此处做说明性注释，无需额外 DDL
-- Ref: v5.1 §6.3 "source_id / fetch_rule_id 改为 nullable（v2 启用配置时的过渡状态可允许其中一项为空）"
