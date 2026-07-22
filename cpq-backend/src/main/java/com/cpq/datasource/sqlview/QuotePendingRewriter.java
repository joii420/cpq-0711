package com.cpq.datasource.sqlview;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * task-0721 报价数据版本升级 · B3 —— pending 感知 SQL 改写（纯函数 + pg 列元数据缓存）。
 *
 * <p>只读 SQL 结构（词法扫描 FROM/JOIN 白名单表 token）+ DB 元数据（{@code pg_attribute} 列清单），
 * 不读任何组件名/页签名/列别名 —— 用户随意命名不影响正确性（需求说明 §规则三"零配置"）。
 *
 * <p>三步改写（backtask B3.1）：
 * <ol>
 *   <li><b>表替换</b>（换表不换谓词）：把白名单表 token 换成等价子查询，子查询内把 {@code is_current}
 *       列重定义为 {@code (t.is_current OR t.pending_quotation_id = :pq)}；用户原谓词
 *       （如 {@code is_current = true} / {@code asy.is_current}）一字不改，经替换后对 pending 行自动成立。</li>
 *   <li><b>遮蔽</b>：pending 行优先，屏蔽被其 {@code pending_supersedes} 点名的旧 current 行，
 *       防止「official + pending」同组两行都可见导致行数翻倍。</li>
 *   <li><b>锚点注入</b>：在主位表（FROM 后第一个顶层白名单表）所在的外层 SELECT 列表注入
 *       {@code <alias>.id AS __v6_id}，供物化期写入 {@code snapshot_rows.driverRow}、B5 回填按锚点定位行。</li>
 * </ol>
 *
 * <p><b>安全降级</b>：找不到白名单主位表（如整个模板压根不碰这 7 张表，或主位是非白名单表如
 * {@code material_customer_map}）→ {@code anchorInjected=false}，不参与回填（该页签只读展示），
 * 不代表改写失败。真正的"改写失败"由启动期硬校验（{@link QuoteViewValidationService}）兜底。
 */
public final class QuotePendingRewriter {

    private QuotePendingRewriter() {}

    /** 7 张版本化表白名单（占号表 material_customer_map 不参与，见 backtask B3.1 明确排除）。 */
    public static final Set<String> WHITELIST_TABLES = Set.of(
        "unit_price", "material_bom", "material_bom_item",
        "element_bom", "element_bom_item", "capacity", "plating_scheme");

    /** 物化期注入的行锚点系统列名。 */
    public static final String ANCHOR_COLUMN = "__v6_id";

    /** SQL 里代表"当前报价单 pending 归属"的命名参数（{@link SqlViewExecutor} 负责绑定实际值）。 */
    public static final String PENDING_PARAM = "pq";

    private static final Pattern TABLE_TOKEN = Pattern.compile(
        "\\b(FROM|JOIN)\\s+(" + String.join("|", WHITELIST_TABLES) + ")\\b" +
        "(?:\\s+(?:AS\\s+)?(?!(?:WHERE|ON|JOIN|INNER|LEFT|RIGHT|FULL|OUTER|CROSS|GROUP|ORDER|LIMIT|UNION|HAVING|AND|OR)\\b)" +
        "([A-Za-z_][A-Za-z0-9_]*))?",
        Pattern.CASE_INSENSITIVE);

