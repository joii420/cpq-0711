package com.cpq.importsession.service;

import com.cpq.importsession.dto.CustomerConflictItem;
import com.cpq.importsession.dto.OrphanItem;
import com.cpq.importsession.dto.PartVersionDecisionItem;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.partversion.PartVersionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * V6 差异检测服务（只读，不写库）。
 *
 * <p>职责：
 *   1. detectPartVersions：为每个 (cpn, hf) pair 生成 PartVersionDecisionItem（BUMP/NO_BUMP/NEW）
 *   2. detectCustomerConflicts：检测 Excel 与 DB is_current=true 数据的字段冲突
 *   3. detectOrphanRows：检测 hf_part_no 在 mat_customer_part_mapping 中无记录的孤儿行
 *
 * <p>设计原则：
 *   - 只读，不写库（ImportSessionService 负责写 import_session_decision）
 *   - 按 (cpn, hf) 维度聚合差异，每对对应一条 PartVersionDecisionItem
 *   - sheetDiffs 提供 sheet 级变更行数计数（含新增/修改/删除）
 *   - rowLevelDiff 提供 field 级对比（按 sheet 分组，前端「查看详情」展开用）
 */
@ApplicationScoped
public class DiffDetector {

    private static final Logger LOG = Logger.getLogger(DiffDetector.class);

    @Inject
    PartVersionService partVersionService;

    @Inject
    EntityManager em;

    // ── 料号版本差异检测 ──────────────────────────────────────────────────────

    /**
     * 向后兼容重载：不传 sessionId 时走旧的行级字段对比逻辑。
     */
    public List<PartVersionDecisionItem> detectPartVersions(ParsedBasicData data, UUID customerId) {
        return detectPartVersions(data, customerId, null);
    }

    /**
     * 为 ParsedBasicData 中的每个 (cpn, hf) pair 生成版本决策项。
     *
     * <p>逻辑：
     *   1. 从 mappings 行收集 (cpn, hf)；无 mappings 时从 BOM/工艺/费用中收集 hf 再查 DB 补 cpn
     *   2. 调 PartVersionService.getCurrentVersion 判断 isNew
     *   3. isNew=true  → action=NEW, suggestedVersion=2000
     *   4. isNew=false + sessionId 非 null → V6 指纹比对判定 BUMP/NO_BUMP
     *      （规避 BigDecimal 精度 / seq_no 类型 / is_current 过滤等边界 bug）
     *   5. isNew=false + sessionId=null → 旧行级字段对比（向后兼容）
     *   6. 生成 rowLevelDiff（BOM field-level 对比，仅 BUMP 时）
     *
     * @param sessionId 非 null 时启用指纹比对（推荐），null 时退化为旧逻辑
     */
    @SuppressWarnings("unchecked")
    public List<PartVersionDecisionItem> detectPartVersions(ParsedBasicData data, UUID customerId,
                                                             UUID sessionId) {
        // 1. 从 mapping 行收集 (hf → cpn) 映射
        Map<String, String> cpnByHf = new LinkedHashMap<>();
        for (ParsedBasicData.MappingRow r : data.mappings) {
            if (r.hfPartNo != null && !r.hfPartNo.isBlank()
                    && r.customerProductNo != null && !r.customerProductNo.isBlank()) {
                cpnByHf.put(r.hfPartNo, r.customerProductNo);
            }
        }

        // 若无 mapping 行，从 BOM/工艺/费用中收集 hf，再查 DB 补 cpn
        if (cpnByHf.isEmpty()) {
            Set<String> hfSet = new HashSet<>();
            data.matBoms.forEach(r -> { if (r.hfPartNo != null) hfSet.add(r.hfPartNo); });
            data.matProcesses.forEach(r -> { if (r.hfPartNo != null) hfSet.add(r.hfPartNo); });
            data.matFees.forEach(r -> { if (r.hfPartNo != null) hfSet.add(r.hfPartNo); });
            data.platingFees.forEach(r -> { if (r.hfPartNo != null) hfSet.add(r.hfPartNo); });

            if (!hfSet.isEmpty()) {
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT customer_product_no, hf_part_no FROM mat_customer_part_mapping " +
                        "WHERE customer_id = :cid AND hf_part_no IN :hfs " +
                        "AND customer_product_no IS NOT NULL")
                        .setParameter("cid", customerId)
                        .setParameter("hfs", hfSet)
                        .getResultList();
                for (Object[] r : rows) {
                    String cpn = r[0] == null ? null : r[0].toString();
                    String hf  = r[1] == null ? null : r[1].toString();
                    if (cpn != null && hf != null) cpnByHf.put(hf, cpn);
                }
            }
        }

