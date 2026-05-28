-- ============================================================
-- 工业球阀 3D 选配示例数据 seed 脚本
-- ============================================================
-- 配套文档：docs/示例-阀门选配全流程.md
-- 用途：dev 环境手工执行，登录 CPQ 后能看到完整阀门示例
-- 注意：不放在 Flyway 迁移目录，避免污染生产
--
-- 执行方式：
--   PGPASSWORD=<pwd> psql -h <host> -U postgres -d cpq_db -f docs/sql-seed/valve-demo.sql
-- 或登录后：
--   \i docs/sql-seed/valve-demo.sql
--
-- 回滚（见末尾 ROLLBACK 部分）
-- ============================================================

BEGIN;

-- ============================================================
-- Step 1: 3D 模型注册（mat_part_model + source files）
-- ============================================================
INSERT INTO mat_part_model (id, part_no, version, label, is_current, glb_url, thumbnail_url,
                             mesh_count, vertices, size_kb, metadata, uploaded_at)
VALUES (
    '11111111-aaaa-bbbb-cccc-000000000001',
    'VALVE-BALL-BASE', 1, '球阀基础模型 v1', TRUE,
    'cpq-3d-glb://VALVE-BALL-BASE/v1/model.glb',
    'cpq-3d-glb://VALVE-BALL-BASE/v1/thumb.png',
    18, 28450, 2680,
    '{"category":"valve","origin":"UG_NX","stp_ap_version":"AP214"}'::jsonb,
    NOW()
)
ON CONFLICT (part_no, version) DO NOTHING;

INSERT INTO mat_part_source_file (part_no, model_id, file_role, file_url, file_size_bytes, uploaded_at, metadata)
VALUES
  ('VALVE-BALL-BASE', '11111111-aaaa-bbbb-cccc-000000000001',
   'UGNX_SOURCE', 'cpq-ugnx-source://VALVE-BALL-BASE/v1/source.prt', 4582400, NOW(),
   '{"ug_version":"NX 12.0"}'::jsonb),
  ('VALVE-BALL-BASE', '11111111-aaaa-bbbb-cccc-000000000001',
   'STP_NEUTRAL', 'cpq-stp-source://VALVE-BALL-BASE/v1/neutral.stp', 3276800, NOW(),
   '{"ap_version":"AP214"}'::jsonb),
  ('VALVE-BALL-BASE', '11111111-aaaa-bbbb-cccc-000000000001',
   'GLB_DRACO', 'cpq-3d-glb://VALVE-BALL-BASE/v1/model.glb', 2744320, NOW(),
   '{"draco_compression":true,"compression_ratio":0.16}'::jsonb),
  ('VALVE-BALL-BASE', '11111111-aaaa-bbbb-cccc-000000000001',
   'THUMBNAIL', 'cpq-3d-glb://VALVE-BALL-BASE/v1/thumb.png', 18432, NOW(), '{}'::jsonb);


-- ============================================================
-- Step 2: 特征库（cpq_feature_group + field + value）
-- ============================================================
INSERT INTO cpq_feature_group (code, name, description, category, status, created_at, updated_at)
VALUES (
    'FG-VALVE-001',
    '工业球阀特征群组',
    '工业级球阀产品族标准特征（DN/PN/材质/连接/驱动/温度），与 ERP 鼎捷 imsba 同步参考',
    '阀门',
    'ACTIVE',
    NOW(), NOW()
)
ON CONFLICT (code) DO NOTHING;

-- 取群组 ID（PostgreSQL 用 CTE）
DO $$
DECLARE
    v_group_id BIGINT;
    v_field_dn BIGINT;
    v_field_pn BIGINT;
    v_field_material BIGINT;
    v_field_connection BIGINT;
    v_field_drive BIGINT;
    v_field_temp BIGINT;
