package com.cpq.template.service;

import com.cpq.component.dto.DryRunSqlViewResponse;
import com.cpq.component.service.SqlViewValidator;
import com.cpq.template.dto.CreateTemplateSqlViewRequest;
import com.cpq.template.dto.TemplateSqlViewDTO;
import com.cpq.template.dto.UpdateTemplateSqlViewRequest;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateSqlView;
import com.cpq.template.repository.TemplateSqlViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 产品卡片模板 SQL 视图业务服务（Phase 2 迁移自 costing 包）。
 *
 * <p>职责：CRUD + dry-run 校验 + snapshot fallback 查找（lookupForResolver）。
 *
 * <p>与 {@link com.cpq.component.service.ComponentSqlViewService} 同构，区别：
 * <ul>
 *   <li>owner 维度是 {@code templateId}（template.id，不是 componentId）</li>
 *   <li>scope 只允许 LOCAL（不支持 GLOBAL 跨模板引用）</li>
 *   <li>fallback 只有 2 层（template.template_sql_views_snapshot → 实时读），
 *       没有报价单级 snapshot</li>
 * </ul>
 *
 * <p>V249 起替代 Phase 1 的 CostingTemplateSqlViewService。
 */
@ApplicationScoped
public class TemplateSqlViewService {

    private static final Logger LOG = Logger.getLogger(TemplateSqlViewService.class);

