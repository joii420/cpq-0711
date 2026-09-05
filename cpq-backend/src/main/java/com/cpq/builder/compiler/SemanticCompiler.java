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
    private static final String PRICE_FUNC_NODE_KEY = "FUNC_ELEMENT_PRICE";

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
    }

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

        // 收集本次涉及的全部物理表（anchor + 直接边目标 + 价格函数节点忽略，函数无物理表）
        Set<String> tables = new LinkedHashSet<>();
        tables.add(c.anchor.physicalTable);
        for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
            SemanticNode to = snap.nodeById.get(e.toNodeId);
            if (to != null && to.physicalTable != null) tables.add(to.physicalTable);
        }
        c.columnCatalog = catalog.columnsOf(tables);

        c.anchorAlias = allocAlias(c, c.anchor.physicalTable);

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

        // 逐列编译 SELECT 表达式
        List<String> selectExprs = new ArrayList<>();
        List<String> declaredColumns = new ArrayList<>();
        for (BuilderConfig.ColumnConfig col : effectiveColumns) {
            if (isPriceColumn(pricePlan, col)) continue; // 价格策略列单独在下面统一输出
            ResolvedColumn rc = resolveColumn(c, col.sourceNodeKey, col.sourceColumn);
            String alias = dedupeAlias(c,
                    AliasGenerator.viewColumn(dialect, rc.node.shortName, rc.column.displayName, rc.column.dbColumn),
                    rc.node.shortName);
            selectExprs.add(rc.expr + " AS " + quoteAlias(alias));
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
        String anchorExpr = requalifyAnchorExpr(c);
        String hfExpr = anchorExpr;
        selectExprs.add(0, hfExpr + " AS hf_part_no");
        declaredColumns.add(0, "hf_part_no");

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
        sql.append("SELECT\n  ").append(String.join(",\n  ", selectExprs)).append("\n");
        sql.append("FROM ").append(c.anchor.physicalTable).append(" ").append(c.anchorAlias).append("\n");
        for (String j : c.joinClauses) sql.append("  ").append(j).append("\n");
        if (!c.anchorWhere.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", c.anchorWhere)).append("\n");
        }
        // D-45①（2026-08-21 主线裁决）：PG 没有 ORDER BY 的行序是未定义的——必须排序。判据是
        // "golden 行序与基准一致"，不是"加了 ORDER BY 就算数"（golden 实测见 backtask 回报）。键的
        // 构成参照基准 mc_view：ORDER BY ebi.material_no, ebi.material_part_no, ebi.seq_no —— 锚点列
        // 打头（D-50 后闭包层级列已随 A 机制一并停用），随后接锚点节点自身 grain_columns（逐列，按
        // 声明顺序），最后接该节点带 SORT 角色的列（如有）。
        List<String> orderCols = new ArrayList<>();
        orderCols.add(anchorColumnOnly(c));
        for (String grainCol : c.anchor.grainColumns) {
            orderCols.add(c.anchorAlias + "." + grainCol);
        }
        String sortCol = findSortColumn(c);
        if (sortCol != null) orderCols.add(sortCol);
        sql.append("ORDER BY ").append(String.join(", ", orderCols));

        String finalSql = sql.toString();

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
                .orElseThrow(() -> new BuilderApiException(400, "COMPILE_TABVIEW_NOT_FOUND",
                        "未找到页签视图: " + cfg.tabType + "/" + vk + "（数据集 " + dl + "）", Map.of()));
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

        String sub = allocAlias(c, target.physicalTable);
        String left = keys.size() == 1
                ? c.anchorAlias + "." + keys.get(0).leftColumn
                : "(" + keys.stream().map(k -> c.anchorAlias + "." + k.leftColumn)
                        .reduce((a, b) -> a + ", " + b).orElseThrow() + ")";
        String right = keys.stream().map(k -> sub + "." + k.rightColumn)
                .reduce((a, b) -> a + ", " + b).orElseThrow();

        c.anchorWhere.add(left + " IN (SELECT " + right
                + " FROM " + target.physicalTable + " " + sub
                + " WHERE " + sub + "." + inputCol + " = ANY(:total_material_no))");
        c.requiredVars.add("total_material_no");
        // 轴收窄的职责就此移交给本谓词，applyFullScope 不再另发一条（见该方法注释）
        c.narrowedByBridge = true;
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
        // 作为锚点直接使用（外购件 / BOM 树两个页签都以 MATERIAL_BOM 为锚点）
        if ("外购件".equals(c.tabView.tabType)) return "characteristic = 'OUTSOURCED'";
        return null; // BOM 树：不过滤（AC-6②）
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
        if (!c.narrowedByBridge && cols.contains(axis)) {
            where.add(alias + "." + axis + " = ANY(:total_material_no)");
            c.requiredVars.add("total_material_no");
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

    private boolean isPriceColumn(PricePlan plan, BuilderConfig.ColumnConfig col) {
        return PRICE_FUNC_NODE_KEY.equals(col.sourceNodeKey);
    }

    /**
     * 解析价格策略绑定；若用户选了「元素单价/货币」但没显式带上编码列，自动把编码列插入
     * {@code effectiveColumns}（AC-2①「7 项」的来源，也是 D-09 原子组"拖一列自动带出"的落地点）。
     */
    private PricePlan resolvePricePlan(Ctx c, List<BuilderConfig.ColumnConfig> effectiveColumns) {
        boolean priceSelected = effectiveColumns.stream().anyMatch(col -> PRICE_FUNC_NODE_KEY.equals(col.sourceNodeKey));
        if (!priceSelected) return null;

        SemanticEdge priceEdge = c.snap.edgesFrom(c.anchor.id).stream()
                .filter(e -> "PRICE".equals(e.edgeKind))
                .findFirst()
                .orElseThrow(() -> new BuilderApiException(400, "COMPILE_PRICE_EDGE_NOT_FOUND",
                        "锚点「" + c.anchor.displayName + "」没有声明价格策略边", Map.of()));
        SemanticNode funcNode = c.snap.nodeById.get(priceEdge.toNodeId);
        if (funcNode == null) {
            throw new BuilderApiException(500, "COMPILE_PRICE_FUNC_NODE_MISSING",
                    "价格策略边指向的函数节点不存在（图数据不一致）", Map.of());
        }
        List<SemanticEdgeKey> keys = c.snap.keysOf(priceEdge.id).stream()
                .sorted(Comparator.comparingInt(k -> k.seq)).toList();
        if (keys.isEmpty()) {
            throw new BuilderApiException(400, "COMPILE_PRICE_EDGE_NO_KEYS", "价格策略边缺少连接键", Map.of());
        }

        BuilderConfig.PriceStrategyConfig ps = c.cfg.priceStrategy;
        if (ps != null && ps.elementCodeManualField != null && !ps.elementCodeManualField.isBlank()) {
            // 形态 B（AC-23）：元素键改绑手填字段，SQL 不再输出价格策略——既有 element_code_field/
            // element_price_field 运行时定价机制（task-0729）接管，本编译器不生成 JOIN。
            effectiveColumns.removeIf(col -> PRICE_FUNC_NODE_KEY.equals(col.sourceNodeKey));
            return null;
        }

        PricePlan plan = new PricePlan();
        plan.funcNode = funcNode;
        // key[0]：编码键，字面量列引用；key[1..]：与 hf_part_no 表达式逐字一致（AC-1⑤/AC-3⑤）——
        // D-50/D-56 后 hf_part_no 恒为锚点自身列，不再有闭包分支。
        SemanticEdgeKey codeKey = keys.get(0);
        plan.elementCodeSourceColumn = codeKey.leftColumn;
        String codeExpr = c.anchorAlias + "." + codeKey.leftColumn;
        String hfExprForJoin = requalifyAnchorExpr(c);

        List<String> on = new ArrayList<>();
        on.add(PRICE_FUNC_ALIAS + "." + codeKey.rightColumn + " = " + codeExpr);
        for (int i = 1; i < keys.size(); i++) {
            on.add(PRICE_FUNC_ALIAS + "." + keys.get(i).rightColumn + " = " + hfExprForJoin);
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
