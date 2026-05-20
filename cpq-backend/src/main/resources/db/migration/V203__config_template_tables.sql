-- V203: 配置模板 (config_template / config_category / config_item) 三表
--
-- 背景: LIST_FORMULA 字段类型 (Phase B) 需要一个独立的"配置模板"实体作为数据源,
--       替代 Phase A 直接绑物理表的方案. 用户期望:
--   - 配置模板有自己的 CRUD 一级菜单 (DRAFT/PUBLISHED/ARCHIVED 三态机)
--   - 大类 (category) 用户可自由扩展
--   - 明细项 (item) 是大类下的具体配置项, 含 default_value
--   - 与 basic_data_config 完全脱钩 — 用户从零录入
--
-- 表结构:
--   config_template      模板主表
--   config_category      大类 (1:N, FK template_id, CASCADE)
--   config_item          明细项 (1:N, FK category_id, CASCADE)
--
-- 三态机说明:
--   - DRAFT     初创态, 不能被 LIST_FORMULA 字段引用 (前端 Drawer 过滤)
--   - PUBLISHED 可用态, 才会出现在选择器里
--   - ARCHIVED  归档态, 历史报价单 snapshot 仍可渲染, 不能新建绑定

-- ── 1. 主表: config_template ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS config_template (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  UUID,
    published_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_config_template_code ON config_template(code);
CREATE INDEX IF NOT EXISTS idx_config_template_status ON config_template(status);
COMMENT ON TABLE config_template IS 'V203: LIST_FORMULA 字段类型的配置模板主表 (Phase B)';
COMMENT ON COLUMN config_template.status IS 'DRAFT/PUBLISHED/ARCHIVED — 仅 PUBLISHED 可被组件字段引用';

-- ── 2. 大类: config_category ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS config_category (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES config_template(id) ON DELETE CASCADE,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_config_category_tpl_code ON config_category(template_id, code);
CREATE INDEX IF NOT EXISTS idx_config_category_template ON config_category(template_id);
COMMENT ON TABLE config_category IS 'V203: 配置模板下的大类 (1:N, 用户自由扩展)';

-- ── 3. 明细项: config_item ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS config_item (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id   UUID NOT NULL REFERENCES config_category(id) ON DELETE CASCADE,
    code          VARCHAR(50)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    default_value VARCHAR(500),
    sort_order    INT          NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_config_item_cat_code ON config_item(category_id, code);
CREATE INDEX IF NOT EXISTS idx_config_item_category ON config_item(category_id);
COMMENT ON TABLE config_item IS 'V203: 配置模板大类下的明细项 (1:N, 含 default_value)';
COMMENT ON COLUMN config_item.default_value IS 'LIST_FORMULA 渲染时 per_item_rules 缺项/全分支不命中时的兜底';

-- ── 自检 ─────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_tpl_exists BOOLEAN;
    v_cat_exists BOOLEAN;
    v_item_exists BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='config_template') INTO v_tpl_exists;
    SELECT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='config_category') INTO v_cat_exists;
    SELECT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='config_item') INTO v_item_exists;
    IF NOT (v_tpl_exists AND v_cat_exists AND v_item_exists) THEN
        RAISE EXCEPTION 'V203 自检失败: tpl=% cat=% item=%', v_tpl_exists, v_cat_exists, v_item_exists;
    END IF;
    RAISE NOTICE 'V203 自检通过: 3 张 config_* 表已创建 (LIST_FORMULA Phase B 基础设施)';
END
$$;