    /** {@code WITH [RECURSIVE] name AS (} / {@code , name AS (}：CTE 定义名，避免同名遮蔽真实表。 */
    private static final Pattern CTE_NAME = Pattern.compile(
        "(?:\\bWITH(?:\\s+RECURSIVE)?\\s+|,\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s+AS\\s*\\(",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SELECT_KW = Pattern.compile("\\bSELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTINCT_KW = Pattern.compile("^\\s+DISTINCT\\b", Pattern.CASE_INSENSITIVE);
    /** 集合运算关键字：UNION/UNION ALL/INTERSECT/EXCEPT——多分支列位置不天然对齐，锚点注入不安全
     *  （需求说明 §规则三"COALESCE/表达式/常量/UNION ALL → 安全降级，判为不可回写"）。 */
    private static final Pattern SET_OP_KW = Pattern.compile("\\b(UNION|INTERSECT|EXCEPT)\\b", Pattern.CASE_INSENSITIVE);
    /** GROUP BY——聚合结果一行对应多条源行，裸 id 引用既不合法（须出现在 GROUP BY 或聚合函数里）
     *  也无意义（无法回填单一源行），同样安全降级不注入锚点。 */
    private static final Pattern GROUP_BY_KW = Pattern.compile("\\bGROUP\\s+BY\\b", Pattern.CASE_INSENSITIVE);

    /** 一处 FROM/JOIN 白名单表命中。 */
    private static final class TableMatch {
        final String keyword;   // FROM / JOIN（大写）
        final int start, end;   // 整体 match 区间（含 table + 可选别名），原始坐标
        final String table;
        final String alias;     // 缺省 = table 本身（POC-D：无别名时用表名作别名）
        TableMatch(String keyword, int start, int end, String table, String alias) {
            this.keyword = keyword; this.start = start; this.end = end;
            this.table = table; this.alias = (alias == null || alias.isBlank()) ? table : alias;
        }
    }

    /** 改写结果。 */
    public static final class Result {
        /** 改写后的 SQL（未命中任何白名单表时原样返回原 sqlTemplate）。 */
        public final String sql;
        /** 是否成功注入 {@code __v6_id} 锚点（true=该视图可回填；false=只读展示，不参与回填）。 */
        public final boolean anchorInjected;
        /** 主位表名（未确定时为 null）。 */
        public final String primaryTable;
        /** 主位表别名（未确定时为 null）。 */
        public final String primaryAlias;
        /** 本次改写实际命中的白名单表集合（供诊断）。 */
        public final Set<String> touchedTables;

        Result(String sql, boolean anchorInjected, String primaryTable, String primaryAlias, Set<String> touchedTables) {
            this.sql = sql; this.anchorInjected = anchorInjected;
            this.primaryTable = primaryTable; this.primaryAlias = primaryAlias;
            this.touchedTables = touchedTables;
        }
    }

    /**
     * 屏蔽字符串字面量 / 行注释 / 块注释为等长空白（换行符原样保留，保证行号/偏移量不变），
     * 供 token 定位用；实际替换仍作用于原始文本（偏移量对齐）。
     */
    static String mask(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { out.append("  "); i += 2; continue; }
                        out.append(' '); i++; break;
                    }
                    out.append(d == '\n' ? '\n' : ' '); i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                out.append("  "); i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    out.append(sql.charAt(i) == '\n' ? '\n' : ' '); i++;
                }
                if (i + 1 < n) { out.append("  "); i += 2; } else { i = n; }
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }

    static Set<String> cteNames(String masked) {
        Set<String> names = new HashSet<>();
        Matcher m = CTE_NAME.matcher(masked);
        while (m.find()) names.add(m.group(1).toLowerCase());
        return names;
    }

    static List<TableMatch> findTableTokens(String masked, Set<String> ctes) {
        List<TableMatch> out = new ArrayList<>();
        Matcher m = TABLE_TOKEN.matcher(masked);
        while (m.find()) {
            String table = m.group(2).toLowerCase();
            if (ctes.contains(table)) continue;   // 同名 CTE 遮蔽真实表，跳过（POC 要求 R-1）
            out.add(new TableMatch(m.group(1).toUpperCase(), m.start(), m.end(), table, m.group(3)));
        }
        return out;
    }

    /** 某位置的括号嵌套深度（相对字符串起点；0 = 未进入任何括号，即顶层）。 */
    static int depthAt(String masked, int pos) {
        int depth = 0;
        for (int i = 0; i < pos && i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return depth;
    }

    /** 顶层（depth==0）是否存在 UNION/INTERSECT/EXCEPT——存在则模板是多分支集合运算，
     *  各分支列位置不天然对齐，注入单一锚点不安全（R-2 安全降级：不可回写，非错误）。 */
    static boolean hasTopLevelSetOp(String masked) {
        Matcher m = SET_OP_KW.matcher(masked);
        while (m.find()) {
            if (depthAt(masked, m.start()) == 0) return true;
        }
        return false;
    }

    /** 与 primary 表同一嵌套深度是否存在 GROUP BY——存在则该 SELECT 是聚合查询，裸 id 引用
     *  既非法（PG 要求出现在 GROUP BY 或聚合函数里）也无意义（一行对应多条源行），安全降级不注入锚点。 */
    static boolean hasGroupByAtDepth(String masked, int depth) {
        Matcher m = GROUP_BY_KW.matcher(masked);
        while (m.find()) {
            if (depthAt(masked, m.start()) == depth) return true;
        }
        return false;
    }

    /** 在 fromPos 之前、同一嵌套深度、最近的 SELECT 关键字起始位置；找不到返回 -1。 */
    static int findOwningSelect(String masked, int fromPos) {
        int targetDepth = depthAt(masked, fromPos);
        Matcher m = SELECT_KW.matcher(masked);
        int best = -1;
        while (m.find()) {
            if (m.start() >= fromPos) break;
            if (depthAt(masked, m.start()) == targetDepth) best = m.start();
        }
        return best;
    }

    /** 表的列清单（进程级缓存；表结构稳定，DDL 变更需重启——与项目既有的 ImplicitJoinRewriter 缓存约定一致）。 */
    private static final ConcurrentHashMap<String, List<String>> COLUMNS_CACHE = new ConcurrentHashMap<>();

    static List<String> columnsOf(String table, Connection conn) throws SQLException {
        List<String> cached = COLUMNS_CACHE.get(table);
        if (cached != null) return cached;
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT attname FROM pg_attribute WHERE attrelid = ?::regclass " +
                "AND attnum > 0 AND NOT attisdropped ORDER BY attnum")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        COLUMNS_CACHE.put(table, cols);
        return cols;
    }

