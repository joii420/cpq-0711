package com.cpq.component.service;

import com.cpq.component.dto.ComponentSqlViewDTO;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.dto.DryRunSqlViewResponse;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 组件 SQL 视图业务服务（方案 §5）。
 *
 * <p>职责：CRUD + dry-run 校验 + 双层冻结协调（snapshotForTemplate / snapshotForQuotation 由
 * TemplateService / QuotationService 在状态机过渡时调）+ BnfPathResolver 接入点 lookupForResolver。
 */
@ApplicationScoped
public class ComponentSqlViewService {

    private static final Logger LOG = Logger.getLogger(ComponentSqlViewService.class);

    /** sql_view_name 命名规则：小写字母开头，字母数字下划线。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]{0,79}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ComponentSqlViewRepository repository;

    @Inject
    SqlViewValidator validator;

    /** 阶段 3: snapshot 反序列化用（quotation_component_sql_snapshot 表 native SQL 查询）。 */
    @Inject
    EntityManager em;

    // ────────────────────────────────── CRUD ──────────────────────────────────

    public List<ComponentSqlViewDTO> listByComponent(UUID componentId) {
        String code = lookupComponentCode(componentId);
        return repository.listByComponent(componentId).stream()
                .map(entity -> ComponentSqlViewDTO.from(entity, code))
                .collect(Collectors.toList());
    }

    public ComponentSqlViewDTO get(UUID componentId, UUID id) {
        ComponentSqlView entity = repository.findById(id);
        if (entity == null || !entity.componentId.equals(componentId)) {
            throw new NotFoundException("SQL 视图不存在：" + id);
        }
        return ComponentSqlViewDTO.from(entity, lookupComponentCode(componentId));
    }

    /** 单查 component.code，找不到返 null（DTO 容忍）。 */
    public String lookupComponentCode(UUID componentId) {
        if (componentId == null) return null;
        Component c = Component.findById(componentId);
        return c == null ? null : c.code;
    }

    // ──────────────────────── 双层冻结：序列化工具 ──────────────────────────

    /**
     * 把指定组件列表关联的所有 SQL 视图（含 GLOBAL scope 跨组件闭包）序列化为
     * snapshot JSONB Map，key = {@code "componentId::sql_view_name"}。
     *
     * <p>用于：
     * <ul>
     *   <li>模板 DRAFT → PUBLISHED 时由 TemplateService 调，写入 template.sql_views_snapshot</li>
     *   <li>报价单 DRAFT → SUBMITTED 时由 QuotationService 调，写入 quotation_component_sql_snapshot 表</li>
     * </ul>
     */
    public Map<String, Map<String, Object>> snapshotForComponents(List<UUID> componentIds) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (componentIds == null || componentIds.isEmpty()) return result;

        // 1. 本组件 SQL 视图（COMPONENT + GLOBAL）
        Set<UUID> seen = new HashSet<>(componentIds);
        for (UUID cid : componentIds) {
            List<ComponentSqlView> views = repository.listByComponent(cid);
            for (ComponentSqlView v : views) {
                addSnapshotEntry(result, v);
            }
        }

