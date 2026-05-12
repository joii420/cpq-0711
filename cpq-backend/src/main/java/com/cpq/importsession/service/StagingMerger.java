package com.cpq.importsession.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.importsession.entity.ImportSessionDecision;
import com.cpq.partversion.PartVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V6 Staging → mat_* 合并服务。
 *
 * <p>职责：
 *   1. applyPartVersionDecisions：根据用户决策把 staging 数据合并进 mat_* 正式表
 *      - BUMP：先 mergeStagingToMat（写正式表），再 PartVersionService.applyVersionBump（升版号）
 *      - NEW ：先 mergeStagingToMat（写正式表，版本号=2000），返回 appliedVersion=2000
 *      - NO_BUMP：跳过写库，appliedVersion 取 mapping 的 current_version
 *   2. clearStaging：commit 或 cancel 后显式清空 7 张 staging 表（session DELETE CASCADE 也会清，
 *      但主动清可确保事务内数据不残留）
 *
 * <p>事务边界：
 *   applyPartVersionDecisions 由 ImportSessionService.commit 在外层 @Transactional 中调用，
 *   mergeStagingToMat 中的 SQL 参与同一 JTA 事务，任何一步失败整体回滚。
 *
 * <p>JDBC 直连原因：staging 表无 JPA 实体（避免建立 Hibernate 映射），
 *   Agroal 连接自动加入当前 JTA 事务，无需手动 commit。
 */
@ApplicationScoped
public class StagingMerger {