        if (cpnByHf.isEmpty()) {
            LOG.warnf("V6 DiffDetector: 无法提取 (cpn, hf) pairs，customerId=%s", customerId);
            return Collections.emptyList();
        }

        List<PartVersionDecisionItem> result = new ArrayList<>();

        for (Map.Entry<String, String> entry : cpnByHf.entrySet()) {
            String hf  = entry.getKey();
            String cpn = entry.getValue();
            String key = cpn + "|" + hf;

            // 2. 查当前版本
            Optional<Integer> currentOpt = partVersionService.getCurrentVersion(cpn, hf);
            boolean isNew = currentOpt.isEmpty();
            Integer currentVersion = currentOpt.orElse(null);
            Integer suggestedVersion = isNew ? 2000 : (currentVersion + 1);

            Map<String, Integer> sheetDiffs;
            Map<String, List<PartVersionDecisionItem.RowDiff>> rowLevelDiff;
            String action;

            if (isNew) {
                // 全新料号
                action = "NEW";
                sheetDiffs = computeSheetDiffs(data, hf, null);
                rowLevelDiff = Collections.emptyMap();
            } else if (sessionId != null) {
                // V6 指纹比对路径：staging 表 md5 vs mat_* 正式表 md5
                String stagingFp = partVersionService.computeStagingFingerprint(sessionId, cpn, hf);
                String matFp     = partVersionService.computeMatFingerprintForStagingCompare(
                        cpn, hf, currentVersion);
                boolean hasChange = !stagingFp.equals(matFp);
                LOG.infof("V6 fingerprint compare (%s|%s, v=%d): staging=%s mat=%s → %s",
                        cpn, hf, currentVersion,
                        stagingFp.substring(0, Math.min(8, stagingFp.length())),
                        matFp.substring(0, Math.min(8, matFp.length())),
                        hasChange ? "BUMP" : "NO_BUMP");
                action = hasChange ? "BUMP" : "NO_BUMP";
                sheetDiffs = hasChange
                        ? computeSheetDiffs(data, hf, currentVersion)
                        : Map.of("bom", 0, "process", 0, "fee", 0, "plating_fee", 0);
                rowLevelDiff = hasChange
                        ? computeRowLevelDiff(data, hf, currentVersion)
                        : Collections.emptyMap();
            } else {
                // 旧行级字段对比路径（向后兼容，sessionId=null 时）
                sheetDiffs = computeSheetDiffs(data, hf, currentVersion);
                boolean hasChange = sheetDiffs.values().stream().anyMatch(v -> v > 0);
                action = hasChange ? "BUMP" : "NO_BUMP";
                rowLevelDiff = hasChange
                        ? computeRowLevelDiff(data, hf, currentVersion)
                        : Collections.emptyMap();
            }

            PartVersionDecisionItem item = new PartVersionDecisionItem();
            item.key = key;
            item.customerProductNo = cpn;
            item.hfPartNo = hf;
            item.currentVersion = currentVersion;
            item.suggestedVersion = suggestedVersion;
            item.isNew = isNew;
            item.action = action;
            item.sheetDiffs = sheetDiffs;
            item.rowLevelDiff = rowLevelDiff;
            result.add(item);
        }