BEGIN
    SELECT id INTO v_group_id FROM cpq_feature_group WHERE code = 'FG-VALVE-001';

    -- 6 个字段
    INSERT INTO cpq_feature_field (group_id, code, name, sort_order, data_type, assign_mode,
                                    is_required, default_value, min_value, max_value, decimal_places,
                                    partno_prefix, partno_suffix, created_at, updated_at)
    VALUES
      (v_group_id, 'DN',         '公称通径',       1, 'NUMBER', 'SELECT', TRUE, '25',  '15',  '100', 0, '-DN', '',  NOW(), NOW()),
      (v_group_id, 'PN',         '压力等级',       2, 'NUMBER', 'SELECT', TRUE, '16',  '16',  '64',  0, '-PN', '',  NOW(), NOW()),
      (v_group_id, 'MATERIAL',   '阀体材质',       3, 'STRING', 'SELECT', TRUE, 'WCB', NULL,  NULL,  NULL, '-', '',  NOW(), NOW()),
      (v_group_id, 'CONNECTION', '连接方式',       4, 'STRING', 'SELECT', TRUE, 'FLANGE', NULL, NULL, NULL, '-', '', NOW(), NOW()),
      (v_group_id, 'DRIVE',      '驱动方式',       5, 'STRING', 'SELECT', FALSE, 'HANDLE', NULL, NULL, NULL, '-', '', NOW(), NOW()),
      (v_group_id, 'TEMP_RANGE', '工作介质温度℃', 6, 'NUMBER', 'MANUAL', TRUE, '80',  '-20', '200', 0, '',  'C', NOW(), NOW())
    ON CONFLICT (group_id, code) DO NOTHING;

    SELECT id INTO v_field_dn         FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'DN';
    SELECT id INTO v_field_pn         FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'PN';
    SELECT id INTO v_field_material   FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'MATERIAL';
    SELECT id INTO v_field_connection FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'CONNECTION';
    SELECT id INTO v_field_drive      FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'DRIVE';
    SELECT id INTO v_field_temp       FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'TEMP_RANGE';

    -- DN 9 取值
    INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at) VALUES
      (v_field_dn, '15',  'DN15',  1, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '20',  'DN20',  2, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '25',  'DN25',  3, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '32',  'DN32',  4, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '40',  'DN40',  5, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '50',  'DN50',  6, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '65',  'DN65',  7, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '80',  'DN80',  8, TRUE, TRUE, NOW(), NOW()),
      (v_field_dn, '100', 'DN100', 9, TRUE, TRUE, NOW(), NOW())
    ON CONFLICT (field_id, code) DO NOTHING;

    -- PN 4 取值
    INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at) VALUES
      (v_field_pn, '16', 'PN16',  1, TRUE, TRUE, NOW(), NOW()),
      (v_field_pn, '25', 'PN25',  2, TRUE, TRUE, NOW(), NOW()),
      (v_field_pn, '40', 'PN40',  3, TRUE, TRUE, NOW(), NOW()),
      (v_field_pn, '64', 'PN64',  4, TRUE, TRUE, NOW(), NOW())
    ON CONFLICT (field_id, code) DO NOTHING;

    -- 材质 4 取值
    INSERT INTO cpq_feature_value (field_id, code, label, description, sort_order, partno_include, is_active, created_at, updated_at) VALUES
      (v_field_material, 'WCB',   '铸钢 WCB',        '碳钢铸件，适用一般工况',         1, TRUE, TRUE, NOW(), NOW()),
      (v_field_material, '304',   '不锈钢 304',      '通用不锈钢，耐腐蚀',             2, TRUE, TRUE, NOW(), NOW()),
      (v_field_material, '316',   '不锈钢 316',      '高耐蚀，适用 >150℃ 工况',        3, TRUE, TRUE, NOW(), NOW()),
      (v_field_material, '黄铜',  '黄铜',           '小通径轻负载场景，禁止 PN64',     4, TRUE, TRUE, NOW(), NOW())
    ON CONFLICT (field_id, code) DO NOTHING;

    -- 连接 3 取值
    INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at) VALUES
      (v_field_connection, 'FLANGE', '法兰 FL',  1, TRUE, TRUE, NOW(), NOW()),
      (v_field_connection, 'THREAD', '螺纹 TH',  2, TRUE, TRUE, NOW(), NOW()),
      (v_field_connection, 'WELD',   '焊接 WD',  3, TRUE, TRUE, NOW(), NOW())
    ON CONFLICT (field_id, code) DO NOTHING;

    -- 驱动 3 取值
    INSERT INTO cpq_feature_value (field_id, code, label, description, sort_order, partno_include, is_active, created_at, updated_at) VALUES
      (v_field_drive, 'HANDLE',    '手动 HA',  '蝶柄手动，最经济',          1, TRUE, TRUE, NOW(), NOW()),
      (v_field_drive, 'PNEUMATIC', '气动 PN',  '气动执行器，禁用焊接连接',  2, TRUE, TRUE, NOW(), NOW()),
      (v_field_drive, 'ELECTRIC',  '电动 EL',  '电动执行器 + 24V 电源',     3, TRUE, TRUE, NOW(), NOW())
    ON CONFLICT (field_id, code) DO NOTHING;

    -- TEMP_RANGE 是 MANUAL 不需要枚举值