    /** 表替换子查询体（不含外层别名，调用方拼 "(...) alias"）。 */
    private static String buildReplacementSubquery(String table, Connection conn) throws SQLException {
        List<String> cols = columnsOf(table, conn);
        if (cols.isEmpty()) {
            throw new IllegalStateException("表 " + table + " 列元数据为空（regclass 解析失败或表不存在）");
        }
        StringBuilder sel = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sel.append(", ");
            String c = cols.get(i);
            if ("is_current".equals(c)) {
                sel.append("(t.is_current OR t.pending_quotation_id = :").append(PENDING_PARAM).append(") AS is_current");
            } else {
                sel.append("t.").append(c);
            }
        }
        return "(SELECT " + sel + " FROM " + table + " t WHERE " +
            "t.pending_quotation_id = :" + PENDING_PARAM + " OR (t.is_current AND t.pending_quotation_id IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM " + table + " p WHERE p.pending_quotation_id = :" + PENDING_PARAM + " " +
            "AND t.id = ANY(p.pending_supersedes))))";
    }

    /** 一处待应用的文本编辑：[start, end) 替换为 replacement（end==start 表示纯插入）。 */
    private record Edit(int start, int end, String replacement) {}

    /**
     * 改写入口。
     *
     * @param sqlTemplate 组件/模板 sql_template 原文
     * @param conn        取列元数据用的连接（不执行业务查询，不修改数据）
     * @return 改写结果；模板未命中任何白名单表时 {@code anchorInjected=false}，sql 原样返回
     */
    public static Result rewrite(String sqlTemplate, Connection conn) throws SQLException {
        String masked = mask(sqlTemplate);
        Set<String> ctes = cteNames(masked);
        List<TableMatch> matches = findTableTokens(masked, ctes);
        if (matches.isEmpty()) {
            return new Result(sqlTemplate, false, null, null, Set.of());
        }

        // 主位：顶层（depth==0）第一个 FROM（非 JOIN）白名单表；顶层没有则退化为任意深度第一个 FROM。
        // 顶层存在 UNION/INTERSECT/EXCEPT 时不确定主位（多分支列位置不天然对齐，安全降级不注入锚点）。
        boolean setOp = hasTopLevelSetOp(masked);
        TableMatch primary = null;
        if (!setOp) {
            for (TableMatch mt : matches) {
                if ("FROM".equals(mt.keyword) && depthAt(masked, mt.start) == 0) { primary = mt; break; }
            }
            if (primary == null) {
                for (TableMatch mt : matches) {
                    if ("FROM".equals(mt.keyword)) { primary = mt; break; }
                }
            }
        }

        Set<String> touched = new HashSet<>();
        List<Edit> edits = new ArrayList<>();
        for (TableMatch mt : matches) {
            touched.add(mt.table);
            String replBody = buildReplacementSubquery(mt.table, conn);
            edits.add(new Edit(mt.start, mt.end, mt.keyword + " " + replBody + " " + mt.alias));
        }

        String primaryTable = null, primaryAlias = null;
        int insertPos = -1;
        String anchorFrag = null;
        if (primary != null && !hasGroupByAtDepth(masked, depthAt(masked, primary.start))) {
            int selectPos = findOwningSelect(masked, primary.start);
            if (selectPos >= 0) {
                insertPos = selectPos + 6; // "SELECT".length()
                Matcher distinctM = DISTINCT_KW.matcher(
                    sqlTemplate.substring(insertPos, Math.min(insertPos + 24, sqlTemplate.length())));
                if (distinctM.find()) insertPos += distinctM.end();
                anchorFrag = " " + primary.alias + ".id AS " + ANCHOR_COLUMN + ",";
                edits.add(new Edit(insertPos, insertPos, anchorFrag));
                primaryTable = primary.table;
                primaryAlias = primary.alias;
            }
        }

        // 统一按起始位置降序应用编辑（同起点则先应用范围更大的，即表替换优先于零长度锚点插入不会发生冲突，
        // 因为锚点插入点恒在 SELECT 关键字之后、任何表 token 之前，位置互斥不重叠）。
        edits.sort((a, b) -> {
            int c = Integer.compare(b.start(), a.start());
            return c != 0 ? c : Integer.compare(b.end(), a.end());
        });
        StringBuilder sb = new StringBuilder(sqlTemplate);
        for (Edit e : edits) sb.replace(e.start(), e.end(), e.replacement());

        return new Result(sb.toString(), anchorFrag != null, primaryTable, primaryAlias, touched);
    }
}