    /** sql_view_name 命名规则：小写字母开头，字母数字下划线，长度≤80。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]{0,79}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    TemplateSqlViewRepository repository;

    @Inject
    SqlViewValidator validator;

    // ────────────────────────────────── CRUD ──────────────────────────────────

    public List<TemplateSqlViewDTO> list(UUID templateId) {
        return repository.findActiveByTemplate(templateId).stream()
                .map(TemplateSqlViewDTO::from)
                .collect(Collectors.toList());
    }

    public TemplateSqlViewDTO get(UUID id) {
        TemplateSqlView entity = repository.findById(id);
        if (entity == null) {
            throw new NotFoundException("TemplateSqlView not found: " + id);
        }
        return TemplateSqlViewDTO.from(entity);
    }

    public TemplateSqlViewDTO getForTemplate(UUID templateId, UUID id) {
        TemplateSqlView entity = repository.findById(id);
        if (entity == null || !entity.templateId.equals(templateId)) {
            throw new NotFoundException("TemplateSqlView not found: " + id);
        }
        return TemplateSqlViewDTO.from(entity);
    }

    @Transactional
    public TemplateSqlViewDTO create(UUID templateId,
                                     CreateTemplateSqlViewRequest req,
                                     UUID createdBy) {
        // 校验模板存在且为 DRAFT
        validateTemplateEditable(templateId);
        validateName(req.sqlViewName);

        // ACTIVE 同名 → 409 拒绝
        Optional<TemplateSqlView> activeDup =
                repository.findByTemplateAndName(templateId, req.sqlViewName);
        if (activeDup.isPresent()) {
            throw new WebApplicationException(
                    "SQL 视图名称已存在：" + req.sqlViewName, Response.Status.CONFLICT);
        }

        // dry-run 校验（必须通过）
        DryRunSqlViewResponse dryRun = validator.validate(req.sqlTemplate);
        if (!dryRun.success) {
            throw new WebApplicationException(
                    "SQL 校验未通过：" + dryRun.error, Response.Status.BAD_REQUEST);
        }

        // PG UNIQUE (template_id, sql_view_name) 不区分 status：
        // 若存在 INACTIVE 同名记录（软删除残留），复活并替换内容
        Optional<TemplateSqlView> inactiveDup =
                repository.findAnyByTemplateAndName(templateId, req.sqlViewName);
        if (inactiveDup.isPresent()) {
            TemplateSqlView reuse = inactiveDup.get();
            reuse.sqlTemplate = req.sqlTemplate;
            reuse.declaredColumns = serializeColumns(dryRun.declaredColumns);
            reuse.requiredVariables = dryRun.requiredVariables == null
                    ? new String[0]
                    : dryRun.requiredVariables.toArray(new String[0]);
            reuse.scope = "LOCAL";
            reuse.status = "ACTIVE";
            reuse.description = req.description;
            LOG.infof("[TemplateSqlView] revived INACTIVE record id=%s name=%s",
                    reuse.id, req.sqlViewName);
            return TemplateSqlViewDTO.from(reuse);
        }

        TemplateSqlView entity = new TemplateSqlView();
        entity.templateId = templateId;
        entity.sqlViewName = req.sqlViewName;
        entity.sqlTemplate = req.sqlTemplate;
        entity.declaredColumns = serializeColumns(dryRun.declaredColumns);
        entity.requiredVariables = dryRun.requiredVariables == null
                ? new String[0]
                : dryRun.requiredVariables.toArray(new String[0]);
        entity.scope = "LOCAL";
        entity.status = "ACTIVE";
        entity.description = req.description;
        entity.createdBy = createdBy;

        repository.persist(entity);
        LOG.infof("[TemplateSqlView] created templateId=%s name=%s", templateId, req.sqlViewName);
        return TemplateSqlViewDTO.from(entity);
    }

    @Transactional
    public TemplateSqlViewDTO update(UUID id, UpdateTemplateSqlViewRequest req) {
        TemplateSqlView entity = repository.findById(id);
        if (entity == null) {
            throw new NotFoundException("TemplateSqlView not found: " + id);
        }
        // 校验模板为 DRAFT
        validateTemplateEditable(entity.templateId);

        // 改名时校验
        if (req.sqlViewName != null && !req.sqlViewName.equals(entity.sqlViewName)) {
            validateName(req.sqlViewName);
            Optional<TemplateSqlView> conflict =
                    repository.findByTemplateAndName(entity.templateId, req.sqlViewName);
            if (conflict.isPresent() && !conflict.get().id.equals(id)) {
                throw new WebApplicationException(
                        "SQL 视图名称已存在：" + req.sqlViewName, Response.Status.CONFLICT);
            }
            entity.sqlViewName = req.sqlViewName;
        }

        // 改 SQL 时重跑 dry-run
        if (req.sqlTemplate != null && !req.sqlTemplate.equals(entity.sqlTemplate)) {
            DryRunSqlViewResponse dryRun = validator.validate(req.sqlTemplate);
            if (!dryRun.success) {
                throw new WebApplicationException(
                        "SQL 校验未通过：" + dryRun.error, Response.Status.BAD_REQUEST);
            }
            entity.sqlTemplate = req.sqlTemplate;
            entity.declaredColumns = serializeColumns(dryRun.declaredColumns);
            entity.requiredVariables = dryRun.requiredVariables == null
                    ? new String[0]
                    : dryRun.requiredVariables.toArray(new String[0]);
        }

        if (req.description != null) {
            entity.description = req.description;
        }

        LOG.infof("[TemplateSqlView] updated id=%s", id);
        return TemplateSqlViewDTO.from(entity);
    }

    @Transactional
    public void delete(UUID id) {
        TemplateSqlView entity = repository.findById(id);
        if (entity == null) {
            throw new NotFoundException("TemplateSqlView not found: " + id);
        }
        // 校验模板为 DRAFT
        validateTemplateEditable(entity.templateId);

        // 软删除（保留快照回放能力）
        entity.status = "INACTIVE";
        LOG.infof("[TemplateSqlView] soft-deleted id=%s name=%s", id, entity.sqlViewName);
    }

    // ────────────────────────── Dry-run（公开接口） ──────────────────────────

    public DryRunSqlViewResponse dryRun(String sqlTemplate) {
        return validator.validate(sqlTemplate);
    }

    // ─────────────────────── BnfPathResolver 接入点 ────────────────────────

    /**
     * SqlViewExecutor 调用入口（ownerType=TEMPLATE 时）。
     *
     * <p>fallback 顺序：
     * <ol>
     *   <li>template.template_sql_views_snapshot（PUBLISHED 模板冻结视图定义）</li>
     *   <li>实时读 template_sql_view（DRAFT 期使用）</li>
     * </ol>
     *
     * @param templateId  产品卡片模板 ID（template.id）
     * @param sqlViewName BNF 引用名
     * @return 命中的 SQL 视图（可能是 DB 持久实体，也可能是从 snapshot 反序列化的 detached 实例）
     */
    public Optional<TemplateSqlView> lookupForResolver(UUID templateId, String sqlViewName) {
        if (templateId == null || sqlViewName == null) return Optional.empty();

        // 1. snapshot 优先（PUBLISHED 模板冻结视图定义）
        Optional<TemplateSqlView> fromSnapshot =
                lookupFromTemplateSnapshot(templateId, sqlViewName);
        if (fromSnapshot.isPresent()) {
            LOG.debugf("[TemplateSqlViewService] hit snapshot templateId=%s name=%s",
                    templateId, sqlViewName);
            return fromSnapshot;
        }

        // 2. 兜底实时读
        return repository.findByTemplateAndName(templateId, sqlViewName);
    }

    // ──────────── snapshot 反序列化辅助 ────────────────────────────────────