END $$;


-- ============================================================
-- Step 3: 选配模板 + 选项 + 选项值 + base 模型关联
-- ============================================================
INSERT INTO product_config_template (
    id, code, name, category, base_part_no,
    base_model_id, base_model_version, base_model_snapshot_at,
    description, show_price, metadata, status, version, created_at, updated_at
) VALUES (
    '22222222-aaaa-bbbb-cccc-000000000001',
    'CFG-TPL-BALL-VALVE',
    '工业球阀选配',
    '阀门',
    'VBV',
    '11111111-aaaa-bbbb-cccc-000000000001',  -- 关联 mat_part_model
    1,
    NOW(),
    '工业级球阀产品族，支持 DN15~DN100 / PN16~PN64 / 4 种材质 / 3 种连接 / 3 种驱动；含 5 类业务约束（小通径不允高压、焊接禁气动、黄铜禁 PN64、高温必须 316、电动配电源线）',
    TRUE,
    '{"theme":"industrial","logo":"none","color_primary":"#1890ff"}'::jsonb,
    'PUBLISHED',
    1,
    NOW(), NOW()
)
ON CONFLICT (code) DO NOTHING;

-- 选项 + 取值（快照复制自特征库；这里直接 INSERT 等价于「📥 从特征库选择」+ price_delta 维护）
DO $$
DECLARE
    v_tpl_id UUID := '22222222-aaaa-bbbb-cccc-000000000001';
    v_group_id BIGINT;
    v_opt_dn UUID;
    v_opt_pn UUID;
    v_opt_material UUID;
    v_opt_connection UUID;
    v_opt_drive UUID;
    v_opt_temp UUID;
    v_field_dn BIGINT;
    v_field_pn BIGINT;
    v_field_material BIGINT;
    v_field_connection BIGINT;
    v_field_drive BIGINT;
    v_field_temp BIGINT;