        // 2. GLOBAL scope 闭包：扫所有 GLOBAL 视图，确保跨组件引用回放可用
        //    (保守冻结所有 GLOBAL 避免依赖分析；规模有限，可接受)
        for (ComponentSqlView gv : repository.listAllGlobal()) {
            if (!seen.contains(gv.componentId)) {
                seen.add(gv.componentId);
            }
            addSnapshotEntry(result, gv);
        }
        return result;
    }

    private void addSnapshotEntry(Map<String, Map<String, Object>> result, ComponentSqlView v) {
        String key = v.componentId.toString() + "::" + v.sqlViewName;
        if (result.containsKey(key)) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sql_template", v.sqlTemplate);
        entry.put("declared_columns", v.declaredColumns);
        entry.put("required_variables", v.requiredVariables == null
                ? List.of() : Arrays.asList(v.requiredVariables));
        entry.put("scope", v.scope);
        result.put(key, entry);
    }

    @Transactional
    public ComponentSqlViewDTO create(UUID componentId, CreateComponentSqlViewRequest req, UUID createdBy) {
        validateName(req.sqlViewName);

        // ACTIVE 同名 → 409 拒绝
        Optional<ComponentSqlView> activeDup = repository.findByComponentAndName(componentId, req.sqlViewName);
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

        // PG UNIQUE (component_id, sql_view_name) 不区分 status：
        // 若存在 INACTIVE 同名记录（软删除残留），复活并替换内容；语义：删后同名 create = 复活
        Optional<ComponentSqlView> inactiveDup = repository.findAnyByComponentAndName(componentId, req.sqlViewName);
        if (inactiveDup.isPresent()) {
            ComponentSqlView reuse = inactiveDup.get();
            reuse.sqlTemplate = req.sqlTemplate;
            reuse.declaredColumns = serializeColumns(dryRun.declaredColumns);
            reuse.requiredVariables = dryRun.requiredVariables == null
                    ? new String[0]
                    : dryRun.requiredVariables.toArray(new String[0]);
            reuse.scope = normalizeScope(req.scope);
            reuse.status = "ACTIVE";
            reuse.description = req.description;
            LOG.infof("[ComponentSqlView] revived INACTIVE record id=%s name=%s",
                    reuse.id, req.sqlViewName);
            return ComponentSqlViewDTO.from(reuse, lookupComponentCode(componentId));
        }

        ComponentSqlView entity = new ComponentSqlView();
        entity.componentId = componentId;
        entity.sqlViewName = req.sqlViewName;
        entity.sqlTemplate = req.sqlTemplate;
        entity.declaredColumns = serializeColumns(dryRun.declaredColumns);
        entity.requiredVariables = dryRun.requiredVariables == null
                ? new String[0]
                : dryRun.requiredVariables.toArray(new String[0]);
        entity.scope = normalizeScope(req.scope);
        entity.status = "ACTIVE";
        entity.description = req.description;
        entity.createdBy = createdBy;

        repository.persist(entity);
        LOG.infof("[ComponentSqlView] created componentId=%s name=%s scope=%s",
                componentId, req.sqlViewName, entity.scope);
        return ComponentSqlViewDTO.from(entity, lookupComponentCode(componentId));
    }

    @Transactional
    public ComponentSqlViewDTO update(UUID componentId, UUID id, CreateComponentSqlViewRequest req) {
        ComponentSqlView entity = repository.findById(id);
        if (entity == null || !entity.componentId.equals(componentId)) {
            throw new NotFoundException("SQL 视图不存在：" + id);
        }

        // 改名时校验
        if (req.sqlViewName != null && !req.sqlViewName.equals(entity.sqlViewName)) {
            validateName(req.sqlViewName);
            Optional<ComponentSqlView> conflict = repository.findByComponentAndName(componentId, req.sqlViewName);
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

        if (req.scope != null) {
            entity.scope = normalizeScope(req.scope);
        }
        if (req.description != null) {
            entity.description = req.description;
        }

        LOG.infof("[ComponentSqlView] updated id=%s componentId=%s", id, componentId);
        return ComponentSqlViewDTO.from(entity, lookupComponentCode(componentId));
    }

    @Transactional
    public void delete(UUID componentId, UUID id) {
        ComponentSqlView entity = repository.findById(id);
        if (entity == null || !entity.componentId.equals(componentId)) {
            throw new NotFoundException("SQL 视图不存在：" + id);
        }

        // 阶段 3: 跨组件引用检查 — 删除前扫 component.fields JSONB 中是否含本视图引用
        List<Map<String, Object>> refs = findReferences(entity);
        if (!refs.isEmpty()) {
            // 拼出受影响清单返回前端展示
            StringBuilder hint = new StringBuilder();
            hint.append("无法删除：该 SQL 视图被以下组件引用，请先在引用方移除路径：");
            for (Map<String, Object> r : refs) {
                hint.append(" [").append(r.get("code")).append(" / ").append(r.get("name")).append("]");
            }
            // 409 Conflict + 详细信息
            jakarta.ws.rs.core.Response resp = jakarta.ws.rs.core.Response
                    .status(jakarta.ws.rs.core.Response.Status.CONFLICT)
                    .entity(Map.of("message", hint.toString(), "references", refs))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
            throw new WebApplicationException(hint.toString(), resp);
        }

        // 软删除（保留快照回放能力）
        entity.status = "INACTIVE";
        LOG.infof("[ComponentSqlView] soft-deleted id=%s componentId=%s name=%s",
                id, componentId, entity.sqlViewName);
    }

    /**
     * 阶段 3: 找出引用本 SQL 视图的所有组件（仅检查活跃 component.fields，不检查 snapshot —— snapshot 已闭包独立副本）。
     *
     * <p>引用形态：
     * <ul>
     *   <li>本组件 COMPONENT scope: {@code $<sqlViewName>}（仅本 component.fields 可能含）</li>
     *   <li>跨组件 GLOBAL scope: {@code $$<componentCode>.<sqlViewName>}（任何 component.fields 可能含）</li>
     * </ul>
     *
     * <p>使用 PG 正则匹配 {@code component.fields::text ~ '\$xxx\b'} 风格，word boundary 防止
     * {@code $foo} 被误匹配为 {@code $foobar}。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findReferences(ComponentSqlView view) {
        List<Map<String, Object>> refs = new java.util.ArrayList<>();
        String componentCode = lookupComponentCode(view.componentId);
        // 转义正则元字符（sql_view_name 实际由 NAME_PATTERN 约束为 [a-z_0-9]，不会有特殊字符，但稳妥处理）
        String nameEsc = java.util.regex.Pattern.quote(view.sqlViewName)
                .replace("\\Q", "").replace("\\E", "");

        try {
            // 1. 本组件 COMPONENT 引用（$name 形态）— 扫该 component 自身 fields
            String selfSql = "SELECT id, code, name FROM component " +
                             "WHERE id = ?1 AND status = 'ACTIVE' " +
                             "AND fields::text ~ ?2";
            // 正则: \$name\b 但不能匹配 \$\$name（前置非 $）
            String selfPattern = "(?<!\\$)\\$" + nameEsc + "\\b";
            List<Object[]> selfRows = em.createNativeQuery(selfSql)
                    .setParameter(1, view.componentId)
                    .setParameter(2, selfPattern)
                    .getResultList();
            for (Object[] row : selfRows) {
                refs.add(Map.of(
                        "id", row[0].toString(),
                        "code", String.valueOf(row[1]),
                        "name", String.valueOf(row[2]),
                        "refType", "$" + view.sqlViewName + "（本组件）"
                ));
            }

            // 2. GLOBAL 跨组件引用（$$code.name）— 仅当本视图为 GLOBAL scope 时扫全表
            if ("GLOBAL".equals(view.scope) && componentCode != null) {
                String codeEsc = java.util.regex.Pattern.quote(componentCode)
                        .replace("\\Q", "").replace("\\E", "");
                String crossSql = "SELECT id, code, name FROM component " +
                                  "WHERE status = 'ACTIVE' AND fields::text ~ ?1";
                String crossPattern = "\\$\\$" + codeEsc + "\\." + nameEsc + "\\b";
                List<Object[]> crossRows = em.createNativeQuery(crossSql)
                        .setParameter(1, crossPattern)
                        .getResultList();
                for (Object[] row : crossRows) {
                    refs.add(Map.of(
                            "id", row[0].toString(),
                            "code", String.valueOf(row[1]),
                            "name", String.valueOf(row[2]),
                            "refType", "$$" + componentCode + "." + view.sqlViewName + "（跨组件 GLOBAL）"
                    ));
                }
            }
        } catch (Exception e) {
            LOG.warnf("[findReferences] scan failed (treat as no references): %s", e.getMessage());
        }
        return refs;
    }

    // ────────────────────────── Dry-run（公开接口） ──────────────────────────

    public DryRunSqlViewResponse dryRun(String sqlTemplate) {
        return validator.validate(sqlTemplate);
    }

    // ─────────────────────── BnfPathResolver 接入点 ────────────────────────

    /**
     * SqlViewExecutor / SqlViewPathRewriter 调用入口，实现方案 §5.3 三层 fallback：
     * <ol>
     *   <li>报价单 APPROVED/PUBLISHED/SUBMITTED → 查 quotation_component_sql_snapshot 表（阶段 3 接入）</li>
     *   <li>模板已发布上下文 → 读 template.sql_views_snapshot JSONB（阶段 3 接入）</li>
     *   <li>兜底：实时读 component_sql_view（DRAFT 期 / 无 snapshot 上下文）</li>
     * </ol>
     *
     * <p>SqlViewRuntimeContext ThreadLocal 提供 templateId / quotationId / quotationStatus
     * 三个维度，调用方（ComponentDriverService）在 expand 入口 set，finally clear。
     *
     * @param componentId 当前组件 ID（本组件引用时必传）
     * @param sqlViewName SQL 视图名
     * @param isCrossComponent true 表示 {@code $$code.name} 跨组件引用
     * @param componentCode 跨组件时的组件 code
     * @return 命中的 SQL 视图（可能是 DB 持久实体，也可能是从 snapshot 反序列化的 detached 实例）
     */
    public Optional<ComponentSqlView> lookupForResolver(
            UUID componentId, String sqlViewName, boolean isCrossComponent, String componentCode) {

        SqlViewRuntimeContext.Snapshot ctx = SqlViewRuntimeContext.get();

        // 1. 报价单冻结优先（APPROVED/PUBLISHED/SUBMITTED 状态读 quotation_component_sql_snapshot）
        if (ctx.quotationId != null && ctx.isQuotationFrozen()) {
            Optional<ComponentSqlView> fromQuotation = lookupFromQuotationSnapshot(
                    ctx.quotationId, componentId, sqlViewName, isCrossComponent, componentCode);
            if (fromQuotation.isPresent()) {
                LOG.debugf("[lookupForResolver] hit quotation snapshot quotationId=%s name=%s",
                        ctx.quotationId, sqlViewName);
                return fromQuotation;
            }
        }

        // 2. 模板已发布 snapshot 优先（template.sql_views_snapshot JSONB）
        if (ctx.templateId != null) {
            Optional<ComponentSqlView> fromTemplate = lookupFromTemplateSnapshot(
                    ctx.templateId, componentId, sqlViewName, isCrossComponent, componentCode);
            if (fromTemplate.isPresent()) {
                LOG.debugf("[lookupForResolver] hit template snapshot templateId=%s name=%s",
                        ctx.templateId, sqlViewName);
                return fromTemplate;
            }
        }

        // 3. 兜底实时读
        if (isCrossComponent) {
            if (componentCode == null || componentCode.isBlank()) {
                return Optional.empty();
            }
            return repository.findGlobalByComponentCodeAndName(componentCode, sqlViewName);
        }
        if (componentId == null) {
            return Optional.empty();
        }
        return repository.findByComponentAndName(componentId, sqlViewName);
    }

    // ──────────── snapshot 反序列化辅助（阶段 3） ────────────

    @SuppressWarnings("unchecked")
    private Optional<ComponentSqlView> lookupFromQuotationSnapshot(
            UUID quotationId, UUID componentId, String sqlViewName,
            boolean isCrossComponent, String componentCode) {
        try {
            String sql;
            String matchKey;
            if (isCrossComponent) {
                // GLOBAL 跨组件：snapshot 存的是 "componentId::sql_view_name"，需按 sql_view_name 后缀模糊匹配
                sql = "SELECT sql_view_key, sql_template, declared_columns, required_variables " +
                      "FROM quotation_component_sql_snapshot " +
                      "WHERE quotation_id = ?1 AND sql_view_key LIKE ?2";
                matchKey = "%::" + sqlViewName;
            } else {
                if (componentId == null) return Optional.empty();
                sql = "SELECT sql_view_key, sql_template, declared_columns, required_variables " +
                      "FROM quotation_component_sql_snapshot " +
                      "WHERE quotation_id = ?1 AND sql_view_key = ?2";
                matchKey = componentId + "::" + sqlViewName;
            }
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter(1, quotationId)
                    .setParameter(2, matchKey)
                    .getResultList();
            if (rows.isEmpty()) return Optional.empty();
            Object[] r = rows.get(0);
            return Optional.of(buildDetachedFromSnapshot(
                    (String) r[0], (String) r[1], r[2], r[3]));
        } catch (Exception e) {
            LOG.warnf("[lookupFromQuotationSnapshot] query failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ComponentSqlView> lookupFromTemplateSnapshot(
            UUID templateId, UUID componentId, String sqlViewName,
            boolean isCrossComponent, String componentCode) {
        try {
            Template t = Template.findById(templateId);
            if (t == null || t.sqlViewsSnapshot == null || t.sqlViewsSnapshot.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(t.sqlViewsSnapshot);
            if (!root.isObject()) return Optional.empty();

            String matchKey = isCrossComponent
                    ? "::" + sqlViewName        // 后缀匹配（跨组件按 name 找 GLOBAL 项）
                    : (componentId != null ? componentId + "::" + sqlViewName : null);
            if (matchKey == null) return Optional.empty();

            java.util.Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                boolean matched = isCrossComponent ? key.endsWith(matchKey) : key.equals(matchKey);
                if (!matched) continue;
                JsonNode entry = root.get(key);
                if (entry == null || !entry.isObject()) continue;
                // GLOBAL 校验
                if (isCrossComponent) {
                    JsonNode scopeNode = entry.get("scope");
                    if (scopeNode == null || !"GLOBAL".equals(scopeNode.asText())) continue;
                }
                return Optional.of(buildDetachedFromSnapshot(
                        key,
                        entry.has("sql_template") ? entry.get("sql_template").asText() : "",
                        entry.has("declared_columns") ? entry.get("declared_columns") : "[]",
                        entry.has("required_variables") ? entry.get("required_variables") : null
                ));
            }
            return Optional.empty();
        } catch (Exception e) {
            LOG.warnf("[lookupFromTemplateSnapshot] parse failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 snapshot 数据构造 detached ComponentSqlView（非持久化，仅用于 lookupForResolver 返回）。
     */
    private ComponentSqlView buildDetachedFromSnapshot(
            String sqlViewKey, String sqlTemplate, Object declaredColumns, Object requiredVariables) {
        ComponentSqlView v = new ComponentSqlView();
        // 从 key 反解 componentId + name
        int idx = sqlViewKey.indexOf("::");
        if (idx > 0) {
            try { v.componentId = UUID.fromString(sqlViewKey.substring(0, idx)); } catch (Exception ignored) {}
            v.sqlViewName = sqlViewKey.substring(idx + 2);
        } else {
            v.sqlViewName = sqlViewKey;
        }
        v.sqlTemplate = sqlTemplate;
        v.declaredColumns = serializeJsonField(declaredColumns);
        v.requiredVariables = toStringArray(requiredVariables);
        v.status = "ACTIVE";   // snapshot 项视作 ACTIVE 回放
        v.scope = "COMPONENT"; // snapshot 时已闭包，scope 无运行时意义
        return v;
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
        if (o instanceof java.sql.Array sqlArr) {
            try {
                Object[] arr = (Object[]) sqlArr.getArray();
                String[] out = new String[arr.length];
                for (int i = 0; i < arr.length; i++) out[i] = String.valueOf(arr[i]);
                return out;
            } catch (Exception ignored) {}
        }
        return new String[0];
    }

    // ──────────────────────────── 内部工具 ────────────────────────────────

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new WebApplicationException(
                    "SQL 视图名称非法：必须以小写字母或下划线开头，仅含小写字母数字下划线，长度≤80",
                    Response.Status.BAD_REQUEST);
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null) return "COMPONENT";
        String upper = scope.toUpperCase();
        if (!upper.equals("COMPONENT") && !upper.equals("GLOBAL")) {
            throw new WebApplicationException(
                    "scope 仅允许 COMPONENT / GLOBAL", Response.Status.BAD_REQUEST);
        }
        return upper;
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
}
