package com.cpq.quotation.snapshot;

import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.refdata.GlobalVariableDataLoader;
import com.cpq.quotation.service.DriftDetectionService;
import com.cpq.template.entity.TemplateGlobalVariableBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * SnapshotCollectorService — v5.1 §10 报价提交快照收集器。
 *
 * <p>在 DRAFT→SUBMITTED 状态转换时，冻结以下数据到 submission_snapshot JSONB：
 * <ul>
 *   <li>referencedVersions: 复用 quotation.referenced_versions 内容</li>
 *   <li>elementActualPrices: 从所有 LineComponentData.row_data 提取 element_actual_* 字段</li>
 *   <li>formulaDefinitions: 当前活跃的 derived_attribute 公式表达式</li>
 *   <li>masterDataSnapshot: mat_part/mat_bom/plating_plan/mat_customer_part_mapping 关联行的 KV</li>
 *   <li>snapshotAt: 快照时间 ISO 8601</li>
 * </ul>
 */
@ApplicationScoped
public class SnapshotCollectorService {

    private static final Logger LOG = Logger.getLogger(SnapshotCollectorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    DriftDetectionService driftDetectionService;

    /**
     * V104: 懒注入全局变量服务. 用 Instance 包装避免 quotation.snapshot 包硬依赖 globalvariable 包。
     * 报价提交时, 扫公式中的 global_variable token, 把当前生效值落到快照, 防止后续维护变动追溯改写已审核报价。
     */
    @Inject
    Instance<GlobalVariableService> globalVariableServiceRef;

    /**
     * V212: 全局变量全表数据加载器 (ADR-002 §3.4 引用数据快照).
     * 用 Instance 包装避免循环依赖风险; collect() 末尾调用.
     */
    @Inject
    Instance<GlobalVariableDataLoader> globalVariableDataLoaderRef;

    /**
     * 提交快照 DTO — 对应 submission_snapshot JSONB 的完整结构。
     *
     * @param referencedVersions  JSON 字符串，复用 V53 referenced_versions 内容
     * @param elementActualPrices 从所有 LineComponentData.row_data 提取 element_actual_* 字段
     * @param formulaDefinitions  提交瞬间的 derived_attribute 公式表达式
     * @param masterDataSnapshot  4 张全局表 KV
     * @param templateConfigs     被引用的模板"展示配置"快照（excelViewConfig/componentsSnapshot/columns）
     *                            按 templateId 索引；用于审计模式还原列结构与公式定义
     * @param comparisonTags      提交瞬间的 ComparisonTag 元数据（code → label/groupName）
     *                            标签后续被改名 / 禁用时，老报价仍能正确显示比对组与标签
     * @param snapshotAt          ISO 8601 时间戳
     */
    public record SubmissionSnapshot(
            Object referencedVersions,
            Map<String, Object> elementActualPrices,
            List<Map<String, Object>> formulaDefinitions,
            Map<String, Object> masterDataSnapshot,
            Map<String, Object> templateConfigs,
            Map<String, Object> comparisonTags,
            /** V104: 公式中引用的全局变量值快照, key = "<varCode>::<keyValuesJson>" → value */
            Map<String, Object> globalVariables,
            String snapshotAt
    ) {}

    /**
     * 收集指定报价单的提交快照。
     *
     * @param quotationId 报价单 ID
     * @param referencedVersionsJson quotation.referenced_versions 原始 JSON（可为 null）
     * @param customerId  报价客户 ID（用于查询 master data）
     * @return SubmissionSnapshot record
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public SubmissionSnapshot collect(UUID quotationId, String referencedVersionsJson, UUID customerId) {

        // 1. referencedVersions — 直接复用已存在的 referenced_versions 快照
        Object referencedVersions = parseJsonOrRaw(referencedVersionsJson);

        // 2. elementActualPrices — 遍历 LineComponentData.row_data 抓 element_actual_* 字段
        Map<String, Object> elementActualPrices = collectElementActualPrices(quotationId);

        // 3. formulaDefinitions — 查询当前活跃的 derived_attribute 记录（v1 取全部 ACTIVE）
        List<Map<String, Object>> formulaDefinitions = collectFormulaDefinitions();

        // 4. masterDataSnapshot — 收集本报价单涉及的基础数据行
        Map<String, Object> masterDataSnapshot = collectMasterDataSnapshot(quotationId, customerId);

        // 5. templateConfigs — 冻结被引用模板的展示配置，避免日后模板被编辑后老报价导出/重渲染漂移
        Map<String, Object> templateConfigs = collectTemplateConfigs(quotationId);

        // 6. comparisonTags — 冻结当前 ACTIVE 的 ComparisonTag 元数据，
        //    防止 tag 后续被改名 / 禁用时老报价比对视图标签错位
        Map<String, Object> comparisonTags = collectComparisonTags();

        // 7. globalVariables — V104: 扫报价单组件公式 token 中的 global_variable, 当前生效值入快照
        Map<String, Object> globalVariables = collectGlobalVariables(quotationId);

        // 8. snapshotAt
        String snapshotAt = OffsetDateTime.now().toString();

        // 9. V212: bound_global_variables_snapshot — 冻结引用数据全表到独立列
        //    只在末尾追加, 不改上方 1-8 步逻辑, 不写入 submission_snapshot (ADR-002 决策点 3).
        writeBoundGlobalVariablesSnapshot(quotationId, snapshotAt);

        return new SubmissionSnapshot(
                referencedVersions,
                elementActualPrices,
                formulaDefinitions,
                masterDataSnapshot,
                templateConfigs,
                comparisonTags,
                globalVariables,
                snapshotAt
        );
    }

    /**
     * 将 SubmissionSnapshot 序列化为 JSON 字符串（写入 quotation.submission_snapshot）。
     */
    public String toJson(SubmissionSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: failed to serialize SubmissionSnapshot: %s", e.getMessage());
            // 降级：返回只含 snapshotAt 的简单 JSON
            return "{\"snapshotAt\":\"" + snapshot.snapshotAt() + "\",\"error\":\"serialization_failed\"}";
        }
    }

