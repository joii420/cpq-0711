package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.datasource.sqlview.QuotePendingRewriter;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.GraphPathResolver;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取数配置器编译器核心（task-260819 B-5/B-6/B-9/B-10，整个取数配置器的心脏）。
 *
 * <p>输入 {@link BuilderConfig}（拖拽出的已选列 + 页签类型 + 开关），输出一段逐字确定的
 * SQL 文本——同一份 {@code builder_config} 任何时候编译结果都相同（AC-49①「实时面板」与
 * 保存落库 {@code sql_template} 必须逐字一致的前提）。
 *
 * <p>🔄 2026-08-24（D-50/D-56，AC-3 整条改写）：原「三条闭包铁律」（各页签自建
 * {@code WITH RECURSIVE bom_closure}）已整体作废——{@link #closureCte} 停用、不再被任何调用路径
 * 引用（保留方法体，删除与否由主线收尾裁决）。子件收窄统一为「主树供数组」：锚点料号列上生成
 * {@code = ANY(:total_material_no)}（无任何用户开关，AC-60），{@code hf_part_no} 恒保持锚点自身列
 * 不再按闭包改写——「子件行归属哪个成品」这层职责整体移交 Java 侧（B-19/B-21，
 * {@code BomTreeRenderService#collectTotalMaterialNoUnion} 顺带产出「后代→根」映射 +
 * {@code ConfigureSnapshotService} 的 {@code expandMulti} 回分，见 AC-62）。版本化表
 * （{@code unit_price} 等）仍然禁止 {@code LEFT JOIN} 直连——图声明已经把这类关系登记成
 * {@code SUB}（相关标量子查询）而不是 {@code LOOKUP}，编译器只需老实按 {@code edge_kind} 分支，
 * 不需要另行猜哪张表"危险"。
 *
 * <p>🔄 <b>2026-09-03（task-260819 v9，B-40/B-41）：三数据集范围替换</b>。{@link CompileDialect}
 * 由两值扩到三值，编译器凡是"按侧"分叉的地方一律改读 {@code c.dialect}，不再有任何硬编码的
 * {@code "QUOTE"} 字面量：
 * <ul>
 *   <li><b>页签视图 / 节点查找按方言过滤</b>（{@link #resolveTabView} / {@link #resolveColumn}）——
 *       三套数据集在 {@code semantic_tab_view} 里是 {@code (tab_type, variant_key, dialect)} 三行并列，
 *       不带 dialect 过滤会随机取到别的数据集那一行（唯一键第三段就是 dialect，漏过滤 = 静默取错表）。</li>
 *   <li><b>收窄退化为「只做轴收窄 + 版本谓词」</b>（{@link #applyFullScope}）——{@code ds_*} 45 张表
 *       <b>没有 {@code system_type} / {@code customer_no} 列</b>（唯一有 customer_no 的
 *       {@code ds_quote_customer_part} 按 N-19 不进图），旧 V6 的三件套收窄整块删除，不保留（B-41④）。</li>
 *   <li><b>版本谓词改由全版本视图承载</b>（S-31/D-84）——核价两套的节点 {@code physical_table} 指向
 *       {@code v_<主表>_all}（{@code 主表 UNION ALL <主表>_history}，多一列常量 {@code is_current}），
 *       编译器对其发 {@code :versionFilter(alias.is_current, alias.version_no::text, alias.<轴列>)}。
 *       🚨 {@code ::text} <b>不是可选的</b>：{@code VersionFilterMacro.render()} 把版本列与
 *       {@code :__vfVer::text[]} 展开出来的 {@code k.v} 比较，而 {@code ds_*.version_no} 是
 *       {@code integer} ⇒ 不转换直接 {@code operator does not exist: integer = text}（D-85）。</li>
 * </ul>
 *
 * <p>N+1 自检：单次 compile() 调用只有一条 {@link PhysicalColumnCatalog#columnsOf} SQL
 * （一次性查完本次涉及的全部物理表列名），其余全是内存图遍历（{@link SemanticGraphSnapshot}
 * 已是不可变内存快照）——SQL 条数与已选列数/图节点数无关，恒为 1。
 */
@ApplicationScoped
public class SemanticCompiler {

    public static final int CURRENT_VERSION = 1;

    private static final String PRICE_FUNC_ALIAS = "cep";
    // 🚫 task-260907 B-17 / B-8 合并：这里原先是 PRICE_FUNC_NODE_KEY = "FUNC_ELEMENT_PRICE" 常量。
    //    它把「价格函数」这个**角色**钉死成**某一个具体节点**，换价格函数就必须同时改代码与种子，
    //    而漏改的症状是「拖得动、编译时才报列找不到」或「元素单价整列空且不报错」。
    //    ⇒ 改为**顺锚点的 PRICE 边**解析，见 resolvePricePlan。角色由图数据表达，不由常量表达。
    //
    // 📌 2026-09-07 合并纪要（两条线独立做了同一个泛化，取 B-17 的结构）：
    //    · B-8 侧曾引入 LEGACY_PRICE_FUNC_NODE_KEY 常量做向后兼容，理由是「价格节点已换成
    //      FUNC_CUSTOMER_ELEMENT_PRICE，21 份存量配置仍写旧键会编译不过」。
    //    · 🚨 **该前提已不成立**：D-39 撤回了换节点方案，V424 把 FUNC_CUSTOMER_ELEMENT_PRICE
    //      连同它的 PRICE 边一并删除。全库现在只有 FUNC_ELEMENT_PRICE 一个价格函数节点，
    //      边就指向它 ⇒ 精确匹配天然覆盖那 21 份，兼容常量成了**永远为真的冗余判据**。
    //    · 实测依据：21 份存量配置在「有 LEGACY / 无 LEGACY」两种编译器下产物**逐字节相同**，
    //      且 21/21 全部编译成功（见本次合并回报）。⇒ 不保留该常量。
    //    · 🚫 不要再把它加回来：它会让「选了价格函数的列、锚点却没有对应 PRICE 边」这个
    //      本该报错的情形被判成价格列，从而绕过 resolvePricePlan 里的 COMPILE_PRICE_EDGE_NOT_FOUND。

    @Inject
    PhysicalColumnCatalog catalog;

    // ---------------- 编译期上下文（每次 compile() 调用重置，非线程共享状态） ----------------

    private static final class Ctx {
        SemanticGraphSnapshot snap;
        CompileDialect dialect;
        BuilderConfig cfg;
        SemanticTabView tabView;
        SemanticNode anchor;
        String anchorAlias;
        Map<String, Set<String>> columnCatalog;
        Map<String, Integer> aliasSeq = new HashMap<>();
        Map<UUID, String> aliasByNode = new LinkedHashMap<>(); // node.id -> allocated alias (JOIN/GRAIN/PRICE targets)
        LinkedHashSet<String> joinClauses = new LinkedHashSet<>();
        List<String> anchorWhere = new ArrayList<>();
        LinkedHashSet<String> requiredVars = new LinkedHashSet<>();
        LinkedHashSet<String> grainDims = new LinkedHashSet<>();
        List<String> discriminatorValues = new ArrayList<>(); // 费用类多变体合并用（AC-8②）
        String discriminatorColumn; // 上面那组值所在的列名（不带别名）
        List<String> warnings = new ArrayList<>();
        /** 本次已产出的输出列名（B-47 去重用，含 hf_part_no / view_version 等约定列）。 */
        LinkedHashSet<String> usedAliases = new LinkedHashSet<>();
        /** B-50：锚点上存在 NARROW 边 ⇒ 轴收窄职责已移交半连接，applyFullScope 不再直接发轴谓词。 */
        boolean narrowedByBridge = false;

        /**
         * task-260907 B-3（F-3，AC-4）：树页签的<b>子件列</b>物理列名，非树页签恒 {@code null}。
         *
         * <p>非空时 {@link #applyFullScope} 对<b>锚点自身</b>发的 {@code = ANY(:total_material_no)}
         * 改落在这一列上（父件列 → 子件列）。🚫 只作用于锚点：GRAIN/SUB 目标是别的物理表，
         * 它们靠自己的连接键与锚点相关联，轴列语义不变。
         */
        String treeChildColumn;
        /** 树页签根分支的数据源节点（同方言「主件」页签的锚点，如 QUOTE → {@code ds_quote_material}）。 */
        SemanticNode treeRootNode;
        /** 锚点上生效的那条 NARROW 料号桥边（核价两套有、报价侧为 null）——树根分支要经同一座桥。 */
        SemanticEdge narrowEdge;
        /**
         * 树页签根分支自己的 WHERE 谓词原文。
         *
         * <p>🚨 单独存一份是给 {@link #assertAxisParamSingleSemantic} 记账用的：护栏按
         * 「结构化认出的桥 vs 产物里数出的桥」对账，根分支的桥不在 {@link #anchorWhere} 里，
         * 不登记就会被判成 {@code COMPILE_AXIS_NARROW_UNCLASSIFIABLE}（护栏在正确实现上误报）。
         * <b>登记 ≠ 放宽</b>：它仍然要出现在产物里、条数仍然要对得上，只是从「未知形态」变成「已知形态」。
         */
        List<String> treeRootWhere = new ArrayList<>();
    }

    // ---------------- task-260907 B-3：树页签边式契约的三个约定列 / 坐标 ----------------

    /**
     * 树契约的两个约定列名（与存量 {@code $bom_view} / {@code $wl_bom_view} 逐字同名）：
     * {@code material_no} = <b>子件</b>、{@code parent_no} = <b>父件</b>。
     *
     * <p>{@code BomTreeRenderService} 按 {@code (parent_no, material_no)} 边键分桶
     * （见该类 {@code edgeKey} 与 {@code assertParentNoPresent}）——两个名字都是**渲染主链路的硬契约**，
     * 🚫 不许改名，也不许只出其中一个（只出 material_no 时渲染层会 400「未输出 parent_no 列」）。
     */
    private static final String TREE_COL_MATERIAL_NO = "material_no";
    private static final String TREE_COL_PARENT_NO = "parent_no";

    /**
     * 树页签根分支的数据源坐标：同方言「主件」页签的锚点节点。
     *
     * <p>🚫 这里出现字面量「主件」是<b>刻意的且唯一的</b>一处：根分支要的是「成品自身那张主档表」，
     * 图里没有别的属性能表达它（{@code node_key} 三方言都叫 {@code MATERIAL} 但那是 key 巧合，
     * 不是契约）。用页签坐标反查 ⇒ 表名仍然从图里来（QUOTE → {@code ds_quote_material}），
     * 换表只改种子不改代码。该值属 {@code ComponentService.VALID_TAB_TYPES} 的存储值域（D-39）。
     */
    private static final String ROOT_SOURCE_TAB_TYPE = "主件";

    /**
     * 客户维度列名（repair-260908 B-1 / B-1b）。
     *
     * <p>唯一判据：{@code c.columnCatalog} 里这张物理表**有没有这一列**
     * （{@link PhysicalColumnCatalog} 真查 {@code information_schema.columns}，不是猜表名）。
     * 🚫 不要改成按方言、按视图名前缀或按 SQL 文本正则判 —— 那三种写法都能"碰巧对"，
     * 而碰巧对比明确错更危险（并发线 2026-09-08 实证，见任务目录 ./证据/材料-并发线交接-260908.md）。
     */
    private static final String CUSTOMER_SCOPE_COLUMN = "customer_no";

    public CompileResult compile(SemanticGraphSnapshot snap, BuilderConfig cfg, CompileDialect dialect) {
        return compile(snap, cfg, dialect, false);
    }

    /**
     * @param skipNarrowPredicates {@code true} = <b>不发 NARROW 半连接</b>（task-260819 B-52）。
     *
     * <p>唯一使用者是 {@code /preview} 的「<b>未指定料号</b>」场景：桥的作用是把**给定的**销售料号
     * 翻译成生产料号；一个料号都没给时没有什么可翻译，此时经桥反而会把结果收窄成"恰好有桥映射的
     * 那几个料号"——实测 {@code ds_cost_basic_material} 12 行里只有 6 行有桥，而 AC-117 的基准是
     * <b>整表</b>。跳过后 {@link #applyFullScope} 恢复直接轴收窄
     * （{@code <轴列> = ANY(:total_material_no)}），预览侧注入锚点表自己的轴值 ⇒ 整表样例。
     *
     * <p>🚫 <b>保存/编译/体检一律传 false</b>：落库的 {@code sql_template} 必须带桥，
     * 否则渲染期就不收窄了。这是**预览专用的放宽**，不是编译器的常规能力。
     */
    public CompileResult compile(SemanticGraphSnapshot snap, BuilderConfig cfg, CompileDialect dialect,
                                 boolean skipNarrowPredicates) {
        Ctx c = new Ctx();
        c.snap = snap;
        c.dialect = dialect;
        c.cfg = cfg;

        c.tabView = resolveTabView(snap, cfg, dialect);
        c.anchor = snap.nodeById.get(c.tabView.anchorNodeId);
        if (c.anchor == null) {
            throw new BuilderApiException(400, "COMPILE_ANCHOR_MISSING", "页签视图的锚点节点不存在", Map.of());
        }
        // D-50/D-56（AC-3/AC-60）：闭包开关已整体取消，"子件数据带出与否"不再由任何用户开关
        // 或 tabView.switches 决定——统一改为在锚点料号列上生成 = ANY(:total_material_no) 收窄
        // （见下方 anchorWhere 追加处），SQL 侧不再区分"闭包/非闭包"两态。

        // task-260907 B-3（F-3）：本页签是不是「BOM 树」——判据只有一条，走 TabSemanticResolver
        // 的唯一映射（🚫 不在本文件里再写一份 tab_type→semantic 的 if/else，那会是第四份）。
        boolean treeSemantic = com.cpq.component.service.TabSemanticResolver.SEMANTIC_TREE.equals(
                com.cpq.component.service.TabSemanticResolver.semanticOfGraphTabType(c.tabView.tabType));
        // 根分支的数据源节点必须**在建列目录之前**解析出来，否则它的物理表进不了下面那一条
        // columnsOf() 查询，就得为它另发一条 SQL（N+1 的起点）。解析不到先记 null，
        // 真要用它的时候（applyTreeContract）再报错。
        if (treeSemantic) {
            c.treeRootNode = resolveTreeRootNode(c);
        }

        // 收集本次涉及的全部物理表（anchor + 直接边目标 + 价格函数节点忽略，函数无物理表）
        Set<String> tables = new LinkedHashSet<>();
        tables.add(c.anchor.physicalTable);
        for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
            SemanticNode to = snap.nodeById.get(e.toNodeId);
            if (to != null && to.physicalTable != null) tables.add(to.physicalTable);
        }
        if (c.treeRootNode != null && c.treeRootNode.physicalTable != null) {
            tables.add(c.treeRootNode.physicalTable);
        }
        c.columnCatalog = catalog.columnsOf(tables);

        c.anchorAlias = allocAlias(c, c.anchor.physicalTable);

        // 🌳 task-260907 B-3：子件列必须**在 NARROW 循环之前**解析出来 ——
        //    核价两套的 BOM 页签靠料号桥（NARROW 半连接）收窄，而树契约要求那条半连接
        //    也落在**子件列**上（与报价侧的直接轴收窄同一口径）。晚于 NARROW 循环解析，
        //    桥就已经按父件列发出去了，再改就得回头改字符串 —— 那正是最容易漂的写法。
        if (treeSemantic) {
            c.treeChildColumn = resolveTreeChildColumn(c);
        }

        // B-26（AC-15①，D-45同类跟进）：锚点自身的基线粒度此前从未写进 c.grainDims——只有
        // resolveGrain() 命中某个 GRAIN 目标时才会追加一维，导致"只选主档列（不涉及任何 GRAIN
        // 边）"时 grain=[]，与 AC-15①「粒度条显示『每个成品1行』」（非空、单维度）矛盾。
        // 取值口径（主线认可）：基线维度用锚点的 displayName 表示——与 resolveGrain 里
        // "target.displayName + '.' + dim" 的展示粒度保持同一语义层级（"这一维度是谁的"）。
        c.grainDims.add(c.anchor.displayName);

        // 有效列 = 用户已选列 + 价格策略自动带出的成员
        List<BuilderConfig.ColumnConfig> effectiveColumns = new ArrayList<>(
                cfg.columns == null ? List.of() : cfg.columns);
        PricePlan pricePlan = resolvePricePlan(c, effectiveColumns);

        // 强制 JOIN（edge_kind=JOIN，如「主件」页签的客户料号收窄）——无论是否被选列引用都必须出现
        for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
            if (!"JOIN".equals(e.edgeKind)) continue;
            emitMandatoryJoin(c, e);
        }

        // B-50：NARROW 半连接收窄。同样"无论是否被选列引用都必须出现"——它是**入参收窄**，
        // 不是可选的取列方式（用户根本选不到它的列，见 resolveColumn 的 NARROW 分支）。
        if (!skipNarrowPredicates) {
            for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
                if (!"NARROW".equals(e.edgeKind)) continue;
                emitNarrowPredicate(c, e);
            }
        }

        // B-47：两个约定列先占住名字，**必须在逐列循环之前**。业务列若正好叫 hf_part_no /
        // view_version（核价侧「裸 dbColumn」规则下完全可能），撞的就是渲染链路赖以定位料号 /
        // 版本的那一列——后果比普通撞名重得多。先占 ⇒ 业务列被改名让路；后占则业务列先拿到裸名、
        // 约定列再输出一个同名的，等于没修。
        c.usedAliases.add("hf_part_no");
        c.usedAliases.add("view_version");

        // ── task-260907 B-3（F-3，AC-4）：树页签走「边式契约」 ──────────────────────────
        // 三处改动（其余数据源一个字不变，AC-8）：
        //   ① 产出父子两列 material_no（子）/ parent_no（父）；
        //   ② 轴收窄从父件列改到子件列（见 Ctx.treeChildColumn / applyFullScope）；
        //   ③ 补 UNION ALL 根分支（无父边的成品自身，parent_no 置 NULL）。
        // 🚫 **不生成 WITH RECURSIVE**：递归早已存在于 costing_bom_tree_config（全系统一份、
        //    配置化、自带 CYCLE 防环），从本单根成品 unnest(:production_part_nos) 出发产出
        //    :total_material_no。页签 SQL 只吐「集合内的边」，树由 BomTreeRenderService 拼。
        // 🚦 2026-09-07 用户裁决：**核价两套（COST_BASIC / COST_DETAIL）本期一并改**，
        //    不再按「锚点挂了料号桥就跳过」收窄。桥不是障碍，只是收窄形态不同：
        //      · 报价侧无桥 ⇒ 直接轴收窄，子件列上发 `= ANY(:total_material_no)`；
        //      · 核价两套有桥 ⇒ 半连接收窄，同样落在**子件列**（见 emitNarrowPredicate），
        //        根分支也经同一座桥（见 buildTreeRootBranch）—— 🚫 绝不能退回
        //        `<根表>.<轴> = ANY(:total_material_no)`：核价轴是生产料号、数组装的是销售料号，
        //        那样写恒不命中（0 行且不报错）；更不能干脆不收窄，那是整张主档表全扫。
        boolean treeContract = treeSemantic;
        String treeParentExpr = null;
        String treeChildExpr = null;
        if (treeContract) {
            treeChildExpr = c.anchorAlias + "." + c.treeChildColumn;
            treeParentExpr = anchorColumnOnly(c);
            // 与 hf_part_no / view_version 同理（B-47）：两个约定列先占住名字，**必须在逐列循环之前**。
            // 核价侧「裸 dbColumn」别名规则下，业务列完全可能正好叫 material_no —— 撞的就是
            // 渲染层用来定位树节点的那一列，后果比普通撞名重得多。
            c.usedAliases.add(TREE_COL_MATERIAL_NO);
            c.usedAliases.add(TREE_COL_PARENT_NO);
        }

        // 逐列编译 SELECT 表达式
        List<String> selectExprs = new ArrayList<>();
        List<String> declaredColumns = new ArrayList<>();
        // 树页签根分支的逐列占位表达式，与 selectExprs **严格同序同长**（UNION ALL 要求列数对齐）。
        List<String> rootExprs = new ArrayList<>();
        for (BuilderConfig.ColumnConfig col : effectiveColumns) {
            if (isPriceColumn(pricePlan, col)) continue; // 价格策略列单独在下面统一输出
            ResolvedColumn rc = resolveColumn(c, col.sourceNodeKey, col.sourceColumn);
            String alias = dedupeAlias(c,
                    AliasGenerator.viewColumn(dialect, rc.node.shortName, rc.column.displayName, rc.column.dbColumn),
                    rc.node.shortName);
            selectExprs.add(rc.expr + " AS " + quoteAlias(alias));
            rootExprs.add(TREE_NULL_PLACEHOLDER);
            declaredColumns.add(alias);
            col.viewColumn = alias;
            col.resolvedDataType = rc.column.dataType;
            col.resolvedRoles = mergedRoles(c, rc.column);
            if (col.fieldName == null || col.fieldName.isBlank()) col.fieldName = rc.column.displayName;
            trackGrain(c, rc);
        }

        // 价格策略原子组的输出列（不带前缀，AC-1③）
        if (pricePlan != null) {
            for (BuilderConfig.ColumnConfig col : effectiveColumns) {
                if (!isPriceColumn(pricePlan, col)) continue;
                String dbCol = col.sourceColumn;
                // B-41：原先在这里用 nodeByKeyDialect.get(PRICE_FUNC_NODE_KEY + "|QUOTE") 重新查了
                // 一次函数节点（硬编码方言）。改用 resolvePricePlan 里**顺着 PRICE 边**解析出来的
                // 那个节点——边本身就是按方言声明的，既消灭硬编码又消灭"查到另一个节点"的可能。
                SemanticNodeColumn funcCol = findColumn(c, pricePlan.funcNode, dbCol);
                String bare = dedupeAlias(c,
                        AliasGenerator.bareColumn(col.fieldName != null ? col.fieldName : funcCol.displayName),
                        pricePlan.funcNode.shortName);
                selectExprs.add(PRICE_FUNC_ALIAS + "." + dbCol + " AS " + quoteAlias(bare));
                rootExprs.add(TREE_NULL_PLACEHOLDER);
                declaredColumns.add(bare);
                col.viewColumn = bare;
                col.resolvedDataType = funcCol.dataType;
                col.resolvedRoles = mergedRoles(c, funcCol);
                if (col.fieldName == null || col.fieldName.isBlank()) col.fieldName = funcCol.displayName;
            }
        }

        // hf_part_no 表达式（D-50/D-56：始终保持锚点自身列，不再按闭包改写为 COALESCE(cl.root_no,...)——
        // "子件行归属哪个成品"这层职责已整体移交 Java 侧，见 BomTreeRenderService#collectTotalMaterialNoUnion
        // 顺带产出的「后代→根」映射与 B-21 的 expandMulti 回分，AC-3③/AC-62）。
        String anchorExpr = requalifyAnchorExpr(c);   // 顺带做别名漂移校验，树/非树都要跑
        // 🌳 task-260907 B-3①：树页签的 hf_part_no 取**子件**（这一行讲的是这个料号，不是它父件），
        //    与存量 $wl_bom_view 的 `mbt.component_no as hf_part_no` 同口径。
        String hfExpr = treeContract ? treeChildExpr : anchorExpr;
        selectExprs.add(0, hfExpr + " AS hf_part_no");
        rootExprs.add(0, TREE_ROOT_SELF_EXPR);   // 占位，下面拿到根别名后统一回填
        declaredColumns.add(0, "hf_part_no");
        if (treeContract) {
            // 顺序 = api.md §2.2：material_no（子）· parent_no（父）· hf_part_no · 业务列…
            selectExprs.add(0, treeParentExpr + " AS " + TREE_COL_PARENT_NO);
            rootExprs.add(0, "NULL::text");
            declaredColumns.add(0, TREE_COL_PARENT_NO);
            selectExprs.add(0, treeChildExpr + " AS " + TREE_COL_MATERIAL_NO);
            rootExprs.add(0, TREE_ROOT_SELF_EXPR);
            declaredColumns.add(0, TREE_COL_MATERIAL_NO);
        }

        // 锚点自身收窄（轴收窄 + 核价侧版本谓词，B-41）+ 判别式
        // ⚠️ 轴收窄现在**三个方言统一**由 applyFullScope 发（见该方法注释）——原先 QUOTE 方言在
        // 本处另发一遍 anchorColumnOnly(c) + " = ANY(:total_material_no)" 的分支已删除，
        // 保留会与 applyFullScope 发出的同款谓词重复出现在 WHERE 里。
        applyFullScope(c, c.anchor, c.anchorAlias, c.anchorWhere);
        // AC-109③（B-41，随 D-84 反转）：核价两套输出 view_version 约定列——只有 applyFullScope
        // 真的发出了 :versionFilter 宏（锚点物理源同时有 is_current / version_no / 轴列，即它是
        // S-31 建的 v_<主表>_all 全版本视图）时才输出，避免给不支持版本切换的锚点硬造一列。
        // 🚨 取值必须 ::text：{@code CostingVersionService} 拿 driverRow 里的 view_version 直接
        // toString() 后与 costing_order_version_override.view_version（varchar(40)）比对，而
        // ds_*.version_no 是 integer —— V6 时代 unit_price.version_no 本身就是 character varying，
        // 下游是按 String 写的。这里转一次 text，下游契约逐字不变，且与宏第二实参口径一致。
        if (c.dialect.isCosting()) {
            Set<String> anchorCols = c.columnCatalog.getOrDefault(c.anchor.physicalTable, Set.of());
            if (emitsVersionFilter(c, anchorCols)) {
                selectExprs.add(c.anchorAlias + ".version_no::text AS view_version");
                rootExprs.add("NULL::text");
                declaredColumns.add("view_version");
            }
        }
        String anchorDiscriminator = resolveDiscriminator(c, c.anchor, null);
        if (anchorDiscriminator != null) {
            c.anchorWhere.add(qualify(c.anchorAlias, anchorDiscriminator));
        }
        // 费用类多变体合并（AC-8②）：把同物理表其它变体节点的判别式值并入 IN(...)
        if (!c.discriminatorValues.isEmpty() && c.discriminatorColumn != null) {
            String col = c.anchorAlias + "." + c.discriminatorColumn;
            // 移除刚才 anchor 自身判别式的单值写法，改成合并后的 IN(...)（去重保序）
            c.anchorWhere.removeIf(w -> w.startsWith(col + " ="));
            LinkedHashSet<String> vals = new LinkedHashSet<>(c.discriminatorValues);
            if (vals.size() == 1) {
                c.anchorWhere.add(col + " = '" + vals.iterator().next() + "'");
            } else {
                StringBuilder sb = new StringBuilder(col).append(" IN (");
                Iterator<String> it = vals.iterator();
                while (it.hasNext()) { sb.append("'").append(it.next()).append("'"); if (it.hasNext()) sb.append(","); }
                sb.append(")");
                c.anchorWhere.add(sb.toString());
            }
        }

        if (pricePlan != null) {
            c.joinClauses.add(pricePlan.joinClause);
            c.requiredVars.add("customerCode");
            c.requiredVars.add("priceBaseDate");
        }

        // FROM（D-50：闭包 CTE 已停用，顶层 FROM 恒为裸表——AC-3④，closureCte() 不再被调用）
        StringBuilder sql = new StringBuilder();
        if (treeContract) {
            sql.append("-- 树契约: ").append(TREE_COL_MATERIAL_NO).append("=子 / ")
               .append(TREE_COL_PARENT_NO).append("=父 + :total_material_no; 边式全子件 + 根分支\n");
        }
        sql.append("SELECT\n  ").append(String.join(",\n  ", selectExprs)).append("\n");
        sql.append("FROM ").append(c.anchor.physicalTable).append(" ").append(c.anchorAlias).append("\n");
        for (String j : c.joinClauses) sql.append("  ").append(j).append("\n");
        if (!c.anchorWhere.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", c.anchorWhere)).append("\n");
        }
        // 🌳 task-260907 B-3③：根分支 —— 集合内**无父边**的成品自身（树根，parent_no = NULL）。
        //    没有它，spine 的根节点（parent_no IS NULL）永远配不到业务行 ⇒ 树顶一行空白。
        if (treeContract) {
            sql.append(buildTreeRootBranch(c, rootExprs));
        }
        // D-45①（2026-08-21 主线裁决）：PG 没有 ORDER BY 的行序是未定义的——必须排序。判据是
        // "golden 行序与基准一致"，不是"加了 ORDER BY 就算数"（golden 实测见 backtask 回报）。键的
        // 构成参照基准 mc_view：ORDER BY ebi.material_no, ebi.material_part_no, ebi.seq_no —— 锚点列
        // 打头（D-50 后闭包层级列已随 A 机制一并停用），随后接锚点节点自身 grain_columns（逐列，按
        // 声明顺序），最后接该节点带 SORT 角色的列（如有）。
        List<String> orderCols = new ArrayList<>();
        if (treeContract) {
            // 🚨 UNION ALL 的 ORDER BY **只能引用输出列名/序号**，不能写 `别名.列`
            //    （PG：`ORDER BY dqmb.material_no` 在集合运算上直接语法错）。⇒ 树分支改用输出列名。
            //    键的构成与非树同源：父 → 子 → 该节点的 SORT 列（选中了才有输出列可排）。
            orderCols.add(TREE_COL_PARENT_NO);
            orderCols.add(TREE_COL_MATERIAL_NO);
            String sortAlias = findSortOutputAlias(c, effectiveColumns);
            if (sortAlias != null) orderCols.add(sortAlias);
        } else {
            orderCols.add(anchorColumnOnly(c));
            for (String grainCol : c.anchor.grainColumns) {
                orderCols.add(c.anchorAlias + "." + grainCol);
            }
            String sortCol = findSortColumn(c);
            if (sortCol != null) orderCols.add(sortCol);
        }
        sql.append("ORDER BY ").append(String.join(", ", orderCols));

        String finalSql = sql.toString();

        // B-53：产物级护栏。在**最终 SQL 文本**上复核 :total_material_no 只被一种语义消费
        assertAxisParamSingleSemantic(c, finalSql);

        // customerCode 只要涉及任意 customer_no 收窄或价格函数就需要
        if (finalSql.contains(":customerCode")) c.requiredVars.add("customerCode");

        CompileResult result = new CompileResult();
        result.sql = finalSql;
        result.declaredColumns = declaredColumns;
        result.requiredVariables = new ArrayList<>(c.requiredVars);
        result.grain = new ArrayList<>(c.grainDims);
        result.warnings = c.warnings;
        result.effectiveColumns = effectiveColumns;
        result.rewriterCompatible = checkRewriterCompatible(finalSql, c.anchor.physicalTable);
        result.anchorTable = c.anchor.physicalTable;
        result.axisColumn = c.dialect.axisColumn();
        // repair-260908 B-3（AC-7）：轴范围声明。判据 = 页签类型是不是「主件」，
        // 取 c.tabView.tabType（图里的权威值）而不是 cfg.tabType（请求体的自述值）——
        // resolveTabView 已按 (tab_type, variant_key, dialect) 三段坐标解析过，图里那份才作数。
        // 🚫 三个方言一律产出，不按方言分叉：分叉会让「核价侧有没有这个键」变成又一个隐式约定。
        result.axisScope = ROOT_SOURCE_TAB_TYPE.equals(c.tabView.tabType)
                ? CompileResult.AXIS_SCOPE_SELF : CompileResult.AXIS_SCOPE_CLOSURE;
        return result;
    }

    // ---------------- 页签视图解析 ----------------

    /**
     * 页签视图解析（B-41：<b>必须带 dialect 过滤</b>）。
     *
     * <p>🚨 {@code semantic_tab_view} 的唯一键是 {@code (tab_type, variant_key, dialect)} ——
     * v9 起同一个 {@code (页签类型, 变体)} 在三个数据集下<b>各有一行并列存在</b>（§9.2 映射表）。
     * 不带 dialect 过滤时 {@code findFirst()} 命中的是加载顺序里的第一行，编译「基础核价·主件」
     * 可能拿到报价侧那一行的锚点 ⇒ FROM 到另一套物理表、还照样编译成功、照样能查 —— 典型的
     * 静默取错数据集。
     */
    private SemanticTabView resolveTabView(SemanticGraphSnapshot snap, BuilderConfig cfg, CompileDialect dialect) {
        String vk = cfg.variantKey == null ? "" : cfg.variantKey;
        String dl = dialect.graphDialect();
        return snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(vk) && dl.equals(t.dialect))
                .findFirst()
                // B-60/AC-127⑤：报文必须点名**合法值域**（从图按 dialect 实时取），
                // 且把"页签类型非法"与"类型合法、只是缺/错变体"分开说 —— 见 TabViewNotFound。
                .orElseThrow(() -> TabViewNotFound.of(snap, 400, cfg.tabType, vk, dl));
    }

    // D-51/AC-60：containsSwitch() 曾用于读 tabView.switches 里的 CLOSURE 标记，随闭包开关整体
    // 取消一并停用移除（唯一调用点已随 c.closure 字段一起删除）。

    // ---------------- 别名分配（(shortName 无关) 纯按物理表推导，复现现网 ebi/mm/mr/up/ca 等约定） ----------------

    private String allocAlias(Ctx c, String physicalTable) {
        String base = baseAlias(physicalTable);
        int n = c.aliasSeq.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + n;
    }

    private static String baseAlias(String table) {
        if (table == null || table.isBlank()) return "t";
        if (table.contains("_")) {
            StringBuilder sb = new StringBuilder();
            for (String seg : table.split("_")) {
                if (!seg.isEmpty()) sb.append(seg.charAt(0));
            }
            return sb.length() > 0 ? sb.toString() : table.substring(0, Math.min(2, table.length()));
        }
        return table.substring(0, Math.min(2, table.length()));
    }

    // ---------------- 强制 JOIN（edge_kind=JOIN，客户维度收窄类） ----------------

    private void emitMandatoryJoin(Ctx c, SemanticEdge e) {
        SemanticNode target = c.snap.nodeById.get(e.toNodeId);
        if (target == null || c.aliasByNode.containsKey(target.id)) return;
        String alias = allocAlias(c, target.physicalTable);
        c.aliasByNode.put(target.id, alias);
        List<String> on = new ArrayList<>();
        for (SemanticEdgeKey k : c.snap.keysOf(e.id)) {
            on.add(alias + "." + k.rightColumn + " = " + c.anchorAlias + "." + k.leftColumn);
        }
        if (target.fixedPredicate != null && !target.fixedPredicate.isBlank()) {
            String qualified = qualify(alias, target.fixedPredicate);
            on.add(qualified);
            if (qualified.contains(":customerCode")) c.requiredVars.add("customerCode");
        }
        c.joinClauses.add("JOIN " + target.physicalTable + " " + alias + " ON " + String.join(" AND ", on));
    }

    // ---------------- NARROW 半连接收窄（B-50） ----------------

    /**
     * {@code edge_kind='NARROW'}：拿 from 表的键去 to 表解析出对应键，用结果<b>收窄 from 表</b>
     * （task-260819 B-50，用户 2026-09-04 裁决）。
     *
     * <p><b>它解决什么</b>：核价侧的轴是<b>生产料号</b>，而产品卡片给的是<b>销售料号</b>，中间要过
     * {@code ds_quote_material} 这座桥。桥原本声明成 {@code LOOKUP}（输出列）⇒ 编译成
     * {@code LEFT JOIN ds_quote_material ON dqm.production_no = 锚点.production_no}，方向是
     * 「生产料号 → 销售料号」，而一个生产料号可以对应多个销售料号（用户裁决：这是<b>合法业务</b>）
     * ⇒ <b>一行核价数据被放大成 N 行，行数与金额一起翻倍</b>。
     *
     * <p><b>改法的要点是方向反过来 + 落在 WHERE 而不是 FROM</b>：
     * <pre>
     * 锚点.production_no IN (SELECT b.production_no FROM ds_quote_material b
     *                        WHERE b.material_no = ANY(:total_material_no))
     * </pre>
     * 方向变成「销售料号 → 生产料号」（45/45 唯一），且半连接<b>按定义不放大行数</b>——
     * 子查询返回多少个销售料号都不影响外层行数，这正是 {@code IN} 与 {@code JOIN} 的本质差别。
     * <b>同一张表、同一列，用在 SELECT 里还是 WHERE 里，差别就是扇出与不扇出。</b>
     *
     * <p>🚫 不产出 FROM 项、不产出任何显示列、不进字段面板（{@code FieldTreeBuilder} 侧同步排除）。
     *
     * <p><b>入参列</b>取 {@link CompileDialect#QUOTE} 的轴列（{@code material_no}）——桥节点的
     * {@code anchor_expr} 实测为 NULL（它从不作为页签锚点），所以不能从那里推。桥表里没有这一列时
     * <b>直接报错而不是静默不发</b>：不发 = 子查询退化成"整张桥表"= 完全不收窄 = 全表数据，
     * 那是比报错坏得多的静默故障。
     */
    private void emitNarrowPredicate(Ctx c, SemanticEdge e) {
        SemanticNode target = c.snap.nodeById.get(e.toNodeId);
        if (target == null || target.physicalTable == null || target.physicalTable.isBlank()) {
            throw new BuilderApiException(500, "COMPILE_NARROW_TARGET_MISSING",
                    "NARROW 边指向的节点不存在或没有物理表（图数据不一致）", Map.of("edge", String.valueOf(e.id)));
        }
        List<SemanticEdgeKey> keys = c.snap.keysOf(e.id).stream()
                .sorted(Comparator.comparingInt(k -> k.seq)).toList();
        if (keys.isEmpty()) {
            throw new BuilderApiException(500, "COMPILE_NARROW_NO_KEYS",
                    "NARROW 边「" + target.displayName + "」没有声明连接键，无法生成收窄条件", Map.of());
        }

        Set<String> targetCols = c.columnCatalog.getOrDefault(target.physicalTable, Set.of());
        String inputCol = CompileDialect.QUOTE.axisColumn(); // 产品卡片给的是销售料号
        if (!targetCols.contains(inputCol)) {
            throw new BuilderApiException(500, "COMPILE_NARROW_INPUT_COLUMN_MISSING",
                    "收窄源「" + target.displayName + "」(" + target.physicalTable + ") 没有入参列 "
                            + inputCol + "，无法按销售料号收窄", Map.of("table", target.physicalTable));
        }

        // 🌳 task-260907 B-3②（核价侧）：树页签的收窄同样落在**子件列**上。
        //    改动前是 `<锚点>.production_no IN (...)`，即「这条边的**父件**在本单」；
        //    树契约要的是「这条边的**子件**在本单」，与报价侧 input_material_no 的直接轴收窄同口径。
        //    🚫 只在树页签覆盖，其余数据源仍按连接键左列（AC-8：非树产物逐字不变）。
        List<String> leftCols = c.treeChildColumn != null
                ? List.of(c.treeChildColumn)
                : keys.stream().map(k -> k.leftColumn).toList();
        if (c.treeChildColumn != null && keys.size() != 1) {
            throw new BuilderApiException(500, "COMPILE_TREE_NARROW_MULTIKEY",
                    "BOM 树页签的料号桥声明了 " + keys.size() + " 个连接键，"
                            + "无法把收窄整体挪到单一子件列「" + c.treeChildColumn + "」上 —— "
                            + "复合键的树收窄语义未定义，拒绝猜。",
                    Map.of("anchorNodeKey", c.anchor.nodeKey, "edge", String.valueOf(e.id)));
        }

        String sub = allocAlias(c, target.physicalTable);
        String left = leftCols.size() == 1
                ? c.anchorAlias + "." + leftCols.get(0)
                : "(" + leftCols.stream().map(x -> c.anchorAlias + "." + x)
                        .reduce((a, b) -> a + ", " + b).orElseThrow() + ")";
        String right = keys.stream().map(k -> sub + "." + k.rightColumn)
                .reduce((a, b) -> a + ", " + b).orElseThrow();

        // 🚨 repair-260908 B-1b（AC-16）：桥的**子查询**同样要按客户收窄。
        //
        // 本谓词直接拼进 c.anchorWhere，**不经 applyFullScope** ⇒ B-1 覆盖不到它。
        // 桥的 target 是 ds_quote_material（有 customer_no 且此前完全没过滤）：外层锚点是
        // ds_cost_basic_*（无该列、B-1 不发），所以缺陷①在核价侧是**以桥接形态存在**的
        // （D-2b：「核价侧天然不适用」那句话不完整）。判据仍是列存在性，不是方言。
        //
        // ⚠️ 这是**结构隐患不是活故障**：实测 ds_quote_material 里 16 个料号跨客户，但
        // count(distinct production_no) > 1 的组数 = 0；且 x IN (SELECT ...) 是集合成员判定，
        // 子查询多出重复值不会让外层翻倍 ⇒ 拿行数验它会得到一个恒绿的判据（AC-16 因此写成结构断言）。
        //
        // 📌 位置刻意放在 `= ANY(:total_material_no)` **之后**：B-53 护栏的 BRIDGE_SEMI_JOIN 正则
        //    用 [^()] 锁死在同一层子查询、匹配到 ANY(:total_material_no) 为止，追加在其后不影响
        //    「产物里数出的桥 vs 结构化认出的桥」对账（谓词原文仍是 anchorWhere 里那一条的逐字子串）。
        String bridgeCustomerScope = "";
        if (targetCols.contains(CUSTOMER_SCOPE_COLUMN)) {
            bridgeCustomerScope = " AND " + sub + "." + CUSTOMER_SCOPE_COLUMN + " = :customerCode";
            c.requiredVars.add("customerCode");
        }

        c.anchorWhere.add(left + " IN (SELECT " + right
                + " FROM " + target.physicalTable + " " + sub
                + " WHERE " + sub + "." + inputCol + " = ANY(:total_material_no)"
                + bridgeCustomerScope + ")");
        c.requiredVars.add("total_material_no");
        // 轴收窄的职责就此移交给本谓词，applyFullScope 不再另发一条（见该方法注释）
        c.narrowedByBridge = true;
        c.narrowEdge = e;   // 树页签的根分支要经同一座桥（buildTreeRootBranch）
    }

    // ---------------- 单列解析 ----------------

    private static final class ResolvedColumn {
        SemanticNode node;   // 值实际所在的节点（用于别名前缀 shortName）
        SemanticNodeColumn column;
        String expr;         // 完整 SQL 表达式（含别名限定或子查询）
    }

    private ResolvedColumn resolveColumn(Ctx c, String sourceNodeKey, String sourceColumn) {
        // B-41：节点查找必须按当前方言取（{@code semantic_node} 唯一键 = (node_key, dialect)）。
        // 原先硬编码 "|QUOTE"：三方言并存后，用 COST_BASIC 编译会一律拿到报价侧同名节点的
        // physical_table，编出来的 SQL 指着另一套数据集的表且不报错。
        SemanticNode target = c.snap.nodeByKeyDialect.get(sourceNodeKey + "|" + c.dialect.graphDialect());
        if (target == null) {
            throw new BuilderApiException(400, "COMPILE_COLUMN_SOURCE_UNKNOWN",
                    "未知的列来源节点: " + sourceNodeKey + "（数据集 " + c.dialect.graphDialect() + "）", Map.of());
        }
        SemanticNodeColumn col = findColumn(c, target, sourceColumn);

        // 同物理表：SAME 边成员 / 费用类多变体合并 —— 直接读锚点自己的行，不另开 JOIN
        if (target.physicalTable != null && target.physicalTable.equals(c.anchor.physicalTable)) {
            if (!target.id.equals(c.anchor.id)) {
                mergeSameTableDiscriminator(c, target);
            }
            ResolvedColumn rc = new ResolvedColumn();
            rc.node = target;
            rc.column = col;
            rc.expr = c.anchorAlias + "." + col.dbColumn;
            return rc;
        }

        // 经边到达：在 anchor 的直接出边里找目标节点
        SemanticEdge edge = c.snap.edgesFrom(c.anchor.id).stream()
                .filter(e -> e.toNodeId.equals(target.id))
                .findFirst()
                .orElseThrow(() -> new BuilderApiException(400, "COMPILE_PATH_NOT_FOUND",
                        "锚点「" + c.anchor.displayName + "」没有到「" + target.displayName + "」的声明边", Map.of()));

        return switch (edge.edgeKind) {
            case "LOOKUP" -> resolveLookup(c, edge, target, col);
            // B-25（AC-11③/AC-13，D-45②同类跟进）：edge_kind=JOIN 的边（如「主件」页签的
            // CUSTOMER_MAP，客户维度收窄用的强制 JOIN）此前逐列编译完全没有分支，任意选它的列都
            // 报 COMPILE_EDGE_KIND_UNSUPPORTED。JOIN 与 LOOKUP 在"取列"这一步是同一件事——
            // 都是"目标节点已经/将要被 JOIN 进来，取它自己的物理列"，唯一差异是 emitMandatoryJoin
            // 已经把该边的 JOIN 无条件建好并把 alias 记入 c.aliasByNode，resolveLookup 内部
            // ensureLeftJoin() 命中 existing alias 时直接复用、不会重复建 JOIN 子句，也不会把
            // 强制 JOIN 降级成 LEFT JOIN（JOIN 子句本身在 emitMandatoryJoin 里已经生成过）。
            case "JOIN" -> resolveLookup(c, edge, target, col);
            // B-50：NARROW 的产物是 WHERE 半连接，不产出 FROM 项、不产出显示列 ⇒ 它的列**不可选**。
            // 给一条专门的错误文案而不是落进 default 的"暂不支持"——后者会让人以为是没实现，
            // 于是去给 NARROW 加取列实现，而那恰恰是这次要消灭的扇出根源（桥当输出列 = LEFT JOIN）。
            case "NARROW" -> throw new BuilderApiException(400, "COMPILE_EDGE_KIND_UNSUPPORTED",
                    "「" + target.displayName + "」是收窄用的输入源（NARROW），只用来限定取哪些行，"
                            + "本身不提供可展示的列。若确实需要展示它的字段，应改用查名（LOOKUP）声明，"
                            + "但要先确认不会因一对多而放大行数",
                    Map.of("node", target.nodeKey, "edgeKind", edge.edgeKind));
            case "SUB" -> resolveSub(c, edge, target, col);
            case "GRAIN" -> resolveGrain(c, edge, target, col);
            default -> throw new BuilderApiException(400, "COMPILE_EDGE_KIND_UNSUPPORTED",
                    "本列的连接类型暂不支持: " + edge.edgeKind, Map.of());
        };
    }

    /** 两层 roles 合并（D-35）：本页签视图的列级覆盖优先，否则退回节点级默认。 */
    private List<String> mergedRoles(Ctx c, SemanticNodeColumn col) {
        List<SemanticTabViewColumn> overrides = c.snap.tabViewColumnsByView
                .getOrDefault(c.tabView.id, List.of()).stream()
                .filter(o -> o.columnId.equals(col.id)).toList();
        if (!overrides.isEmpty()) return List.of(overrides.get(0).roles);
        return List.of(col.roles);
    }

    private SemanticNodeColumn findColumn(Ctx c, SemanticNode node, String dbColumn) {
        return c.snap.columnsOf(node.id).stream()
                .filter(cc -> cc.dbColumn.equals(dbColumn))
                .findFirst()
                .orElseThrow(() -> new BuilderApiException(400, "COMPILE_COLUMN_NOT_FOUND",
                        "节点「" + node.displayName + "」没有列: " + dbColumn, Map.of()));
    }

    /** 费用类多变体合并（AC-8）：user 同时选中多个共享物理表的"变体主节点"的列时，把判别式值并入 IN(...)。 */
    private void mergeSameTableDiscriminator(Ctx c, SemanticNode siblingNode) {
        if (siblingNode.discriminator == null) return;
        String[] parts = siblingNode.discriminator.split("=", 2);
        if (parts.length != 2) return;
        String colName = parts[0].trim();
        String val = parts[1].trim().replaceAll("^'|'$", "");
        c.discriminatorColumn = colName;
        if (!c.discriminatorValues.contains(val)) c.discriminatorValues.add(val);
        // 锚点自身的判别式值也要进合并集合（否则只剩 sibling 一个值）
        if (c.anchor.discriminator != null) {
            String[] ap = c.anchor.discriminator.split("=", 2);
            if (ap.length == 2) {
                String av = ap[1].trim().replaceAll("^'|'$", "");
                if (!c.discriminatorValues.contains(av)) c.discriminatorValues.add(0, av);
            }
        }
    }

    private ResolvedColumn resolveLookup(Ctx c, SemanticEdge edge, SemanticNode target, SemanticNodeColumn col) {
        ResolvedColumn rc = new ResolvedColumn();
        rc.node = target;
        rc.column = col;

        if (edge.coalesceGroup != null) {
            // 多源 COALESCE：找同 coalesceGroup 的全部边，按 fallbackOrder 排序，各自 LEFT JOIN，
            // 每个目标里挑一个与 col 同角色（role）的列做 COALESCE 分支。
            List<SemanticEdge> siblings = c.snap.edgesFrom(c.anchor.id).stream()
                    .filter(e -> edge.coalesceGroup.equals(e.coalesceGroup))
                    .sorted(Comparator.comparingInt(e -> e.fallbackOrder == null ? 0 : e.fallbackOrder))
                    .toList();
            List<String> roles = List.of(col.roles);
            List<String> branches = new ArrayList<>();
            for (SemanticEdge sib : siblings) {
                SemanticNode sibNode = c.snap.nodeById.get(sib.toNodeId);
                String alias = ensureLeftJoin(c, sib, sibNode);
                SemanticNodeColumn sibCol = pickColumnByRole(c, sibNode, roles, col);
                branches.add(alias + "." + sibCol.dbColumn);
            }
            if (edge.fallbackToJoinKey) branches.add(joinKeyFallbackExpr(c, edge));
            rc.expr = branches.size() == 1 ? branches.get(0) : "COALESCE(" + String.join(", ", branches) + ")";
            return rc;
        }

        String alias = ensureLeftJoin(c, edge, target);
        String expr = alias + "." + col.dbColumn;
        // D-45③（V393 fallback_to_join_key）：查不到名称时退回连接键左列（原始编码），如
        // jg_view/ll_view 的 COALESCE(pm.process_name, up.operation_no)——是否退回是每条边的
        // 业务选择（mc_view 的材质名称查名就没有），只在该边显式置 true 时才追加。
        rc.expr = edge.fallbackToJoinKey ? "COALESCE(" + expr + ", " + joinKeyFallbackExpr(c, edge) + ")" : expr;
        return rc;
    }

    /** {@code fallback_to_join_key} 用：该 LOOKUP 边自身连接键的左列（锚点侧原始编码列）。 */
    private String joinKeyFallbackExpr(Ctx c, SemanticEdge edge) {
        List<SemanticEdgeKey> keys = c.snap.keysOf(edge.id);
        if (keys.isEmpty()) {
            throw new BuilderApiException(500, "COMPILE_FALLBACK_KEY_MISSING",
                    "边 " + edge.id + " 声明了 fallback_to_join_key 但没有连接键", Map.of());
        }
        return c.anchorAlias + "." + keys.get(0).leftColumn;
    }

    private SemanticNodeColumn pickColumnByRole(Ctx c, SemanticNode node, List<String> roles, SemanticNodeColumn fallback) {
        if (!roles.isEmpty()) {
            for (SemanticNodeColumn cc : c.snap.columnsOf(node.id)) {
                for (String r : cc.roles) if (roles.contains(r)) return cc;
            }
        }
        // 兜底：同名列
        for (SemanticNodeColumn cc : c.snap.columnsOf(node.id)) {
            if (cc.dbColumn.equals(fallback.dbColumn)) return cc;
        }
        // 再兜底：该节点唯一非 code 列
        return c.snap.columnsOf(node.id).stream().filter(cc -> !cc.isCode).findFirst().orElse(fallback);
    }

    private String ensureLeftJoin(Ctx c, SemanticEdge edge, SemanticNode target) {
        String existing = c.aliasByNode.get(target.id);
        if (existing != null) return existing;
        String alias = allocAlias(c, target.physicalTable);
        c.aliasByNode.put(target.id, alias);
        List<String> on = new ArrayList<>();
        for (SemanticEdgeKey k : c.snap.keysOf(edge.id)) {
            on.add(alias + "." + k.rightColumn + " = " + c.anchorAlias + "." + k.leftColumn);
        }
        // 2026-08-21 实测发现（D-45②验证时撞见）：目标节点若声明了 fixed_predicate（如
        // LOOKUP_CUSTOMER_MAP 的 customer_no=:customerCode），此前只有 emitMandatoryJoin
        // （edge_kind=JOIN）会应用它，LOOKUP 边完全没管——LEFT JOIN 会不分客户地捞出该料号
        // 在"任意客户"下的映射行，是真实的跨客户串号风险，不是理论问题（本项目 RECORD.md
        // 明确记录过跨客户串号类 bug 的历史教训）。LOOKUP/SUB 共用的查名 JOIN 必须同样限定。
        if (target.fixedPredicate != null && !target.fixedPredicate.isBlank()) {
            String qualified = qualify(alias, target.fixedPredicate);
            on.add(qualified);
            if (qualified.contains(":customerCode")) c.requiredVars.add("customerCode");
        }
        c.joinClauses.add("LEFT JOIN " + target.physicalTable + " " + alias + " ON " + String.join(" AND ", on));
        return alias;
    }

    private ResolvedColumn resolveSub(Ctx c, SemanticEdge edge, SemanticNode target, SemanticNodeColumn col) {
        String subAlias = "t"; // 子查询作用域局部，不参与外层别名分配
        List<String> where = new ArrayList<>();
        for (SemanticEdgeKey k : c.snap.keysOf(edge.id)) {
            where.add(subAlias + "." + k.rightColumn + " = " + c.anchorAlias + "." + k.leftColumn);
        }
        applyFullScope(c, target, subAlias, where);
        String disc = resolveDiscriminator(c, target, edge);
        if (disc != null) where.add(qualify(subAlias, disc));

        ResolvedColumn rc = new ResolvedColumn();
        rc.node = target;
        rc.column = col;
        rc.expr = "(SELECT " + subAlias + "." + col.dbColumn +
                " FROM " + target.physicalTable + " " + subAlias +
                " WHERE " + String.join(" AND ", where) + " LIMIT 1)";
        return rc;
    }

    private ResolvedColumn resolveGrain(Ctx c, SemanticEdge edge, SemanticNode target, SemanticNodeColumn col) {
        String existing = c.aliasByNode.get(target.id);
        String alias;
        if (existing != null) {
            alias = existing;
        } else {
            // 已经有别的 GRAIN 目标被选中过 → 打架，编译期拒绝而不是猜（呼应 AC-16/17 的兜底）
            boolean hasOtherGrain = c.snap.edgesFrom(c.anchor.id).stream()
                    .anyMatch(e -> "GRAIN".equals(e.edgeKind) && !e.toNodeId.equals(target.id)
                            && c.aliasByNode.containsKey(e.toNodeId));
            if (hasOtherGrain) {
                throw new BuilderApiException(400, "COMPILE_GRAIN_CONFLICT",
                        "已选列的行粒度冲突：不能同时按「" + target.displayName + "」与另一个附属源的维度展开",
                        Map.of("node", target.nodeKey));
            }
            alias = allocAlias(c, target.physicalTable);
            c.aliasByNode.put(target.id, alias);
            List<String> on = new ArrayList<>();
            for (SemanticEdgeKey k : c.snap.keysOf(edge.id)) {
                on.add(alias + "." + k.rightColumn + " = " + c.anchorAlias + "." + k.leftColumn);
            }
            c.joinClauses.add("JOIN " + target.physicalTable + " " + alias + " ON " + String.join(" AND ", on));
            applyFullScope(c, target, alias, c.anchorWhere);
            String disc = resolveDiscriminator(c, target, edge);
            if (disc != null) c.anchorWhere.add(qualify(alias, disc));
            for (String dim : target.grainColumns) c.grainDims.add(target.displayName + "." + dim);
        }
        ResolvedColumn rc = new ResolvedColumn();
        rc.node = target;
        rc.column = col;
        rc.expr = alias + "." + col.dbColumn;
        return rc;
    }

    private void trackGrain(Ctx c, ResolvedColumn rc) {
        // grain[] 展示用：GRAIN 目标已在 resolveGrain 里记录；直接列/LOOKUP/SUB 不改变行粒度。
    }

    // ---------------- 判别式（AC-6：MATERIAL_BOM 的 characteristic 由页签类型/来向边动态推导） ----------------

    /**
     * "来向边"分支委托给 {@link com.cpq.semanticgraph.service.DiscriminatorResolver}（2026-08-21
     * 抽取共享，原因见该类注释：{@link com.cpq.semanticgraph.service.SemanticGraphService} 的边
     * 基数校验也需要同一条规则，此前两处独立实现过一次并因此漏过一次真实 bug）。"作为锚点"分支
     * （tabType 相关）是编译期特有语境，边基数校验用不到，留在本类。
     */
    private String resolveDiscriminator(Ctx c, SemanticNode node, SemanticEdge viaEdge) {
        if (viaEdge != null) {
            SemanticNode from = c.snap.nodeById.get(viaEdge.fromNodeId);
            return com.cpq.semanticgraph.service.DiscriminatorResolver.resolve(from, node);
        }
        if (node.discriminator != null) return node.discriminator;
        if (!"MATERIAL_BOM".equals(node.nodeKey)) return null;
        // task-260904 B-3（AC-1，api.md §2）：原此处有一条
        //     if ("外购件".equals(c.tabView.tabType)) return "characteristic = 'OUTSOURCED'";
        // 已移除。两条独立理由：
        //   ① **它是会报错的死代码**：该判别式引用的 characteristic 列在 ds_quote_material_bom
        //      的 20 个物理列里根本不存在 ⇒ 选「外购件」页签走编译器必 400
        //      `column dqmb.characteristic does not exist`（2026-09-06 §①ter 实测）；
        //   ② **「外购件」页签已对新建组件退役**（S-4 / RETIRED_TAB_TYPES）——料号是不是外购件
        //      是料号自身的属性（ds_quote_material.material_type），不是页签的属性。
        // ⇒ MATERIAL_BOM 作锚点时**恒不加 characteristic 过滤**，即原 BOM 树行为（AC-6②）。
        // 🚫 存量那 15 个外购件组件不受影响：它们是**手写 SQL 视图**，压根不经过本编译器
        //    （其 SQL 走 v_compat_material_bom_item 兼容视图，实测 15/15 取得到数）。
        return null;
    }

    // ---------------- 收窄（B-41：轴收窄 + 核价侧版本谓词；🚫 已无 system_type / customer_no） ----------------

    /**
     * 节点级收窄（task-260819 B-41，AC-107 / AC-108 / AC-109②）。
     *
     * <p>🔄 <b>2026-09-03 整块改写</b>。原方法叫「三件套收窄」（{@code is_current} /
     * {@code system_type} / {@code customer_no}），那是 V6 表结构的形态；新的 {@code ds_*} 45 张表
     * <b>这三列一列都没有</b>（唯一有 {@code customer_no} 的 {@code ds_quote_customer_part} 按 N-19
     * 不进图，2026-09-03 逐表查 {@code information_schema} 实测确认）。旧 {@code QUOTE}/{@code COSTING}
     * 两条分支随 V6 节点一并删除、不保留（B-41④）——留着只会在新图上生成永远为假/永远报错的谓词。
     *
     * <p>现在只剩两类谓词，且<b>三个方言同一套代码</b>（差异全部压进 {@link CompileDialect}）：
     * <ol>
     *   <li><b>轴收窄</b>（AC-108）：{@code <别名>.<轴列> = ANY(:total_material_no)}。轴列由
     *       {@link CompileDialect#axisColumn()} 给出（{@code QUOTE} → {@code material_no}；
     *       两个 {@code COST_*} → {@code production_no}）。<b>只在目标表真的有这一列时才发</b> ——
     *       按 {@code scheme_no} 建模的 {@code ds_*_plating_scheme} 之类没有轴列，硬造一个不存在的
     *       列引用会让整条 SQL 运行期报错（这正是 AC-7② 当年实测踩到的同型坑），此时靠该节点自身
     *       的连接键收窄即可。</li>
     *   <li><b>版本谓词</b>（AC-109②，仅核价两套）：{@code :versionFilter(<别名>.is_current,
     *       <别名>.version_no::text, <别名>.<轴列>)}。触发条件 = 该物理源同时有
     *       {@code is_current} + {@code version_no} + 轴列，也就是它是 S-31 建的
     *       {@code v_<主表>_all} 全版本视图（{@code 主表 UNION ALL <主表>_history}，多一列常量
     *       {@code is_current}）；报价侧节点直接指主表、连 {@code is_current} 都没有，天然不发（AC-107）。</li>
     * </ol>
     *
     * <p>🚨 <b>{@code ::text} 不是可选的</b>（D-85）：{@link com.cpq.datasource.sqlview.VersionFilterMacro}
     * 展开出 {@code (版本列) IS NOT DISTINCT FROM k.v}，而 {@code k.v} 来自 {@code :__vfVer::text[]}；
     * {@code ds_*.version_no} 是 {@code integer} ⇒ 不转换直接
     * {@code operator does not exist: integer = text}。V6 的 {@code unit_price.version_no} 是
     * {@code character varying} 所以老路从没暴露过这个问题。
     *
     * <p>兜底分支：物理源有 {@code is_current} 但缺 {@code version_no} 或缺轴列时（新模型里不该出现，
     * 因为全版本视图必然三者齐全），退回裸 {@code <别名>.is_current} —— <b>不能什么都不发</b>，
     * 否则 {@code _history} 的历史行（{@code is_current=false}）会整批漏进结果，是静默的行数翻倍。
     */
    private void applyFullScope(Ctx c, SemanticNode node, String alias, List<String> where) {
        Set<String> cols = c.columnCatalog.getOrDefault(node.physicalTable, Set.of());
        String axis = c.dialect.axisColumn();

        if (c.dialect.isCosting() && cols.contains("is_current")) {
            if (emitsVersionFilter(c, cols)) {
                where.add(":versionFilter(" + alias + ".is_current, "
                        + alias + ".version_no::text, "
                        + alias + "." + axis + ")");
            } else {
                where.add(alias + ".is_current");
            }
        }

        // B-50：锚点声明了 NARROW 边时，**不再直接发轴谓词**。
        // 🚨 这不是优化，是正确性：:total_material_no 装的是**销售料号**，而核价侧的轴列是
        // production_no —— 两者是不同号段，直接 `production_no = ANY(:total_material_no)` 会
        // 恒不命中（0 行），且与半连接 AND 在一起时"看起来只是没数据"，不会报任何错。
        // 收窄职责整体交给半连接：它挂在锚点上，SUB/GRAIN 目标通过各自的连接键与锚点相关联，
        // 因而是被间接收窄的，不需要各自再发一条。
        // 🌳 task-260907 B-3②：树页签的轴收窄落在**子件列**，不是父件（轴）列 ——
        //    与存量 $bom_view 的 `mbi.component_no = ANY(:total_material_no)` 逐字同口径。
        //
        //    ⚠️ 说清楚它修的是什么、不是什么（2026-09-07 实跑产物核对过，免得照需求文档的措辞去验错东西）：
        //    · **不是**"父件过滤只出一层"。:total_material_no 是本单闭包（成品 + 全部后代），
        //      闭包对"取子件"封闭 ⇒ 父件过滤同样能出孙级边。物料BOM 页签真正坏在
        //      **从不产出 parent_no / 没有根分支**（本方法上游那两处）。
        //    · 两种写法覆盖的 spine 节点集合相同（spine 每个节点的父与子都在闭包里，两边都命中）；
        //      差别在**多出来的行**：子件过滤会额外带回「子件在本单、父件不在本单」的边
        //      （实测料号 0526-2609000005 的闭包下多出 3 行，父件是 S-2120011659 / S-3110520789 /
        //      T260907-M1）。这些行在 BomTreeRenderService 里按 (parent_no, material_no) 配不到
        //      spine 节点，被原样丢弃 —— 无害，但确实是多查出来的。
        //    · 那为什么仍然改：**与存量树视图口径统一**（18/18 个 bom_recursive_expand=true 的
        //      组件全是子件过滤，如 $bom_view 的 `mbi.component_no = ANY(:total_material_no)`）。
        //      同一份数据、配置器一条路、手写视图另一条路，产出规则不一致本身就是故障源 ——
        //      这正是本任务在收敛的东西。
        // 🚫 只对**锚点自身**生效：GRAIN/SUB 目标是别的物理表，轴语义不变。
        // 🚫 只对**锚点自身**生效：GRAIN/SUB 目标是别的物理表，轴语义不变。
        String effectiveAxis = (c.treeChildColumn != null && alias.equals(c.anchorAlias))
                ? c.treeChildColumn : axis;
        if (!c.narrowedByBridge && cols.contains(effectiveAxis)) {
            where.add(alias + "." + effectiveAxis + " = ANY(:total_material_no)");
            c.requiredVars.add("total_material_no");
        }

        // 🚨 repair-260908 B-1（AC-1b / AC-2 / AC-2b / AC-12 / AC-12b）：客户维度收窄。
        //
        // 缺陷原文：本方法此前一条客户谓词都不发，:customerCode 只出现在 LOOKUP 边的
        // JOIN ... ON 上（见 ensureLeftJoin 的 fixedPredicate 分支）⇒ **主表一行都不过滤**。
        // 而 task-260907（V425~V429）已给 28 张 ds_quote_* 加了 customer_no 并把轴模型改成
        // 复合轴 (customer_no, material_no)，编译器侧没跟上 ⇒ 复合轴只落实了一半：
        // 同一个销售料号在两个客户下各有一行，卡片就把别人客户的行**静默**并进来
        // （实测「产品」页签闭包 14 个料号返 28 行，另一半全是 CUST-0001 的）。
        //
        // 🔑 判据是**列存在性**，不是方言、不是视图数量、不是从 SQL 文本里正则抽出来的表名：
        //   · ds_cost_* 55 张表逐表实测 customer_no 列数 = 0（核价按生产料号建模，本无客户维度）
        //     ⇒ 核价两方言的锚点/GRAIN/SUB 目标天然一条都不发（AC-4 零改动）；
        //   · 反过来，凡表上真有这一列的（含 QUOTE 侧 SUB / GRAIN 目标）一律要发 ——
        //     「28 个视图全加」那种按数量的写法会得到 column "customer_no" does not exist。
        //   · 并发线曾用正则 FROM\s+(ds_quote_\w+) 抽主表，3 条 COST_BASIC 视图匹到的是
        //     NARROW 桥**子查询里**的 ds_quote_material —— 用错误的判据碰巧碰对了位置，
        //     那个位置由 B-1b 单独处理（见 emitNarrowPredicate），不是本处。
        //
        // 🚫 刻意**不碰 ensureLeftJoin()**：LOOKUP 边的客户维度走 fixedPredicate + 列对列连接键
        //    （task-260908 那 46 条查名边就是 ON dqm.customer_no = dqiof.customer_no），
        //    把它挪进 WHERE 会把 LEFT JOIN 收成 INNER、静默丢行（AC-6）。
        if (cols.contains(CUSTOMER_SCOPE_COLUMN)) {
            where.add(alias + "." + CUSTOMER_SCOPE_COLUMN + " = :customerCode");
            c.requiredVars.add("customerCode");
        }
    }

    // ---------------- task-260907 B-3：树页签边式契约的解析与产出 ----------------

    /**
     * 根分支里「其余列」的占位表达式 —— <b>刻意用无类型 {@code NULL}，不写 {@code NULL::text}</b>。
     *
     * <p>PG 的 {@code UNION} 类型消解规则：某一分支该列是 unknown（裸 {@code NULL}）时，
     * 结果类型取<b>另一分支</b>的类型。⇒ 无论上面那列是 {@code varchar} / {@code numeric} /
     * {@code integer} / {@code timestamptz}，根分支都天然对齐。
     * 若在这里按语义 {@code data_type} 猜一个物理类型（TEXT→text、NUMBER→numeric），
     * 一旦某列的语义类型与物理类型不同族（例如 TEXT 落在 {@code date} 上），
     * 就会得到 {@code UNION types text and date cannot be matched} —— 而且是<b>保存那一刻才炸</b>。
     */
    private static final String TREE_NULL_PLACEHOLDER = "NULL";

    /** 根分支里「成品自身料号」的占位记号，{@link #buildTreeRootBranch} 拿到根别名后回填。 */
    private static final String TREE_ROOT_SELF_EXPR = "<<TREE_ROOT_SELF>>";

    /**
     * 树页签的<b>子件列</b>：锚点节点上带 {@code PART_NO} 角色的列（QUOTE 物料BOM →
     * {@code input_material_no}；核价两套 → {@code component_no}）。
     *
     * <p>🚫 <b>不硬编码列名</b>：三个方言的子件列各不相同，写死等于把「哪一列是子件」这件事
     * 从图里搬进代码，换表/换数据集就静默错位。{@code PART_NO} 在本项目里的语义正是
     * 「这一行讲的是哪个料号」（{@code BuilderService} 回填 {@code partNoField} 用的也是它），
     * 对一张 BOM 边表来说那就是子件。
     *
     * <p>解析不到 ⇒ <b>报错而不是退回父件列</b>：退回等于产出一棵所有节点都指向自己的"树"，
     * 渲染层不会报错，只会把整棵树画错。
     */
    private String resolveTreeChildColumn(Ctx c) {
        String parentCol = c.anchor.anchorExpr == null ? null
                : c.anchor.anchorExpr.substring(c.anchor.anchorExpr.indexOf('.') + 1);
        for (SemanticNodeColumn col : c.snap.columnsOf(c.anchor.id)) {
            if (!mergedRoles(c, col).contains("PART_NO")) continue;
            if (col.dbColumn.equals(parentCol)) continue;   // 父件列自己不能当子件列
            return col.dbColumn;
        }
        throw new BuilderApiException(500, "COMPILE_TREE_CHILD_COLUMN_MISSING",
                "BOM 树页签「" + c.anchor.displayName + "」的锚点节点上找不到子件列 —— "
                        + "树契约要求产出 material_no（子）/ parent_no（父）两列，"
                        + "子件列的判据是节点列上带 PART_NO 角色且不是父件列（" + parentCol + "）。"
                        + "请在 semantic_node_column 上给子件列补 PART_NO 角色。",
                Map.of("anchorNodeKey", c.anchor.nodeKey, "dialect", c.dialect.graphDialect()));
    }

    /**
     * 根分支的数据源节点 = 同方言「主件」页签的锚点（QUOTE → {@code ds_quote_material}）。
     * 解析不到返回 {@code null}（真要用时由 {@link #buildTreeRootBranch} 报错）。
     */
    private SemanticNode resolveTreeRootNode(Ctx c) {
        String dl = c.dialect.graphDialect();
        return c.snap.tabViews.stream()
                .filter(t -> ROOT_SOURCE_TAB_TYPE.equals(t.tabType)
                        && (t.variantKey == null || t.variantKey.isEmpty())
                        && dl.equals(t.dialect))
                .map(t -> c.snap.nodeById.get(t.anchorNodeId))
                .filter(Objects::nonNull)
                .filter(n -> n.physicalTable != null && !n.physicalTable.isBlank())
                .filter(n -> n.anchorExpr != null && n.anchorExpr.contains("."))
                .findFirst()
                .orElse(null);
    }

    /**
     * 产出 {@code UNION ALL} 根分支：本单闭包里<b>没有任何父边</b>的料号（= 成品自身）。
     *
     * <pre>
     * UNION ALL
     * SELECT dqm.material_no, NULL::text, dqm.material_no, NULL, NULL, …
     * FROM ds_quote_material dqm
     * WHERE dqm.material_no = ANY(:total_material_no)
     *   AND NOT EXISTS (SELECT 1 FROM ds_quote_material_bom dqmb2
     *                   WHERE dqmb2.input_material_no = dqm.material_no)
     * </pre>
     *
     * <p>🚫 <b>不生成 WITH RECURSIVE</b>（见 {@code compile} 里的说明）。
     * 🚫 <b>不去掉 {@code = ANY(:total_material_no)}</b>：没有它就是整张主档表全捞。
     */
    private String buildTreeRootBranch(Ctx c, List<String> rootExprs) {
        SemanticNode root = c.treeRootNode;
        if (root == null) {
            throw new BuilderApiException(500, "COMPILE_TREE_ROOT_SOURCE_MISSING",
                    "BOM 树页签需要一个「根分支」数据源（成品自身那张主档表），但本数据集（"
                            + c.dialect.graphDialect() + "）在 semantic_tab_view 里没有可用的「"
                            + ROOT_SOURCE_TAB_TYPE + "」页签声明。没有根分支，树顶那一行永远是空白。",
                    Map.of("dialect", c.dialect.graphDialect()));
        }
        String rootAxis = root.anchorExpr.substring(root.anchorExpr.indexOf('.') + 1);
        Set<String> rootCols = c.columnCatalog.getOrDefault(root.physicalTable, Set.of());
        if (!rootCols.contains(rootAxis)) {
            throw new BuilderApiException(500, "COMPILE_TREE_ROOT_AXIS_MISSING",
                    "根分支数据源「" + root.displayName + "」(" + root.physicalTable + ") 没有料号列 "
                            + rootAxis + "，无法按 :total_material_no 收窄 —— 不收窄就是整表全捞。",
                    Map.of("table", root.physicalTable, "axis", rootAxis));
        }
        String rootAlias = allocAlias(c, root.physicalTable);
        String notExistsAlias = allocAlias(c, c.anchor.physicalTable);
        String selfExpr = rootAlias + "." + rootAxis;

        List<String> exprs = new ArrayList<>(rootExprs.size());
        for (String e : rootExprs) exprs.add(TREE_ROOT_SELF_EXPR.equals(e) ? selfExpr : e);

        // 🚨 根分支的收窄必须与主分支**同一套机制**，否则不是恒 0 行就是全表扫：
        //   · 报价侧（无桥）：直接轴收窄 `<根表>.material_no = ANY(:total_material_no)`；
        //   · 核价两套（有桥）：经**同一座料号桥**做半连接。
        //     🚫 这里绝不能退回直接轴收窄 —— 核价的轴是生产料号、:total_material_no 装的是
        //        销售料号，`production_no = ANY(销售料号[])` 恒不命中（0 行且不报错）；
        //     🚫 更不能干脆不发收窄 —— 那是把整张主档表全扫进来。
        c.treeRootWhere.clear();
        c.treeRootWhere.add(rootNarrowPredicate(c, rootAlias, rootAxis, root));
        c.requiredVars.add("total_material_no");

        // 🚨 repair-260908 B-1c(a)（AC-2 / AC-2b）：根分支的**外层** WHERE 同样要按客户收窄。
        //
        // 根分支不经 applyFullScope（它 FROM 的是另一张表、WHERE 是本方法自己拼的）⇒ B-1 覆盖不到。
        // 报价侧根表就是 ds_quote_material，**有 customer_no 且此前完全没过滤** ——
        // 实测 QT-20260908-0624 的 BOM 页签根分支现状 8 行、加谓词后 4 行，另 4 行是别家客户的根行。
        // 这是**活故障**，不是结构隐患。判据同 B-1：按根表物理列存在性，不按方言。
        if (rootCols.contains(CUSTOMER_SCOPE_COLUMN)) {
            c.treeRootWhere.add(rootAlias + "." + CUSTOMER_SCOPE_COLUMN + " = :customerCode");
            c.requiredVars.add("customerCode");
        }

        // 🚨 repair-260908 B-1c(b)（AC-17）：NOT EXISTS 的「有没有父边」判定也要限定同一个客户。
        //
        // ⚠️ **本条的失败方向与本任务其余全部相反 —— 是少行，不是多行**：不带客户约束时，
        // 某成品只要在**别的客户**下挂过 BOM 边，就会被判成「非树根」而从根分支**消失**，
        // 树顶那一行直接空白。⇒ 🚫 不许拿行数验它（全库实测「只在别客户下有父边」的料号 = 0 行，
        // 拿数据验会得到一个**恒绿的判据**，与 AC-16 同性质），只能用结构断言。
        //
        // 🔑 写成**列对列**（子.customer_no = 根.customer_no）而不是 = :customerCode：
        // 根别名那一侧已由上面 (a) 钉死到 :customerCode 上，列对列在语义上等价，
        // 却额外表达了「父边与成品必须属于同一个客户」这条不变量本身 —— 将来 (a) 若因故不发，
        // 这条仍然成立，不会退化成「拿本客户的成品去和任意客户的父边比」。
        String rootParentScope = "";
        Set<String> anchorCols = c.columnCatalog.getOrDefault(c.anchor.physicalTable, Set.of());
        if (anchorCols.contains(CUSTOMER_SCOPE_COLUMN) && rootCols.contains(CUSTOMER_SCOPE_COLUMN)) {
            rootParentScope = " AND " + notExistsAlias + "." + CUSTOMER_SCOPE_COLUMN
                    + " = " + rootAlias + "." + CUSTOMER_SCOPE_COLUMN;
        }

        return "UNION ALL\n"
                + "-- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）\n"
                + "SELECT\n  " + String.join(",\n  ", exprs) + "\n"
                + "FROM " + root.physicalTable + " " + rootAlias + "\n"
                + "WHERE " + String.join(" AND ", c.treeRootWhere) + "\n"
                + "  AND NOT EXISTS (SELECT 1 FROM " + c.anchor.physicalTable + " " + notExistsAlias
                + " WHERE " + notExistsAlias + "." + c.treeChildColumn + " = " + selfExpr
                + rootParentScope + ")\n";
    }

    /**
     * 根分支的收窄谓词：有料号桥就经桥，没有就直接轴收窄。
     *
     * <p><b>经桥时为什么可以把桥的左列换成根表的轴列</b>：桥的左列声明在**锚点**上，
     * 而根分支 FROM 的是另一张表。只有当两者是同一个号段时替换才成立 ——
     * 实测全部 28 条 NARROW 边都是单键且 {@code left=right='production_no'}，
     * 与核价「主件」表的轴列 {@code production_no} 逐字相同。
     * <b>不满足就报错，不猜</b>：猜错的形态是「谓词写得出来、跑得通、返回的却是别的号段的行」。
     */
    private String rootNarrowPredicate(Ctx c, String rootAlias, String rootAxis, SemanticNode root) {
        String selfExpr = rootAlias + "." + rootAxis;
        if (c.narrowEdge == null) {
            return selfExpr + " = ANY(:total_material_no)";
        }
        SemanticNode bridge = c.snap.nodeById.get(c.narrowEdge.toNodeId);
        List<SemanticEdgeKey> keys = c.snap.keysOf(c.narrowEdge.id).stream()
                .sorted(Comparator.comparingInt(k -> k.seq)).toList();
        if (bridge == null || bridge.physicalTable == null || keys.size() != 1) {
            throw new BuilderApiException(500, "COMPILE_TREE_ROOT_BRIDGE_UNUSABLE",
                    "BOM 树页签的根分支要经料号桥收窄，但桥不可用（桥节点缺失或不是单键）："
                            + "keys=" + (keys.isEmpty() ? 0 : keys.size())
                            + "。不经桥的写法只有两种，都是错的：直接轴收窄会因号段不同而恒 0 行，"
                            + "不收窄则是整张主档表全扫。",
                    Map.of("anchorNodeKey", c.anchor.nodeKey, "dialect", c.dialect.graphDialect()));
        }
        String leftCol = keys.get(0).leftColumn;
        if (!leftCol.equals(rootAxis)) {
            throw new BuilderApiException(500, "COMPILE_TREE_ROOT_BRIDGE_COLUMN_MISMATCH",
                    "料号桥的左列是「" + leftCol + "」（声明在锚点上），而根分支数据源「"
                            + root.displayName + "」(" + root.physicalTable + ") 的料号列是「" + rootAxis
                            + "」—— 两者不同名，无法确定它们是同一号段，拒绝按桥收窄。",
                    Map.of("bridgeLeftColumn", leftCol, "rootAxis", rootAxis));
        }
        String sub = allocAlias(c, bridge.physicalTable);
        String inputCol = CompileDialect.QUOTE.axisColumn();   // 桥的入参恒是销售料号
        // 🚨 repair-260908 B-1c（AC-16 同款）：核价侧根分支**自己另拼了一座桥**（同样
        //    FROM ds_quote_material），与 emitNarrowPredicate 那座是两处独立代码 ——
        //    只改那一处、漏掉这一处，就会出现「主分支按客户收窄、根分支不收」的半截状态。
        //    同一判据（桥的物理表含 customer_no 列）一并覆盖。
        Set<String> bridgeCols = c.columnCatalog.getOrDefault(bridge.physicalTable, Set.of());
        String bridgeCustomerScope = "";
        if (bridgeCols.contains(CUSTOMER_SCOPE_COLUMN)) {
            bridgeCustomerScope = " AND " + sub + "." + CUSTOMER_SCOPE_COLUMN + " = :customerCode";
            c.requiredVars.add("customerCode");
        }
        return selfExpr + " IN (SELECT " + sub + "." + keys.get(0).rightColumn
                + " FROM " + bridge.physicalTable + " " + sub
                + " WHERE " + sub + "." + inputCol + " = ANY(:total_material_no)"
                + bridgeCustomerScope + ")";
    }

    /**
     * 树分支 ORDER BY 用的 SORT 输出列名（已加引号）。取<b>已选列里</b>第一个带 SORT 角色的
     * ——没选就没有对应输出列，集合运算的 ORDER BY 引用不到，只能不排（父/子两键已保证确定性）。
     */
    private String findSortOutputAlias(Ctx c, List<BuilderConfig.ColumnConfig> effectiveColumns) {
        for (BuilderConfig.ColumnConfig col : effectiveColumns) {
            if (col.resolvedRoles == null || !col.resolvedRoles.contains("SORT")) continue;
            if (col.viewColumn == null || col.viewColumn.isBlank()) continue;
            return quoteAlias(col.viewColumn);
        }
        return null;
    }

    // ---------------- B-53：`:total_material_no` 单一语义护栏（产物级） ----------------

    /** 直接轴谓词的形态：{@code <别名>.<列> = ANY(:total_material_no)}（{@link #applyFullScope} 产）。 */
    private static final Pattern DIRECT_AXIS_NARROW = Pattern.compile(
            "([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)\\s*=\\s*ANY\\(\\s*:total_material_no\\s*\\)");

    /**
     * 桥半连接的形态：{@code IN (SELECT … = ANY(:total_material_no))}（{@link #emitNarrowPredicate} 产）。
     *
     * <p>🚨 <b>这一个 Pattern 同时充当两处判据</b>——「从 {@link Ctx#anchorWhere} 里认出已知桥」与
     * 「在最终 SQL 文本里数出实际有几处桥」。<b>刻意共用同一条**，就是为了让两处判据不可能漂移：
     * 一旦它们用不同的写法（比如一处认字面量 {@code "IN (SELECT"}、另一处用正则），
     * 将来任何改动只要动了桥的文本形态，就会出现「结构化认不出、文本认得出」的偏差，
     * 而那个偏差过去是被 {@code bridgePredicates.isEmpty() → return} 静默吞掉的。
     *
     * <p><b>中间段用 {@code [^()]} 而不是 {@code [\s\S]}</b>：桥子查询从 {@code IN (SELECT} 到
     * {@code = ANY(} 之间只有 {@code <列> FROM <表> <别名> WHERE <别名>.<列>}，<b>不含任何括号</b>。
     * 用「禁止括号」把匹配锁死在同一层子查询里 ⇒ 一个与本入参无关的 {@code IN (SELECT …)}
     * 不可能跨过自己的右括号、去够上后面某条直接轴谓词的 {@code ANY(:total_material_no)}
     * （那会造成假报警）。同时 {@code \s*} + {@code CASE_INSENSITIVE} 让它不受
     * {@code IN(SELECT} 无空格、换行、大小写的影响。
     */
    private static final Pattern BRIDGE_SEMI_JOIN = Pattern.compile(
            "\\bIN\\s*\\(\\s*SELECT\\b[^()]{0,2000}=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 🚨 <b>护栏（task-260819 B-53，用户 2026-09-04 裁决「护栏做」）</b>：产物里
     * {@code :total_material_no} 只允许被<b>一种</b>语义消费。
     *
     * <p><b>防的是什么</b>：绑定变量 {@code :total_material_no} 只有一个数组，但编译器有两条
     * 会消费它的路径，且要求的号段<b>相反</b>：
     * <ul>
     *   <li>{@link #emitNarrowPredicate} 的桥半连接 —— 数组必须装<b>销售料号</b>
     *       （拿去查 {@code ds_quote_material.material_no}）；</li>
     *   <li>{@link #applyFullScope} 的直接轴谓词 —— 核价方言下轴列是 {@code production_no}，
     *       数组必须装<b>生产料号</b>。</li>
     * </ul>
     * 两者一旦同时出现在同一段产物里，<b>无论数组装哪种号，另一条必然恒不命中 ⇒ 两个条件
     * AND 起来交集为空 ⇒ 查出 0 行，而且不抛异常、不告警、不留任何诊断</b>——这正是 B-52
     * （D-119）的成因，也是 {@code /preview} 第四次同型「静默返空」缺陷。
     *
     * <p><b>为什么要有第二重检查</b>：现行防线是 {@link Ctx#narrowedByBridge} 这个布尔标志位
     * （有桥就不发直接轴谓词）——它是<b>运行时靠一个变量维持</b>的约束，任何一条新增代码路径
     * 漏读它就重新引入同型缺陷，而缺陷本身不报错。本护栏<b>刻意不读那个标志</b>，只看编译产出
     * 的最终 SQL 文本，因而「{@code narrowedByBridge} 维护得对不对」不影响它的判定。
     *
     * <p>⚠️ <b>但「完全不依赖被检查方」是做不到的，别这么宣称</b>（2026-09-04 主线复核纠正）。
     * 本护栏仍然依赖一条性质：<b>桥谓词落在 {@link Ctx#anchorWhere} 里</b>（否则认不出它是桥，
     * 就无法与直接轴谓词区分）。这条性质将来可能被破坏 —— 比如照 {@link #resolveSub} 那条路
     * 把桥发进某个局部 {@code where}。<b>关键不在于消灭这个依赖（消灭不掉），而在于让它被破坏时
     * 「响亮地失败」而不是「安静地放行」</b>：所以
     * {@link #checkAxisParamSingleSemantic} 会把「产物里数出的桥」与「结构化认出的桥」对账，
     * 对不上就抛 {@code COMPILE_AXIS_NARROW_UNCLASSIFIABLE}。
     *
     * <p><b>判定手法（结构化标记 + 产物文本，二者取长）</b>：桥谓词的<b>原文</b>从
     * {@link Ctx#anchorWhere} 按 {@link #BRIDGE_SEMI_JOIN} 形态取（<b>不是</b>按「哪个方法产的」认），
     * 再从最终 SQL 里逐字剔除；剩下的文本中只要还能匹配到直接轴谓词形态即判定冲突。
     * 这样做的两点好处：① 剔除是<b>逐字子串</b>匹配，不需要正则去数括号配对，不会被子查询里
     * 那个 {@code = ANY(...)} 误伤；② 扫描面是<b>整段产物</b>而不只是 {@code anchorWhere}，
     * 因此 {@link #resolveSub} 相关子查询里、{@code JOIN ... ON} 里冒出来的同型谓词一样能抓到。
     */
    private void assertAxisParamSingleSemantic(Ctx c, String finalSql) {
        List<String> narrowEdgeIds = c.snap.edgesFrom(c.anchor.id).stream()
                .filter(e -> "NARROW".equals(e.edgeKind))
                .map(e -> String.valueOf(e.id))
                .toList();
        // 🌳 task-260907 B-3：树页签的**根分支**也会发一条同形态的收窄谓词，它不在 anchorWhere 里。
        //    必须一并登记，否则护栏「产物里数出的桥 vs 结构化认出的桥」对不上，会在**正确实现**上
        //    抛 COMPILE_AXIS_NARROW_UNCLASSIFIABLE。
        //    🚨 这是**登记新的已知形态**，不是放宽判据：条数照样要对得上，剔干净后照样不许有残留。
        List<String> knownAxisPredicates = new ArrayList<>(c.anchorWhere);
        knownAxisPredicates.addAll(c.treeRootWhere);
        checkAxisParamSingleSemantic(knownAxisPredicates, finalSql, c.dialect.axisColumn(),
                String.valueOf(c.dialect), c.anchor.nodeKey, c.anchor.physicalTable, narrowEdgeIds);
    }

    /**
     * 护栏的<b>纯函数内核</b>（包级可见，仅为让 {@code SemanticCompilerAxisNarrowGuardTest} 能直接喂
     * 合成产物驱动它）。不碰 {@link Ctx}、不碰图、不碰 DB ⇒ 它的用例是<b>不启 Quarkus、不连库</b>的
     * 普通 JUnit，因而可以放心把「护栏自己坏没坏」做成常驻回归（共享库红线下这点很关键）。
     *
     * <p>🚨 <b>三条出口，两条都是「响亮失败」而不是放行</b>（2026-09-04 主线复核回流 ①）：
     * <ol>
     *   <li><b>分类不上 ⇒ {@code COMPILE_AXIS_NARROW_UNCLASSIFIABLE}</b>。产物里数出来的桥半连接
     *       条数与从 {@code anchorWhere} 结构化认出来的对不上 ⇒ 说明有桥<b>不在 {@code anchorWhere} 里</b>
     *       （例如将来有人照 {@link #resolveSub} 那条路把桥发进某个局部 {@code where}），
     *       护栏此时<b>无法对产物分类</b>。<b>🚫 绝不能当成「没有桥」放过</b> —— 老写法
     *       {@code if (bridgePredicates.isEmpty()) return;} 恰恰会在这种情况下提前返回、一声不吭，
     *       而 {@code narrowedByBridge} 那套逻辑是独立的、直接轴谓词照发 ⇒ <b>B-52 原样重现，
     *       护栏在旁边看着什么都不说</b>。<b>护栏的失效形态 = 静默 no-op = 它被造出来要防的那件事。</b></li>
     *   <li><b>两种语义共存 ⇒ {@code COMPILE_AXIS_NARROW_CONFLICT}</b>（本护栏的主目标，见类内注释）。</li>
     *   <li><b>冒出第三种形态 ⇒ 同样 {@code UNCLASSIFIABLE}</b>。把已知的两种形态都剔干净后，
     *       文本里居然还在消费 {@code :total_material_no} ⇒ 出现了护栏没见过的用法，
     *       同样拒绝放行而不是假设它无害。</li>
     * </ol>
     *
     * <p>⚠️ <b>为什么不能简化成「数 {@code :total_material_no} 出现几次」</b>：单个锚点可以挂
     * <b>多条 NARROW 边</b>，那时该入参正常就会出现多次，计数法会误报。逐字 {@code replace} 剔除
     * 已知桥能正确处理 N 个桥，保留这个手法。
     */
    static void checkAxisParamSingleSemantic(List<String> knownAxisPredicates, String finalSql,
                                             String axisColumn, String dialect,
                                             String anchorNodeKey, String anchorTable,
                                             List<String> narrowEdgeIds) {
        // ① 结构化认出已知桥；② 数一遍产物里实际有几处 —— 两处判据共用 BRIDGE_SEMI_JOIN，不可能漂移
        // 🌳 task-260907：入参由「锚点 WHERE」扩成「**全部已登记的**轴谓词原文」
        //    （锚点 WHERE + 树页签根分支 WHERE）。语义没变——仍然是「护栏认得出的那些」，
        //    只是树根分支这一种新形态被登记了进来；未登记的形态照旧拒绝放行。
        // 🚨 分析对象必须是**屏蔽掉注释与字符串字面量**的文本，不是原文（task-260907 实测踩到）：
        //    B-3 给树页签产物加了一行说明性头注释，里面原样写着 `:total_material_no`
        //    （照存量 $bom_view 的注释体例）。注释里的参数名**不会被执行**，但下面第 ③ 步是
        //    `contains(":total_material_no")` 的纯文本判断 ⇒ 护栏在**完全正确**的产物上抛
        //    COMPILE_AXIS_NARROW_UNCLASSIFIABLE，核价两套的 BOM 页签直接编译不出来。
        //    ⇒ 屏蔽注释是**修正判据的作用域**（注释本来就不该参与判定），不是放宽判据：
        //      条数照样要对得上、剔干净后照样不许有残留。
        //    📌 报错信息里仍打印**原文** finalSql，屏蔽后的文本只用于分析 —— 排查时要看的是真产物。
        String scanned = com.cpq.datasource.sqlview.SqlTextMask.mask(finalSql);
        List<String> bridgePredicates = knownAxisPredicates.stream()
                .filter(w -> BRIDGE_SEMI_JOIN.matcher(w).find())
                .toList();
        long inArtifact = BRIDGE_SEMI_JOIN.matcher(scanned).results().count();
        if (inArtifact != bridgePredicates.size()) {
            throw new BuilderApiException(500, "COMPILE_AXIS_NARROW_UNCLASSIFIABLE",
                    "护栏无法对编译产物分类，拒绝放行：产物里数出 " + inArtifact + " 处桥半连接收窄，"
                            + "但只能从锚点 WHERE 里结构化认出 " + bridgePredicates.size() + " 处"
                            + "（多出来的桥不在 anchorWhere 里，护栏无法把它与『直接轴收窄』区分开）。"
                            + " 🚫 此处**必须**报错而不是放行：放行等于退回 B-52 那种「两条谓词共存 ⇒ 恒 0 行且不报错」的静默故障。"
                            + " dialect=" + dialect + "；锚点=" + anchorNodeKey + "(" + anchorTable + ")"
                            + "；NARROW 边=" + narrowEdgeIds + "；产物=\n" + finalSql,
                    Map.of("bridgesInArtifact", inArtifact,
                            "bridgesRecognized", bridgePredicates.size(),
                            "dialect", String.valueOf(dialect),
                            "anchorNodeKey", String.valueOf(anchorNodeKey),
                            "narrowEdgeIds", narrowEdgeIds));
        }
        if (bridgePredicates.isEmpty()) return; // 确认过「产物里也一处都没有」⇒ 入参语义唯一，直接轴谓词是正确形态

        String stripped = scanned;
        for (String bridge : bridgePredicates) stripped = stripped.replace(bridge, "");

        Matcher m = DIRECT_AXIS_NARROW.matcher(stripped);
        if (m.find()) {
            String direct = m.group();
            throw new BuilderApiException(500, "COMPILE_AXIS_NARROW_CONFLICT",
                    "编译产物同时含「桥半连接收窄」与「直接轴收窄」，两者对 :total_material_no 的号段要求相反"
                            + "（桥要销售料号、轴列要 " + axisColumn + "）"
                            + " ⇒ 无论数组装哪种号另一条都恒不命中，AND 起来交集为空 ⇒ 静默返 0 行且不报错。"
                            + " 直接轴谓词=[" + direct + "]；桥半连接=" + bridgePredicates
                            + "；dialect=" + dialect
                            + "；锚点=" + anchorNodeKey + "(" + anchorTable + ")"
                            + "；NARROW 边=" + narrowEdgeIds,
                    Map.of("directAxisPredicate", direct,
                            "bridgePredicates", bridgePredicates,
                            "dialect", String.valueOf(dialect),
                            "anchorNodeKey", String.valueOf(anchorNodeKey),
                            "narrowEdgeIds", narrowEdgeIds));
        }

        // ③ 两种已知形态都剔干净了，还在消费该入参 ⇒ 第三种形态，护栏没见过 ⇒ 同样不放行
        if (stripped.contains(":total_material_no")) {
            throw new BuilderApiException(500, "COMPILE_AXIS_NARROW_UNCLASSIFIABLE",
                    "护栏无法对编译产物分类，拒绝放行：剔除已知的「桥半连接」与「直接轴收窄」两种形态后，"
                            + "产物里仍在消费 :total_material_no —— 出现了护栏未知的第三种用法，"
                            + "无法判断它与桥的号段是否相容。"
                            + " dialect=" + dialect + "；锚点=" + anchorNodeKey + "(" + anchorTable + ")"
                            + "；NARROW 边=" + narrowEdgeIds + "；剔除后残留=\n" + stripped,
                    Map.of("bridgePredicates", bridgePredicates,
                            "dialect", String.valueOf(dialect),
                            "anchorNodeKey", String.valueOf(anchorNodeKey),
                            "narrowEdgeIds", narrowEdgeIds));
        }
    }

    /**
     * 该物理源是否具备发 {@code :versionFilter} 宏的条件（AC-109②）——{@link #applyFullScope}
     * 与 {@code view_version} 约定列的输出判据必须<b>逐字同源</b>：两处判据一旦漂移，就会出现
     * 「发了宏但没输出 view_version」（版本下拉恒空）或「输出了 view_version 但没发宏」
     * （切了版本没反应）这两种静默故障，都不报错。
     */
    private boolean emitsVersionFilter(Ctx c, Set<String> cols) {
        return c.dialect.isCosting()
                && cols.contains("is_current")
                && cols.contains("version_no")
                && cols.contains(c.dialect.axisColumn());
    }

    // 📌 已删除的 alreadyScopedByMandatoryJoin(...)（B-41）：它唯一的作用是"customer_no 收窄已由
    // 强制 JOIN 覆盖时锚点不再重复加 WHERE"，而 customer_no 收窄本身已随 V6 三件套整块删除
    // （ds_* 45 张表没有这一列）。留一个再也不会被调用、且描述的是已废弃谓词的判据方法，
    // 下一个人照它推断"编译器还会发 customer_no"就是错的。

    // ---------------- 价格策略原子组（B-9，D-09） ----------------

    private static final class PricePlan {
        String joinClause;
        String elementCodeSourceColumn; // anchor 自己的编码列名（形态 A 时非空）
        SemanticNode funcNode;          // B-41：顺 PRICE 边解析出的价格函数节点（替代按 key+"|QUOTE" 反查）
    }

    /**
     * 是否为「价格策略原子组」的输出列。
     *
     * <p>判据是 {@code col.sourceNodeKey} 是否等于<b>本次解析出的那个</b>价格函数节点
     * —— 🚫 不是跟某个常量比。同一份图里可以有多个价格函数节点（当前 QUOTE 方言有两个：
     * 按料号的 {@code FUNC_ELEMENT_PRICE} 与按客户的 {@code FUNC_CUSTOMER_ELEMENT_PRICE}），
     * 用常量比会把「另一个」的列漏判成普通列，然后在 resolveColumn 里当作锚点列去找，
     * 要么报一个语义完全不相干的错，要么静默输出空列。
     *
     * <p>{@code plan == null}（没选价格列、或形态 B 已把价格列摘掉）时恒 false。
     */
    private boolean isPriceColumn(PricePlan plan, BuilderConfig.ColumnConfig col) {
        // 🚩 `plan != null &&` 这一段不是多余的防御，它是**结构性免疫**：
        //    判据只由「本次真的解析出来的 plan」决定，plan 为 null 时恒 false。
        //    合并时 B-8 侧的写法是 isPriceNodeKey(col.sourceNodeKey, plan == null ? null : ...)，
        //    其中历史键分支在 plan == null 时仍可能返 true ⇒ 上面第一个输出循环
        //    （`if (isPriceColumn(...)) continue;`）会把该列**静默丢掉**，而第二个循环被
        //    `if (pricePlan != null)` 挡住不会补输出它。
        //    实测该形态在 B-8 自己的代码里不可达（见合并回报的可达性分析），但它的不可达
        //    依赖的是**另一个方法**维持的不变量 —— 那种「今天不可达」不是安全，是欠债。
        return plan != null && plan.funcNode != null
                && plan.funcNode.nodeKey.equals(col.sourceNodeKey);
    }

    /**
     * 解析价格策略绑定；若用户选了「元素单价/货币」但没显式带上编码列，自动把编码列插入
     * {@code effectiveColumns}（AC-2①「7 项」的来源，也是 D-09 原子组"拖一列自动带出"的落地点）。
     */
    private PricePlan resolvePricePlan(Ctx c, List<BuilderConfig.ColumnConfig> effectiveColumns) {
        // ── ① 顺锚点的 PRICE 边，列出「本锚点可用的价格函数节点」
        //    🚨 这里**绝不能用 findFirst()**。原实现是
        //        edgesFrom(anchor).filter(PRICE).findFirst()
        //    —— 同一锚点挂两条 PRICE 边时它按遍历顺序碰运气取一条，取错了也不报错。
        //    这是本项目反复出现的反模式（refreshSnapshotsByComponent 的 firstResult()、
        //    semantic_tab_view 三段坐标只用两段）。⇒ 按 builder_config 里列引用的函数节点
        //    **精确匹配**，多一条少一条都有明确的错误码。
        Map<String, SemanticEdge> priceEdgeByFuncKey = new LinkedHashMap<>();
        for (SemanticEdge e : c.snap.edgesFrom(c.anchor.id)) {
            if (!"PRICE".equals(e.edgeKind)) continue;
            SemanticNode to = c.snap.nodeById.get(e.toNodeId);
            if (to == null) {
                throw new BuilderApiException(500, "COMPILE_PRICE_FUNC_NODE_MISSING",
                        "价格策略边指向的函数节点不存在（图数据不一致）", Map.of());
            }
            SemanticEdge dup = priceEdgeByFuncKey.putIfAbsent(to.nodeKey, e);
            if (dup != null) {
                // 同一锚点 → 同一函数节点有两条 PRICE 边：图数据本身有歧义，
                // 此时无论选哪条都是碰运气 ⇒ 直接拒绝，不许猜。
                throw new BuilderApiException(400, "COMPILE_PRICE_EDGE_DUPLICATED",
                        "锚点「" + c.anchor.displayName + "」到价格函数「" + to.nodeKey
                                + "」存在多条 PRICE 边，无法确定用哪条", Map.of());
            }
        }

        // ── ② 本次选列引用了哪些价格函数节点
        LinkedHashSet<String> selectedFuncKeys = new LinkedHashSet<>();
        for (BuilderConfig.ColumnConfig col : effectiveColumns) {
            if (col.sourceNodeKey != null && priceEdgeByFuncKey.containsKey(col.sourceNodeKey)) {
                selectedFuncKeys.add(col.sourceNodeKey);
            }
        }

        if (selectedFuncKeys.isEmpty()) {
            // 没选价格列。但要区分「真没选」和「选了某个 FUNCTION 节点的列、锚点却没有对应 PRICE 边」——
            // 后者若静默 return null，那列会掉进普通列分支被当成锚点列去找，
            // 报出来的错与真实原因毫不相干。⇒ 在这里就点名。
            for (BuilderConfig.ColumnConfig col : effectiveColumns) {
                if (col.sourceNodeKey == null) continue;
                SemanticNode n = c.snap.nodeByKeyDialect.get(
                        col.sourceNodeKey + "|" + c.dialect.graphDialect());
                if (n != null && "FUNCTION".equals(n.nodeKind)) {
                    throw new BuilderApiException(400, "COMPILE_PRICE_EDGE_NOT_FOUND",
                            "锚点「" + c.anchor.displayName + "」没有指向价格函数「"
                                    + col.sourceNodeKey + "」的价格策略边", Map.of());
                }
            }
            return null;
        }
        if (selectedFuncKeys.size() > 1) {
            // 一个组件同时选两个价格函数的列：JOIN 别名 cep 只有一个，且两组价格语义不同，
            // 合成一张视图没有业务含义 ⇒ 拒绝，而不是悄悄只生效一个。
            throw new BuilderApiException(400, "COMPILE_PRICE_MULTI_FUNC",
                    "同一组件不能同时使用多个价格函数：" + selectedFuncKeys, Map.of());
        }

        String funcKey = selectedFuncKeys.iterator().next();
        SemanticEdge priceEdge = priceEdgeByFuncKey.get(funcKey);
        SemanticNode funcNode = c.snap.nodeById.get(priceEdge.toNodeId);
        List<SemanticEdgeKey> keys = c.snap.keysOf(priceEdge.id).stream()
                .sorted(Comparator.comparingInt(k -> k.seq)).toList();
        if (keys.isEmpty()) {
            throw new BuilderApiException(400, "COMPILE_PRICE_EDGE_NO_KEYS", "价格策略边缺少连接键", Map.of());
        }

        BuilderConfig.PriceStrategyConfig ps = c.cfg.priceStrategy;
        if (ps != null && ps.elementCodeManualField != null && !ps.elementCodeManualField.isBlank()) {
            // 形态 B（AC-23）：元素键改绑手填字段，SQL 不再输出价格策略——既有 element_code_field/
            // element_price_field 运行时定价机制（task-0729）接管，本编译器不生成 JOIN。
            effectiveColumns.removeIf(col -> funcKey.equals(col.sourceNodeKey));
            return null;
        }

        PricePlan plan = new PricePlan();
        plan.funcNode = funcNode;
        // key[0]：编码键，字面量列引用；key[1..]：与 hf_part_no 表达式逐字一致（AC-1⑤/AC-3⑤）——
        // D-50/D-56 后 hf_part_no 恒为锚点自身列，不再有闭包分支。
        SemanticEdgeKey codeKey = keys.get(0);
        plan.elementCodeSourceColumn = codeKey.leftColumn;
        String codeExpr = c.anchorAlias + "." + codeKey.leftColumn;

        List<String> on = new ArrayList<>();
        on.add(PRICE_FUNC_ALIAS + "." + codeKey.rightColumn + " = " + codeExpr);
        if (keys.size() > 1) {
            // repair-260909 B-1（AC-P2 / AC-R1）：第 2..N 键的左侧改用 leftColumn，
            // 与同方法 key[0] 的 codeExpr、以及普通边 ensureLeftJoin（:502）的
            // `alias.rightColumn = anchorAlias.leftColumn` 三处彻底对齐。
            //
            // 🔑 这是抹掉一处不一致，不是加机制：原实现把左侧写死成 anchor_expr，
            //    leftColumn 被完全忽略 ⇒ 连接键只能是锚点自身的那一列。
            //
            // ⚠️ 原处注释写着「task-260907 B-8 后 QUOTE 侧只剩 element_code 一个键，
            //    走不到这里」—— **该注释已过期**：2026-09-09 实测共享库，报价侧
            //    semantic_edge_key 就是 2 行（element_code / material_no），8 个已配价格列的
            //    组件全部天天走这条分支。🚫 不要相信那句话而跳过报价侧回归。
            //
            // ✅ 报价侧字节等价已实测（AC-R1）：key[1].leftColumn='material_no'、
            //    anchor_expr='dqeb.material_no'、anchorAlias='dqeb' ⇒ 两种写法同串 SQL；
            //    8/8 样本 diff 为空，且先做过灵敏度实验（故意写错列名 ⇒ diff 出 40 行）。
            for (int i = 1; i < keys.size(); i++) {
                on.add(PRICE_FUNC_ALIAS + "." + keys.get(i).rightColumn
                        + " = " + c.anchorAlias + "." + keys.get(i).leftColumn);
            }
        }
        plan.joinClause = "LEFT JOIN " + funcNode.funcSignature + " " + PRICE_FUNC_ALIAS +
                " ON " + String.join(" AND ", on);

        // 确保编码列本身也作为一个普通输出列存在（用户没手拖时自动补上）
        boolean codeColSelected = effectiveColumns.stream().anyMatch(col ->
                c.anchor.nodeKey.equals(col.sourceNodeKey) && codeKey.leftColumn.equals(col.sourceColumn));
        if (!codeColSelected) {
            SemanticNodeColumn codeCol = findColumn(c, c.anchor, codeKey.leftColumn);
            BuilderConfig.ColumnConfig auto = new BuilderConfig.ColumnConfig(
                    c.anchor.nodeKey, codeKey.leftColumn, codeCol.displayName);
            effectiveColumns.add(0, auto);
        }
        return plan;
    }

    // ---------------- 锚点表达式重限定 + BOM 闭包 CTE ----------------

    private String requalifyAnchorExpr(Ctx c) {
        if (c.anchor.anchorExpr == null) {
            throw new BuilderApiException(400, "COMPILE_ANCHOR_EXPR_MISSING",
                    "节点「" + c.anchor.displayName + "」未声明 anchor_expr，不能作为页签锚点", Map.of());
        }
        // 种子声明里的 anchor_expr 已经用与本编译器同一套确定性别名规则写死（如 ebi.material_no），
        // 与本次 allocAlias() 算出的 anchorAlias 理应逐字相同——不相同时说明别名规则漂移，直接报错
        // 比静默生成错列引用更安全。
        String declaredAlias = c.anchor.anchorExpr.split("\\.")[0];
        if (!declaredAlias.equals(c.anchorAlias)) {
            throw new BuilderApiException(500, "COMPILE_ALIAS_DRIFT",
                    "锚点别名与声明不一致：声明=" + declaredAlias + " 实算=" + c.anchorAlias, Map.of());
        }
        return c.anchor.anchorExpr;
    }

    /** 锚点节点自身列里第一个带 SORT 角色（两层合并后）的列，取不到返回 null（不强行拼接）。 */
    private String findSortColumn(Ctx c) {
        for (SemanticNodeColumn col : c.snap.columnsOf(c.anchor.id)) {
            if (mergedRoles(c, col).contains("SORT")) {
                return c.anchorAlias + "." + col.dbColumn;
            }
        }
        return null;
    }

    private String anchorColumnOnly(Ctx c) {
        String[] parts = c.anchor.anchorExpr.split("\\.", 2);
        return c.anchorAlias + "." + parts[parts.length - 1];
    }

    /**
     * 🛑 停用（task-260819 B-5，D-50）：A 机制（各页签自建递归闭包）已被 B 机制
     * （{@code = ANY(:total_material_no)}，主树供数组）统一取代，本方法不再被 {@link #compile}
     * 的任何路径调用。保留方法体不删——是否物理删除由主线在收尾时裁决。
     */
    private String closureCte(String whitelistTable) {
        return "WITH RECURSIVE bom_closure AS (\n" +
                "  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl\n" +
                "  FROM material_bom_item b\n" +
                "  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode\n" +
                "  UNION\n" +
                "  SELECT c.root_no, b.component_no, c.lvl + 1\n" +
                "  FROM bom_closure c\n" +
                "    JOIN material_bom_item b ON b.material_no = c.node_no\n" +
                "  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode\n" +
                "    AND c.lvl < 10\n" +
                "), bom_closure_d AS (\n" +
                "  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no\n" +
                ")\n";
    }

    /**
     * 输出列名去重（task-260819 B-47，主线 2026-09-03 裁决）。
     *
     * <p><b>问题</b>：核价两套的别名规则是「裸英文 {@code dbColumn}」（AC-110），而不同节点完全
     * 可能有同名列——实测 {@code COST_BASIC} 主件同时选主表与料号桥的 {@code material_name} 时，
     * {@code declaredColumns} 出现两个 {@code material_name}。<b>PG 允许 SELECT 输出重复列名</b>，
     * 所以 SQL 跑得通、dry-run 也过；但渲染链路按「列名 → 值」的 Map 读行，
     * <b>后写的会覆盖先写的，静默丢一列</b>（本项目 AP-22 那一族的同型失败）。
     *
     * <p><b>规则</b>：<b>首次出现的保持裸名不变</b>（AC-110 既有断言零回归），后出现的加
     * {@code _<节点短名>} 后缀；后缀本身再撞（同一短名下同名列）就继续追加序号，直到唯一。
     * 报价侧别名带 {@code _<短名>_} 前缀、本就几乎不会撞，但同样走这条路径——<b>不做"只在核价侧
     * 去重"的分叉</b>：撞名是输出层的事实问题，与方言无关，分叉只会制造一个只在一侧存在的漏洞。
     *
     * <p>⚠️ 纯函数性质不变（AC-11②）：去重只依赖「本次已产出的别名序列」，同一份
     * {@code builder_config} 任何时候编译，列的遍历顺序相同 ⇒ 结果逐字相同。
     */
    private String dedupeAlias(Ctx c, String alias, String shortName) {
        if (c.usedAliases.add(alias)) return alias;
        String candidate = alias + "_" + (shortName == null || shortName.isBlank() ? "x" : shortName);
        int n = 2;
        while (!c.usedAliases.add(candidate)) {
            candidate = alias + "_" + (shortName == null || shortName.isBlank() ? "x" : shortName) + n;
            n++;
        }
        return candidate;
    }

    /**
     * 列别名一律双引号包裹（实测坑，非规范条款直接要求）：PG 对**未加引号**的标识符按
     * ASCII 范围折叠成小写——{@code shortName} 里混了英文缩写时（如"元素BOM"），生成的裸别名
     * {@code _元素BOM_组成含量} 实际执行后 JDBC 拿到的列名会变成 {@code _元素bom_组成含量}，
     * 与 {@code declaredColumns}/{@code default_source.path} 里保存的原始大小写字符串**不再逐字
     * 相等**——渲染主链路按列名比对会静默取不到值。双引号强制保留原始大小写，一次性堵死
     * 整类风险，不必要求上游"短名称不能含 ASCII 字母"这种脆弱约定。
     */
    private static String quoteAlias(String alias) {
        return "\"" + alias.replace("\"", "\"\"") + "\"";
    }

    // ---------------- 判别式 / fixedPredicate 限定符前缀（简单单列谓词） ----------------

    private static final Pattern LEADING_IDENT = Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_]*)");

    private static String qualify(String alias, String predicate) {
        Matcher m = LEADING_IDENT.matcher(predicate);
        if (!m.find()) return predicate;
        return predicate.substring(0, m.start(1)) + alias + "." + predicate.substring(m.start(1));
    }

    // ---------------- 改写器兼容性自检（AC-9） ----------------

    private boolean checkRewriterCompatible(String sql, String anchorTable) {
        if (!QuotePendingRewriter.WHITELIST_TABLES.contains(anchorTable)) {
            // 锚点本就不是版本化白名单表（如 material_master）——不适用改写器锚点注入，视为兼容。
            return true;
        }
        Pattern p = Pattern.compile("\\bFROM\\s+" + Pattern.quote(anchorTable) + "\\b", Pattern.CASE_INSENSITIVE);
        return p.matcher(sql).find();
    }
}
