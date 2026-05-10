-- V125: 电镀双侧分流 — 报价侧 mat_plating_* / 核价侧 costing_part_plating_fee
--
-- 起因:
--   `plating_fee` / `plating_plan` 是核价/报价共享物理表,违反"报价基础数据 vs 核价基础数据
--   两套独立"的总体设计 (mat_bom vs costing_part_material_bom 已分流, 电镀漏掉)。
--   导致同 partNo 报价/核价单的电镀数据耦合 — 报价导入会改核价单展示,反之亦然。
--
-- 修法 (用户批准方案 C: 双写迁移):
--   ── 新建 3 张表 ──────────────────────────────────────────────────────────
--   1. mat_plating_plan       — 报价侧电镀方案库 (mirror plating_plan, GLOBAL)
--   2. mat_plating_fee        — 报价侧电镀费用 (mirror plating_fee, CUSTOMER + version)
--   3. costing_part_plating_fee — 核价侧电镀费用 (mirror plating_fee 非 versioned 简化版)
--   (核价侧方案库 costing_part_plating 由 V76 已建,保持不变)
--
--   ── 数据迁移 (历史 plating_fee/plating_plan 双写)─────────────────────────
--   两侧都收到现有数据的全量副本,从此独立演化。下次双侧 import 各覆盖各的。
--
--   ── basic_data_config / basic_data_attribute 双轨注册 ────────────────────
--   "电镀费用"/"电镀方案" 各拆出 QUOTATION + COSTING 两份配置,target_table 分别指向
--   报价侧/核价侧物理表。已有的 sheet_name='电镀方案' 配置 (V94 拆过) 改 target_table.
--
-- 配套 (后续迁移/代码):
--   V126 — 组件 fields/data_driver_path 切到双侧表 + 视图 v_q_part_plating_scheme
--   Java — VersionedWriter / TableRegistry / PathToSqlGenerator / DriftDetectionService
--          / FieldMetaCache / BasicDataImportServiceV5 / CustomerPartCandidateService
--          全部加双侧 TableMeta + 字段元数据 + 路径
--   前端 — CostingPartDataPage 「电镀费用」子页切到 costing_part_plating_fee

