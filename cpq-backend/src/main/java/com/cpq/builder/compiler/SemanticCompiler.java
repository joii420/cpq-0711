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
 * <p>三条闭包铁律（照抄 {@code ll_view}/{@code mc_view} 现网注释，见 backtask.md B-5）：
 * ① 白名单表必须在顶层 FROM；② 必须 {@code LEFT JOIN bom_closure_d} + {@code COALESCE} 兜底；
 * ③ 闭包 CTE 用 {@code UNION} 去重（不能 {@code UNION ALL}），且 {@code (root,node)} 用
 * {@code MIN(lvl)} 唯一化。版本化表（{@code unit_price} 等）禁止 {@code LEFT JOIN} 直连
 * ——图声明已经把这类关系登记成 {@code SUB}（相关标量子查询）而不是 {@code LOOKUP}，编译器
 * 只需老实按 {@code edge_kind} 分支，不需要另行猜哪张表"危险"。
 *
 * <p>N+1 自检：单次 compile() 调用只有一条 {@link PhysicalColumnCatalog#columnsOf} SQL
 * （一次性查完本次涉及的全部物理表列名），其余全是内存图遍历（{@link SemanticGraphSnapshot}
 * 已是不可变内存快照）——SQL 条数与已选列数/图节点数无关，恒为 1。
 */
@ApplicationScoped
public class SemanticCompiler {

    public static final int CURRENT_VERSION = 1;

    private static final String CLOSURE_ALIAS = "cl";
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
        boolean closure;
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
    }

    public CompileResult compile(SemanticGraphSnapshot snap, BuilderConfig cfg, CompileDialect dialect) {
        Ctx c = new Ctx();
        c.snap = snap;
        c.dialect = dialect;
        c.cfg = cfg;

        c.tabView = resolveTabView(snap, cfg);
        c.anchor = snap.nodeById.get(c.tabView.anchorNodeId);
        if (c.anchor == null) {
            throw new BuilderApiException(400, "COMPILE_ANCHOR_MISSING", "页签视图的锚点节点不存在", Map.of());
        }
        c.closure = containsSwitch(c.tabView.switches, "CLOSURE") && cfg.includeChildParts();

        // 收集本次涉及的全部物理表（anchor + 直接边目标 + 价格函数节点忽略，函数无物理表）
        Set<String> tables = new LinkedHashSet<>();
        tables.add(c.anchor.physicalTable);
        for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
            SemanticNode to = snap.nodeById.get(e.toNodeId);
            if (to != null && to.physicalTable != null) tables.add(to.physicalTable);
        }
        c.columnCatalog = catalog.columnsOf(tables);

        c.anchorAlias = allocAlias(c, c.anchor.physicalTable);

        // 有效列 = 用户已选列 + 价格策略自动带出的成员
        List<BuilderConfig.ColumnConfig> effectiveColumns = new ArrayList<>(
                cfg.columns == null ? List.of() : cfg.columns);
        PricePlan pricePlan = resolvePricePlan(c, effectiveColumns);

        // 强制 JOIN（edge_kind=JOIN，如「主件」页签的客户料号收窄）——无论是否被选列引用都必须出现
        for (SemanticEdge e : snap.edgesFrom(c.anchor.id)) {
            if (!"JOIN".equals(e.edgeKind)) continue;
            emitMandatoryJoin(c, e);
        }

        // 逐列编译 SELECT 表达式
        List<String> selectExprs = new ArrayList<>();
        List<String> declaredColumns = new ArrayList<>();
        for (BuilderConfig.ColumnConfig col : effectiveColumns) {
            if (isPriceColumn(pricePlan, col)) continue; // 价格策略列单独在下面统一输出
            ResolvedColumn rc = resolveColumn(c, col.sourceNodeKey, col.sourceColumn);
            String alias = AliasGenerator.viewColumn(dialect, rc.node.shortName, rc.column.displayName, rc.column.dbColumn);
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
                SemanticNodeColumn funcCol = findColumn(c, c.snap.nodeByKeyDialect.get(PRICE_FUNC_NODE_KEY + "|QUOTE"), dbCol);
                String bare = AliasGenerator.bareColumn(col.fieldName != null ? col.fieldName : funcCol.displayName);
                selectExprs.add(PRICE_FUNC_ALIAS + "." + dbCol + " AS " + quoteAlias(bare));
                declaredColumns.add(bare);
                col.viewColumn = bare;
                col.resolvedDataType = funcCol.dataType;
                col.resolvedRoles = mergedRoles(c, funcCol);
                if (col.fieldName == null || col.fieldName.isBlank()) col.fieldName = funcCol.displayName;
            }
        }

        // hf_part_no 表达式（闭包感知）
        String anchorExpr = requalifyAnchorExpr(c);
        String hfExpr = c.closure ? "COALESCE(" + CLOSURE_ALIAS + ".root_no, " + anchorExpr + ")" : anchorExpr;
        selectExprs.add(0, hfExpr + " AS hf_part_no");
        declaredColumns.add(0, "hf_part_no");

        // 锚点自身三件套 + 判别式
        applyFullScope(c, c.anchor, c.anchorAlias, c.anchorWhere);
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

        // FROM / closure CTE
        StringBuilder sql = new StringBuilder();
        if (c.closure) {
            sql.append(closureCte(c.anchor.physicalTable));
        }
        sql.append("SELECT\n  ").append(String.join(",\n  ", selectExprs)).append("\n");
        sql.append("FROM ").append(c.anchor.physicalTable).append(" ").append(c.anchorAlias).append("\n");
        if (c.closure) {
            sql.append("  LEFT JOIN bom_closure_d ").append(CLOSURE_ALIAS)
               .append(" ON ").append(CLOSURE_ALIAS).append(".node_no = ").append(anchorColumnOnly(c)).append("\n");
        }
        for (String j : c.joinClauses) sql.append("  ").append(j).append("\n");
        if (!c.anchorWhere.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", c.anchorWhere)).append("\n");
        }
        // D-45①（2026-08-21 主线裁决）：PG 没有 ORDER BY 的行序是未定义的——不能只在闭包分支排序，
        // 非闭包也必须排。判据是"golden 行序与基准一致"，不是"加了 ORDER BY 就算数"（golden 实测
        // 见 backtask 回报）。键的构成参照基准 mc_view：ORDER BY ebi.material_no, ebi.material_part_no,
        // ebi.seq_no —— 锚点列打头，闭包开启时前面再加 COALESCE(cl.lvl,0)（层级优先，原逻辑保留），
        // 随后接锚点节点自身 grain_columns（逐列，按声明顺序），最后接该节点带 SORT 角色的列（如有）。
        List<String> orderCols = new ArrayList<>();
        if (c.closure) orderCols.add("COALESCE(" + CLOSURE_ALIAS + ".lvl, 0)");
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
        return result;
    }

    // ---------------- 页签视图解析 ----------------

    private SemanticTabView resolveTabView(SemanticGraphSnapshot snap, BuilderConfig cfg) {
        String vk = cfg.variantKey == null ? "" : cfg.variantKey;
        return snap.tabViews.stream()
                .filter(t -> t.tabType.equals(cfg.tabType) && t.variantKey.equals(vk))
                .findFirst()
                .orElseThrow(() -> new BuilderApiException(400, "COMPILE_TABVIEW_NOT_FOUND",
                        "未找到页签视图: " + cfg.tabType + "/" + vk, Map.of()));
    }

    private boolean containsSwitch(String[] switches, String name) {
        if (switches == null) return false;
        for (String s : switches) if (name.equals(s)) return true;
        return false;
    }

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

    // ---------------- 单列解析 ----------------

    private static final class ResolvedColumn {
        SemanticNode node;   // 值实际所在的节点（用于别名前缀 shortName）
        SemanticNodeColumn column;
        String expr;         // 完整 SQL 表达式（含别名限定或子查询）
    }

    private ResolvedColumn resolveColumn(Ctx c, String sourceNodeKey, String sourceColumn) {
        SemanticNode target = c.snap.nodeByKeyDialect.get(sourceNodeKey + "|QUOTE");
        if (target == null) {
            throw new BuilderApiException(400, "COMPILE_COLUMN_SOURCE_UNKNOWN",
                    "未知的列来源节点: " + sourceNodeKey, Map.of());
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

    // ---------------- 三件套收窄（is_current / system_type / customer_no，按真实列存在与否决定） ----------------

    private void applyFullScope(Ctx c, SemanticNode node, String alias, List<String> where) {
        Set<String> cols = c.columnCatalog.getOrDefault(node.physicalTable, Set.of());
        if (c.dialect == CompileDialect.QUOTE) {
            if (cols.contains("system_type")) where.add(alias + ".system_type = 'QUOTE'");
            if (cols.contains("is_current")) where.add(alias + ".is_current");
            if (cols.contains("customer_no") && !alreadyScopedByMandatoryJoin(c, node)) {
                where.add(alias + ".customer_no = :customerCode");
                c.requiredVars.add("customerCode");
            }
        } else {
            // COSTING（AC-37）：:versionFilter(...) 宏收窄 + code = ANY(:total_material_no)
            if (cols.contains("is_current") && cols.contains("version_no") && cols.contains("code")) {
                where.add(":versionFilter(" + alias + ".is_current, " + alias + ".version_no, " + alias + ".code)");
            }
            if (cols.contains("code")) {
                where.add(alias + ".code = ANY(:total_material_no)");
                c.requiredVars.add("total_material_no");
            }
        }
    }

    /** 客户维度已经由强制 JOIN（edge_kind=JOIN 的 fixedPredicate）覆盖时，锚点自己不再重复加 WHERE。 */
    private boolean alreadyScopedByMandatoryJoin(Ctx c, SemanticNode node) {
        if (!node.id.equals(c.anchor.id)) return false;
        return c.snap.edgesFrom(c.anchor.id).stream().anyMatch(e -> "JOIN".equals(e.edgeKind)
                && c.snap.nodeById.get(e.toNodeId) != null
                && c.snap.nodeById.get(e.toNodeId).fixedPredicate != null
                && c.snap.nodeById.get(e.toNodeId).fixedPredicate.contains("customer_no"));
    }

    // ---------------- 价格策略原子组（B-9，D-09） ----------------

    private static final class PricePlan {
        String joinClause;
        String elementCodeSourceColumn; // anchor 自己的编码列名（形态 A 时非空）
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
        // key[0]：编码键，字面量列引用；key[1..]：与 hf_part_no 表达式逐字一致（AC-1⑤/AC-3⑥）
        SemanticEdgeKey codeKey = keys.get(0);
        plan.elementCodeSourceColumn = codeKey.leftColumn;
        String codeExpr = c.anchorAlias + "." + codeKey.leftColumn;
        String hfExprForJoin = c.closure
                ? "COALESCE(" + CLOSURE_ALIAS + ".root_no, " + requalifyAnchorExpr(c) + ")"
                : requalifyAnchorExpr(c);

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
