package com.cpq.repair260908;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * repair-260908 · S-1 分片的<b>量具</b>：把一段编译产物 SQL 拆成
 * 「外层 SELECT 块」/「子查询 SELECT 块」，各自带自己的 FROM、JOIN、WHERE。
 *
 * <h3>🚨 为什么必须自己写一个，而不是拿正则抽表名</h3>
 * 并发会话 {@code task-260907} 用 {@code FROM\s+(ds_quote_\w+)} 抽「主表」，
 * 3 条 {@code COST_BASIC} 视图匹到的是 <b>{@code NARROW} 桥子查询里</b>的
 * {@code FROM ds_quote_material dqm}，外层真正的 {@code FROM v_ds_cost_basic_*}
 * 反而没匹上 —— 而那个错位<b>恰好指到了 B-1b 要改的位置</b>。
 * 对方原话：「用一个错误的判据碰巧碰到了正确的位置。这种『歪打正着』比明确的错更危险，
 * 因为它会让人以为判据是对的。」
 *
 * <p>⇒ 本类的<b>唯一职责</b>就是把这两者显式分开：
 * <ul>
 *   <li>{@code AC-2b} 只看 {@link Block#depth}=0 的块（外层锚点）；</li>
 *   <li>{@code AC-16} 只看 {@code depth}&gt;0 的块（桥子查询）。</li>
 * </ul>
 * 两条 AC 的判据<b>不共用同一个「主表」概念</b>，因此不可能再次「歪打正着」。
 *
 * <h3>纯函数 · 零依赖</h3>
 * 不连库、不启 Quarkus、不 import 任何 {@code src/main} 下的类。
 * 它的正确性由 {@link SqlShapeSelfProofTest} 用<b>真实编译产物</b>正反两向证明。
 *
 * <h3>离线量具自证入口</h3>
 * <pre>java src/test/java/com/cpq/repair260908/SqlShape.java &lt;目录或文件&gt;…</pre>
 * 会把每个 .sql 的块结构打印出来，供人工与 {@code psql} 结果背靠背核对。
 */
public final class SqlShape {

    private SqlShape() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 标识符边界工具（backtask.md 硬约束 7）
    //    ds_quote_material 是 ds_quote_material_bom 的前缀，裸 substring 会两个方向同时出错
    // ═══════════════════════════════════════════════════════════════

    /** SQL 标识符的组成字符：字母、数字、下划线、{@code $}。边界断言一律用它。 */
    private static final String IDENT_CHARS = "A-Za-z0-9_$";

    /** {@code hay} 里是否以<b>完整标识符</b>的形式出现 {@code name}（大小写不敏感）。 */
    public static boolean containsIdentifier(String hay, String name) {
        if (hay == null || name == null || name.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![" + IDENT_CHARS + "])" + Pattern.quote(name)
                        + "(?![" + IDENT_CHARS + "])", Pattern.CASE_INSENSITIVE)
                .matcher(hay).find();
    }

    /** {@code text} 里是否含谓词 {@code <alias>.customer_no = :customerCode}（容忍空白差异）。 */
    public static boolean hasCustomerCodePredicate(String text, String alias) {
        if (text == null || alias == null) {
            return false;
        }
        return Pattern.compile("(?<![" + IDENT_CHARS + "])" + Pattern.quote(alias)
                        + "\\s*\\.\\s*customer_no\\s*=\\s*:customerCode(?![" + IDENT_CHARS + "])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /** {@code text} 里是否<b>以任何形式</b>提到 {@code <alias>.customer_no}（用于反向断言）。 */
    public static boolean mentionsCustomerNo(String text, String alias) {
        if (text == null || alias == null) {
            return false;
        }
        return Pattern.compile("(?<![" + IDENT_CHARS + "])" + Pattern.quote(alias)
                        + "\\s*\\.\\s*customer_no(?![" + IDENT_CHARS + "])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }



    /** {@code text} 里是否以完整标识符出现 {@code <alias>.}（用来判断相关子查询引用了哪个外层别名）。 */
    public static boolean mentionsAlias(String text, String alias) {
        if (text == null || alias == null) {
            return false;
        }
        return Pattern.compile("(?<![" + IDENT_CHARS + "])" + Pattern.quote(alias) + "\\s*\\.",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /**
     * {@code text} 里是否含<b>列对列</b>客户相关谓词 {@code <a>.customer_no = <b>.customer_no}
     * （任一方向）。
     *
     * <p>🔑 {@code B-1c} 给根分支的 {@code NOT EXISTS} 子查询加的是<b>相关谓词</b>
     * （{@code dqmb2.customer_no = dqm.customer_no}），<b>不是</b> {@code = :customerCode} ——
     * 拿 {@link #hasCustomerCodePredicate} 去验它会恒为 false，AC-17(b) 直接变空跑。
     */
    public static boolean hasCustomerCorrelation(String text, String innerAlias, String outerAlias) {
        if (text == null || innerAlias == null || outerAlias == null) {
            return false;
        }
        String a = Pattern.quote(innerAlias) + "\\s*\\.\\s*customer_no";
        String b = Pattern.quote(outerAlias) + "\\s*\\.\\s*customer_no";
        String bd = "(?<![" + IDENT_CHARS + "])";
        return Pattern.compile(bd + a + "\\s*=\\s*" + bd + b, Pattern.CASE_INSENSITIVE).matcher(text).find()
            || Pattern.compile(bd + b + "\\s*=\\s*" + bd + a, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /**
     * {@code AC-4} 用：把<b>本次允许出现的两种客户谓词片段</b>从 SQL 里抹掉。
     *
     * <h3>🚨 必须两边都抹（2026-09-08 后端实证的坑）</h3>
     * 后端的同型分类器<b>首跑误报 4 例</b>，根因是<b>只抹 after 没抹 before</b> ——
     * 那 4 个视图的 before 里本来就有 {@code LEFT JOIN … ON … customer_no = :customerCode}
     * （客户维度形态③），只抹一边等于人为制造差异。
     * ⇒ 调用方一律 {@code strip(before)} vs {@code strip(after)}。
     */
    public static String stripAllowedCustomerPredicates(String sql) {
        if (sql == null) {
            return null;
        }
        String ident = "[A-Za-z_][A-Za-z0-9_]*";
        String out = sql.replaceAll(
                "(?i)\\s+AND\\s+" + ident + "\\s*\\.\\s*customer_no\\s*=\\s*:customerCode", "");
        out = out.replaceAll(
                "(?i)\\s+AND\\s+" + ident + "\\s*\\.\\s*customer_no\\s*=\\s*"
                        + ident + "\\s*\\.\\s*customer_no", "");
        return out;
    }

    /** 压平空白，便于逐字比对时忽略排版差异。🚫 不改变语义。 */
    public static String flatten(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 词法：注释 / 字符串 / 双引号标识符 / 括号深度 / 括号组
    // ═══════════════════════════════════════════════════════════════

    /** 一个词法单元。{@code depth}=所在括号深度（顶层 0）；{@code group}=所在括号组 id（顶层 0）。 */
    public record Tok(String text, int depth, int group, int start, int end) {
        boolean isWord() {
            return !text.isEmpty() && (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_');
        }

        boolean kw(String k) {
            return text.equalsIgnoreCase(k);
        }
    }

    /**
     * 切词。规则：
     * <ul>
     *   <li>{@code --} 行注释、{@code /* *}{@code /} 块注释整段丢弃（{@code builder_32ab8212df6c}
     *       的产物第一行就是 {@code -- 树契约: …}，不处理会把注释里的词当代码）；</li>
     *   <li>{@code '…'} 字符串、{@code "…"} 双引号标识符各成一个 token（列别名是中文，如
     *       {@code "_物料_销售料号"}，不能被当成多个词）；</li>
     *   <li>{@code (} 记在<b>外层</b>深度/组上，随后深度+1、开新组；{@code )} 先回退再记。</li>
     * </ul>
     */
    public static List<Tok> lex(String sql) {
        List<Tok> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        int depth = 0;
        int groupSeq = 0;
        List<Integer> groupStack = new ArrayList<>();
        groupStack.add(0);
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 行注释
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int cur = groupStack.get(groupStack.size() - 1);
            if (c == '\'' || c == '"') {
                char q = c;
                int s = i;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            i += 2;   // 转义的双写引号
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.add(new Tok(sql.substring(s, i), depth, cur, s, i));
                continue;
            }
            if (c == '(') {
                out.add(new Tok("(", depth, cur, i, i + 1));
                depth++;
                groupStack.add(++groupSeq);
                i++;
                continue;
            }
            if (c == ')') {
                if (groupStack.size() > 1) {
                    groupStack.remove(groupStack.size() - 1);
                    depth--;
                }
                out.add(new Tok(")", depth, groupStack.get(groupStack.size() - 1), i, i + 1));
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == ':' || c == '@'
                    || Character.isDigit(c) || c > 127) {
                int s = i;
                // 冒号参数：:customerCode / :total_material_no / :versionFilter
                if (c == ':') {
                    i++;
                }
                while (i < n) {
                    char d = sql.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '$' || d > 127) {
                        i++;
                    } else {
                        break;
                    }
                }
                if (i == s) {
                    i++;   // 单个孤立的 ':' 之类，别死循环
                }
                out.add(new Tok(sql.substring(s, i), depth, cur, s, i));
                continue;
            }
            // 其余符号逐字符成 token（. , = < > + - * / :: 等）
            int s = i;
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                i += 2;
            } else {
                i++;
            }
            out.add(new Tok(sql.substring(s, i), depth, cur, s, i));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 结构：SELECT 块（一个 UNION 分支 = 一个块；一个子查询 = 一个块）
    // ═══════════════════════════════════════════════════════════════

    /** 一条关系引用：{@code FROM t a} 或 {@code LEFT JOIN t a ON …}。 */
    public record Rel(String kind, String joinType, String table, String alias, String onClause) {
        @Override
        public String toString() {
            return (kind.equals("FROM") ? "FROM " : joinType + " ") + table + " " + alias
                    + (onClause == null || onClause.isBlank() ? "" : " ON " + onClause);
        }
    }

    /**
     * 一个 SELECT 块。
     *
     * @param depth      0 = 外层（AC-2b 的作用域）；&gt;0 = 子查询（AC-16 的作用域）
     * @param group      所在括号组 id
     * @param from       该块的 {@code FROM} 关系（可能为 null，如 {@code SELECT 1}）
     * @param joins      该块的 JOIN 列表（AC-6 的作用域）
     * @param whereFull  该块 WHERE 全文（含嵌套子查询原文）
     * @param whereTop   该块 WHERE 里<b>只属于本层</b>的部分（括号组内容被替换成 {@code ( … )}）
     */
    public record Block(int depth, int group, Rel from, List<Rel> joins,
                        String whereFull, String whereTop) {
    }

    private static final Set<String> BLOCK_KW = Set.of(
            "select", "from", "where", "group", "order", "having", "limit", "offset",
            "union", "except", "intersect", "join", "on", "using",
            "left", "right", "inner", "full", "cross", "outer", "natural", "lateral",
            "window", "fetch", "returning", "as");

    /** WHERE 的终止关键字（同层出现即认为 WHERE 段结束）。 */
    private static final Set<String> WHERE_END = Set.of(
            "group", "order", "having", "limit", "offset", "union", "except", "intersect", "window", "fetch");

    /** 把整段 SQL 拆成块。顺序 = 出现顺序。 */
    public static List<Block> blocks(String sql) {
        List<Tok> t = lex(sql);
        List<Block> out = new ArrayList<>();
        int i = 0;
        while (i < t.size()) {
            if (!t.get(i).kw("select")) {
                i++;
                continue;
            }
            int selDepth = t.get(i).depth();
            int selGroup = t.get(i).group();
            int j = i + 1;
            Rel from = null;
            List<Rel> joins = new ArrayList<>();
            String whereFull = null;
            String whereTop = null;

            while (j < t.size()) {
                Tok tk = t.get(j);
                // 走出本块（括号关闭或遇到同层的集合运算 / 排序）
                if (tk.depth() < selDepth) {
                    break;
                }
                boolean sameLevel = tk.depth() == selDepth && tk.group() == selGroup;
                if (sameLevel && (tk.kw("union") || tk.kw("except") || tk.kw("intersect"))) {
                    break;
                }
                if (sameLevel && tk.kw("select") && j > i) {
                    break;   // 同层不该有第二个 SELECT，防御
                }
                if (sameLevel && tk.kw("from") && from == null) {
                    int[] cur = new int[]{j + 1};
                    from = readRel(t, cur, "FROM", null, sql);
                    j = cur[0];
                    continue;
                }
                if (sameLevel && tk.kw("join")) {
                    StringBuilder jt = new StringBuilder();
                    int back = j - 1;
                    List<String> mods = new ArrayList<>();
                    while (back >= 0 && t.get(back).isWord() && Set.of("left", "right", "inner",
                            "full", "cross", "outer", "natural", "lateral").contains(
                            t.get(back).text().toLowerCase(Locale.ROOT))) {
                        mods.add(0, t.get(back).text().toUpperCase(Locale.ROOT));
                        back--;
                    }
                    mods.forEach(m -> jt.append(m).append(' '));
                    jt.append("JOIN");
                    int[] cur = new int[]{j + 1};
                    Rel r = readRel(t, cur, "JOIN", jt.toString(), sql);
                    j = cur[0];
                    // ON 子句：读到同层的下一个块级关键字
                    String on = null;
                    if (j < t.size() && t.get(j).depth() == selDepth && t.get(j).kw("on")) {
                        int s = j + 1;
                        int k = s;
                        while (k < t.size()) {
                            Tok x = t.get(k);
                            if (x.depth() < selDepth) {
                                break;
                            }
                            if (x.depth() == selDepth && x.group() == selGroup && x.isWord()
                                    && (x.kw("join") || x.kw("where") || WHERE_END.contains(
                                    x.text().toLowerCase(Locale.ROOT))
                                    || Set.of("left", "right", "inner", "full", "cross", "natural")
                                    .contains(x.text().toLowerCase(Locale.ROOT)))) {
                                break;
                            }
                            k++;
                        }
                        on = span(sql, t, s, k);
                        j = k;
                    }
                    joins.add(new Rel(r.kind(), r.joinType(), r.table(), r.alias(), on));
                    continue;
                }
                if (sameLevel && tk.kw("where") && whereFull == null) {
                    int s = j + 1;
                    int k = s;
                    while (k < t.size()) {
                        Tok x = t.get(k);
                        if (x.depth() < selDepth) {
                            break;
                        }
                        if (x.depth() == selDepth && x.group() == selGroup && x.isWord()
                                && WHERE_END.contains(x.text().toLowerCase(Locale.ROOT))) {
                            break;
                        }
                        k++;
                    }
                    whereFull = span(sql, t, s, k);
                    whereTop = topLevelOnly(t, s, k, selDepth, selGroup);
                    j = k;
                    continue;
                }
                j++;
            }
            out.add(new Block(selDepth, selGroup, from, joins, whereFull, whereTop));
            i++;
        }
        return out;
    }

    /** 读一条关系引用：{@code 表名 [ (函数实参) ] [AS] [别名]}。 */
    private static Rel readRel(List<Tok> t, int[] cur, String kind, String joinType, String sql) {
        int j = cur[0];
        if (j >= t.size()) {
            cur[0] = j;
            return new Rel(kind, joinType, "<缺失>", "<缺失>", null);
        }
        StringBuilder name = new StringBuilder();
        if (t.get(j).text().equals("(")) {
            // 派生表 FROM ( SELECT … ) alias
            int d = t.get(j).depth();
            name.append("(subquery)");
            j++;
            while (j < t.size() && !(t.get(j).text().equals(")") && t.get(j).depth() == d)) {
                j++;
            }
            j++;
        } else {
            name.append(t.get(j).text());
            j++;
            while (j + 1 < t.size() && t.get(j).text().equals(".")) {
                name.append('.').append(t.get(j + 1).text());
                j += 2;
            }
            // 表函数：f_material_element_price(:customerCode, :priceBaseDate) cep
            if (j < t.size() && t.get(j).text().equals("(")) {
                int d = t.get(j).depth();
                name.append("(…)");
                j++;
                while (j < t.size() && !(t.get(j).text().equals(")") && t.get(j).depth() == d)) {
                    j++;
                }
                j++;
            }
        }
        String alias = null;
        if (j < t.size() && t.get(j).kw("as")) {
            j++;
        }
        if (j < t.size() && t.get(j).isWord()
                && !BLOCK_KW.contains(t.get(j).text().toLowerCase(Locale.ROOT))) {
            alias = t.get(j).text();
            j++;
        }
        cur[0] = j;
        String table = name.toString();
        return new Rel(kind, joinType, table, alias == null ? stripFuncArgs(table) : alias, null);
    }

    private static String stripFuncArgs(String table) {
        int p = table.indexOf('(');
        return p < 0 ? table : table.substring(0, p);
    }

    private static String span(String sql, List<Tok> t, int from, int to) {
        if (from >= to || from >= t.size()) {
            return "";
        }
        int a = t.get(from).start();
        int b = t.get(Math.min(to, t.size()) - 1).end();
        return sql.substring(a, b).trim();
    }

    /** 只保留 {@code depth/group} 完全等于本层的 token；子查询内容压成 {@code ( … )}。 */
    private static String topLevelOnly(List<Tok> t, int from, int to, int depth, int group) {
        StringBuilder sb = new StringBuilder();
        boolean skipped = false;
        for (int k = from; k < Math.min(to, t.size()); k++) {
            Tok x = t.get(k);
            if (x.depth() == depth && x.group() == group) {
                if (skipped) {
                    sb.append(" … ");
                    skipped = false;
                }
                sb.append(x.text()).append(' ');
            } else {
                skipped = true;
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 派生视图：外层块 / 子查询块 / JOIN 清单
    // ═══════════════════════════════════════════════════════════════

    /** 外层块（{@code depth==0}）。UNION 有几个分支就有几个。 */
    public static List<Block> outerBlocks(String sql) {
        return blocks(sql).stream().filter(b -> b.depth() == 0).toList();
    }

    /** 子查询块（{@code depth>0}）—— {@code NARROW} 桥就住在这里。 */
    public static List<Block> subBlocks(String sql) {
        return blocks(sql).stream().filter(b -> b.depth() > 0).toList();
    }

    /** 全部 JOIN（任意层），按出现顺序。AC-6 的比对对象。 */
    public static List<Rel> allJoins(String sql) {
        List<Rel> out = new ArrayList<>();
        for (Block b : blocks(sql)) {
            out.addAll(b.joins());
        }
        return out;
    }

    /** AC-6 用的 JOIN 投影：{@code 类型|表|别名|ON 原文（压平空白）}，逐条逐字可比。 */
    public static List<String> joinFingerprint(String sql) {
        List<String> out = new ArrayList<>();
        for (Rel r : allJoins(sql)) {
            out.add(r.joinType() + " | " + r.table() + " | " + r.alias() + " | ON " + flatten(r.onClause()));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. 离线量具自证入口（人工核对用；常驻自证在 SqlShapeSelfProofTest）
    // ═══════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    s.filter(x -> x.toString().endsWith(".sql")).sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (Path f : files) {
            String sql = Files.readString(f);
            System.out.println("### " + f.getFileName());
            for (Block b : blocks(sql)) {
                String tag = b.depth() == 0 ? "外层" : "子查询(d=" + b.depth() + ")";
                System.out.println("  [" + tag + "] FROM=" + (b.from() == null ? "-" : b.from()));
                for (Rel j : b.joins()) {
                    System.out.println("            " + j);
                }
                System.out.println("            WHERE(本层)=" + b.whereTop());
                tally.merge(tag.replaceAll("\\(.*", ""), 1, Integer::sum);
            }
            System.out.println();
        }
        System.out.println("== 块数汇总 " + tally + "，文件数 " + files.size());
    }
}
