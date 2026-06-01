package com.cpq.quotation.service;

import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 报价单整份快照 Phase 1 — 报价单级 4 份结构快照 + 产品行级 4 份值快照。
 *
 * <p><b>核心职责</b>:
 * <ul>
 *   <li>{@link #ensureStructure(UUID)} — 首次加产品时固定 4 份视图结构（创建即冻，不覆盖）</li>
 *   <li>{@link #snapshotLineValues(com.cpq.quotation.entity.QuotationLineItem)} — 对每行算四份初始值</li>
 * </ul>
 *
 * <p><b>设计 §1.4 收口纪律</b>: {@code buildCardValues} 必须复用 ConfigureSnapshotService
 * 的展开结果，不另跑 expand（Phase 1 写架构，Task 6/7 接入时实现）。
 *
 * <p><b>AP-39</b>: DATA_SOURCE 字段的 {@code datasource_binding} 必须完整搬运，不能丢。
 */
@ApplicationScoped
public class CardSnapshotService {

    private static final Logger LOG = Logger.getLogger(CardSnapshotService.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    /** 自注入：触发 REQUIRES_NEW 代理拦截器 */
    @Inject
    CardSnapshotService self;

    // =========================================================================
    // ensureStructure — 4 份结构快照（创建即冻）
    // =========================================================================

    /**
     * 若该报价单 4 份结构快照尚不存在，则从模板组装并写入；已存在则不覆盖（创建即冻）。
     * 全程 try/catch 降级，不影响加产品主流程。
     */
    @Transactional
    public void ensureStructure(UUID quotationId) {
        if (quotationId == null) return;
        try {
            Quotation q = Quotation.findById(quotationId);
            if (q == null) {
                LOG.warnf("[card-snapshot] ensureStructure: quotation not found id=%s", quotationId);
                return;
            }

            // 报价模板 → QUOTE_CARD + QUOTE_EXCEL
            if (q.customerTemplateId != null) {
                upsertStructure(quotationId, "QUOTE_CARD",
                    buildCardStructure(q.customerTemplateId, "QUOTATION"));
                upsertStructure(quotationId, "QUOTE_EXCEL",
                    buildExcelStructure(q.customerTemplateId));
            }

            // 核价模板 → COSTING_CARD + COSTING_EXCEL
            if (q.costingCardTemplateId != null) {
                upsertStructure(quotationId, "COSTING_CARD",
                    buildCardStructure(q.costingCardTemplateId, "COSTING"));
                upsertStructure(quotationId, "COSTING_EXCEL",
                    buildExcelStructure(q.costingCardTemplateId));
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] ensureStructure failed for quotation=%s: %s",
                quotationId, e.getMessage());
        }
    }

    /**
     * UPSERT 结构快照：已存在则跳过（创建即冻），不存在则插入。
     */
    private void upsertStructure(UUID quotationId, String viewKind, String structureJson) {
        if (structureJson == null) return;
        QuotationViewStructure existing =
            QuotationViewStructure.findByQuotationAndKind(quotationId, viewKind);
        if (existing != null) return; // 已冻，不覆盖

        QuotationViewStructure s = new QuotationViewStructure();
        s.quotationId = quotationId;
        s.viewKind = viewKind;
        s.structure = structureJson;
        s.createdAt = OffsetDateTime.now();
        s.persist();
        LOG.debugf("[card-snapshot] created structure quotation=%s kind=%s", quotationId, viewKind);
    }

    // =========================================================================
    // buildCardStructure — 从 components_snapshot 组装卡片结构
    // =========================================================================

    /**
     * 从模板 {@code components_snapshot} 组装卡片结构 JSON（spec §3.1 形状）。
     *
     * <p>结构 = { version:1, templateId, templateKind, tabs:[{ componentId, tabName,
     * sortOrder, componentType, dataDriverPath, rowKeyFields, fields:[], formulas:[] }] }
     *
     * <p><b>AP-39</b>: DATA_SOURCE 字段的 {@code datasource_binding} 完整搬运（不丢）。
     */
    private String buildCardStructure(UUID templateId, String templateKind) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;

            String snapshotJson = rows.get(0).toString();
            JsonNode snapshot = MAPPER.readTree(snapshotJson);
            if (!snapshot.isArray()) return null;

            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            ArrayNode tabs = root.putArray("tabs");

            for (JsonNode tab : snapshot) {
                ObjectNode tabNode = MAPPER.createObjectNode();

                // 基础元数据
                String componentId = tab.path("componentId").asText(null);
                tabNode.put("componentId", componentId != null ? componentId : "");
                tabNode.put("tabName", tab.path("tabName").asText(""));
                tabNode.put("sortOrder", tab.path("sortOrder").asInt(0));
                tabNode.put("componentType", tab.path("componentType").asText("NORMAL"));
                tabNode.put("dataDriverPath", tab.path("data_driver_path").asText(""));

                // rowKeyFields：从 component 表读（AP-39 补充：行键冻进结构）
                if (componentId != null && !componentId.isBlank()) {
                    String rowKeyFields = loadRowKeyFields(componentId);
                    if (rowKeyFields != null) {
                        tabNode.set("rowKeyFields", MAPPER.readTree(rowKeyFields));
                    } else {
                        tabNode.putArray("rowKeyFields");
                    }
                } else {
                    tabNode.putArray("rowKeyFields");
                }

                // fields 映射（snapshot 用 field_type，结构用 fieldType；AP-39 datasource_binding 保留）
                ArrayNode fieldsNode = tabNode.putArray("fields");
                for (JsonNode f : tab.path("fields")) {
                    ObjectNode fieldNode = MAPPER.createObjectNode();
                    fieldNode.put("name", f.path("name").asText(""));
                    fieldNode.put("fieldType", f.path("field_type").asText(""));
                    fieldNode.put("label", f.path("name").asText(""));
                    fieldNode.put("sortOrder", f.path("sort_order").asInt(0));
                    fieldNode.put("isAmount", f.path("is_amount").asBoolean(false));
                    fieldNode.put("isRequired", f.path("is_required").asBoolean(false));
                    fieldNode.put("editable", isEditable(f.path("field_type").asText("")));
                    if (!f.path("content").isMissingNode()) {
                        fieldNode.put("defaultValue", f.path("content").asText(null));
                    }
                    if (!f.path("basic_data_path").isMissingNode()) {
                        fieldNode.put("basicDataPath", f.path("basic_data_path").asText(null));
                    }
                    // AP-39: DATA_SOURCE 必须完整搬运 datasource_binding
                    if ("DATA_SOURCE".equals(f.path("field_type").asText(""))) {
                        JsonNode binding = f.path("datasource_binding");
                        if (!binding.isMissingNode() && !binding.isNull()) {
                            fieldNode.set("datasourceBinding", binding);
                        }
                    }
                    fieldsNode.add(fieldNode);
                }

                // formulas 直接搬运
                JsonNode formulas = tab.path("formulas");
                if (formulas.isArray()) {
                    tabNode.set("formulas", formulas);
                } else {
                    tabNode.putArray("formulas");
                }

                tabs.add(tabNode);
            }

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCardStructure failed templateId=%s: %s",
                templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // buildExcelStructure — 从 excel_view_config 组装 Excel 列结构
    // =========================================================================

    /**
     * 从模板 {@code excel_view_config} 组装 Excel 列结构（spec §3.1 形状）。
     * 输出: { version:1, templateId, columns:[{colKey, title, sourceType, ...}] }
     */
    private String buildExcelStructure(UUID templateId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT excel_view_config, template_kind FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty()) return null;

            Object[] row = (Object[]) rows.get(0);
            String excelViewConfig = row[0] != null ? row[0].toString() : null;
            String templateKind = row[1] != null ? row[1].toString() : "";

            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            if (excelViewConfig == null || excelViewConfig.isBlank()
                    || "[]".equals(excelViewConfig.trim())
                    || "{}".equals(excelViewConfig.trim())) {
                root.putArray("columns");
                return MAPPER.writeValueAsString(root);
            }

            JsonNode cols = MAPPER.readTree(excelViewConfig);
            if (!cols.isArray()) {
                root.putArray("columns");
                return MAPPER.writeValueAsString(root);
            }

            ArrayNode columns = root.putArray("columns");
            for (JsonNode col : cols) {
                ObjectNode colNode = MAPPER.createObjectNode();
                colNode.put("colKey", col.path("col_key").asText(col.path("colKey").asText("")));
                colNode.put("title", col.path("title").asText(col.path("col_name").asText("")));
                colNode.put("sourceType", col.path("source_type").asText("VARIABLE"));
                if (!col.path("variable_path").isMissingNode()) {
                    colNode.put("variablePath", col.path("variable_path").asText(null));
                }
                if (!col.path("formula").isMissingNode()) {
                    colNode.put("formula", col.path("formula").asText(null));
                }
                colNode.put("hidden", col.path("hidden").asBoolean(false));
                if (!col.path("comparison_tag").isMissingNode()) {
                    colNode.put("comparisonTag", col.path("comparison_tag").asText(null));
                }
                columns.add(colNode);
            }

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelStructure failed templateId=%s: %s",
                templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // snapshotLineValues — 产品行级 4 份值快照
    // =========================================================================

    /**
     * 对一行产品算四份初始值并写入 4 个值列。
     * Phase 1 占位实现：buildCardValues / buildExcelValues 复用 ConfigureSnapshotService
     * 的展开结果（Task 6/7 接入时填充），当前仅写空结构。
     * 各份 try/catch 降级，单份失败写 null 不连坐。
     */
    @Transactional
    public void snapshotLineValues(com.cpq.quotation.entity.QuotationLineItem li) {
        if (li == null || li.id == null) return;
        try {
            // 在当前事务内重新加载，避免 "Detached entity" 错误
            com.cpq.quotation.entity.QuotationLineItem managed =
                com.cpq.quotation.entity.QuotationLineItem.findById(li.id);
            if (managed == null) return;

            Quotation q = Quotation.findById(managed.quotationId);
            if (q == null) return;

            managed.quoteCardValues  = safeCall(() -> buildCardValues(managed, q.customerTemplateId));
            managed.quoteExcelValues = safeCall(() -> buildExcelValues(managed, q.customerTemplateId));
            if (q.costingCardTemplateId != null) {
                managed.costingCardValues  = safeCall(() -> buildCardValues(managed, q.costingCardTemplateId));
                managed.costingExcelValues = safeCall(() -> buildExcelValues(managed, q.costingCardTemplateId));
            }
            managed.cardSnapshotAt = OffsetDateTime.now();
            managed.quoteValuesAt  = managed.cardSnapshotAt;
            // Panache managed entity — no explicit persist needed in active transaction
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] snapshotLineValues failed lineItem=%s: %s", li.id, e.getMessage());
        }
    }

    /**
     * 构建卡片值快照 JSON（spec §3.2 形状）。
     * Phase 1 实现：组装空 tabs 结构（baseRows/editRows/formulaResults 在 Task 6 接入后填充）。
     * Task 6 会重构此方法，复用 ConfigureSnapshotService 的展开结果（不双写 expand）。
     */
    String buildCardValues(com.cpq.quotation.entity.QuotationLineItem li, UUID templateId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;

            JsonNode snapshot = MAPPER.readTree(rows.get(0).toString());
            if (!snapshot.isArray()) return null;

            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode tabs = root.putArray("tabs");

            for (JsonNode tab : snapshot) {
                ObjectNode tabNode = MAPPER.createObjectNode();
                tabNode.put("componentId", tab.path("componentId").asText(""));
                tabNode.put("tabName", tab.path("tabName").asText(""));
                // Phase 1: baseRows 空（Task 6 填充展开结果）
                tabNode.putArray("baseRows");
                tabNode.putArray("editRows");
                tabNode.putArray("formulaResults");
                tabs.add(tabNode);
            }

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCardValues failed templateId=%s: %s", templateId, e.getMessage());
            return null;
        }
    }

    /**
     * 构建 Excel 值快照 JSON（spec §3.2 形状：{rows:[{colKey:value}]}）。
     * Phase 1 实现：写空 rows（Task 6/7 接入后填充）。
     */
    String buildExcelValues(com.cpq.quotation.entity.QuotationLineItem li, UUID templateId) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.putArray("rows");
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelValues failed templateId=%s: %s", templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private String loadRowKeyFields(String componentId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT row_key_fields FROM component WHERE id = :cid")
                .setParameter("cid", UUID.fromString(componentId))
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            return rows.get(0).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEditable(String fieldType) {
        return "INPUT_NUMBER".equals(fieldType)
            || "INPUT_TEXT".equals(fieldType)
            || "LIST_FORMULA".equals(fieldType);
    }

    private String safeCall(java.util.concurrent.Callable<String> c) {
        try {
            return c.call();
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] safeCall降级: %s", e.getMessage());
            return null;
        }
    }
}
