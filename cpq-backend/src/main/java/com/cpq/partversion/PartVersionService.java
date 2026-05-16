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
@SuppressWarnings("unused")
public class PartVersionService {

    @Inject EntityManager em;

    /** Self-injection: 让 wipeBasicData 通过 CDI 代理调 wipeSingleTable 才能触发 REQUIRES_NEW */
    @Inject jakarta.enterprise.inject.Instance<PartVersionService> self;

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
            "import_record_id", "imported_by", "source_excel", "import_batch_id",
            // V6: staging 表专有元数据列（与 mat_* 正式表对齐，让双方指纹可比）
            "staging_id", "import_session_id", "part_version", "is_current", "version"
    );

    /** V6: 参与版本指纹比对的 staging 表名列表（与 MAT_TABLES_FOR_FP 一一对应） */
    private static final List<String> STAGING_TABLES_FOR_FP = List.of(
            "mat_bom_staging",
            "mat_process_staging",
            "mat_fee_staging",
            "mat_plating_plan_staging",
            "mat_plating_fee_staging"
    );

    /** V6: 正式表参与指纹比对的子集（与 STAGING_TABLES_FOR_FP 对齐顺序） */
    private static final List<String> MAT_TABLES_FOR_FP = List.of(
            "mat_bom",
            "mat_process",
            "mat_fee",
            "mat_plating_plan",
            "mat_plating_fee"
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
    // V6: staging 指纹基础设施
    // ============================================================

    /**
     * V6: 计算 (sessionId, cpn, hf) 在 mat_*_staging 中的"待提交数据指纹"。
     *
     * <p>与 computeMatFingerprintForStagingCompare 配合：两侧 md5 相同 → NO_BUMP；
     * 不同 → BUMP（数据真有变化）。
     *
     * <p>METADATA_COLS 已扩展了 staging_id/import_session_id/part_version/is_current/version，
     * 使 staging 与 mat_* 双方列集合对齐，指纹可直接比较。
     */
    public String computeStagingFingerprint(UUID sessionId, String customerProductNo, String hfPartNo) {
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < STAGING_TABLES_FOR_FP.size(); i++) {
            String stagingTable = STAGING_TABLES_FOR_FP.get(i);
            String matTable     = MAT_TABLES_FOR_FP.get(i);
            // 关键: 用 staging∩mat 共同列, 否则 mat_bom 有 child_part_no 而 mat_bom_staging 没有
            // 会让两侧 concat_ws 列数不同 → md5 永远不同 → 永远 BUMP 误报 (2026-05-15 修)
            List<String> commonCols = commonDataColumns(stagingTable, matTable);
            String tableHash = computeStagingTableFingerprint(stagingTable, commonCols, sessionId,
                    customerProductNo, hfPartNo);
            combined.append(matTable).append('=').append(tableHash).append(';');
        }
        return md5Hex(combined.toString());
    }

    /**
     * V6: 计算正式表 (cpn, hf, version) 的指纹，只跨与 STAGING_TABLES_FOR_FP 对齐的 5 张 mat_* 表
     * （不含 costing_part_*），让 staging vs mat_* 双方指纹按相同表集合算出，可直接比较。
     * 列集合也用 staging∩mat 交集, 避免单边列(如 mat_bom.child_part_no)拉偏指纹.
     */
    public String computeMatFingerprintForStagingCompare(String customerProductNo, String hfPartNo,
                                                          int version) {
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < MAT_TABLES_FOR_FP.size(); i++) {
            String matTable     = MAT_TABLES_FOR_FP.get(i);
            String stagingTable = STAGING_TABLES_FOR_FP.get(i);
            List<String> commonCols = commonDataColumns(stagingTable, matTable);
            String tableHash = computeTableFingerprint(matTable, commonCols,
                    customerProductNo, hfPartNo, version);
            combined.append(matTable).append('=').append(tableHash).append(';');
        }
        return md5Hex(combined.toString());
    }

    /** 单张 staging 表的指纹：按 import_session_id + hf_part_no (+ cpn 如有) 过滤
     *  使用调用方传入的 commonCols（保证两侧用相同列集合算指纹）.
     */
    private String computeStagingTableFingerprint(String stagingTable, List<String> commonCols,
                                                   UUID sessionId, String cpn, String hf) {
        if (commonCols.isEmpty()) return "EMPTY";

        StringBuilder filterSb = new StringBuilder();
        // (2026-05-15) customer_product_no = :cpn 时容忍 NULL — 与 buildFilterClause (mat 侧) 对齐
        if (commonCols.contains("customer_product_no")) {
            filterSb.append(" AND (customer_product_no = :cpn OR customer_product_no IS NULL)");
        }
        if (commonCols.contains("hf_part_no"))          filterSb.append(" AND hf_part_no = :hf");

        String colsExpr = String.join(", ",
                commonCols.stream().map(c -> "COALESCE(" + c + "::text, '')").toList());
        String sql = "SELECT COALESCE(md5(string_agg(row_text, ',' ORDER BY row_text)), 'EMPTY') FROM (" +
                " SELECT concat_ws('|', " + colsExpr + ") AS row_text" +
                " FROM " + stagingTable +
                " WHERE import_session_id = :sid" + filterSb +
                ") t";
        try {
            jakarta.persistence.Query q = em.createNativeQuery(sql).setParameter("sid", sessionId);
            if (commonCols.contains("customer_product_no")) q.setParameter("cpn", cpn);
            if (commonCols.contains("hf_part_no"))          q.setParameter("hf", hf);
            Object r = q.getSingleResult();
            return r == null ? "EMPTY" : r.toString();
        } catch (Exception e) {
            Log.warnf(e, "PartVersion: staging 表 %s 指纹计算失败, 视为 EMPTY", stagingTable);
            return "EMPTY";
        }
    }

    /** staging ∩ mat 共同数据列(去除 METADATA_COLS), 保持 mat 表的 ordinal_position 顺序 */
    @SuppressWarnings("unchecked")
    private List<String> commonDataColumns(String stagingTable, String matTable) {
        List<String> matCols = listDataColumns(matTable);
        java.util.Set<String> stgCols = new java.util.HashSet<>(listDataColumns(stagingTable));
        return matCols.stream().filter(stgCols::contains).toList();
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

        // 反查 customer_id —— mat_part_version_log 的 PK 是 (customer_id, hf_part_no, version),
        // customer_id NOT NULL; 同时 mat_customer_part_mapping 的真键也是 (customer_id, hf_part_no)
        // (2026-05-15 修: 原 INSERT 漏 customer_id 列 → 23502 NOT NULL 违反)
        @SuppressWarnings("unchecked")
        List<Object> cidRows = em.createNativeQuery(
                "SELECT customer_id FROM mat_customer_part_mapping " +
                "WHERE customer_product_no = :cpn AND hf_part_no = :hf LIMIT 1")
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .getResultList();
        if (cidRows.isEmpty() || cidRows.get(0) == null) {
            throw new IllegalArgumentException(
                    "无法解析 customer_id: (cpn, hf)=(" + customerProductNo + ", " + hfPartNo + ")");
        }
        UUID customerId = (UUID) cidRows.get(0);

        String diffJson = diffByTable == null || diffByTable.isEmpty()
                ? null
                : serializeDiff(diffByTable);

        em.createNativeQuery(
                "INSERT INTO mat_part_version_log " +
                "(customer_id, customer_product_no, hf_part_no, version, content_hash, diff_summary, " +
                " source_excel, source_import_id, created_at, created_by) " +
                "VALUES (:cid, :cpn, :hf, :v, :hash, CAST(:diff AS jsonb), :src, NULL::uuid, now(), :uid)")
                .setParameter("cid", customerId)
                .setParameter("cpn", customerProductNo)
                .setParameter("hf", hfPartNo)
                .setParameter("v", newVer)
                .setParameter("hash", contentHash)
                .setParameter("diff", diffJson)
                .setParameter("src", sourceExcel)
                .setParameter("uid", userId)
                .executeUpdate();

        // UPDATE 按真键 (customer_id, hf_part_no) 走, 与 uq_mat_cust_part_per_hf 对齐
        em.createNativeQuery(
                "UPDATE mat_customer_part_mapping SET current_version = :v, updated_at = now() " +
                "WHERE customer_id = :cid AND hf_part_no = :hf")
                .setParameter("v", newVer)
                .setParameter("cid", customerId)
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
    public java.util.Map<String, Integer> wipeBasicData() {
        java.util.LinkedHashMap<String, Integer> stats = new java.util.LinkedHashMap<>();
        // 顺序很重要 - 先删 FK 引用方, 后删被引用方
        // 注意: 不删 mat_part (物料主档, 有 plating_fee 等 FK 引用, 删会让事务回滚)
        // 不删 import_record (有其他表 FK 引用); 它们留着不影响重新导入测试
        // V6: 加 mat_*_staging 和 import_session 系列表
        // 每个 DELETE 用 REQUIRES_NEW 独立事务,一个失败不影响其他表
        String[] tables = {
                // 报价单链
                "quotation_line_component_data",
                "quotation_line_process",
                "quotation_line_item_snapshot",
                "quotation_line_item",
                "quotation_approval",
                "quotation",
                // V6 staging 系列 (按依赖顺序: staging 先于 session)
                "mat_part_staging",
                "mat_customer_part_mapping_staging",
                "mat_bom_staging",
                "mat_process_staging",
                "mat_fee_staging",
                "mat_plating_fee_staging",
                "mat_plating_plan_staging",
                "import_session_decision",
                "import_session",
                // 版本日志
                "mat_part_version_log",
                // 正式表 (按依赖顺序)
                "mat_plating_fee",
                "mat_plating_plan",
                "mat_fee",
                "mat_process",
                "mat_bom",
                "mat_customer_part_mapping"
        };
        // 通过 self CDI 代理调用 wipeSingleTable,才能触发 @Transactional(REQUIRES_NEW)
        PartVersionService proxy = self.get();
        for (String t : tables) {
            stats.put(t, proxy.wipeSingleTable(t));
        }
        return stats;
    }

    /**
     * 单表 DELETE,REQUIRES_NEW 独立事务。任何异常自动回滚本表,不影响其他表。
     * 旧实现把所有 DELETE 放进单个 @Transactional → 一旦某表 DELETE 抛异常,
     * 整个 JTA 事务标记 rollback-only,后续所有 DELETE 都失败 (-1)。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int wipeSingleTable(String table) {
        try {
            int n = em.createNativeQuery("DELETE FROM " + table).executeUpdate();
            Log.infof("WIPE: %s deleted %d rows", table, n);
            return n;
        } catch (Exception e) {
            Log.warnf("WIPE: %s failed: %s", table, e.getMessage());
            return -1;
        }
    }

    // ============================================================
    // 内部: 单表指纹计算
    // ============================================================

    private String computeTableFingerprint(String table, String cpn, String hfPart, int version) {
        return computeTableFingerprint(table, listDataColumns(table), cpn, hfPart, version);
    }

    /** 重载: 调用方传入要参与指纹的列集合（用于 staging vs mat 取交集场景）. */
    private String computeTableFingerprint(String table, List<String> dataCols,
                                            String cpn, String hfPart, int version) {
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
        // (2026-05-15) customer_product_no 在 mat_fee 等表是 NULL (新列, 历史 V5 导入未填),
        // 用作 WHERE 过滤会排除全部行 → fingerprint 永远 "EMPTY" → 误报 NO_BUMP.
        // 改为 customer_product_no = :cpn 时容忍 NULL, 保证历史行也参与指纹比对.
        StringBuilder sb = new StringBuilder();
        if (dataCols.contains("customer_product_no")) {
            sb.append(" AND (customer_product_no = :cpn OR customer_product_no IS NULL)");
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
