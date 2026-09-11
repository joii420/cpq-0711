package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import com.cpq.semanticgraph.entity.SemanticTabViewColumn;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-260911 · 「行级维度」角色 {@code ROW_SCOPE} 的唯一定义处（api.md §2）。
 *
 * <p><b>它解决什么</b>：{@code repair-260910} 让报价单产品页签的取数口径开始依赖**当前明细行自己的**
 * 客户产品编号（标量谓词 {@code = :customerProductNo}），代价是退出了「合桶」——
 * 合桶成立的充要条件是「视图结果不依赖行」，一旦依赖行，等式在定义上就不成立，
 * 整单物化从 1 次 {@code expandMulti} 退化成每明细行 1 次 expand + 1 次反查
 * （实测最大单 1845 行 ⇒ 约 3690 条 SQL）。
 *
 * <p><b>本机制怎么两全</b>：把「按行收窄」这件事从 <b>SQL 层</b>挪到<b>分发层</b>——
 * <ol>
 *   <li>SQL 侧改成<b>集合成员</b>谓词 {@code = ANY(:<列>s)}，值 = 整单去重集合
 *       ⇒ 结果只依赖<b>单</b>、不依赖<b>行</b> ⇒ 合桶重新成立（{@code SemanticCompiler});</li>
 *   <li>分发层按该列把桶里的超集投影成「属于本行的那一行」
 *       （{@code com.cpq.component.service.RowScopeProjector}）。</li>
 * </ol>
 *
 * <p>🚨 <b>谓词只许出现在 {@code LEFT JOIN … ON}，绝不许进 {@code WHERE}</b>：
 * 实测 {@code quotation_line_item.customer_part_no} 3970 行里 <b>128 行为空</b>，
 * 进 {@code WHERE} 会让这些卡片整个页签 0 行且不报错（AP-31/37/53 同族静默故障）。
 * 挂在 {@code ON} 上时不匹配只是「左表行仍在、右表侧列为空」。
 *
 * <p>🚫 <b>本类不硬编码任何具体列名</b>（{@link #LINE_ITEM_COLUMN_ALIAS} 是例外中的例外，
 * 见该字段注释）——哪一列是行级维度，唯一判据是语义图里的 {@code roles}。
 */
public final class RowScopeSupport {

    private RowScopeSupport() {}

    /** 角色常量。落点 = {@code semantic_node_column.roles}（迁移 {@code V441}）。 */
    public static final String ROLE = "ROW_SCOPE";

    /**
     * 行级维度列的运行时值来源：{@code quotation_line_item} 的哪一列。
     *
     * <p><b>约定优先</b>：默认按<b>同名</b>取（节点列 {@code foo} → {@code quotation_line_item.foo}）。
     * 本表<b>只列名字对不上的例外</b>，今天只有一条：语义图里叫 {@code customer_product_no}，
     * 报价明细行上叫 {@code customer_part_no}（历史命名，两边都改不动——前者是
     * {@code ds_quote_customer_part} 的物理列名，后者被前端与导入链路广泛引用）。
     *
     * <p>🚫 这不是「哪些列是行级维度」的开关 —— 那个判据只有 {@code roles} 一处（AC-9）。
     * 本表只回答「已经是行级维度的那一列，值从明细行的哪个字段来」。
     */
    private static final Map<String, String> LINE_ITEM_COLUMN_ALIAS =
            Map.of("customer_product_no", "customer_part_no");

    /** 两层 roles 合并（D-35）：页签视图的列级覆盖优先，否则退回节点级默认。 */
    public static List<String> mergedRoles(SemanticGraphSnapshot snap, UUID tabViewId, SemanticNodeColumn col) {
        if (snap == null || col == null) return List.of();
        if (tabViewId != null) {
            for (SemanticTabViewColumn o : snap.tabViewColumnsByView.getOrDefault(tabViewId, List.of())) {
                if (o.columnId.equals(col.id)) return List.of(o.roles);
            }
        }
        return List.of(col.roles);
    }

    /** 该列是不是行级维度（两层合并后的结果）。 */
    public static boolean isRowScope(SemanticGraphSnapshot snap, UUID tabViewId, SemanticNodeColumn col) {
        return mergedRoles(snap, tabViewId, col).contains(ROLE);
    }

    /**
     * 行级维度列 → 运行时<b>集合</b>占位符名（复数）。
     * {@code customer_product_no} → {@code customerProductNos}（api.md §3）。
     *
     * <p>复数形态是刻意的：它与 {@code repair-260910} 的单数 {@code :customerProductNo}
     * <b>不同名</b>，所以存量未重编译的 {@code sql_template} 不会被本机制误绑，
     * 两套口径不会半新半旧地混在一起（重编译后老名字整体消失）。
     */
    public static String setParamName(String dbColumn) {
        return toCamel(dbColumn) + "s";
    }

    /**
     * 语义图里<b>全部</b>行级维度列的集合占位符名（复数形态，见 {@link #setParamName}）。
     *
     * <p>用途：{@code /preview}、dry-run 校验这类<b>不走 {@code SqlViewExecutor} enrich 管线</b>的
     * 裸 JDBC 路径，模板里留一个未绑定的 {@code :xxx} 会让 PG 直接报语法错，必须逐个加桩。
     *
     * <p>🚫 <b>这些打桩点绝不能写死列名</b>：写死了，第二个列被打上 {@code ROW_SCOPE} 时预览/保存
     * 当场报错，而 AC-9 的立项前提就是「加第二个列只改配置不改 Java」。所以统一从这里枚举。
     */
    public static java.util.Set<String> setParamNames(SemanticGraphSnapshot snap) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (snap == null || snap.nodeColumns == null) return out;
        for (SemanticNodeColumn col : snap.nodeColumns) {
            if (col == null || col.roles == null || col.dbColumn == null) continue;
            for (String r : col.roles) {
                if (ROLE.equals(r)) { out.add(setParamName(col.dbColumn)); break; }
            }
        }
        return out;
    }

    /** 该行级维度列的值，来自 {@code quotation_line_item} 的哪一列（约定同名 + 例外表）。 */
    public static String lineItemColumnFor(String dbColumn) {
        return LINE_ITEM_COLUMN_ALIAS.getOrDefault(dbColumn, dbColumn);
    }

    /** {@code customer_product_no} → {@code customerProductNo}。 */
    public static String toCamel(String snake) {
        if (snake == null || snake.isBlank()) return "";
        StringBuilder sb = new StringBuilder(snake.length());
        boolean up = false;
        for (int i = 0; i < snake.length(); i++) {
            char ch = snake.charAt(i);
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }

    /** 归一化比较值：{@code null} 与空白等价（明细行客编「没填」与视图侧 NULL 是同一件事）。 */
    public static String normalize(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