    private static final Logger LOG = Logger.getLogger(StagingMerger.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    PartVersionService partVersionService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 主流程 ────────────────────────────────────────────────────────────────

    /**
     * 按 PART_VERSION 类型的决策列表合并 staging → mat_*。
     *
     * @param sessionId  import_session.id
     * @param decisions  PART_VERSION 类型的决策列表（已从 import_session_decision 表加载）
     * @param userId     操作人 ID（写 version log 用）
     * @param sourceExcel 来源 Excel 文件名（写 version log 用）
     * @return Map&lt;decisionKey, appliedVersion&gt; 每个 (cpn|hf) 实际写入的版本号
     */
    @Transactional
    public Map<String, Integer> applyPartVersionDecisions(
            UUID sessionId,
            List<ImportSessionDecision> decisions,
            UUID userId,
            String sourceExcel) {

        Map<String, Integer> appliedVersions = new HashMap<>();

        for (ImportSessionDecision decision : decisions) {
            if (!"PART_VERSION".equals(decision.decisionType)) continue;

            String key = decision.decisionKey; // 格式: "{cpn}|{hf}"
            String[] parts = key.split("\\|", 2);
            if (parts.length != 2) {
                LOG.warnf("StagingMerger: 跳过无效 decisionKey=%s", key);
                continue;
            }
            String cpn = parts[0];
            String hf  = parts[1];

            // 解析 decision_value JSONB
            String action;
            Integer currentVersion;
            try {
                JsonNode node = MAPPER.readTree(decision.decisionValue);
                action = node.path("action").asText("NO_BUMP");
                currentVersion = node.has("currentVersion") && !node.path("currentVersion").isNull()
                        ? node.path("currentVersion").asInt() : null;
            } catch (Exception e) {
                LOG.warnf("StagingMerger: 解析 decisionValue 失败 key=%s value=%s, 默认 NO_BUMP",
                        key, decision.decisionValue);
                action = "NO_BUMP";
                currentVersion = null;
            }

            int appliedVersion;

            switch (action) {
                case "BUMP" -> {
                    // 先将 staging 写入正式表（用 currentVersion+1 作为版本号，由 applyVersionBump 算）
                    // 这里先用 currentVersion 写入，applyVersionBump 会 bump mapping 记录
                    int targetVersion = (currentVersion != null ? currentVersion : 2000) + 1;
                    mergeStagingToMat(sessionId, cpn, hf, targetVersion);
                    // 再升版号（写 mat_part_version_log + bump mapping.current_version）
                    appliedVersion = partVersionService.applyVersionBump(
                            cpn, hf, userId, sourceExcel, null, null);
                    LOG.infof("StagingMerger: BUMP (%s, %s) → v%d", cpn, hf, appliedVersion);
                }
                case "NEW" -> {
                    // 新料号，先确保 mapping 行存在（由 mergeStagingToMat 写 mapping staging）
                    mergeStagingToMat(sessionId, cpn, hf, 2000);
                    appliedVersion = 2000;
                    LOG.infof("StagingMerger: NEW (%s, %s) → v%d", cpn, hf, appliedVersion);
                }
                default -> { // NO_BUMP
                    // 不写库，取 DB 当前版本
                    appliedVersion = currentVersion != null ? currentVersion : 2000;
                    LOG.infof("StagingMerger: NO_BUMP (%s, %s), 保持 v%d", cpn, hf, appliedVersion);
                }
            }

            appliedVersions.put(key, appliedVersion);
        }

        return appliedVersions;
    }

    /**
     * 将 staging 表中属于 (sessionId, cpn, hf) 的数据合并到 mat_* 正式表。
     *
     * <p>写入顺序：
     *   1. mat_customer_part_mapping（UPSERT，仅新料号 NEW 动作才写 mapping；BUMP 时 mapping 已存在）
     *   2. mat_part（UPSERT by part_no）
     *   3. mat_bom（先 DELETE 旧版本行，再 INSERT）
     *   4. mat_process（先 DELETE 旧版本行，再 INSERT，并标记 is_current=true）
     *   5. mat_fee（先 DELETE 旧版本行，再 INSERT，并标记 is_current=true）
     *   6. mat_plating_fee（先 DELETE 旧版本行，再 INSERT，并标记 is_current=true）
     *   7. mat_plating_plan（先 DELETE 旧版本行，再 INSERT）
     *
     * <p>版本号注入：staging 中的 part_version=2000 基线值在此处替换为 targetVersion。
     */
    private void mergeStagingToMat(UUID sessionId, String cpn, String hf, int targetVersion) {
        try (Connection conn = dataSource.getConnection()) {
            // 1. mat_customer_part_mapping（UPSERT：若不存在则插入，存在则不覆盖 mapping 主数据）
            mergeMapping(conn, sessionId, cpn, hf, targetVersion);
            // 2. mat_part（UPSERT by part_no）
            mergePart(conn, sessionId, hf);
            // 3. mat_bom
            mergeBom(conn, sessionId, hf, targetVersion);
            // 4. mat_process
            mergeProcess(conn, sessionId, cpn, hf, targetVersion);
            // 5. mat_fee
            mergeFee(conn, sessionId, cpn, hf, targetVersion);
            // 6. mat_plating_fee
            mergePlatingFee(conn, sessionId, cpn, hf, targetVersion);
            // 7. mat_plating_plan（plating_plan_code 来自 staging，按 hf 关联）
            mergePlatingPlan(conn, sessionId, hf, targetVersion);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            LOG.errorf(e, "StagingMerger: mergeStagingToMat 失败 session=%s cpn=%s hf=%s v=%d",
                    sessionId, cpn, hf, targetVersion);
            throw new BusinessException(500, "合并 staging 到正式表失败: " + e.getMessage());
        }
    }

    private void mergeMapping(Connection conn, UUID sessionId, String cpn, String hf,
                               int targetVersion) throws Exception {
        // UPSERT：若 mapping 不存在则从 staging 插入（新料号），已存在则更新 current_version
        // V151 创建的 uq_mat_cust_part_global 是部分唯一索引（WHERE 谓词限定 NOT NULL），
        // ON CONFLICT 必须显式匹配相同 WHERE 谓词，否则 PG 报 "no unique constraint matching"
        String sql =
            "INSERT INTO mat_customer_part_mapping " +
            "  (id, customer_id, customer_product_no, customer_part_name, customer_drawing_no, " +
            "   hf_part_no, payment_method, base_currency, quote_currency, current_version) " +
            "SELECT gen_random_uuid(), s.customer_id, s.customer_product_no, s.customer_part_name, " +
            "       s.customer_drawing_no, s.hf_part_no, s.payment_method, " +
            "       s.base_currency, s.quote_currency, ? " +
            "FROM mat_customer_part_mapping_staging s " +
            "WHERE s.import_session_id = ? " +
            "  AND s.hf_part_no = ? AND s.customer_product_no = ? " +
            "ON CONFLICT (customer_product_no, hf_part_no) " +
            "  WHERE customer_product_no IS NOT NULL AND hf_part_no IS NOT NULL " +
            "DO UPDATE SET current_version = EXCLUDED.current_version, updated_at = now()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setString(3, hf);
            ps.setString(4, cpn);
            ps.executeUpdate();
        }
    }

