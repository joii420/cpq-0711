package com.cpq.builder.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.CompileDialect;
import com.cpq.builder.compiler.CompileResult;
import com.cpq.builder.dto.BuilderDTOs.*;
import com.cpq.builder.exception.BuilderApiException;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.component.service.ComponentService;
import com.cpq.component.service.ComponentSqlViewService;
import com.cpq.builder.compiler.SemanticCompiler;
import com.cpq.semanticgraph.service.SemanticGraphLoader;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取数配置器 builder 端点族的编排（task-260819 B-11/B-12/B-13/B-15/B-20）。
 *
 * <p>四个动作各自独立可调用：compile（不落库）、preview（真实只读执行）、inspect（保存前体检）、
 * save（一体化保存事务，api.md §2.4 五步原子）、detach（转手写，不可逆）。
 */
@ApplicationScoped
public class BuilderService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NAMED_VAR = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    @Inject SemanticGraphLoader loader;
    @Inject SemanticCompiler compiler;
    @Inject ComponentSqlViewService componentSqlViewService;
    @Inject ComponentSqlViewRepository sqlViewRepository;
    @Inject ComponentService componentService;
    @Inject TemplateService templateService;
    @Inject DataSource dataSource;

    // ---------------- GET / (B-20, AC-34) ----------------

    public GetBuilderResponse get(UUID componentId) {
        Component component = requireComponent(componentId);
        GetBuilderResponse resp = new GetBuilderResponse();
        resp.currentCompilerVersion = SemanticCompiler.CURRENT_VERSION;

        ComponentSqlView view = resolveDrivingView(component);
        if (view == null || view.builderConfig == null) {
            resp.builderConfig = null;
            resp.builderVersion = null;
            resp.isLegacyHandwritten = true;
            resp.isStale = false;
            return resp;
        }
        try {
            resp.builderConfig = MAPPER.readValue(view.builderConfig, BuilderConfig.class);
        } catch (Exception e) {
            throw new BuilderApiException(500, "BUILDER_CONFIG_CORRUPT", "builder_config 解析失败: " + e.getMessage(), Map.of());
        }
        resp.builderVersion = view.builderVersion;
        resp.isLegacyHandwritten = false;
        resp.isStale = view.builderVersion == null || view.builderVersion < SemanticCompiler.CURRENT_VERSION;
        return resp;
    }

    // ---------------- POST /compile (B-5/B-6/B-9/B-10) ----------------

    public CompileResponse compile(UUID componentId, BuilderConfig cfg) {
        requireComponent(componentId);
        CompileResult r = doCompile(cfg);
        CompileResponse resp = new CompileResponse();
        resp.sql = r.sql;
        resp.declaredColumns = r.declaredColumns;
        resp.requiredVariables = r.requiredVariables;
        resp.grain = r.grain;
        resp.rewriterCompatible = r.rewriterCompatible;
        resp.warnings = r.warnings;
        return resp;
    }

    private CompileResult doCompile(BuilderConfig cfg) {
        SemanticGraphSnapshot snap = loader.get();
        return compiler.compile(snap, cfg, CompileDialect.QUOTE);
    }

    // ---------------- POST /preview (B-11, AC-26~28) ----------------

    public PreviewResponse preview(UUID componentId, PreviewRequest req) {
        requireComponent(componentId);
        CompileResult r = doCompile(req);

        String bound = bindLiterals(r.sql, req.customerCode,
                req.customerCode != null ? LocalDate.now().toString() : null);

        String wrapped = "SELECT * FROM (" + bound + ") __preview";
        List<String> conditions = new ArrayList<>();
        if (req.partNo != null && !req.partNo.isBlank()) {
            conditions.add("hf_part_no = '" + req.partNo.replace("'", "''") + "'");
        }
        if (!conditions.isEmpty()) wrapped += " WHERE " + String.join(" AND ", conditions);
        wrapped += " LIMIT 50";

        PreviewResponse resp = new PreviewResponse();
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(wrapped)) {
                ps.setQueryTimeout(5);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    for (int i = 1; i <= cols; i++) resp.columns.add(meta.getColumnLabel(i));
                    Map<String, Integer> nonNullCount = new LinkedHashMap<>();
                    for (String col : resp.columns) nonNullCount.put(col, 0);
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            row.put(meta.getColumnLabel(i), v);
                            if (v != null) nonNullCount.merge(meta.getColumnLabel(i), 1, Integer::sum);
                        }
                        resp.rows.add(row);
                    }
                    resp.rowCount = resp.rows.size();
                    resp.elapsedMs = System.currentTimeMillis() - start;

                    if (resp.rowCount == 0) {
                        resp.diagnostics.add(new Diagnostic("WARN", "PREVIEW_ZERO_ROWS", null,
                                zeroRowsHint(req)));
                    } else {
                        for (String col : resp.columns) {
                            if ("hf_part_no".equals(col)) continue;
                            if (nonNullCount.get(col) == 0) {
                                resp.diagnostics.add(new Diagnostic("WARN", "COLUMN_ALL_NULL", col,
                                        "整列 " + resp.rowCount + "/" + resp.rowCount + " 行全为 NULL —— 疑似绑错列，请核对该列实际取数来源"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new BuilderApiException(400, "PREVIEW_EXECUTION_FAILED", "预览执行失败: " + e.getMessage(), Map.of());
        }
        return resp;
    }

    private String zeroRowsHint(PreviewRequest req) {
        if (req.partNo != null && !req.partNo.isBlank()) {
            return "料号「" + req.partNo + "」在客户「" + req.customerCode + "」下不存在，或该客户下无此类基础数据；" +
                    "若该料号数据挂在子件上，请勾选『子件数据也要』后重试";
        }
        return "该客户下无此类基础数据，请先导入基础资料";
    }

    /** 把 :customerCode / :priceBaseDate 直接替换成字面量（预览只读场景，不走 PreparedStatement 位置参数）。 */
    private String bindLiterals(String sql, String customerCode, String priceBaseDate) {
        String result = sql;
        if (customerCode != null) {
            result = result.replaceAll("(?<!:):customerCode\\b", Matcher.quoteReplacement("'" + customerCode.replace("'", "''") + "'"));
        }
        if (priceBaseDate != null) {
            result = result.replaceAll("(?<!:):priceBaseDate\\b", Matcher.quoteReplacement("'" + priceBaseDate + "'"));
        }
        return result;
    }

    // ---------------- POST /inspect (B-12, AC-13/16-19/29/30) ----------------

    public InspectResponse inspect(UUID componentId, BuilderConfig cfg) {
        requireComponent(componentId);
        InspectResponse resp = new InspectResponse();
        CompileResult r;
        try {
            r = doCompile(cfg);
        } catch (BuilderApiException e) {
            if ("COMPILE_GRAIN_CONFLICT".equals(e.getErrorCode())) {
                resp.items.add(new InspectItem("ERR", "COMPILE_GRAIN_CONFLICT",
                        "粒度冲突（兜底拦截）：" + e.getMessage() + "。正常路径下拖拽期应已置灰，出现在这里说明是绕过前端拖拽直接构造的配置"));
                resp.blocked = true;
                return resp;
            }
            throw e;
        }
        runInspectChecks(cfg, r, resp);
        resp.blocked = resp.items.stream().anyMatch(i -> "ERR".equals(i.level));
        return resp;
    }

    private void runInspectChecks(BuilderConfig cfg, CompileResult r, InspectResponse resp) {
        List<BuilderConfig.ColumnConfig> cols = cfg.columns == null ? List.of() : cfg.columns;

        // AC-30：料号列 / 名称列至少一个
        boolean hasPartNo = cols.stream().anyMatch(c -> c.resolvedRoles != null && c.resolvedRoles.contains("PART_NO"));
        boolean hasPartName = cols.stream().anyMatch(c -> c.resolvedRoles != null && c.resolvedRoles.contains("PART_NAME"));
        if (!hasPartNo && !hasPartName) {
            resp.items.add(new InspectItem("ERR", "INSPECT_BLOCKED",
                    "缺少标识列：料号列与名称列至少要配一个"));
        }

        // AC-18/19：粗粒度列 / 附属源列勾小计应阻断——本轮未实现（需要区分"列来自哪条边"，
        // 时间预算优先给了 B-5/6/7/11/12/13 主干，如实标注未做，不写假通过的检查项）。

        // AC-13：字段名重复只告警不阻断
        Map<String, Long> nameCounts = new LinkedHashMap<>();
        for (BuilderConfig.ColumnConfig c : cols) {
            if (c.fieldName == null) continue;
            nameCounts.merge(c.fieldName, 1L, Long::sum);
        }
        nameCounts.forEach((name, count) -> {
            if (count > 1) {
                resp.items.add(new InspectItem("WARN", "FIELD_NAME_DUPLICATE",
                        "字段名「" + name + "」重复 " + count + " 次：视图别名仍唯一、技术无害，但表头会同名"));
            }
        });

        if (r.warnings != null) {
            for (String w : r.warnings) resp.items.add(new InspectItem("WARN", "COMPILER_WARNING", w));
        }
    }

    // ---------------- PUT / (B-13，一体化保存事务，api.md §2.4) ----------------

    @Transactional
    public SaveResponse save(UUID componentId, SaveRequest req, String operatorId) {
        Component component = requireComponent(componentId);

        InspectResponse inspect = inspect(componentId, req);
        if (inspect.blocked) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("items", inspect.items);
            throw new BuilderApiException(400, "INSPECT_BLOCKED",
                    "保存前体检未通过: " + inspect.items.stream()
                            .filter(i -> "ERR".equals(i.level)).findFirst().map(i -> i.message).orElse(""),
                    extra);
        }

        CompileResult r = doCompile(req);

        String viewName = resolveOrGenerateViewName(component);
        ComponentSqlView existing = sqlViewRepository.findAnyByComponentAndName(componentId, viewName).orElse(null);

        // AC-31：删列影响面二次确认
        if (existing != null && existing.builderConfig != null) {
            Set<String> oldCols = extractDeclaredColumnNames(existing);
            Set<String> newCols = new LinkedHashSet<>(r.declaredColumns);
            Set<String> removed = new LinkedHashSet<>(oldCols);
            removed.removeAll(newCols);
            if (!removed.isEmpty() && !req.confirmedImpact) {
                List<Map<String, Object>> affected = affectedTemplatesInfo(componentId);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("removedColumns", removed);
                extra.put("affectedTemplates", affected);
                throw new BuilderApiException(409, "IMPACT_CONFIRM_REQUIRED",
                        "删除了 " + removed.size() + " 列，影响 " + affected.size() + " 个模板，需确认后重试", extra);
            }
        }

        // ① component_sql_view（sql_template / declared_columns / builder_config / builder_version）
        CreateComponentSqlViewRequest svReq = new CreateComponentSqlViewRequest();
        svReq.sqlViewName = viewName;
        svReq.sqlTemplate = r.sql;
        svReq.scope = "COMPONENT";
        UUID createdBy = toUuid(operatorId);
        if (existing == null) {
            componentSqlViewService.create(componentId, svReq, createdBy);
        } else {
            componentSqlViewService.update(componentId, existing.id, svReq);
        }
        ComponentSqlView persisted = sqlViewRepository.findByComponentAndName(componentId, viewName)
                .orElseThrow(() -> new BuilderApiException(500, "BUILDER_SAVE_VIEW_LOST", "视图保存后查不到", Map.of()));
        try {
            // D-42 扁平化的副作用（2026-08-21 主线裁决）：req 是 SaveRequest（BuilderConfig 的
            // 子类，多带一个 confirmedImpact），Jackson 按运行时实际类型序列化，直接存进 JSONB
            // 会把 confirmedImpact 也写进 builder_config——下次 GET / 拿纯 BuilderConfig 反序列化
            // 就撞 Unrecognized field 500。这里先转成 JsonNode 剥掉多余字段，再落库，保证 JSONB
            // 里只有 builder_config 自己的字段（兜底见 get() 的 @JsonIgnoreProperties，但兜底
            // 不能替代这一步剥离——已经写脏的存量数据兜底也救不回来，见 V391）。
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.valueToTree(req);
            node.remove("confirmedImpact");
            persisted.builderConfig = MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new BuilderApiException(500, "BUILDER_CONFIG_SERIALIZE_FAILED", e.getMessage(), Map.of());
        }
        persisted.builderVersion = SemanticCompiler.CURRENT_VERSION;
        persisted.persist();

        // ②③④ component.fields[] + 组件级属性 + 价格策略三项绑定
        CreateComponentRequest compReq = buildComponentUpdateRequest(component, req, r, viewName);
        componentService.update(componentId, compReq);

        // ⑤ 刷新受引用模板 snapshot（按 sortOrder 精确匹配——forceRealignSnapshots 是 task-0806
        //   起替代已退役 refreshSnapshotsByComponent 的批量化实现，天然不会重现 AP-40 的
        //   firstResult() 反向污染，因为它是按 template_component.id 逐行 INSERT...SELECT，不做
        //   "找第一个匹配"这种操作）。
        List<UUID> affectedTemplateIds = TemplateComponent.<TemplateComponent>list("componentId", componentId)
                .stream().map(tc -> tc.templateId).distinct().toList();
        int affected = 0;
        if (!affectedTemplateIds.isEmpty()) {
            Map<String, Object> realign = templateService.forceRealignSnapshots(affectedTemplateIds);
            affected = ((Number) realign.getOrDefault("refreshedTemplates", 0)).intValue();
        }

        SaveResponse resp = new SaveResponse();
        resp.builderVersion = SemanticCompiler.CURRENT_VERSION;
        resp.affectedTemplates = affected;
        return resp;
    }

    private CreateComponentRequest buildComponentUpdateRequest(Component component, SaveRequest req,
                                                                 CompileResult r, String viewName) {
        CreateComponentRequest compReq = new CreateComponentRequest();
        compReq.dataDriverPath = "$" + viewName;
        compReq.tabType = req.tabType;

        List<Map<String, Object>> fields = new ArrayList<>();
        for (BuilderConfig.ColumnConfig col : r.effectiveColumns) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", col.fieldName);
            f.put("notes", "");
            f.put("content", "");
            boolean isAmount = Boolean.TRUE.equals(col.isAmount);
            boolean inSubtotal = Boolean.TRUE.equals(col.inSubtotal);
            f.put("is_amount", isAmount);
            f.put("is_subtotal", inSubtotal);
            String fieldType = "TEXT".equals(col.resolvedDataType) ? "INPUT_TEXT" : "INPUT_NUMBER";
            f.put("field_type", col.fieldType != null ? col.fieldType : fieldType);
            f.put("sort_order", fields.size());
            f.put("default_source", Map.of("type", "BASIC_DATA", "path", "$" + viewName + "." + col.viewColumn));
            fields.add(f);
        }
        compReq.fields = fields;

        // 行键 / 料号列 / 名称列 / 排序列：只从**用户原始请求**的列里推导角色（不含价格策略自动
        // 带出的编码列——那是价格 JOIN 的实现细节，不是本页签的行身份，混进去会让 row_key_fields
        // 多出一项，AC-2③ 明确断言只有 ["材质名称"] 一项）。
        List<String> rowKeys = new ArrayList<>();
        String partNo = null, partName = null, sortField = null;
        List<BuilderConfig.ColumnConfig> userCols = req.columns == null ? List.of() : req.columns;
        for (BuilderConfig.ColumnConfig col : userCols) {
            if (col.resolvedRoles == null) continue;
            if (col.resolvedRoles.contains("ROW_KEY") && !rowKeys.contains(col.fieldName)) rowKeys.add(col.fieldName);
            if (partNo == null && col.resolvedRoles.contains("PART_NO")) partNo = col.fieldName;
            if (partName == null && col.resolvedRoles.contains("PART_NAME")) partName = col.fieldName;
            if (sortField == null && col.resolvedRoles.contains("SORT")) sortField = col.fieldName;
        }
        compReq.rowKeyFields = rowKeys.isEmpty() ? null : rowKeys;
        compReq.partNoField = partNo;
        compReq.partNameField = partName;
        compReq.sortField = sortField;

        // 价格策略三项绑定（B-9，AC-22①）：从 effectiveColumns 里找价格函数节点的输出列
        String codeField = null, priceField = null, currencyField = null;
        for (BuilderConfig.ColumnConfig col : r.effectiveColumns) {
            if ("FUNC_ELEMENT_PRICE".equals(col.sourceNodeKey)) {
                if ("unit_price".equals(col.sourceColumn)) priceField = col.fieldName;
                if ("currency".equals(col.sourceColumn)) currencyField = col.fieldName;
            }
        }
        if (priceField != null) {
            // 编码列 = effectiveColumns 里第一个带 ROW_KEY 角色、来自锚点自身编码列的成员，
            // 与 SemanticCompiler 自动注入的那一列同源（sourceNodeKey=锚点自身）。
            codeField = r.effectiveColumns.stream()
                    .filter(col -> !"FUNC_ELEMENT_PRICE".equals(col.sourceNodeKey))
                    .filter(col -> col.resolvedRoles != null && col.resolvedRoles.contains("ROW_KEY"))
                    .filter(col -> "component_no".equals(col.sourceColumn) || "code".equals(col.sourceColumn))
                    .map(col -> col.fieldName).findFirst().orElse(null);
        }
        if (req.priceStrategy != null && req.priceStrategy.elementCodeManualField != null
                && !req.priceStrategy.elementCodeManualField.isBlank()) {
            codeField = req.priceStrategy.elementCodeManualField;
        }
        compReq.elementCodeField = codeField;
        compReq.elementPriceField = priceField;
        compReq.elementCurrencyField = currencyField;
        return compReq;
    }

    // ---------------- POST /detach (B-15, AC-33) ----------------

    @Transactional
    public void detach(UUID componentId) {
        Component component = requireComponent(componentId);
        ComponentSqlView view = resolveDrivingView(component);
        if (view == null) {
            throw new BuilderApiException(404, "BUILDER_VIEW_NOT_FOUND", "该组件没有绑定的取数配置视图", Map.of());
        }
        view.builderConfig = null;
        view.builderVersion = null;
        view.persist();
    }

    // ---------------- 内部辅助 ----------------

    private Component requireComponent(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) throw new BuilderApiException(404, "COMPONENT_NOT_FOUND", "组件不存在: " + componentId, Map.of());
        return c;
    }

    private ComponentSqlView resolveDrivingView(Component component) {
        if (component.dataDriverPath == null || component.dataDriverPath.isBlank()) return null;
        String viewName = component.dataDriverPath.startsWith("$") ? component.dataDriverPath.substring(1) : component.dataDriverPath;
        int dot = viewName.indexOf('.');
        if (dot >= 0) viewName = viewName.substring(dot + 1); // 跨组件引用 $$code.name 形态兜底
        return sqlViewRepository.findByComponentAndName(component.id, viewName).orElse(null);
    }

    private String resolveOrGenerateViewName(Component component) {
        ComponentSqlView existing = resolveDrivingView(component);
        if (existing != null) return existing.sqlViewName;
        String base = "builder_" + component.id.toString().replace("-", "").substring(0, 12);
        return base.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractDeclaredColumnNames(ComponentSqlView view) {
        Set<String> out = new LinkedHashSet<>();
        try {
            List<Map<String, Object>> parsed = MAPPER.readValue(view.declaredColumns, List.class);
            for (Map<String, Object> m : parsed) {
                Object name = m.get("name");
                if (name != null) out.add(name.toString());
            }
        } catch (Exception ignored) {
            // 解析失败按"没有历史列信息"处理——不阻断保存，只是这次的删除影响面判定会漏检
        }
        return out;
    }

    private List<Map<String, Object>> affectedTemplatesInfo(UUID componentId) {
        List<UUID> templateIds = TemplateComponent.<TemplateComponent>list("componentId", componentId)
                .stream().map(tc -> tc.templateId).distinct().toList();
        if (templateIds.isEmpty()) return List.of();
        List<Template> templates = Template.list("id in ?1", templateIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Template t : templates) out.add(Map.of("id", t.id.toString(), "name", t.name));
        return out;
    }

    private UUID toUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