BEGIN
    SELECT id INTO v_group_id FROM cpq_feature_group WHERE code = 'FG-VALVE-001';
    SELECT id INTO v_field_dn         FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'DN';
    SELECT id INTO v_field_pn         FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'PN';
    SELECT id INTO v_field_material   FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'MATERIAL';
    SELECT id INTO v_field_connection FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'CONNECTION';
    SELECT id INTO v_field_drive      FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'DRIVE';
    SELECT id INTO v_field_temp       FROM cpq_feature_field WHERE group_id = v_group_id AND code = 'TEMP_RANGE';

    -- 6 个选项（快照复制 + 留 source_feature_field_id 追溯）
    INSERT INTO product_config_option (template_id, code, label, option_type, data_type, assign_mode,
                                        is_required, default_value, min_value, max_value,
                                        partno_prefix, partno_suffix, sort_order,
                                        source_feature_field_id, source_feature_snapshot_at,
                                        created_at, updated_at)
    VALUES
      (v_tpl_id, 'DN',         '公称通径',       'EXCLUSIVE', 'NUMBER', 'SELECT', TRUE,  '25',  '15',  '100', '-DN', '',  1, v_field_dn,         NOW(), NOW(), NOW()),
      (v_tpl_id, 'PN',         '压力等级',       'EXCLUSIVE', 'NUMBER', 'SELECT', TRUE,  '16',  '16',  '64',  '-PN', '',  2, v_field_pn,         NOW(), NOW(), NOW()),
      (v_tpl_id, 'MATERIAL',   '阀体材质',       'EXCLUSIVE', 'STRING', 'SELECT', TRUE,  'WCB', NULL,  NULL,  '-',   '',  3, v_field_material,   NOW(), NOW(), NOW()),
      (v_tpl_id, 'CONNECTION', '连接方式',       'EXCLUSIVE', 'STRING', 'SELECT', TRUE,  'FLANGE', NULL, NULL, '-',  '',  4, v_field_connection, NOW(), NOW(), NOW()),
      (v_tpl_id, 'DRIVE',      '驱动方式',       'EXCLUSIVE', 'STRING', 'SELECT', FALSE, 'HANDLE', NULL, NULL, '-',  '',  5, v_field_drive,      NOW(), NOW(), NOW()),
      (v_tpl_id, 'TEMP_RANGE', '工作介质温度℃', 'NUMERIC',   'NUMBER', 'MANUAL', TRUE,  '80',  '-20', '200', '',    'C', 6, v_field_temp,       NOW(), NOW(), NOW())
    ON CONFLICT (template_id, code) DO NOTHING;

    SELECT id INTO v_opt_dn         FROM product_config_option WHERE template_id = v_tpl_id AND code = 'DN';
    SELECT id INTO v_opt_pn         FROM product_config_option WHERE template_id = v_tpl_id AND code = 'PN';
    SELECT id INTO v_opt_material   FROM product_config_option WHERE template_id = v_tpl_id AND code = 'MATERIAL';
    SELECT id INTO v_opt_connection FROM product_config_option WHERE template_id = v_tpl_id AND code = 'CONNECTION';
    SELECT id INTO v_opt_drive      FROM product_config_option WHERE template_id = v_tpl_id AND code = 'DRIVE';

    -- DN 选项值（9 个）+ price_delta
    INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                                              partno_include, is_active,
                                              source_feature_value_id, source_feature_snapshot_at,
                                              created_at, updated_at)
    SELECT v_opt_dn, fv.code, fv.label,
           CASE fv.code
               WHEN '15'  THEN -300
               WHEN '20'  THEN -200
               WHEN '25'  THEN 0
               WHEN '32'  THEN 150
               WHEN '40'  THEN 320
               WHEN '50'  THEN 580
               WHEN '65'  THEN 980
               WHEN '80'  THEN 1450
               WHEN '100' THEN 2200
           END,
           fv.sort_order, fv.partno_include, fv.is_active,
           fv.id, NOW(), NOW(), NOW()
    FROM cpq_feature_value fv WHERE fv.field_id = v_field_dn
    ON CONFLICT (option_id, code) DO NOTHING;

    -- PN 选项值（4 个）
    INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                                              partno_include, is_active,
                                              source_feature_value_id, source_feature_snapshot_at,
                                              created_at, updated_at)
    SELECT v_opt_pn, fv.code, fv.label,
           CASE fv.code WHEN '16' THEN 0 WHEN '25' THEN 180 WHEN '40' THEN 420 WHEN '64' THEN 980 END,
           fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
    FROM cpq_feature_value fv WHERE fv.field_id = v_field_pn
    ON CONFLICT (option_id, code) DO NOTHING;

    -- 材质（4 个）
    INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                                              partno_include, is_active,
                                              source_feature_value_id, source_feature_snapshot_at,
                                              created_at, updated_at)
    SELECT v_opt_material, fv.code, fv.label,
           CASE fv.code WHEN 'WCB' THEN 0 WHEN '304' THEN 250 WHEN '316' THEN 580 WHEN '黄铜' THEN 120 END,
           fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
    FROM cpq_feature_value fv WHERE fv.field_id = v_field_material
    ON CONFLICT (option_id, code) DO NOTHING;

    -- 连接（3 个）
    INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                                              partno_include, is_active,
                                              source_feature_value_id, source_feature_snapshot_at,
                                              created_at, updated_at)
    SELECT v_opt_connection, fv.code, fv.label,
           CASE fv.code WHEN 'FLANGE' THEN 0 WHEN 'THREAD' THEN -80 WHEN 'WELD' THEN 150 END,
           fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
    FROM cpq_feature_value fv WHERE fv.field_id = v_field_connection
    ON CONFLICT (option_id, code) DO NOTHING;

    -- 驱动（3 个）
    INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                                              partno_include, is_active,
                                              source_feature_value_id, source_feature_snapshot_at,
                                              created_at, updated_at)
    SELECT v_opt_drive, fv.code, fv.label,
           CASE fv.code WHEN 'HANDLE' THEN 0 WHEN 'PNEUMATIC' THEN 2800 WHEN 'ELECTRIC' THEN 3600 END,
           fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
    FROM cpq_feature_value fv WHERE fv.field_id = v_field_drive
    ON CONFLICT (option_id, code) DO NOTHING;

    -- TEMP_RANGE 是 MANUAL，不建枚举值
