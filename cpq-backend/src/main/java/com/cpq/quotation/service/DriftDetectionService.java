package com.cpq.quotation.service;

import com.cpq.quotation.dto.DriftedRecordDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * DRAFT 漂移检测服务（v5.1 §6.6）。
 *
 * <p>设计选项 B：referencedVersions 存 业务键(hfPartNo|customerId) → version 映射；
 * 漂移检测直接查询当前 is_current=true 行的 version 进行比对。
 * 业务键作 key 更稳定，不受 recordId 随版本变化影响。
 *
 * <p>覆盖的版本化表: mat_process / mat_fee / mat_plating_fee / plating_fee / element_price.
 * V125: plating_fee 已弃用,新 referencedVersions 用 mat_plating_fee;旧 referencedVersions
 * 仍含 plating_fee key — 漂移检测保留该键防回归.
 *
 * <p>D-3（v5.1 遗留清理）：referencedVersions JSON 结构升级，每个业务键值由 int 改为
 * {@code {"version": N, "recordId": "uuid"}}，向后兼容旧格式 int（recordId=null）。
 */
@ApplicationScoped
public class DriftDetectionService {

    private static final Logger LOG = Logger.getLogger(DriftDetectionService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 支持漂移检测的表名集合（小写），及其业务键列定义。 */
    private static final Map<String, TableMeta> TABLE_META = Map.of(
            "mat_process",     new TableMeta("mat_process",     "hf_part_no",  "customer_id"),
            "mat_fee",         new TableMeta("mat_fee",         "hf_part_no",  "customer_id"),
            "plating_fee",     new TableMeta("plating_fee",     "hf_part_no",  "customer_id"),
            "mat_plating_fee", new TableMeta("mat_plating_fee", "hf_part_no",  "customer_id"),
            "element_price",   new TableMeta("element_price",   "element_name","customer_id")
    );

    @Inject
    EntityManager em;

    // ── 对外 API ─────────────────────────────────────────────────────────────

    /**
     * 漂移检测结果，包含是否存在漂移及漂移明细列表。
     */
    public record DriftDetectionResult(boolean hasDrift, List<DriftedRecordDTO> driftedRecords) {}

    /**
     * D-3：单条版本引用条目，包含版本号和记录 ID（用于前端版本对比 API）。
     *
     * <p>向后兼容：旧格式 int 解析时 recordId=null，version 正常解析。
     *
     * @param version  版本号
     * @param recordId 当前 is_current=true 行的主键 UUID（可为 null，旧数据兼容）
     */
    public record RefVersionEntry(int version, String recordId) {}

    /**
     * 检测 DRAFT 报价单的基础数据版本是否漂移。
     *
     * @param referencedVersionsJson Quotation.referencedVersions JSON 字符串
     * @return 漂移检测结果；若 json 为 null/空则返回 hasDrift=false
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public DriftDetectionResult detect(String referencedVersionsJson) {
        if (referencedVersionsJson == null || referencedVersionsJson.isBlank()) {
            return new DriftDetectionResult(false, List.of());
        }

        Map<String, Map<String, RefVersionEntry>> snapshot;
        try {
            snapshot = parseReferencedVersions(referencedVersionsJson);
        } catch (Exception e) {
            LOG.warnf("DriftDetectionService: failed to parse referencedVersions JSON: %s", e.getMessage());
            return new DriftDetectionResult(false, List.of());
        }

        List<DriftedRecordDTO> drifted = new ArrayList<>();

        for (Map.Entry<String, Map<String, RefVersionEntry>> tableEntry : snapshot.entrySet()) {
            String tableName = tableEntry.getKey().toLowerCase();
            Map<String, RefVersionEntry> businessKeyVersions = tableEntry.getValue();
            if (businessKeyVersions == null || businessKeyVersions.isEmpty()) continue;

            TableMeta meta = TABLE_META.get(tableName);
            if (meta == null) {
                LOG.warnf("DriftDetectionService: unknown table '%s' in referencedVersions, skipping", tableName);
                continue;
            }

            drifted.addAll(detectTableDrift(meta, businessKeyVersions));
        }

        return new DriftDetectionResult(!drifted.isEmpty(), drifted);
    }

    /**
     * 收集报价单涉及的所有版本化基础数据，生成 referencedVersions JSON 字符串（新格式）。
     *
     * <p>D-3：新格式每条记录为 {@code {"version": N, "recordId": "uuid"}}。
     * 调用场景：saveDraft 或 refreshVersions 时，通过 customerId + hfPartNoList 定位当前 is_current 行。
     *
     * @param customerId   报价客户 UUID
     * @param hfPartNos    报价行项目涉及的 hf_part_no 列表
     * @return JSON 字符串（若无数据则返回 null）
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public String collectReferencedVersions(UUID customerId, List<String> hfPartNos) {
        if (customerId == null || hfPartNos == null || hfPartNos.isEmpty()) {
            return null;
        }

        Map<String, Map<String, Map<String, Object>>> result = new LinkedHashMap<>();

        // V125: 新 referencedVersions 不再包含 plating_fee, 改写 mat_plating_fee.
        for (String tableName : List.of("mat_process", "mat_fee", "mat_plating_fee")) {
            TableMeta meta = TABLE_META.get(tableName);
            Map<String, Map<String, Object>> versions = queryCurrentVersionEntries(meta, customerId, hfPartNos);
            if (!versions.isEmpty()) {
                result.put(tableName, versions);
            }
        }

        // element_price 业务键是 element_name，不直接关联 hf_part_no
        // 通过 mat_bom 找 element_name（ELEMENT 类型）
        List<String> elementNames = queryElementNamesByPartNos(hfPartNos);
        if (!elementNames.isEmpty()) {
            TableMeta epMeta = TABLE_META.get("element_price");
            Map<String, Map<String, Object>> epVersions = queryElementPriceEntries(epMeta, customerId, elementNames);
            if (!epVersions.isEmpty()) {
                result.put("element_price", epVersions);
            }
        }

        if (result.isEmpty()) return null;

        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            LOG.warnf("DriftDetectionService: failed to serialize referencedVersions: %s", e.getMessage());
            return null;
        }
    }

    /**
     * D-3：解析 referencedVersions JSON，兼容新格式（object）和旧格式（int）。
     *
     * <p>新格式：{@code {"mat_process": {"bk": {"version": 2, "recordId": "uuid"}}}}
     * <p>旧格式：{@code {"mat_process": {"bk": 2}}} → 解析为 RefVersionEntry(version=2, recordId=null)
     *
     * @param json referencedVersions JSON 字符串
     * @return 表名 → (业务键 → RefVersionEntry) 映射
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, RefVersionEntry>> parseReferencedVersions(String json) {
        Map<String, Map<String, RefVersionEntry>> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;

        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warnf("DriftDetectionService.parseReferencedVersions: invalid JSON: %s", e.getMessage());
            return result;
        }

        for (Map.Entry<String, Object> tableEntry : raw.entrySet()) {
            String tableName = tableEntry.getKey();
            Object tableVal = tableEntry.getValue();
            if (!(tableVal instanceof Map<?, ?> tableMap)) continue;

            Map<String, RefVersionEntry> bkMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> bkEntry : tableMap.entrySet()) {
                String bk = bkEntry.getKey().toString();
                Object val = bkEntry.getValue();

                if (val instanceof Number num) {
                    // 旧格式：int → RefVersionEntry(version=N, recordId=null)
                    bkMap.put(bk, new RefVersionEntry(num.intValue(), null));
                } else if (val instanceof Map<?, ?> entryMap) {
                    // 新格式：{"version": N, "recordId": "uuid"}
                    Object versionVal = entryMap.get("version");
                    Object recordIdVal = entryMap.get("recordId");
                    int version = versionVal instanceof Number ? ((Number) versionVal).intValue() : 0;
                    String recordId = recordIdVal != null ? recordIdVal.toString() : null;
                    bkMap.put(bk, new RefVersionEntry(version, recordId));
                } else {
                    LOG.warnf("DriftDetectionService.parseReferencedVersions: unexpected value type for bk='%s': %s",
                            bk, val == null ? "null" : val.getClass().getSimpleName());
                }
            }
            if (!bkMap.isEmpty()) result.put(tableName, bkMap);
        }

        return result;
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * 针对单张表检测漂移（D-3 升级版：使用 RefVersionEntry）。
     */
    @SuppressWarnings("unchecked")
    private List<DriftedRecordDTO> detectTableDrift(TableMeta meta, Map<String, RefVersionEntry> businessKeyVersions) {
        List<DriftedRecordDTO> drifted = new ArrayList<>();

        List<Object[]> keyPairs = new ArrayList<>();
        Map<String, RefVersionEntry> keyToEntry = new HashMap<>();

        for (Map.Entry<String, RefVersionEntry> entry : businessKeyVersions.entrySet()) {
            String bk = entry.getKey();
            int pipeIdx = bk.lastIndexOf('|');
            if (pipeIdx < 0) {
                LOG.warnf("DriftDetectionService: invalid business key '%s' for table '%s'", bk, meta.tableName);
                continue;
            }
            String partKeyVal = bk.substring(0, pipeIdx);
            String customerIdStr = bk.substring(pipeIdx + 1);
            keyPairs.add(new Object[]{partKeyVal, customerIdStr, bk});
            keyToEntry.put(bk, entry.getValue());
        }

        if (keyPairs.isEmpty()) return drifted;

        for (Object[] kp : keyPairs) {
            String partKeyVal = (String) kp[0];
            String customerIdStr = (String) kp[1];
            String bk = (String) kp[2];
            RefVersionEntry refEntry = keyToEntry.get(bk);
            int refVersion = refEntry.version();

            UUID customerId;
            try {
                customerId = UUID.fromString(customerIdStr);
            } catch (IllegalArgumentException e) {
                LOG.warnf("DriftDetectionService: invalid customerId '%s' in business key", customerIdStr);
                continue;
            }

            String sql = "SELECT MAX(version) FROM " + meta.tableName
                    + " WHERE is_current = true AND customer_id = :cid AND "
                    + meta.partKeyColumn + " = :partKey";

            List<Object> rows = em.createNativeQuery(sql)
                    .setParameter("cid", customerId)
                    .setParameter("partKey", partKeyVal)
                    .getResultList();

            if (rows == null || rows.isEmpty() || rows.get(0) == null) {
                LOG.debugf("DriftDetectionService: no current row for %s bk='%s', skipping drift", meta.tableName, bk);
                continue;
            }

            int currentVersion = ((Number) rows.get(0)).intValue();
            if (currentVersion != refVersion) {
                drifted.add(new DriftedRecordDTO(
                        meta.tableName, bk, refVersion, currentVersion, partKeyVal));
                LOG.infof("DriftDetectionService: drift detected on %s bk='%s' refVer=%d curVer=%d",
                        meta.tableName, bk, refVersion, currentVersion);
            }
        }

        return drifted;
    }

    /**
     * D-3：查询指定 customerId + hfPartNos 下各业务键的当前版本 + recordId。
     * 业务键格式：<hfPartNo>|<customerId>
     * 返回 Map&lt;bk, Map{version, recordId}&gt;（直接用于 JSON 序列化）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> queryCurrentVersionEntries(
            TableMeta meta, UUID customerId, List<String> hfPartNos) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        String sql = "SELECT " + meta.partKeyColumn + ", MAX(version), (array_agg(id ORDER BY version DESC))[1]::text"
                + " FROM " + meta.tableName
                + " WHERE is_current = true AND customer_id = :cid"
                + " AND " + meta.partKeyColumn + " = ANY(:partNos)"
                + " GROUP BY " + meta.partKeyColumn;

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                    .setParameter("cid", customerId)
                    .setParameter("partNos", hfPartNos.toArray(new String[0]))
                    .getResultList();
        } catch (Exception e) {
            // array_agg 不支持时降级（H2 等测试 DB）
            LOG.debugf("DriftDetectionService: array_agg query failed, falling back: %s", e.getMessage());
            return queryCurrentVersionEntriesFallback(meta, customerId, hfPartNos);
        }

        for (Object[] row : rows) {
            String partKey = (String) row[0];
            int version = ((Number) row[1]).intValue();
            String recordId = row[2] != null ? row[2].toString() : null;
            String bk = partKey + "|" + customerId;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", version);
            entry.put("recordId", recordId);
            result.put(bk, entry);
        }

        return result;
    }

    /**
     * 降级实现：不查 recordId（H2/SQLite 等不支持 array_agg）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> queryCurrentVersionEntriesFallback(
            TableMeta meta, UUID customerId, List<String> hfPartNos) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        String sql = "SELECT " + meta.partKeyColumn + ", MAX(version) FROM " + meta.tableName
                + " WHERE is_current = true AND customer_id = :cid"
                + " AND " + meta.partKeyColumn + " = ANY(:partNos)"
                + " GROUP BY " + meta.partKeyColumn;

        try {
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter("cid", customerId)
                    .setParameter("partNos", hfPartNos.toArray(new String[0]))
                    .getResultList();

            for (Object[] row : rows) {
                String partKey = (String) row[0];
                int version = ((Number) row[1]).intValue();
                String bk = partKey + "|" + customerId;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("version", version);
                entry.put("recordId", null);
                result.put(bk, entry);
            }
        } catch (Exception e) {
            LOG.warnf("DriftDetectionService: fallback query also failed for %s: %s", meta.tableName, e.getMessage());
        }

        return result;
    }

    /**
     * 通过 element_bom_item（V6 替代 mat_bom）查询 hfPartNos 涉及的 element_name 列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> queryElementNamesByPartNos(List<String> hfPartNos) {
        if (hfPartNos.isEmpty()) return List.of();
        String sql = "SELECT DISTINCT component_no AS element_name FROM element_bom_item"
                + " WHERE system_type='QUOTE' AND is_current = true AND hf_part_no = ANY(:partNos)"
                + " AND component_no IS NOT NULL";
        return em.createNativeQuery(sql)
                .setParameter("partNos", hfPartNos.toArray(new String[0]))
                .getResultList();
    }

    /**
     * D-3：查询 element_price 的当前版本快照（含 recordId）。
     * 业务键格式：<elementName>|<customerId>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> queryElementPriceEntries(
            TableMeta meta, UUID customerId, List<String> elementNames) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        String sql = "SELECT " + meta.partKeyColumn + ", MAX(version), (array_agg(id ORDER BY version DESC))[1]::text"
                + " FROM " + meta.tableName
                + " WHERE is_current = true AND customer_id = :cid"
                + " AND " + meta.partKeyColumn + " = ANY(:names)"
                + " GROUP BY " + meta.partKeyColumn;

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                    .setParameter("cid", customerId)
                    .setParameter("names", elementNames.toArray(new String[0]))
                    .getResultList();
        } catch (Exception e) {
            LOG.debugf("DriftDetectionService: element_price array_agg failed, falling back: %s", e.getMessage());
            return queryElementPriceEntriesFallback(meta, customerId, elementNames);
        }

        for (Object[] row : rows) {
            String elemName = (String) row[0];
            int version = ((Number) row[1]).intValue();
            String recordId = row[2] != null ? row[2].toString() : null;
            String bk = elemName + "|" + customerId;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", version);
            entry.put("recordId", recordId);
            result.put(bk, entry);
        }

        return result;
    }

    /**
     * 降级实现：element_price 不查 recordId。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> queryElementPriceEntriesFallback(
            TableMeta meta, UUID customerId, List<String> elementNames) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        String sql = "SELECT " + meta.partKeyColumn + ", MAX(version) FROM " + meta.tableName
                + " WHERE is_current = true AND customer_id = :cid"
                + " AND " + meta.partKeyColumn + " = ANY(:names)"
                + " GROUP BY " + meta.partKeyColumn;

        try {
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter("cid", customerId)
                    .setParameter("names", elementNames.toArray(new String[0]))
                    .getResultList();

            for (Object[] row : rows) {
                String elemName = (String) row[0];
                int version = ((Number) row[1]).intValue();
                String bk = elemName + "|" + customerId;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("version", version);
                entry.put("recordId", null);
                result.put(bk, entry);
            }
        } catch (Exception e) {
            LOG.warnf("DriftDetectionService: element_price fallback also failed: %s", e.getMessage());
        }

        return result;
    }

    // ── 内部类 ────────────────────────────────────────────────────────────────

    /**
     * 版本化表元数据。
     *
     * @param tableName     表名
     * @param partKeyColumn 业务键列名（partNo 类或 elementName）
     * @param customerIdCol 客户ID列名（通常为 customer_id）
     */
    private record TableMeta(String tableName, String partKeyColumn, String customerIdCol) {}
}
