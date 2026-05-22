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

    /** V212: 全局变量绑定服务, 供 createNewDraft 末尾调用复制绑定 */
    @Inject
    TemplateGvBindingService templateGvBindingService;

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
        //
        // V200 (2026-05-19): 模板级覆盖 — 同 component 在 SIMPLE / COMPOSITE 模板需要不同
        // driver_path / fields. 引入 template_component.data_driver_path_override 和
        // fields_override 两列; 非 NULL 时盖掉 component 表对应字段, 否则走 component 默认.
        //
        // 没用 override 时与 V199 之前完全等价 → 历史模板行为不变.
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
                // V200: fields 走 override 优先
                String effectiveFields = (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank())
                        ? tc.fieldsOverride : comp.fields;
                entry.put("fields", parseJsonArray(effectiveFields));
                entry.put("formulas", parseJsonArray(comp.formulas));
                entry.put("preset_rows", parseJsonArray(tc.presetRows));
                // V200: data_driver_path 走 override 优先
                String effectiveDriverPath = (tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank())
                        ? tc.dataDriverPathOverride : comp.dataDriverPath;
                entry.put("data_driver_path", effectiveDriverPath);
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

    /**
     * H1: 同步所有引用该组件的模板 components_snapshot.
     *
     * <p>当组件配置 (fields / formulas / dataDriverPath) 发生平台级变化时, 已发布模板
     * 的 snapshot 会陈旧 — 报价单按旧 snapshot 渲染会丢失新字段 / 旧路径报错.
     * 本方法找到所有 components_snapshot 含 componentId 的 template, 用组件最新内容
     * 覆盖 snapshot 对应数组项的 fields / formulas / data_driver_path / componentType.
     *
     * <p>语义说明: 这刻意破坏 PUBLISHED 模板的 "版本快照不可变" 约定, 因为配置层变更
     * 必须对所有视图同步生效 (用户原则: 所有渲染从配置中心走). version 不变保持版本号语义.
     *
     * @param componentId 组件 id
     * @return 受影响的 template id 列表
     */
    @Transactional
    public List<UUID> refreshSnapshotsByComponent(UUID componentId) {
        Component comp = Component.findById(componentId);
        if (comp == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }
        // 找所有 components_snapshot 含该 componentId 的 template
        @SuppressWarnings("unchecked")
        List<Object> templateIds = em.createNativeQuery(
                "SELECT id FROM template WHERE components_snapshot::text LIKE :pattern")
                .setParameter("pattern", "%" + componentId.toString() + "%")
                .getResultList();
        List<UUID> affected = new ArrayList<>();
        List<?> compFields = parseJsonArray(comp.fields);
        List<?> compFormulas = parseJsonArray(comp.formulas);
        for (Object obj : templateIds) {
            UUID tplId = obj instanceof UUID u ? u : UUID.fromString(obj.toString());
            Template tpl = Template.findById(tplId);
            if (tpl == null || tpl.componentsSnapshot == null) continue;
            List<?> snapList = parseJsonArray(tpl.componentsSnapshot);
            if (snapList == null) continue;
            // V200/V206 (2026-05-19 H1 bug fix): 同 cid 在模板里出现多次时, 必须按
            // (templateId, componentId, sortOrder) 精确匹配 tc; firstResult() 只拿第一个
            // tc 会让所有同 cid snapshot entry 错误共用同一份 fields_override → 后到的
            // Tab (如"选配-工序列表") 的差异化配置 (LIST_FORMULA 成材率等) 被"工序" Tab 的
            // fields_override 反向覆盖.
            boolean touched = false;
            for (Object entryObj : snapList) {
                if (!(entryObj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) entryObj;
                Object cidObj = entry.get("componentId");
                if (cidObj == null) cidObj = entry.get("component_id");
                if (cidObj == null || !componentId.toString().equals(cidObj.toString())) continue;
                // V206: 按 sortOrder 精确匹配 tc
                Object sortObj = entry.get("sortOrder");
                if (sortObj == null) sortObj = entry.get("sort_order");
                Integer sortOrder = (sortObj instanceof Number) ? ((Number) sortObj).intValue() : null;
                TemplateComponent tc = (sortOrder != null)
                        ? TemplateComponent.find(
                                "templateId = ?1 AND componentId = ?2 AND sortOrder = ?3",
                                tplId, componentId, sortOrder).firstResult()
                        : TemplateComponent.find(
                                "templateId = ?1 AND componentId = ?2",
                                tplId, componentId).firstResult();
                List<?> effectiveFields = (tc != null && tc.fieldsOverride != null && !tc.fieldsOverride.isBlank())
                        ? parseJsonArray(tc.fieldsOverride) : compFields;
                String effectiveDriverPath = (tc != null && tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank())
                        ? tc.dataDriverPathOverride : comp.dataDriverPath;
                // V200: fields / data_driver_path 走 override 优先, 其余 (formulas / componentType / name / code) 走 component
                entry.put("fields", effectiveFields);
                entry.put("formulas", compFormulas);
                entry.put("data_driver_path", effectiveDriverPath);
                entry.put("componentType", comp.componentType);
                entry.put("componentName", comp.name);
                entry.put("componentCode", comp.code);
                touched = true;
            }
            if (touched) {
                tpl.componentsSnapshot = toJson(snapList);
                affected.add(tplId);
            }
        }
        LOG.infof("[H1 snapshot sync] componentId=%s affected %d templates", componentId, affected.size());
        return affected;
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

        // Copy TemplateComponent associations (含 preset_rows / formula_assignments / V200 overrides)
        List<TemplateComponent> sourceTcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", sourceId);
        for (TemplateComponent stc : sourceTcs) {
            TemplateComponent newTc = new TemplateComponent();
            newTc.templateId = draft.id;
            newTc.componentId = stc.componentId;
            newTc.tabName = stc.tabName;
            newTc.sortOrder = stc.sortOrder;
            newTc.presetRows = stc.presetRows != null ? stc.presetRows : "[]";
            newTc.formulaAssignments = stc.formulaAssignments != null ? stc.formulaAssignments : "{}";
            // V200: 复制覆盖列 - 否则派生新版本会丢 COMPOSITE 模板的 v_composite_child_* 覆盖
            newTc.dataDriverPathOverride = stc.dataDriverPathOverride;
            newTc.fieldsOverride = stc.fieldsOverride;
            newTc.persist();
        }

        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", draft.id);

        // V212: 复制全局变量绑定到新草稿 (display_order 原样保留, ADR-002 §5.2)
        templateGvBindingService.copyBindings(sourceId, draft.id);

        LOG.infof("Created new draft id=%s from source=%s", draft.id, sourceId);
        return TemplateDTO.from(draft, tcs);
    }

    /**
     * 客户报价模板匹配:同时返回客户专属 + 通用模板(让用户在两类间自由选择)。
     *
     * <p>对应 docs/API.md L100/L643 + 2026-05-15 修复(报价模板不显示通用模板 bug)设计:
     * <pre>
     * 1. 同时查询两个集合(都过滤 templateKind='QUOTATION' AND status='PUBLISHED'):
     *    a. 客户专属:customer_id = customerId AND category_id = categoryId
     *    b. 通用:     customer_id IS NULL AND category_id = categoryId
     * 2. 根据命中情况返回:
     *    - 两边都有 → MIXED, templates = [客户专属... 在前 + 通用... 在后]
     *    - 仅客户专属 → CUSTOMER_SPECIFIC
     *    - 仅通用     → GENERAL_FALLBACK
     *    - 都无       → NONE
     * </pre>
     *
     * <p>修复内容:
     * <ul>
     *   <li>不再 short-circuit;客户有专属时通用模板仍可见,与前端 QuotationCreateForm
     *       的 MIXED 处理逻辑(2026-05-14 加)契约对齐</li>
     *   <li>两条查询都加 templateKind='QUOTATION' 过滤,避免 COSTING 客户专属
     *       模板被误算入 CUSTOMER_SPECIFIC</li>
     * </ul>
     *
     * @param customerId 报价单关联的客户 ID(必填)
     * @param categoryId 产品分类 ID(必填)
     */
    public com.cpq.template.dto.TemplateMatchResult matchCustomerQuoteTemplate(UUID customerId, UUID categoryId) {
        if (customerId == null || categoryId == null) {
            throw new BusinessException("customerId 和 categoryId 不能为空");
        }
        List<Template> specific = Template.list(
                "customerId = ?1 AND categoryId = ?2 AND templateKind = 'QUOTATION' AND status = 'PUBLISHED' "
                        + "ORDER BY publishedAt DESC NULLS LAST",
                customerId, categoryId);
        List<Template> general = Template.list(
                "customerId IS NULL AND categoryId = ?1 AND templateKind = 'QUOTATION' AND status = 'PUBLISHED' "
                        + "ORDER BY publishedAt DESC NULLS LAST",
                categoryId);

        boolean hasSpecific = !specific.isEmpty();
        boolean hasGeneral = !general.isEmpty();

        if (!hasSpecific && !hasGeneral) {
            return new com.cpq.template.dto.TemplateMatchResult(
                    com.cpq.template.dto.TemplateMatchResult.MatchType.NONE,
                    Collections.emptyList());
        }

        com.cpq.template.dto.TemplateMatchResult.MatchType matchType;
        List<Template> combined = new java.util.ArrayList<>(specific.size() + general.size());
        if (hasSpecific && hasGeneral) {
            matchType = com.cpq.template.dto.TemplateMatchResult.MatchType.MIXED;
            combined.addAll(specific);
            combined.addAll(general);
        } else if (hasSpecific) {
            matchType = com.cpq.template.dto.TemplateMatchResult.MatchType.CUSTOMER_SPECIFIC;
            combined.addAll(specific);
        } else {
            matchType = com.cpq.template.dto.TemplateMatchResult.MatchType.GENERAL_FALLBACK;
            combined.addAll(general);
        }

        return new com.cpq.template.dto.TemplateMatchResult(
                matchType,
                combined.stream()
                        .map(t -> TemplateDTO.from(t, Collections.emptyList()))
                        .collect(Collectors.toList()));
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

    // ---- Admin / data migration endpoints ----

    /**
     * Step C1: 统一智能视图路径方案 — 模板数据迁移端点 (2026-05-21).
     *
     * <p>对每个 PUBLISHED 模板的每个 tc：
     * <ol>
     *   <li>fields_override 各字段: basic_data_path_composite 有值 → 覆盖 basic_data_path，删除 _composite 键</li>
     *   <li>tc 顶层: dataDriverPathComposite 有值 → 覆盖 dataDriverPathOverride，列写 null</li>
     *   <li>snapshot 同步刷新</li>
     * </ol>
     *
     * @param templateIds null 或空 → 处理所有 PUBLISHED 模板
     * @return 迁移摘要（每个模板: id / tcMigrated / fieldsMigrated / errors）
     */
    @Transactional
    public Map<String, Object> migrateToUnifiedView(List<UUID> templateIds) {
        List<Template> targets;
        if (templateIds == null || templateIds.isEmpty()) {
            targets = Template.list("status = 'PUBLISHED' ORDER BY createdAt ASC");
        } else {
            targets = new ArrayList<>();
            for (UUID tid : templateIds) {
                Template t = Template.findById(tid);
                if (t != null) targets.add(t);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int totalTcMigrated = 0;
        int totalFieldsMigrated = 0;

        for (Template tpl : targets) {
            Map<String, Object> tplResult = new LinkedHashMap<>();
            tplResult.put("templateId", tpl.id.toString());
            tplResult.put("templateName", tpl.name);

            List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", tpl.id);
            int tcMigrated = 0;
            int fieldsMigrated = 0;
            List<String> errors = new ArrayList<>();

            for (TemplateComponent tc : tcs) {
                try {
                    boolean tcChanged = false;

                    // 1. tc 顶层: dataDriverPathComposite → dataDriverPathOverride
                    if (tc.dataDriverPathComposite != null && !tc.dataDriverPathComposite.isBlank()) {
                        LOG.infof("[migrate-unified-view] template=%s tc=%s: driver_path_composite=%s → override",
                                tpl.id, tc.id, tc.dataDriverPathComposite);
                        tc.dataDriverPathOverride = tc.dataDriverPathComposite;
                        tc.dataDriverPathComposite = null;
                        tcChanged = true;
                    }

                    // 2. fields_override: basic_data_path_composite → basic_data_path
                    if (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank()) {
                        List<Map<String, Object>> fields;
                        try {
                            fields = MAPPER.readValue(tc.fieldsOverride,
                                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                        } catch (Exception e) {
                            errors.add("tc=" + tc.id + " fieldsOverride parse error: " + e.getMessage());
                            continue;
                        }

                        boolean fieldsChanged = false;
                        for (Map<String, Object> field : fields) {
                            // basic_data_path_composite → basic_data_path
                            Object compositePathObj = field.get("basic_data_path_composite");
                            if (compositePathObj != null) {
                                String compositePath = compositePathObj.toString();
                                if (!compositePath.isBlank()) {
                                    LOG.infof("[migrate-unified-view] template=%s tc=%s field=%s: basic_data_path_composite=%s → basic_data_path",
                                            tpl.id, tc.id, field.get("name"), compositePath);
                                    field.put("basic_data_path", compositePath);
                                }
                                field.remove("basic_data_path_composite");
                                fieldsChanged = true;
                                fieldsMigrated++;
                            }
                            // 清理其他双轨残留键（formula_composite / default_formula_composite）
                            // 注意: LIST_FORMULA formula_composite token 迁移 (G3) 暂跳过 (PM 已确认独立任务)
                            if (field.remove("formula_composite") != null) fieldsChanged = true;
                            if (field.remove("default_formula_composite") != null) fieldsChanged = true;
                            // datasource_binding_composite 如有也清理
                            if (field.remove("datasource_binding_composite") != null) fieldsChanged = true;
                        }

                        if (fieldsChanged) {
                            tc.fieldsOverride = MAPPER.writeValueAsString(fields);
                            tcChanged = true;
                        }
                    }

                    if (tcChanged) tcMigrated++;
                } catch (Exception e) {
                    errors.add("tc=" + tc.id + " error: " + e.getMessage());
                    LOG.warnf("[migrate-unified-view] template=%s tc=%s error: %s", tpl.id, tc.id, e.getMessage());
                }
            }

            // 3. 刷新 snapshot — 让 basic_data_path 修改在 components_snapshot 生效
            // 遍历所有涉及到的 componentId 刷新
            if (tcMigrated > 0) {
                try {
                    // 用 publish-style snapshot rebuild
                    rebuildSnapshotForTemplate(tpl, tcs);
                } catch (Exception e) {
                    errors.add("snapshot rebuild error: " + e.getMessage());
                    LOG.warnf("[migrate-unified-view] template=%s snapshot rebuild error: %s", tpl.id, e.getMessage());
                }
            }

            tplResult.put("tcMigrated", tcMigrated);
            tplResult.put("fieldsMigrated", fieldsMigrated);
            tplResult.put("errors", errors);
            results.add(tplResult);
            totalTcMigrated += tcMigrated;
            totalFieldsMigrated += fieldsMigrated;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTemplates", targets.size());
        summary.put("totalTcMigrated", totalTcMigrated);
        summary.put("totalFieldsMigrated", totalFieldsMigrated);
        summary.put("details", results);
        LOG.infof("[migrate-unified-view] done: templates=%d, tc=%d, fields=%d",
                targets.size(), totalTcMigrated, totalFieldsMigrated);
        return summary;
    }

    /**
     * 重建 PUBLISHED 模板的 components_snapshot（基于当前 tc 配置，不改 status/version）.
     * 逻辑与 publish() 的 snapshot build 段完全对齐。
     */
    private void rebuildSnapshotForTemplate(Template tpl, List<TemplateComponent> tcs) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (TemplateComponent tc : tcs) {
            com.cpq.component.entity.Component comp = com.cpq.component.entity.Component.findById(tc.componentId);
            if (comp != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", tc.id.toString());
                entry.put("componentId", comp.id.toString());
                entry.put("componentName", comp.name);
                entry.put("componentCode", comp.code);
                entry.put("componentType", comp.componentType);
                entry.put("tabName", tc.tabName);
                entry.put("sortOrder", tc.sortOrder);
                String effectiveFields = (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank())
                        ? tc.fieldsOverride : comp.fields;
                entry.put("fields", parseJsonArray(effectiveFields));
                entry.put("formulas", parseJsonArray(comp.formulas));
                entry.put("preset_rows", parseJsonArray(tc.presetRows));
                String effectiveDriverPath = (tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank())
                        ? tc.dataDriverPathOverride : comp.dataDriverPath;
                entry.put("data_driver_path", effectiveDriverPath);
                entry.put("formula_assignments", parseJsonObject(tc.formulaAssignments));
                snapshot.add(entry);
            }
        }
        tpl.componentsSnapshot = toJson(snapshot);
    }

    /**
     * 2026-05-20 admin endpoint: SYSTEM_ADMIN 修复 PUBLISHED 模板的 tc composite overrides.
     *
     * <p>用于双轨方案迁移 / 紧急数据修复。标记 @deprecated — 统一视图方案后此端点不再推荐使用。
     *
     * @deprecated 使用 migrateToUnifiedView 替代
     */
    @Deprecated
    @Transactional
    public Map<String, Object> patchTemplateComponentCompositeOverrides(
            UUID templateId,
            UUID tcId,
            String dataDriverPathComposite,
            List<java.util.Map<String, String>> fieldComposites,
            List<Object> replaceFieldsOverride) {

        Template tpl = Template.findById(templateId);
        if (tpl == null) throw new com.cpq.common.exception.BusinessException(404, "Template not found: " + templateId);

        TemplateComponent tc = TemplateComponent.findById(tcId);
        if (tc == null || !templateId.equals(tc.templateId)) {
            throw new com.cpq.common.exception.BusinessException(404, "TemplateComponent not found: " + tcId);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("dataDriverPathComposite", tc.dataDriverPathComposite);
        before.put("fieldsOverride", tc.fieldsOverride);

        // 写 composite driver path
        if (dataDriverPathComposite != null) {
            tc.dataDriverPathComposite = dataDriverPathComposite;
        }

        // 整体替换 fieldsOverride（如果提供）
        if (replaceFieldsOverride != null) {
            try {
                tc.fieldsOverride = MAPPER.writeValueAsString(replaceFieldsOverride);
            } catch (Exception e) {
                throw new com.cpq.common.exception.BusinessException("fieldsOverride 序列化失败: " + e.getMessage());
            }
        }

        // 注入 basic_data_path_composite 字段（覆盖单个字段）
        if (!fieldComposites.isEmpty() && replaceFieldsOverride == null) {
            try {
                List<Map<String, Object>> fields;
                if (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank()) {
                    fields = MAPPER.readValue(tc.fieldsOverride,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                } else {
                    fields = new ArrayList<>();
                }
                for (java.util.Map<String, String> fc : fieldComposites) {
                    String name = fc.get("name");
                    String path = fc.get("basicDataPathComposite");
                    if (name == null || path == null) continue;
                    for (Map<String, Object> f : fields) {
                        if (name.equals(f.get("name"))) {
                            f.put("basic_data_path_composite", path);
                        }
                    }
                }
                tc.fieldsOverride = MAPPER.writeValueAsString(fields);
            } catch (Exception e) {
                throw new com.cpq.common.exception.BusinessException("fieldsOverride 更新失败: " + e.getMessage());
            }
        }

        // 重建 snapshot
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);
        rebuildSnapshotForTemplate(tpl, tcs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tcId", tcId.toString());
        result.put("before", before);
        result.put("after", Map.of(
                "dataDriverPathComposite", tc.dataDriverPathComposite,
                "fieldsOverride", tc.fieldsOverride));
        return result;
    }

    /**
     * 2026-05-20 admin endpoint: 按 sortOrder 删除 PUBLISHED 模板的 tc 记录.
     */
    @Transactional
    public Map<String, Object> deleteTemplateComponentsBySortOrder(UUID templateId, List<Integer> sortOrders) {
        Template tpl = Template.findById(templateId);
        if (tpl == null) throw new com.cpq.common.exception.BusinessException(404, "Template not found: " + templateId);

        String snapshotBefore = tpl.componentsSnapshot;
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);

        int deleted = 0;
        for (TemplateComponent tc : tcs) {
            if (sortOrders.contains(tc.sortOrder)) {
                tc.delete();
                deleted++;
            }
        }

        // 重建 snapshot
        List<TemplateComponent> remaining = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);
        rebuildSnapshotForTemplate(tpl, remaining);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedTcs", deleted);
        result.put("snapshotBefore", snapshotBefore);
        result.put("snapshotAfter", tpl.componentsSnapshot);
        return result;
    }

    /**
     * 2026-05-21 admin endpoint: 将 template_component.fields_override 上升为 component.fields（单一来源）.
     *
     * <p>背景：组件管理 UI 显示 component.fields（旧配置），而实际渲染走 template_component.fields_override
     * （新完整配置，含"子件"字段）。用户无法在 UI 中看到真实渲染字段，造成配置不透明。
     *
     * <p>本方法对每个目标组件：
     * <ol>
     *   <li>找所有引用该组件的 template_component，收集所有非 NULL 的 fields_override，
     *       选取字段数最多的作为"权威版"（最完整）</li>
     *   <li>用权威版 fields_override 更新 component.fields；
     *       同时从 dataDriverPathOverride（非 NULL 时）更新 component.dataDriverPath</li>
     *   <li>将所有引用该组件的 tc 的 fields_override + dataDriverPathOverride 设 NULL（清空覆盖）</li>
     *   <li>调用 refreshSnapshotsByComponent 同步所有模板 snapshot</li>
     * </ol>
     *
     * <p>约束：不动 SIMPLE 产品逻辑；不动 DATA_SOURCE.GLOBAL_VARIABLE 字段；零回归。
     *
     * @param componentIds 目标组件 ID 列表，null 或空则对所有 ACTIVE 组件处理（慎用）
     * @return 迁移摘要
     */
    @Transactional
    public Map<String, Object> promoteOverrideToComponent(List<UUID> componentIds) {
        List<Component> targets;
        if (componentIds == null || componentIds.isEmpty()) {
            // 安全兜底：不提供 componentIds 时只处理名称以"选配-"开头的组件（避免误操作）
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT id FROM component WHERE name LIKE '选配-%' AND status = 'ACTIVE'")
                    .getResultList();
            targets = new ArrayList<>();
            for (Object row : rows) {
                UUID cid = row instanceof UUID u ? u : UUID.fromString(row.toString());
                Component c = Component.findById(cid);
                if (c != null) targets.add(c);
            }
        } else {
            targets = new ArrayList<>();
            for (UUID cid : componentIds) {
                Component c = Component.findById(cid);
                if (c != null) targets.add(c);
            }
        }

        List<Map<String, Object>> details = new ArrayList<>();
        int totalComponentsUpdated = 0;
        int totalTcCleared = 0;
        int totalSnapshotTouched = 0;

        for (Component comp : targets) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("componentId", comp.id.toString());
            detail.put("componentName", comp.name);

            // 1. 找所有引用该组件的 tc
            List<TemplateComponent> allTcs = TemplateComponent.list("componentId = ?1", comp.id);

            // 2. 收集非 NULL 的 fields_override，选字段数最多的作为权威版
            List<Map<String, Object>> bestFields = null;
            String bestDriverPathOverride = null;
            int bestFieldCount = -1;

            for (TemplateComponent tc : allTcs) {
                if (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank()) {
                    try {
                        List<Map<String, Object>> fields = MAPPER.readValue(tc.fieldsOverride,
                                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                        if (fields.size() > bestFieldCount) {
                            bestFieldCount = fields.size();
                            bestFields = fields;
                            // 同时记录该 tc 的 dataDriverPathOverride（作为新 component.dataDriverPath 候选）
                            if (tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank()) {
                                bestDriverPathOverride = tc.dataDriverPathOverride;
                            }
                        }
                    } catch (Exception e) {
                        LOG.warnf("[promote-override] componentId=%s tc=%s fieldsOverride parse error: %s",
                                comp.id, tc.id, e.getMessage());
                    }
                }
                // 即使 fieldsOverride 为 NULL，也尝试提取 dataDriverPathOverride（作为兜底候选）
                if (bestDriverPathOverride == null && tc.dataDriverPathOverride != null
                        && !tc.dataDriverPathOverride.isBlank()) {
                    bestDriverPathOverride = tc.dataDriverPathOverride;
                }
            }

            if (bestFields == null) {
                detail.put("status", "SKIPPED_NO_FIELDS_OVERRIDE");
                detail.put("reason", "所有 tc.fields_override 均为 NULL，无法推断权威字段配置");
                details.add(detail);
                LOG.infof("[promote-override] componentId=%s SKIPPED: no fields_override found", comp.id);
                continue;
            }

            // 3. 更新 component.fields（旧字段数 → 新字段数）
            int oldFieldCount;
            try {
                List<?> oldFields = parseJsonArray(comp.fields);
                oldFieldCount = oldFields.size();
            } catch (Exception e) {
                oldFieldCount = 0;
            }

            try {
                comp.fields = MAPPER.writeValueAsString(bestFields);
                comp.columnCount = bestFields.size();
            } catch (Exception e) {
                detail.put("status", "ERROR");
                detail.put("reason", "component.fields 序列化失败: " + e.getMessage());
                details.add(detail);
                LOG.warnf("[promote-override] componentId=%s fields serialize error: %s", comp.id, e.getMessage());
                continue;
            }

            // 4. 更新 component.dataDriverPath（如果有 override）
            String oldDriverPath = comp.dataDriverPath;
            if (bestDriverPathOverride != null) {
                comp.dataDriverPath = bestDriverPathOverride;
            }
            comp.updatedAt = java.time.OffsetDateTime.now();

            detail.put("oldFieldCount", oldFieldCount);
            detail.put("newFieldCount", bestFields.size());
            detail.put("oldDriverPath", oldDriverPath);
            detail.put("newDriverPath", comp.dataDriverPath);
            totalComponentsUpdated++;

            // 5. 清空所有 tc 的 fields_override + dataDriverPathOverride
            int tcCleared = 0;
            for (TemplateComponent tc : allTcs) {
                boolean wasChanged = false;
                if (tc.fieldsOverride != null) {
                    tc.fieldsOverride = null;
                    wasChanged = true;
                }
                if (tc.dataDriverPathOverride != null) {
                    tc.dataDriverPathOverride = null;
                    wasChanged = true;
                }
                if (wasChanged) tcCleared++;
            }
            detail.put("tcCleared", tcCleared);
            totalTcCleared += tcCleared;

            // 6. 刷新所有模板 snapshot（基于新 component.fields，tc.fields_override 已清空）
            try {
                List<UUID> affected = refreshSnapshotsByComponent(comp.id);
                detail.put("snapshotTouched", affected.size());
                totalSnapshotTouched += affected.size();
                detail.put("status", "OK");
            } catch (Exception e) {
                detail.put("status", "PARTIAL");
                detail.put("snapshotError", e.getMessage());
                LOG.warnf("[promote-override] componentId=%s snapshot refresh error: %s", comp.id, e.getMessage());
            }

            details.add(detail);
            LOG.infof("[promote-override] componentId=%s: fields %d→%d, driverPath %s→%s, tcCleared=%d",
                    comp.id, oldFieldCount, bestFields.size(), oldDriverPath, comp.dataDriverPath, tcCleared);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("targetComponents", targets.size());
        summary.put("componentsUpdated", totalComponentsUpdated);
        summary.put("tcCleared", totalTcCleared);
        summary.put("snapshotTouched", totalSnapshotTouched);
        summary.put("details", details);
        LOG.infof("[promote-override] done: components=%d updated=%d tcCleared=%d snapshotTouched=%d",
                targets.size(), totalComponentsUpdated, totalTcCleared, totalSnapshotTouched);
        return summary;
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
