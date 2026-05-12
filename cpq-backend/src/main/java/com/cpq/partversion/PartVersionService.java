package com.cpq.partversion;

import com.cpq.partversion.dto.DiffSummary;
import com.cpq.partversion.dto.PartVersionLogDTO;
import com.cpq.partversion.dto.VersionDecisionDTO;
import com.cpq.partversion.dto.VersionProposeRequest;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 料号版本管理服务 (S2).
 *
 * <p>设计原则: S2 不接入任何主流程, 仅提供独立能力供 S3 阶段前端调用.
 * <ul>
 *   <li>不改 BasicDataImportServiceV5</li>
 *   <li>不改 VersionedWriter</li>
 *   <li>DB 行为不变 (现有数据保持 part_version=2000)</li>
 * </ul>
 *
 * <p>S2 提供:
 * <ul>
 *   <li>查询当前/历史版本号</li>
 *   <li>升版动作 (写 mat_part_version_log + bump current_version)</li>
 *   <li>版本切换 (REVERT 路径)</li>
 *   <li>指纹基础设施 (按 (cpn, hf, version) 计算 md5)</li>
 * </ul>
 *
 * <p>S3 阶段会扩展:
 * <ul>
 *   <li>接入 Excel 解析: 拿 ParsedBasicData 算"新数据指纹"</li>
 *   <li>三路判定真正落地: 比对历史指纹 → REVERT / NEW_VERSION / NO_CHANGE</li>
 *   <li>接入 BasicDataImportServiceV5 写库时按 part_version 路由</li>
 * </ul>
 */
@ApplicationScoped
public class PartVersionService {

    @Inject EntityManager em;

    /** 参与版本管理的明细表 (V153 已加 part_version 列). mat_part 不在内 (维度表). */
    private static final List<String> VERSIONED_TABLES = List.of(
            "mat_bom",
            "mat_process",
            "mat_fee",
            "mat_plating_plan",
            "mat_plating_fee",
            "costing_part_process_cost",
            "costing_part_tooling_cost",
            "costing_part_material_bom",
            "costing_part_element_bom",
            "costing_part_quality_check",
            "costing_part_plating",
            "costing_part_plating_fee",
            "costing_part_design_cost",
            "costing_part_weight"
    );

    /** 指纹排除的元数据列 (用户决策 #4). 各表如有这些列, 计算指纹时排除. */
    private static final List<String> METADATA_COLS = List.of(
            "id", "created_at", "updated_at", "created_by", "updated_by",
            "import_record_id", "imported_by", "source_excel", "import_batch_id"
    );

    // ============================================================
    // 公开 API — 查询
    // ============================================================

    /** 查 (cpn, hf) 当前激活版本; 若 mapping 无记录返回 Optional.empty(). */
    @SuppressWarnings("unchecked")
    public Optional<Integer> getCurrentVersion(String customerProductNo, String hfPartNo) {
        List<Object> rs = em.createNativeQuery(
                "SELECT current_version FROM mat_customer_part_mapping " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf " +
                "LIMIT 1")
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .getResultList();
        if (rs.isEmpty()) return Optional.empty();
        return Optional.of(((Number) rs.get(0)).intValue());
    }