-- ════════════════════════════════════════════════════════════════════════════
-- A. 新建 mat_plating_plan (报价侧方案库, mirror plating_plan)
-- ════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS mat_plating_plan (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code            VARCHAR(32)  NOT NULL,
    version              VARCHAR(16)  NOT NULL,
    seq_no               INT          NOT NULL,
    plating_element      VARCHAR(64),
    plating_area         DECIMAL(18,4),
    coating_thickness    DECIMAL(10,4),
    plating_requirement  VARCHAR(256),
    imported_by          UUID,
    import_record_id     UUID,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           UUID,
    updated_by           UUID
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_plating_plan_row
    ON mat_plating_plan(plan_code, version, seq_no);
COMMENT ON TABLE mat_plating_plan IS 'V125: 报价侧电镀方案库 (与核价侧 costing_part_plating 独立)';

-- ════════════════════════════════════════════════════════════════════════════
-- B. 新建 mat_plating_fee (报价侧电镀费用, mirror plating_fee)
-- ════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS mat_plating_fee (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           UUID         NOT NULL REFERENCES customer(id),
    hf_part_no            VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    version               INT          NOT NULL DEFAULT 1,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    plating_plan_code     VARCHAR(32),
    plan_version          VARCHAR(16),
    plating_process_fee   DECIMAL(18,4),
    plating_material_fee  DECIMAL(18,4),
    currency              VARCHAR(8),
    price_unit            VARCHAR(16),
    defect_rate           DECIMAL(10,4),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    imported_by           UUID,
    import_record_id      UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_mat_plating_fee_status CHECK (status IN ('ACTIVE','DELETED'))
);
CREATE INDEX IF NOT EXISTS idx_mat_plating_fee_cust
    ON mat_plating_fee(customer_id, hf_part_no, version);
CREATE INDEX IF NOT EXISTS idx_mat_plating_fee_curr
    ON mat_plating_fee(is_current);
-- 与 plating_fee 一致的当前版本唯一约束 (业务键 = customer + part + plan + plan_version)
CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_plating_fee_current
    ON mat_plating_fee (
        customer_id, hf_part_no,
        COALESCE(plating_plan_code, ''),
        COALESCE(plan_version, '')
    )
    WHERE is_current = true;
COMMENT ON TABLE mat_plating_fee IS 'V125: 报价侧电镀费用 (CUSTOMER + version, 与核价侧 costing_part_plating_fee 独立)';

-- ════════════════════════════════════════════════════════════════════════════
-- C. 新建 costing_part_plating_fee (核价侧电镀费用, 简化版 — 不带 customer 维度)
-- ════════════════════════════════════════════════════════════════════════════
-- 核价侧物理表族 (costing_part_*) 全部按 partNo 维度,不带 customer_id;
-- 设计上"核价不区分客户,出厂前给 part 标准成本"。所以 costing_part_plating_fee
-- 也按 partNo + plan_code + plan_version 唯一,不引 customer_id / is_current / version 列。
CREATE TABLE IF NOT EXISTS costing_part_plating_fee (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no            VARCHAR(100) NOT NULL,
    plating_plan_code     VARCHAR(32),
    plan_version          VARCHAR(16),
    plating_process_fee   DECIMAL(18,4),
    plating_material_fee  DECIMAL(18,4),
    currency              VARCHAR(10),
    price_unit            VARCHAR(20),
    defect_rate           DECIMAL(10,4),
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    notes                 TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_costing_part_plating_fee UNIQUE
        (hf_part_no, plating_plan_code, plan_version)
);
CREATE INDEX IF NOT EXISTS idx_costing_part_plating_fee_part
    ON costing_part_plating_fee(hf_part_no);
COMMENT ON TABLE costing_part_plating_fee IS
    'V125: 核价侧电镀费用 (按 partNo, 与报价侧 mat_plating_fee 独立)';

-- ════════════════════════════════════════════════════════════════════════════
-- D. 数据迁移 — 双写: 历史 plating_fee 行 → mat_plating_fee + costing_part_plating_fee
--    plating_plan 行 → mat_plating_plan (核价侧 costing_part_plating 由 V76 已存在数据,
--    若也想迁全局方案库到核价侧,这里同时填充)
-- ════════════════════════════════════════════════════════════════════════════

-- D.1 plating_plan → mat_plating_plan (报价侧)
INSERT INTO mat_plating_plan
    (id, plan_code, version, seq_no, plating_element, plating_area, coating_thickness,
     plating_requirement, created_at, updated_at, created_by, updated_by)
SELECT gen_random_uuid(), plan_code, version, seq_no, plating_element, plating_area,
       coating_thickness, plating_requirement, created_at, updated_at, created_by, updated_by
  FROM plating_plan
ON CONFLICT (plan_code, version, seq_no) DO NOTHING;

-- D.2 plating_plan → costing_part_plating (核价侧, 已 V76 创建; 若已有则跳过)
INSERT INTO costing_part_plating
    (id, plating_no, version_number, seq_no, element_attr, plating_area_cm2,
     layer_thickness_um, requirement, is_active, created_at, updated_at)
SELECT gen_random_uuid(), pp.plan_code, pp.version, pp.seq_no, pp.plating_element,
       pp.plating_area, pp.coating_thickness, pp.plating_requirement, true,
       pp.created_at, pp.updated_at
  FROM plating_plan pp
  LEFT JOIN costing_part_plating cpp
    ON cpp.plating_no = pp.plan_code
   AND cpp.version_number = pp.version
   AND cpp.seq_no = pp.seq_no
 WHERE cpp.id IS NULL;

-- D.3 plating_fee → mat_plating_fee (报价侧, 全量复制 is_current=true 行)
INSERT INTO mat_plating_fee
    (id, customer_id, hf_part_no, version, is_current, plating_plan_code, plan_version,
     plating_process_fee, plating_material_fee, currency, price_unit, defect_rate,
     status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by)
SELECT gen_random_uuid(), customer_id, hf_part_no, version, is_current, plating_plan_code,
       plan_version, plating_process_fee, plating_material_fee, currency, price_unit,
       defect_rate, status, imported_by, import_record_id, created_at, updated_at,
       created_by, updated_by
  FROM plating_fee
 WHERE is_current = true
ON CONFLICT (customer_id, hf_part_no, COALESCE(plating_plan_code, ''), COALESCE(plan_version, ''))
WHERE is_current = true
DO NOTHING;

-- D.4 plating_fee → costing_part_plating_fee (核价侧, 按 partNo 去重)
-- 由于核价侧无 customer 维度,同 partNo 多客户的 plating_fee 行只会保留 1 行 — 用最新版本
INSERT INTO costing_part_plating_fee
    (id, hf_part_no, plating_plan_code, plan_version, plating_process_fee,
     plating_material_fee, currency, price_unit, defect_rate, is_active, created_at, updated_at)
SELECT gen_random_uuid(), pf.hf_part_no, pf.plating_plan_code, pf.plan_version,
       pf.plating_process_fee, pf.plating_material_fee, pf.currency, pf.price_unit,
       pf.defect_rate, true, pf.created_at, pf.updated_at
  FROM (
        SELECT DISTINCT ON (hf_part_no, COALESCE(plating_plan_code, ''), COALESCE(plan_version, ''))
               hf_part_no, plating_plan_code, plan_version, plating_process_fee,
               plating_material_fee, currency, price_unit, defect_rate, created_at, updated_at
          FROM plating_fee
         WHERE is_current = true
         ORDER BY hf_part_no, COALESCE(plating_plan_code, ''), COALESCE(plan_version, ''),
                  version DESC
       ) pf
ON CONFLICT (hf_part_no, plating_plan_code, plan_version) DO NOTHING;

-- ════════════════════════════════════════════════════════════════════════════
-- E. basic_data_config 切换 target_table — "电镀方案" / "电镀费用" 双轨配置
-- ════════════════════════════════════════════════════════════════════════════

-- E.1 「电镀方案」kind=QUOTATION (V94 已建,target=plating_plan) → 改 target=mat_plating_plan
UPDATE basic_data_config
   SET target_table = 'mat_plating_plan',
       description = COALESCE(description,'') || ' [V125] target 切到 mat_plating_plan',
       updated_at = now()
 WHERE sheet_name = '电镀方案'
   AND template_kind = 'QUOTATION'
   AND status = 'ACTIVE'
   AND target_table = 'plating_plan';

-- E.2 「电镀方案」kind=COSTING (V94 已建,target=plating_plan) → 改 target=costing_part_plating
UPDATE basic_data_config
   SET target_table = 'costing_part_plating',
       description = COALESCE(description,'') || ' [V125] target 切到 costing_part_plating',
       updated_at = now()
 WHERE sheet_name = '电镀方案'
   AND template_kind = 'COSTING'
   AND status = 'ACTIVE'
   AND target_table = 'plating_plan';

-- E.3 「电镀费用」当前 V64 只有一份配置 (kind=BOTH 或未指定) → 拆为 QUOTATION + COSTING 双份
DO $$
DECLARE
    v_old_id   UUID;
    v_q_id     UUID := gen_random_uuid();
    v_c_id     UUID := gen_random_uuid();
BEGIN
    -- 找到现存的「电镀费用」配置 (任一 template_kind)
    SELECT id INTO v_old_id FROM basic_data_config
     WHERE sheet_name = '电镀费用' AND status = 'ACTIVE' LIMIT 1;

    IF v_old_id IS NULL THEN
        RAISE NOTICE 'V125-E3: 未找到「电镀费用」配置, 跳过拆分';
        RETURN;
    END IF;

    -- 检查是否已经拆好
    IF EXISTS (SELECT 1 FROM basic_data_config
                WHERE sheet_name='电镀费用' AND template_kind='QUOTATION' AND status='ACTIVE'
                  AND target_table='mat_plating_fee') THEN
        RAISE NOTICE 'V125-E3: 「电镀费用」已拆分, 跳过';
        RETURN;
    END IF;

    -- 把现有配置改为 QUOTATION + target=mat_plating_fee
    UPDATE basic_data_config
       SET template_kind = 'QUOTATION',
           target_table = 'mat_plating_fee',
           description = COALESCE(description,'') || ' [V125] kind=QUOTATION, target=mat_plating_fee',
           updated_at = now()
     WHERE id = v_old_id;

    -- 复制一份属性到 COSTING + target=costing_part_plating_fee
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, parent_config_id, join_columns, sort_order, status,
        target_table, target_discriminator, template_kind, created_at, updated_at
    )
    SELECT v_c_id, sheet_name, sheet_index + 100, header_row_index, data_start_row_index,
           '[V125] kind=COSTING, target=costing_part_plating_fee, 复制自 ' || sheet_name,
           parent_config_id, join_columns, sort_order, status,
           'costing_part_plating_fee', target_discriminator, 'COSTING', now(), now()
      FROM basic_data_config WHERE id = v_old_id;

    -- 复制属性 (但 variable_code 必须唯一 — 加 _costing 后缀)
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, created_at, updated_at
    )
    SELECT gen_random_uuid(), v_c_id, column_letter, column_title,
           variable_code || '__costing', variable_label, data_type, status, sort_order,
           now(), now()
      FROM basic_data_attribute WHERE config_id = v_old_id;

    RAISE NOTICE 'V125-E3: 「电镀费用」已拆分 — QUOTATION configId=%, COSTING configId=%',
                 v_old_id, v_c_id;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- F. 旧 plating_fee / plating_plan 留作只读兼容 — 注释标记 + 新 import 不再写入
