package com.cpq.builder.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.CompileDialect;
import com.cpq.builder.compiler.CompileResult;
import com.cpq.builder.compiler.PhysicalColumnCatalog;
import com.cpq.datasource.sqlview.SpineKeysMacro;
import com.cpq.datasource.sqlview.VersionFilterMacro;
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

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(BuilderService.class);
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
    @Inject PhysicalColumnCatalog physicalColumnCatalog;

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
        // task-260909 B-5①（AC-15，api.md §1.6）：fieldType 为空的列，回填 component.fields[] 里的**真实值**。
        backfillFieldTypesFromComponent(resp.builderConfig, component);
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
        return doCompile(cfg, false);
    }

    private CompileResult doCompile(BuilderConfig cfg, boolean skipNarrowPredicates) {
        SemanticGraphSnapshot snap = loader.get();
        // task-260819 B-22（D-59）：改读请求体 cfg.dialect，不再硬编码 QUOTE——硬编码会让
        // AC-37 的核价侧编译路径根本走不到（一期 B-10「方言参数化」因此无法验收）。
        // 只改取值来源，编译器内部按 dialect 分支的逻辑（B-10 已交付部分）不动。
        return compiler.compile(snap, cfg, resolveDialect(cfg), skipNarrowPredicates);
    }

    /**
     * 取本次编译用哪套数据集（task-260819 B-22 引入，<b>B-46 改判据</b>）。
     *
     * <p>🔄 <b>2026-09-03（主线裁决）：无法识别的值不再静默回落 QUOTE，改为显式 400。</b>
     * 缺省（不传 dialect）仍是 {@code QUOTE}。判据与错误文案的唯一出处是
     * {@link CompileDialect#parse}——那里写着"为什么必须拒绝"，不要在这里再复制一份判断。
     */
    private static CompileDialect resolveDialect(BuilderConfig cfg) {
        return CompileDialect.parse(cfg == null ? null : cfg.dialect);
    }

    // ---------------- 字段类型（task-260909 B-1 / B-2，api.md §1.2~§1.4） ----------------

    /**
     * 配置器可产出的 {@code field_type} 值域（task-260909 D-4，api.md §1.2）—— <b>恰好 3 个</b>。
     *
     * <p>🚫 <b>不要按 {@code ComponentService.VALID_FIELD_TYPES}（6 个）放宽</b>：那是"组件字段这一层
     * 允许存在什么类型"，本集合是"<b>取数配置器这一条产线能产出什么类型</b>"，两者是包含关系不是同一件事。
     * 差集里的 {@code FORMULA} / {@code FIXED_VALUE} / {@code LIST_FORMULA} 分别还需要
     * {@code formula_id} / {@code content} / {@code conditional_formula} 才是完整字段，而<b>配置器根本
     * 不收集这些</b> —— 放它们进来只会产出「存得下、但必然坏」的字段：下游 {@code ComponentService}
     * 的白名单会照样放行（{@code FORMULA} 在它的 6 个里），于是<b>全程不报错</b>，
     * 到渲染期才表现为空值/"—"。这正是本任务要堵的那个静默故障面。
     */
    static final List<String> ALLOWED_FIELD_TYPES = List.of("BASIC_DATA", "INPUT_TEXT", "INPUT_NUMBER");

    /**
     * 保存请求里显式传来的 {@code fieldType} 必须落在 {@link #ALLOWED_FIELD_TYPES} 内，否则 400
     * （task-260909 B-1，AC-8）。
     *
     * <p><b>不传 / null / 空串 → 放行</b>，交给 {@link #defaultFieldType} 按方言推（AC-9 向后兼容门禁：
     * 旧客户端不发这个字段，不能因此碎掉）。空串按"未传"处理，与
     * {@link CompileDialect#parse} 对 {@code dialect} 的 {@code isBlank()} 口径一致 ——
     * 两处都是"外部可选字符串"，判据不该各自漂移。
     *
     * <p>只校验<b>用户请求里的列</b>（{@code cfg.columns}）：编译器为价格策略自动补出的成员
     * （{@code SemanticCompiler#resolvePricePlan} 往 {@code effectiveColumns} 里 add 的那个编码列）
     * {@code fieldType} 恒为 null，本来就走默认，没有可校验的用户输入。
     */
    private static void assertValidFieldTypes(BuilderConfig cfg) {
        if (cfg == null || cfg.columns == null) return;
        for (BuilderConfig.ColumnConfig col : cfg.columns) {
            if (col == null || col.fieldType == null || col.fieldType.isBlank()) continue;
            if (!ALLOWED_FIELD_TYPES.contains(col.fieldType)) {
                throw new BuilderApiException(400, "BUILDER_FIELD_TYPE_UNKNOWN",
                        "非法 fieldType: " + col.fieldType + "，合法值："
                                + String.join(" / ", ALLOWED_FIELD_TYPES),
                        Map.of("received", col.fieldType,
                                "fieldName", col.fieldName == null ? "" : col.fieldName,
                                "allowed", ALLOWED_FIELD_TYPES));
            }
        }
    }

    /**
     * 列没显式指定 {@code fieldType} 时的默认值（task-260909 B-2 / D-2，api.md §1.3）。
     *
     * <ul>
     *   <li><b>核价两套</b>（{@code COST_BASIC} / {@code COST_DETAIL}）→ {@code BASIC_DATA}：这些列的
     *       语义是"从 SQL 视图取来的展示值"，不是用户输入。原先恒推 {@code INPUT_*} 的后果是核价页签
     *       渲染出可编辑 {@code <input>}，而核价侧根本没有增量写路径
     *       （{@code useSnapEdit = cardSide === 'QUOTE'}）⇒ 用户打字、失焦、刷新就没了，<b>全程不报错</b>。</li>
     *   <li><b>报价侧</b>（{@code QUOTE}）→ 维持按数据类型推（{@code TEXT → INPUT_TEXT}，其余
     *       {@code INPUT_NUMBER}），<b>逐位不变</b>（AC-5 零回归门禁）—— 那边用户确实要填数。</li>
     * </ul>
     *
     * <p>🔑 用 {@link CompileDialect#isCosting()} 判，<b>不要逐个枚举值写 {@code ==}</b>
     * （该方法 javadoc 明写：漏一个就是静默走错分支）。
     *
     * <p>🚫 <b>这里改的是 {@code field_type} 的「初值」，不是绑定键的「规则」。</b>
     * 绑定键仍在 {@code buildComponentUpdateRequest} 里<b>跟 {@code field_type} 走</b>
     * （{@code BASIC_DATA → 顶层 basic_data_path}，{@code INPUT_* → default_source.path}），
     * D-73/B-30 的「不跟报价/核价侧走」不变量原样保留 —— 别把本方法误读成"按侧决定绑定键"回潮，
     * 那正是 {@link CompileDialect} 里已被删除的 {@code bindingKeyName()} 干的事。
     *
     * <p>⚠️ <b>前端 {@code SqlViewBuilderTab} 里有一份同规则的副本</b>（新拖入列的初值），
     * 这是<b>有意的双写</b>：前端算是为了"用户看到的就是将要保存的"，后端算是为了"旧客户端不传时
     * 仍正确"（AC-9）。🚫 不要"顺手收敛成一处"——收掉哪一边都会丢掉它各自负责的那件事；
     * 但两边规则必须同步改。
     */
    static String defaultFieldType(CompileDialect dialect, String resolvedDataType) {
        if (dialect != null && dialect.isCosting()) return "BASIC_DATA";
        return "TEXT".equals(resolvedDataType) ? "INPUT_TEXT" : "INPUT_NUMBER";
    }

    /**
     * 把 effective 值回填进 {@code columns[].fieldType}，使其落进 {@code builder_config} 后不再为空
     * （task-260909 B-5②，AC-16，api.md §1.6）。
     *
     * <p>🔑 <b>为什么必须在 {@code doCompile} 之后、序列化 {@code builder_config} 之前调</b>：
     * 默认值要用编译器回填的 {@code resolvedDataType}（编译前它是 null，报价侧会全推成
     * {@code INPUT_NUMBER}）；而 {@code builder_config} 的序列化早于
     * {@code buildComponentUpdateRequest}，等到那里再回填就来不及了。
     *
     * <p>📌 入参传 {@code r.effectiveColumns} 即可覆盖 {@code req.columns}：两者是<b>同一批对象引用</b>
     * （{@code SemanticCompiler} 只是 {@code new ArrayList<>(cfg.columns)} 浅拷贝了列表），
     * 编译器正是靠这一点把 {@code viewColumn} / {@code resolvedDataType} 回填给请求体的。
     * 价格策略自动补出的那一列不在 {@code req.columns} 里，不会被写进 {@code builder_config}（本就不该写）。
     *
     * <p>🚫 <b>规则不在这里第二次实现</b> —— 恒调 {@link #defaultFieldType}，与
     * {@code buildComponentUpdateRequest} 同一个真源。两处调用点都在，是因为它们各自守着不同的东西：
     * 本方法保证「<b>存进 builder_config 的 == 生效的</b>」（AC-16），那边保证「即便有人绕过本方法，
     * 落库的 field_type 仍然对」。
     */
    private static void applyEffectiveFieldTypes(List<BuilderConfig.ColumnConfig> columns,
                                                 CompileDialect dialect) {
        if (columns == null) return;
        for (BuilderConfig.ColumnConfig col : columns) {
            if (col == null) continue;
            if (col.fieldType == null || col.fieldType.isBlank()) {
                col.fieldType = defaultFieldType(dialect, col.resolvedDataType);
            }
        }
    }

    /**
     * {@code GET /builder} 的回填（task-260909 B-5①，AC-15，api.md §1.6）：
     * {@code builder_config.columns[].fieldType} 为空时，取 {@code component.fields[]} 里<b>同名字段</b>
     * 的真实 {@code field_type} 返回。
     *
     * <p>🚨 <b>没有这一步，{@code F-3}（前端开始发 fieldType）就是在 42 个存量组件上埋雷</b>：
     * 存量 {@code builder_config.fieldType} 全为 null（实测 351/387 列），前端只能显示兜底值，
     * 用户「打开 → 什么都不改 → 保存」就把兜底值写进了 {@code field_type} ——
     * NUMBER 列降级成文本，或核价侧被静默改成 {@code BASIC_DATA}。
     * <b>不变量：界面回填显示的 == 实际生效的。</b>
     *
     * <p>⚠️ <b>按字段名匹配，🚫 不按下标</b>：列可以被拖拽排序，两边顺序不保证一致 ——
     * 按下标匹配会把类型串到别的列上，而且<b>不报错</b>（{@code AP-54} 同族：过滤/重排后的下标当原下标用）。
     *
     * <p>⚠️ <b>读回的值不过滤、不校验</b>：实测存量里有 {@code FORMULA}（{@code COMP-2300}）——
     * 那是公式字段，独立管理，本就不是配置器的列，前端不会为它渲染选择器。
     * 在读路径上按 3 值白名单过滤或报错，只会让「打得开的组件」变成「打不开的组件」。
     * 白名单是<b>写路径</b>的守卫（{@link #assertValidFieldTypes}），不是读路径的。
     *
     * <p>N+1 自检：{@code component.fields} 是该组件自己的 jsonb 列（已随实体加载），
     * 本方法先把它解析成一张 name→field_type 的内存 Map <b>再</b>遍历列，
     * <b>零 SQL、与列数无关</b>；🚫 不要改成在列循环里逐个去查字段。
     */
    private void backfillFieldTypesFromComponent(BuilderConfig cfg, Component component) {
        if (cfg == null || cfg.columns == null || cfg.columns.isEmpty()) return;
        boolean anyMissing = cfg.columns.stream()
                .anyMatch(c -> c != null && (c.fieldType == null || c.fieldType.isBlank()));
        if (!anyMissing) return;

        Map<String, String> realTypeByName = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(
                    component.fields == null || component.fields.isBlank() ? "[]" : component.fields);
            for (com.fasterxml.jackson.databind.JsonNode f : arr) {
                String name = f.path("name").asText(null);
                String ft = f.path("field_type").asText(null);
                if (name != null && ft != null && !ft.isBlank()) realTypeByName.putIfAbsent(name, ft);
            }
        } catch (Exception e) {
            // 🚫 读路径不因为字段 jsonb 解析失败就 500 —— 那会让组件直接打不开。
            //    回填是"锦上添花"，拿不到就维持原样（前端仍走它自己的兜底），不放大故障面。
            LOG.warnf(e, "builder GET: 解析 component.fields 失败，跳过 fieldType 回填 componentId=%s", component.id);
            return;
        }

        for (BuilderConfig.ColumnConfig col : cfg.columns) {
            if (col == null || !(col.fieldType == null || col.fieldType.isBlank())) continue;
            String real = realTypeByName.get(col.fieldName);
            if (real != null) col.fieldType = real;   // 同名字段找不到（列被改名/新拖入未保存）→ 保持 null
        }
    }

    /**
     * 预览是否走「不经桥的整表样例」（task-260819 B-52）：<b>核价方言 + 未指定料号</b>。
     *
     * <p>桥是用来翻译**给定的**销售料号的；一个料号都没给时没有什么可翻译，此时经桥反而会把结果
     * 收窄成"恰好有桥映射的那几个料号"——实测 {@code ds_cost_basic_material} 12 行里只有 6 行有桥，
     * 而 AC-117 的基准是<b>整表 12</b>。报价侧不经桥，恒 false（零回归）。
     */
    boolean isUnrestrictedPreview(PreviewRequest req) {
        return resolveDialect(req).isCosting() && (req.partNo == null || req.partNo.isBlank());
    }

    /**
     * 预览专用编译（B-52）。
     *
     * <p>⚠️ <b>必须是 preview() 唯一的编译入口</b>：把 {@code isUnrestrictedPreview} 的结果摊回
     * 调用点，自测就只能打到"判定函数"本身，而<b>接线被删掉照样全绿</b>——B-48 与本条我各踩过一次，
     * 两次都是证伪实验才抓出来的。收成一个方法后，删掉接线自测必红。
     */
    CompileResult compileForPreview(PreviewRequest req) {
        return doCompile(req, isUnrestrictedPreview(req));
    }

    // ---------------- POST /preview (B-11, AC-26~28) ----------------

    public PreviewResponse preview(UUID componentId, PreviewRequest req) {
        requireComponent(componentId);
        boolean unrestricted = isUnrestrictedPreview(req);
        CompileResult r = compileForPreview(req);

        String bound = buildPreviewSql(r, req);

        // task-260819 B-23（D-63）：D-50 后编译产物一律带 = ANY(:total_material_no)，但 /preview
        // 走裸 JDBC 直接拼 SQL 执行、不经 SqlViewExecutor/BomTreeVarsContext，该占位符无人绑定会
        // 原样进入发给 PG 的 SQL 文本，PG 不认识 ":xxx" 语法 → syntax error（A/B 对照实证的真回归）。
        // 与 customerCode/priceBaseDate 同款字面量替换风格，注入"该料号自己的 BOM 闭包"（成品+
        // 全部后代，D-58「传几行算几行」口径）——复用 BomTreeRenderService.collectTotalMaterialNoUnion，
        // 不另写第二套闭包算法（D-50 要收敛的正是这个）。

        String wrapped = wrapPreviewSql(bound, req);

        PreviewResponse resp = new PreviewResponse();
        if (unrestricted) {
            // 让"预览 SQL 与落库 SQL 不同"这件事**可见**：不说的话，用户会以为自己预览的就是
            // 将来渲染要跑的那条，而这正是本任务反复在消灭的静默错配。
            resp.diagnostics.add(new Diagnostic("WARN", "PREVIEW_UNRESTRICTED_SAMPLE", null,
                    "未指定料号：本次预览展示的是整表样例（最多 50 行），未经料号桥收窄。"
                            + "落库后的取数仍会按销售料号经桥收窄——要看真实收窄结果，请填一个销售料号"));
        }
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
                                zeroRowsHint(req, resolveDialect(req))));
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

    /**
     * 0 行时的可操作诊断（B-51 起按方言分叉）。
     *
     * <p>⚠️ <b>原文案在核价侧是误导的</b>：它说「料号在客户 X 下不存在」，而核价两套
     * <b>根本没有客户维度</b>（{@code ds_cost_*} 无 {@code customer_no} 列，{@code customerCode}
     * 恒为 null，于是打印成「客户「null」下不存在」），并且核价侧 0 行最常见的原因**不是**料号不存在，
     * 而是<b>料号桥没接上</b>——销售料号在 {@code ds_quote_material} 里没有对应行，或那行的
     * {@code production_no} 为空（实测 45 行里仅 25 行非空，"报价时生产料号还没定"是正常业务状态）。
     * 诊断把人指向"数据缺失"，排查方向会被整个带偏（本条正是这么被发现的）。
     */
    // 包级可见仅为开发自测直调。
    String zeroRowsHint(PreviewRequest req, CompileDialect dialect) {
        boolean hasPart = req.partNo != null && !req.partNo.isBlank();
        if (dialect.isCosting()) {
            if (hasPart) {
                return "没有取到数据。核价取数是「销售料号 → 料号桥 → 生产料号」三步，请按顺序排查："
                        + "① 销售料号「" + req.partNo + "」在报价物料（ds_quote_material）里是否存在；"
                        + "② 该行的生产料号是否已填（报价阶段可能尚未确定，属正常业务状态，"
                        + "表现就是暂时取不到核价数据）；③ 该生产料号在本页签对应的核价表里是否已有数据。"
                        + "🚫 与客户无关——核价数据集没有客户维度";
            }
            return "没有取到数据：本页签对应的核价表里还没有数据，请先导入核价基础资料"
                    + "（核价数据集没有客户维度，与选哪个客户无关）";
        }
        if (hasPart) {
            return "料号「" + req.partNo + "」在客户「" + req.customerCode + "」下不存在，或该客户下无此类基础数据；" +
                    "若该料号数据挂在子件上，请勾选『子件数据也要』后重试";
        }
        return "该客户下无此类基础数据，请先导入基础资料";
    }

    /**
     * 预览路径的宏展开（task-260819 B-48）。
     *
     * <p><b>为什么会漏</b>：编译产物是给渲染链路（{@code SqlViewExecutor}）吃的，那条链路会
     * 展开宏 + 绑命名参数；而 {@code /preview} 为了"所见即所得且只读"走裸 JDBC 直接拼 SQL 执行，
     * <b>两条路径对同一份 SQL 的处理能力不对等</b>。D-63/B-23 已经因为 {@code :total_material_no}
     * 栽过一次，这次是 {@code :versionFilter} —— <b>同一个结构性缺陷的第二次发作</b>，
     * 所以这里不只修这一个宏，而是把"预览要自己消化什么"列成清单集中处理。
     *
     * <p><b>选 {@code expandForValidation} 而不是 {@code expandForExecution}</b>：前者展开成
     * {@code (is_current列)}，<b>不引入新占位符</b>；后者会吐出 {@code :__vfPart::text[]} /
     * {@code :__vfVer::text[]} 两个新占位符，裸 JDBC 这边还得再绑一次空数组——等于把同一个坑
     * 往后挪一格。语义上 {@code (is_current)} = 「预览看当前版本」，与预览"不带 override 上下文"
     * 的定位一致，也与保存期 dry-run 的口径同源。
     *
     * <p>{@code :spineKeys} 目前的编译产物不会产生（{@code grep} 全 builder 包为空），但它可以
     * 经节点的 {@code fixed_predicate}/{@code discriminator} 从图里流进来 —— 一并展开，
     * <b>不赌"现在没有就永远没有"</b>。
     */
    /**
     * 编译产物 → 可直接发给 PG 的预览 SQL（task-260819 B-48）。
     *
     * <p>⚠️ <b>本方法必须是 preview() 里"从编译产物到可执行 SQL"的唯一通道</b>，一步都不要挪回
     * 调用点。原因是实测教训：我第一版把三步（展开宏 / 替字面量 / 绑轴数组）摊在 {@code preview()}
     * 里、自测直接调各个小方法——结果**把宏展开那步从 preview() 删掉，自测照样全绿**（测的是零件，
     * 不是装配）。收成一个方法后，删掉其中任何一步自测都会红。
     */
    String buildPreviewSql(CompileResult r, PreviewRequest req) {
        // 预览走裸 JDBC，**不经 SqlViewExecutor 那条管线**，所以编译产物里每一个「本该由管线消化
        // 的东西」都得在这里自己消化。先展开宏、再替字面量——宏展开后仍可能吐出占位符，顺序反了会漏。
        String bound = expandMacrosForPreview(r.sql);
        bound = bindLiterals(bound, req.customerCode, LocalDate.now().toString());
        bound = bindTotalMaterialNo(bound, req.partNo, req.customerCode, resolveDialect(req), r);

        // 兜底：还有没人认领的占位符就别发给 PG——PG 只会回一句 "syntax error at or near :"，
        // 对配置人员零信息量（AC-117/AC-119 被阻塞时看到的正是这句）。
        String unbound = detectUnboundPlaceholders(bound);
        if (unbound != null) {
            throw new BuilderApiException(500, "PREVIEW_UNBOUND_PLACEHOLDER",
                    "预览无法执行：编译产物里的占位符「" + unbound + "」没有被预览路径绑定。"
                            + "这是预览路径（裸 JDBC）与渲染路径（SqlViewExecutor）能力不对等导致的，"
                            + "属后端缺陷，请连同本条信息报告开发", Map.of("placeholders", unbound));
        }
        return bound;
    }

    /**
     * 预览外层包装（task-260819 B-51）：{@code SELECT * FROM (编译产物) __preview [WHERE …] LIMIT 50}。
     *
     * <p>⚠️ <b>外层 {@code hf_part_no} 等值过滤只对报价方言成立</b>。它是"桥改形态之前"的假设 ——
     * 当时 {@code partNo} 与锚点自己的键是同一个号段，外层再过一道只是把闭包收敛到指定成品。
     * B-50 之后核价两套的 {@code partNo} <b>语义翻转成了销售料号</b>，而 {@code hf_part_no} 输出的是
     * 锚点的<b>生产料号</b> ⇒ 两者永不相等 ⇒ <b>恒 0 行</b>。核价侧的收窄已经由 NARROW 半连接在
     * {@code WHERE} 里做完（销售料号 → 生产料号），外层再按 {@code partNo} 过一遍既重复又错位。
     *
     * <p>📌 <b>这是 /preview 的第三个同型缺陷</b>（D-63 漏注入 {@code :total_material_no} →
     * D-95 核价闭包错取 QUOTE 模型 → 本条外层过滤语义错位）。共因是<b>预览路径不走渲染管线，
     * 每加一个"渲染期才成立"的假设，预览就漏一次</b>。{@code detectUnboundPlaceholders} 防得住
     * "漏注入"，防不住"语义错位"——后者没有任何机械信号，只表现为 0 行。
     *
     * <p>⚠️ <b>本方法必须是 preview() 唯一的包装通道</b>，别把条件挪回调用点：B-48 的教训是
     * 把步骤摊在 {@code preview()} 里、自测打各个零件，结果删掉其中一步自测照样全绿。
     */
    String wrapPreviewSql(String bound, PreviewRequest req) {
        String wrapped = "SELECT * FROM (" + bound + ") __preview";
        List<String> conditions = new ArrayList<>();
        if (!resolveDialect(req).isCosting() && req.partNo != null && !req.partNo.isBlank()) {
            conditions.add("hf_part_no = '" + req.partNo.replace("'", "''") + "'");
        }
        if (!conditions.isEmpty()) wrapped += " WHERE " + String.join(" AND ", conditions);
        return wrapped + " LIMIT 50";
    }

    // 包级可见仅为开发自测直调；生产调用点只有 buildPreviewSql()。
    String expandMacrosForPreview(String sql) {
        String out = sql;
        if (VersionFilterMacro.containsMacro(out)) out = VersionFilterMacro.expandForValidation(out);
        if (SpineKeysMacro.containsMacro(out)) out = SpineKeysMacro.expandForValidation(out);
        return out;
    }

    /** 预览拼完 SQL 后仍残留的 {@code :name} 占位符（PG 不认识，发过去就是 syntax error）。 */
    private static final Pattern LEFTOVER_PLACEHOLDER = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * 预览专用兜底体检（B-48）：SQL 发给 PG 之前，先看看还有没有没人认领的 {@code :占位符}。
     *
     * <p>🔑 <b>这条比修某一个宏更重要</b>：D-63 漏 {@code :total_material_no}、本次漏
     * {@code :versionFilter}，两次都是「编译端新增了一个东西、预览端没跟上」，而症状都是
     * 一句对配置人员毫无意义的 {@code syntax error at or near ":"}。有了这条，<b>下一次再漏</b>
     * 会得到一个点名占位符的可读诊断，而不是又一轮根因排查。
     */
    // 包级可见仅为开发自测直调；生产调用点只有 preview()。
    String detectUnboundPlaceholders(String sql) {
        // 先摘掉单引号字符串字面量再扫描：料号/客户编码里真的可能带冒号（如 'AB:CD'），
        // 不摘的话会把 CD 当成"未绑定占位符"报一个假 500 —— 体检本身反而成了故障源。
        // PG 的 '' 是转义单引号，正则里用 (?:[^']|'')* 处理。
        Matcher m = LEFTOVER_PLACEHOLDER.matcher(sql.replaceAll("'(?:[^']|'')*'", "''"));
        LinkedHashSet<String> left = new LinkedHashSet<>();
        while (m.find()) left.add(m.group(1));
        // PG 的类型转换 ::text 不是占位符；上面的 (?<!:) 已排除，这里只是保险
        left.removeIf(String::isBlank);
        return left.isEmpty() ? null : String.join(", ", left);
    }

    /**
     * 把 :customerCode / :priceBaseDate / :customerProductNo 直接替换成字面量
     * （预览只读场景，不走 PreparedStatement 位置参数）。
     */
    private String bindLiterals(String sql, String customerCode, String priceBaseDate) {
        String result = sql;
        if (customerCode != null) {
            result = result.replaceAll("(?<!:):customerCode\\b", Matcher.quoteReplacement("'" + customerCode.replace("'", "''") + "'"));
        }
        if (priceBaseDate != null) {
            result = result.replaceAll("(?<!:):priceBaseDate\\b", Matcher.quoteReplacement("'" + priceBaseDate + "'"));
        }
        // 🔄 task-260911（AC-7 / AC-9）：行级维度占位符已由单数标量 :customerProductNo 改成
        //    **复数集合** :customerProductNos（= ANY(...)）。/preview 走裸 JDBC 字面量替换、
        //    **不经** SqlViewExecutor 的 enrich 管线 ⇒ 不绑就会被 detectUnboundPlaceholders 拦成
        //    500 PREVIEW_UNBOUND_PLACEHOLDER（配置人员点一下预览就报错）。
        //
        // 🚫 **占位符名必须从语义图枚举，不许写死**：写死 "customerProductNos" 的话，第二个列被
        //    打上 ROW_SCOPE 时预览当场 500，而 AC-9 的前提正是「加第二个列只改配置不改 Java」。
        //
        // 🔑 绑**空数组**而不是某个具体编号：预览页没有报价明细行上下文，"属于当前卡片的那一个
        //    客户产品编号"在预览里根本不存在。x = ANY(ARRAY[]::text[]) 恒 false，挂在
        //    LEFT JOIN … ON 上 ⇒ JOIN 不匹配 ⇒ **左表行照常全部返回**（AC-7 要的"非空行"），
        //    只是对端表侧的列显示为空 —— 与 AC-5（明细行客编为空）同一条降级语义，两处口径一致。
        // 🚫 不要绑成恒真形态（如 `IS NOT DISTINCT FROM` 或 `(NULL IS NULL OR …)`）：那会让预览
        //    看到 N 行放大后的结果，与渲染期"只出 1 行"不一致 —— 预览与渲染口径不一致本身就是故障源。
        try {
            for (String p : com.cpq.semanticgraph.service.RowScopeSupport.setParamNames(loader.get())) {
                result = result.replaceAll("(?<!:):" + Pattern.quote(p) + "\\b",
                        Matcher.quoteReplacement("(ARRAY[]::text[])"));
            }
        } catch (Exception ignored) {
            // 语义图不可用时不阻断预览：未绑占位符会被 detectUnboundPlaceholders 明确报出来，
            // 比在这里吞成一个看不懂的 SQL 语法错好。
        }
        // 过渡期：尚未重编译的存量模板仍带 repair-260910 的单数标量占位符。两条 replace 互不干扰
        // —— 上面那条 \b 要求 "No" 后面是非单词字符，对 ":customerProductNos" 不匹配；本条同理，
        // 所以先后顺序不影响结果。B-10 重编译完成后本条恒不命中，可随存量清理一并删除。
        result = result.replaceAll("(?<!:):customerProductNo\\b", "NULL");
        return result;
    }

    private static final Pattern TOTAL_MATERIAL_NO_TOKEN = Pattern.compile("(?<!:):total_material_no\\b");

    /** 标识符白名单守卫：表名/列名来自语义图（DDL 受控），但拼进 SQL 前仍然自己再验一次。 */
    private static boolean isSafeIdentifier(String s) {
        return s != null && s.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

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
    // 包级可见仅为开发自测直调；生产调用点只有 preview()。
    String bindTotalMaterialNo(String sql, String partNo, String customerCode,
                                       CompileDialect dialect, CompileResult compiled) {
        if (!TOTAL_MATERIAL_NO_TOKEN.matcher(sql).find()) return sql; // 该 SQL 不含此占位符，零开销跳过

        // ---- B-48：核价两套走另一条口径 ----
        // 🚫 不能复用下面的 QUOTE 闭包：collectTotalMaterialNoUnion(..., "QUOTE") 查的是 V6 的
        // material_bom_item、按**销售料号**展开；而核价两套的轴是 ds_* 的**生产料号**。拿 V6 的
        // 销售料号闭包去喂 production_no = ANY(...)，结果恒空 ⇒ 预览永远 0 行，且不报错
        // （正是本任务在消灭的静默形态）。两套模型之间只有 D-76 的料号桥，不存在"核价侧闭包"。
        if (dialect.isCosting()) {
            if (partNo != null && !partNo.isBlank()) {
                // 指定了料号：就看这一个（核价侧无子件闭包概念，BOM 行本身按轴列挂在成品上）
                return TOTAL_MATERIAL_NO_TOKEN.matcher(sql).replaceAll(Matcher.quoteReplacement(
                        "ARRAY['" + partNo.replace("'", "''") + "']::text[]"));
            }
            // 未指定料号 = "看这份配置整体产出什么"（AC-117/AC-119 的基准就是整表行数）。
            // 用子查询数组而不是先查一遍再拼字面量：零额外往返、永远与库同步；外层还有 LIMIT 50。
            String tbl = compiled == null ? null : compiled.anchorTable;
            String axis = compiled == null ? null : compiled.axisColumn;
            if (isSafeIdentifier(tbl) && isSafeIdentifier(axis)) {
                return TOTAL_MATERIAL_NO_TOKEN.matcher(sql).replaceAll(Matcher.quoteReplacement(
                        "ARRAY(SELECT DISTINCT " + axis + "::text FROM " + tbl + ")"));
            }
            return TOTAL_MATERIAL_NO_TOKEN.matcher(sql).replaceAll(
                    Matcher.quoteReplacement("ARRAY[]::text[]"));
        }

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

        // B-45（AC-123）：S-20 第③道「物理存在性」前置到 compile 之前。
        // 🔑 为什么必须前置而不是让 compile 去报：compile 只认识**图里的列声明**
        // （findColumn → COMPILE_COLUMN_NOT_FOUND，消息里只有节点显示名，没有物理表名）；
        // 而"图里声明了、库里没有"这一类它**根本查不出来**——编译照样成功，SQL 落库，
        // 到运行期才 column does not exist（AC-123 要拦的正是这一类静默故障）。
        if (checkPhysicalExistence(cfg, resolveDialect(cfg), resp)) {
            resp.blocked = true;
            return resp;
        }

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

    /**
     * S-20 第③道 · 物理存在性校验在新图上跑通（task-260819 B-45，AC-123 / S-28）。
     *
     * <p>校验对象是「本次配置**实际会引用到**的 (物理表, 物理列)」：页签视图锚点的表 + 每个已选列
     * 所属节点的表与列。判据是 {@code information_schema}（视图同样算数——{@code information_schema}
     * 的 {@code tables}/{@code columns} 覆盖 VIEW，所以 S-31 建的 {@code v_<主表>_all} 全版本视图
     * 天然通过，不必开例外）。
     *
     * <p>🚨 <b>错误信息必须点名"哪张表的哪一列"</b>（AC-123 原文）：配置人员不写 SQL，
     * "节点「物料与元素BOM」没有列 xxx" 对他们不可操作；给出物理表名 + 该表实有列清单才能自己改对。
     *
     * <p>🚫 <b>不复用 {@link com.cpq.semanticgraph.service.SemanticGraphValidator#checkColumnExists}</b>：
     * 那个方法是**每列一条** {@code information_schema} 查询，那里的调用方一次只校验一列所以没问题；
     * 在这里按已选列循环调用就是标准 N+1（列数 N → N 条 SQL）。改用
     * {@link PhysicalColumnCatalog#columnsOf}，本次涉及的全部物理表<b>一条 SQL 查完</b>。
     *
     * <p>N+1 自检：本方法恒 1 条 SQL（{@code columnsOf} 的 {@code table_name = ANY(:tbls)}），
     * 与已选列数 / 图节点数无关；两个 {@code for} 循环体内全是 {@link SemanticGraphSnapshot}
     * 的内存 Map 查找，零查库。
     *
     * @return true = 发现不存在的表/列（已把 ERR 写入 resp.items，调用方应阻断）
     */
    // 包级可见（而非 private）仅为让同包的开发自测直接喂桩 loader/catalog 调用它——本方法唯一的
    // 生产调用点仍是上面的 inspect()。
    boolean checkPhysicalExistence(BuilderConfig cfg, CompileDialect dialect, InspectResponse resp) {
        List<BuilderConfig.ColumnConfig> cols = cfg.columns == null ? List.of() : cfg.columns;
        SemanticGraphSnapshot snap = loader.get();
        String dl = dialect.name();
        String vk = cfg.variantKey == null ? "" : cfg.variantKey;

        // 待校验的 (表 → 列集合)。锚点表即使一列都没选也要校验——FROM 子句一定引用它。
        Map<String, Set<String>> want = new LinkedHashMap<>();
        // 列 → 报错时用的可读上下文：[物理表, 物理列, 字段名]
        List<String[]> colRefs = new ArrayList<>();

        SemanticTabView tv = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(vk) && dl.equals(t.dialect))
                .findFirst().orElse(null);
        if (tv != null) {
            SemanticNode anchor = snap.nodeById.get(tv.anchorNodeId);
            if (anchor != null && anchor.physicalTable != null && !anchor.physicalTable.isBlank()) {
                want.computeIfAbsent(anchor.physicalTable, k -> new LinkedHashSet<>());
            }
        }
        // 页签视图/锚点缺失不在本校验的职责内——compile() 会报 COMPILE_TABVIEW_NOT_FOUND，
        // 在这里再报一遍只会让同一个问题出现两条不同措辞的错误。

        for (BuilderConfig.ColumnConfig col : cols) {
            if (col.sourceNodeKey == null || col.sourceColumn == null || col.sourceColumn.isBlank()) continue;
            SemanticNode n = snap.nodeByKeyDialect.get(col.sourceNodeKey + "|" + dl);
            if (n == null) continue;                       // compile 报 COMPILE_COLUMN_SOURCE_UNKNOWN
            if (n.physicalTable == null || n.physicalTable.isBlank()) continue; // FUNCTION 节点（价格策略）无物理表
            want.computeIfAbsent(n.physicalTable, k -> new LinkedHashSet<>()).add(col.sourceColumn);
            String fieldName = (col.fieldName != null && !col.fieldName.isBlank()) ? col.fieldName : col.sourceColumn;
            colRefs.add(new String[]{n.physicalTable, col.sourceColumn, fieldName});
        }
        if (want.isEmpty()) return false;

        Map<String, Set<String>> actual = physicalColumnCatalog.columnsOf(want.keySet()); // ← 唯一一条 SQL
        boolean failed = false;
        Set<String> reported = new LinkedHashSet<>();

        for (String table : want.keySet()) {
            if (!actual.containsKey(table) || actual.get(table).isEmpty()) {
                resp.items.add(new InspectItem("ERR", "PHYSICAL_EXISTENCE",
                        "取数表「" + table + "」在数据库里不存在（数据集 " + dl
                                + "）：语义图声明的物理表已被改名或删除，这个配置存下来一定取不到数"));
                failed = true;
            }
        }
        for (String[] ref : colRefs) {
            String table = ref[0], column = ref[1], fieldName = ref[2];
            Set<String> real = actual.get(table);
            if (real == null || real.isEmpty()) continue;   // 表都不存在，上面已报，不重复刷屏
            if (real.contains(column)) continue;
            if (!reported.add(table + "." + column)) continue;
            resp.items.add(new InspectItem("ERR", "PHYSICAL_EXISTENCE",
                    "字段「" + fieldName + "」取的是 " + table + "." + column
                            + " —— 该表在数据库里没有这一列。" + table + " 实有列："
                            + previewColumns(real)));
            failed = true;
        }
        return failed;
    }

    /** 错误信息里列出实有列，超过 30 个截断——全列表对排查没有增量价值，只会把 message 撑爆。 */
    private static String previewColumns(Set<String> cols) {
        List<String> sorted = new ArrayList<>(cols);
        Collections.sort(sorted);
        if (sorted.size() <= 30) return String.join(", ", sorted);
        return String.join(", ", sorted.subList(0, 30)) + " …（共 " + sorted.size() + " 列）";
    }

    private void runInspectChecks(BuilderConfig cfg, CompileResult r, InspectResponse resp) {
        List<BuilderConfig.ColumnConfig> cols = cfg.columns == null ? List.of() : cfg.columns;

        // AC-30：料号列 / 名称列至少一个
        //
        // 🔄 task-260904 B-22（AC-29①，用户 2026-09-06 裁决）：**树页签豁免**。
        //    树页签的料件标识取系统列 __hfPartNo，本就不需要用户配标识列 —— 与
        //    {@code ComponentService.TAB_TYPES_REQUIRE_PART_NO_FIELD} 不含 "BOM" 是同一口径。
        //    不加这个前置条件，「绑物料BOM、不配标识列」在**保存前体检**这一关就 400
        //    INSPECT_BLOCKED，压根走不到 ComponentService 那条按 semantic 判的分支
        //    （2026-09-06 实测：AC-29① 的 A 用例在真实配置器路径上恒 400）。
        //
        // 🚨 同一条业务规则有**两个执行点**（这里的保存前体检 + ComponentService.applyTabType
        //    的保存期校验），判据必须一致；只改一个 = 上游堵死、下游的放行永远不生效。
        // 🚫 semantic 一律走 TabSemanticResolver 的唯一映射，**不许在本文件里再写一份**
        //    tab_type→semantic 的 if/else —— 那会是第三份，而三份各自漂移的失败形态是
        //    「面板说这是树、体检当它不是树」，两边都不报错。
        // 📌 cfg.tabType 就是 semantic_tab_view.tab_type 这一段坐标本身（compile 已按
        //    (tabType, variantKey, dialect) 精确命中该行，命不中会先抛 TabViewNotFound），
        //    故此处直接映射与「查行后取 tv.tabType 再映射」逐字等价。
        boolean isTreeTab = com.cpq.component.service.TabSemanticResolver.SEMANTIC_TREE
                .equals(com.cpq.component.service.TabSemanticResolver.semanticOfGraphTabType(cfg.tabType));
        boolean hasPartNo = cols.stream().anyMatch(c -> c.resolvedRoles != null && c.resolvedRoles.contains("PART_NO"));
        boolean hasPartName = cols.stream().anyMatch(c -> c.resolvedRoles != null && c.resolvedRoles.contains("PART_NAME"));
        if (!isTreeTab && !hasPartNo && !hasPartName) {
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
        // B-41 连带修：页签视图与节点查找必须带 dialect —— semantic_tab_view 的唯一键是
        // (tab_type, variant_key, dialect)，v9 起三套数据集各有一行并列。不过滤时 findFirst()
        // 会拿到加载顺序里的第一行，体检读的锚点可能是另一套数据集的 ⇒ AC-18/19 的小计判据
        // 静默按错锚点算（既可能漏拦也可能误拦），且全程不报错。
        String graphDialect = resolveDialect(cfg).name();
        SemanticTabView tabView = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(variantKey)
                        && graphDialect.equals(t.dialect))
                .findFirst().orElse(null);
        if (tabView == null) return; // compile() 早已对页签视图缺失报过错，这里不会真的走到
        SemanticNode anchor = snap.nodeById.get(tabView.anchorNodeId);
        if (anchor == null) return;

        boolean grainWidened = r.grain != null && r.grain.size() > 1;
        boolean anchorHasOwnSubIdentity = anchor.grainColumns != null && anchor.grainColumns.length > 0;

        for (BuilderConfig.ColumnConfig col : cols) {
            if (!Boolean.TRUE.equals(col.inSubtotal)) continue;
            SemanticNode source = snap.nodeByKeyDialect.get(col.sourceNodeKey + "|" + graphDialect);
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

        // task-260909 B-1（AC-8）：fieldType 白名单校验。
        // 🔑 **必须是 save() 的第一件事**（早于 inspect / doCompile / 任何 persist）——
        //    AC-8 断言"校验失败时库中不发生变更"。虽然 BuilderApiException 是 RuntimeException、
        //    @Transactional 会回滚，但把校验放在**任何写之前**才是不依赖回滚语义的证明。
        assertValidFieldTypes(req);

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

        // task-260909 B-5②（AC-16）：把 effective fieldType 回填进请求体的列，使下面落进
        // builder_config 的值 == 实际生效的值。必须在 doCompile 之后（要 resolvedDataType）、
        // 在 builder_config 序列化之前（就在下面几行）。详见 applyEffectiveFieldTypes 的 javadoc。
        applyEffectiveFieldTypes(r.effectiveColumns, resolveDialect(req));

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
            // repair-260908 B-4（AC-7）：把**编译产物**里的轴范围声明并进 builder_config。
            // 🔑 它是**产物**不是**输入** —— 故不进 BuilderConfig 类（那是请求体的形状），
            //    只作为 jsonb 的一个键落库；BuilderConfig 有 @JsonIgnoreProperties(ignoreUnknown=true)，
            //    下次读回来重编译时会被安全忽略，不会撞 Unrecognized field。
            // 🚫 不要反过来把它做成用户可填字段：那样"页签类型"与"轴范围"就成了两个可以互相矛盾
            //    的真相源，而矛盾时谁赢是隐式的。
            node.put("axisScope", r.axisScope);
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
        // task-260904 (c) 方案（用户 2026-09-05 裁决）：按本任务目标形态，
        // 组件不再持有页签类型 —— 页签语义由所绑数据源的 semantic 推导（见 TabSemanticResolver）。
        // 故此处不再写 compReq.tabType：留 null 时 ComponentService.applyTabType 走
        // 「requestedTabType == null ⇒ 不改动 tab_type」分支，新组件保持 NULL，
        // 存量组件经配置器重存时其 tab_type 也一个都不会被改写（AC-25④）。
        // 🚫 本行与「配置器存 BOM 树组件 400」无关：那是 task-260819 的 V413 种子把显示名
        //    写进了 semantic_tab_view.tab_type 键值列，由其 V417 修复（D-128/S-33）。

        // task-260909 B-2：默认 field_type 按数据集方言分叉（见 defaultFieldType 的 javadoc）。
        // 循环外解一次——resolveDialect 是纯函数解析请求体字符串，不查库，但没有理由在循环里重复解。
        CompileDialect dialect = resolveDialect(req);

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
            // 显式传值恒优先于默认（api.md §1.3）；不传/空串才按方言推（B-2）。
            // 空串按"未传"处理，与 assertValidFieldTypes 的放行口径必须一致——否则空串会绕过
            // 校验、又不走默认，最后原样落成一个空的 field_type。
            boolean explicit = col.fieldType != null && !col.fieldType.isBlank();
            String effectiveFieldType = explicit
                    ? col.fieldType
                    : defaultFieldType(dialect, col.resolvedDataType);
            f.put("field_type", effectiveFieldType);
            f.put("sort_order", fields.size());
            // B-30 (D-73, task-260819)：绑定键跟 field_type 走，不跟报价/核价侧走——
            // BASIC_DATA 写顶层 basic_data_path（平铺字符串，前端 useCardSnapshots.ts:99/:201
            // 的 BASIC_DATA 渲染分支只读这个键）；INPUT_TEXT/INPUT_NUMBER 仍写 default_source.path
            // （嵌套对象）。此前无条件写 default_source 致报价侧 BASIC_DATA 字段恒取不到值、
            // 静默回退 content()，不报错。
            String basicPath = "$" + viewName + "." + col.viewColumn;
            if ("BASIC_DATA".equals(effectiveFieldType)) {
                f.put("basic_data_path", basicPath);
            } else {
                f.put("default_source", Map.of("type", "BASIC_DATA", "path", basicPath));
            }
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
            // 编码列 = 顺着**锚点的 PRICE 边**取 seq 最小的那个连接键，其 left_column 就是锚点侧的
            // 元素键列 —— 与 {@link SemanticCompiler#resolvePricePlan} 读的是同一处真源（那边据此
            // 生成 JOIN 左键 cep.<right> = <锚点别名>.<left>，并在用户没手拖时把这一列自动补进
            // effectiveColumns）。两处认列判据同源，就不会再各自漂移。
            //
            // 🚫 不要退回按列名匹配：此处原先写死 V6 口径的 component_no / code，而 v9 锚点
            // ELEMENT_BOM 的元素键列叫 element_code，两个名字都对不上 ⇒ codeField 恒为 null ⇒
            // 形态 A（元素列取自语义图）保存必撞它自己的 COMPONENT_ELEMENT_BINDING_REQUIRED
            // 守卫，v9 下任何想用价格策略的「材质元素」组件都存不下来（B-57）。
            // ⚠️ 也不要放宽成"任意带 ROW_KEY 的编码列"：实测锚点上 material_no / material_part_no
            // 同样是 ROW_KEY + is_code，宽松判据会让 findFirst() 把 element_code_field 回填成
            // 「材质料号」—— 存得下来但绑错列，比直接报错隐蔽得多。必须按连接键逐字认列。
            ElementCodeSource src = resolveElementCodeSource(req);
            if (src != null) {
                codeField = r.effectiveColumns.stream()
                        .filter(col -> src.nodeKey().equals(col.sourceNodeKey)
                                && src.column().equals(col.sourceColumn))
                        .map(col -> col.fieldName).findFirst().orElse(null);
            }
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

    /** 锚点侧元素键列的来源坐标（节点 key + 物理列名），由 PRICE 边的连接键解出。 */
    private record ElementCodeSource(String nodeKey, String column) {}

    /**
     * 解析「元素键列」在语义图里的来源坐标（task-260819 B-57）。
     *
     * <p>判据与 {@code SemanticCompiler#resolvePricePlan} <b>逐字同源</b>：页签视图按
     * {@code (tabType, variantKey, dialect)} 三元组定位 → 取其锚点 → 顺锚点的 {@code PRICE} 边 →
     * 取 {@code seq} 最小的连接键，其 {@code leftColumn} 即锚点侧元素键列。编译器正是用这一列
     * 生成 JOIN 左键、并在用户没手拖时把它自动补进 {@code effectiveColumns}，所以回填按同一坐标
     * 去 {@code effectiveColumns} 里认列必然认得到。
     *
     * <p>解析不出来时返回 {@code null}（调用方据此让 {@code codeField} 保持为空，由既有守卫报错）——
     * 保存流程在此之前已经 {@code doCompile} 过一次，图缺页签/锚点/边/连接键的情形编译器早就 400 了，
     * 这里的 {@code null} 分支只是不越权替它下结论。
     *
     * <p>N+1 自检：{@code loader.get()} 返回的是启动期加载、{@code AtomicReference} 持有的不可变
     * 内存快照，本方法全程只做内存图遍历，<b>零 SQL</b>；每次 save 调用一次，与列数/图规模无关。
     */
    private ElementCodeSource resolveElementCodeSource(BuilderConfig cfg) {
        SemanticGraphSnapshot snap = loader.get();
        String variantKey = cfg.variantKey == null ? "" : cfg.variantKey;
        String graphDialect = resolveDialect(cfg).graphDialect();
        SemanticTabView tabView = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(variantKey)
                        && graphDialect.equals(t.dialect))
                .findFirst().orElse(null);
        if (tabView == null) return null;
        SemanticNode anchor = snap.nodeById.get(tabView.anchorNodeId);
        if (anchor == null) return null;
        SemanticEdge priceEdge = snap.edgesFrom(anchor.id).stream()
                .filter(e -> "PRICE".equals(e.edgeKind)).findFirst().orElse(null);
        if (priceEdge == null) return null;
        String codeColumn = null;
        int bestSeq = Integer.MAX_VALUE;
        for (var k : snap.keysOf(priceEdge.id)) {
            if (k.seq < bestSeq) {
                bestSeq = k.seq;
                codeColumn = k.leftColumn;
            }
        }
        return codeColumn == null ? null : new ElementCodeSource(anchor.nodeKey, codeColumn);
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
