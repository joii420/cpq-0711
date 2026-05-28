-- V241: 约束 + 3D 渲染规则 + 价格规则 三表
-- 详见 docs/3D产品选配方案.md §7.5-7.7
-- 注：约束求值算法 / 客户覆盖 / 审批 当前未文档化，相关表骨架先建但 Resource 暂不开放（路线图见 3D-集成总览-索引.md §八）

CREATE TABLE IF NOT EXISTS product_config_constraint (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID NOT NULL REFERENCES product_config_template(id) ON DELETE CASCADE,
    constraint_type VARCHAR(32) NOT NULL,
    trigger_expr    JSONB NOT NULL,
    affected_expr   JSONB NOT NULL,
    message         TEXT,
    severity        VARCHAR(16) NOT NULL DEFAULT 'ERROR',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pcc_type CHECK (constraint_type IN ('REQUIRES','EXCLUDES','IMPLIES','HIDES','NUMERIC_RANGE')),
    CONSTRAINT chk_pcc_severity CHECK (severity IN ('ERROR','WARN','INFO'))
);
CREATE INDEX IF NOT EXISTS idx_pcc_template ON product_config_constraint(template_id);

CREATE TABLE IF NOT EXISTS product_config_3d_rule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    option_value_id UUID NOT NULL REFERENCES product_config_option_value(id) ON DELETE CASCADE,
    action          VARCHAR(32) NOT NULL,
    target_mesh     VARCHAR(128),
    params          JSONB NOT NULL DEFAULT '{}',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pc3d_action CHECK (action IN ('SHOW_MESH','HIDE_MESH','REPLACE_MATERIAL','SWAP_MESH','TRANSFORM_MESH'))
);
CREATE INDEX IF NOT EXISTS idx_pc3d_value ON product_config_3d_rule(option_value_id);

CREATE TABLE IF NOT EXISTS product_config_price_rule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID NOT NULL REFERENCES product_config_template(id) ON DELETE CASCADE,
    option_value_id UUID REFERENCES product_config_option_value(id) ON DELETE CASCADE,
    rule_type       VARCHAR(32) NOT NULL DEFAULT 'DELTA',
    amount          NUMERIC(18,4),
    formula_token   VARCHAR(255),
    currency        VARCHAR(8) DEFAULT 'CNY',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pcpr_rule_type CHECK (rule_type IN ('DELTA','OVERRIDE','FORMULA'))
);
CREATE INDEX IF NOT EXISTS idx_pcpr_template ON product_config_price_rule(template_id);
CREATE INDEX IF NOT EXISTS idx_pcpr_value ON product_config_price_rule(option_value_id) WHERE option_value_id IS NOT NULL;