END $$;


-- ============================================================
-- Step 4: 5 类约束规则（业务展示用；evaluate 端点暂不消费 — 等约束求值算法切片）
-- ============================================================
INSERT INTO product_config_constraint (template_id, constraint_type, trigger_expr, affected_expr,
                                        message, severity, sort_order, is_active, created_at)
VALUES
  ('22222222-aaaa-bbbb-cccc-000000000001', 'EXCLUDES',
   '{"option":"DN","value_in":["15","20"]}'::jsonb,
   '{"option":"PN","value_in":["40","64"]}'::jsonb,
   '小通径 DN15/20 不允许 PN40/64（强度不够）',
   'ERROR', 1, TRUE, NOW()),
  ('22222222-aaaa-bbbb-cccc-000000000001', 'EXCLUDES',
   '{"option":"CONNECTION","value":"WELD"}'::jsonb,
   '{"option":"DRIVE","value":"PNEUMATIC"}'::jsonb,
   '焊接连接禁止气动驱动（结构冲突，焊后无法装气动执行器）',
   'ERROR', 2, TRUE, NOW()),
  ('22222222-aaaa-bbbb-cccc-000000000001', 'EXCLUDES',
   '{"option":"MATERIAL","value":"黄铜"}'::jsonb,
   '{"option":"PN","value":"64"}'::jsonb,
   '黄铜材质强度不足，禁止 PN64',
   'ERROR', 3, TRUE, NOW()),
  ('22222222-aaaa-bbbb-cccc-000000000001', 'NUMERIC_RANGE',
   '{"option":"TEMP_RANGE","gt":150}'::jsonb,
   '{"option":"MATERIAL","value_in":["黄铜","304"]}'::jsonb,
   '介质温度 > 150℃ 时必须使用 316 不锈钢',
   'ERROR', 4, TRUE, NOW()),
  ('22222222-aaaa-bbbb-cccc-000000000001', 'IMPLIES',
   '{"option":"DRIVE","value":"ELECTRIC"}'::jsonb,
   '{"add_accessory":"24V_POWER_CABLE"}'::jsonb,
   '电动驱动自动加配 24V 电源线',
   'INFO', 5, TRUE, NOW())
ON CONFLICT DO NOTHING;


-- ============================================================
-- Step 5: 3 个示例实例（不同状态：DRAFT / SUBMITTED / LINKED）
-- ============================================================

-- 推进 instance_code 序列
SELECT setval('seq_config_instance_seq', GREATEST(COALESCE((SELECT last_value FROM seq_config_instance_seq), 0), 100), FALSE);

