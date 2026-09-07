package com.cpq.builder.compiler;

import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 字段树（task-260819 B-7/B-8，api.md §1.4）—— 配置器左侧字段面板的数据源。
 *
 * <p>2026-08-21 裁决：形状是 {@code {groups:[...]}}（按 Sheet 分组），不是扁平 {@code List<NodeDTO>}
 * （见 api.md §1.4 的裁决说明）。本类替换 {@code SemanticGraphService.getFieldTree} 原来的扁平实现。
 *
 * <p>🔄 <b>2026-09-03（task-260819 v9，B-46）：整类改为按方言取数</b>（AC-116 / AC-110）。
 * 原实现三处硬编码报价侧：页签视图查找不带 dialect 过滤、节点按 {@code key+"|QUOTE"} 反查、
 * 视图列名恒用报价侧别名规则。v9 三套数据集在 {@code semantic_tab_view} 里是
 * {@code (tab_type, variant_key, dialect)} 三行并列，不过滤 ⇒ {@code findFirst()} 命中加载顺序里的
 * 第一行 ⇒ <b>选了「基础核价」却把报价侧的表铺满字段面板，且不报错</b>。
 *
 * <p>🚨 <b>AC-116 的过滤是服务端职责</b>：「选定数据集后，另两套的表<b>一张都不出现</b>（不是置灰，
 * 是不出现）」。落地方式不是在 groups 上再加一道 filter，而是<b>选对那一行页签视图</b>——
 * {@code tab_view_node} 挂的本来就只有该方言的节点，选对了自然只出这一套。响应里另给
 * {@code groups[].dialect} 作为<b>权威标注</b>，供前端兜底核对。
 *
 * <p>N+1 自检：全部基于传入的不可变 {@link SemanticGraphSnapshot} 内存索引遍历，不查库。
 */
@ApplicationScoped
public class FieldTreeBuilder {

    /**
     * 标准 6 个页签类型（展示顺序的权威来源，也是 {@code tabTypesFallback} 时返回的兜底全量）。
     *
     * <p>🚨 <b>这里装的是「存储值」，不是显示名</b>（需求文档 <b>D-39</b>）。
     * 存储值 = {@code "BOM"}（{@code component.tab_type} /
     * {@link com.cpq.component.service.ComponentService#VALID_TAB_TYPES} /
     * {@code semantic_tab_view.tab_type} / {@code builder_config.tabType} 四处统一）；
     * 显示名「BOM 树」只活在前端的 {@code TAB_TYPE_LABEL} 映射、图谱页文案和文档正文里。
     *
     * <p>本常量原写作 {@code "BOM 树"}，是 D-39 被违反的<b>第三次</b>（前两次：前端 {@code TAB_TYPES}
     * 常量、{@code V413} 种子，后者由 {@code V417} 修）。两处后果：
     * <ul>
     *   <li>{@code tabTypesFallback} 分支把「BOM 树」当<b>可选值</b>发给前端 ⇒ 用户选中后
     *       {@code PUT /builder} 被 {@code ComponentService.assertValidTabType} 判 400
     *       {@code Invalid tabType}；</li>
     *   <li>正常路径的展示顺序收窄里「BOM 树」永不匹配库里的 {@code 'BOM'} ⇒ {@code 'BOM'} 落到
     *       下方"图里有但不在标准值里"的兜底追加，被挤到列表末位，顺序静默退化。</li>
     * </ul>
     *
     * <p>🔒 本常量与 {@code ComponentService.VALID_TAB_TYPES} 的<b>同集关系</b>由
     * {@code TabTypeValueDomainSelfCheckTest} 钉死（B-58b）。改这一行必须同时跑它。
     * 启动期的 {@code SemanticGraphKeyValueSelfCheck}（B-56）钉的是<b>库侧</b>
     * （{@code semantic_tab_view.tab_type ⊆ 值域}），钉不到这里。
     */
    static final List<String> ALL_TAB_TYPES =
            List.of("主件", "材质元素", "零件", "外购件", "费用类", "BOM");

    public static final class Field {
        public String sourceNodeKey;
        public String sourceColumn;
        public String displayName;
        public String dataType;
        public List<String> roles;
        public String viewColumn;
        public String lookupLib;
        public boolean isCore;
        /** true = 价格策略元素符号列（左键，B-24/D-65）——前端据此在拖入价格策略列时自动带出本列。 */
        public boolean elemKey;
    }

