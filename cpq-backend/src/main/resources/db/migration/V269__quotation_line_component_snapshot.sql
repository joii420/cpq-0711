-- V269: 加产品整份快照 Phase 1 — quotation_line_component_data 增基础冻结层
--
-- 背景(docs/方案-加产品整份快照.md):报价单应展示"报价单自己的数据"——加产品时把每个组件
-- 整行解析值(basicDataValues:BASIC_DATA + DATA_SOURCE + BNF path 三类)冻进报价单,
-- 之后展示/编辑读副本,基础表变化不影响本单。
--
-- 两层模型:
--   snapshot_rows(本次新增)= 基础冻结层,存整组行 [{driverRow, basicDataValues}, ...]
--   row_data(现有)        = 编辑层,用户 INPUT 值
-- 渲染有效值 = 编辑值优先,否则取冻结基础值。
--
-- Phase 1 仅"写"(加性):configure 加产品时写 snapshot_rows;渲染暂不变。

ALTER TABLE quotation_line_component_data
    ADD COLUMN IF NOT EXISTS snapshot_rows JSONB,
    ADD COLUMN IF NOT EXISTS snapshot_at   TIMESTAMPTZ;

COMMENT ON COLUMN quotation_line_component_data.snapshot_rows IS
    '加产品整份快照-基础冻结层:整组行 [{driverRow, basicDataValues}]，加产品/从基础刷新时写;渲染读副本(Phase 2)';
