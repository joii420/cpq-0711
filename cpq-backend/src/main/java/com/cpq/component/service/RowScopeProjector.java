package com.cpq.component.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import com.cpq.semanticgraph.service.RowScopeSupport;
import com.cpq.semanticgraph.service.SemanticGraphLoader;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * task-260911 · B-7：<b>行级作用域单点投影</b> —— 把「整单合桶」查回来的超集，按明细行挑出属于它的那一行。
 *
 * <p><b>为什么需要这一层</b>：{@code repair-260910} 把「按本行客编收窄」做在 SQL 里（标量谓词
 * {@code = :customerProductNo}），结果视图输出依赖<b>明细行</b>，合桶
 * （{@code ComponentDriverService#eligibleForQuoteBucket}）在定义上不成立 ——
 * 整单物化从 1 次 {@code expandMulti} 退化成每行 1 次 expand + 1 次反查（实测最大单 1845 行 ≈ 3690 条 SQL）。
 * 本任务把谓词改成<b>集合成员</b> {@code = ANY(:<列>s)}（值 = 整单去重集合）⇒ 输出只依赖<b>单</b>、
 * 不依赖<b>行</b> ⇒ 合桶重新成立；「哪一行属于我」这件事下沉到这里，在内存里做。
 *
 * <p>🚫 <b>本类不硬编码任何列名</b>（api.md §4）。两个判据各有唯一出处：
 * <ol>
 *   <li><b>哪一列是行级维度</b> —— 语义图 {@code semantic_node_column.roles} 含
 *       {@code ROW_SCOPE}（迁移 {@code V441} 写入，{@link RowScopeSupport#ROLE}）；</li>
 *   <li><b>它在视图里叫什么</b> —— {@code component_sql_view.builder_config} 的
 *       {@code columns[]} 三元组 {@code sourceNodeKey}/{@code sourceColumn}/{@code viewColumn}。</li>
 * </ol>
 * ⇒ 给第二个列打上标记（一行 {@code UPDATE} + 重编译）即自动参与投影，无需改 Java（AC-9）。
 *
 * <p><b>精确闸门</b>：某组件到底走不走投影，最终判据是「该视图编译出来的 {@code sql_template}
 * <b>真的含</b>对应的集合占位符 {@code :<列>s}」。这一条同时把两件事挡在外面：<b>①</b> 没打标记的列
 * （AC-7 加法式，{@link Plan#project} 原样返回入参对象）；<b>②</b> 打了标记但被页签级
 * {@code semantic_tab_view_column} 覆盖掉的列 —— 两层覆盖（D-35）已经在编译期生效并烙进模板，
 * 这里不需要、也<b>不应该</b>再算一遍（算第二遍就是双写，日后必漂移）。
 *
 * <p>🚨 <b>B-6 空值兜底是方案能否成立的必要条件，不是配套守卫</b>
 * （实证：{@code 证据/S2-判别性反例实证-AC5静默失败面-260911.md}）。
 * 标量谓词下客编为空时 {@code = NULL} 恒 UNKNOWN ⇒ 该料号所有 {@code dqcp} 行都不匹配 ⇒
 * LEFT JOIN <b>天然产生一行「未匹配」记录</b>（物料侧有值、客编侧全 NULL），这就是现状那 1 行的来源。
 * 换成集合谓词后，只要该料号<b>还有别的客编在集合里</b>就会匹配上 ⇒ 未匹配那一行<b>根本不产生</b>
 * ⇒ 实测桶内属于空客编明细行的行数 = <b>0</b>，影响 <b>128/3970</b> 明细行，且<b>不报错</b>。
 * ⇒ {@link Plan#project} 桶内挑不到时<b>必须</b>基于该料号任一桶内行构造返回行、把作用域侧的列置空，
 * 🚫 不得返 0 行、🚫 不得回退逐行查询（那会重新破坏合桶，回到原点）。
 * 数据上安全：桶内这几行的物料侧列完全相同（同一个左表行 LEFT JOIN 出来的），差异只在对端表侧。
 *
 * <p><b>N+1 纪律</b>：{@link #planFor} 每单 <b>1 条</b> SQL（整单作用域值，与明细行数无关；
 * 语义图里一个 {@code ROW_SCOPE} 列都没有时 <b>0 条</b>）；{@link Plan#project} 的视图列解析按
 * {@code componentId} <b>记忆化</b>，每组件至多 1 条 SQL、与明细行数无关。投影本身是纯内存运算。
 *
 * <p><b>AP-37 可变共享面</b>：桶里的 {@code Row} 被同料号多行共享。本类<b>只读不改</b> ——
 * 命中分支返回装着<b>原 Row 引用</b>的新响应对象（共享语义与改动前一致，调用方写库前仍走
 * {@code MAPPER.writeValueAsString} 深拷贝）；兜底分支<b>另建</b>新 Row + 新 Map，
 * 🚫 绝不就地 mutate 桶里的对象。
 */
@ApplicationScoped
public class RowScopeProjector {

    private static final Logger LOG = Logger.getLogger(RowScopeProjector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 标识符白名单：列名来自语义图（DDL 受控），拼进 SQL 前仍自己再验一次。 */
    private static final Pattern SQL_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,79}$");

    @Inject
    SemanticGraphLoader semanticGraphLoader;

    @Inject
    DataSource dataSource;

    /** 语义图里一个 {@code ROW_SCOPE} 列都没有时的常量计划：{@link Plan#project} 恒等，零查询、零分配。 */
    private static final Plan NOOP = new Plan(Set.of(), Map.of());

    /**
     * 为一张报价单构建投影计划（整单一次，{@code expandMulti} 之前或之后都可以）。
     *
     * <p>语义图里没有任何 {@code ROW_SCOPE} 列 → 直接返回 {@link #NOOP}，<b>不发 SQL</b>
     * （这是「本机制是加法式的」在成本上的体现：没人打标记时整条链路开销为 0）。
     *
     * @param quotationId 报价单 id；为 {@code null} 时返回 {@link #NOOP}（无单据上下文，无从取作用域值）
     */
    public Plan planFor(UUID quotationId) {
        SemanticGraphSnapshot snap;
        try {
            snap = semanticGraphLoader.get();
        } catch (Exception e) {
            LOG.warnf("[row-scope] 语义图不可用，本单不做行级投影: %s", e.getMessage());
            return NOOP;
        }
        if (snap == null) return NOOP;

        // 「nodeKey|dbColumn」集合 —— 唯一判据是节点级 roles。
        // 🚫 这里刻意**不**算页签级覆盖（D-35）：覆盖结果已经烙进 sql_template，
        //    真正的闸门是下面 resolveComp() 的「模板含不含该占位符」，在这儿再算一遍就是双写。
        Set<String> scopeNodeCols = new LinkedHashSet<>();
        Set<String> scopeDbColumns = new LinkedHashSet<>();
        for (SemanticNodeColumn col : snap.nodeColumns) {
            if (col == null || col.roles == null || col.dbColumn == null) continue;
            boolean marked = false;
            for (String r : col.roles) {
                if (RowScopeSupport.ROLE.equals(r)) { marked = true; break; }
            }
            if (!marked) continue;
            SemanticNode node = snap.nodeById.get(col.nodeId);
            if (node == null || node.nodeKey == null) continue;
            scopeNodeCols.add(node.nodeKey + "|" + col.dbColumn);
            scopeDbColumns.add(col.dbColumn);
        }
        if (scopeNodeCols.isEmpty() || quotationId == null) return NOOP;

        Map<UUID, Map<String, String>> valuesByLine = loadScopeValues(quotationId, scopeDbColumns);
        return new Plan(scopeNodeCols, valuesByLine);
    }

    /**
     * 整单一次取回每个明细行的全部作用域值：{@code lineItemId → (语义图列名 → 值)}。
     *
     * <p>「语义图列名 → {@code quotation_line_item} 列名」的映射走
     * {@link RowScopeSupport#lineItemColumnFor}（约定同名，只列例外）。
     * 失败一律降级为空 Map（该单全部行都走 B-6 兜底 = 与「客编为空」同一条路径），
     * 🚫 不抛异常打断物化链路。
     *
     * <p><b>1 条 SQL，与明细行数无关。</b>
     */
    private Map<UUID, Map<String, String>> loadScopeValues(UUID quotationId, Set<String> scopeDbColumns) {
        Map<UUID, Map<String, String>> out = new HashMap<>();
        // 语义图列名 → 明细行列名；去重（不同节点可能声明同名列）
        Map<String, String> liColByDbCol = new LinkedHashMap<>();
        for (String dbCol : scopeDbColumns) {
            String liCol = RowScopeSupport.lineItemColumnFor(dbCol);
            if (!SQL_IDENT.matcher(liCol).matches()) {
                LOG.warnf("[row-scope] 行级维度列名非法，跳过: %s", liCol);
                continue;
            }
            liColByDbCol.put(dbCol, liCol);
        }
        if (liColByDbCol.isEmpty()) return out;

        List<String> distinctLiCols = new ArrayList<>(new LinkedHashSet<>(liColByDbCol.values()));
        // 列名 → 结果集 1-based 位置（+1 跳过 id，+1 转 1-based），避免逐行 indexOf
        Map<String, Integer> posByLiCol = new HashMap<>();
        for (int i = 0; i < distinctLiCols.size(); i++) posByLiCol.put(distinctLiCols.get(i), i + 2);
        StringBuilder sql = new StringBuilder("SELECT id");
        for (String c : distinctLiCols) sql.append(", ").append(c);
        sql.append(" FROM quotation_line_item WHERE quotation_id = ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setObject(1, quotationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object idObj = rs.getObject(1);
                    if (idObj == null) continue;
                    UUID lid;
                    try { lid = UUID.fromString(idObj.toString()); } catch (Exception ignored) { continue; }
                    Map<String, String> byDbCol = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : liColByDbCol.entrySet()) {
                        Integer pos = posByLiCol.get(e.getValue());
                        if (pos == null) continue;
                        byDbCol.put(e.getKey(), RowScopeSupport.normalize(rs.getObject(pos)));
                    }
                    out.put(lid, byDbCol);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[row-scope] 取整单作用域值失败 quotation=%s，全部行按「无作用域值」兜底: %s",
                    quotationId, e.getMessage());
        }
        return out;
    }

    /**
     * 某组件视图上的作用域解析结果（按 {@code componentId} 记忆化在 {@link Plan} 里，每组件至多 1 条 SQL）。
     *
     * @param active           该视图是否真的带了行级集合谓词（模板含 {@code :<列>s}）——false 时投影恒等
     * @param matchCols        参与「挑出属于本行那一行」的列：视图列名 → 语义图列名
     * @param blankViewColumns 兜底构造行时要置空的视图列 —— <b>不只是作用域列本身</b>，还包括
     *                         <b>同源于同一个对端节点</b>的其余列（如客户零件名称 / 客户图号）。
     *                         漏掉它们会让兜底行显示<b>别的明细行</b>的客户侧数据 —— 比显示空更糟：
     *                         它是一个看起来完全正常、但归属错误的值。
     */
    record CompScope(boolean active,
                             Map<String, String> matchCols,
                             Set<String> blankViewColumns) {
        static final CompScope INACTIVE = new CompScope(false, Map.of(), Set.of());
    }

    /**
     * 解析某组件的视图作用域列。{@code driverPath} 由调用方传入（两个调用点本来就握着它），
     * 省掉一次 {@code Component.findById}。
     */
    private static CompScope resolveComp(Set<String> scopeNodeCols, UUID componentId, String driverPath) {
        String viewName = ComponentDriverService.extractSqlViewName(driverPath);
        if (viewName == null) return CompScope.INACTIVE;
        ComponentSqlView v;
        try {
            v = ComponentSqlView.find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        } catch (Exception e) {
            LOG.warnf("[row-scope] 读视图失败 comp=%s view=%s，不做投影: %s", componentId, viewName, e.getMessage());
            return CompScope.INACTIVE;
        }
        if (v == null || v.sqlTemplate == null) return CompScope.INACTIVE;
        if (v.builderConfig == null || v.builderConfig.isBlank()) {
            // 手写模式视图（builder_config 为 null）：没有 sourceNodeKey/viewColumn 三元组可读 ⇒
            // 无从知道作用域列在视图里叫什么。这类视图也不会被编译器发集合谓词（谓词只由编译器生成），
            // 正常情况下恒不含占位符；若含（人手抄进去的）则只能不投影并告警，不静默。
            if (templateUsesAnyScopeParam(scopeNodeCols, v.sqlTemplate)) {
                LOG.warnf("[row-scope] comp=%s view=%s 是手写模式(builder_config 为空)却含行级集合占位符，"
                        + "无法解析视图列名 ⇒ 不做投影（该页签会返回整单超集，请改用取数配置器生成）",
                        componentId, viewName);
            }
            return CompScope.INACTIVE;
        }

        BuilderConfig cfg;
        try {
            cfg = MAPPER.readValue(v.builderConfig, BuilderConfig.class);
        } catch (Exception e) {
            LOG.warnf("[row-scope] 解析 builder_config 失败 comp=%s view=%s，不做投影: %s",
                    componentId, viewName, e.getMessage());
            return CompScope.INACTIVE;
        }
        if (cfg == null || cfg.columns == null || cfg.columns.isEmpty()) return CompScope.INACTIVE;

        Map<String, String> matchCols = new LinkedHashMap<>();
        Set<String> scopeNodeKeys = new LinkedHashSet<>();
        for (BuilderConfig.ColumnConfig cc : cfg.columns) {
            if (cc == null || cc.sourceNodeKey == null || cc.sourceColumn == null || cc.viewColumn == null) continue;
            if (!scopeNodeCols.contains(cc.sourceNodeKey + "|" + cc.sourceColumn)) continue;
            // 🔑 精确闸门：模板真的含这个集合占位符才算数（页签级覆盖已在编译期生效）
            if (!v.sqlTemplate.contains(":" + RowScopeSupport.setParamName(cc.sourceColumn))) continue;
            matchCols.put(cc.viewColumn, cc.sourceColumn);
            scopeNodeKeys.add(cc.sourceNodeKey);
        }
        if (matchCols.isEmpty()) return CompScope.INACTIVE;

        // 同源于作用域节点的**全部**视图列都要能被兜底置空（见 CompScope#blankViewColumns 注释）
        Set<String> blanks = new LinkedHashSet<>();
        for (BuilderConfig.ColumnConfig cc : cfg.columns) {
            if (cc == null || cc.sourceNodeKey == null || cc.viewColumn == null) continue;
            if (scopeNodeKeys.contains(cc.sourceNodeKey)) blanks.add(cc.viewColumn);
        }
        LOG.debugf("[row-scope] comp=%s view=%s 作用域列=%s 兜底置空列=%s",
                componentId, viewName, matchCols.keySet(), blanks);
        return new CompScope(true, matchCols, blanks);
    }

    private static boolean templateUsesAnyScopeParam(Set<String> scopeNodeCols, String tpl) {
        for (String k : scopeNodeCols) {
            int bar = k.indexOf('|');
            if (bar < 0) continue;
            if (tpl.contains(":" + RowScopeSupport.setParamName(k.substring(bar + 1)))) return true;
        }
        return false;
    }

    /**
     * 一张报价单的投影计划。整单构建一次，逐行逐组件复用。
     *
     * <p>线程安全：构建完成后除 {@link #compCache} 外全部只读；{@code compCache} 是
     * {@link ConcurrentHashMap}，重复解析同一组件只是幂等重算，不会算错。
     */
    public static final class Plan {

        private final Set<String> scopeNodeCols;
        /** lineItemId → (语义图列名 → 该行的作用域值，空白已归一为 null) */
        private final Map<UUID, Map<String, String>> valuesByLine;
        private final Map<UUID, CompScope> compCache = new ConcurrentHashMap<>();

        private Plan(Set<String> scopeNodeCols, Map<UUID, Map<String, String>> valuesByLine) {
            this.scopeNodeCols = scopeNodeCols;
            this.valuesByLine = valuesByLine;
        }

        /** 本单是否需要投影（语义图无 {@code ROW_SCOPE} 列 / 无单据上下文 → false，调用方可整段跳过）。 */
        public boolean active() {
            return !scopeNodeCols.isEmpty();
        }

        /**
         * 把合桶查回的超集投影成「属于该明细行的那一行」。
         *
         * <p><b>三条分支</b>：
         * <ol>
         *   <li><b>不适用</b>（本单无 {@code ROW_SCOPE} / 该组件视图没带集合谓词）→ <b>原样返回入参对象</b>，
         *       与改动前逐字节相同（AC-7 加法式）；</li>
         *   <li><b>命中</b> → 返回只含匹配行的新响应（行对象仍是桶里的原引用，只读共享）；</li>
         *   <li><b>未命中且桶内非空</b> → <b>B-6 兜底</b>：基于桶内第一行构造 1 行，作用域侧的列全部置空
         *       （见 {@link #blankScopeSide}）。桶内本来就 0 行 → 原样返回（那是真的没数据，
         *       不是「挑不到」，不许凭空造出一行来）。</li>
         * </ol>
         *
         * @param componentId 组件 id
         * @param driverPath  该组件生效的 driver path（调用方已持有，省一次 {@code Component.findById}）
         * @param lineItemId  本明细行 id
         * @param exp         合桶结果里属于本行料号的那一桶
         */
        public ExpandDriverResponse project(UUID componentId, String driverPath,
                                            UUID lineItemId, ExpandDriverResponse exp) {
            if (!active() || exp == null || componentId == null) return exp;
            CompScope cs = compCache.computeIfAbsent(componentId,
                    k -> resolveComp(scopeNodeCols, k, driverPath));
            return projectRows(cs, valuesByLine.getOrDefault(lineItemId, Map.of()), exp);
        }

    }

    /**
     * <b>纯函数内核</b>：给定「该组件的作用域解析结果 + 本明细行要的值 + 桶里的超集」，算出属于本行的响应。
     *
     * <p>🚨 <b>为什么单独抽出来（并且是 package-private）</b>：B-6 的空值兜底是本方案能否成立的
     * <b>必要条件</b> —— 不做它，{@code AC-5} 必红，影响实测 <b>128/3970</b> 明细行，<b>而且完全不报错</b>
     * （症状只是那类卡片整页签 0 行）。一个会静默失效的兜底，必须有机械信号证明它还活着。
     * 本方法不连库、不碰 CDI、输入全是内存对象 ⇒ 可由 {@code RowScopeProjectorProjectionTest}
     * 直接驱动，沿用 {@code SemanticCompilerAxisNarrowGuardTest} 立的先例（🚫 不起 Quarkus、不碰共享库）。
     */
    static ExpandDriverResponse projectRows(CompScope cs, Map<String, String> want, ExpandDriverResponse exp) {
        if (cs == null || !cs.active() || exp == null) return exp;      // 分支 ①：加法式，逐字节不变
        List<ExpandDriverResponse.Row> rows = exp.rows;
        if (rows == null || rows.isEmpty()) return exp;                 // 真·无数据，🚫 不凭空造行
        if (want == null) want = Map.of();

        List<ExpandDriverResponse.Row> matched = new ArrayList<>();
        for (ExpandDriverResponse.Row row : rows) {
            if (row == null) continue;
            if (rowMatches(cs, row, want)) matched.add(row);
        }
        if (!matched.isEmpty()) return respond(exp, matched);           // 分支 ②：命中

        // 分支 ③：B-6 兜底 —— 桶里没有属于本行的行（本行作用域值为空，或该值在对端表里没有记录）。
        // 这正是标量谓词时代 LEFT JOIN「全不匹配」天然产生的那一行，集合谓词把它弄没了，这里补回来。
        ExpandDriverResponse.Row pivot = null;
        for (ExpandDriverResponse.Row row : rows) { if (row != null) { pivot = row; break; } }
        if (pivot == null) return exp;
        LOG.debugf("[row-scope] 桶内无匹配(want=%s, 桶 %d 行) → 构造兜底行并置空作用域侧列", want, rows.size());
        return respond(exp, List.of(blankScopeSide(cs, pivot)));
    }

    /** 该行的全部作用域列是否都等于本明细行要的值（null 与空白等价）。 */
    static boolean rowMatches(CompScope cs, ExpandDriverResponse.Row row, Map<String, String> want) {
        Map<String, Object> dr = row.driverRow;
        if (dr == null) return false;
        for (Map.Entry<String, String> e : cs.matchCols().entrySet()) {
            String actual = RowScopeSupport.normalize(dr.get(e.getKey()));
            String expected = want.get(e.getValue());
            // 本行作用域值为空 ⇒ 桶里不可能有「属于它」的行（集合谓词里没有 NULL 项）⇒ 直接判不匹配，
            // 交给 B-6 兜底。🚫 不要写成「空则放行」：那会让空客编的行随便认领别人的数据。
            if (expected == null || actual == null || !expected.equals(actual)) return false;
        }
        return true;
    }

    /**
     * 构造兜底行：复制一份 {@code pivot}，把<b>作用域侧的列</b>置空。
     *
     * <p>两处都要置空，缺一不可：
     * <ul>
     *   <li>{@code driverRow} 里同源于作用域节点的<b>全部</b>视图列；</li>
     *   <li>{@code basicDataValues} 里指向这些视图列的条目 —— 实测报价「产品」页签的字段是
     *       {@code default_source.path = "$builder_xxx._客户料号_客户产品编号"} 的
     *       {@code BASIC_DATA}，值在 expand 阶段就已按 pivot 行解析进 {@code basicDataValues}。
     *       只清 {@code driverRow} 不清这里，渲染层照样会显示 pivot 行的客户侧值 ——
     *       而且看起来完全正常，没有任何报错。</li>
     * </ul>
     *
     * <p>🚫 全程<b>另建</b>对象，绝不就地改 pivot（AP-37：pivot 是桶里被同料号多行共享的引用）。
     */
    static ExpandDriverResponse.Row blankScopeSide(CompScope cs, ExpandDriverResponse.Row pivot) {
        ExpandDriverResponse.Row out = new ExpandDriverResponse.Row();
        if (pivot.driverRow != null) {
            Map<String, Object> dr = new LinkedHashMap<>(pivot.driverRow);
            for (String vc : cs.blankViewColumns()) {
                if (dr.containsKey(vc)) dr.put(vc, null);
            }
            out.driverRow = dr;
        }
        if (pivot.basicDataValues != null) {
            Map<String, Object> bd = new LinkedHashMap<>(pivot.basicDataValues);
            for (Map.Entry<String, Object> e : bd.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                for (String vc : cs.blankViewColumns()) {
                    if (bdvKeyTargets(key, vc)) { e.setValue(null); break; }
                }
            }
            out.basicDataValues = bd;
        }
        return out;
    }

    /**
     * {@code basicDataValues} 的某个键是否指向该视图列。
     *
     * <p>🚨 <b>2026-09-11 返修（S3 实测缺陷）</b>：键的真实形态是
     * <b>带花括号</b>的 {@code "{$builder_221dc7668ab6._客户料号_客户产品编号}"}
     * —— 见 {@code ExpandDriverResponse.Row#basicDataValues} 的 javadoc（「key = 字段原始路径
     * <b>(含花括号)</b>」）与 {@code FormulaCalculator#bnfDriverLookupKey}（读取侧统一补花括号）。
     * 上一轮写成 {@code key.endsWith("." + viewColumn)}，键以 {@code '}'} 收尾 ⇒ <b>恒不匹配</b> ⇒
     * {@code driverRow} 清了、{@code basicDataValues} 没清。症状不是报错：
     * {@code FormulaCalculator#resolveRowByFieldName} 解 {@code INPUT_*} 时是
     * {@code editValues → driverRow[字段名] → default_source→basicDataValues → content} 的瀑布，
     * 前两级都空 ⇒ 第三级从<b>未置空的</b> {@code basicDataValues} 里读到 <b>pivot 行（属于别的明细行）</b>
     * 的客编，原样落进 {@code row_data} 并显示在输入框里 —— 用户看到并可能保存<b>别人的</b>客户产品编号。
     *
     * <p>⚠️ 单测当时是绿的，因为夹具把键写成了<b>不带花括号</b>的形态。本方法两种形态都认，
     * 单测夹具也已改成实查形态（{@code RowScopeProjectionTest#BD_PATH}）。
     *
     * <p>匹配口径仍是「按 {@code .<视图列名>} 结尾」，🚫 不用 {@code contains}
     * （避免某列名恰好是另一列名的后缀时误清）。
     */
    static boolean bdvKeyTargets(String key, String viewColumn) {
        if (key == null || viewColumn == null || viewColumn.isEmpty()) return false;
        String k = key.trim();
        if (k.startsWith("{") && k.endsWith("}")) k = k.substring(1, k.length() - 1).trim();
        return k.endsWith("." + viewColumn);
    }

    /** 同 driverPath / debugSql，换一批行。🚫 不改入参对象（AP-37：桶里的 Row 被多行共享）。 */
    static ExpandDriverResponse respond(ExpandDriverResponse src, List<ExpandDriverResponse.Row> rows) {
        ExpandDriverResponse out = new ExpandDriverResponse();
        out.driverPath = src.driverPath;
        out.debugSql = src.debugSql;
        out.rows = new ArrayList<>(rows);
        out.rowCount = out.rows.size();
        return out;
    }
}