INSERT INTO product_config_instance (
    id, instance_code, template_id, template_version,
    name, customer_id, user_id,
    selected_values, config_fingerprint, computed_total_price, base_price,
    status, expires_at, created_at, updated_at
) VALUES
-- 示例 1：销售完整 LINKED 案例（DN50 + PN25 + 304 + FLANGE + ELECTRIC + 120℃）
(
    '33333333-aaaa-bbbb-cccc-000000000001',
    'CI-' || TO_CHAR(NOW(), 'YYYYMM') || '-V001',
    '22222222-aaaa-bbbb-cccc-000000000001', 1,
    '罗克韦尔Q3球阀订单',
    NULL,
    NULL,
    '{"DN":"50","PN":"25","MATERIAL":"304","CONNECTION":"FLANGE","DRIVE":"ELECTRIC","TEMP_RANGE":"120"}'::jsonb,
    'demo-fingerprint-001',
    6410.00, 1800.00,
    'LINKED',
    NOW() + INTERVAL '30 days', NOW(), NOW()
),
-- 示例 2：销售 SUBMITTED 草稿（待绑定报价单）
(
    '33333333-aaaa-bbbb-cccc-000000000002',
    'CI-' || TO_CHAR(NOW(), 'YYYYMM') || '-V002',
    '22222222-aaaa-bbbb-cccc-000000000001', 1,
    '高端定制 - 待确认',
    NULL, NULL,
    '{"DN":"80","PN":"40","MATERIAL":"316","CONNECTION":"WELD","DRIVE":"PNEUMATIC","TEMP_RANGE":"180"}'::jsonb,
    'demo-fingerprint-002',
    7750.00, 1800.00,
    'SUBMITTED',
    NOW() + INTERVAL '30 days', NOW(), NOW()
),
-- 示例 3：客户公网自助提交（关联 customer_lead，待审核）
(
    '33333333-aaaa-bbbb-cccc-000000000003',
    'CI-' || TO_CHAR(NOW(), 'YYYYMM') || '-V003',
    '22222222-aaaa-bbbb-cccc-000000000001', 1,
    '客户自助_张总_球阀',
    NULL,
    NULL,
    '{"DN":"65","PN":"40","MATERIAL":"316","CONNECTION":"WELD","DRIVE":"ELECTRIC","TEMP_RANGE":"180"}'::jsonb,
    'demo-fingerprint-003',
    7530.00, 1800.00,
    'SUBMITTED',
    NOW() + INTERVAL '30 days', NOW(), NOW()
)
ON CONFLICT (instance_code) DO NOTHING;

-- 示例 1 标记为 LINKED，填 generated_* 字段
UPDATE product_config_instance SET
    generated_part_no = 'VBV-DN50-PN25-304-FL-EL',
    generated_quotation_id = '44444444-aaaa-bbbb-cccc-000000000001',
    generated_line_item_id = '55555555-aaaa-bbbb-cccc-000000000001',
    linked_quotation_id    = '44444444-aaaa-bbbb-cccc-000000000001',
    linked_at = NOW()
WHERE id = '33333333-aaaa-bbbb-cccc-000000000001';


-- ============================================================
-- Step 6: 1 个客户线索 + 关联实例 3
-- ============================================================
SELECT setval('seq_customer_lead_seq', GREATEST(COALESCE((SELECT last_value FROM seq_customer_lead_seq), 0), 100), FALSE);

INSERT INTO customer_lead (
    id, lead_code, source_type, share_token,
    contact_name, contact_phone, contact_email, company_name,
    note, status, created_at, updated_at
) VALUES (
    '66666666-aaaa-bbbb-cccc-000000000001',
    'LEAD-' || TO_CHAR(NOW(), 'YYYYMM') || '-V001',
    'CUSTOMER_SELF',
    'shr-VALVE-DEMO-001',
    '张总',
    '138 8888 8888',
    'zhang@customer.cn',
    '罗克韦尔自动化',
    '需要 DN65 球阀，180℃ 工况，预计 30 台，请联系报价',
    'PENDING_REVIEW',
    NOW(), NOW()
)
ON CONFLICT (lead_code) DO NOTHING;

-- 把示例 3 的实例关联到此 lead
UPDATE product_config_instance SET
    customer_lead_id = '66666666-aaaa-bbbb-cccc-000000000001',
    share_token = 'shr-VALVE-DEMO-001'
WHERE id = '33333333-aaaa-bbbb-cccc-000000000003';


