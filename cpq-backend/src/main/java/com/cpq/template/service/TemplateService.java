package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class TemplateService {

    private static final Logger LOG = Logger.getLogger(TemplateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    public List<TemplateDTO> list(int page, int size, String category, String status, String keyword) {
        return list(page, size, category, null, null, status, keyword, null);
    }

    public List<TemplateDTO> list(int page, int size, String category, UUID customerId, UUID categoryId, String status, String keyword) {
        return list(page, size, category, customerId, categoryId, status, keyword, null);
    }

    public List<TemplateDTO> list(int page, int size, String category, UUID customerId, UUID categoryId,
                                  String status, String keyword, String templateKind) {
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (categoryId != null) {
            where.append(" AND categoryId = :categoryId");
            params.put("categoryId", categoryId);
        } else if (category != null && !category.isBlank()) {
            where.append(" AND category = :category");
            params.put("category", category);
        }
        if (customerId != null) {
            where.append(" AND customerId = :customerId");
            params.put("customerId", customerId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status");
            params.put("status", status);
        }
        if (templateKind != null && !templateKind.isBlank()) {
            where.append(" AND templateKind = :templateKind");
            params.put("templateKind", templateKind);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE :kw");
            params.put("kw", "%" + keyword + "%");
        }
        List<Template> templates = Template.<Template>list(where + " ORDER BY createdAt DESC", params)
            .stream()
            .skip((long) page * size)
            .limit(size)
            .collect(Collectors.toList());

        return templates.stream()
            .map(t -> TemplateDTO.from(t, Collections.emptyList()))
            .collect(Collectors.toList());
    }

    public TemplateDTO getById(UUID id) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        return TemplateDTO.from(template, tcs);
    }

    @Transactional
    public TemplateDTO create(CreateTemplateRequest request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BusinessException("Template name is required");
        }

        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = request.name.trim();
        template.category = request.category;
        template.customerId = request.customerId;
        template.categoryId = request.categoryId;
        template.description = request.description;
        template.usageNote = request.usageNote;
        template.productAttributes = nullSafeJson(request.productAttributes);
        template.subtotalFormula = nullSafeJson(request.subtotalFormula);
        // V71：模板类型，缺省 QUOTATION；COSTING 类型 customerId 可选（默认所有客户可用）
        template.templateKind = (request.templateKind != null && !request.templateKind.isBlank())
                ? request.templateKind
                : "QUOTATION";
        template.status = "DRAFT";
        template.persist();

        LOG.infof("Created template id=%s name=%s", template.id, template.name);
        return TemplateDTO.from(template, Collections.emptyList());
    }

    @Transactional
    public TemplateDTO update(UUID id, CreateTemplateRequest request) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Only DRAFT templates can be edited");
        }

        if (request.name != null && !request.name.isBlank()) {
            template.name = request.name.trim();
        }
        if (request.category != null) {
            template.category = request.category;
        }
        if (request.customerId != null) {
            template.customerId = request.customerId;
        }
        if (request.categoryId != null) {
            template.categoryId = request.categoryId;
        }
        if (request.description != null) {
            template.description = request.description;
        }
        if (request.usageNote != null) {
            template.usageNote = request.usageNote;
        }
        if (request.productAttributes != null) {
            template.productAttributes = nullSafeJson(request.productAttributes);
        }
        if (request.subtotalFormula != null) {
            template.subtotalFormula = nullSafeJson(request.subtotalFormula);
        }

        LOG.infof("Updated template id=%s", id);
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        return TemplateDTO.from(template, tcs);
    }

    @Transactional
    public void delete(UUID id) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Only DRAFT templates can be deleted");
        }
        // cascade deletes template_component rows
        template.delete();
        LOG.infof("Deleted template id=%s", id);
    }

    @Transactional
    public TemplateDTO publish(UUID id, PublishRequest request) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Only DRAFT templates can be published");
        }

        // 校验:小计配置二选一(向前兼容旧模板的 subtotalFormula token 数组,新模板使用 SUBTOTAL 组件)
        // - 旧:模板属性页直接填 subtotalFormula JSONB token 数组
        // - 新:拖入 SUBTOTAL 类型组件,公式由组件自身的 formulas 承载
        // 只要满足其一即视为已配置小计
        List<?> subtotalList = parseJsonArray(template.subtotalFormula);
        long tcCount = TemplateComponent.count("templateId", id);
        if (tcCount == 0) {
            throw new BusinessException("模板发布前必须至少包含一个组件");
        }
        boolean hasSubtotalComponent = false;
        if (subtotalList.isEmpty()) {
            List<TemplateComponent> tcsForCheck = TemplateComponent.list("templateId = ?1", id);
            for (TemplateComponent tc : tcsForCheck) {
                Component comp = Component.findById(tc.componentId);
                if (comp != null && "SUBTOTAL".equals(comp.componentType)) {
                    hasSubtotalComponent = true;
                    break;
                }
            }
            if (!hasSubtotalComponent) {
                throw new BusinessException("模板发布前必须配置小计:请拖入一个『小计』类型的组件,或在模板属性中填写小计公式");
            }
        }

        // Build components_snapshot
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (TemplateComponent tc : tcs) {
            Component comp = Component.findById(tc.componentId);
            if (comp != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", tc.id.toString());
                entry.put("componentId", comp.id.toString());
                entry.put("componentName", comp.name);
                entry.put("componentCode", comp.code);
                entry.put("componentType", comp.componentType);
                entry.put("tabName", tc.tabName);
                entry.put("sortOrder", tc.sortOrder);
                entry.put("fields", parseJsonArray(comp.fields));
                entry.put("formulas", parseJsonArray(comp.formulas));
                entry.put("preset_rows", parseJsonArray(tc.presetRows));
                // Y1.5 行驱动路径(可空) — 让前端在不发额外请求的情况下识别"驱动组件"
                entry.put("data_driver_path", comp.dataDriverPath);
                entry.put("formula_assignments", parseJsonObject(tc.formulaAssignments));
                snapshot.add(entry);
            }
        }
        template.componentsSnapshot = toJson(snapshot);

        // PRD 多版本语义:同 series 升级时,旧 PUBLISHED 保持原状态,由用户后续主动归档。
        // V62 已撤销 V28 partial unique index,同 (customer_id, category_id) 允许多个 PUBLISHED 共存。

        // Calculate version
        template.version = calculateNextVersion(template.templateSeriesId, request);
        template.status = "PUBLISHED";
        template.publishedAt = OffsetDateTime.now();

        LOG.infof("Published template id=%s version=%s", id, template.version);
        return TemplateDTO.from(template, tcs);
    }

    @Transactional
    public TemplateDTO archive(UUID id, boolean force) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        if (!"PUBLISHED".equals(template.status)) {
            throw new BusinessException("Only PUBLISHED templates can be archived");
        }

        // BLOCK: in-progress quotations using this template (no force override)
        checkNoInProgressQuotations(id);
        // WARNING: products that only bind this template version (allow with force=true)
        if (!force) {
            checkNotBoundByProducts(id);
        }

        template.status = "ARCHIVED";
        LOG.infof("Archived template id=%s", id);
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        return TemplateDTO.from(template, tcs);
    }

    @Transactional
    public TemplateDTO createNewDraft(UUID sourceId) {
        Template source = Template.findById(sourceId);
        if (source == null) {
            throw new BusinessException(404, "Template not found: " + sourceId);
        }

        // Per series: at most one DRAFT may exist concurrently. If a DRAFT already exists,
        // return it instead of silently creating a duplicate (T3 finding 3.6).
        Template existingDraft = Template.<Template>find(
                "templateSeriesId = ?1 AND status = 'DRAFT'", source.templateSeriesId).firstResult();
        if (existingDraft != null) {
            throw new BusinessException(400,
                    "该模板系列已存在草稿版本（id=" + existingDraft.id + "），请先发布或删除现有草稿");
        }

        Template draft = new Template();
        draft.templateSeriesId = source.templateSeriesId; // inherit series
        draft.name = source.name;
        draft.category = source.category;
        draft.customerId = source.customerId;
        draft.categoryId = source.categoryId;
        draft.description = source.description;
        draft.usageNote = source.usageNote;
        draft.productAttributes = source.productAttributes;
        draft.subtotalFormula = source.subtotalFormula;
        // V71：模板类型必须从 source 继承——否则 Template 实体上的默认值 'QUOTATION'
        // 会让所有"创建为新草稿"出来的核价模板退化成报价模板。
        draft.templateKind = source.templateKind != null ? source.templateKind : "QUOTATION";
        draft.componentsSnapshot = null;
        // V145+: 公式 / Excel 视图配置 必须随新草稿带出,否则草稿"瘸腿"
        draft.formulas = source.formulas != null ? source.formulas : "[]";
        draft.excelViewConfig = source.excelViewConfig;
        draft.status = "DRAFT";
        draft.persist();

        // Copy TemplateComponent associations (含 preset_rows / formula_assignments)
        List<TemplateComponent> sourceTcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", sourceId);
        for (TemplateComponent stc : sourceTcs) {
            TemplateComponent newTc = new TemplateComponent();
            newTc.templateId = draft.id;
            newTc.componentId = stc.componentId;
            newTc.tabName = stc.tabName;
            newTc.sortOrder = stc.sortOrder;
            newTc.presetRows = stc.presetRows != null ? stc.presetRows : "[]";
            newTc.formulaAssignments = stc.formulaAssignments != null ? stc.formulaAssignments : "{}";
            newTc.persist();
        }

        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", draft.id);
        LOG.infof("Created new draft id=%s from source=%s", draft.id, sourceId);
        return TemplateDTO.from(draft, tcs);
    }

    /**
     * 客户报价模板匹配:客户专属优先,无则回退到通用模板。
     *
     * <p>对应 docs/API.md L100/L643 设计:
     * <pre>
     * 1. customer_id = customerId AND category_id = categoryId AND status = 'PUBLISHED'
     *    → 命中即返回(可能多个版本,由前端选择)
     * 2. 上一步为空 → customer_id IS NULL AND category_id = categoryId AND status = 'PUBLISHED'
     *    → 通用模板兜底
     * 3. 都为空 → matchType=NONE,前端应引导用户配置
     * </pre>
     *
     * @param customerId 报价单关联的客户 ID(必填)
     * @param categoryId 产品分类 ID(必填)
     */
    public com.cpq.template.dto.TemplateMatchResult matchCustomerQuoteTemplate(UUID customerId, UUID categoryId) {
        if (customerId == null || categoryId == null) {
            throw new BusinessException("customerId 和 categoryId 不能为空");
        }
        // 客户专属
        List<Template> specific = Template.list(
                "customerId = ?1 AND categoryId = ?2 AND status = 'PUBLISHED' ORDER BY publishedAt DESC NULLS LAST",
                customerId, categoryId);
        if (!specific.isEmpty()) {
            return new com.cpq.template.dto.TemplateMatchResult(
                    com.cpq.template.dto.TemplateMatchResult.MatchType.CUSTOMER_SPECIFIC,
                    specific.stream().map(t -> TemplateDTO.from(t, Collections.emptyList())).collect(Collectors.toList()));
        }
        // 通用兜底
        List<Template> general = Template.list(
                "customerId IS NULL AND categoryId = ?1 AND status = 'PUBLISHED' ORDER BY publishedAt DESC NULLS LAST",
                categoryId);
        if (!general.isEmpty()) {
            return new com.cpq.template.dto.TemplateMatchResult(
                    com.cpq.template.dto.TemplateMatchResult.MatchType.GENERAL_FALLBACK,
                    general.stream().map(t -> TemplateDTO.from(t, Collections.emptyList())).collect(Collectors.toList()));
        }
        return new com.cpq.template.dto.TemplateMatchResult(
                com.cpq.template.dto.TemplateMatchResult.MatchType.NONE,
                Collections.emptyList());
    }

    public List<TemplateDTO> getVersionHistory(UUID templateSeriesId) {
        List<Template> templates = Template.list(
            "templateSeriesId = ?1 ORDER BY publishedAt DESC NULLS LAST, createdAt DESC",
            templateSeriesId
        );
        return templates.stream()
            .map(t -> TemplateDTO.from(t, Collections.emptyList()))
            .collect(Collectors.toList());
    }

    // ---- Private helpers ----

    private String calculateNextVersion(UUID seriesId, PublishRequest request) {
        // 查找同 series 任何已发布过的版本(PUBLISHED 或 ARCHIVED)以推算下一版本号
        List<Template> published = Template.list(
            "templateSeriesId = ?1 AND status IN ('PUBLISHED', 'ARCHIVED') ORDER BY publishedAt DESC NULLS LAST",
            seriesId
        );

        if (published.isEmpty()) {
            int major = (request != null && request.majorVersion != null) ? request.majorVersion : 1;
            return "v" + major + ".0";
        }

        String latestVersion = published.get(0).version;
        if (latestVersion == null) {
            return "v1.0";
        }

        // Parse "vX.Y"
        try {
            String stripped = latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
            String[] parts = stripped.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            if (request != null && request.majorVersion != null) {
                return "v" + request.majorVersion + ".0";
            } else {
                return "v" + major + "." + (minor + 1);
            }
        } catch (NumberFormatException e) {
            return "v1.0";
        }
    }

    /** BLOCK (no force override): in-progress quotations using this template version.
     *
     * v4: quotation references the customer-quote template via {@code customer_template_id}
     * (added in V30). Pre-v4 also stored {@code template_id} on quotation_line_item;
     * we check both to cover historical and current data.
     *
     * Use Panache count on the typed entity field instead of a native query — this
     * avoids "column does not exist" SQL errors that abort the surrounding transaction.
     */
    private void checkNoInProgressQuotations(UUID templateId) {
        long inProgress = com.cpq.quotation.entity.Quotation.count(
                "customerTemplateId = ?1 AND status NOT IN ('CANCELLED','REJECTED','ACCEPTED','EXPIRED')",
                templateId);
        if (inProgress == 0) {
            // Also check legacy line-item references (pre-v4)
            inProgress = com.cpq.quotation.entity.QuotationLineItem.count(
                    "templateId = ?1", templateId);
        }
        if (inProgress > 0) {
            throw new BusinessException(
                    "Template is used in " + inProgress + " in-progress quotation(s) and cannot be archived");
        }
    }

    /** WARNING (allow with force=true): products that only bind this template version */
    private void checkNotBoundByProducts(UUID templateId) {
        long bindings = com.cpq.template.entity.ProductTemplateBinding.count(
                "templateId = ?1", templateId);
        if (bindings > 0) {
            throw new BusinessException(
                    "Template is bound to " + bindings + " product(s). Use force=true to archive anyway.");
        }
    }

    private String nullSafeJson(String json) {
        if (json == null || json.isBlank()) return "[]";
        return json;
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("rawtypes")
    private List parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map parseJsonObject(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
