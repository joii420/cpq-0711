package com.cpq.importsession.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import com.cpq.system.config.service.SystemConfigService;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * V6 导入暂存写入服务。
 *
 * <p>职责：
 *   1. 复用 BasicDataImportServiceV5.parseExcel 解析 Excel（V6 staging flow 复用，parseExcel 已是 public）
 *   2. 把 ParsedBasicData 批量 INSERT 进 7 张 mat_*_staging 表
 *   3. 不写正式 mat_* 表，保证上传阶段 staging 完全隔离
 *
 * <p>事务边界：
 *   parseAndWriteStaging 标 @Transactional，外层 ImportSessionService.upload
 *   在同一事务中建 session + 写 staging + 写 decisions，任何一步失败整体回滚。
 *
 * <p>staging_id 列由 DB DEFAULT gen_random_uuid() 自动生成，INSERT 语句不显式传入。
 * id 列（源表 PK）允许 NULL（V159 已 DROP NOT NULL），commit 时再 gen_random_uuid()。
 */
@ApplicationScoped
public class StagingWriter {

    private static final Logger LOG = Logger.getLogger(StagingWriter.class);

    /** V6 staging flow 复用：parseExcel 已是 public 方法 */
    @Inject
    BasicDataImportServiceV5 basicDataImportServiceV5;

    @Inject
    SystemConfigService configService;

    /** 直接 JDBC 批量 INSERT，避免 Hibernate 对 staging 表建立映射实体 */
    @Inject
    AgroalDataSource dataSource;

    /**
     * 解析 Excel 并将数据批量写入 7 张 staging 表。
     *
     * @param excel      上传的 Excel InputStream（调用方负责关闭）
     * @param customerId 关联客户 ID
     * @param sessionId  当前 import_session.id（写入每条 staging 行的 import_session_id）
     * @return ParsedBasicData 解析结果，供 DiffDetector 复用，避免重复解析
     */
    @Transactional
    public ParsedBasicData parseAndWriteStaging(InputStream excel, UUID customerId, UUID sessionId) {
        int maxRows = (int) configService.getNumber("validation.import_max_rows");

        // V6 staging flow 复用：BasicDataImportServiceV5.parseExcel 已是 public 方法
        ParsedBasicData data = basicDataImportServiceV5.parseExcel(excel, customerId, maxRows);

        LOG.infof("V6 StagingWriter: session=%s customer=%s totalRows=%d，开始写 staging 表",
                sessionId, customerId, data.totalRows);

        try (Connection conn = dataSource.getConnection()) {
            // Agroal 连接池默认 autoCommit=true；
            // @Transactional 通过 JTA 管控，Agroal 会自动加入已有事务，无需手动 setAutoCommit
            writeMatPartStaging(conn, data, sessionId);
            writeMappingStaging(conn, data, sessionId, customerId);
            writeMatBomStaging(conn, data, sessionId);
            writeMatProcessStaging(conn, data, sessionId);
            writeMatFeeStaging(conn, data, sessionId);
            writeMatPlatingFeeStaging(conn, data, sessionId);
            writeMatPlatingPlanStaging(conn, data, sessionId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            LOG.errorf(e, "V6 StagingWriter: 写 staging 表失败 session=%s", sessionId);
            throw new BusinessException(500, "写入 staging 表失败: " + e.getMessage());
        }

        LOG.infof("V6 StagingWriter: session=%s staging 写入完成", sessionId);
        return data;
    }

    // ── 各 staging 表写入方法 ──────────────────────────────────────────────────

    private void writeMatPartStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        if (data.matParts.isEmpty()) return;
        // INSERT 列：mat_part 主要列 + import_session_id
        // staging_id 由 DEFAULT gen_random_uuid() 自动生成，id 列允许 NULL
        String sql = "INSERT INTO mat_part_staging " +
                "(part_no, part_name, specification, size_info, unit_weight, weight_unit, " +
                " status_code, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.MatPartRow r : data.matParts) {
                ps.setString(1, r.partNo);
                ps.setString(2, r.partName);
                ps.setString(3, r.specification);
                ps.setString(4, r.sizeInfo);
                setBigDecimal(ps, 5, r.unitWeight);
                ps.setString(6, r.weightUnit);
                ps.setString(7, r.statusCode != null ? r.statusCode : "Y");
                ps.setObject(8, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_part_staging 插入 %d 行", data.matParts.size());
        }
    }