    public static final class Group {
        public String groupKey;
        public String groupName;
        public String groupKind;
        /**
         * 本分组的**权威**数据集标注（AC-116），值 = {@link CompileDialect} 枚举名。
         *
         * <p>取自**节点自身**的 {@code semantic_node.dialect}，不是把请求参数原样回显——种子灌错时
         * （某个节点挂到了别的方言的页签视图下）前端的兜底过滤才有机会拦住，回显请求参数等于
         * 让这道兜底永远为真。
         *
         * <p>📌 曾经并发过一个同值别名 {@code dataset}（前端早期写法），2026-09-03 主线裁决
         * <b>线上统一用 {@code dialect}</b>，前端已改完并实测（field-tree 请求三次全部发
         * {@code dialect}）⇒ 别名已无消费方，随本次删除。
         */
        public String dialect;
        public List<String> dims;
        public boolean conflict;
        public String conflictReason;
        public List<Field> fields;
    }

    public static final class FieldTreeResponse {
        public String tabType;
        public String variantKey;
        public String anchorDesc;
        /**
         * **本数据集下真实可用**的页签类型（AC-115③）——按方言从 {@code semantic_tab_view} 取
         * distinct {@code tab_type}。前端据此把不在清单里的置灰 + 出空态文案。
         */
        public List<String> availableTabTypes;
        /**
         * true = 上面那份清单是**兜底的全量 6 值**，不是从图里查出来的（该方言在
         * {@code semantic_tab_view} 里一个页签类型都没有，通常是种子没落库/被清空）。
         *
         * <p>🚨 <b>为什么不能直接返空</b>：返空会让前端把 6 个页签类型全部置灰，用户卡在一个
         * "哪个都选不了"的界面上、且看不出是环境问题还是产品坏了。兜底返全量至少能继续操作
         * （真选了不存在的页签，编译期会给 {@code COMPILE_TABVIEW_NOT_FOUND} 的明确报错）。
         *
         * <p>🚨 <b>为什么必须带这个标记</b>：兜底一旦和正常返回长得一模一样，"图是空的"就变成
         * 了静默状态——这正是本任务反复在消灭的失败形态。带上标记，前端/测试才分得出两者。
         */
        public boolean tabTypesFallback;
        public List<Map<String, String>> variants;
        public List<String> switches;
        public List<Group> groups;
    }