    /** 列出 (cpn, hf) 全部历史版本日志, 按 version DESC. */
    @SuppressWarnings("unchecked")
    public List<PartVersionLogDTO> listVersions(String customerProductNo, String hfPartNo) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT version, content_hash, diff_summary, source_excel, " +
                "       source_import_id, created_at, created_by " +
                "FROM mat_part_version_log " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf " +
                "ORDER BY version DESC")
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .getResultList();
        List<PartVersionLogDTO> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new PartVersionLogDTO(
                    customerProductNo,
                    hfPartNo,
                    ((Number) r[0]).intValue(),
                    r[1] == null ? null : r[1].toString(),
                    r[2] == null ? null : r[2].toString(),
                    r[3] == null ? null : r[3].toString(),
                    r[4] == null ? null : (UUID) r[4],
                    r[5] == null ? null : toOffsetDateTime(r[5]),
                    r[6] == null ? null : (UUID) r[6]
            ));
        }
        return out;
    }

    /**
     * 计算 DB 中 (cpn, hf, version) 已存数据的指纹 (md5).
     * 跨 14 张表分别取行集, 拼接后 md5.
     * 各表过滤策略:
     * <ul>
     *   <li>含 customer_product_no 的表 (V154: mat_process/fee/plating_fee): WHERE cpn=X AND hf=Y AND part_version=V</li>
     *   <li>仅含 hf_part_no 的表 (mat_bom/costing_part_*): WHERE hf=Y AND part_version=V</li>
     *   <li>无 hf_part_no 的表 (mat_plating_plan, costing_part_element_bom, costing_part_plating): WHERE part_version=V</li>
     * </ul>
     */
    public String computeStoredFingerprint(String customerProductNo, String hfPartNo, int version) {
        StringBuilder combined = new StringBuilder();
        for (String table : VERSIONED_TABLES) {
            String tableHash = computeTableFingerprint(table, customerProductNo, hfPartNo, version);
            combined.append(table).append('=').append(tableHash).append(';');
        }
        return md5Hex(combined.toString());
    }

    // ============================================================
    // 公开 API — propose (S2 占位, S3 接入 Excel 解析后扩展真实判定)
    // ============================================================

    /**
     * 三路判定. S2 阶段最小占位实现:
     * <ul>
     *   <li>若 mapping 无记录 (cpn, hf): 返回 NEW_VERSION, proposedVersion=2001 (新料号首次升版)</li>
     *   <li>否则: 默认建议 NEW_VERSION (current+1)</li>
     * </ul>
     * S3 接入 Excel 解析后, 会比对 incoming 数据指纹与历史指纹, 真实返回 NO_CHANGE/REVERT/NEW_VERSION.
     */
    public VersionDecisionDTO propose(VersionProposeRequest req) {
        Optional<Integer> currentOpt = getCurrentVersion(req.customerProductNo(), req.hfPartNo());
        int current = currentOpt.orElse(2000);
        List<Integer> historical = listVersions(req.customerProductNo(), req.hfPartNo()).stream()
                .map(PartVersionLogDTO::version).toList();
        return new VersionDecisionDTO(
                VersionDecisionDTO.Action.NEW_VERSION,
                current,
                current + 1,
                null,
                Collections.emptyMap(),
                historical
        );
    }

    // ============================================================
    // 公开 API — 写入 (升版 / 切换版本)
    // ============================================================

    /**
     * 应用升版决策, 写 mat_part_version_log 并 bump mapping.current_version.
     * S2 阶段: 仅做"版本号管理", 不写明细表数据 (S3 接入时由 BasicDataImportServiceV5 按 part_version 写库).
     *
     * @return 实际写入的版本号 (current + 1)
     */
    @Transactional
    public int applyVersionBump(String customerProductNo, String hfPartNo,
                                  UUID userId, String sourceExcel,
                                  String contentHash, Map<String, DiffSummary> diffByTable) {
        int currentVer = getCurrentVersion(customerProductNo, hfPartNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "无效的 (cpn, hf): " + customerProductNo + "/" + hfPartNo +
                        " — mat_customer_part_mapping 表无对应行"));
        int newVer = currentVer + 1;

        String diffJson = diffByTable == null || diffByTable.isEmpty()
                ? null
                : serializeDiff(diffByTable);

        em.createNativeQuery(
                "INSERT INTO mat_part_version_log " +
                "(customer_product_no, hf_part_no, version, content_hash, diff_summary, " +
                " source_excel, source_import_id, created_at, created_by) " +
                "VALUES (:cpn, :hf, :v, :hash, CAST(:diff AS jsonb), :src, NULL::uuid, now(), :uid)")
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .setParameter("v", newVer)
                .setParameter("hash", contentHash)
                .setParameter("diff", diffJson)
                .setParameter("src", sourceExcel)
                .setParameter("uid", userId)
                .executeUpdate();

        em.createNativeQuery(
                "UPDATE mat_customer_part_mapping SET current_version = :v, updated_at = now() " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf")
                .setParameter("v", newVer)
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .executeUpdate();

        Log.infof("PartVersion: (%s, %s) bumped %d -> %d (excel=%s)",
                customerProductNo, hfPartNo, currentVer, newVer, sourceExcel);
        return newVer;
    }

    /**
     * 切换激活版本到某个历史版本 (用户决策 #1.2 — REVERT 路径).
     * S5 阶段 quotation 切换版本时也会调用类似逻辑.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void switchActiveVersion(String customerProductNo, String hfPartNo, int targetVersion) {
        List<Object> rs = em.createNativeQuery(
                "SELECT 1 FROM mat_part_version_log " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf AND version = :v")
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .setParameter("v", targetVersion)
                .getResultList();
        if (rs.isEmpty()) {
            throw new IllegalArgumentException(
                    "版本 v" + targetVersion + " 在 (" + customerProductNo + ", " + hfPartNo + ") 的历史中不存在");
        }
        em.createNativeQuery(
                "UPDATE mat_customer_part_mapping SET current_version = :v, updated_at = now() " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf")
                .setParameter("v", targetVersion)
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .executeUpdate();
        Log.infof("PartVersion: (%s, %s) active switched to v%d",
                customerProductNo, hfPartNo, targetVersion);
    }

    // ============================================================
    // 临时 admin: 清空报价基础数据 (用于测试重置)
    // 不清空: customer / product / template / component / costing_part_*
    // ============================================================
    @Transactional
    public java.util.Map<String, Integer> wipeBasicData() {
        java.util.LinkedHashMap<String, Integer> stats = new java.util.LinkedHashMap<>();
        // 顺序很重要 - 先删 FK 引用方, 后删被引用方
        // 注意: 不删 mat_part (物料主档, 有 plating_fee 等 FK 引用, 删会让事务回滚)
        // 不删 import_record (有其他表 FK 引用); 它们留着不影响重新导入测试
        String[] tables = {
                "quotation_line_component_data",
                "quotation_line_process",
                "quotation_line_item_snapshot",
                "quotation_line_item",
                "quotation_approval",
                "quotation",
                "mat_part_version_log",
                "mat_plating_fee",
                "mat_plating_plan",
                "mat_fee",
                "mat_process",
                "mat_bom",
                "mat_customer_part_mapping"
        };
        for (String t : tables) {
            try {
                int n = em.createNativeQuery("DELETE FROM " + t).executeUpdate();
                stats.put(t, n);
                Log.infof("WIPE: %s deleted %d rows", t, n);
            } catch (Exception e) {
                stats.put(t, -1);
                Log.warnf("WIPE: %s failed: %s", t, e.getMessage());
            }
        }
        return stats;
    }

    // ============================================================
    // 内部: 单表指纹计算
    // ============================================================

    private String computeTableFingerprint(String table, String cpn, String hfPart, int version) {
        List<String> dataCols = listDataColumns(table);
        if (dataCols.isEmpty()) return "EMPTY";

        String filter = buildFilterClause(dataCols);
        String colsExpr = String.join(", ",
                dataCols.stream().map(c -> "COALESCE(" + c + "::text, '')").toList());
        String sql = "SELECT COALESCE(md5(string_agg(row_text, ',' ORDER BY row_text)), 'EMPTY') FROM (" +
                " SELECT concat_ws('|', " + colsExpr + ") AS row_text" +
                " FROM " + table +
                " WHERE part_version = :ver " + filter +
                ") t";
        try {
            jakarta.persistence.Query q = em.createNativeQuery(sql).setParameter("ver", version);
            if (dataCols.contains("customer_product_no")) {
                q.setParameter("cpn", cpn);
            }
            if (dataCols.contains("hf_part_no")) {
                q.setParameter("hf", hfPart);
            }
            Object r = q.getSingleResult();
            return r == null ? "EMPTY" : r.toString();
        } catch (Exception e) {
            Log.warnf(e, "PartVersion: 表 %s 指纹计算失败, 视为 EMPTY", table);
            return "EMPTY";
        }
    }

    private String buildFilterClause(List<String> dataCols) {
        StringBuilder sb = new StringBuilder();
        if (dataCols.contains("customer_product_no")) {
            sb.append(" AND customer_product_no = :cpn");
        }
        if (dataCols.contains("hf_part_no")) {
            sb.append(" AND hf_part_no = :hf");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> listDataColumns(String table) {
        List<String> all = em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = :t " +
                "ORDER BY ordinal_position")
                .setParameter("t", table)
                .getResultList();
        return all.stream()
                .filter(c -> !METADATA_COLS.contains(c))
                .toList();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String serializeDiff(Map<String, DiffSummary> diff) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, DiffSummary> e : diff.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":{")
              .append("\"added\":").append(e.getValue().added()).append(',')
              .append("\"changed\":").append(e.getValue().changed()).append(',')
              .append("\"deleted\":").append(e.getValue().deleted())
              .append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.time.Instant inst) return inst.atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
