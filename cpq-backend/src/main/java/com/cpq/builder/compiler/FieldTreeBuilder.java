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

    /**
     * task-260904 S-4 / B-2（AC-1 / AC-3 / AC-20）—— <b>新建组件时不再提供</b>的页签类型。
     *
     * <p>「零件」「外购件」两类页签在 v9 下本就是坏的（需求文档 §①ter 实测）：外购件编译产物带
     * {@code characteristic='OUTSOURCED'} 而 {@code ds_quote_material_bom} 无该列 ⇒ 400；零件的
     * 编译产物与 BOM 树<b>逐字节相同</b> ⇒ 200 但静默返回全部 BOM 行。更根本的一条：本任务已把
     * 加叶子的类型判定改读主数据（{@code ds_quote_material.material_type}），「料号是零件还是
     * 外购件」是<b>料号自身的属性</b>，不是页签的属性 ⇒ 这两类页签存在的理由已经消失。
     *
     * <p>🚫 <b>这不是「值域收缩」，是「可选项收缩」</b>，两者必须分开：
     * <ul>
     *   <li>{@link #ALL_TAB_TYPES} / {@code ComponentService.VALID_TAB_TYPES} 是<b>存储值</b>的
     *       权威清单，两侧集合相等由 {@code TabTypeValueDomainSelfCheckTest} 钉死 ——
     *       🚫 <b>不许从那两个常量里删这两个值</b>（存量 30 个零件/外购件组件的
     *       {@code component.tab_type} 就是这两个值，删了 {@code assertValidTabType} 会把它们判
     *       400、启动期 {@code SemanticGraphKeyValueSelfCheck} 会直接让服务起不来）；</li>
     *   <li>本常量只作用于 {@link #build} 的<b>输出侧</b>，即「新建时给用户看的可选项」。</li>
     * </ul>
     *
     * <p>🚫 <b>{@code semantic_tab_view} 那 6 行数据一行都不动、全部保持 ACTIVE</b>（S-4）：
     * 30 个存量零件/外购件组件打开配置页时靠 {@code field-tree} 按
     * {@code (tabType, variantKey, dialect)} 精确查询这 6 行，停用即 404（违反 AC-25①）。
     * ⇒ 过滤只发生在<b>列可选项</b>时，<b>按坐标查</b>时照常命中。
     */
    static final Set<String> RETIRED_TAB_TYPES = Set.of("零件", "外购件");

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

    /**
     * task-260904 B-1（api.md §1.2）—— 「数据源」下拉的一项。取代 {@code availableTabTypes}
     * 成为前端下拉的数据源：用户选具体数据源（「物料BOM」「自制加工费」…），
     * 页签语义由后端按锚点推导后放进 {@link #semantic} 只读回显，用户不再选抽象的页签类型。
     *
     * <p>{@link #tabType} / {@link #variantKey} / {@link #dialect} 是<b>内部坐标</b>（api.md §0）：
     * 前端原样回传给 {@code field-tree} / {@code compile}，不展示给用户。
     * 🚨 <b>三段缺一不可</b>：{@code semantic_tab_view} 唯一约束是
     * {@code UNIQUE (tab_type, variant_key, dialect)}，只回传前两段会命中 3 行（三方言各一）。
     */
    public static final class Source {
        /** 锚点节点的 {@code node_key}（如 {@code MATERIAL_BOM}）—— 前端的稳定标识，不用 label 判事。 */
        public String sourceKey;
        /** 锚点节点的 {@code display_name}（如「物料BOM」）—— 下拉里展示给用户的那一行字。 */
        public String label;
        public String tabType;
        public String variantKey;
        public String dialect;
        /**
         * {@code "TREE"} / {@code "MATERIAL_ELEMENT"} / {@code null}（普通平铺数据源）。
         * 🚫 前端不得按 label 或 sourceKey 硬编码判语义，一律读本字段（api.md §1.2）。
         * 取值由 {@code TabSemanticResolver.semanticOfGraphTabType} 唯一给出 —— 与后端判树
         * （{@code TabSemanticResolver.isTreeTab}）同一份映射，避免「面板说是树、渲染判不是树」。
         */
        public String semantic;
    }

    public static final class FieldTreeResponse {
        public String tabType;
        public String variantKey;
        public String anchorDesc;
        /**
         * task-260904 B-1/B-2（AC-1 / AC-3 / AC-20，api.md §1.2）：<b>本方言下可新建的数据源清单</b>
         * —— {@code semantic_tab_view} 里 {@code status='ACTIVE'} 且方言匹配的行，
         * 逐行取其锚点节点的 {@code node_key} / {@code display_name}，并已剔除
         * {@link #RETIRED_TAB_TYPES}。实测行数：QUOTE 11 · COST_BASIC 10 · COST_DETAIL 18。
         */
        public List<Source> availableSources;
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
            // ⚠️ task-260904 B-2：**本分支的 6 值不做退役过滤**，两个理由（不是遗漏）：
            //   ① 本分支的语义是「该方言在图里一个页签视图都没有」= 环境坏了，groups 也是空的，
            //      用户选中任何一项都配不出东西；而 availableSources 在这里必然为空（下一行），
            //      前端 F-1 改读 availableSources 后，退役页签在本分支<b>根本不可达</b>；
            //   ② `FieldTreeAndDialectParseSelfCheckTest#emptyDialectFallsBackToAllSixWithFlag`
            //      （task-260819 的护栏）明确断言本分支返回**全量 6 值** —— 那条断言钉的是
            //      「兜底 = 全量存储值域」这个契约，与「新建可选项」是两件事。
            //   ⇒ 退役过滤只作用于下面正常路径的 narrowed 与 availableSources。
            empty.availableSources = List.of();
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
        // 🆕 task-260904 B-2（S-4②）：**在输出侧**剔除退役页签。为什么必须也过滤这一份而不是
        //    只过滤 availableSources —— 前端 SqlViewBuilderTab 的页签类型下拉当前直接消费
        //    availableTabTypes，只过滤新字段的话用户照样选得到「零件」「外购件」。
        //    🚫 过滤点在这里、不在 ALL_TAB_TYPES 常量上：那个常量是**存储值权威清单**，
        //       与 ComponentService.VALID_TAB_TYPES 的集合相等关系由 TabTypeValueDomainSelfCheckTest
        //       钉死，动它会连带把存量 30 个零件/外购件组件判成非法值域。
        List<String> narrowed = ALL_TAB_TYPES.stream()
                .filter(inGraph::contains)
                .filter(t -> !RETIRED_TAB_TYPES.contains(t))
                .collect(Collectors.toList());
        // 图里出现了但不在标准 6 值里的（种子写了别名/错别字）也要透出，否则用户看不到自己有这个页签
        for (String t : inGraph) if (!narrowed.contains(t) && !RETIRED_TAB_TYPES.contains(t)) narrowed.add(t);
        resp.availableTabTypes = narrowed;
        resp.availableSources = buildAvailableSources(snap, dl);
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

        // ── task-260904 B-23（2026-09-06 用户裁决转入本任务）：PRICE 边目标不走通用循环 ──
        // 症状（共享 dev 库实测，master 与本分支逐字相同 ⇒ 非本任务引入）：
        //     dialect=QUOTE tabType=材质元素 → groups 出 **3** 组，
        //     FUNC_ELEMENT_PRICE 的 PRICE 组出现两次（api.md 期望 2 组）。
        // 根因：FUNC_ELEMENT_PRICE 有**两个**出组通道，两个都成立且互不知情 ——
        //   ① 它以 AUX 挂在 QUOTE/材质元素 的 tab_view_node 上（V413 种子，**有意为之**：
        //      价格策略要作为附属源出现在字段面板里；Sec34PriceStrategyTest 5 条用例靠它存在）
        //      ⇒ 下面这个通用 tvns 循环给它出一组，groupKind 由锚点出边算得也是 "PRICE"；
        //   ② 它是锚点 PRICE 边的目标 ⇒ 本方法末尾的**专用块**再给它出一组。
        // 只有 QUOTE 复现：COST_BASIC / COST_DETAIL 的材质元素没挂这个 AUX。
        //
        // 🚫 **不改种子**（那条 AUX 挂载是有意的，且 V413 已应用到共享库，动它撞契约红线），
        //    改的是这里的组装逻辑：**谁有专用块，谁就不走通用循环**——这正是 B-49
        //    nodesWithOwnGroup 那条判据的反向用法（那边是「已自成一组就不再内联」，
        //    这里是「专用块会出组就不再通用出组」，同一条不变量的两个方向）。
        //
        // 为什么保留专用块那一份而不是反过来：专用块的 viewColumn 走
        // AliasGenerator.bareColumn，与 SemanticCompiler 生成价格列别名的那一处**同源**
        // （实测编译产物 `cep.unit_price AS "元素单价"`）；通用循环那一份走的是
        // AliasGenerator.viewColumn（得到 `_价格策略_元素单价`）且 isCore 恒 false ——
        // 前端的 killsGroup / priceCol 两处原子组判定都认 isCore，认不到就整组语义失效。
        UUID priceGroupNodeId = (priceEdge != null && snap.nodeById.get(priceEdge.toNodeId) != null)
                ? priceEdge.toNodeId : null;

        List<Group> groups = new ArrayList<>();
        List<SemanticTabViewNode> tvns = snap.tabViewNodesByView.getOrDefault(tv.id, List.of()).stream()
                .filter(x -> !narrowTargets.contains(x.nodeId))
                .filter(x -> priceGroupNodeId == null || !priceGroupNodeId.equals(x.nodeId))   // B-23
                .toList();
        // B-49：本页签视图上"已经自成一组"的节点集合 —— 供 syntheticLookupFields 排除，见该方法注释。
        Set<UUID> nodesWithOwnGroup = tvns.stream()
                .map(x -> x.nodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // B-23：价格策略节点虽被上面过滤出了 tvns，但它**确实有自己的组**（末尾专用块）——
        // 这个集合的语义是「本响应里已经自成一组的节点」，故必须补回，否则
        // syntheticLookupFields 会认为它没组、把它的列内联进 MAIN，等于换个形式再重复一次。
        if (priceGroupNodeId != null) nodesWithOwnGroup.add(priceGroupNodeId);
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
            // 🆕 task-260907 B-1（api.md §1.3）：**经 LOOKUP 边到达、又自成一组**的节点，
            //    groupKind 报 "AUX" 而不是 "LOOKUP"。
            //    · 为什么会出现这种节点：客户料号必须走 LOOKUP —— SemanticCompiler 里只有 LOOKUP
            //      编译成 LEFT JOIN（JOIN 是 INNER，会把 28 个没有客户料号的物料整行丢掉，AC-2②）；
            //      同时它又是一整张有意义的表，要以 AUX 挂在页签上让用户整组拖。
            //    · 为什么不直接报 "LOOKUP"：api.md §1.3 把这一组的 groupKind 定义为 AUX；
            //      而 groupKind 在前端只用于 PRICE / SUB / GRAIN·JOIN·SAME 三处徽标分支，
            //      "LOOKUP" 与 "AUX" 的渲染完全相同 ⇒ 报 AUX 零视觉差异、契约却对得上。
            //    · 零回归依据（2026-09-07 实测共享库）：本分支此前**不可达** —— 挂进
            //      semantic_tab_view_node 的非 SHEET 节点只有 FUNC_ELEMENT_PRICE（PRICE 边，
            //      走末尾专用块且已被 B-23 从 tvns 里过滤掉），没有任何节点经 LOOKUP 边自成一组。
            String edgeKind = edgeFromAnchor == null ? null : edgeFromAnchor.edgeKind;
            g.groupKind = isMain ? "MAIN"
                    : (edgeKind == null || "LOOKUP".equals(edgeKind) ? "AUX" : edgeKind);

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

    /**
     * task-260904 B-1/B-2（api.md §1.2 / §1.3）：组装本方言下的「数据源」清单。
     *
     * <p>来源 = {@code semantic_tab_view} 中 {@code status='ACTIVE'} 且方言匹配的行，
     * 逐行取其<b>锚点节点</b>的 {@code node_key}（sourceKey）与 {@code display_name}（label）。
     * 一行页签视图 = 一个数据源入口：45 行里 {@code MATERIAL_BOM} 锚点占 9 行
     * （3 方言 × BOM/零件/外购件），其余 36 行锚点互不相同 ⇒ 剔除退役的 6 行后，
     * 每个方言内 sourceKey 恰好唯一（QUOTE 11 · COST_BASIC 10 · COST_DETAIL 18，2026-09-06 实测）。
     *
     * <p><b>B-2 的过滤就是这里的一行 filter</b> —— 🚫 不改 {@code semantic_tab_view} 一行数据：
     * 30 个存量零件/外购件组件靠 {@code build()} 上面那句<b>按坐标精确查</b>（{@code tv} 查找）
     * 打开配置页，那条路径不经过本方法，因此仍能命中、不会 404（AC-25①）。
     *
     * <p>排序：先按 {@link #ALL_TAB_TYPES} 的展示顺序（与页签类型下拉的既有顺序一致），
     * 同一页签类型内按 {@code variantKey} 字典序（费用类的多个变体），保证响应稳定可比对。
     *
     * <p>N+1：纯内存遍历不可变快照，零查库。
     */
    private List<Source> buildAvailableSources(SemanticGraphSnapshot snap, String graphDialect) {
        List<Source> out = new ArrayList<>();
        for (SemanticTabView tv : snap.tabViews) {
            if (!graphDialect.equals(tv.dialect)) continue;
            if (!"ACTIVE".equals(tv.status)) continue;
            if (RETIRED_TAB_TYPES.contains(tv.tabType)) continue;   // B-2：退役页签不进可选项
            SemanticNode anchorNode = snap.nodeById.get(tv.anchorNodeId);
            if (anchorNode == null) continue;   // 种子残缺：锚点丢了就没有可展示的数据源，跳过而非发半条
            Source src = new Source();
            src.sourceKey = anchorNode.nodeKey;
            src.label = anchorNode.displayName;
            src.tabType = tv.tabType;
            src.variantKey = tv.variantKey == null ? "" : tv.variantKey;
            src.dialect = tv.dialect;
            // 与后端判树共用同一份映射（🚫 不在这里重写一份 tab_type→semantic 的 if/else）。
            // PLAIN 在 Java 侧是空串、在 JSON 契约里是 null（api.md §1.2），此处做唯一一次转换。
            String sem = com.cpq.component.service.TabSemanticResolver.semanticOfGraphTabType(tv.tabType);
            src.semantic = com.cpq.component.service.TabSemanticResolver.SEMANTIC_PLAIN.equals(sem) ? null : sem;
            out.add(src);
        }
        out.sort(Comparator
                .<Source>comparingInt(x -> {
                    int i = ALL_TAB_TYPES.indexOf(x.tabType);
                    return i < 0 ? ALL_TAB_TYPES.size() : i;   // 种子里的非标值排最后，不丢
                })
                .thenComparing(x -> x.variantKey == null ? "" : x.variantKey)
                .thenComparing(x -> x.sourceKey == null ? "" : x.sourceKey));
        return out;
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
