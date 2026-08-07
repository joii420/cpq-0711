package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * task-0806 B11：模板发布全量冻结的体检 + 差异服务。
 *
 * <ol>
 *   <li><b>frozen-drift</b>（A2 单模板 / A3 admin 批量，兼迁移前体检 A）——对比
 *       {@code template_component_snapshot} 冻结值 vs 当前活 {@code template_component}
 *       + {@code component} 配置，逐字段输出差异。语义：迁移刚完成时应全零；随组件迭代自然
 *       增长——这是严格版本化下的正常状态，不是故障。</li>
 *   <li><b>sqlview-closure-check</b>（A4，迁移前体检 B）——验证每个已发布模板引用到的 SQL
 *       视图（{@code $view} / {@code $$code.view} 语法）都在 {@code sql_views_snapshot}
 *       闭包内。是 FR-6「SQL 视图 fallback 切报错」的前置门槛（D13，结果须交用户拍板）。</li>
 * </ol>
 */
@ApplicationScoped
public class TemplateFreezeDriftService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 匹配本组件 SQL 视图引用 {@code $view_name}（排除 {@code $$code.view} 的第二个 $）。 */
    private static final Pattern SELF_REF = Pattern.compile("(?<!\\$)\\$([a-z_][a-z0-9_]{0,79})\\b");
    /** 匹配跨组件 GLOBAL 引用 {@code $$componentCode.view_name}。 */
    private static final Pattern CROSS_REF = Pattern.compile("\\$\\$([A-Za-z][A-Za-z0-9_-]*)\\.([a-z_][a-z0-9_]{0,79})\\b");

    @Inject
    EntityManager em;

    // ============================================================================
    // A2 / A3：frozen vs live 差异
    // ============================================================================

    /** A2：单模板差异。DRAFT 模板无快照可比 → 400。 */
    public Map<String, Object> driftOf(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if ("DRAFT".equals(t.status)) {
            throw new BusinessException(400, "DRAFT 模板无快照，无差异可比");
        }
        return computeDrift(t);
    }

    /** A3：批量差异（admin，兼迁移前体检 A）。 */
    public Map<String, Object> driftOfMany(List<String> statuses, boolean onlyDrift) {
        List<Template> templates = (statuses == null || statuses.isEmpty())
                ? Template.list("status IN ?1", List.of("PUBLISHED", "ARCHIVED"))
                : Template.list("status IN ?1", statuses);

        int withDrift = 0;
        int totalFieldDrifts = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Template t : templates) {
            Map<String, Object> d = computeDrift(t);
            boolean hasDrift = Boolean.TRUE.equals(d.get("hasDrift"));
            int cnt = (Integer) d.get("driftCount");
            if (hasDrift) {
                withDrift++;
                totalFieldDrifts += cnt;
            }
            if (!onlyDrift || hasDrift) results.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", templates.size());
        out.put("withDrift", withDrift);
        out.put("totalFieldDrifts", totalFieldDrifts);
        out.put("templates", results);
        return out;
    }

    private Map<String, Object> computeDrift(Template t) {
        List<TemplateComponentSnapshot> frozenRows =
                TemplateComponentSnapshot.list("templateId = ?1 ORDER BY sortOrder ASC", t.id);
        List<TemplateComponent> tcs = TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", t.id);

        Map<Integer, TemplateComponent> tcBySort = new LinkedHashMap<>();
        for (TemplateComponent tc : tcs) tcBySort.put(tc.sortOrder, tc);

        List<UUID> liveComponentIds = new ArrayList<>();
        for (TemplateComponent tc : tcs) liveComponentIds.add(tc.componentId);
        Map<UUID, Component> compById = loadComponentsByIds(liveComponentIds);

        int driftCount = 0;
        OffsetDateTime frozenAt = frozenRows.isEmpty() ? null : frozenRows.get(0).frozenAt;
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (TemplateComponentSnapshot frozen : frozenRows) {
            TemplateComponent tc = tcBySort.get(frozen.sortOrder);
            Component comp = (tc != null) ? compById.get(tc.componentId) : null;
            boolean componentExists = tc != null && comp != null;

            Map<String, Object> tab = new LinkedHashMap<>();
            tab.put("sortOrder", frozen.sortOrder);
            tab.put("tabName", frozen.tabName);
            tab.put("componentId", frozen.componentId.toString());
            tab.put("componentCode", frozen.componentCode);
            tab.put("componentExists", componentExists);

            List<Map<String, Object>> fieldDrifts = componentExists
                    ? diffFields(frozen, tc, comp)
                    : List.of();
            tab.put("fieldDrifts", fieldDrifts);
            driftCount += fieldDrifts.size();
            tabs.add(tab);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", t.id.toString());
        out.put("templateName", t.name);
        out.put("templateVersion", t.version);
        out.put("templateStatus", t.status);
        out.put("frozenAt", frozenAt);
        out.put("hasDrift", driftCount > 0);
        out.put("driftCount", driftCount);
        out.put("tabs", tabs);
        return out;
    }

    /**
     * 逐字段比较冻结行 vs 当前活配置（override 优先语义与 publish() 一致）。
     * JSON 结构字段按 {@link JsonNode} 深度相等比较（避免格式化空白差异误报为 drift）；
     * 标量字段按字符串 null-safe 相等比较。
     */
    private List<Map<String, Object>> diffFields(TemplateComponentSnapshot frozen, TemplateComponent tc, Component comp) {
        List<Map<String, Object>> drifts = new ArrayList<>();

        addIfDiffScalar(drifts, "tabName", frozen.tabName, tc.tabName);
        addIfDiffJson(drifts, "preset_rows", frozen.presetRows, orDefault(tc.presetRows, "[]"));
        addIfDiffJson(drifts, "formula_assignments", frozen.formulaAssignments, orDefault(tc.formulaAssignments, "{}"));

        addIfDiffScalar(drifts, "componentName", frozen.componentName, comp.name);
        addIfDiffScalar(drifts, "componentCode", frozen.componentCode, comp.code);
        addIfDiffScalar(drifts, "componentType", frozen.componentType, comp.componentType);
        addIfDiffScalar(drifts, "columnCount",
                frozen.columnCount == null ? null : frozen.columnCount.toString(),
                comp.columnCount == null ? null : comp.columnCount.toString());

        String liveFields = (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank()) ? tc.fieldsOverride : comp.fields;
        addIfDiffJson(drifts, "fields", frozen.fields, liveFields);
        addIfDiffJson(drifts, "formulas", frozen.formulas, comp.formulas);
        addIfDiffJson(drifts, "excel_columns", frozen.excelColumns, comp.excelColumns);

        String liveDriverPath = (tc.dataDriverPathOverride != null && !tc.dataDriverPathOverride.isBlank())
                ? tc.dataDriverPathOverride : comp.dataDriverPath;
        addIfDiffScalar(drifts, "data_driver_path", frozen.dataDriverPath, liveDriverPath);

        addIfDiffJson(drifts, "tree_config", frozen.treeConfig, comp.treeConfig);
        addIfDiffScalar(drifts, "bom_recursive_expand",
                frozen.bomRecursiveExpand == null ? null : frozen.bomRecursiveExpand.toString(),
                comp.bomRecursiveExpand == null ? null : comp.bomRecursiveExpand.toString());
        addIfDiffScalar(drifts, "tab_type", frozen.tabType, comp.tabType);
        addIfDiffScalar(drifts, "part_no_field", frozen.partNoField, comp.partNoField);
        addIfDiffScalar(drifts, "part_name_field", frozen.partNameField, comp.partNameField);
        addIfDiffJson(drifts, "row_key_fields", frozen.rowKeyFields, comp.rowKeyFields);
        addIfDiffScalar(drifts, "sort_field", frozen.sortField, comp.sortField);
        addIfDiffScalar(drifts, "element_code_field", frozen.elementCodeField, comp.elementCodeField);
        addIfDiffScalar(drifts, "element_price_field", frozen.elementPriceField, comp.elementPriceField);
        addIfDiffScalar(drifts, "element_currency_field", frozen.elementCurrencyField, comp.elementCurrencyField);

        return drifts;
    }

    private void addIfDiffScalar(List<Map<String, Object>> drifts, String field, String frozenValue, String liveValue) {
        if (Objects.equals(frozenValue, liveValue)) return;
        drifts.add(driftEntry(field, frozenValue, liveValue));
    }

    private void addIfDiffJson(List<Map<String, Object>> drifts, String field, String frozenJson, String liveJson) {
        JsonNode a = parseOrNull(frozenJson);
        JsonNode b = parseOrNull(liveJson);
        boolean equal = (a == null && b == null) || (a != null && a.equals(b));
        if (equal) return;
        drifts.add(driftEntry(field, frozenJson, liveJson));
    }

    private Map<String, Object> driftEntry(String field, String frozenValue, String liveValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("frozenValue", frozenValue);
        m.put("liveValue", liveValue);
        return m;
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String orDefault(String s, String def) {
        return (s != null && !s.isBlank()) ? s : def;
    }

    private Map<UUID, Component> loadComponentsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Component> comps = Component.list("id IN ?1", ids);
        Map<UUID, Component> map = new LinkedHashMap<>();
        for (Component c : comps) map.put(c.id, c);
        return map;
    }

    // ============================================================================
    // A4：sqlview-closure-check（迁移前体检 B，D13 前置门槛）
    // ============================================================================

    /**
     * 验证每个 {@code statuses} 状态的模板，其冻结快照（{@code template_component_snapshot}）
     * 里 {@code data_driver_path} / {@code fields} / {@code formulas} 引用到的 {@code $view} /
     * {@code $$code.view} SQL 视图，都能在该模板的 {@code sql_views_snapshot} 闭包内命中。
     *
     * <p>命中判定与运行期 {@code ComponentSqlViewService#lookupFromTemplateSnapshot} 完全同口径：
     * 本组件引用按 {@code componentId::viewName} 精确匹配；跨组件 GLOBAL 引用按
     * {@code ::viewName} 后缀匹配且要求 {@code scope=GLOBAL}。
     */
    public Map<String, Object> sqlviewClosureCheck(List<String> statuses) {
        List<Template> templates = (statuses == null || statuses.isEmpty())
                ? Template.list("status IN ?1", List.of("PUBLISHED", "ARCHIVED"))
                : Template.list("status IN ?1", statuses);

        int totalRefs = 0;
        List<Map<String, Object>> misses = new ArrayList<>();

        for (Template t : templates) {
            Map<String, JsonNode> sqlViewsSnapshot = parseSqlViewsSnapshot(t.sqlViewsSnapshot);
            List<TemplateComponentSnapshot> rows =
                    TemplateComponentSnapshot.list("templateId = ?1 ORDER BY sortOrder ASC", t.id);

            for (TemplateComponentSnapshot row : rows) {
                Set<String> selfRefs = new LinkedHashSet<>();
                Set<String[]> crossRefs = new LinkedHashSet<>();
                scanRefs(row.dataDriverPath, selfRefs, crossRefs);
                scanRefs(row.fields, selfRefs, crossRefs);
                scanRefs(row.formulas, selfRefs, crossRefs);

                for (String viewName : selfRefs) {
                    totalRefs++;
                    String key = row.componentId + "::" + viewName;
                    if (sqlViewsSnapshot.isEmpty()) {
                        misses.add(missEntry(t, row, viewName, false, "SNAPSHOT_EMPTY"));
                    } else if (!sqlViewsSnapshot.containsKey(key)) {
                        misses.add(missEntry(t, row, viewName, false, "NOT_IN_SNAPSHOT"));
                    }
                }
                for (String[] cross : crossRefs) {
                    totalRefs++;
                    String viewName = cross[1];
                    String suffix = "::" + viewName;
                    boolean hit = false;
                    for (Map.Entry<String, JsonNode> e : sqlViewsSnapshot.entrySet()) {
                        if (e.getKey().endsWith(suffix)) {
                            JsonNode scope = e.getValue().get("scope");
                            if (scope != null && "GLOBAL".equals(scope.asText())) {
                                hit = true;
                                break;
                            }
                        }
                    }
                    if (!hit) {
                        misses.add(missEntry(t, row, cross[0] + "." + viewName, true,
                                sqlViewsSnapshot.isEmpty() ? "SNAPSHOT_EMPTY" : "NOT_IN_SNAPSHOT"));
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", templates.size());
        out.put("totalRefs", totalRefs);
        out.put("missCount", misses.size());
        out.put("misses", misses);
        return out;
    }

    private Map<String, Object> missEntry(Template t, TemplateComponentSnapshot row, String sqlViewName,
                                           boolean crossComponent, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.id.toString());
        m.put("templateName", t.name);
        m.put("componentId", row.componentId.toString());
        m.put("componentCode", row.componentCode);
        m.put("sqlViewName", sqlViewName);
        m.put("crossComponent", crossComponent);
        m.put("reason", reason);
        return m;
    }

    /** 扫描一段文本（raw JSON 或纯文本 path）里的全部 {@code $view} / {@code $$code.view} 引用。 */
    private void scanRefs(String text, Set<String> selfRefs, Set<String[]> crossRefs) {
        if (text == null || text.isBlank()) return;
        Matcher cm = CROSS_REF.matcher(text);
        while (cm.find()) {
            crossRefs.add(new String[]{cm.group(1), cm.group(2)});
        }
        Matcher sm = SELF_REF.matcher(text);
        while (sm.find()) {
            selfRefs.add(sm.group(1));
        }
    }

    private Map<String, JsonNode> parseSqlViewsSnapshot(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) return Map.of();
            Map<String, JsonNode> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