    private void mergePart(Connection conn, UUID sessionId, String hf) throws Exception {
        // mat_part PK = part_no (VARCHAR)，无 id 列；UPSERT by part_no
        String sql =
            "INSERT INTO mat_part " +
            "  (part_no, part_name, specification, size_info, unit_weight, weight_unit, status_code) " +
            "SELECT s.part_no, s.part_name, s.specification, s.size_info, " +
            "       s.unit_weight, s.weight_unit, COALESCE(s.status_code, 'Y') " +
            "FROM mat_part_staging s " +
            "WHERE s.import_session_id = ? AND s.part_no = ? " +
            "ON CONFLICT (part_no) DO UPDATE " +
            "  SET part_name = EXCLUDED.part_name, specification = EXCLUDED.specification, " +
            "      size_info = EXCLUDED.size_info, unit_weight = EXCLUDED.unit_weight, " +
            "      weight_unit = EXCLUDED.weight_unit, status_code = EXCLUDED.status_code, " +
            "      updated_at = now()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sessionId);
            ps.setString(2, hf);
            ps.executeUpdate();
        }
    }

    private void mergeBom(Connection conn, UUID sessionId, String hf, int targetVersion) throws Exception {
        // 先删旧版本行（同 hf + part_version），再从 staging 插入
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM mat_bom WHERE hf_part_no = ? AND part_version = ?")) {
            del.setString(1, hf);
            del.setInt(2, targetVersion);
            del.executeUpdate();
        }
        String sql =
            "INSERT INTO mat_bom " +
            "  (id, bom_type, hf_part_no, seq_no, input_material_no, input_material_name, " +
            "   loss_rate, gross_qty, net_qty, gross_unit, net_unit, output_material_type, " +
            "   defect_rate, element_name, composition_pct, part_version) " +
            "SELECT gen_random_uuid(), s.bom_type, s.hf_part_no, s.seq_no, s.input_material_no, " +
            "       s.input_material_name, s.loss_rate, s.gross_qty, s.net_qty, s.gross_unit, " +
            "       s.net_unit, s.output_material_type, s.defect_rate, s.element_name, " +
            "       s.composition_pct, ? " +
            "FROM mat_bom_staging s " +
            "WHERE s.import_session_id = ? AND s.hf_part_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setString(3, hf);
            ps.executeUpdate();
        }
    }

    private void mergeProcess(Connection conn, UUID sessionId, String cpn, String hf,
                               int targetVersion) throws Exception {
        // 先将同 hf + part_version 的旧行 is_current=false（不物理删除，保留历史）
        // 然后从 staging INSERT 新版本行，is_current=true
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE mat_process SET is_current = false " +
                "WHERE hf_part_no = ? AND part_version = ?")) {
            upd.setString(1, hf);
            upd.setInt(2, targetVersion);
            upd.executeUpdate();
        }
        String sql =
            "INSERT INTO mat_process " +
            "  (id, customer_id, hf_part_no, seq_no, sub_seq_no, process_code, assembly_process, " +
            "   component_part_no, component_name, supplier_code, supplier_name, quantity, " +
            "   quantity_unit, unit_price, freight, currency, price_unit, " +
            "   part_version, is_current, version) " +
            "SELECT gen_random_uuid(), s.customer_id, s.hf_part_no, s.seq_no, s.sub_seq_no, " +
            "       s.process_code, s.assembly_process, s.component_part_no, s.component_name, " +
            "       s.supplier_code, s.supplier_name, s.quantity, s.quantity_unit, " +
            "       s.unit_price, s.freight, s.currency, s.price_unit, " +
            "       ?, true, 1 " +
            "FROM mat_process_staging s " +
            "WHERE s.import_session_id = ? AND s.hf_part_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setString(3, hf);
            ps.executeUpdate();
        }
    }

    private void mergeFee(Connection conn, UUID sessionId, String cpn, String hf,
                           int targetVersion) throws Exception {
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE mat_fee SET is_current = false " +
                "WHERE hf_part_no = ? AND part_version = ?")) {
            upd.setString(1, hf);
            upd.setInt(2, targetVersion);
            upd.executeUpdate();
        }
        String sql =
            "INSERT INTO mat_fee " +
            "  (id, customer_id, hf_part_no, fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, " +
            "   dim_input_material_no, dim_input_material_name, dim_element_name, " +
            "   dim_assembly_process, dim_sub_seq_no, price_floating, settlement_rise_ratio, " +
            "   fixed_rise_value, rise_currency, rise_unit, reject_rate, " +
            "   part_version, is_current, version) " +
            "SELECT gen_random_uuid(), s.customer_id, s.hf_part_no, s.fee_type, s.seq_no, " +
            "       s.fee_value, s.fee_ratio, s.currency, s.price_unit, " +
            "       s.dim_input_material_no, s.dim_input_material_name, s.dim_element_name, " +
            "       s.dim_assembly_process, s.dim_sub_seq_no, s.price_floating, " +
            "       s.settlement_rise_ratio, s.fixed_rise_value, s.rise_currency, s.rise_unit, " +
            "       s.reject_rate, ?, true, 1 " +
            "FROM mat_fee_staging s " +
            "WHERE s.import_session_id = ? AND s.hf_part_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setString(3, hf);
            ps.executeUpdate();
        }
    }

    private void mergePlatingFee(Connection conn, UUID sessionId, String cpn, String hf,
                                  int targetVersion) throws Exception {
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE mat_plating_fee SET is_current = false " +
                "WHERE hf_part_no = ? AND part_version = ?")) {
            upd.setString(1, hf);
            upd.setInt(2, targetVersion);
            upd.executeUpdate();
        }
        String sql =
            "INSERT INTO mat_plating_fee " +
            "  (id, customer_id, hf_part_no, plating_plan_code, plan_version, " +
            "   plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, " +
            "   part_version, is_current, version) " +
            "SELECT gen_random_uuid(), s.customer_id, s.hf_part_no, s.plating_plan_code, s.plan_version, " +
            "       s.plating_process_fee, s.plating_material_fee, s.currency, s.price_unit, " +
            "       s.defect_rate, ?, true, 1 " +
            "FROM mat_plating_fee_staging s " +
            "WHERE s.import_session_id = ? AND s.hf_part_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setString(3, hf);
            ps.executeUpdate();
        }
    }

    private void mergePlatingPlan(Connection conn, UUID sessionId, String hf,
                                   int targetVersion) throws Exception {
        // plating_plan 通过 plating_plan_code 关联 hf（间接）
        // staging 写入时 hf_part_no 未存储在 mat_plating_plan_staging（原表无此列），
        // 需通过 mat_plating_fee_staging 中的 plating_plan_code 关联找出对应 plan_code
        // 再按 plan_code 合并 mat_plating_plan
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM mat_plating_plan WHERE plan_code IN (" +
                "  SELECT DISTINCT plating_plan_code FROM mat_plating_fee_staging " +
                "  WHERE import_session_id = ? AND hf_part_no = ?) " +
                "AND part_version = ?")) {
            del.setObject(1, sessionId);
            del.setString(2, hf);
            del.setInt(3, targetVersion);
            del.executeUpdate();
        }
        String sql =
            "INSERT INTO mat_plating_plan " +
            "  (id, plan_code, version, seq_no, plating_element, plating_area, " +
            "   coating_thickness, plating_requirement, part_version) " +
            "SELECT gen_random_uuid(), s.plan_code, s.version, s.seq_no, s.plating_element, " +
            "       s.plating_area, s.coating_thickness, s.plating_requirement, ? " +
            "FROM mat_plating_plan_staging s " +
            "WHERE s.import_session_id = ? " +
            "  AND s.plan_code IN (" +
            "    SELECT DISTINCT plating_plan_code FROM mat_plating_fee_staging " +
            "    WHERE import_session_id = ? AND hf_part_no = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetVersion);
            ps.setObject(2, sessionId);
            ps.setObject(3, sessionId);
            ps.setString(4, hf);
            ps.executeUpdate();
        }
    }

    // ── staging 清理 ──────────────────────────────────────────────────────────

    /**
     * 显式清空 7 张 staging 表中属于 sessionId 的行。
     *
     * <p>import_session DELETE CASCADE 也会触发清理，但 commit 流程在 session 仍 COMMITTED
     * 状态下需主动清理（session 行保留作审计，staging 数据不再需要）。
     */
    @Transactional
    public void clearStaging(UUID sessionId) {
        try (Connection conn = dataSource.getConnection()) {
            String[] tables = {
                "mat_plating_plan_staging",
                "mat_plating_fee_staging",
                "mat_fee_staging",
                "mat_process_staging",
                "mat_bom_staging",
                "mat_customer_part_mapping_staging",
                "mat_part_staging"
            };
            for (String table : tables) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + table + " WHERE import_session_id = ?")) {
                    ps.setObject(1, sessionId);
                    int rows = ps.executeUpdate();
                    LOG.debugf("StagingMerger: clearStaging %s → 删除 %d 行 (session=%s)",
                            table, rows, sessionId);
                }
            }
            LOG.infof("StagingMerger: clearStaging 完成 session=%s", sessionId);
        } catch (Exception e) {
            LOG.warnf(e, "StagingMerger: clearStaging 失败 session=%s（非致命，CASCADE 会清理）", sessionId);
        }
    }
}