-- ============================================================
-- Step 7: 1 个分享链接（与实例 3 + lead 关联）
-- ============================================================
INSERT INTO product_config_share (
    id, instance_id, share_type, share_token,
    shared_to_email, expires_at, access_count, last_accessed_at,
    can_modify, status, created_at
) VALUES (
    '77777777-aaaa-bbbb-cccc-000000000001',
    '33333333-aaaa-bbbb-cccc-000000000003',
    'CUSTOMER_SELF',
    'shr-VALVE-DEMO-001',
    'zhang@customer.cn',
    NOW() + INTERVAL '7 days',
    3,
    NOW() - INTERVAL '2 hours',
    TRUE,
    'ACTIVE',
    NOW() - INTERVAL '2 days'
)
ON CONFLICT (share_token) DO NOTHING;


-- ============================================================
-- 验证查询
-- ============================================================
SELECT '✅ 阀门 demo 数据已入库' AS status,
       (SELECT COUNT(*) FROM mat_part_model WHERE part_no = 'VALVE-BALL-BASE') AS models,
       (SELECT COUNT(*) FROM cpq_feature_group WHERE code = 'FG-VALVE-001') AS groups,
       (SELECT COUNT(*) FROM cpq_feature_field cff
        JOIN cpq_feature_group cfg ON cfg.id = cff.group_id
        WHERE cfg.code = 'FG-VALVE-001') AS fields,
       (SELECT COUNT(*) FROM cpq_feature_value cfv
        JOIN cpq_feature_field cff ON cff.id = cfv.field_id
        JOIN cpq_feature_group cfg ON cfg.id = cff.group_id
        WHERE cfg.code = 'FG-VALVE-001') AS values,
       (SELECT COUNT(*) FROM product_config_template WHERE code = 'CFG-TPL-BALL-VALVE') AS templates,
       (SELECT COUNT(*) FROM product_config_option WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001') AS options,
       (SELECT COUNT(*) FROM product_config_option_value pcov
        JOIN product_config_option pco ON pco.id = pcov.option_id
        WHERE pco.template_id = '22222222-aaaa-bbbb-cccc-000000000001') AS option_values,
       (SELECT COUNT(*) FROM product_config_constraint WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001') AS constraints,
       (SELECT COUNT(*) FROM product_config_instance WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001') AS instances,
       (SELECT COUNT(*) FROM customer_lead WHERE lead_code LIKE 'LEAD-%-V%') AS leads;

COMMIT;


-- ============================================================
-- 回滚（手工执行，谨慎！）
-- ============================================================
-- BEGIN;
-- DELETE FROM product_config_share WHERE share_token = 'shr-VALVE-DEMO-001';
-- DELETE FROM product_config_instance_history WHERE instance_id IN (
--     SELECT id FROM product_config_instance WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001'
-- );
-- DELETE FROM product_config_instance WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001';
-- DELETE FROM customer_lead WHERE id = '66666666-aaaa-bbbb-cccc-000000000001';
-- DELETE FROM product_config_constraint WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001';
-- DELETE FROM product_config_option_value WHERE option_id IN (
--     SELECT id FROM product_config_option WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001'
-- );
-- DELETE FROM product_config_option WHERE template_id = '22222222-aaaa-bbbb-cccc-000000000001';
-- DELETE FROM product_config_template WHERE id = '22222222-aaaa-bbbb-cccc-000000000001';
-- DELETE FROM cpq_feature_value cfv USING cpq_feature_field cff, cpq_feature_group cfg
--     WHERE cfv.field_id = cff.id AND cff.group_id = cfg.id AND cfg.code = 'FG-VALVE-001';
-- DELETE FROM cpq_feature_field cff USING cpq_feature_group cfg
--     WHERE cff.group_id = cfg.id AND cfg.code = 'FG-VALVE-001';
-- DELETE FROM cpq_feature_group WHERE code = 'FG-VALVE-001';
-- DELETE FROM mat_part_source_file WHERE part_no = 'VALVE-BALL-BASE';
-- DELETE FROM mat_part_model WHERE part_no = 'VALVE-BALL-BASE';
-- COMMIT;
