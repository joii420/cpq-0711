-- V330__model_config.sql —— task-0712 B5：3D 模型配置新表
-- 按对象类型(SALES_PART/MATERIAL) + 对象键(销售料号/材质配方码) 绑定 .glb 模型 + 预览图。
-- 支持多版本，同一 subject 仅一条 is_current=true（部分唯一索引保证并发安全）。
-- 详见 dev-docs/task-0712-选配模板和报价单选配功能/backtask.md B5.1 + api.md §4。

CREATE TABLE model_config (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_type  VARCHAR(20) NOT NULL,          -- SALES_PART / MATERIAL
  subject_key   VARCHAR(64) NOT NULL,          -- 销售料号 / 材质配方码
  version       INTEGER     NOT NULL DEFAULT 1,
  is_current    BOOLEAN     NOT NULL DEFAULT TRUE,
  label         VARCHAR(255),
  glb_url       TEXT        NOT NULL,
  thumbnail_url TEXT,
  mesh_count    INTEGER, vertices INTEGER, size_kb INTEGER,
  metadata      JSONB DEFAULT '{}',
  uploaded_by   UUID, uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_mc_subject CHECK (subject_type IN ('SALES_PART','MATERIAL')),
  UNIQUE (subject_type, subject_key, version)
);
CREATE UNIQUE INDEX uq_model_config_current ON model_config(subject_type, subject_key) WHERE is_current;
CREATE INDEX idx_model_config_lookup ON model_config(subject_type, subject_key, is_current);

CREATE TABLE model_config_file (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  model_config_id UUID NOT NULL REFERENCES model_config(id) ON DELETE CASCADE,
  file_role       VARCHAR(20) NOT NULL,        -- GLB / THUMBNAIL / OTHER
  file_url        TEXT NOT NULL, file_size_bytes BIGINT, md5_hash VARCHAR(64),
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_mcf_role CHECK (file_role IN ('GLB','THUMBNAIL','OTHER'))
);
CREATE INDEX idx_model_config_file_config ON model_config_file(model_config_id);