        LOG.infof("V6 DiffDetector: 检测到 %d 个 (cpn, hf) pairs，customerId=%s", result.size(), customerId);
        return result;
    }

    /**
     * 计算各 sheet 的变更行数（新增 + 修改 + 删除之和）。
     * currentVersion=null 表示新料号，全为新增行。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> computeSheetDiffs(ParsedBasicData data, String hf,
                                                    Integer currentVersion) {
        Map<String, Integer> diffs = new LinkedHashMap<>();

        // BOM sheet
        List<ParsedBasicData.MatBomRow> excelBoms = data.matBoms.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        diffs.put("bom", computeBomDiff(excelBoms, hf, currentVersion));

        // process sheet
        List<ParsedBasicData.MatProcessRow> excelProcs = data.matProcesses.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        diffs.put("process", computeCountDiff(excelProcs.size(), "mat_process", hf, currentVersion, true));

        // fee sheet
        List<ParsedBasicData.MatFeeRow> excelFees = data.matFees.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        diffs.put("fee", computeCountDiff(excelFees.size(), "mat_fee", hf, currentVersion, true));

        // plating_fee sheet
        List<ParsedBasicData.PlatingFeeRow> excelPf = data.platingFees.stream()
                .filter(r -> hf.equals(r.hfPartNo) && "mat_plating_fee".equals(r.targetTable))
                .collect(Collectors.toList());
        diffs.put("plating_fee", computeCountDiff(excelPf.size(), "mat_plating_fee", hf, currentVersion, true));

        return diffs;
    }

    /** BOM diff：行数 delta + 关键字段变更数 */
    @SuppressWarnings("unchecked")
    private int computeBomDiff(List<ParsedBasicData.MatBomRow> excelBoms, String hf,
                                Integer currentVersion) {
        if (excelBoms.isEmpty()) return 0;
        if (currentVersion == null) return excelBoms.size();  // 全新行

        List<Object[]> dbBoms = em.createNativeQuery(
                "SELECT bom_type, seq_no, gross_qty, net_qty, loss_rate FROM mat_bom " +
                "WHERE hf_part_no = :hf AND part_version = :v")
                .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();

        int diff = Math.abs(excelBoms.size() - dbBoms.size());
        if (diff == 0 && !dbBoms.isEmpty()) {
            Map<String, Object[]> dbMap = new HashMap<>();
            for (Object[] r : dbBoms) dbMap.put(str(r[0]) + ":" + str(r[1]), r);
            for (ParsedBasicData.MatBomRow r : excelBoms) {
                Object[] db = dbMap.get(r.bomType + ":" + r.seqNo);
                if (db == null) { diff++; continue; }
                if (!eqDec(str(db[2]), r.grossQty)) diff++;
                else if (!eqDec(str(db[3]), r.netQty)) diff++;
                else if (!eqDec(str(db[4]), r.lossRate)) diff++;
            }
        }
        return diff;
    }

    /** 通用行数 delta diff（is_current 过滤可选） */
    @SuppressWarnings("unchecked")
    private int computeCountDiff(int excelCount, String table, String hf,
                                  Integer currentVersion, boolean filterIsCurrent) {
        if (excelCount == 0) return 0;
        if (currentVersion == null) return excelCount;
        try {
            String whereCurrent = filterIsCurrent ? " AND is_current = true" : "";
            long dbCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM " + table +
                    " WHERE hf_part_no = :hf AND part_version = :v" + whereCurrent)
                    .setParameter("hf", hf).setParameter("v", currentVersion)
                    .getSingleResult()).longValue();
            return Math.abs(excelCount - (int) dbCount);
        } catch (Exception e) {
            LOG.warnf("V6 DiffDetector: %s 行数 diff 计算失败（非阻塞）: %s", table, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算 BOM 行级 field-level diff（field 值对比，按 rowKey 分组）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<PartVersionDecisionItem.RowDiff>> computeRowLevelDiff(
            ParsedBasicData data, String hf, Integer currentVersion) {
        Map<String, List<PartVersionDecisionItem.RowDiff>> result = new LinkedHashMap<>();
        if (currentVersion == null) return result;

        List<ParsedBasicData.MatBomRow> excelBoms = data.matBoms.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        if (excelBoms.isEmpty()) return result;

        List<Object[]> dbBoms = em.createNativeQuery(
                "SELECT bom_type, seq_no, gross_qty, net_qty, loss_rate, " +
                "  input_material_no, input_material_name FROM mat_bom " +
                "WHERE hf_part_no = :hf AND part_version = :v")
                .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();
        Map<String, Object[]> dbMap = new HashMap<>();
        for (Object[] r : dbBoms) dbMap.put(str(r[0]) + ":" + str(r[1]), r);

        List<PartVersionDecisionItem.RowDiff> bomDiffs = new ArrayList<>();
        for (ParsedBasicData.MatBomRow r : excelBoms) {
            String rowKey = r.bomType + ":seq" + r.seqNo;
            Object[] db = dbMap.get(r.bomType + ":" + r.seqNo);
            if (db == null) {
                bomDiffs.add(new PartVersionDecisionItem.RowDiff(rowKey, "row", null, "新增行"));
                continue;
            }
            addIfChanged(bomDiffs, rowKey, "gross_qty",   str(db[2]), r.grossQty);
            addIfChanged(bomDiffs, rowKey, "net_qty",     str(db[3]), r.netQty);
            addIfChanged(bomDiffs, rowKey, "loss_rate",   str(db[4]), r.lossRate);
        }
        if (!bomDiffs.isEmpty()) result.put("bom", bomDiffs);
        return result;
    }

    // ── 客户冲突检测 ──────────────────────────────────────────────────────────

    /**
     * 检测客户料号冲突：对比 Excel 与 DB is_current=true 行的字段差异。
     * V6 简化版：仅检测 mat_process.unit_price 字段（差异 >0.01 标记冲突）。
     * 后续可扩展为全字段对比（参考 V5 BasicDataImportServiceV5.detectCustomerDataConflicts）。
     */
    @SuppressWarnings("unchecked")
    public List<CustomerConflictItem> detectCustomerConflicts(ParsedBasicData data, UUID customerId) {
        List<CustomerConflictItem> conflicts = new ArrayList<>();
        try {
            for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
                if (r.hfPartNo == null || r.unitPrice == null) continue;
                List<Object[]> rows = em.createNativeQuery(
                        "SELECT unit_price FROM mat_process " +
                        "WHERE customer_id = :cid AND hf_part_no = :hf " +
                        "AND seq_no = :seq AND is_current = true LIMIT 1")
                        .setParameter("cid", customerId)
                        .setParameter("hf", r.hfPartNo)
                        .setParameter("seq", r.seqNo)
                        .getResultList();
                if (!rows.isEmpty() && rows.get(0)[0] != null) {
                    BigDecimal dbPrice = (BigDecimal) rows.get(0)[0];
                    if (r.unitPrice.subtract(dbPrice).abs().compareTo(new BigDecimal("0.01")) > 0) {
                        CustomerConflictItem item = new CustomerConflictItem();
                        item.key = "process|" + r.hfPartNo + "|" + r.seqNo;
                        item.conflictType = "process";
                        item.hfPartNo = r.hfPartNo;
                        item.excelValue = r.unitPrice.toPlainString();
                        item.dbValue = dbPrice.toPlainString();
                        item.defaultAction = "USE_EXCEL";
                        conflicts.add(item);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("V6 DiffDetector: 客户冲突检测失败（非阻塞）: %s", e.getMessage());
        }
        return conflicts;
    }

    // ── 孤儿行检测 ────────────────────────────────────────────────────────────

    /**
     * 检测孤儿行：hfPartNo 在 mat_customer_part_mapping 中无对应记录的行。
     * 检测范围：mat_bom / mat_process / mat_fee / mat_plating_fee 四类 sheet。
     */
    @SuppressWarnings("unchecked")
    public List<OrphanItem> detectOrphanRows(ParsedBasicData data, UUID customerId) {
        List<OrphanItem> result = new ArrayList<>();

        // 收集 Excel 中涉及的所有 hfPartNo
        Set<String> excelHfs = new HashSet<>();
        data.matBoms.forEach(r -> { if (r.hfPartNo != null) excelHfs.add(r.hfPartNo); });
        data.matProcesses.forEach(r -> { if (r.hfPartNo != null) excelHfs.add(r.hfPartNo); });
        data.matFees.forEach(r -> { if (r.hfPartNo != null) excelHfs.add(r.hfPartNo); });
        data.platingFees.forEach(r -> { if (r.hfPartNo != null) excelHfs.add(r.hfPartNo); });

        if (excelHfs.isEmpty()) return result;

        // 查 DB 中有 mapping 记录的 hf（任意 cpn 均可）
        List<String> knownHfs = em.createNativeQuery(
                "SELECT DISTINCT hf_part_no FROM mat_customer_part_mapping " +
                "WHERE customer_id = :cid AND hf_part_no IN :hfs")
                .setParameter("cid", customerId)
                .setParameter("hfs", excelHfs)
                .getResultList();
        Set<String> knownHfSet = new HashSet<>(knownHfs);

        // BOM 孤儿行
        for (ParsedBasicData.MatBomRow r : data.matBoms) {
            if (r.hfPartNo == null || knownHfSet.contains(r.hfPartNo)) continue;
            OrphanItem item = new OrphanItem();
            item.key = "bom|" + r.rowNum;
            item.sheetCode = "bom";
            item.rowIndex = r.rowNum;
            item.rowSnapshot = Map.of(
                    "hf_part_no", r.hfPartNo,
                    "seq_no", String.valueOf(r.seqNo),
                    "bom_type", r.bomType != null ? r.bomType : "");
            item.reason = "hf_part_no [" + r.hfPartNo + "] 在 mat_customer_part_mapping 中无记录";
            item.defaultAction = "DISCARD";
            result.add(item);
        }

        // mat_process 孤儿行
        for (ParsedBasicData.MatProcessRow r : data.matProcesses) {
            if (r.hfPartNo == null || knownHfSet.contains(r.hfPartNo)) continue;
            OrphanItem item = new OrphanItem();
            item.key = "process|" + r.rowNum;
            item.sheetCode = "process";
            item.rowIndex = r.rowNum;
            item.rowSnapshot = Map.of(
                    "hf_part_no", r.hfPartNo,
                    "seq_no", String.valueOf(r.seqNo));
            item.reason = "hf_part_no [" + r.hfPartNo + "] 在 mat_customer_part_mapping 中无记录";
            item.defaultAction = "DISCARD";
            result.add(item);
        }

        LOG.infof("V6 DiffDetector: 检测到 %d 条孤儿行，customerId=%s", result.size(), customerId);
        return result;
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** BigDecimal 值相等比较（忽略精度差异） */
    private boolean eqDec(String dbVal, BigDecimal excelVal) {
        if (dbVal == null && excelVal == null) return true;
        if (dbVal == null || excelVal == null) return false;
        try {
            return new BigDecimal(dbVal).compareTo(excelVal) == 0;
        } catch (NumberFormatException e) {
            return dbVal.equals(excelVal.toPlainString());
        }
    }

    /** 当 DB 值与 Excel 值不相等时，向 diffs 添加一条 RowDiff */
    private void addIfChanged(List<PartVersionDecisionItem.RowDiff> diffs, String rowKey,
                               String field, String dbVal, BigDecimal excelVal) {
        String excelStr = excelVal == null ? null : excelVal.toPlainString();
        if (!Objects.equals(dbVal, excelStr)) {
            diffs.add(new PartVersionDecisionItem.RowDiff(rowKey, field, dbVal, excelStr));
        }
    }
}
