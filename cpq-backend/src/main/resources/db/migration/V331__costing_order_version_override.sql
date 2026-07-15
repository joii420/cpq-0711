-- V331__costing_order_version_override.sql
-- task-0713 B1：核价单版本 override 表 —— 记「这张核价单里，哪个页签(component)的哪个销售料号
-- 被切到哪个版本」。生命周期归单张核价单独立（不继承旧核价单，见需求说明 §B1）。
CREATE TABLE costing_order_version_override (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    costing_order_id  uuid NOT NULL REFERENCES costing_order(id),
    component_id      uuid NOT NULL,
    part_no           varchar(40) NOT NULL,
    view_version      varchar(40) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_covo UNIQUE (costing_order_id, component_id, part_no)
);

CREATE INDEX idx_covo_order ON costing_order_version_override(costing_order_id);
