package com.cpq.template.service;

import com.cpq.basicdata.entity.ProductCategory;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.exception.FormulaCycleException;
import com.cpq.component.entity.Component;
import com.cpq.component.service.ComponentSqlViewService;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.service.CrossTabComponentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.QuoteImportAutoDefaults;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
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

    /** 阶段 2: 组件 SQL 视图冻结服务（模板 PUBLISHED 时调 snapshotForComponents 写 sql_views_snapshot） */
    @Inject
    ComponentSqlViewService componentSqlViewService;

    /** V250: 模板自有 SQL 视图冻结服务（模板 PUBLISHED 时写 template_sql_views_snapshot） */
    @Inject
    TemplateSqlViewService templateSqlViewService;

    /** Task 3.1: 列定义统一从 EXCEL 组件解析（校验等迭代列定义站点用）。 */
    @Inject
    com.cpq.quotation.service.ExcelColumnResolver excelColumnResolver;

    /** task-0806：admin 后门审计（confirm=true 时写 operation_log，与业务写入同事务同生共死）。 */
    @Inject
    com.cpq.system.service.OperationLogService operationLogService;

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

        // 小计配置可选(2026-06-10 解除强制): 模板可不配 subtotalFormula、不拖 SUBTOTAL 组件而发布;
        // 此时产品小计运行期默认 = 各页签总计之和(前端 computeProductSubtotal 兜底)。
        // 配了 subtotalFormula token 或 SUBTOTAL 组件则照其公式算(行为不变)。
        long tcCount = TemplateComponent.count("templateId", id);
        if (tcCount == 0) {
            throw new BusinessException("模板发布前必须至少包含一个组件");
        }

        // task-0806 B5：唯一写入点。V200 override 优先语义（同 component 在 SIMPLE / COMPOSITE
        // 模板需要不同 driver_path / fields, 走 template_component.data_driver_path_override /
        // fields_override; 非 NULL 时盖掉 component 表对应字段）保持不变。
        //
        // N+1 自检：原实现在本循环 + 下方 cross_tab_ref 校验循环内各自对每个 tc 调一次
        // Component.findById（2 处循环 × N 次查询）。本次改造顺带批量化——整单一次 IN 查
        // 预载 compById，两个循环均改用内存 map 查找，SQL 条数与 tc 数量无关。
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        Map<UUID, Component> compById = loadComponentsByIds(
                tcs.stream().map(tc -> tc.componentId).distinct().collect(Collectors.toList()));

        // 唯一写入点：template_component_snapshot 落 N 行（按 tc 逐行，不得按 componentId 聚合
        // ——AP-40 教训；(template_id, template_component_id) 唯一约束已从 schema 层面消灭该坑，
        // 未用 sort_order 是因为实测存量数据里同模板内 sort_order 并非天然唯一）。
        // 重发布场景防脏行：先清掉该模板旧快照行（正常首次 publish 时应为空，防御性操作）。
        List<TemplateComponentSnapshot> snapshotRows = persistSnapshotRows(id, tcs, compById);
        // components_snapshot jsonb 从①派生（不再各自拼装）——两份数据同源同事务生成，
        // 结构上不可能不一致（AC-2：键集合/键顺序/值与改造前逐字段一致）。
        template.componentsSnapshot = deriveComponentsSnapshotJson(snapshotRows);

        // Task 2.2: 模板级 cross_tab_ref 校验 — 每个组件公式里的 cross_tab_ref.source 必须指向
        // 本卡片内存在的组件(componentId), 且组件间 cross_tab_ref 依赖不得成环。
        // 组件级 (Task 2.1) 只能校验 token 结构, 跨组件存在性 / 环检测必须在模板层做。
        {
            List<String> compIds = new ArrayList<>();
            Map<String, JsonNode> formulasByCompId = new LinkedHashMap<>();
            Map<String, String> namesById = new LinkedHashMap<>();
            // repair-0803：环检测改与渲染期同口径（列粒度 component_subtotal 边），
            // 需要 fields 判断被引用列是否公式列；plainNames 供链路文案（不带 CODE 后缀）。
            List<CrossTabComponentOrder.TabDep> tabDeps = new ArrayList<>();
            Map<String, String> plainNameById = new LinkedHashMap<>();
            for (TemplateComponent tc : tcs) {
                Component comp = compById.get(tc.componentId);
                if (comp == null) continue;
                String cid = comp.id.toString();
                compIds.add(cid);
                JsonNode fx = parseJsonNode(comp.formulas);
                formulasByCompId.put(cid, fx);
                namesById.put(cid, comp.name + (comp.code != null && !comp.code.isBlank()
                        ? " (" + comp.code + ")" : ""));
                plainNameById.put(cid, comp.name);
                tabDeps.add(new CrossTabComponentOrder.TabDep(
                        cid, comp.code, comp.name, fx, parseJsonNode(comp.fields)));
            }
            validateCrossTabRefs(compIds, formulasByCompId, namesById, tabDeps, plainNameById);
        }

        // 阶段 2: 冻结 component_sql_view 闭包到 template.sql_views_snapshot
        //   - 扫该模板挂载的所有组件 → 序列化其 SQL 视图（含 GLOBAL scope 闭包）
        //   - 与 components_snapshot 同事务（@Transactional 已覆盖整个 publish）
        List<UUID> componentIds = tcs.stream()
                .map(tc -> tc.componentId)
                .collect(Collectors.toList());
        Map<String, Map<String, Object>> sqlViewsSnapshot =
                componentSqlViewService.snapshotForComponents(componentIds);
        template.sqlViewsSnapshot = toJson(sqlViewsSnapshot);
        LOG.infof("[TemplateService.publish] frozen %d sql_views for template=%s",
                sqlViewsSnapshot.size(), id);

        // V250: 校验 excel_view_config 中不含 $$ 跨组件引用（模板隔离规则）
        validateNoDoubleDollarRefsInExcelView(template);

        // V250: 冻结 template_sql_view 到 template.template_sql_views_snapshot
        try {
            Map<String, Map<String, Object>> tsvSnapshot =
                    templateSqlViewService.snapshotForTemplate(id);
            template.templateSqlViewsSnapshot = toJson(tsvSnapshot);
            LOG.infof("[TemplateService.publish] frozen %d template_sql_views for template=%s",
                    tsvSnapshot.size(), id);
        } catch (Exception e) {
            LOG.warnf("[TemplateService.publish] failed to build template_sql_views_snapshot: %s",
                    e.getMessage());
            template.templateSqlViewsSnapshot = "{}";
        }

        // PRD 多版本语义:同 series 升级时,旧 PUBLISHED 保持原状态,由用户后续主动归档。
        // V62 已撤销 V28 partial unique index,同 (customer_id, category_id) 允许多个 PUBLISHED 共存。

        // Calculate version
        template.version = calculateNextVersion(template.templateSeriesId, request);
        template.status = "PUBLISHED";
        template.publishedAt = OffsetDateTime.now();

        LOG.infof("[perf] renderTemplate publish templateId=%s tabs=%d sql=constant(batched)", id, tcs.size());
        LOG.infof("Published template id=%s version=%s", id, template.version);
        return TemplateDTO.from(template, tcs);
    }

    // task-0806 B6：refreshSnapshotsByComponent（H1）整体退役。
    //
    // 该方法曾是"改一个组件、无条件静默改写所有引用它的已发布模板 snapshot"的活穿透源头
    // （ComponentService.update:733 每次保存组件即自动调用）——违背 docs/三大核心模块基线.md:178
    // 写明的"snapshot 存在的理由：组件管理改字段后老模板不能反向受影响"。
    //
    // 下线后：
    //   - DRAFT 模板恒无快照，该方法的作用面从来只有 PUBLISHED + ARCHIVED；冻结之后它没有
    //     任何合法用途（不是限制它只刷 DRAFT，是整个下线）。
    //   - ComponentService.update / setDriverView 两处自动传导调用点已删除（AC-4）。
    //   - POST /api/cpq/components/{id}/refresh-template-snapshots 路由已删除（AC-4，D11）。
    //   - 保留的 admin 后门（ConfigCenterResource.refreshAllSnapshots / TemplateResource 的
    //     delete-tcs / promote-override-to-component）改走本类下方的 forceRealignSnapshots(...)
    //     ——同样是"明确破坏不可变性"的操作，但批量化实现（SQL 条数与模板数/组件数无关），
    //     且统一加 confirm 预览 + operation_log 审计 + LOG.warn 告警（FR-7）。

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

        // task-0806 B20（D18）：归档是终态，无法再走 createNewDraft → publish 重新发布——若该
        // 模板还没按 B20 后的新语义完成过一次"重新发布"（template_component_snapshot 零行，
        // 过渡期正常状态，见 PublishedTemplateReader 类注释），此处必须按当时活配置补一份快照
        // 再归档，否则归档后 PublishedTemplateReader 会把它的历史报价单渲染永久判定为「未冻结」
        // ——但它已经不可能再发新版来补上，等于永久打不开。复用 publish() 落库的同一套私有方法
        // （persistSnapshotRows + deriveComponentsSnapshotJson），保证"补冻"产出的快照结构与
        // 正常发布路径完全一致，不另起一套写法。
        if (TemplateComponentSnapshot.count("templateId", id) == 0) {
            List<TemplateComponent> tcsForFreeze =
                    TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
            Map<UUID, Component> compByIdForFreeze = loadComponentsByIds(
                    tcsForFreeze.stream().map(tc -> tc.componentId).distinct().collect(Collectors.toList()));
            List<TemplateComponentSnapshot> freezeRows =
                    persistSnapshotRows(id, tcsForFreeze, compByIdForFreeze);
            template.componentsSnapshot = deriveComponentsSnapshotJson(freezeRows);
            LOG.infof("[task-0806 B20] archive() 自动补冻：templateId=%s 此前未按新语义重新发布过，"
                    + "补 %d 行快照后再归档", id, freezeRows.size());
        }

        template.status = "ARCHIVED";
        LOG.infof("Archived template id=%s", id);
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        return TemplateDTO.from(template, tcs);
    }

    /**
     * task-0806 B22-a（D20）：已 PUBLISHED/ARCHIVED 但从未按新语义冻结过的模板，补一次「首次
     * 冻结」——不改 {@code version}/{@code status}/{@code publishedAt}，只补
     * {@code template_component_snapshot} + 同步派生 {@code components_snapshot} jsonb。
     *
     * <p><b>背景</b>：D17 的 409 文案指向"重新发布"，但 {@link #publish} 只收 DRAFT——已发布
     * 模板根本没有"重新发布"这个操作。{@code createNewDraft → publish} 只会产出新版本
     * （老版本永远冻不上）；唯一能就地补冻的通道此前只有 admin 后门
     * {@link #forceRealignSnapshots}，但那个方法的语义是"明确破坏不可变性"，不该是入门路径。
     *
     * <p><b>零行守卫是这个操作结构上安全的全部理由</b>：仅当该模板当前一行快照都没有时才允许
     * 执行——见下方守卫判断。守卫不许放松：一旦允许对已有快照的模板调用本方法，它就退化成
     * 又一个能覆盖快照的后门，与 D20 的设计初衷相悖。
     *
     * @throws BusinessException 400，模板是 DRAFT（草稿本就不该有快照，发布时才生成）
     * @throws BusinessException 409，模板已有快照行（不是"从未冻结"，应走
     *         {@code createNewDraft → publish} 发新版本，而不是就地覆盖）
     */
    @Transactional
    public TemplateDTO freeze(UUID id, UUID operatorId) {
        Template template = Template.findById(id);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + id);
        }
        if ("DRAFT".equals(template.status)) {
            throw new BusinessException(400,
                    "DRAFT 模板不支持首次冻结：草稿期本就不写快照，发布（publish）时会自动生成，"
                    + "templateId=" + id);
        }

        // 零行守卫（核心，不许放松）：仅当该模板 template_component_snapshot 行数 == 0 时才允许
        // 执行——结构上保证本方法不可能覆盖已有快照，因此不破坏不可变性，可以放心开给业务角色
        // （SALES_MANAGER），不必只留给 SYSTEM_ADMIN 后门。
        if (TemplateComponentSnapshot.count("templateId", id) > 0) {
            throw new BusinessException(409,
                    "该模板已冻结，如需更新配置请走 createNewDraft → publish 发布新版本。"
                    + "templateId=" + id + ", status=" + template.status);
        }

        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", id);
        // 按当前活配置就地冻一份：复用 publish()/archive() 的同一套落库私有方法
        // （persistSnapshotRows + deriveComponentsSnapshotJson），保证"首次冻结"产出的快照结构
        // 与正常发布路径完全一致，不另起一套写法。version / status / publishedAt 一律不动。
        rebuildSnapshotForTemplate(template, tcs);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endpoint", "freeze");
        details.put("tabCount", tcs.size());
        // 不打「破坏不可变性」的 WARN——零行守卫保证这不是覆盖，是给从未冻过的东西补冻，
        // 是正常业务操作，用 INFO 级别记录即可。
        operationLogService.log(operatorId, "TEMPLATE_INITIAL_FREEZE", "TEMPLATE", id,
                "首次冻结模板：" + template.name + (template.version != null ? " " + template.version : "")
                        + "，" + tcs.size() + " 个页签", details);
        LOG.infof("[task-0806 B22-a] freeze：templateId=%s status=%s 首次冻结 %d 行快照"
                + "（非破坏性操作——冻结前快照行数恒为 0）", id, template.status, tcs.size());

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

        // V250: deep-copy template_sql_view 行（source → draft）
        // 新草稿拥有独立的 SQL 视图列表，与源模板完全解耦
        templateSqlViewService.deepCopySqlViews(sourceId, draft.id);

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
    //
    // task-0806 D11：migrate-to-unified-view（一次性历史迁移，基线 §3.5 标注「已跑过」）
    // 整体删除——不做 410 过渡，直接下线路由 + 服务方法（AC-4）。

    /**
     * 重建 PUBLISHED / ARCHIVED 模板的 {@code components_snapshot} + {@code template_component_snapshot}
     * （基于当前 tc 配置，不改 status/version）。task-0806 起：先清后插整表重建，落库逻辑与
     * {@link #publish} 的落库段完全对齐（同一 {@link #persistSnapshotRows} / {@link #deriveComponentsSnapshotJson}
     * 实现，两处不可能分叉）。
     */
    private void rebuildSnapshotForTemplate(Template tpl, List<TemplateComponent> tcs) {
        Map<UUID, Component> compById = loadComponentsByIds(
                tcs.stream().map(tc -> tc.componentId).distinct().collect(Collectors.toList()));
        List<TemplateComponentSnapshot> rows = persistSnapshotRows(tpl.id, tcs, compById);
        tpl.componentsSnapshot = deriveComponentsSnapshotJson(rows);
    }

    /**
     * 2026-05-20 admin endpoint: 按 sortOrder 删除 PUBLISHED 模板的 tc 记录.
     *
     * <p>task-0806 B7 改造（FR-7 / api.md §7 A6）：加 {@code confirm} 预览门槛 + 执行时写
     * {@code operation_log} 审计 + {@code LOG.warn} 告警。{@code confirm=false}（含缺省）
     * 只读不写，返回待删 Tab 清单；{@code confirm=true} 才真正删除并同步快照。
     *
     * @param confirm    false=仅预览零写入；true=执行
     * @param operatorId 当前操作者（执行时写审计用；预览路径不需要）
     */
    @Transactional
    public Map<String, Object> deleteTemplateComponentsBySortOrder(UUID templateId, List<Integer> sortOrders,
                                                                     boolean confirm, UUID operatorId) {
        Template tpl = Template.findById(templateId);
        if (tpl == null) throw new BusinessException(404, "Template not found: " + templateId);

        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);
        List<TemplateComponent> toDelete = new ArrayList<>();
        for (TemplateComponent tc : tcs) {
            if (sortOrders != null && sortOrders.contains(tc.sortOrder)) toDelete.add(tc);
        }

        if (!confirm) {
            Map<UUID, Component> compById = loadComponentsByIds(
                    toDelete.stream().map(tc -> tc.componentId).distinct().collect(Collectors.toList()));
            List<Map<String, Object>> tabsToDelete = new ArrayList<>();
            for (TemplateComponent tc : toDelete) {
                Component c = compById.get(tc.componentId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sortOrder", tc.sortOrder);
                m.put("tabName", tc.tabName);
                m.put("componentCode", c != null ? c.code : null);
                tabsToDelete.add(m);
            }
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("preview", true);
            preview.put("templateId", templateId.toString());
            preview.put("tabsToDelete", tabsToDelete);
            LOG.warnf("[admin-backdoor] delete-tcs 预览（未执行）：templateId=%s sortOrders=%s "
                    + "——确认执行将破坏模板不可变性", templateId, sortOrders);
            return preview;
        }

        String snapshotBefore = tpl.componentsSnapshot;
        int deleted = 0;
        for (TemplateComponent tc : toDelete) {
            tc.delete();
            deleted++;
        }

        // 重建 snapshot（同步删对应快照行 + 重生成 jsonb，否则快照与 tc 不一致）
        List<TemplateComponent> remaining = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);
        rebuildSnapshotForTemplate(tpl, remaining);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preview", false);
        result.put("deletedTcs", deleted);
        result.put("snapshotBefore", snapshotBefore);
        result.put("snapshotAfter", tpl.componentsSnapshot);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endpoint", "delete-tcs");
        details.put("sortOrders", sortOrders);
        details.put("deletedTcs", deleted);
        details.put("before", snapshotBefore);
        details.put("after", tpl.componentsSnapshot);
        UUID logId = operationLogService.log(operatorId, "TEMPLATE_TC_DELETE", "TEMPLATE", templateId,
                "删除模板 Tab：" + tpl.name + (tpl.version != null ? " " + tpl.version : "")
                        + "，" + deleted + " 个页签", details);
        result.put("operationLogId", logId != null ? logId.toString() : null);
        LOG.warnf("[admin-backdoor] delete-tcs 已执行：templateId=%s 已破坏模板不可变性，deleted=%d", templateId, deleted);
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
     *   <li>强制重新对齐所有受影响模板 snapshot（{@link #forceRealignSnapshots}，
     *       task-0806 起替代已退役的 refreshSnapshotsByComponent）</li>
     * </ol>
     *
     * <p>约束：不动 SIMPLE 产品逻辑；不动 DATA_SOURCE.GLOBAL_VARIABLE 字段；零回归。
     *
     * <p>task-0806 B7（FR-7 / api.md §7 A7）：加 {@code confirm} 预览门槛 + 执行时写
     * {@code operation_log} 审计（按受影响模板各写一行）+ {@code LOG.warn} 告警。
     *
     * @param componentIds 目标组件 ID 列表，null 或空则对所有 ACTIVE 组件处理（慎用）
     * @param confirm      false=仅预览零写入；true=执行
     * @param operatorId   当前操作者（执行时写审计用）
     * @return 迁移摘要（confirm=false 时为预览摘要）
     */
    @Transactional
    public Map<String, Object> promoteOverrideToComponent(List<UUID> componentIds, boolean confirm, UUID operatorId) {
        List<Component> targets = resolvePromoteTargets(componentIds);

        if (!confirm) {
            return buildPromotePreview(targets);
        }

        List<Map<String, Object>> details = new ArrayList<>();
        int totalComponentsUpdated = 0;
        int totalTcCleared = 0;
        int totalSnapshotTouched = 0;
        Set<UUID> allAffectedTemplateIds = new LinkedHashSet<>();

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

            // 6. 强制重新对齐所有受影响模板 snapshot（基于新 component.fields，tc.fields_override
            // 已清空）——task-0806 起替代已退役的 refreshSnapshotsByComponent，走批量化实现。
            List<UUID> affectedTemplateIds = allTcs.stream().map(tc -> tc.templateId)
                    .distinct().collect(Collectors.toList());
            try {
                Map<String, Object> realign = forceRealignSnapshots(affectedTemplateIds);
                int touched = ((Number) realign.getOrDefault("refreshedTemplates", 0)).intValue();
                detail.put("snapshotTouched", touched);
                totalSnapshotTouched += touched;
                detail.put("status", "OK");
            } catch (Exception e) {
                detail.put("status", "PARTIAL");
                detail.put("snapshotError", e.getMessage());
                LOG.warnf("[promote-override] componentId=%s snapshot realign error: %s", comp.id, e.getMessage());
            }
            allAffectedTemplateIds.addAll(affectedTemplateIds);

            details.add(detail);
            LOG.infof("[promote-override] componentId=%s: fields %d→%d, driverPath %s→%s, tcCleared=%d",
                    comp.id, oldFieldCount, bestFields.size(), oldDriverPath, comp.dataDriverPath, tcCleared);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("preview", false);
        summary.put("targetComponents", targets.size());
        summary.put("componentsUpdated", totalComponentsUpdated);
        summary.put("tcCleared", totalTcCleared);
        summary.put("snapshotTouched", totalSnapshotTouched);
        summary.put("details", details);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("endpoint", "promote-override-to-component");
        auditDetails.put("componentIds", targets.stream().map(c -> c.id.toString()).collect(Collectors.toList()));
        auditDetails.put("fieldDrifts", details);
        List<UUID> logIds = operationLogService.logBatch(operatorId, "TEMPLATE_OVERRIDE_PROMOTE", "TEMPLATE",
                allAffectedTemplateIds,
                "提升 fields_override 为 component.fields：" + totalComponentsUpdated + " 个组件，"
                        + "强制重新对齐 " + allAffectedTemplateIds.size() + " 个模板快照",
                auditDetails);
        summary.put("operationLogId", logIds.isEmpty() ? null : logIds.get(logIds.size() - 1).toString());
        summary.put("operationLogIds", logIds.stream().map(UUID::toString).collect(Collectors.toList()));

        LOG.warnf("[admin-backdoor] promote-override-to-component 已执行：已破坏 %d 个模板的不可变性 "
                        + "(components=%d updated=%d tcCleared=%d snapshotTouched=%d)",
                allAffectedTemplateIds.size(), targets.size(), totalComponentsUpdated, totalTcCleared, totalSnapshotTouched);
        return summary;
    }

    /** {@link #promoteOverrideToComponent} 的目标组件解析，preview/execute 两路共用。 */
    private List<Component> resolvePromoteTargets(List<UUID> componentIds) {
        List<Component> targets;
        if (componentIds == null || componentIds.isEmpty()) {
            // 安全兜底：不提供 componentIds 时只处理名称以"选配-"开头的组件（避免误操作）
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT id FROM component WHERE name LIKE '选配-%' AND status = 'ACTIVE'")
                    .getResultList();
            List<UUID> ids = new ArrayList<>();
            for (Object row : rows) ids.add(row instanceof UUID u ? u : UUID.fromString(row.toString()));
            targets = new ArrayList<>(loadComponentsByIds(ids).values());
        } else {
            targets = new ArrayList<>(loadComponentsByIds(componentIds).values());
        }
        return targets;
    }

    /** {@link #promoteOverrideToComponent} 的 confirm=false 预览路径：零写入。 */
    private Map<String, Object> buildPromotePreview(List<Component> targets) {
        Set<UUID> allAffectedTemplateIds = new LinkedHashSet<>();
        List<Map<String, Object>> targetSummaries = new ArrayList<>();
        for (Component comp : targets) {
            List<TemplateComponent> allTcs = TemplateComponent.list("componentId = ?1", comp.id);
            Set<UUID> tids = allTcs.stream().map(tc -> tc.templateId).collect(Collectors.toCollection(LinkedHashSet::new));
            allAffectedTemplateIds.addAll(tids);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("componentId", comp.id.toString());
            m.put("componentName", comp.name);
            m.put("affectedTemplateCount", tids.size());
            targetSummaries.add(m);
        }
        List<Map<String, Object>> affectedTemplates = new ArrayList<>();
        for (UUID tid : allAffectedTemplateIds) {
            Template t = Template.findById(tid);
            if (t == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", t.id.toString());
            m.put("name", t.name);
            m.put("version", t.version);
            m.put("status", t.status);
            affectedTemplates.add(m);
        }
        long quotationCount = countQuotationsForTemplates(allAffectedTemplateIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview", true);
        out.put("targetComponents", targetSummaries);
        out.put("affectedTemplates", affectedTemplates);
        out.put("affectedTemplateCount", allAffectedTemplateIds.size());
        out.put("affectedQuotationCount", quotationCount);
        out.put("warning", "此操作将把 template_component.fields_override 上升为 component.fields，"
                + "并强制重新对齐所有引用模板的冻结快照，破坏版本不可变性。受影响的在途报价单渲染结果可能变化。");
        LOG.warnf("[admin-backdoor] promote-override-to-component 预览（未执行）：targetComponents=%d "
                + "affectedTemplates=%d", targets.size(), allAffectedTemplateIds.size());
        return out;
    }

    /** 公开包装，供 {@code ConfigCenterResource}（A5 预览）复用。 */
    public long countQuotationsReferencingTemplates(Collection<UUID> templateIds) {
        return countQuotationsForTemplates(templateIds);
    }

    /** 引用给定模板集合的报价单总数（一次 SQL，供 admin 后门预览用）。 */
    private long countQuotationsForTemplates(Collection<UUID> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) return 0;
        Number count = (Number) em.createNativeQuery(
                "SELECT count(DISTINCT id) FROM quotation WHERE customer_template_id = ANY(:tids) "
                        + "OR costing_card_template_id = ANY(:tids)")
                .setParameter("tids", templateIds.toArray(new UUID[0]))
                .getSingleResult();
        return count != null ? count.longValue() : 0;
    }

    // ---- task-0806 B5：唯一写入点的落库 + 派生辅助（publish() / rebuildSnapshotForTemplate 共用） ----

    /** 整单一次 IN 查预载组件（N+1 自检：调用方不得在循环里再各自查一次 Component.findById）。 */
    private Map<UUID, Component> loadComponentsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Component> comps = Component.list("id IN ?1", ids);
        Map<UUID, Component> map = new LinkedHashMap<>();
        for (Component c : comps) map.put(c.id, c);
        return map;
    }

    /**
     * 唯一写入点：为该模板落 template_component_snapshot（先清后插，按 tc 逐行，不得按
     * componentId 聚合——AP-40 教训）。{@code compById} 由调用方整单预载，本方法内部零查库。
     *
     * @return 已持久化的快照行，顺序与 {@code tcs}（已按 sortOrder 升序）一致
     */
    private List<TemplateComponentSnapshot> persistSnapshotRows(UUID templateId, List<TemplateComponent> tcs,
                                                                  Map<UUID, Component> compById) {
        TemplateComponentSnapshot.delete("templateId", templateId);
        List<TemplateComponentSnapshot> rows = new ArrayList<>();
        for (TemplateComponent tc : tcs) {
            Component comp = compById.get(tc.componentId);
            if (comp == null) continue;
            TemplateComponentSnapshot s = buildSnapshotEntity(tc, comp);
            s.persist();
            rows.add(s);
        }
        return rows;
    }

    /** 组装一行快照实体（不持久化）——18 个渲染配置字段全冻（FR-1 + FR-4 补齐的 6 个）。 */
    private TemplateComponentSnapshot buildSnapshotEntity(TemplateComponent tc, Component comp) {
        TemplateComponentSnapshot s = new TemplateComponentSnapshot();
        s.templateId = tc.templateId;
        s.templateComponentId = tc.id;
        s.componentId = comp.id;
        s.sortOrder = tc.sortOrder;

        s.tabName = tc.tabName;
        s.presetRows = (tc.presetRows != null && !tc.presetRows.isBlank()) ? tc.presetRows : "[]";
        s.formulaAssignments = (tc.formulaAssignments != null && !tc.formulaAssignments.isBlank())
                ? tc.formulaAssignments : "{}";

        s.componentName = comp.name;
        s.componentCode = comp.code;
        s.componentType = comp.componentType;
        s.columnCount = comp.columnCount != null ? comp.columnCount : 0;
        // V200: fields / data_driver_path 走 override 优先（与改造前 publish() 语义一致）
        s.fields = (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank()) ? tc.fieldsOverride : comp.fields;
        s.formulas = comp.formulas;
        s.excelColumns = comp.excelColumns;
        s.dataDriverPath = (tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank())
                ? tc.dataDriverPathOverride : comp.dataDriverPath;
        s.treeConfig = (comp.treeConfig != null && !comp.treeConfig.isBlank()) ? comp.treeConfig : null;
        s.bomRecursiveExpand = comp.bomRecursiveExpand != null ? comp.bomRecursiveExpand : false;
        s.tabType = comp.tabType;
        s.partNoField = comp.partNoField;
        s.partNameField = comp.partNameField;
        // ② 新补（FR-4）：component 级角色字段，无 tc 级 override 概念
        s.rowKeyFields = comp.rowKeyFields;
        s.sortField = comp.sortField;
        s.elementCodeField = comp.elementCodeField;
        s.elementPriceField = comp.elementPriceField;
        s.elementCurrencyField = comp.elementCurrencyField;
        return s;
    }

    /**
     * components_snapshot jsonb 从 template_component_snapshot 派生（AC-2 硬门槛：键集合/
     * 键顺序/值与改造前逐字段一致——18 个键，形状见改造前 publish() 的 entry 拼装）。
     * {@code rows} 须已按 sortOrder 升序（{@link #persistSnapshotRows} 保证）。
     */
    private String deriveComponentsSnapshotJson(List<TemplateComponentSnapshot> rows) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (TemplateComponentSnapshot s : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", s.templateComponentId.toString());
            entry.put("componentId", s.componentId.toString());
            entry.put("componentName", s.componentName);
            entry.put("componentCode", s.componentCode);
            entry.put("componentType", s.componentType);
            entry.put("excelColumns", parseJsonArray(s.excelColumns));
            entry.put("tabName", s.tabName);
            entry.put("sortOrder", s.sortOrder);
            entry.put("fields", parseJsonArray(s.fields));
            entry.put("formulas", parseJsonArray(s.formulas));
            entry.put("preset_rows", parseJsonArray(s.presetRows));
            entry.put("data_driver_path", s.dataDriverPath);
            entry.put("tree_config", (s.treeConfig != null && !s.treeConfig.isBlank())
                    ? parseJsonObject(s.treeConfig) : null);
            entry.put("formula_assignments", parseJsonObject(s.formulaAssignments));
            entry.put("tab_type", s.tabType);
            entry.put("part_no_field", s.partNoField);
            entry.put("part_name_field", s.partNameField);
            entry.put("bom_recursive_expand", s.bomRecursiveExpand);
            snapshot.add(entry);
        }
        return toJson(snapshot);
    }

    /**
     * B7 A5：把「已发布模板快照」强制重新对齐到当前活组件配置——即 D3 一次性迁移（V382）的
     * 可重复版本。<b>明确破坏不可变性</b>，仅供 admin 后门 {@code confirm=true} 时调用，或
     * {@link #promoteOverrideToComponent} 执行路径内部复用。
     *
     * <p><b>批量化</b>：3 条 SQL 语句（DELETE + INSERT...SELECT + jsonb 重生成 UPDATE），
     * 按 {@code templateIds} 参数化，SQL 条数与模板数/组件数无关（AC-11 同款铁律）——不是循环
     * 调用旧 {@code refreshSnapshotsByComponent} 那种 {@code O(N_template × N_component)}。
     *
     * @param templateIds null/空 → 全部 PUBLISHED + ARCHIVED 模板；否则先过滤出确实
     *                    PUBLISHED/ARCHIVED 的子集（DRAFT 模板无快照可对齐，静默跳过）
     */
    @Transactional
    public Map<String, Object> forceRealignSnapshots(List<UUID> templateIds) {
        List<UUID> targets = resolveTargetTemplateIds(templateIds);
        Map<String, Object> out = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            out.put("refreshedTemplates", 0);
            out.put("refreshedRows", 0);
            return out;
        }
        UUID[] tidArr = targets.toArray(new UUID[0]);

        em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = ANY(:tids)")
                .setParameter("tids", tidArr)
                .executeUpdate();

        int inserted = em.createNativeQuery(
                "INSERT INTO template_component_snapshot (" +
                "    template_id, template_component_id, component_id, sort_order," +
                "    tab_name, preset_rows, formula_assignments," +
                "    component_name, component_code, component_type, column_count," +
                "    fields, formulas, excel_columns, data_driver_path," +
                "    tree_config, bom_recursive_expand, tab_type, part_no_field, part_name_field," +
                "    row_key_fields, sort_field," +
                "    element_code_field, element_price_field, element_currency_field)" +
                " SELECT tc.template_id, tc.id, tc.component_id, tc.sort_order," +
                "        tc.tab_name," +
                "        COALESCE(tc.preset_rows, '[]'::jsonb)," +
                "        COALESCE(tc.formula_assignments, '{}'::jsonb)," +
                "        c.name, c.code, c.component_type, c.column_count," +
                "        COALESCE(tc.fields_override, c.fields)," +
                "        c.formulas, c.excel_columns," +
                "        COALESCE(NULLIF(tc.data_driver_path_override, ''), c.data_driver_path)," +
                "        c.tree_config, c.bom_recursive_expand, c.tab_type, c.part_no_field, c.part_name_field," +
                "        c.row_key_fields, c.sort_field," +
                "        c.element_code_field, c.element_price_field, c.element_currency_field" +
                "   FROM template_component tc" +
                "   JOIN component c ON c.id = tc.component_id" +
                "  WHERE tc.template_id = ANY(:tids)")
                .setParameter("tids", tidArr)
                .executeUpdate();

        em.createNativeQuery(
                "UPDATE template t SET components_snapshot = sub.snap, updated_at = now() FROM (" +
                "  SELECT tcs.template_id, jsonb_agg(jsonb_build_object(" +
                "    'id', tcs.template_component_id, 'componentId', tcs.component_id," +
                "    'componentName', tcs.component_name, 'componentCode', tcs.component_code," +
                "    'componentType', tcs.component_type, 'excelColumns', tcs.excel_columns," +
                "    'tabName', tcs.tab_name, 'sortOrder', tcs.sort_order," +
                "    'fields', tcs.fields, 'formulas', tcs.formulas, 'preset_rows', tcs.preset_rows," +
                "    'data_driver_path', tcs.data_driver_path, 'tree_config', tcs.tree_config," +
                "    'formula_assignments', tcs.formula_assignments, 'tab_type', tcs.tab_type," +
                "    'part_no_field', tcs.part_no_field, 'part_name_field', tcs.part_name_field," +
                "    'bom_recursive_expand', tcs.bom_recursive_expand" +
                "  ) ORDER BY tcs.sort_order) AS snap" +
                "  FROM template_component_snapshot tcs" +
                " WHERE tcs.template_id = ANY(:tids)" +
                " GROUP BY tcs.template_id" +
                ") sub WHERE t.id = sub.template_id")
                .setParameter("tids", tidArr)
                .executeUpdate();

        out.put("refreshedTemplates", targets.size());
        out.put("refreshedRows", inserted);
        LOG.warnf("[admin-backdoor] forceRealignSnapshots：已破坏模板不可变性。templateIds=%s rows=%d",
                targets, inserted);
        return out;
    }

    /**
     * 公开包装：把请求的 templateId 集合过滤成确实 PUBLISHED/ARCHIVED 的子集
     * （不传/空 → 全部 PUBLISHED+ARCHIVED）。供 {@code ConfigCenterResource}（A5 预览）复用，
     * 避免与 {@link #forceRealignSnapshots} 内部同一条过滤 SQL 各写一份。
     */
    public List<UUID> resolvePublishedOrArchivedTemplateIds(List<UUID> requested) {
        return resolveTargetTemplateIds(requested);
    }

    /**
     * A5 confirm=true 执行路径的<b>唯一</b>入口：{@link #forceRealignSnapshots} 快照重写 +
     * {@code operation_log} 审计写在同一个 {@code @Transactional} 方法内，保证「审计与写入
     * 同生共死」（backtask.md §4：不许审计失败而写入成功）。
     *
     * <p>⚠️ 这条方法<b>必须</b>被外部（{@code ConfigCenterResource}）经注入代理跨 bean 调用
     * ——CDI/Quarkus ArC 的 {@code @Transactional} 拦截器只在跨 bean 调用时生效，Resource 类
     * 内部自调用（{@code this.xxx()}）会静默跳过拦截器，之前的实现（Resource 里一个
     * {@code protected @Transactional execute()} 被同类 {@code this.execute()} 调用）就踩了
     * 这个坑——快照重写和审计各自落在独立事务，审计失败时快照重写已提交，破坏了原子性。
     */
    @Transactional
    public Map<String, Object> forceRealignSnapshotsWithAudit(List<UUID> templateIds, UUID operatorId) {
        List<UUID> resolvedIds = resolveTargetTemplateIds(templateIds);
        Map<String, Object> realign = forceRealignSnapshots(resolvedIds);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endpoint", "refresh-all-snapshots");
        details.put("templateIds", resolvedIds.stream().map(UUID::toString).collect(Collectors.toList()));
        details.put("refreshedRows", realign.get("refreshedRows"));
        List<UUID> logIds = operationLogService.logBatch(operatorId, "TEMPLATE_SNAPSHOT_FORCE_REFRESH", "TEMPLATE",
                resolvedIds, "强制重新对齐已发布模板快照：" + resolvedIds.size() + " 个模板", details);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refreshedTemplates", realign.get("refreshedTemplates"));
        out.put("refreshedRows", realign.get("refreshedRows"));
        out.put("operationLogIds", logIds.stream().map(UUID::toString).collect(Collectors.toList()));
        return out;
    }

    /** 解析 forceRealignSnapshots 的目标模板：过滤出确实 PUBLISHED/ARCHIVED 的子集。 */
    @SuppressWarnings("unchecked")
    private List<UUID> resolveTargetTemplateIds(List<UUID> requested) {
        List<Object> rows;
        if (requested != null && !requested.isEmpty()) {
            rows = em.createNativeQuery(
                    "SELECT id FROM template WHERE id = ANY(:ids) AND status IN ('PUBLISHED','ARCHIVED')")
                    .setParameter("ids", requested.toArray(new UUID[0]))
                    .getResultList();
        } else {
            rows = em.createNativeQuery(
                    "SELECT id FROM template WHERE status IN ('PUBLISHED','ARCHIVED')")
                    .getResultList();
        }
        List<UUID> out = new ArrayList<>();
        for (Object o : rows) out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
        return out;
    }

    // ---- Private helpers ----

    /**
     * Task 2.2: 模板级 cross_tab_ref 校验（package-private, 供单元测试直接驱动, 同时被 publish() 调用）。
     *
     * <p>规则:
     * <ol>
     *   <li>每个组件 formulas 中 cross_tab_ref 的 source 必须是本卡片内的某个组件标识(componentId)。
     *       不在卡片内 → BusinessException(400, "...源组件不在本卡片: <id>")。</li>
     *   <li>组件间 cross_tab_ref 依赖图不得成环（topoOrder 抛 BusinessException）。</li>
     * </ol>
     * 仅计入卡片内的依赖边（卡片外的 source 已先被规则 1 拦截）。
     *
     * @param compIds          本卡片所有成员组件标识（componentId 字符串, 按出现顺序）
     * @param formulasByCompId componentId → 该组件 formulas JsonNode([{expression:[token...]}])
     */
    void validateCrossTabRefs(List<String> compIds, Map<String, JsonNode> formulasByCompId,
                              Map<String, String> namesById) {
        validateCrossTabRefs(compIds, formulasByCompId, namesById, List.of(), Map.of());
    }

    /**
     * repair-0803 重载：环检测改用 {@link CrossTabComponentOrder#buildComponentDeps}，
     * 与<b>渲染期同口径</b>（cross_tab_ref 全量 + component_subtotal 按列粒度）。
     *
     * <p><b>为什么要对齐</b>：旧实现只收 {@code cross_tab_ref} 边，于是「发布通过、一渲染就报环」
     * 成为可能（QT-20260803-0052 即此形态）。发布期与渲染期用同一张依赖图，问题才能在发布时拦下。
     *
     * <p>成环时抛 {@link FormulaCycleException}，携带 {@code scope=TAB} 的结构化链路
     * （节点=页签名称，边=出自哪条公式、引用了对方哪一列），供前端弹抽屉（FR-11）。
     *
     * <p>零破坏：三参签名 delegate 到此并传空 {@code tabDeps} → 回落旧的 cross_tab_ref-only 建图
     * （既有单测行为不变）。
     *
     * @param tabDeps       各页签的建图输入（含 fields，用于列粒度判定）；空 → 回落旧口径
     * @param plainNameById componentId → 页签名称（不带 CODE 后缀），用于链路文案
     */
    void validateCrossTabRefs(List<String> compIds, Map<String, JsonNode> formulasByCompId,
                              Map<String, String> namesById,
                              List<CrossTabComponentOrder.TabDep> tabDeps,
                              Map<String, String> plainNameById) {
        // ① 悬空引用：cross_tab_ref 的 source 必须在本卡片内（口径不变）
        for (String cid : compIds) {
            JsonNode formulas = formulasByCompId.get(cid);
            for (String src : CrossTabComponentOrder.extractSourceRefs(formulas)) {
                if (!compIds.contains(src)) {
                    throw new BusinessException(400,
                            buildCrossTabMissingMessage(cid, src, formulas, namesById));
                }
            }
        }

        // ② 环检测：有 tabDeps 走列粒度（与渲染期一致）；否则回落旧口径
        Map<String, Set<String>> deps;
        if (tabDeps != null && !tabDeps.isEmpty()) {
            deps = CrossTabComponentOrder.buildComponentDeps(tabDeps);
        } else {
            deps = new LinkedHashMap<>();
            for (String cid : compIds) {
                deps.put(cid, CrossTabComponentOrder.extractSourceRefs(formulasByCompId.get(cid)));
            }
        }
        try {
            CrossTabComponentOrder.topoOrder(compIds, deps, plainNameById);
        } catch (BusinessException e) {
            List<FormulaCycleException.Cycle> cycles =
                    buildTabCycles(compIds, deps, formulasByCompId, plainNameById);
            if (cycles.isEmpty()) throw e;   // 提不出链路 → 保留原文案，绝不放过环
            throw new FormulaCycleException(e.getMessage(), cycles);
        }
    }

    /**
     * repair-0803：把页签级环渲染成结构化链路（scope=TAB）。
     *
     * <p>节点 = 页签名称；每条边扫描 from 页签的 formulas，定位「哪条公式引用了 to 页签的哪一列」。
     * 全程只出名称，无 componentId（AC-11）。
     */
    private static List<FormulaCycleException.Cycle> buildTabCycles(
            List<String> compIds, Map<String, Set<String>> deps,
            Map<String, JsonNode> formulasByCompId, Map<String, String> plainNameById) {
        List<String> path = CrossTabComponentOrder.findCyclePath(compIds, deps);
        if (path.isEmpty()) return List.of();

        java.util.function.Function<String, String> nm =
                cid -> plainNameById.getOrDefault(cid, cid);

        List<FormulaCycleException.Node> nodes = new ArrayList<>();
        List<FormulaCycleException.Edge> edges = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            String from = path.get(i), to = path.get((i + 1) % path.size());
            String[] hit = locateCrossTabRef(formulasByCompId.get(from), to, plainNameById);
            nodes.add(new FormulaCycleException.Node(nm.apply(from), null, hit[0]));
            edges.add(new FormulaCycleException.Edge(
                    nm.apply(from), nm.apply(to), hit[1], hit[2], hit[0], "公式"));
        }
        return List.of(new FormulaCycleException.Cycle(
                FormulaCycleException.SCOPE_TAB, null, nodes, edges));
    }

    /**
     * 在 {@code formulas} 里找出引用了 {@code targetCid} 页签的那条引用。
     *
     * @return {@code [公式名, 被引用列名, 列类型说明]}，找不到则相应位为 null
     */
    private static String[] locateCrossTabRef(JsonNode formulas, String targetCid,
                                              Map<String, String> plainNameById) {
        String targetName = plainNameById.get(targetCid);
        if (formulas == null || !formulas.isArray()) return new String[]{null, null, null};
        for (JsonNode f : formulas) {
            JsonNode expr = f.path("expression");
            if (!expr.isArray()) continue;
            for (JsonNode tk : expr) {
                String type = tk.path("type").asText("");
                if ("cross_tab_ref".equals(type) && targetCid.equals(tk.path("source").asText(""))) {
                    return new String[]{f.path("name").asText(null),
                            tk.path("sourceLabel").asText(targetName), "整表引用"};
                }
                if ("component_subtotal".equals(type)) {
                    String ref = !tk.path("component_code").asText("").isBlank()
                            ? tk.path("component_code").asText() : tk.path("tab_name").asText("");
                    // 引用键可能是 code / tabName —— 与目标页签名称或其 code 匹配即认为命中
                    if (!ref.isBlank() && (ref.equals(targetName) || ref.equals(targetCid))) {
                        return new String[]{f.path("name").asText(null),
                                tk.path("value").asText(null), "公式列"};
                    }
                }
            }
        }
        return new String[]{null, null, null};
    }

    /**
     * 组装"跨页签引用的源组件不在本卡片"的可读诊断：定位到具体消费页签 + 公式名 + 源页签名称，
     * 并给出修复指引。名称解析全部取自内存（{@code namesById} 或 token 的 {@code sourceLabel}），
     * <b>不打 DB</b>，以便 {@link #validateCrossTabRefs} 仍可被纯单元测试直接驱动。
     *
     * <p>保留关键词「不在本卡片」与源组件 id 原文（既有测试/告警契约）。
     *
     * @param cid       持有该悬空引用的卡片内组件 id（消费方页签）
     * @param src       被引用但不在卡片内的源组件 id
     * @param formulas  cid 组件的 formulas 节点（用于定位公式名 + sourceLabel）
     * @param namesById 卡片内组件 id → "名称 (CODE)" 显示名（源组件不在卡片内，通常查不到）
     */
    private static String buildCrossTabMissingMessage(String cid, String src, JsonNode formulas,
                                                      Map<String, String> namesById) {
        String consumerName = (namesById != null) ? namesById.getOrDefault(cid, cid) : cid;
        String formulaName = null;
        String sourceLabel = null;
        if (formulas != null && formulas.isArray()) {
            outer:
            for (JsonNode f : formulas) {
                JsonNode expr = f.path("expression");
                if (!expr.isArray()) continue;
                for (JsonNode tk : expr) {
                    if ("cross_tab_ref".equals(tk.path("type").asText())
                            && src.equals(tk.path("source").asText())) {
                        formulaName = f.path("name").asText(null);
                        sourceLabel = tk.path("sourceLabel").asText(null);
                        break outer;
                    }
                }
            }
        }
        String sourceName = (sourceLabel != null && !sourceLabel.isBlank())
                ? sourceLabel
                : (namesById != null ? namesById.get(src) : null);

        StringBuilder msg = new StringBuilder();
        msg.append("页签「").append(consumerName).append("」");
        if (formulaName != null && !formulaName.isBlank()) {
            msg.append("的公式「").append(formulaName).append("」");
        }
        msg.append("引用了另一个页签");
        if (sourceName != null && !sourceName.isBlank()) {
            msg.append("「").append(sourceName).append("」");
        }
        msg.append("的数据，但该源页签不在本卡片内。请把该源页签组件加入本模板卡片，")
           .append("或删除/改写这条跨页签引用后再发布。（缺失源组件 id: ").append(src).append("）");
        return msg.toString();
    }

    private JsonNode parseJsonNode(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    /**
     * V250: 校验 excel_view_config 中的 variable_path 不含 $$ 跨组件引用（模板隔离规则）。
     *
     * @throws BusinessException 400 若发现 variable_path 含 $$
     */
    private void validateNoDoubleDollarRefsInExcelView(Template t) {
        if (t.excelViewConfig == null || t.excelViewConfig.isBlank()
                || "[]".equals(t.excelViewConfig.trim())
                || "{}".equals(t.excelViewConfig.trim())) {
            return;
        }
        try {
            // Task 3.1: 列定义统一从 EXCEL 组件解析（含旧裸数组向后兼容）
            List<Map<String, Object>> columns = excelColumnResolver.getEffectiveColumns(t);
            for (Map<String, Object> col : columns) {
                Object vpObj = col.get("variable_path");
                if (vpObj == null) continue;
                String vp = vpObj.toString().trim();
                if (vp.startsWith("$$")) {
                    String colKey = col.get("col_key") != null ? col.get("col_key").toString() : "?";
                    throw new BusinessException(400,
                            "模板 Excel 视图列 " + colKey + " 含跨组件引用 $$，发布前请改为本模板自有视图 $<view>.<col>。"
                            + "当前路径：" + vp);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("[validateNoDoubleDollarRefsInExcelView] parse failed templateId=%s: %s",
                    t.id, e.getMessage());
        }
    }

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

    public QuoteImportAutoDefaults computeAutoDefaults(UUID customerId) {
        if (customerId == null) {
            throw new BusinessException("customerId 不能为空");
        }
        QuoteImportAutoDefaults out = new QuoteImportAutoDefaults();

        // 1. 最近一张报价单(customerTemplateId 非空),其模板仍存在者优先,createdAt DESC
        List<Quotation> quotes = Quotation.list(
                "customerId = ?1 AND customerTemplateId IS NOT NULL ORDER BY createdAt DESC",
                customerId);
        Template lastTemplate = null;
        for (Quotation q : quotes) {
            Template t = Template.findById(q.customerTemplateId);
            if (t != null) { lastTemplate = t; break; }
        }

        // 2. 分类:有历史从模板反推,无历史退「默认分类」
        UUID categoryId;
        if (lastTemplate != null) {
            categoryId = lastTemplate.categoryId;
        } else {
            ProductCategory def = ProductCategory.find("name = '默认分类'").firstResult();
            categoryId = def != null ? def.id : null;
        }
        out.categoryId = categoryId;
        if (categoryId != null) {
            ProductCategory cat = ProductCategory.findById(categoryId);
            out.categoryName = cat != null ? cat.name : null;
        }

        // 3. 有历史 → 取上次使用线的最新 PUBLISHED 版本(LAST_USED)
        if (lastTemplate != null) {
            Template latest = Template.find(
                    "templateSeriesId = ?1 AND status = 'PUBLISHED' ORDER BY publishedAt DESC NULLS LAST",
                    lastTemplate.templateSeriesId).firstResult();
            if (latest != null) {
                out.customerTemplateId = latest.id;
                out.customerTemplateSeriesId = latest.templateSeriesId;
                out.customerTemplateName = latest.name;
                out.customerTemplateVersion = latest.version;
                out.customerTemplateSource = "LAST_USED";
            }
            // latest == null(整条归档)→ 落入第 4 步兜底
        }

        // 4. 兜底:无历史 / 线失效 → 复用现有匹配(客户专属优先 + publishedAt DESC)
        if (out.customerTemplateId == null) {
            if (categoryId != null) {
                com.cpq.template.dto.TemplateMatchResult match =
                        matchCustomerQuoteTemplate(customerId, categoryId);
                if (match.matchType != com.cpq.template.dto.TemplateMatchResult.MatchType.NONE
                        && match.templates != null && !match.templates.isEmpty()) {
                    TemplateDTO first = match.templates.get(0); // specific 优先, 再 publishedAt DESC
                    out.customerTemplateId = first.id;
                    out.customerTemplateSeriesId = first.templateSeriesId;
                    out.customerTemplateName = first.name;
                    out.customerTemplateVersion = first.version;
                    boolean specific = first.customerId != null && first.customerId.equals(customerId);
                    out.customerTemplateSource = specific ? "CUSTOMER_SPECIFIC_FALLBACK" : "GENERAL_FALLBACK";
                } else {
                    out.customerTemplateSource = "NONE";
                }
            } else {
                out.customerTemplateSource = "NONE";
            }
        }

        // 5. 核价模板(独立,不记忆):客户专属优先 → publishedAt DESC
        if (categoryId != null) {
            Template costing = Template.find(
                    "categoryId = ?1 AND templateKind = 'COSTING' AND status = 'PUBLISHED' "
                            + "AND (customerId = ?2 OR customerId IS NULL) "
                            + "ORDER BY CASE WHEN customerId = ?2 THEN 0 ELSE 1 END, publishedAt DESC NULLS LAST",
                    categoryId, customerId).firstResult();
            if (costing != null) {
                out.costingTemplateId = costing.id;
                out.costingTemplateName = costing.name;
                out.costingTemplateVersion = costing.version;
                boolean specific = costing.customerId != null && costing.customerId.equals(customerId);
                out.costingTemplateSource = specific ? "CUSTOMER_SPECIFIC" : "GENERAL";
            } else {
                out.costingTemplateSource = "NONE";
            }
        } else {
            out.costingTemplateSource = "NONE";
        }

        return out;
    }
}
