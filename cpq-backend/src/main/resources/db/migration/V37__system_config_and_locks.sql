-- ============================================================
-- V37: system_config + product_import_lock + ddl_operation_lock
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §5.1
-- ============================================================

-- ============== system_config ==============
CREATE TABLE IF NOT EXISTS system_config (
    config_key      VARCHAR(128) PRIMARY KEY,
    config_value    TEXT NOT NULL,
    default_value   TEXT NOT NULL,
    data_type       VARCHAR(16) NOT NULL,
    category        VARCHAR(32) NOT NULL,
    description     TEXT,
    modifiable_by   VARCHAR(32) NOT NULL DEFAULT 'SYSTEM_ADMIN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_config_key_format
        CHECK (config_key ~ '^[a-z_]+\.[a-z_]+$'),
    CONSTRAINT chk_config_data_type
        CHECK (data_type IN ('STRING','NUMBER','BOOLEAN','JSON')),
    CONSTRAINT chk_config_category
        CHECK (category IN ('validation','import','retention','element_price','business'))
);
CREATE INDEX idx_sysconf_category ON system_config(category);

COMMENT ON TABLE system_config IS 'v5.1 §5.1 系统配置表：阈值、超时、保留期、业务参数';

-- ============== product_import_lock ==============
CREATE TABLE IF NOT EXISTS product_import_lock (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customer(id),
    part_no             VARCHAR(64),
    granularity         VARCHAR(16) NOT NULL,
    locked_by           UUID NOT NULL,
    import_record_id    UUID,
    locked_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    released_at         TIMESTAMPTZ,
    released_by         UUID,
    release_reason      VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_pil_granularity CHECK (granularity IN ('PART_LEVEL','CUSTOMER_LEVEL')),
    CONSTRAINT chk_pil_status      CHECK (status IN ('ACTIVE','RELEASED','EXPIRED')),
    CONSTRAINT chk_pil_partno_consistency CHECK (
        (granularity='CUSTOMER_LEVEL' AND part_no IS NULL) OR
        (granularity='PART_LEVEL'     AND part_no IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_pil_active_part
    ON product_import_lock(customer_id, part_no)
    WHERE status='ACTIVE' AND part_no IS NOT NULL;
CREATE UNIQUE INDEX uq_pil_active_customer
    ON product_import_lock(customer_id)
    WHERE status='ACTIVE' AND part_no IS NULL;
CREATE INDEX idx_pil_locked_by    ON product_import_lock(locked_by, status);
CREATE INDEX idx_pil_expires      ON product_import_lock(expires_at, status);
CREATE INDEX idx_pil_import_rec   ON product_import_lock(import_record_id);

COMMENT ON TABLE product_import_lock IS 'v5.1 §3.4 产品级悲观锁：自适应粒度（料号/客户级）';

-- ============== ddl_operation_lock ==============
CREATE TABLE IF NOT EXISTS ddl_operation_lock (
    lock_key    VARCHAR(64) PRIMARY KEY,
    locked_by   UUID NOT NULL,
    locked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    operation_desc TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  UUID,
    updated_by  UUID
);
INSERT INTO ddl_operation_lock(lock_key, locked_by, locked_at, expires_at, operation_desc)
VALUES ('global', '00000000-0000-0000-0000-000000000000', NOW(), NOW() - INTERVAL '1 second', 'bootstrap-placeholder')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE ddl_operation_lock IS 'v5.1 §3.5 DDL 全局锁：Flyway 扩列运行时 ALTER 互斥';

-- ============== 初始化 system_config（23 条）==============
-- 通用校验
INSERT INTO system_config(config_key, config_value, default_value, data_type, category, description, modifiable_by) VALUES
  ('validation.completeness_threshold', '0.8', '0.8', 'NUMBER', 'validation', '元素价格抓取数据完整度阈值（v2启用）', 'SYSTEM_ADMIN'),
  ('validation.composition_tolerance', '0.01', '0.01', 'NUMBER', 'validation', '元素 BOM 含量合计容差', 'SYSTEM_ADMIN'),
  ('validation.loss_rate_max', '0.5', '0.5', 'NUMBER', 'validation', '损耗率上限', 'SYSTEM_ADMIN'),
  ('validation.defect_rate_max', '0.3', '0.3', 'NUMBER', 'validation', '不良率上限', 'SYSTEM_ADMIN'),
  ('validation.assembly_reject_rate_max', '0.3', '0.3', 'NUMBER', 'validation', '组装报废率上限', 'SYSTEM_ADMIN'),
  ('validation.price_rise_min', '-0.5', '-0.5', 'NUMBER', 'validation', '涨价比例下限', 'SYSTEM_ADMIN'),
  ('validation.price_rise_max', '1.0', '1.0', 'NUMBER', 'validation', '涨价比例上限', 'SYSTEM_ADMIN'),
  ('validation.import_max_rows', '2000', '2000', 'NUMBER', 'validation', '单次导入硬上限', 'SYSTEM_ADMIN'),
  ('validation.allowed_currencies', '["USD","CNY","EUR","HKD","JPY"]', '["USD","CNY","EUR","HKD","JPY"]', 'JSON', 'validation', '允许货币代码', 'SYSTEM_ADMIN'),
  ('validation.allowed_units', '["KG","G","PCS","M","CM","MM"]', '["KG","G","PCS","M","CM","MM"]', 'JSON', 'validation', '允许单位代码', 'SYSTEM_ADMIN')
ON CONFLICT (config_key) DO NOTHING;

-- 性能/超时
INSERT INTO system_config(config_key, config_value, default_value, data_type, category, description, modifiable_by) VALUES
  ('import.product_lock_timeout_seconds', '300', '300', 'NUMBER', 'import', '产品悲观锁总超时', 'SYSTEM_ADMIN'),
  ('import.product_lock_heartbeat_seconds', '30', '30', 'NUMBER', 'import', '锁心跳间隔', 'SYSTEM_ADMIN'),
  ('import.product_lock_downgrade_threshold', '100', '100', 'NUMBER', 'import', '锁降级阈值', 'SYSTEM_ADMIN'),
  ('import.preview_response_timeout_seconds', '30', '30', 'NUMBER', 'import', '预览响应超时', 'SYSTEM_ADMIN'),
  ('import.draft_save_debounce_ms', '500', '500', 'NUMBER', 'import', '草稿保存防抖', 'SYSTEM_ADMIN'),
  ('import.ddl_lock_timeout_seconds', '300', '300', 'NUMBER', 'import', 'DDL 全局锁超时', 'SYSTEM_ADMIN')
ON CONFLICT (config_key) DO NOTHING;

-- 保留期
INSERT INTO system_config(config_key, config_value, default_value, data_type, category, description, modifiable_by) VALUES
  ('retention.change_log_years', '5', '5', 'NUMBER', 'retention', '变更日志保留年数', 'SYSTEM_ADMIN'),
  ('retention.original_excel_months', '12', '12', 'NUMBER', 'retention', '原始 Excel 保留月数', 'SYSTEM_ADMIN'),
  ('retention.element_daily_price_years', '0', '0', 'NUMBER', 'retention', '元素每日价格保留年数（0=永久）', 'SYSTEM_ADMIN')
ON CONFLICT (config_key) DO NOTHING;

-- 元素价格（v2 启用）
INSERT INTO system_config(config_key, config_value, default_value, data_type, category, description, modifiable_by) VALUES
  ('element_price.fetch_cron', '0 0 8 * * ?', '0 0 8 * * ?', 'STRING', 'element_price', '抓取定时任务cron', 'SYSTEM_ADMIN'),
  ('element_price.fetch_timeout_seconds', '30', '30', 'NUMBER', 'element_price', '抓取单源超时', 'SYSTEM_ADMIN'),
  ('element_price.fetch_retry_count', '3', '3', 'NUMBER', 'element_price', '抓取重试次数', 'SYSTEM_ADMIN'),
  ('element_price.fetch_alert_consecutive_failures', '3', '3', 'NUMBER', 'element_price', '连续失败告警阈值', 'SYSTEM_ADMIN')
ON CONFLICT (config_key) DO NOTHING;

-- 业务参数
INSERT INTO system_config(config_key, config_value, default_value, data_type, category, description, modifiable_by) VALUES
  ('business.gross_margin_warning_min', '0.15', '0.15', 'NUMBER', 'business', '毛利率警告阈值', 'SALES_MANAGER'),
  ('business.gross_margin_block_min', '0.05', '0.05', 'NUMBER', 'business', '毛利率阻止提交阈值', 'SALES_MANAGER')
ON CONFLICT (config_key) DO NOTHING;
