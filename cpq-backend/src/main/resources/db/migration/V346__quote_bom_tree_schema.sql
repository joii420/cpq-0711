-- V346: task-0721 报价侧树状结构与页签类型属性 — 数据模型迁移（B1）
-- spec: dev-docs/task-0721-报价侧树状结构与页签类型属性/{需求说明.md,api.md,backtask.md}
--       docs/superpowers/specs/2026-07-21-报价单BOM树状渲染-design.md
--
-- ① 递归 SQL 配置增加 usage 维度（QUOTE / COSTING），默认 COSTING —— 核价侧现役 active 配置
--    （BOMV2）必须原地保住,不可省略默认值。
ALTER TABLE costing_bom_tree_config
  ADD COLUMN usage VARCHAR(16) NOT NULL DEFAULT 'COSTING';

-- 每个 usage 至多一条生效配置（原为全局唯一 active）。部分唯一索引必须带 WHERE is_active，
-- 否则会禁止同 usage 下存在多条非生效配置。
CREATE UNIQUE INDEX uq_bom_tree_config_active_per_usage
  ON costing_bom_tree_config(usage) WHERE is_active;

-- ② 组件增加页签类型属性：BOM / 材质元素 / 零件 / 外购件 / 主件（值域强校验在应用层做，
--    列本身不加 CHECK 约束以便未来演进不必再迁移）。
ALTER TABLE component ADD COLUMN tab_type VARCHAR(16);

-- ③ 报价行增加节点级墓碑（剪枝）：["<nodeId>", ...]，与既有 deleted_row_keys（component 级）
--    同风格，不引入新表/FK。
ALTER TABLE quotation_line_item ADD COLUMN deleted_tree_nodes jsonb;