--    (V125 不删表; 等所有读路径切换稳定后,后续 V128+ 标 ARCHIVED)
-- ════════════════════════════════════════════════════════════════════════════
COMMENT ON TABLE plating_fee IS
    'v5.0 §5.2.3 电镀费用 — [V125 deprecated] 已拆分到 mat_plating_fee (报价侧) + costing_part_plating_fee (核价侧). 留作只读历史数据, 新 import 不写此表.';
COMMENT ON TABLE plating_plan IS
    'v5.0 §5.1.4 电镀方案 — [V125 deprecated] 已拆分到 mat_plating_plan (报价侧) + costing_part_plating (核价侧). 留作只读历史数据.';

-- ════════════════════════════════════════════════════════════════════════════
-- G. 验证报告
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_pf      INT;
    v_mpf     INT;
    v_cpf     INT;
    v_pp      INT;
    v_mpp     INT;
    v_cpp_new INT;
BEGIN
    SELECT COUNT(*) INTO v_pf      FROM plating_fee WHERE is_current = true;
    SELECT COUNT(*) INTO v_mpf     FROM mat_plating_fee WHERE is_current = true;
    SELECT COUNT(*) INTO v_cpf     FROM costing_part_plating_fee;
    SELECT COUNT(*) INTO v_pp      FROM plating_plan;
    SELECT COUNT(*) INTO v_mpp     FROM mat_plating_plan;
    SELECT COUNT(*) INTO v_cpp_new FROM costing_part_plating;
    RAISE NOTICE 'V125 完成 ─────────────────────────────────';
    RAISE NOTICE '  plating_fee (旧, current)    = % 行', v_pf;
    RAISE NOTICE '  mat_plating_fee (新, 报价)   = % 行', v_mpf;
    RAISE NOTICE '  costing_part_plating_fee (新, 核价) = % 行', v_cpf;
    RAISE NOTICE '  plating_plan (旧)             = % 行', v_pp;
    RAISE NOTICE '  mat_plating_plan (新, 报价)   = % 行', v_mpp;
    RAISE NOTICE '  costing_part_plating (核价)   = % 行', v_cpp_new;
END $$;
