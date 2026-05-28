package com.cpq.configurator.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import io.quarkus.runtime.LaunchMode;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 阀门 demo 数据 seed 工具（仅 dev 环境可用）。
 * 配套：docs/sql-seed/valve-demo.sql · docs/示例-阀门选配全流程.md
 */
@Path("/api/cpq/admin/seed/valve-demo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class ValveDemoSeedResource {

    @Inject
    EntityManager em;

    // 固定 UUID 便于幂等
    static final UUID MODEL_ID = UUID.fromString("11111111-aaaa-bbbb-cccc-000000000001");
    static final UUID TPL_ID   = UUID.fromString("22222222-aaaa-bbbb-cccc-000000000001");
    static final UUID INST1_ID = UUID.fromString("33333333-aaaa-bbbb-cccc-000000000001");
    static final UUID INST2_ID = UUID.fromString("33333333-aaaa-bbbb-cccc-000000000002");
    static final UUID INST3_ID = UUID.fromString("33333333-aaaa-bbbb-cccc-000000000003");
    static final UUID FAKE_QT  = UUID.fromString("44444444-aaaa-bbbb-cccc-000000000001");
    static final UUID FAKE_LI  = UUID.fromString("55555555-aaaa-bbbb-cccc-000000000001");
    static final UUID LEAD_ID  = UUID.fromString("66666666-aaaa-bbbb-cccc-000000000001");
    static final UUID SHARE_ID = UUID.fromString("77777777-aaaa-bbbb-cccc-000000000001");

    @POST
    @Transactional
    public ApiResponse<Map<String, Object>> seed() {
        assertDevMode();

        // ===== Step 1: mat_part_model + source_files =====
        em.createNativeQuery("""
            INSERT INTO mat_part_model (id, part_no, version, label, is_current, glb_url, thumbnail_url,
                                         mesh_count, vertices, size_kb, metadata, uploaded_at)
            VALUES (?1, 'VALVE-BALL-BASE', 1, '球阀基础模型 v1', TRUE,
                    'cpq-3d-glb://VALVE-BALL-BASE/v1/model.glb',
                    'cpq-3d-glb://VALVE-BALL-BASE/v1/thumb.png',
                    18, 28450, 2680,
                    CAST('{"category":"valve","origin":"UG_NX"}' AS jsonb),
                    NOW())
            ON CONFLICT (part_no, version) DO NOTHING
            """).setParameter(1, MODEL_ID).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO mat_part_source_file (part_no, model_id, file_role, file_url, file_size_bytes, uploaded_at, metadata) VALUES
              ('VALVE-BALL-BASE', ?1, 'UGNX_SOURCE',
               'cpq-ugnx-source://VALVE-BALL-BASE/v1/source.prt', 4582400, NOW(), CAST('{"ug_version":"NX 12.0"}' AS jsonb)),
              ('VALVE-BALL-BASE', ?1, 'STP_NEUTRAL',
               'cpq-stp-source://VALVE-BALL-BASE/v1/neutral.stp', 3276800, NOW(), CAST('{"ap_version":"AP214"}' AS jsonb)),
              ('VALVE-BALL-BASE', ?1, 'GLB_DRACO',
               'cpq-3d-glb://VALVE-BALL-BASE/v1/model.glb', 2744320, NOW(), CAST('{"draco_compression":true}' AS jsonb)),
              ('VALVE-BALL-BASE', ?1, 'THUMBNAIL',
               'cpq-3d-glb://VALVE-BALL-BASE/v1/thumb.png', 18432, NOW(), CAST('{}' AS jsonb))
            """).setParameter(1, MODEL_ID).executeUpdate();

        // ===== Step 2a: 特征群组 =====
        em.createNativeQuery("""
            INSERT INTO cpq_feature_group (code, name, description, category, status, created_at, updated_at)
            VALUES ('FG-VALVE-001', '工业球阀特征群组',
                    '工业级球阀产品族标准特征（DN/PN/材质/连接/驱动/温度）',
                    '阀门', 'ACTIVE', NOW(), NOW())
            ON CONFLICT (code) DO NOTHING
            """).executeUpdate();
        Long groupId = ((Number) em.createNativeQuery(
            "SELECT id FROM cpq_feature_group WHERE code = 'FG-VALVE-001'"
        ).getSingleResult()).longValue();

        // ===== Step 2b: 6 字段 =====
        em.createNativeQuery("""
            INSERT INTO cpq_feature_field (group_id, code, name, sort_order, data_type, assign_mode,
                                            is_required, default_value, min_value, max_value, decimal_places,
                                            partno_prefix, partno_suffix, created_at, updated_at) VALUES
              (?1, 'DN',         '公称通径',       1, 'NUMBER', 'SELECT', TRUE, '25',  '15',  '100', 0, '-DN', '',  NOW(), NOW()),
              (?1, 'PN',         '压力等级',       2, 'NUMBER', 'SELECT', TRUE, '16',  '16',  '64',  0, '-PN', '',  NOW(), NOW()),
              (?1, 'MATERIAL',   '阀体材质',       3, 'STRING', 'SELECT', TRUE, 'WCB', NULL,  NULL,  NULL, '-', '',  NOW(), NOW()),
              (?1, 'CONNECTION', '连接方式',       4, 'STRING', 'SELECT', TRUE, 'FLANGE', NULL, NULL, NULL, '-', '', NOW(), NOW()),
              (?1, 'DRIVE',      '驱动方式',       5, 'STRING', 'SELECT', FALSE, 'HANDLE', NULL, NULL, NULL, '-', '', NOW(), NOW()),
              (?1, 'TEMP_RANGE', '工作介质温度℃', 6, 'NUMBER', 'MANUAL', TRUE, '80',  '-20', '200', 0, '',  'C', NOW(), NOW())
            ON CONFLICT (group_id, code) DO NOTHING
            """).setParameter(1, groupId).executeUpdate();

        Long fDn   = fieldId(groupId, "DN");
        Long fPn   = fieldId(groupId, "PN");
        Long fMat  = fieldId(groupId, "MATERIAL");
        Long fConn = fieldId(groupId, "CONNECTION");
        Long fDrv  = fieldId(groupId, "DRIVE");
        Long fTemp = fieldId(groupId, "TEMP_RANGE");

        // ===== Step 2c: 取值 =====
        // DN
        for (Object[] v : new Object[][]{
            {"15","DN15",1},{"20","DN20",2},{"25","DN25",3},{"32","DN32",4},
            {"40","DN40",5},{"50","DN50",6},{"65","DN65",7},{"80","DN80",8},{"100","DN100",9}}) {
            em.createNativeQuery("""
                INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, TRUE, TRUE, NOW(), NOW())
                ON CONFLICT (field_id, code) DO NOTHING
                """).setParameter(1, fDn).setParameter(2, v[0]).setParameter(3, v[1]).setParameter(4, v[2]).executeUpdate();
        }
        // PN
        for (Object[] v : new Object[][]{ {"16","PN16",1},{"25","PN25",2},{"40","PN40",3},{"64","PN64",4} }) {
            em.createNativeQuery("""
                INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, TRUE, TRUE, NOW(), NOW())
                ON CONFLICT (field_id, code) DO NOTHING
                """).setParameter(1, fPn).setParameter(2, v[0]).setParameter(3, v[1]).setParameter(4, v[2]).executeUpdate();
        }
        // 材质
        for (Object[] v : new Object[][]{
            {"WCB","铸钢 WCB","碳钢铸件，适用一般工况",1},
            {"304","不锈钢 304","通用不锈钢，耐腐蚀",2},
            {"316","不锈钢 316","高耐蚀，适用 >150℃ 工况",3},
            {"黄铜","黄铜","小通径轻负载场景，禁止 PN64",4}}) {
            em.createNativeQuery("""
                INSERT INTO cpq_feature_value (field_id, code, label, description, sort_order, partno_include, is_active, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, ?5, TRUE, TRUE, NOW(), NOW())
                ON CONFLICT (field_id, code) DO NOTHING
                """).setParameter(1, fMat).setParameter(2, v[0]).setParameter(3, v[1])
                    .setParameter(4, v[2]).setParameter(5, v[3]).executeUpdate();
        }
        // 连接
        for (Object[] v : new Object[][]{
            {"FLANGE","法兰 FL",1},{"THREAD","螺纹 TH",2},{"WELD","焊接 WD",3}}) {
            em.createNativeQuery("""
                INSERT INTO cpq_feature_value (field_id, code, label, sort_order, partno_include, is_active, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, TRUE, TRUE, NOW(), NOW())
                ON CONFLICT (field_id, code) DO NOTHING
                """).setParameter(1, fConn).setParameter(2, v[0]).setParameter(3, v[1]).setParameter(4, v[2]).executeUpdate();
        }
        // 驱动
        for (Object[] v : new Object[][]{
            {"HANDLE","手动 HA","蝶柄手动，最经济",1},
            {"PNEUMATIC","气动 PN","气动执行器，禁用焊接连接",2},
            {"ELECTRIC","电动 EL","电动执行器 + 24V 电源",3}}) {
            em.createNativeQuery("""
                INSERT INTO cpq_feature_value (field_id, code, label, description, sort_order, partno_include, is_active, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, ?5, TRUE, TRUE, NOW(), NOW())
                ON CONFLICT (field_id, code) DO NOTHING
                """).setParameter(1, fDrv).setParameter(2, v[0]).setParameter(3, v[1])
                    .setParameter(4, v[2]).setParameter(5, v[3]).executeUpdate();
        }

        // ===== Step 3a: 模板 =====
        em.createNativeQuery("""
            INSERT INTO product_config_template (id, code, name, category, base_part_no,
                base_model_id, base_model_version, base_model_snapshot_at,
                description, show_price, metadata, status, version, created_at, updated_at)
            VALUES (?1, 'CFG-TPL-BALL-VALVE', '工业球阀选配', '阀门', 'VBV',
                    ?2, 1, NOW(),
                    '工业级球阀产品族；含 5 类业务约束', TRUE,
                    CAST('{"theme":"industrial"}' AS jsonb),
                    'PUBLISHED', 1, NOW(), NOW())
            ON CONFLICT (code) DO NOTHING
            """).setParameter(1, TPL_ID).setParameter(2, MODEL_ID).executeUpdate();

        // ===== Step 3b: 6 选项 =====
        em.createNativeQuery("""
            INSERT INTO product_config_option (template_id, code, label, option_type, data_type, assign_mode,
                is_required, default_value, min_value, max_value, partno_prefix, partno_suffix, sort_order,
                source_feature_field_id, source_feature_snapshot_at, created_at, updated_at) VALUES
              (?1, 'DN',         '公称通径',       'EXCLUSIVE', 'NUMBER', 'SELECT', TRUE,  '25',  '15',  '100', '-DN', '',  1, ?2, NOW(), NOW(), NOW()),
              (?1, 'PN',         '压力等级',       'EXCLUSIVE', 'NUMBER', 'SELECT', TRUE,  '16',  '16',  '64',  '-PN', '',  2, ?3, NOW(), NOW(), NOW()),
              (?1, 'MATERIAL',   '阀体材质',       'EXCLUSIVE', 'STRING', 'SELECT', TRUE,  'WCB', NULL,  NULL,  '-',   '',  3, ?4, NOW(), NOW(), NOW()),
              (?1, 'CONNECTION', '连接方式',       'EXCLUSIVE', 'STRING', 'SELECT', TRUE,  'FLANGE', NULL, NULL, '-',  '',  4, ?5, NOW(), NOW(), NOW()),
              (?1, 'DRIVE',      '驱动方式',       'EXCLUSIVE', 'STRING', 'SELECT', FALSE, 'HANDLE', NULL, NULL, '-',  '',  5, ?6, NOW(), NOW(), NOW()),
              (?1, 'TEMP_RANGE', '工作介质温度℃', 'NUMERIC',   'NUMBER', 'MANUAL', TRUE,  '80',  '-20', '200', '',    'C', 6, ?7, NOW(), NOW(), NOW())
            ON CONFLICT (template_id, code) DO NOTHING
            """).setParameter(1, TPL_ID)
              .setParameter(2, fDn).setParameter(3, fPn).setParameter(4, fMat)
              .setParameter(5, fConn).setParameter(6, fDrv).setParameter(7, fTemp)
              .executeUpdate();

        UUID oDn   = optionUuid(TPL_ID, "DN");
        UUID oPn   = optionUuid(TPL_ID, "PN");
        UUID oMat  = optionUuid(TPL_ID, "MATERIAL");
        UUID oConn = optionUuid(TPL_ID, "CONNECTION");
        UUID oDrv  = optionUuid(TPL_ID, "DRIVE");

        // ===== Step 3c: option_values 含 price_delta =====
        em.createNativeQuery("""
            INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                partno_include, is_active, source_feature_value_id, source_feature_snapshot_at, created_at, updated_at)
            SELECT ?1, fv.code, fv.label,
                   CASE fv.code WHEN '15' THEN -300 WHEN '20' THEN -200 WHEN '25' THEN 0
                                WHEN '32' THEN 150 WHEN '40' THEN 320 WHEN '50' THEN 580
                                WHEN '65' THEN 980 WHEN '80' THEN 1450 WHEN '100' THEN 2200 END,
                   fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
            FROM cpq_feature_value fv WHERE fv.field_id = ?2
            ON CONFLICT (option_id, code) DO NOTHING
            """).setParameter(1, oDn).setParameter(2, fDn).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                partno_include, is_active, source_feature_value_id, source_feature_snapshot_at, created_at, updated_at)
            SELECT ?1, fv.code, fv.label,
                   CASE fv.code WHEN '16' THEN 0 WHEN '25' THEN 180 WHEN '40' THEN 420 WHEN '64' THEN 980 END,
                   fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
            FROM cpq_feature_value fv WHERE fv.field_id = ?2
            ON CONFLICT (option_id, code) DO NOTHING
            """).setParameter(1, oPn).setParameter(2, fPn).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                partno_include, is_active, source_feature_value_id, source_feature_snapshot_at, created_at, updated_at)
            SELECT ?1, fv.code, fv.label,
                   CASE fv.code WHEN 'WCB' THEN 0 WHEN '304' THEN 250 WHEN '316' THEN 580 WHEN '黄铜' THEN 120 END,
                   fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
            FROM cpq_feature_value fv WHERE fv.field_id = ?2
            ON CONFLICT (option_id, code) DO NOTHING
            """).setParameter(1, oMat).setParameter(2, fMat).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                partno_include, is_active, source_feature_value_id, source_feature_snapshot_at, created_at, updated_at)
            SELECT ?1, fv.code, fv.label,
                   CASE fv.code WHEN 'FLANGE' THEN 0 WHEN 'THREAD' THEN -80 WHEN 'WELD' THEN 150 END,
                   fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
            FROM cpq_feature_value fv WHERE fv.field_id = ?2
            ON CONFLICT (option_id, code) DO NOTHING
            """).setParameter(1, oConn).setParameter(2, fConn).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_option_value (option_id, code, label, price_delta, sort_order,
                partno_include, is_active, source_feature_value_id, source_feature_snapshot_at, created_at, updated_at)
            SELECT ?1, fv.code, fv.label,
                   CASE fv.code WHEN 'HANDLE' THEN 0 WHEN 'PNEUMATIC' THEN 2800 WHEN 'ELECTRIC' THEN 3600 END,
                   fv.sort_order, fv.partno_include, fv.is_active, fv.id, NOW(), NOW(), NOW()
            FROM cpq_feature_value fv WHERE fv.field_id = ?2
            ON CONFLICT (option_id, code) DO NOTHING
            """).setParameter(1, oDrv).setParameter(2, fDrv).executeUpdate();

        // ===== Step 4: 5 约束 =====
        // 删旧的约束（如果重跑）以保证幂等
        em.createNativeQuery("DELETE FROM product_config_constraint WHERE template_id = ?1")
            .setParameter(1, TPL_ID).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_constraint (template_id, constraint_type, trigger_expr, affected_expr,
                                                    message, severity, sort_order, is_active, created_at)
            VALUES
              (?1, 'EXCLUDES',
                 CAST('{"option":"DN","value_in":["15","20"]}' AS jsonb),
                 CAST('{"option":"PN","value_in":["40","64"]}' AS jsonb),
                 '小通径 DN15/20 不允许 PN40/64（强度不够）', 'ERROR', 1, TRUE, NOW()),
              (?1, 'EXCLUDES',
                 CAST('{"option":"CONNECTION","value":"WELD"}' AS jsonb),
                 CAST('{"option":"DRIVE","value":"PNEUMATIC"}' AS jsonb),
                 '焊接连接禁止气动驱动（焊后无法装气动执行器）', 'ERROR', 2, TRUE, NOW()),
              (?1, 'EXCLUDES',
                 CAST('{"option":"MATERIAL","value":"黄铜"}' AS jsonb),
                 CAST('{"option":"PN","value":"64"}' AS jsonb),
                 '黄铜材质强度不足，禁止 PN64', 'ERROR', 3, TRUE, NOW()),
              (?1, 'NUMERIC_RANGE',
                 CAST('{"option":"TEMP_RANGE","gt":150}' AS jsonb),
                 CAST('{"option":"MATERIAL","value_in":["黄铜","304"]}' AS jsonb),
                 '介质温度 > 150℃ 时必须使用 316 不锈钢', 'ERROR', 4, TRUE, NOW()),
              (?1, 'IMPLIES',
                 CAST('{"option":"DRIVE","value":"ELECTRIC"}' AS jsonb),
                 CAST('{"add_accessory":"24V_POWER_CABLE"}' AS jsonb),
                 '电动驱动自动加配 24V 电源线', 'INFO', 5, TRUE, NOW())
            """).setParameter(1, TPL_ID).executeUpdate();

        // ===== Step 5: 3 实例 =====
        String mm = java.time.format.DateTimeFormatter.ofPattern("yyyyMM")
            .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        em.createNativeQuery("""
            INSERT INTO product_config_instance (id, instance_code, template_id, template_version,
                name, customer_id, user_id, selected_values, config_fingerprint,
                computed_total_price, base_price, status, expires_at, created_at, updated_at) VALUES
              (?1, 'CI-' || ?3 || '-V001', ?2, 1, '罗克韦尔Q3球阀订单', NULL, NULL,
               CAST('{"DN":"50","PN":"25","MATERIAL":"304","CONNECTION":"FLANGE","DRIVE":"ELECTRIC","TEMP_RANGE":"120"}' AS jsonb),
               'demo-fingerprint-001', 6410.00, 1800.00, 'LINKED',
               NOW() + INTERVAL '30 days', NOW(), NOW())
            ON CONFLICT (instance_code) DO NOTHING
            """).setParameter(1, INST1_ID).setParameter(2, TPL_ID).setParameter(3, mm).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_instance (id, instance_code, template_id, template_version,
                name, customer_id, user_id, selected_values, config_fingerprint,
                computed_total_price, base_price, status, expires_at, created_at, updated_at) VALUES
              (?1, 'CI-' || ?3 || '-V002', ?2, 1, '高端定制 - 待确认', NULL, NULL,
               CAST('{"DN":"80","PN":"40","MATERIAL":"316","CONNECTION":"WELD","DRIVE":"PNEUMATIC","TEMP_RANGE":"180"}' AS jsonb),
               'demo-fingerprint-002', 7750.00, 1800.00, 'SUBMITTED',
               NOW() + INTERVAL '30 days', NOW(), NOW())
            ON CONFLICT (instance_code) DO NOTHING
            """).setParameter(1, INST2_ID).setParameter(2, TPL_ID).setParameter(3, mm).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO product_config_instance (id, instance_code, template_id, template_version,
                name, customer_id, user_id, selected_values, config_fingerprint,
                computed_total_price, base_price, status, expires_at, created_at, updated_at) VALUES
              (?1, 'CI-' || ?3 || '-V003', ?2, 1, '客户自助_张总_球阀', NULL, NULL,
               CAST('{"DN":"65","PN":"40","MATERIAL":"316","CONNECTION":"WELD","DRIVE":"ELECTRIC","TEMP_RANGE":"180"}' AS jsonb),
               'demo-fingerprint-003', 7530.00, 1800.00, 'SUBMITTED',
               NOW() + INTERVAL '30 days', NOW(), NOW())
            ON CONFLICT (instance_code) DO NOTHING
            """).setParameter(1, INST3_ID).setParameter(2, TPL_ID).setParameter(3, mm).executeUpdate();

        em.createNativeQuery("""
            UPDATE product_config_instance SET
                generated_part_no = 'VBV-DN50-PN25-304-FL-EL',
                generated_quotation_id = ?2,
                generated_line_item_id = ?3,
                linked_quotation_id    = ?2,
                linked_at = NOW()
            WHERE id = ?1
            """).setParameter(1, INST1_ID).setParameter(2, FAKE_QT).setParameter(3, FAKE_LI).executeUpdate();

        // ===== Step 6: customer_lead =====
        em.createNativeQuery("""
            INSERT INTO customer_lead (id, lead_code, source_type, share_token,
                contact_name, contact_phone, contact_email, company_name, note, status, created_at, updated_at)
            VALUES (?1, 'LEAD-' || ?2 || '-V001', 'CUSTOMER_SELF', 'shr-VALVE-DEMO-001',
                    '张总', '138 8888 8888', 'zhang@customer.cn', '罗克韦尔自动化',
                    '需要 DN65 球阀，180℃ 工况，预计 30 台', 'PENDING_REVIEW', NOW(), NOW())
            ON CONFLICT (lead_code) DO NOTHING
            """).setParameter(1, LEAD_ID).setParameter(2, mm).executeUpdate();
        em.createNativeQuery("""
            UPDATE product_config_instance SET
                customer_lead_id = ?2,
                share_token = 'shr-VALVE-DEMO-001'
            WHERE id = ?1
            """).setParameter(1, INST3_ID).setParameter(2, LEAD_ID).executeUpdate();

        // ===== Step 7: 分享链接 =====
        em.createNativeQuery("""
            INSERT INTO product_config_share (id, instance_id, share_type, share_token,
                shared_to_email, expires_at, access_count, last_accessed_at,
                can_modify, status, created_at)
            VALUES (?1, ?2, 'CUSTOMER_SELF', 'shr-VALVE-DEMO-001',
                    'zhang@customer.cn', NOW() + INTERVAL '7 days',
                    3, NOW() - INTERVAL '2 hours', TRUE, 'ACTIVE',
                    NOW() - INTERVAL '2 days')
            ON CONFLICT (share_token) DO NOTHING
            """).setParameter(1, SHARE_ID).setParameter(2, INST3_ID).executeUpdate();

        // 验证
        Map<String, Object> stats = new HashMap<>();
        stats.put("mat_part_models", count("mat_part_model WHERE part_no = 'VALVE-BALL-BASE'"));
        stats.put("feature_groups", count("cpq_feature_group WHERE code = 'FG-VALVE-001'"));
        stats.put("feature_fields", count("cpq_feature_field cff JOIN cpq_feature_group cfg ON cfg.id = cff.group_id WHERE cfg.code = 'FG-VALVE-001'"));
        stats.put("feature_values", count("cpq_feature_value cfv JOIN cpq_feature_field cff ON cff.id = cfv.field_id JOIN cpq_feature_group cfg ON cfg.id = cff.group_id WHERE cfg.code = 'FG-VALVE-001'"));
        stats.put("templates", count("product_config_template WHERE code = 'CFG-TPL-BALL-VALVE'"));
        stats.put("options", countTpl("product_config_option WHERE template_id = ?"));
        stats.put("option_values", countTpl("product_config_option_value pcov JOIN product_config_option pco ON pco.id = pcov.option_id WHERE pco.template_id = ?"));
        stats.put("constraints", countTpl("product_config_constraint WHERE template_id = ?"));
        stats.put("instances", countTpl("product_config_instance WHERE template_id = ?"));
        stats.put("leads", count("customer_lead WHERE lead_code LIKE 'LEAD-%-V001'"));
        stats.put("shares", count("product_config_share WHERE share_token = 'shr-VALVE-DEMO-001'"));
        stats.put("status", "✅ 阀门 demo 数据已入库");
        return ApiResponse.success(stats);
    }

    @DELETE
    @Transactional
    public ApiResponse<Map<String, Object>> rollback() {
        assertDevMode();
        Map<String, Object> deleted = new HashMap<>();
        deleted.put("shares", em.createNativeQuery(
            "DELETE FROM product_config_share WHERE share_token = 'shr-VALVE-DEMO-001'"
        ).executeUpdate());
        deleted.put("instances", em.createNativeQuery(
            "DELETE FROM product_config_instance WHERE template_id = ?1"
        ).setParameter(1, TPL_ID).executeUpdate());
        deleted.put("leads", em.createNativeQuery(
            "DELETE FROM customer_lead WHERE id = ?1"
        ).setParameter(1, LEAD_ID).executeUpdate());
        deleted.put("constraints", em.createNativeQuery(
            "DELETE FROM product_config_constraint WHERE template_id = ?1"
        ).setParameter(1, TPL_ID).executeUpdate());
        deleted.put("option_values", em.createNativeQuery(
            "DELETE FROM product_config_option_value WHERE option_id IN (SELECT id FROM product_config_option WHERE template_id = ?1)"
        ).setParameter(1, TPL_ID).executeUpdate());
        deleted.put("options", em.createNativeQuery(
            "DELETE FROM product_config_option WHERE template_id = ?1"
        ).setParameter(1, TPL_ID).executeUpdate());
        deleted.put("templates", em.createNativeQuery(
            "DELETE FROM product_config_template WHERE id = ?1"
        ).setParameter(1, TPL_ID).executeUpdate());
        deleted.put("feature_values", em.createNativeQuery(
            "DELETE FROM cpq_feature_value cfv USING cpq_feature_field cff, cpq_feature_group cfg WHERE cfv.field_id = cff.id AND cff.group_id = cfg.id AND cfg.code = 'FG-VALVE-001'"
        ).executeUpdate());
        deleted.put("feature_fields", em.createNativeQuery(
            "DELETE FROM cpq_feature_field cff USING cpq_feature_group cfg WHERE cff.group_id = cfg.id AND cfg.code = 'FG-VALVE-001'"
        ).executeUpdate());
        deleted.put("feature_groups", em.createNativeQuery(
            "DELETE FROM cpq_feature_group WHERE code = 'FG-VALVE-001'"
        ).executeUpdate());
        deleted.put("source_files", em.createNativeQuery(
            "DELETE FROM mat_part_source_file WHERE part_no = 'VALVE-BALL-BASE'"
        ).executeUpdate());
        deleted.put("mat_part_models", em.createNativeQuery(
            "DELETE FROM mat_part_model WHERE part_no = 'VALVE-BALL-BASE'"
        ).executeUpdate());
        deleted.put("status", "🗑 阀门 demo 数据已回滚");
        return ApiResponse.success(deleted);
    }

    private void assertDevMode() {
        if (LaunchMode.current() != LaunchMode.DEVELOPMENT) {
            throw new WebApplicationException("Seed endpoint only available in DEVELOPMENT mode",
                                              Response.Status.FORBIDDEN);
        }
    }

    private Long fieldId(Long groupId, String code) {
        return ((Number) em.createNativeQuery(
            "SELECT id FROM cpq_feature_field WHERE group_id = ?1 AND code = ?2"
        ).setParameter(1, groupId).setParameter(2, code).getSingleResult()).longValue();
    }

    private UUID optionUuid(UUID templateId, String code) {
        Object o = em.createNativeQuery(
            "SELECT id FROM product_config_option WHERE template_id = ?1 AND code = ?2"
        ).setParameter(1, templateId).setParameter(2, code).getSingleResult();
        return (o instanceof UUID) ? (UUID) o : UUID.fromString(o.toString());
    }

    private long count(String fromAndWhere) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM " + fromAndWhere).getSingleResult()).longValue();
    }
    private long countTpl(String fromAndWhereWithPlaceholder) {
        // 用 ?  会被 hibernate 当 ordinal — 改成 ?1
        String sql = "SELECT COUNT(*) FROM " + fromAndWhereWithPlaceholder.replace("?", "?1");
        return ((Number) em.createNativeQuery(sql).setParameter(1, TPL_ID).getSingleResult()).longValue();
    }
}
