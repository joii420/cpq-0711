-- ============================================================
-- V152: 料号版本历史登记表 mat_part_version_log
--
-- 设计目的:
--   - 记录每次 (customer_product_no, hf_part_no) 升版事件
--   - 存储 content_hash 用于 "新数据指纹 = 某历史版本指纹" 的判定
--     (用户决策 #1.2: 若导入数据匹配某历史版本, 不升版, 切回该版本)
--   - 存储 diff_summary 用于 UI 展示 "本次升版变了什么"
--
-- 用法:
--   S2 阶段 PartVersionService 写库:
--     - findMatchingHistoricalVersion(cpn, hf, newHash) → 按 content_hash 查
--     - bumpVersion(cpn, hf, newVersion) → INSERT 新行 + 更新 mapping.current_version
--   S1 阶段不写, V156 仅做初始化基线
--
-- 数据估计: 每个 (customer_product_no, hf_part_no) 1 条基线 + 升版数. 不会爆.
-- ============================================================

CREATE TABLE IF NOT EXISTS mat_part_version_log (
    customer_product_no  VARCHAR(64)  NOT NULL,
    hf_part_no           VARCHAR(64)  NOT NULL,
    version              INT          NOT NULL,
    content_hash         CHAR(32),
    diff_summary         JSONB,
    source_excel         TEXT,
    source_import_id     UUID,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           UUID,
    PRIMARY KEY (customer_product_no, hf_part_no, version)
);

-- 用于 S2 阶段 "查指纹匹配的历史版本" 快速 lookup
CREATE INDEX IF NOT EXISTS idx_mat_part_version_log_hash
    ON mat_part_version_log (content_hash)
    WHERE content_hash IS NOT NULL;

-- 用于 "查料号最新版本号" / "查料号所有版本" 列表场景
CREATE INDEX IF NOT EXISTS idx_mat_part_version_log_lookup
    ON mat_part_version_log (customer_product_no, hf_part_no, version DESC);

COMMENT ON TABLE mat_part_version_log IS
    '料号版本历史登记 — 每个 (customer_product_no, hf_part_no, version) 一条记录, 含指纹与变更摘要';
COMMENT ON COLUMN mat_part_version_log.content_hash IS
    'md5(全表行集合 JSON 排序后), 32 字符 hex. S2 阶段上线后补算, S1 阶段留 NULL';
COMMENT ON COLUMN mat_part_version_log.diff_summary IS
    'JSONB, 形如 {table_name: {added: N, changed: N, deleted: N}}, 由 PartVersionService.computeDiff 生成';

-- ----- 校验输出 -----
DO $$
BEGIN
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V152 完成: mat_part_version_log 已建立';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  - PK: (customer_product_no, hf_part_no, version)';
    RAISE NOTICE '  - INDEX: content_hash (partial), (cpn, hf, version DESC)';
    RAISE NOTICE '  - 数据为空, V156 会做初始化基线 (每个 cpn-hf 写 version=2000)';
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