    // ── 私有收集方法 ──────────────────────────────────────────────────────────

    /**
     * 遍历所有 LineComponentData.row_data，提取 key 以 "element_actual_" 开头的字段。
     * 返回格式：{ "<lineItemId>.<componentId>.<key>": value, ... }
     */
    private Map<String, Object> collectElementActualPrices(UUID quotationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<QuotationLineItem> lineItems = QuotationLineItem.list(
                    "quotationId = ?1 ORDER BY sortOrder ASC", quotationId);

            for (QuotationLineItem li : lineItems) {
                List<QuotationLineComponentData> compDataList = QuotationLineComponentData.list(
                        "lineItemId = ?1 ORDER BY sortOrder ASC", li.id);

                for (QuotationLineComponentData cd : compDataList) {
                    if (cd.rowData == null || cd.rowData.isBlank() || "[]".equals(cd.rowData.trim())) {
                        continue;
                    }
                    try {
                        // row_data 是 JSON array，每项是一个 row 的字段 map
                        List<Map<String, Object>> rows = MAPPER.readValue(cd.rowData,
                                new TypeReference<List<Map<String, Object>>>() {});
                        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                            Map<String, Object> row = rows.get(rowIdx);
                            for (Map.Entry<String, Object> entry : row.entrySet()) {
                                if (entry.getKey().startsWith("element_actual_")) {
                                    String compositeKey = li.id + "." + cd.id + ".row" + rowIdx + "." + entry.getKey();
                                    result.put(compositeKey, entry.getValue());
                                }
                            }
                        }
                    } catch (Exception e) {
                        // row_data 也可能是 JSON object（单行）
                        try {
                            Map<String, Object> singleRow = MAPPER.readValue(cd.rowData,
                                    new TypeReference<Map<String, Object>>() {});
                            for (Map.Entry<String, Object> entry : singleRow.entrySet()) {
                                if (entry.getKey().startsWith("element_actual_")) {
                                    String compositeKey = li.id + "." + cd.id + ".row0." + entry.getKey();
                                    result.put(compositeKey, entry.getValue());
                                }
                            }
                        } catch (Exception ignore) {
                            LOG.debugf("SnapshotCollectorService: row_data parse failed for cd=%s: %s",
                                    cd.id, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: collectElementActualPrices failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * 查询当前活跃的 derived_attribute 公式定义（ACTIVE 状态）。
     * 返回每条记录的 variableCode / variableLabel / computationType / computation 字段。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectFormulaDefinitions() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<DerivedAttribute> attrs = DerivedAttribute.list("status = 'ACTIVE' ORDER BY sortOrder ASC");
            for (DerivedAttribute attr : attrs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", attr.id.toString());
                m.put("variableCode", attr.variableCode);
                m.put("variableLabel", attr.variableLabel);
                m.put("computationType", attr.computationType);
                m.put("computation", parseJsonOrRaw(attr.computation));
                m.put("dataType", attr.dataType);
                result.add(m);
            }
        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: collectFormulaDefinitions failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * 收集本报价单涉及的基础数据行快照（mat_part / mat_bom / plating_plan / mat_customer_part_mapping）。
     *
     * <p>通过 product → partNo 关联查询，收集当前 is_current=true 行的字段值。
     * 格式：{ "mat_part": { "<partNo>": { <字段KV> } }, "mat_bom": {...}, ... }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> collectMasterDataSnapshot(UUID quotationId, UUID customerId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        try {
            // 收集涉及的 hf_part_no 列表
            List<String> partNos = collectPartNos(quotationId);
            if (partNos.isEmpty()) return snapshot;

            // material_master — V6 替代 mat_part（按 material_no = hf_part_no 查询）
            Map<String, Object> matPartMap = new LinkedHashMap<>();
            List<Object[]> matPartRows = em.createNativeQuery(
                    "SELECT material_no AS hf_part_no, material_name AS part_name, material_type AS material, " +
                    "unit_weight, standard_unit AS unit FROM material_master" +
                    " WHERE material_no = ANY(:partNos)")
                    .setParameter("partNos", partNos.toArray(new String[0]))
                    .getResultList();
            for (Object[] row : matPartRows) {
                String pn = (String) row[0];
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("part_name", row[1]);
                fields.put("material", row[2]);
                fields.put("unit_weight", row[3]);
                fields.put("unit", row[4]);
                matPartMap.put(pn, fields);
            }
            if (!matPartMap.isEmpty()) snapshot.put("mat_part", matPartMap);

            // element_bom_item — V6 替代 mat_bom，含 is_current + 最新 characteristic 过滤
            Map<String, Object> matBomMap = new LinkedHashMap<>();
            List<Object[]> matBomRows = em.createNativeQuery(
                    "SELECT hf_part_no, 'ELEMENT' AS bom_type, component_no AS element_name, content AS quantity " +
                    "FROM element_bom_item ebi WHERE ebi.system_type='QUOTE' AND ebi.is_current = true " +
                    "  AND ebi.hf_part_no = ANY(:partNos) " +
                    "  AND ebi.characteristic = (SELECT MAX(c.characteristic) FROM element_bom_item c " +
                    "     WHERE c.is_current=true AND c.customer_no=ebi.customer_no AND c.material_no=ebi.material_no)")
                    .setParameter("partNos", partNos.toArray(new String[0]))
                    .getResultList();
            for (Object[] row : matBomRows) {
                String key = row[0] + "|" + row[1] + "|" + row[2];
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("hf_part_no", row[0]);
                fields.put("bom_type", row[1]);
                fields.put("element_name", row[2]);
                fields.put("quantity", row[3]);
                matBomMap.put(key, fields);
            }
            if (!matBomMap.isEmpty()) snapshot.put("mat_bom", matBomMap);

            // plating_plan — 按 hf_part_no 查询当前版本
            Map<String, Object> platingMap = new LinkedHashMap<>();
            List<Object[]> platingRows = em.createNativeQuery(
                    "SELECT hf_part_no, plating_type, thickness, version FROM plating_plan" +
                    " WHERE is_current = true AND hf_part_no = ANY(:partNos)")
                    .setParameter("partNos", partNos.toArray(new String[0]))
                    .getResultList();
            for (Object[] row : platingRows) {
                String key = (String) row[0];
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("plating_type", row[1]);
                fields.put("thickness", row[2]);
                fields.put("version", row[3]);
                platingMap.put(key, fields);
            }
            if (!platingMap.isEmpty()) snapshot.put("plating_plan", platingMap);

            // mat_customer_part_mapping — 按 customerId + hf_part_no 查询
            if (customerId != null) {
                Map<String, Object> mappingMap = new LinkedHashMap<>();
                List<Object[]> mappingRows = em.createNativeQuery(
                        "SELECT hf_part_no, customer_part_no, customer_part_name FROM mat_customer_part_mapping" +
                        " WHERE customer_id = :cid AND hf_part_no = ANY(:partNos)")
                        .setParameter("cid", customerId)
                        .setParameter("partNos", partNos.toArray(new String[0]))
                        .getResultList();
                for (Object[] row : mappingRows) {
                    String key = customerId + "|" + row[0];
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("hf_part_no", row[0]);
                    fields.put("customer_part_no", row[1]);
                    fields.put("customer_part_name", row[2]);
                    mappingMap.put(key, fields);
                }
                if (!mappingMap.isEmpty()) snapshot.put("mat_customer_part_mapping", mappingMap);
            }

        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: collectMasterDataSnapshot failed: %s", e.getMessage());
        }
        return snapshot;
    }

    /**
     * 收集本报价单引用的所有模板的"展示配置"快照——主要为 excelViewConfig 与 componentsSnapshot。
     * 这两份配置决定提交时的列结构、公式定义、组件配置；后续模板版本被编辑会让老报价导出错列。
     *
     * <p>格式：{ "<templateId>": { "name": ..., "version": ..., "excelViewConfig": <obj>, "componentsSnapshot": <obj>, "subtotalFormula": <obj> } }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> collectTemplateConfigs(UUID quotationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT DISTINCT t.id, t.name, t.version, t.excel_view_config, " +
                    "       t.components_snapshot, t.subtotal_formula " +
                    "FROM quotation_line_item li" +
                    " JOIN template t ON t.id = li.template_id" +
                    " WHERE li.quotation_id = :qid")
                    .setParameter("qid", quotationId)
                    .getResultList();
            for (Object[] r : rows) {
                if (r == null || r[0] == null) continue;
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("name", r[1]);
                tc.put("version", r[2]);
                tc.put("excelViewConfig", parseJsonOrRaw(r[3] != null ? r[3].toString() : null));
                tc.put("componentsSnapshot", parseJsonOrRaw(r[4] != null ? r[4].toString() : null));
                tc.put("subtotalFormula", parseJsonOrRaw(r[5] != null ? r[5].toString() : null));
                result.put(r[0].toString(), tc);
            }
        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: collectTemplateConfigs failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * 冻结当前所有 ACTIVE 的 ComparisonTag 元数据。
     * <p>格式：{ "<tag_code>": { "label": ..., "groupName": ..., "sortOrder": ... } }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> collectComparisonTags() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT code, label, group_name, group_sort_order, tag_sort_order " +
                    "FROM comparison_tag WHERE status = 'ACTIVE' " +
                    "ORDER BY group_sort_order ASC, tag_sort_order ASC")
                    .getResultList();
            for (Object[] r : rows) {
                if (r == null || r[0] == null) continue;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("label", r[1]);
                meta.put("groupName", r[2]);
                meta.put("groupSortOrder", r[3]);
                meta.put("tagSortOrder", r[4]);
                result.put(r[0].toString(), meta);
            }
        } catch (Exception e) {
            LOG.warnf("SnapshotCollectorService: collectComparisonTags failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * 从报价单 LineItem → Product 收集 hf_part_no 列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> collectPartNos(UUID quotationId) {
        try {
            List<Object> rows = em.createNativeQuery(
                    "SELECT DISTINCT p.part_no FROM quotation_line_item li" +
                    " JOIN product p ON p.id = li.product_id" +
                    " WHERE li.quotation_id = :qid AND p.part_no IS NOT NULL")
                    .setParameter("qid", quotationId)
                    .getResultList();
            List<String> result = new ArrayList<>();
            for (Object r : rows) {
                if (r != null) result.add(r.toString());
            }
            return result;
        } catch (Exception e) {
            LOG.debugf("SnapshotCollectorService: collectPartNos failed: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * 尝试将 JSON 字符串解析为 Object（Map/List），解析失败时原样返回字符串。
     */
    private Object parseJsonOrRaw(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * V104: 扫报价单的组件公式 (components_snapshot.formulas + 各 LineComponentData 的 formulas),
     * 收集所有 type=global_variable token 的 (code, key) 对, 在当前时刻去 GlobalVariableService 解值,
     * 落到快照. 对动态 key (key_field_refs), 当前时刻没有 row 上下文, 退化为"枚举所有候选 key" — v1
     * 简化只快照"静态 key"; 动态 key 的快照值在求值时按行 row_data 解出, 写到对应 cd.row_data 字段
     * (已经在 elementActualPrices 一类机制里做了部分覆盖)。后续 P3b 补全。
     *
     * 返回结构: { "<code>::<col1>=<v1>;<col2>=<v2>": numeric_value, ... }
     */
    private Map<String, Object> collectGlobalVariables(UUID quotationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!globalVariableServiceRef.isResolvable()) return out;
        GlobalVariableService gvSvc = globalVariableServiceRef.get();

        try {
            // 报价单引用的所有模板 components_snapshot 都要扫.
            // 模板可能多个 (line_item.template_id 不同), 用 DISTINCT.
            List<?> rawList = em.createNativeQuery(
                    "SELECT DISTINCT t.components_snapshot FROM template t " +
                    " JOIN quotation_line_item li ON li.template_id = t.id " +
                    "WHERE li.quotation_id = :qid AND t.components_snapshot IS NOT NULL")
                    .setParameter("qid", quotationId)
                    .getResultList();

            Map<String, GlobalVariableDefinition> defCache = new HashMap<>();
            Set<String> seenKeys = new HashSet<>();

            for (Object snapRaw : rawList) {
                if (snapRaw == null) continue;
                String snapJson = snapRaw.toString();
                if (snapJson.isBlank() || "[]".equals(snapJson.trim())) continue;

                List<Map<String, Object>> snapshot;
                try {
                    snapshot = MAPPER.readValue(snapJson, new TypeReference<>() {});
                } catch (Exception e) { continue; }

                for (Map<String, Object> compEntry : snapshot) {
                    Object formulasRaw = compEntry.get("formulas");
                    if (!(formulasRaw instanceof List<?> formulas)) continue;
                    for (Object f : formulas) {
                        if (!(f instanceof Map<?, ?> formula)) continue;
                        Object expr = formula.get("expression");
                        if (!(expr instanceof List<?> tokens)) continue;
                        for (Object tok : tokens) {
                            if (!(tok instanceof Map<?, ?> tokMap)) continue;
                            if (!"global_variable".equals(tokMap.get("type"))) continue;

                            String code = (String) tokMap.get("code");
                            if (code == null || code.isBlank()) continue;

                            @SuppressWarnings("unchecked")
                            Map<String, Object> staticKeys = (Map<String, Object>) tokMap.get("key_values");
                            if (staticKeys == null || staticKeys.isEmpty()) continue;  // 动态 key v1 跳过

                            GlobalVariableDefinition def = defCache.computeIfAbsent(code,
                                    c -> gvSvc.getByCode(c).orElse(null));
                            if (def == null) continue;

                            String keyId = code + "::" + staticKeys.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .reduce((a, b) -> a + ";" + b).orElse("");
                            if (!seenKeys.add(keyId)) continue;

                            try {
                                BigDecimal v = gvSvc.resolveValue(code, staticKeys);
                                out.put(keyId, v);
                            } catch (Exception ignore) {
                                out.put(keyId, null);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("collectGlobalVariables failed: %s", e.getMessage());
        }
        return out;
    }

    // ── V212: 引用数据快照 (ADR-002 §3.4) ────────────────────────────────────

    /**
     * 将报价单引用的模板所有绑定全局变量数据快照写入 quotation.bound_global_variables_snapshot 列.
     *
     * <p>仅在末尾追加, 不改 submission_snapshot (ADR-002 决策点 3).
     * 写入失败只记录警告, 不阻断整体提交流程 (降级语义: 快照为空 → 前端显示"提交时未生成快照").
     *
     * @param quotationId 报价单 ID
     * @param snapshotAt  ISO 8601 快照时间 (与 SubmissionSnapshot.snapshotAt 对齐)
     */
    private void writeBoundGlobalVariablesSnapshot(UUID quotationId, String snapshotAt) {
        if (!globalVariableDataLoaderRef.isResolvable()) {
            LOG.warnf("[V212 snapshot] GlobalVariableDataLoader not resolvable, skip for qid=%s", quotationId);
            return;
        }
        try {
            String snapshotJson = collectBoundGlobalVariablesSnapshot(quotationId, snapshotAt);
            em.createNativeQuery(
                    "UPDATE quotation SET bound_global_variables_snapshot = CAST(:snap AS jsonb) WHERE id = :qid")
                    .setParameter("snap", snapshotJson)
                    .setParameter("qid", quotationId)
                    .executeUpdate();
            LOG.infof("[V212 snapshot] wrote bound_global_variables_snapshot for qid=%s, json-len=%d",
                    quotationId, snapshotJson.length());
        } catch (Exception e) {
            LOG.warnf("[V212 snapshot] writeBoundGlobalVariablesSnapshot failed for qid=%s: %s",
                    quotationId, e.getMessage());
        }
    }

    /**
     * 构建 bound_global_variables_snapshot JSONB 字符串.
     *
     * <p>流程:
     * <ol>
     *   <li>查报价单关联模板 (customer_template_id)</li>
     *   <li>查该模板的绑定列表 (template_global_variable_binding)</li>
     *   <li>对每个绑定调 GlobalVariableDataLoader.loadAllRows</li>
     *   <li>组装 ADR-002 §3.2 JSONB 结构并序列化</li>
     * </ol>
     *
     * @param quotationId 报价单 ID
     * @param snapshotAt  ISO 8601 时间字符串
     * @return JSONB 字符串 (永不返 null, 失败时返 '[]')
     */
    private String collectBoundGlobalVariablesSnapshot(UUID quotationId, String snapshotAt) {
        GlobalVariableDataLoader loader = globalVariableDataLoaderRef.get();
        GlobalVariableService gvSvc = globalVariableServiceRef.isResolvable()
                ? globalVariableServiceRef.get() : null;

        try {
            // 1. 查报价单关联模板 ID
            @SuppressWarnings("unchecked")
            List<Object> templateIdRows = em.createNativeQuery(
                    "SELECT customer_template_id FROM quotation WHERE id = :qid")
                    .setParameter("qid", quotationId)
                    .getResultList();

            if (templateIdRows.isEmpty() || templateIdRows.get(0) == null) {
                return "[]";
            }
            UUID templateId = templateIdRows.get(0) instanceof UUID u
                    ? u : UUID.fromString(templateIdRows.get(0).toString());

            // 2. 查绑定列表
            List<TemplateGlobalVariableBinding> bindings = TemplateGlobalVariableBinding.list(
                    "templateId = ?1 ORDER BY displayOrder ASC", templateId);

            if (bindings.isEmpty()) {
                return "[]";
            }

            // 3. 遍历绑定, 构建快照元素
            List<Map<String, Object>> snapshotItems = new ArrayList<>(bindings.size());
            for (TemplateGlobalVariableBinding binding : bindings) {
                try {
                    // 加载 GV 定义 (用于 name / varType / unit / columns)
                    com.cpq.globalvariable.GlobalVariableDefinition def = gvSvc != null
                            ? gvSvc.getByCode(binding.globalVariableCode).orElse(null)
                            : null;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", binding.globalVariableCode);
                    item.put("name", def != null ? def.name : binding.globalVariableCode);
                    item.put("varType", def != null ? def.varType : null);
                    item.put("unit", def != null ? def.unit : null);
                    item.put("displayOrder", binding.displayOrder);
                    item.put("snapshotAt", snapshotAt);

                    // columns
                    List<String> columns = def != null ? loader.buildColumns(def) : List.of();
                    item.put("columns", columns);

                    // rows
                    List<Map<String, Object>> rows = loader.loadAllRows(binding.globalVariableCode);
                    item.put("rows", rows);

                    snapshotItems.add(item);
                } catch (Exception e) {
                    LOG.warnf("[V212 snapshot] failed to load GV code=%s for qid=%s: %s",
                            binding.globalVariableCode, quotationId, e.getMessage());
                }
            }

            return MAPPER.writeValueAsString(snapshotItems);
        } catch (Exception e) {
            LOG.warnf("[V212 snapshot] collectBoundGlobalVariablesSnapshot failed for qid=%s: %s",
                    quotationId, e.getMessage());
            return "[]";
        }
    }
}