    /**
     * @param selectedConfig 当前已选列（builder_config.columns），null/空 = 不计算 conflict（恒 false）
     */
    public FieldTreeResponse build(SemanticGraphSnapshot snap, CompileDialect dialect,
                                    String tabType, String variantKey,
                                    List<BuilderConfig.ColumnConfig> selectedConfig) {
        String vk = variantKey == null ? "" : variantKey;
        String dl = dialect.graphDialect();

        // AC-115③ + 主线兜底要求：先算"本数据集有哪些页签类型"，**必须在 tv 查找之前**。
        // 🔑 顺序很关键：若先查 tv，该方言完全没种子时会先抛 404，兜底分支永远走不到（我第一版
        // 就是这么写的，等于交付一段死代码）。这里改成——一个页签视图都没有时**不抛错**，返回
        // 「6 个全量 + tabTypesFallback=true + 空 groups」，让用户界面还能操作、且一眼看得出
        // 是环境问题；而"该方言有种子、只是没有你要的这个页签类型"仍然照旧 404（两者可区分）。
        Set<String> inGraph = snap.tabViews.stream()
                .filter(t -> dl.equals(t.dialect))
                .map(t -> t.tabType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (inGraph.isEmpty()) {
            FieldTreeResponse empty = new FieldTreeResponse();
            empty.tabType = tabType;
            empty.variantKey = vk;
            empty.anchorDesc = null;
            empty.availableTabTypes = List.copyOf(ALL_TAB_TYPES);
            empty.tabTypesFallback = true;
            empty.variants = List.of();
            empty.switches = List.of();
            empty.groups = List.of();
            return empty;
        }

        SemanticTabView tv = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(tabType) && t.variantKey.equals(vk) && dl.equals(t.dialect))
                .findFirst()
                // B-60/AC-127⑤：同 SemanticCompiler，报文统一走 TabViewNotFound —— 这里是主线实测
                // 「GET /field-tree?tabType=费用类 不传 variantKey → 404 看着像费用类不存在」那一例的现场。
                .orElseThrow(() -> TabViewNotFound.of(snap, 404, tabType, vk, dl));
        SemanticNode anchor = snap.nodeById.get(tv.anchorNodeId);

        FieldTreeResponse resp = new FieldTreeResponse();
        resp.tabType = tabType;
        resp.variantKey = vk;
        resp.anchorDesc = anchor != null ? anchor.displayName : null;
        // AC-115③：按 ALL_TAB_TYPES 的展示顺序收窄（不用图里的偶然顺序）。
        List<String> narrowed = ALL_TAB_TYPES.stream().filter(inGraph::contains).collect(Collectors.toList());
        // 图里出现了但不在标准 6 值里的（种子写了别名/错别字）也要透出，否则用户看不到自己有这个页签
        for (String t : inGraph) if (!narrowed.contains(t)) narrowed.add(t);
        resp.availableTabTypes = narrowed;
        resp.tabTypesFallback = false; // 走到这里 inGraph 必非空（上面已提前返回）
        // variants 同样是页签视图查询 —— 不带 dialect 过滤会把另外两套数据集的费用类变体
        // 一起列进下拉，用户选中后编译期才报 COMPILE_TABVIEW_NOT_FOUND。
        resp.variants = snap.tabViews.stream()
                .filter(t -> t.tabType.equals(tabType) && t.variantLabel != null && dl.equals(t.dialect))
                .map(t -> Map.of("key", t.variantKey, "label", t.variantLabel))
                .collect(Collectors.toList());
        resp.switches = List.of(tv.switches);

        // 已选列所在的 GRAIN 组（用于 conflict 判定，B-8）
        Set<String> selectedGrainNodeKeys = new HashSet<>();
        if (selectedConfig != null) {
            for (BuilderConfig.ColumnConfig col : selectedConfig) {
                SemanticNode n = snap.nodeByKeyDialect.get(col.sourceNodeKey + "|" + dl);
                if (n == null) continue;
                boolean isGrainTarget = snap.edgesFrom(anchor.id).stream()
                        .anyMatch(e -> "GRAIN".equals(e.edgeKind) && e.toNodeId.equals(n.id));
                if (isGrainTarget) selectedGrainNodeKeys.add(n.nodeKey);
            }
        }

        Map<UUID, List<SemanticTabViewColumn>> overrideByColumn = snap.tabViewColumnsByView
                .getOrDefault(tv.id, List.of()).stream().collect(Collectors.groupingBy(c -> c.columnId));

        // 价格策略边（若锚点声明了一条）：key[0].leftColumn 是锚点自己的"元素符号"列（B-24/D-65）。
        SemanticEdge priceEdge = snap.edgesFrom(anchor.id).stream()
                .filter(e -> "PRICE".equals(e.edgeKind)).findFirst().orElse(null);
        String elemKeySourceColumn = null;
        if (priceEdge != null) {
            List<SemanticEdgeKey> priceKeys = snap.keysOf(priceEdge.id).stream()
                    .sorted(Comparator.comparingInt(k -> k.seq)).toList();
            if (!priceKeys.isEmpty()) elemKeySourceColumn = priceKeys.get(0).leftColumn;
        }

        // B-50：NARROW 边的目标是**入参收窄源**，不是可拖的数据源 —— 它不产出任何显示列
        // （编译器侧 resolveColumn 会直接拒绝取它的列），所以字段面板里也不能出现，
        // 否则用户拖得动、却在保存/编译时才被拒，是最难自解释的一类交互。
        // 🔑 这里按**边**判而不是等种子把它从 tab_view_node 里摘掉：桥此前是以 AUX 身份挂上去的，
        //    种子改不改是另一个会话的事，编译器与字段面板必须自己保证口径一致。
        Set<UUID> narrowTargets = snap.edgesFrom(anchor == null ? null : anchor.id).stream()
                .filter(e -> "NARROW".equals(e.edgeKind))
                .map(e -> e.toNodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Group> groups = new ArrayList<>();
        List<SemanticTabViewNode> tvns = snap.tabViewNodesByView.getOrDefault(tv.id, List.of()).stream()
                .filter(x -> !narrowTargets.contains(x.nodeId))
                .toList();
        // B-49：本页签视图上"已经自成一组"的节点集合 —— 供 syntheticLookupFields 排除，见该方法注释。
        Set<UUID> nodesWithOwnGroup = tvns.stream()
                .map(x -> x.nodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (SemanticTabViewNode tvn : tvns) {
            SemanticNode node = snap.nodeById.get(tvn.nodeId);
            if (node == null) continue;
            boolean isMain = "MAIN".equals(tvn.role);

            Group g = new Group();
            g.groupKey = node.nodeKey;
            g.groupName = node.displayName;
            g.dialect = node.dialect;
            g.dims = new ArrayList<>(List.of(tvn.addDims));

            SemanticEdge edgeFromAnchor = isMain ? null : snap.edgesFrom(anchor.id).stream()
                    .filter(e -> e.toNodeId.equals(node.id)).findFirst().orElse(null);
            g.groupKind = isMain ? "MAIN" : (edgeFromAnchor != null ? edgeFromAnchor.edgeKind : "AUX");

            if (!isMain && "GRAIN".equals(g.groupKind)) {
                if (g.dims.isEmpty()) g.dims = new ArrayList<>(List.of(node.grainColumns));
                boolean conflictsWithOther = !selectedGrainNodeKeys.isEmpty()
                        && !selectedGrainNodeKeys.contains(node.nodeKey);
                g.conflict = conflictsWithOther;
                g.conflictReason = conflictsWithOther
                        ? "与已选的『按 " + String.join("/", g.dims) + " 展开』冲突 —— 两者只能选一类。要改用本组，请先移除那一组的列"
                        : null;
            } else {
                g.conflict = false;
                g.conflictReason = null;
            }

            List<Field> fields = new ArrayList<>();
            for (SemanticNodeColumn col : snap.columnsOf(node.id)) {
                Field f = new Field();
                f.sourceNodeKey = node.nodeKey;
                f.sourceColumn = col.dbColumn;
                f.displayName = col.displayName;
                f.dataType = col.dataType;
                f.roles = mergedRoles(col, overrideByColumn.get(col.id));
                // AC-110：别名规则按侧不统一且必须保持不统一 —— QUOTE 走 _<短名>_<显示名>，
                // 两个 COST_* 走裸英文 dbColumn。前端只读展示、不得自行拼接（AC-11），
                // 所以这里发错等于让配置人员照着一个不存在的视图列名去绑字段。
                f.viewColumn = AliasGenerator.viewColumn(dialect, node.shortName, col.displayName, col.dbColumn);
                f.lookupLib = null;
                f.isCore = false;
                f.elemKey = isMain && elemKeySourceColumn != null && elemKeySourceColumn.equals(col.dbColumn);
                fields.add(f);
            }

            if (isMain) {
                fields.addAll(syntheticLookupFields(snap, dialect, anchor, nodesWithOwnGroup));
            }
            g.fields = fields;
            groups.add(g);
        }

        // 价格策略原子组（B-24/D-65）：单独成组返回，groupKind='PRICE' —— 前端靠它渲染成带框块
        // 并在拖入时自动带出上面标了 elemKey 的元素符号列。元素键列本身仍留在 MAIN 组（不移动）。
        if (priceEdge != null) {
            SemanticNode funcNode = snap.nodeById.get(priceEdge.toNodeId);
            if (funcNode != null) {
                Group priceGroup = new Group();
                priceGroup.groupKey = funcNode.nodeKey;
                priceGroup.groupName = "价格策略";
                priceGroup.groupKind = "PRICE";
                priceGroup.dialect = funcNode.dialect;
                priceGroup.dims = new ArrayList<>();
                priceGroup.conflict = false;
                priceGroup.conflictReason = null;
                List<Field> priceFields = new ArrayList<>();
                for (SemanticNodeColumn col : snap.columnsOf(funcNode.id)) {
                    Field f = new Field();
                    f.sourceNodeKey = funcNode.nodeKey;
                    f.sourceColumn = col.dbColumn;
                    f.displayName = col.displayName;
                    f.dataType = col.dataType;
                    f.roles = List.of(col.roles);
                    f.viewColumn = AliasGenerator.bareColumn(col.displayName);
                    f.lookupLib = "价格策略";
                    f.isCore = "unit_price".equals(col.dbColumn);
                    f.elemKey = false;
                    priceFields.add(f);
                }
                priceGroup.fields = priceFields;
                groups.add(priceGroup);
            }
        }
        resp.groups = groups;
        return resp;
    }

    private List<String> mergedRoles(SemanticNodeColumn col, List<SemanticTabViewColumn> overrides) {
        if (overrides != null && !overrides.isEmpty()) return List.of(overrides.get(0).roles);
        return List.of(col.roles);
    }

    /**
     * 经 LOOKUP 边到达的"虚拟字段"（查名结果），挂在锚点自己的组下展示。
     * 🔴 B-24/D-65：PRICE 边不再在这里合并进 MAIN 组——已改为在 {@link #build} 里单独成
     * {@code groupKind='PRICE'} 的组返回，避免与新逻辑重复输出「元素单价/货币」两份。
     *
     * <p>🔄 <b>2026-09-04（B-49）：跳过"已在本页签视图上自成一组"的 LOOKUP 目标。</b>
     *
     * <p><b>为什么会重复</b>（V6 设计与 B-43 的碰撞，不是谁写错了）：本方法的原意是
     * 「LOOKUP 节点 = <b>查名维表</b>（{@code material_master}/{@code material_recipe} 那种），
     * 用户不会想把整张维表当一个组来拖，所以把它的名称列<b>内联</b>进 MAIN 组」。V6 时代
     * LOOKUP 只有这一种用法，设计成立。
     *
     * <p>但 B-43 的<b>跨数据集料号桥</b>（{@code QUOTE_MATERIAL_BRIDGE}）也<b>必须</b>声明成
     * LOOKUP —— {@code SemanticCompiler#ensureLeftJoin} 里只有 LOOKUP 才编译成 LEFT JOIN
     * （{@code GRAIN} 会展开行、{@code SUB} 是相关标量子查询，都不是桥要的语义）。而桥
     * <b>同时又作为 AUX 挂在页签视图上</b>（它是一整张有意义的表，用户要能整组拖）⇒
     * 它的列<b>既被内联进 MAIN、又自成一组</b>，同一个字段在面板上出现两次。
     * 实测 {@code COST_BASIC} 主件：MAIN 组 7 个自有列 + 桥的 5 个非 code 列 = 12 个。
     *
     * <p><b>判据落在「这个节点在本页签里已经可见了吗」</b>：已经自成一组的，就不再内联
     * （谁自成一组谁负责展示自己的列）；纯查名维表不会挂进 {@code tab_view_node}，
     * 不受影响、继续内联。<b>不靠去重收场</b> —— 去重只是把症状盖掉，"桥该不该内联"这个问题
     * 还在，换个页签形态又会以别的形式冒出来。
     */
    private List<Field> syntheticLookupFields(SemanticGraphSnapshot snap, CompileDialect dialect,
                                              SemanticNode anchor, Set<UUID> nodesWithOwnGroup) {
        List<Field> out = new ArrayList<>();
        Set<String> handledGroups = new HashSet<>();
        for (SemanticEdge e : snap.edgesFrom(anchor.id)) {
            SemanticNode target = snap.nodeById.get(e.toNodeId);
            if (target == null) continue;
            if (nodesWithOwnGroup.contains(target.id)) continue; // B-49：已自成一组，不再内联
            if ("LOOKUP".equals(e.edgeKind)) {
                if (e.coalesceGroup != null) {
                    if (!handledGroups.add(e.coalesceGroup)) continue; // 同组只出现一次（用 fallback=0 的列代表）
                    SemanticEdge lead = snap.edgesFrom(anchor.id).stream()
                            .filter(x -> e.coalesceGroup.equals(x.coalesceGroup))
                            .min(Comparator.comparingInt(x -> x.fallbackOrder == null ? 0 : x.fallbackOrder))
                            .orElse(e);
                    SemanticNode leadNode = snap.nodeById.get(lead.toNodeId);
                    // 多源 COALESCE：代表节点可能与触发本次循环的 target 不是同一个，单独再判一次
                    if (leadNode == null || nodesWithOwnGroup.contains(leadNode.id)) continue;
                    for (SemanticNodeColumn col : snap.columnsOf(leadNode.id)) {
                        if (!col.isCode) out.add(lookupField(dialect, leadNode, col));
                    }
                } else {
                    for (SemanticNodeColumn col : snap.columnsOf(target.id)) {
                        if (!col.isCode) out.add(lookupField(dialect, target, col));
                    }
                }
            }
        }
        return out;
    }

    private Field lookupField(CompileDialect dialect, SemanticNode lookupNode, SemanticNodeColumn col) {
        Field f = new Field();
        f.sourceNodeKey = lookupNode.nodeKey;
        f.sourceColumn = col.dbColumn;
        f.displayName = col.displayName;
        f.dataType = col.dataType;
        f.roles = List.of(col.roles);
        f.viewColumn = AliasGenerator.viewColumn(dialect, lookupNode.shortName, col.displayName, col.dbColumn);
        f.lookupLib = lookupNode.displayName;
        f.isCore = false;
        return f;
    }
}