    private void writeMappingStaging(Connection conn, ParsedBasicData data,
                                      UUID sessionId, UUID customerId) throws Exception {
        if (data.mappings.isEmpty()) return;
        // INSERT 列：mat_customer_part_mapping 主要列 + import_session_id
        String sql = "INSERT INTO mat_customer_part_mapping_staging " +
                "(customer_id, customer_product_no, customer_part_name, customer_drawing_no, " +
                " hf_part_no, payment_method, base_currency, quote_currency, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.MappingRow r : data.mappings) {
                ps.setObject(1, customerId);
                ps.setString(2, r.customerProductNo);
                ps.setString(3, r.customerPartName);
                ps.setString(4, r.customerDrawingNo);
                ps.setString(5, r.hfPartNo);
                ps.setString(6, r.paymentMethod);
                ps.setString(7, r.baseCurrency);
                ps.setString(8, r.quoteCurrency);
                ps.setObject(9, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_customer_part_mapping_staging 插入 %d 行", data.mappings.size());
        }
    }

    private void writeMatBomStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        if (data.matBoms.isEmpty()) return;
        // INSERT 列：mat_bom 主要列（V153 含 part_version）+ import_session_id
        // staging 阶段暂存 part_version=2000（基线），commit 时按决策替换为正式版本号
        String sql = "INSERT INTO mat_bom_staging " +
                "(bom_type, hf_part_no, seq_no, input_material_no, input_material_name, " +
                " loss_rate, gross_qty, net_qty, gross_unit, net_unit, output_material_type, " +
                " defect_rate, element_name, composition_pct, part_version, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.MatBomRow r : data.matBoms) {
                ps.setString(1, r.bomType);
                ps.setString(2, r.hfPartNo);
                ps.setInt(3, r.seqNo);
                ps.setString(4, r.inputMaterialNo);
                ps.setString(5, r.inputMaterialName);
                setBigDecimal(ps, 6, r.lossRate);
                setBigDecimal(ps, 7, r.grossQty);
                setBigDecimal(ps, 8, r.netQty);
                ps.setString(9, r.grossUnit);
                ps.setString(10, r.netUnit);
                ps.setString(11, r.outputMaterialType);
                setBigDecimal(ps, 12, r.defectRate);
                ps.setString(13, r.elementName);
                setBigDecimal(ps, 14, r.compositionPct);
                ps.setInt(15, 2000);  // staging 暂存基线版本，commit 时替换
                ps.setObject(16, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_bom_staging 插入 %d 行", data.matBoms.size());
        }
    }

    private void writeMatProcessStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        if (data.matProcesses.isEmpty()) return;
        // INSERT 列：mat_process 主要列（V153 含 part_version，V154 含 customer_product_no）+ import_session_id
        // version/is_current 由 VersionedWriter 管理，staging 阶段不填
        String sql = "INSERT INTO mat_process_staging " +
                "(customer_id, hf_part_no, seq_no, sub_seq_no, process_code, assembly_process, " +
                " component_part_no, component_name, supplier_code, supplier_name, quantity, " +
                " quantity_unit, unit_price, freight, currency, price_unit, " +
                " part_version, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
                ps.setObject(1, r.customerId);
                ps.setString(2, r.hfPartNo);
                ps.setInt(3, r.seqNo);
                setIntOrNull(ps, 4, r.subSeqNo);
                ps.setString(5, r.processCode);
                ps.setString(6, r.assemblyProcess);
                ps.setString(7, r.componentPartNo);
                ps.setString(8, r.componentName);
                ps.setString(9, r.supplierCode);
                ps.setString(10, r.supplierName);
                setBigDecimal(ps, 11, r.quantity);
                ps.setString(12, r.quantityUnit);
                setBigDecimal(ps, 13, r.unitPrice);
                setBigDecimal(ps, 14, r.freight);
                ps.setString(15, r.currency);
                ps.setString(16, r.priceUnit);
                ps.setInt(17, 2000);
                ps.setObject(18, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_process_staging 插入 %d 行", data.matProcesses.size());
        }
    }

    private void writeMatFeeStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        if (data.matFees.isEmpty()) return;
        // INSERT 列：mat_fee 主要列（V153 含 part_version，V154 含 customer_product_no）+ import_session_id
        String sql = "INSERT INTO mat_fee_staging " +
                "(customer_id, hf_part_no, fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, " +
                " dim_input_material_no, dim_input_material_name, dim_element_name, " +
                " dim_assembly_process, dim_sub_seq_no, price_floating, settlement_rise_ratio, " +
                " fixed_rise_value, rise_currency, rise_unit, reject_rate, " +
                " part_version, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.MatFeeRow r : data.matFees) {
                ps.setObject(1, r.customerId);
                ps.setString(2, r.hfPartNo);
                ps.setString(3, r.feeType);
                ps.setInt(4, r.seqNo);
                setBigDecimal(ps, 5, r.feeValue);
                setBigDecimal(ps, 6, r.feeRatio);
                ps.setString(7, r.currency);
                ps.setString(8, r.priceUnit);
                ps.setString(9, r.dimInputMaterialNo);
                ps.setString(10, r.dimInputMaterialName);
                ps.setString(11, r.dimElementName);
                ps.setString(12, r.dimAssemblyProcess);
                setIntOrNull(ps, 13, r.dimSubSeqNo);
                setBoolOrNull(ps, 14, r.priceFloating);
                setBigDecimal(ps, 15, r.settlementRiseRatio);
                setBigDecimal(ps, 16, r.fixedRiseValue);
                ps.setString(17, r.riseCurrency);
                ps.setString(18, r.riseUnit);
                setBigDecimal(ps, 19, r.rejectRate);
                ps.setInt(20, 2000);
                ps.setObject(21, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_fee_staging 插入 %d 行", data.matFees.size());
        }
    }

    private void writeMatPlatingFeeStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        // platingFees 中 targetTable="mat_plating_fee" 的行（V125 区分 plating_fee vs mat_plating_fee）
        long count = data.platingFees.stream()
                .filter(r -> "mat_plating_fee".equals(r.targetTable)).count();
        if (count == 0) return;
        // INSERT 列：mat_plating_fee 主要列（V153 含 part_version）+ import_session_id
        String sql = "INSERT INTO mat_plating_fee_staging " +
                "(customer_id, hf_part_no, plating_plan_code, plan_version, " +
                " plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, " +
                " part_version, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.PlatingFeeRow r : data.platingFees) {
                if (!"mat_plating_fee".equals(r.targetTable)) continue;
                ps.setObject(1, r.customerId);
                ps.setString(2, r.hfPartNo);
                ps.setString(3, r.platingPlanCode);
                ps.setString(4, r.planVersion);
                setBigDecimal(ps, 5, r.platingProcessFee);
                setBigDecimal(ps, 6, r.platingMaterialFee);
                ps.setString(7, r.currency);
                ps.setString(8, r.priceUnit);
                setBigDecimal(ps, 9, r.defectRate);
                ps.setInt(10, 2000);
                ps.setObject(11, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_plating_fee_staging 插入 %d 行", count);
        }
    }

    private void writeMatPlatingPlanStaging(Connection conn, ParsedBasicData data, UUID sessionId) throws Exception {
        // platingPlans 中 targetTable="mat_plating_plan" 的行（V125 区分 plating_plan vs mat_plating_plan）
        long count = data.platingPlans.stream()
                .filter(r -> "mat_plating_plan".equals(r.targetTable)).count();
        if (count == 0) return;
        // INSERT 列：mat_plating_plan 主要列（V153 含 part_version）+ import_session_id
        String sql = "INSERT INTO mat_plating_plan_staging " +
                "(plan_code, version, seq_no, plating_element, plating_area, " +
                " coating_thickness, plating_requirement, part_version, import_session_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParsedBasicData.PlatingPlanRow r : data.platingPlans) {
                if (!"mat_plating_plan".equals(r.targetTable)) continue;
                ps.setString(1, r.planCode);
                ps.setString(2, r.version);
                ps.setInt(3, r.seqNo);
                ps.setString(4, r.platingElement);
                setBigDecimal(ps, 5, r.platingArea);
                setBigDecimal(ps, 6, r.coatingThickness);
                ps.setString(7, r.platingRequirement);
                ps.setInt(8, 2000);
                ps.setObject(9, sessionId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.debugf("V6 StagingWriter: mat_plating_plan_staging 插入 %d 行", count);
        }
    }

    // ── JDBC 辅助方法 ──────────────────────────────────────────────────────────

    private void setBigDecimal(PreparedStatement ps, int idx, BigDecimal val) throws Exception {
        if (val == null) {
            ps.setNull(idx, java.sql.Types.NUMERIC);
        } else {
            ps.setBigDecimal(idx, val);
        }
    }

    private void setIntOrNull(PreparedStatement ps, int idx, Integer val) throws Exception {
        if (val == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, val);
        }
    }

    private void setBoolOrNull(PreparedStatement ps, int idx, Boolean val) throws Exception {
        if (val == null) {
            ps.setNull(idx, java.sql.Types.BOOLEAN);
        } else {
            ps.setBoolean(idx, val);
        }
    }
}
