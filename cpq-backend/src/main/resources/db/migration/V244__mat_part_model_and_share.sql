-- V244: 3D 模型注册 + 分享链接
-- 详见 docs/3D产品选配方案.md §7.9 + §17.2

CREATE TABLE IF NOT EXISTS mat_part_model (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    part_no           VARCHAR(64) NOT NULL,
    version           INTEGER NOT NULL DEFAULT 1,
    label             VARCHAR(255),
    is_current        BOOLEAN NOT NULL DEFAULT TRUE,
    glb_url           TEXT NOT NULL,
    thumbnail_url     TEXT,
    mesh_count        INTEGER,
    vertices          INTEGER,
    size_kb           INTEGER,
    metadata          JSONB DEFAULT '{}',
    uploaded_by       UUID,
    uploaded_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(part_no, version)
);
CREATE INDEX IF NOT EXISTS idx_mpm_partno ON mat_part_model(part_no);
CREATE INDEX IF NOT EXISTS idx_mpm_current ON mat_part_model(part_no, is_current) WHERE is_current = TRUE;

-- 3D 源文件多文件表（§6 UG NX 工作流）
CREATE TABLE IF NOT EXISTS mat_part_source_file (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    part_no           VARCHAR(64) NOT NULL,
    model_id          UUID REFERENCES mat_part_model(id) ON DELETE CASCADE,
    file_role         VARCHAR(32) NOT NULL,                    -- UGNX_SOURCE / STP_NEUTRAL / GLB_RENDER / ...
    file_url          TEXT NOT NULL,
    file_size_bytes   BIGINT,
    md5_hash          VARCHAR(64),
    uploaded_by       UUID,
    uploaded_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    metadata          JSONB DEFAULT '{}',
    CONSTRAINT chk_mpsf_role CHECK (file_role IN (
        'UGNX_SOURCE','STP_NEUTRAL','GLB_RENDER','GLB_DRACO','THUMBNAIL','LOD_LOW','LOD_HIGH','OTHER'
    ))
);
CREATE INDEX IF NOT EXISTS idx_mpsf_part ON mat_part_source_file(part_no);
CREATE INDEX IF NOT EXISTS idx_mpsf_model ON mat_part_source_file(model_id) WHERE model_id IS NOT NULL;

-- §17.2 分享链接
CREATE TABLE IF NOT EXISTS product_config_share (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id        UUID NOT NULL REFERENCES product_config_instance(id) ON DELETE CASCADE,
    share_type         VARCHAR(32) NOT NULL,
    share_token        VARCHAR(64) NOT NULL UNIQUE,
    shared_by          UUID,
    shared_to_user_id  UUID,
    shared_to_email    VARCHAR(128),
    expires_at         TIMESTAMP WITH TIME ZONE,
    access_count       INTEGER NOT NULL DEFAULT 0,
    last_accessed_at   TIMESTAMP WITH TIME ZONE,
    can_modify         BOOLEAN NOT NULL DEFAULT FALSE,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    revoked_at         TIMESTAMP WITH TIME ZONE,
    revoked_by         UUID,
    revoke_reason      TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pcs_type CHECK (share_type IN ('CUSTOMER_SELF','INTERNAL','PUBLIC_PRESET')),
    CONSTRAINT chk_pcs_status CHECK (status IN ('ACTIVE','EXPIRED','REVOKED'))
);
CREATE INDEX IF NOT EXISTS idx_pcs_token ON product_config_share(share_token);
CREATE INDEX IF NOT EXISTS idx_pcs_instance ON product_config_share(instance_id);
CREATE INDEX IF NOT EXISTS idx_pcs_status ON product_config_share(status);

CREATE TABLE IF NOT EXISTS product_config_share_access (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    share_id           UUID NOT NULL REFERENCES product_config_share(id) ON DELETE CASCADE,
    accessed_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip                 VARCHAR(64),
    user_agent         TEXT,
    action             VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_pcsa_share ON product_config_share_access(share_id);
