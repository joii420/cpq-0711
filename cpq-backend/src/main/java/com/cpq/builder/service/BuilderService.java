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
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticTabView;
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
    @Inject BomTreeRenderService bomTreeRenderService;

    // ---------------- GET / (B-20, AC-34) ----------------

    /**
     * D-46（2026-08-21 主线裁决，紧急修复）：三态判定——原契约把"全新组件"（无任何
     * component_sql_view 行）与"存量手写"（有行但 builder_config 为空）都判成
     * {@code isLegacyHandwritten=true}，导致新建组件也弹手写引导页，用户真机撞到。
     *
     * <p>⚠️ 多行边界（主线要求"有歧义就报，不要自己假定"）：一个组件理论上可能有多条
     * {@code component_sql_view} 行（如历史遗留的多个 GLOBAL/COMPONENT 视图）。本方法判定
     * "是否 NEW"用 {@code listByComponent}（是否存在任意 ACTIVE 行，不看具体是哪条）；判定
     * LEGACY_HANDWRITTEN vs BUILDER 用"当前驱动视图"（{@code component.dataDriverPath} 指向
     * 的那一条）——这是本组件实际渲染用的那条，语义上最贴近"这个组件现在处于什么配置状态"。
     * <b>唯一未覆盖的边界</b>：驱动视图解析不出来（{@code dataDriverPath} 为空或指向的行不存在）
     * 但确实存在其它 ACTIVE 行——这种"有行但没有驱动"的组合目前保守按 LEGACY_HANDWRITTEN 处理
     * （视为需要人工确认，不当 BUILDER 处理），已在回报里向主线标出，未自行拍板为最终口径。
     */
    public GetBuilderResponse get(UUID componentId) {
        Component component = requireComponent(componentId);
        GetBuilderResponse resp = new GetBuilderResponse();
        resp.currentCompilerVersion = SemanticCompiler.CURRENT_VERSION;

        boolean hasAnySqlView = !sqlViewRepository.listByComponent(componentId).isEmpty();
        if (!hasAnySqlView) {
            resp.builderConfig = null;
            resp.builderVersion = null;
            resp.viewState = "NEW";
            resp.isLegacyHandwritten = false;
            resp.isStale = false;
            return resp;
        }

        ComponentSqlView view = resolveDrivingView(component);
        if (view == null || view.builderConfig == null) {
            resp.builderConfig = null;
            resp.builderVersion = null;
            resp.viewState = "LEGACY_HANDWRITTEN";
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
        resp.viewState = "BUILDER";
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
        // task-260819 B-22（D-59）：改读请求体 cfg.dialect，不再硬编码 QUOTE——硬编码会让
        // AC-37 的核价侧编译路径根本走不到（一期 B-10「方言参数化」因此无法验收）。
        // 只改取值来源，编译器内部按 dialect 分支的逻辑（B-10 已交付部分）不动。
        return compiler.compile(snap, cfg, resolveDialect(cfg));
    }

    /** task-260819 B-22：缺省/无法识别的 dialect 值一律按 QUOTE 处理（与改动前行为一致，零回归）。 */
    private static CompileDialect resolveDialect(BuilderConfig cfg) {
        if (cfg == null || cfg.dialect == null || cfg.dialect.isBlank()) return CompileDialect.QUOTE;
        try {
            return CompileDialect.valueOf(cfg.dialect.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CompileDialect.QUOTE;
        }
    }

    // ---------------- POST /preview (B-11, AC-26~28) ----------------

    public PreviewResponse preview(UUID componentId, PreviewRequest req) {
        requireComponent(componentId);
        CompileResult r = doCompile(req);

        String bound = bindLiterals(r.sql, req.customerCode,
                req.customerCode != null ? LocalDate.now().toString() : null);

        // task-260819 B-23（D-63）：D-50 后编译产物一律带 = ANY(:total_material_no)，但 /preview
        // 走裸 JDBC 直接拼 SQL 执行、不经 SqlViewExecutor/BomTreeVarsContext，该占位符无人绑定会
        // 原样进入发给 PG 的 SQL 文本，PG 不认识 ":xxx" 语法 → syntax error（A/B 对照实证的真回归）。
        // 与 customerCode/priceBaseDate 同款字面量替换风格，注入"该料号自己的 BOM 闭包"（成品+
        // 全部后代，D-58「传几行算几行」口径）——复用 BomTreeRenderService.collectTotalMaterialNoUnion，
        // 不另写第二套闭包算法（D-50 要收敛的正是这个）。
        bound = bindTotalMaterialNo(bound, req.partNo, req.customerCode);

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

    private static final Pattern TOTAL_MATERIAL_NO_TOKEN = Pattern.compile("(?<!:):total_material_no\\b");

    /**
     * task-260819 B-23（D-63）：把编译产物里的 {@code :total_material_no} 占位符替换成一个
     * 字面量 PG 数组——预览走裸 JDBC，没有 {@code SqlViewExecutor} 的命名参数绑定管线可用，
     * 只能沿用本类既有的 {@link #bindLiterals} 字面量替换风格。
     *
     * <p>注入内容 = 预览料号自己的 BOM 闭包（成品 + 全部后代），与 AC-26 乙组、与单卡路径
     * 「传几行算几行」口径一致（D-58）——🚫 不是只注入料号自身，那样闭包收窄的存在毫无意义，
     * 预览永远看不到子件行，与 AC-26 断言直接冲突。
     *
     * <p>{@code partNo} 为空（如 AC-28 misbound 场景，只用 customerCode 探测整表）时，SQL 里的
     * {@code :total_material_no} 仍需要一个合法值才能过 PG 语法检查——绑定「空数组」而非报错：
     * 语义上「没有选定料号」= 没有可收窄的种子，`= ANY(ARRAY[]::text[])` 恒为 FALSE，与
     * 预览页面「未选料号时不该看到任何具体料号的数据行」的直觉一致，且不会把 AC-27/AC-28 那类
     * 「本该 0 行给诊断」的用例升级成一个新的必答问题（保持零回归）。
     */
    private String bindTotalMaterialNo(String sql, String partNo, String customerCode) {
        if (!TOTAL_MATERIAL_NO_TOKEN.matcher(sql).find()) return sql; // 该 SQL 不含此占位符，零开销跳过

        List<String> closure;
        if (partNo != null && !partNo.isBlank()) {
            QuotationLineItem lite = new QuotationLineItem();
            lite.productPartNoSnapshot = partNo;
            BomTreeRenderService.MaterialUnionResult union =
                    bomTreeRenderService.collectTotalMaterialNoUnion(List.of(lite), "QUOTE");
            closure = union.totalMaterialNo;
        } else if (customerCode != null && !customerCode.isBlank()) {
            // task-260819 B-28（D-70，主线裁决）：无 partNo = 主件页签"按客户预览、不针对具体料号"
            // 场景（AC-15），语义即"不限料号"——注入该客户下的全部料号，而不是空闭包（空闭包会让
            // = ANY(ARRAY[]::text[]) 恒假，AC-15①的预览恒 0 行）。
            // 🚫 不许因此破坏 AC-27/AC-28：客户确实无基础数据时，下面 roots 查询本就返回空列表，
            // 结果与原先的空闭包完全一致（仍是 0 行 + zeroRowsHint 可操作诊断，不是报错）。
            // 「全部料号」的口径 = 该客户 QUOTE 侧全部 BOM 根（与 AC-26 的闭包口径同源：
            // system_type='QUOTE' AND is_current AND customer_no=:customerCode），
            // 再套同一条 collectTotalMaterialNoUnion 把各根的子件闭包一并纳入——复用既有算法，
            // 不另写第二套闭包逻辑（D-50 要收敛的正是这个）。
            // N+1 自检：本方法固定 1 条根查询 SQL（常数，与客户下料号数无关）+
            // collectTotalMaterialNoUnion 内部固定 1 条递归 CTE（对整批根一次算完）——
            // 与料号数无关，恒为 2 条 SQL。
            List<String> roots = queryCustomerRootMaterialNos(customerCode);
            if (roots.isEmpty()) {
                closure = List.of();
            } else {
                List<QuotationLineItem> seeds = new ArrayList<>();
                for (String root : roots) {
                    QuotationLineItem lite = new QuotationLineItem();
                    lite.productPartNoSnapshot = root;
                    seeds.add(lite);
                }
                BomTreeRenderService.MaterialUnionResult union =
                        bomTreeRenderService.collectTotalMaterialNoUnion(seeds, "QUOTE");
                closure = union.totalMaterialNo;
            }
        } else {
            closure = List.of();
        }
        String arrayLiteral = closure.isEmpty()
                ? "ARRAY[]::text[]"
                : "ARRAY[" + closure.stream()
                        .map(s -> "'" + s.replace("'", "''") + "'")
                        .reduce((a, b) -> a + "," + b).orElse("") + "]::text[]";
        return TOTAL_MATERIAL_NO_TOKEN.matcher(sql).replaceAll(Matcher.quoteReplacement(arrayLiteral));
    }

    /**
     * task-260819 B-28（D-70）：该客户 QUOTE 侧全部 BOM 根料号（{@code system_type='QUOTE' AND
     * is_current AND customer_no=:customerCode}），口径与 AC-26 的闭包定义同源（见
     * {@code SemanticCompiler.closureCte()} 的注释）。固定 1 条 SQL，返回条数与结果集无关
     * （N+1 约束②：单次业务操作 SQL 条数是常数）。客户确实无任何 QUOTE 侧料号时返回空列表
     * （不是异常）——由调用方按"空闭包"兜底，保住 AC-27/AC-28 的 0 行 + 诊断路径。
     */
    private List<String> queryCustomerRootMaterialNos(String customerCode) {
        List<String> roots = new ArrayList<>();
        String q = "SELECT DISTINCT material_no FROM material_bom_item "
                + "WHERE system_type = 'QUOTE' AND is_current AND customer_no = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, customerCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String materialNo = rs.getString(1);
                    if (materialNo != null && !materialNo.isBlank()) roots.add(materialNo);
                }
            }
        } catch (Exception e) {
            throw new BuilderApiException(500, "PREVIEW_CUSTOMER_ROOTS_QUERY_FAILED",
                    "查询客户全部料号失败: " + e.getMessage(), Map.of());
        }
        return roots;
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

        // AC-18/19（B-27，D-68）：粗粒度列 / 附属源列勾小计应阻断。
        checkSubtotalGrainMismatch(cfg, r, resp);

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

    /**
     * AC-18/19（B-27，D-68）：粗粒度列 / 附属源列勾小计一律阻断——两条 AC 的诱因不同，文案也
     * 分别写死（D-22：体检文案要让用户知道该改什么，不能一句通用话糊弄），判据各自独立：
     *
     * <p>· AC-18「粗粒度列」：列直接来自锚点自身（{@code source.id == anchor.id}）。锚点行本身
     * 不会因为别的 GRAIN 目标被选中而增多，但一旦有 GRAIN 目标把行粒度撑宽（{@code r.grain.size()>1}，
     * B-26 保证 baseline 恒占 1 维），锚点列在被撑宽出的新增行之间取值不变——勾小计会把同一个
     * 值按新增维度的行数重复累加。
     *
     * <p>· AC-19「附属源列」：列经某条 {@code GRAIN} 边到达。触发条件是 {@code anchor.grainColumns}
     * 非空——即锚点自身还有"物理连接键之外"的额外身份维度（如材质元素锚点的
     * {@code material_part_no}/{@code component_no}，物理连接键只用了 {@code material_no}）。
     * 这类 GRAIN 边的连接键天生够不到锚点的完整身份，同一个附属源行会被"借"给锚点侧多个不同
     * 明细行使用，值按锚点自己更粗的那层（如"归属料号"）重复出现，不是"新增维度"而是"借来的
     * 维度"，同样不能直接累加。反例是「主件」锚点（{@code grainColumns=[]}，物理连接键本身就是
     * 锚点唯一的身份列，如 {@code ASSEMBLY_FEE}/{@code FINISHED_OTHER}）——那类 GRAIN 目标的值
     * 是货真价实的按新维度展开，逐行求和是合法小计，不在本检查拦截范围内。
     *
     * <p>N+1 自检：{@code cols} 上的一次遍历，图查找全部落在 {@link SemanticGraphSnapshot} 的
     * 内存索引（{@code nodeByKeyDialect}/{@code edgesFrom}）上，零 SQL。
     */
    private void checkSubtotalGrainMismatch(BuilderConfig cfg, CompileResult r, InspectResponse resp) {
        List<BuilderConfig.ColumnConfig> cols = cfg.columns == null ? List.of() : cfg.columns;
        if (cols.isEmpty()) return;

        SemanticGraphSnapshot snap = loader.get();
        String variantKey = cfg.variantKey == null ? "" : cfg.variantKey;
        SemanticTabView tabView = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(variantKey))
                .findFirst().orElse(null);
        if (tabView == null) return; // compile() 早已对页签视图缺失报过错，这里不会真的走到
        SemanticNode anchor = snap.nodeById.get(tabView.anchorNodeId);
        if (anchor == null) return;

        boolean grainWidened = r.grain != null && r.grain.size() > 1;
        boolean anchorHasOwnSubIdentity = anchor.grainColumns != null && anchor.grainColumns.length > 0;

        for (BuilderConfig.ColumnConfig col : cols) {
            if (!Boolean.TRUE.equals(col.inSubtotal)) continue;
            SemanticNode source = snap.nodeByKeyDialect.get(col.sourceNodeKey + "|QUOTE");
            if (source == null) continue;
            String fieldName = (col.fieldName != null && !col.fieldName.isBlank()) ? col.fieldName : source.displayName;

            if (source.id.equals(anchor.id)) {
                if (grainWidened) {
                    resp.items.add(new InspectItem("ERR", "SUBTOTAL_COARSE_GRAIN_COLUMN",
                            "字段「" + fieldName + "」来自「" + anchor.displayName + "」自身，当前行粒度已按 "
                                    + String.join("+", r.grain) + " 展开：该值会在每一行重复出现、累加即重复计算，不能勾为小计"));
                }
                continue;
            }

            SemanticEdge edge = snap.edgesFrom(anchor.id).stream()
                    .filter(e -> e.toNodeId.equals(source.id)).findFirst().orElse(null);
            if (edge != null && "GRAIN".equals(edge.edgeKind) && anchorHasOwnSubIdentity) {
                resp.items.add(new InspectItem("ERR", "SUBTOTAL_AUX_SOURCE_COLUMN",
                        "字段「" + fieldName + "」来自附属源「" + source.displayName + "」：该值按主源粒度重复出现"
                                + "（主源「" + anchor.displayName + "」自身还有 " + String.join("/", anchor.grainColumns)
                                + " 维度未参与该附属源的连接键），不能直接累加为小计"));
            }
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
