-- V307__costing_bom_tree_config.sql
-- 全局可配置核价树递归 SQL;全局同一时刻最多一条生效。
CREATE TABLE costing_bom_tree_config (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text NOT NULL,
    sql_template text NOT NULL,
    is_active    boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- 全局最多一条生效(部分唯一索引)
CREATE UNIQUE INDEX ux_cbt_active ON costing_bom_tree_config (is_active) WHERE is_active;
