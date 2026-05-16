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
                boolean fpDiffers = !stagingFp.equals(matFp);

                // 指纹"有差"可能源于历史脏数据 (stale is_current=false 重复行 / V5 时存的低精度遗留),
                // 不一定是业务字段真变. 用 sheetDiffs + rowLevelDiff 复核, 全 0 → 降级 NO_BUMP.
                sheetDiffs = computeSheetDiffs(data, hf, currentVersion);
                rowLevelDiff = computeRowLevelDiff(data, hf, currentVersion);
                boolean realChange = sheetDiffs.values().stream().anyMatch(v -> v > 0)
                        || rowLevelDiff.values().stream().anyMatch(rows -> !rows.isEmpty());
                action = realChange ? "BUMP" : "NO_BUMP";

                LOG.infof("V6 fingerprint+fielddiff (%s|%s, v=%d): fp=%s/%s sheetDiffs=%s realChange=%s → %s",
                        cpn, hf, currentVersion,
                        stagingFp.substring(0, Math.min(8, stagingFp.length())),
                        matFp.substring(0, Math.min(8, matFp.length())),
                        sheetDiffs, realChange, action);

                // realChange=false 时 sheetDiffs/rowLevelDiff 必然为零, UI 不会显示;
                // 但保留 fpDiffers 信息到日志, 便于后续排查"应该升版却被压成 NO_BUMP"的反例.
                if (fpDiffers && !realChange) {
                    LOG.debugf("V6: %s|%s fingerprint 报差但 fielddiff 全 0 — 降级 NO_BUMP", cpn, hf);
                }
            } else {
                // 旧行级字段对比路径（向后兼容，sessionId=null 时）
                sheetDiffs = computeSheetDiffs(data, hf, currentVersion);
                boolean hasChange = sheetDiffs.values().stream().anyMatch(v -> v > 0);
                action = hasChange ? "BUMP" : "NO_BUMP";
                rowLevelDiff = hasChange
                        ? computeRowLevelDiff(data, hf, currentVersion)
                        : Collections.emptyMap();
            }

            // (2026-05-15) 只把"需要用户决策"的料号返给前端 — NO_BUMP 跳过.
            // commit 阶段的 hfPairs 改从 staging mapping 表独立查 (不依赖 decisions),
            // 因此过滤 NO_BUMP 不会影响报价单候选拉取.
            if ("NO_BUMP".equals(action)) {
                continue;
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

        // fee sheet — 字段级对比 (业务高频改动的费用值)
        List<ParsedBasicData.MatFeeRow> excelFees = data.matFees.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        diffs.put("fee", computeFeeDiff(excelFees, hf, currentVersion));

        // plating_fee sheet
        List<ParsedBasicData.PlatingFeeRow> excelPf = data.platingFees.stream()
                .filter(r -> hf.equals(r.hfPartNo) && "mat_plating_fee".equals(r.targetTable))
                .collect(Collectors.toList());
        diffs.put("plating_fee", computeCountDiff(excelPf.size(), "mat_plating_fee", hf, currentVersion, true));

        return diffs;
    }

    /** BOM diff：行数 delta + 关键字段变更数
     *
     *  匹配 key 必须包含 (bom_type, seq_no, input_material_no, element_name) —— 与 DB 唯一约束
     *  uq_mat_bom_row(bom_type, hf_part_no, seq_no, COALESCE(input_material_no,''), COALESCE(element_name,''), part_version)
     *  对齐。否则同 seq_no 下多元素 (如 ELEMENT:seq1 既有 AgNi10 又有 CuZn36) 时,
     *  HashMap 会被覆盖, 导致 Excel 行错配 DB 行 → 误报"未改也升版" (2026-05-15 修).
     */
    @SuppressWarnings("unchecked")
    private int computeBomDiff(List<ParsedBasicData.MatBomRow> excelBoms, String hf,
                                Integer currentVersion) {
        if (excelBoms.isEmpty()) return 0;
        if (currentVersion == null) return excelBoms.size();  // 全新行

        List<Object[]> dbBoms = em.createNativeQuery(
                "SELECT bom_type, seq_no, gross_qty, net_qty, loss_rate, " +
                "  input_material_no, element_name FROM mat_bom " +
                "WHERE hf_part_no = :hf AND part_version = :v")
                .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();

        int diff = Math.abs(excelBoms.size() - dbBoms.size());
        if (diff == 0 && !dbBoms.isEmpty()) {
            Map<String, Object[]> dbMap = new HashMap<>();
            for (Object[] r : dbBoms) dbMap.put(bomCompoundKey(str(r[0]), str(r[1]), str(r[5]), str(r[6])), r);
            for (ParsedBasicData.MatBomRow r : excelBoms) {
                Object[] db = dbMap.get(bomCompoundKey(r.bomType, str(r.seqNo), r.inputMaterialNo, r.elementName));
                if (db == null) { diff++; continue; }
                if (!eqDec(str(db[2]), r.grossQty)) diff++;
                else if (!eqDec(str(db[3]), r.netQty)) diff++;
                else if (!eqDec(str(db[4]), r.lossRate)) diff++;
            }
        }
        return diff;
    }

    /**
     * 与 DB 唯一索引 uq_mat_bom_row 对齐的复合 key:
     * (bom_type, seq_no, COALESCE(input_material_no,''), COALESCE(element_name,''))
     */
    private static String bomCompoundKey(String bomType, Object seqNo, String inputMaterialNo, String elementName) {
        return (bomType == null ? "" : bomType) + ":" +
                (seqNo == null ? "" : seqNo.toString()) + ":" +
                (inputMaterialNo == null ? "" : inputMaterialNo) + ":" +
                (elementName == null ? "" : elementName);
    }

    /** mat_fee 复合 key: (fee_type, seq_no, dim_input_material_no, dim_element_name, dim_assembly_process) —
     *  覆盖 INCOMING_*, FINISHED_*, ASSEMBLY_PROCESS, COMPONENT_OTHER 等 fee_type 的多元素/多工序场景. */
    private static String feeCompoundKey(String feeType, Object seqNo, String inputMatNo,
                                          String elementName, String assemblyProcess) {
        return (feeType == null ? "" : feeType) + ":" +
                (seqNo == null ? "" : seqNo.toString()) + ":" +
                (inputMatNo == null ? "" : inputMatNo) + ":" +
                (elementName == null ? "" : elementName) + ":" +
                (assemblyProcess == null ? "" : assemblyProcess);
    }

    /** mat_fee 字段级 diff：行数 delta + 关键金额/比例字段变更数 (is_current=true 过滤) */
    @SuppressWarnings("unchecked")
    private int computeFeeDiff(List<ParsedBasicData.MatFeeRow> excelFees, String hf,
                                Integer currentVersion) {
        if (excelFees.isEmpty()) return 0;
        if (currentVersion == null) return excelFees.size();

        List<Object[]> dbFees = em.createNativeQuery(
                "SELECT fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, " +
                "  dim_input_material_no, dim_element_name, dim_assembly_process, " +
                "  settlement_rise_ratio, fixed_rise_value, reject_rate " +
                "FROM mat_fee WHERE hf_part_no = :hf AND part_version = :v AND is_current = true")
                .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();

        int diff = Math.abs(excelFees.size() - dbFees.size());
        if (diff == 0 && !dbFees.isEmpty()) {
            Map<String, Object[]> dbMap = new HashMap<>();
            for (Object[] r : dbFees) {
                dbMap.put(feeCompoundKey(str(r[0]), r[1], str(r[6]), str(r[7]), str(r[8])), r);
            }
            for (ParsedBasicData.MatFeeRow r : excelFees) {
                Object[] db = dbMap.get(feeCompoundKey(r.feeType, r.seqNo,
                        r.dimInputMaterialNo, r.dimElementName, r.dimAssemblyProcess));
                if (db == null) { diff++; continue; }
                if      (!eqDec(str(db[2]), r.feeValue))            diff++;
                else if (!eqDec(str(db[3]), r.feeRatio))            diff++;
                else if (!Objects.equals(emptyToNull(str(db[4])), emptyToNull(r.currency)))   diff++;
                else if (!Objects.equals(emptyToNull(str(db[5])), emptyToNull(r.priceUnit)))  diff++;
                else if (!eqDec(str(db[9]), r.settlementRiseRatio)) diff++;
                else if (!eqDec(str(db[10]), r.fixedRiseValue))     diff++;
                else if (!eqDec(str(db[11]), r.rejectRate))         diff++;
            }
        }
        return diff;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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

        // (2026-05-15) 加 element_name 列 + 改用复合 key, 与 DB 唯一索引 uq_mat_bom_row 对齐;
        // 修复同 seq_no 多元素时 HashMap 覆盖 → Excel 行错配 DB 行误报差异.
        List<Object[]> dbBoms = em.createNativeQuery(
                "SELECT bom_type, seq_no, gross_qty, net_qty, loss_rate, " +
                "  input_material_no, input_material_name, element_name FROM mat_bom " +
                "WHERE hf_part_no = :hf AND part_version = :v")
                .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();
        Map<String, Object[]> dbMap = new HashMap<>();
        for (Object[] r : dbBoms) dbMap.put(bomCompoundKey(str(r[0]), str(r[1]), str(r[5]), str(r[7])), r);

        List<PartVersionDecisionItem.RowDiff> bomDiffs = new ArrayList<>();
        for (ParsedBasicData.MatBomRow r : excelBoms) {
            // rowKey 显示给用户的标签，包含元素让用户区分同 seq_no 多元素
            String rowLabel = r.elementName != null && !r.elementName.isBlank()
                    ? r.bomType + ":seq" + r.seqNo + ":" + r.elementName
                    : r.bomType + ":seq" + r.seqNo;
            Object[] db = dbMap.get(bomCompoundKey(r.bomType, str(r.seqNo), r.inputMaterialNo, r.elementName));
            if (db == null) {
                bomDiffs.add(new PartVersionDecisionItem.RowDiff(rowLabel, "row", null, "新增行"));
                continue;
            }
            addIfChanged(bomDiffs, rowLabel, "gross_qty",   str(db[2]), r.grossQty);
            addIfChanged(bomDiffs, rowLabel, "net_qty",     str(db[3]), r.netQty);
            addIfChanged(bomDiffs, rowLabel, "loss_rate",   str(db[4]), r.lossRate);
        }
        if (!bomDiffs.isEmpty()) result.put("bom", bomDiffs);

        // (2026-05-15) 同样为 mat_fee 加字段级 diff 展示, 让用户能看到"来料/装配费"等变更明细
        List<ParsedBasicData.MatFeeRow> excelFees = data.matFees.stream()
                .filter(r -> hf.equals(r.hfPartNo)).collect(Collectors.toList());
        if (!excelFees.isEmpty()) {
            List<Object[]> dbFees = em.createNativeQuery(
                    "SELECT fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, " +
                    "  dim_input_material_no, dim_element_name, dim_assembly_process, " +
                    "  settlement_rise_ratio, fixed_rise_value, reject_rate " +
                    "FROM mat_fee WHERE hf_part_no = :hf AND part_version = :v AND is_current = true")
                    .setParameter("hf", hf).setParameter("v", currentVersion).getResultList();
            Map<String, Object[]> feeDbMap = new HashMap<>();
            for (Object[] r : dbFees) {
                feeDbMap.put(feeCompoundKey(str(r[0]), r[1], str(r[6]), str(r[7]), str(r[8])), r);
            }
            List<PartVersionDecisionItem.RowDiff> feeDiffs = new ArrayList<>();
            for (ParsedBasicData.MatFeeRow r : excelFees) {
                String dimLabel = "";
                if (r.dimAssemblyProcess != null && !r.dimAssemblyProcess.isBlank()) dimLabel = ":" + r.dimAssemblyProcess;
                else if (r.dimElementName != null && !r.dimElementName.isBlank())    dimLabel = ":" + r.dimElementName;
                else if (r.dimInputMaterialNo != null && !r.dimInputMaterialNo.isBlank()) dimLabel = ":" + r.dimInputMaterialNo;
                String rowLabel = r.feeType + ":seq" + r.seqNo + dimLabel;
                Object[] db = feeDbMap.get(feeCompoundKey(r.feeType, r.seqNo,
                        r.dimInputMaterialNo, r.dimElementName, r.dimAssemblyProcess));
                if (db == null) {
                    feeDiffs.add(new PartVersionDecisionItem.RowDiff(rowLabel, "row", null, "新增行"));
                    continue;
                }
                addIfChanged(feeDiffs, rowLabel, "fee_value",             str(db[2]), r.feeValue);
                addIfChanged(feeDiffs, rowLabel, "fee_ratio",             str(db[3]), r.feeRatio);
                addIfChanged(feeDiffs, rowLabel, "settlement_rise_ratio", str(db[9]), r.settlementRiseRatio);
                addIfChanged(feeDiffs, rowLabel, "fixed_rise_value",      str(db[10]), r.fixedRiseValue);
                addIfChanged(feeDiffs, rowLabel, "reject_rate",           str(db[11]), r.rejectRate);
            }
            if (!feeDiffs.isEmpty()) result.put("fee", feeDiffs);
        }
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

    /** 当 DB 值与 Excel 值不相等时，向 diffs 添加一条 RowDiff
     *
     *  (2026-05-15) 必须用 BigDecimal.compareTo 做数值比较, 而不是文本 Objects.equals:
     *  V178 后 mat_bom.net_qty 是 numeric(20,10), 0.03 存为 0.0300000000;
     *  Excel 输入 0.03 转 BigDecimal 后 toPlainString="0.03".
     *  文本比较会把它们标为不等 → "0.0300000000 → 0.03" 假差异 → 误报升版.
     */
    private void addIfChanged(List<PartVersionDecisionItem.RowDiff> diffs, String rowKey,
                               String field, String dbVal, BigDecimal excelVal) {
        String excelStr = excelVal == null ? null : excelVal.toPlainString();
        if (eqDec(dbVal, excelVal)) return; // 数值等 → 不算差异
        diffs.add(new PartVersionDecisionItem.RowDiff(rowKey, field, dbVal, excelStr));
    }
}