    private Optional<TemplateSqlView> lookupFromTemplateSnapshot(
            UUID templateId, String sqlViewName) {
        try {
            Template t = Template.findById(templateId);
            if (t == null || t.templateSqlViewsSnapshot == null
                    || t.templateSqlViewsSnapshot.isBlank()
                    || "{}".equals(t.templateSqlViewsSnapshot.trim())) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(t.templateSqlViewsSnapshot);
            if (!root.isObject()) return Optional.empty();

            JsonNode entry = root.get(sqlViewName);
            if (entry == null || !entry.isObject()) return Optional.empty();

            return Optional.of(buildDetachedFromSnapshot(templateId, sqlViewName, entry));
        } catch (Exception e) {
            LOG.warnf("[lookupFromTemplateSnapshot] parse failed templateId=%s name=%s: %s",
                    templateId, sqlViewName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 snapshot JSONB 节点构造 detached TemplateSqlView（非持久化，仅供 lookupForResolver 返回）。
     */
    private TemplateSqlView buildDetachedFromSnapshot(
            UUID templateId, String sqlViewName, JsonNode entry) {
        TemplateSqlView v = new TemplateSqlView();
        v.templateId = templateId;
        v.sqlViewName = sqlViewName;
        v.sqlTemplate = entry.has("sqlTemplate") ? entry.get("sqlTemplate").asText() : "";
        v.declaredColumns = serializeJsonField(
                entry.has("declaredColumns") ? entry.get("declaredColumns") : null);
        v.requiredVariables = toStringArray(
                entry.has("requiredVariables") ? entry.get("requiredVariables") : null);
        v.scope = "LOCAL";
        v.status = "ACTIVE"; // snapshot 项视作 ACTIVE
        return v;
    }

    // ──────────────────── 模板编辑约束校验 ────────────────────────────────────

    /**
     * 校验模板存在且为 DRAFT。CUD 操作前必须通过此检查。
     *
     * @throws WebApplicationException 404（找不到模板）/ 400（不是 DRAFT）
     */
    private void validateTemplateEditable(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) {
            throw new NotFoundException("Template not found: " + templateId);
        }
        if (!"DRAFT".equals(t.status)) {
            throw new WebApplicationException(
                    "只有 DRAFT 状态的模板才能编辑 SQL 视图（当前状态：" + t.status + "）",
                    Response.Status.BAD_REQUEST);
        }
    }

    // ──────────────────────────── 内部工具 ────────────────────────────────────

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new WebApplicationException(
                    "SQL 视图名称非法：必须以小写字母或下划线开头，仅含小写字母数字下划线，长度≤80",
                    Response.Status.BAD_REQUEST);
        }
    }

    private String serializeColumns(List<DryRunSqlViewResponse.ColumnMeta> columns) {
        if (columns == null || columns.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(columns);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize declared_columns: %s", e.getMessage());
            return "[]";
        }
    }

    private String serializeJsonField(Object o) {
        if (o == null) return "[]";
        if (o instanceof String s) return s.isBlank() ? "[]" : s;
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }

    private String[] toStringArray(Object o) {
        if (o == null) return new String[0];
        if (o instanceof String[]) return (String[]) o;
        if (o instanceof JsonNode node && node.isArray()) {
            List<String> out = new java.util.ArrayList<>();
            node.forEach(n -> out.add(n.asText()));
            return out.toArray(new String[0]);
        }
        return new String[0];
    }

    /**
     * 把源模板的 ACTIVE SQL 视图 deep-copy 到新草稿（不同 UUID，templateId 指向 newId）。
     * 由 TemplateService.createNewDraft 调用，确保派生草稿拥有独立的 SQL 视图列表。
     *
     * @param sourceId 源模板 ID
     * @param newId    新草稿模板 ID
     */
    @Transactional
    public void deepCopySqlViews(UUID sourceId, UUID newId) {
        List<TemplateSqlView> srcViews = repository.findActiveByTemplate(sourceId);
        for (TemplateSqlView src : srcViews) {
            TemplateSqlView copy = new TemplateSqlView();
            copy.templateId = newId;
            copy.sqlViewName = src.sqlViewName;
            copy.sqlTemplate = src.sqlTemplate;
            copy.declaredColumns = src.declaredColumns;
            copy.requiredVariables = src.requiredVariables != null
                    ? src.requiredVariables.clone() : new String[0];
            copy.scope = "LOCAL";
            copy.status = "ACTIVE";
            copy.description = src.description;
            // createdBy 留空（派生草稿由操作者创建，不继承原作者）
            repository.persist(copy);
        }
        if (!srcViews.isEmpty()) {
            LOG.infof("[TemplateSqlViewService] deepCopySqlViews: copied %d views from %s to %s",
                    srcViews.size(), sourceId, newId);
        }
    }

    /**
     * 把指定模板所有 ACTIVE 视图序列化为 snapshot JSONB Map。
     * 由 TemplateService.publish 调用，写入 template.template_sql_views_snapshot。
     *
     * @param templateId 产品卡片模板 ID
     * @return Map 格式：{viewName: {sqlTemplate, declaredColumns, requiredVariables}}
     */
    public java.util.Map<String, java.util.Map<String, Object>> snapshotForTemplate(UUID templateId) {
        List<TemplateSqlView> views = repository.findActiveByTemplate(templateId);
        java.util.Map<String, java.util.Map<String, Object>> result = new java.util.LinkedHashMap<>();
        for (TemplateSqlView v : views) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("sqlTemplate", v.sqlTemplate);
            entry.put("declaredColumns", v.declaredColumns);
            entry.put("requiredVariables",
                    v.requiredVariables == null ? List.of() : Arrays.asList(v.requiredVariables));
            result.put(v.sqlViewName, entry);
        }
        return result;
    }
}
