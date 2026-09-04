package com.cpq.datasource.sqlview;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p>四步改写（backtask B3.1 三步 + repair-260830 补第四步）：
 * <ol>
 *   <li><b>表替换</b>（换表不换谓词）：把白名单表 token 换成等价子查询，子查询内把 {@code is_current}
 *       列重定义为 {@code (t.is_current OR t.pending_quotation_id = :pq)}；用户原谓词
 *       （如 {@code is_current = true} / {@code asy.is_current}）一字不改，经替换后对 pending 行自动成立。</li>
 *   <li><b>遮蔽</b>：pending 行优先，屏蔽被其 {@code pending_supersedes} 点名的旧 current 行，
 *       防止「official + pending」同组两行都可见导致行数翻倍。</li>
 *   <li><b>锚点注入</b>：在主位表（FROM 后第一个顶层白名单表）所在的外层 SELECT 列表注入
 *       {@code <alias>.id AS __v6_id}，供物化期写入 {@code snapshot_rows.driverRow}、B5 回填按锚点定位行。
 *       顶层为 UNION/INTERSECT/EXCEPT 集合运算时（repair-0727 B1）：按分支切分后逐分支独立判定/注入，
 *       某分支未命中白名单表或含 GROUP BY 只让该分支取 {@code NULL::uuid}，不阻断整视图，见
 *       {@link #rewrite(String, Connection, boolean)} 内联注释。</li>
 *   <li><b>库函数补参</b>（repair-260830）：把 {@link #PENDING_AWARE_FUNCTION} 的两参调用补成三参
 *       （第三参 {@code :pq}）。<b>为什么必须有这一步</b>：第 1 步的表替换是<b>文本</b>改写，够不到
 *       编译在库里的函数体；而 {@code f_material_element_price} 内部自己又读了一遍
 *       {@code material_bom_item} / {@code element_bom_item} 并写死 {@code is_current = true} ——
 *       同一次查询里同一张表被读两遍，外层看得见 pending、函数里看不见，本单 pending 料号在函数
 *       候选集里整体缺席（症状：元素单价恒 NULL，见 repair-260830 问题说明 §4.1）。
 *       <p>⚠️ <b>今后凡在 SQL 视图里引用「内部会读版本化表」的数据库函数，都必须同步登记进
 *       {@link #PENDING_AWARE_FUNCTION} 这条通道</b>，否则会静默复现同一类断链。</li>
 * </ol>
 *
 * <p><b>安全降级</b>：找不到白名单主位表（如整个模板压根不碰这 8 张表，或主位是非白名单表如
 * {@code material_customer_map}；或 set-op 场景全部分支都未命中）→ {@code anchorInjected=false}，
 * 不参与回填（该页签只读展示），不代表改写失败。真正的"改写失败"由启动期硬校验
 * （{@link QuoteViewValidationService}）兜底。
 */
public final class QuotePendingRewriter {

    private QuotePendingRewriter() {}

    /** 8 张版本化表白名单（占号表 material_customer_map 不参与，见 backtask B3.1 明确排除）。
     *  repair-0804：annual_discount 并入。
     *
     *  <p><b>task-260903 · B-3：并入 3 张兼容视图。</b>本类的 {@link #TABLE_TOKEN} 要求表名
     *  <b>紧跟在 FROM/JOIN 之后</b>才算命中。V411 把 135 段组件 SQL 里的
     *  {@code material_bom_item} / {@code element_bom_item} / {@code material_master}
     *  替换成 {@code v_compat_*} 之后，若不同步登记这三个名字，命中就会消失，而后果是
     *  <b>完全静默</b>的 —— {@code QuoteViewValidationService.checkOne} 对
     *  {@code anchorInjected=false} 返回「不适用，非失败」（那是本类刻意的安全降级设计），
     *  启动不报错、日志不告警。
     *
     *  <p>实测口径（共享库 {@code cpq_db_0724}，2026-09-03）：150 段视图中 128 段命中白名单，
     *  其中 <b>48 段只靠 {@code material_bom_item} / {@code element_bom_item} 命中</b>，
     *  改名后这 48 段会同时失去 ① pending 影子行可见性 ② B5 回填资格。
     *
     *  <p>✅ 视图可以当表用：本类第 1 步的表替换只是把 token 换成
     *  {@code (SELECT … FROM <名字> t WHERE …) alias} 等价子查询，视图完全支持；
     *  {@code columnsOf} 走 {@code pg_attribute + regclass}，视图同样返回列清单；
     *  锚点用的 {@code id} 列在兼容视图两侧都是 {@code uuid}
     *  （V6 侧原生 uuid，新表侧由 {@code md5(...)::uuid} 合成，见 V410）。
     *
     *  <p>🚩 <b>已知未闭合缺口（回报已登记，待裁决）</b>：{@code QuoteBackfillService} 回填时执行
     *  {@code UPDATE <基表名> SET is_current = …}，基表名由 pgjdbc {@code getBaseTableName} 得到
     *  ——对视图返回<b>视图名本身</b>（实测，UNION 视图亦然）。UNION 视图不可更新，
     *  且新表侧的合成 id 在任何物理表里都不存在。⇒ 兼容视图目前只保证
     *  「pending 可见 + 锚点可追」，<b>回填写回路径尚未打通</b>。 */
    public static final Set<String> WHITELIST_TABLES = Set.of(
        "unit_price", "material_bom", "material_bom_item",
        "element_bom", "element_bom_item", "capacity", "plating_scheme",
        "annual_discount",
        // task-260903 B-3：V411 表名替换后的兼容视图名（material_master 无 is_current/
        // pending_quotation_id 语义，历来就不在白名单里，其兼容视图同样不加）
        "v_compat_material_bom_item", "v_compat_element_bom_item");

    /**
     * <b>兼容视图名 → 物理表名</b>（task-260903 · B-3 · 主线裁决「方案乙」）。
     *
     * <h3>为什么读路径走视图、写路径必须回物理表</h3>
     * V411 把组件 SQL 的表名换成兼容视图后，{@code QuoteBackfillColumnMapper} 解析出的
     * {@code primaryTable} 就是视图名（pgjdbc {@code getBaseTableName} 对视图返回视图名本身，
     * UNION 视图亦然，已实测）。而回填最终执行的是
     * {@code UPDATE <表> SET is_current = …} —— UNION 视图<b>不可更新</b>。
     *
     * <h3>为什么归一化就够，不需要 INSTEAD OF 触发器</h3>
     * 兼容视图的新表侧把 {@code pending_quotation_id} 投影成常量 {@code NULL}（V410），
     * 而回填的两条 UPDATE 都带 {@code WHERE pending_quotation_id = :qid}
     * （{@code QuoteBackfillService#executeFlip}）⇒ <b>新表侧的行永远不会被回填选中</b>。
     * 所以归一化之后：V6 侧行的 {@code id} 是真 uuid，{@code UPDATE material_bom_item}
     * 与改造前逐字一致；新表侧行本就选不中，不存在「合成 uuid 定位不到物理行」的问题。
     * 触发器方案是在为一个不会发生的场景付出复杂度。
     *
     * <p>🚨 <b>新增兼容视图时必须同步加进本表</b>。漏加不会报错 ——
     * {@code QuoteBackfillCollector:172} 的 {@code QuoteTableAxis.of(...) == null → continue}
     * 守卫会让它静默降级成「不回填」。守卫测试见
     * {@code com.cpq.quotation.service.backfill.CompatViewBackfillGuardTest}。
     */
    public static final Map<String, String> COMPAT_VIEW_TO_TABLE = Map.of(
        "v_compat_material_bom_item", "material_bom_item",
        "v_compat_element_bom_item", "element_bom_item");

    /**
     * 把兼容视图名归一化成物理表名；非兼容视图（含 null）原样返回。
     *
     * <p>🚫 <b>只允许在 {@code QuoteBackfillColumnMapper} 解析出 {@code primaryTable} 的那一处调用。</b>
     * 散在多处会让「这个字符串到底是视图名还是表名」变成需要逐处推理的问题 ——
     * {@code QuoteBackfillCollector} 里有 4 处硬编码的
     * {@code "material_bom_item".equals(primaryTable)} 分支，单点归一化才能一次性覆盖它们。
     */
    public static String physicalTable(String table) {
        return table == null ? null : COMPAT_VIEW_TO_TABLE.getOrDefault(table, table);
    }

    /** 物化期注入的行锚点系统列名。 */
    public static final String ANCHOR_COLUMN = "__v6_id";

    /** SQL 里代表"当前报价单 pending 归属"的命名参数（{@link SqlViewExecutor} 负责绑定实际值）。 */
    public static final String PENDING_PARAM = "pq";

    /**
     * repair-260830：<b>内部会自己读版本化表、因而文本改写够不到的库函数</b>——必须显式把
     * {@code :pq} 当参数传进去，否则同一次查询里同一张 BOM 表被读两遍：外层那遍（表替换）看得见
     * pending 影子行，函数体里那遍（写死 {@code is_current = true}）看不见，结果就是本单 pending
     * 料号在函数的候选集里整体缺席（元素单价恒 NULL，见 repair-260830 问题说明 §4.1）。
     *
     * <p>{@code f_material_element_price} 的 {@code candidate_materials} CTE 读
     * {@code material_bom_item} / {@code element_bom_item}；V397 起提供三参重载
     * {@code (text, date, uuid)}，第三参为当前报价单 id，传 NULL 时自动退化为纯 {@code is_current}
     * （核价侧 / 冻结态所需）。
     *
     * <p>⚠️ 不包含 {@code f_customer_element_price}——它是纯元素级算价，不读任何 BOM 表。
     */
    static final String PENDING_AWARE_FUNCTION = "f_material_element_price";

    /** {@link #PENDING_AWARE_FUNCTION} 补参前的参数个数（补参后 = 该值 + 1）。 */
    private static final int PENDING_AWARE_FUNCTION_BASE_ARITY = 2;

    /** {@code f_material_element_price(} 调用起点（大小写不敏感；masked 文本上定位，注释/字面量内的同名文本天然不匹配）。 */
    private static final Pattern PENDING_AWARE_FN_CALL = Pattern.compile(
        "\\b" + PENDING_AWARE_FUNCTION + "\\s*\\(", Pattern.CASE_INSENSITIVE);

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
    /** 集合运算关键字：UNION/UNION ALL/INTERSECT/EXCEPT。
     *  <p>repair-0727 B1 前：多分支列位置不天然对齐，整体安全降级不注入锚点。
     *  repair-0727 B1 后：不再整体降级——按分支切分，逐分支独立判定/注入（见 {@link #rewrite}），
     *  只有"全部分支都未命中白名单表"才维持 {@code anchorInjected=false}（此时上层 matches 为空，
     *  在方法开头就已提前返回，不会走到分支循环）。 */
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
        /**
         * repair-0727 B1：仅顶层集合运算（UNION/INTERSECT/EXCEPT）视图非空——主位分支（第一个成功注入
         * 真实 {@code __v6_id} 锚点的分支，若该分支依赖顶层 WITH 前导已一并拼接）独立改写后的 SQL 文本。
         *
         * <p>为什么需要单独暴露：pgjdbc 对整体集合运算结果的输出列不返回
         * {@code getBaseTableName}（结果列不再对应单一物理表列，是集合运算节点的投影），必须对单一
         * 分支单独探测才能拿到基表元数据。{@code QuoteBackfillColumnMapper}（B2）/
         * {@code QuoteViewValidationService} 据此字段做单分支 {@code LIMIT 0} 元数据探测，非 set-op
         * 视图或未命中主位时为 {@code null}，此时消费方应直接用 {@link #sql} 整体探测（原有行为不变）。
         */
        public final String primaryBranchSql;

        Result(String sql, boolean anchorInjected, String primaryTable, String primaryAlias,
               Set<String> touchedTables, String primaryBranchSql) {
            this.sql = sql; this.anchorInjected = anchorInjected;
            this.primaryTable = primaryTable; this.primaryAlias = primaryAlias;
            this.touchedTables = touchedTables; this.primaryBranchSql = primaryBranchSql;
        }
    }

    /**
     * 屏蔽字符串字面量 / 行注释 / 块注释为等长空白（换行符原样保留，保证行号/偏移量不变），
     * 供 token 定位用；实际替换仍作用于原始文本（偏移量对齐）。
     *
     * <p>task-0725 根因 2：实现已抽到 {@link SqlTextMask#mask(String)}（同一屏蔽语义需要跨包共用，
     * 见 {@code SqlViewValidator} / {@code BomTreeRenderService} 两个站点），本方法保留仅为了不改动
     * 既有单测（{@code QuotePendingRewriterTest}）里对 package-private {@code mask} 的隐式依赖面，
     * 纯委派，无自有逻辑。
     */
    static String mask(String sql) {
        return SqlTextMask.mask(sql);
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
     *  改走 {@link #rewrite} 的逐分支注入路径（repair-0727 B1），而非单一主位探测。 */
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

    /** 与 {@link #hasGroupByAtDepth} 同语义，限定扫描范围为 {@code [start, end)}
     *  （repair-0727 B1：分支级 GROUP BY 判定，不能用全文档扫描——否则会被其它分支的 GROUP BY 误伤）。 */
    static boolean hasGroupByInRange(String masked, int start, int end, int depth) {
        Matcher m = GROUP_BY_KW.matcher(masked);
        while (m.find()) {
            if (m.start() < start) continue;
            if (m.start() >= end) break;
            if (depthAt(masked, m.start()) == depth) return true;
        }
        return false;
    }

    /** 顶层（depth==0）第一个 SELECT 关键字起始位置；找不到返回 -1（repair-0727 B1：定位顶层 WITH
     *  前导的结束点，供 {@link #buildPrimaryBranchSql} 判断非首分支是否需要拼接 CTE 前导）。 */
    static int firstTopLevelSelect(String masked) {
        Matcher m = SELECT_KW.matcher(masked);
        while (m.find()) {
            if (depthAt(masked, m.start()) == 0) return m.start();
        }
        return -1;
    }

    /** 顶层 UNION/INTERSECT/EXCEPT 关键字切出的分支文本区间列表（{@code [start, end)}，坐标与
     *  masked/sqlTemplate 对齐，repair-0727 B1）。至少返回 1 个区间（无分隔符时整个文档是唯一分支，
     *  仅在 {@link #hasTopLevelSetOp} 为真时才会被调用，故实际至少 2 个区间）。 */
    static List<int[]> splitTopLevelBranches(String masked) {
        List<int[]> seps = new ArrayList<>();
        Matcher m = SET_OP_KW.matcher(masked);
        while (m.find()) {
            if (depthAt(masked, m.start()) == 0) seps.add(new int[]{m.start(), m.end()});
        }
        List<int[]> branches = new ArrayList<>();
        int prevEnd = 0;
        for (int[] sep : seps) {
            branches.add(new int[]{prevEnd, sep[0]});
            prevEnd = sep[1];
        }
        branches.add(new int[]{prevEnd, masked.length()});
        return branches;
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
     * repair-260830：从 {@code openParen}（{@code '('} 的下标）起在 masked 文本上做括号配平扫描。
     *
     * <p>为什么不能用贪婪正则一把梭：参数里可能出现嵌套括号（如 {@code COALESCE(a,b)}）与命名占位符，
     * {@code [^)]*} 会在第一个内层 {@code ')'} 就收口，把补参插到错误位置。
     *
     * @return {@code int[]{ 配平的 ')' 下标, 顶层逗号数 }}；括号不配平返回 {@code null}
     */
    static int[] scanCallArgs(String masked, int openParen) {
        int depth = 0, topLevelCommas = 0;
        for (int i = openParen; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) return new int[]{i, topLevelCommas};
            } else if (c == ',' && depth == 1) {
                topLevelCommas++;
            }
        }
        return null;
    }

    /**
     * repair-260830：把 {@link #PENDING_AWARE_FUNCTION} 的两参调用补成三参（第三参 {@code :pq}）。
     *
     * <p>四条约束（backtask B-2）：
     * <ol>
     *   <li><b>注释屏蔽</b>：在 {@code masked} 上定位（{@code mask()} 把注释/字符串字面量整体换成等长
     *       空白），所以 {@code mc_view} 注释里写到的函数名不会被改写；实际插入作用于原文同一偏移量。</li>
     *   <li><b>幂等</b>：已是三参（顶层逗号数 != 1）的调用直接跳过，不重复补参。</li>
     *   <li><b>只改这一个函数</b>：{@code f_customer_element_price} 名字不匹配，天然不受影响。</li>
     *   <li><b>安全降级</b>：括号不配平 / 参数个数非预期 → 跳过该处（退化为两参版 = 改动前行为），
     *       不抛异常、不阻断其余改写。</li>
     * </ol>
     */
    static List<Edit> pendingAwareFunctionEdits(String sqlTemplate, String masked) {
        List<Edit> out = new ArrayList<>();
        Matcher m = PENDING_AWARE_FN_CALL.matcher(masked);
        while (m.find()) {
            int open = m.end() - 1;                       // 匹配以 '(' 结尾
            int[] scan = scanCallArgs(masked, open);
            if (scan == null) continue;                   // 括号不配平：安全降级
            int close = scan[0];
            // 参数个数 = 顶层逗号数 + 1，但空参表（原文括号内全空白）算 0 个。
            // ⚠️ 判空必须看原文而不是 masked：masked 把字符串字面量换成了空白，
            //    f_material_element_price('CUST-0004','2026-08-30') 在 masked 里看着"全空"。
            int args = sqlTemplate.substring(open + 1, close).isBlank() ? 0 : scan[1] + 1;
            if (args != PENDING_AWARE_FUNCTION_BASE_ARITY) continue;   // 已三参 / 形态异常 → 跳过
            out.add(new Edit(close, close, ", :" + PENDING_PARAM));
        }
        return out;
    }

    /** 按起始位置降序应用编辑（同起点先应用范围更大的），返回改写后的文本。 */
    private static String applyEdits(String src, List<Edit> edits) {
        edits.sort((a, b) -> {
            int c = Integer.compare(b.start(), a.start());
            return c != 0 ? c : Integer.compare(b.end(), a.end());
        });
        StringBuilder sb = new StringBuilder(src);
        for (Edit e : edits) sb.replace(e.start(), e.end(), e.replacement());
        return sb.toString();
    }

    /**
     * 改写入口（等价 {@code rewrite(sqlTemplate, conn, true)}，保留既有调用方 2 参签名不变）。
     *
     * @param sqlTemplate 组件/模板 sql_template 原文
     * @param conn        取列元数据用的连接（不执行业务查询，不修改数据）
     * @return 改写结果；模板未命中任何白名单表时 {@code anchorInjected=false}，sql 原样返回
     */
    public static Result rewrite(String sqlTemplate, Connection conn) throws SQLException {
        return rewrite(sqlTemplate, conn, true);
    }

    /**
     * task-0725 T3 收尾修复：{@code injectAnchor=false} 时跳过"主位表探测 + {@code __v6_id} 锚点插入"整段
     * （表替换/遮蔽逻辑不受影响，仍对全部白名单表命中生效）。
     *
     * <p><b>根因</b>：主位表探测的 fallback 分支（"顶层没有则退化为<b>任意深度</b>第一个 FROM"）按<b>文本
     * 出现顺序</b>取第一个 FROM，未区分该 FROM 是否位于 SELECT 列表内的相关子查询（如
     * {@code (SELECT sd.bom_version FROM material_bom_item sd WHERE ...) AS bom_version}）。递归 CTE
     * （如 {@link com.cpq.quotation.service.BomTreeRenderService#queryRecursive}
     * 使用的 {@code costing_bom_tree_config.sql_template}）常见把此类相关子查询写在 SELECT 列表最前面、
     * 早于该分支真正的行来源 FROM（如 {@code FROM material_bom_item ch JOIN bom b ON ...}）——此时会
     * 误将子查询内的 FROM 当主位，把 {@code <alias>.id AS __v6_id,} 插进一个"只能返回单列"的标量子查询
     * SELECT 列表，产生 Postgres {@code subquery must return only one column}（2026-07-25 端到端实测复现，
     * 见 task-0725 dev-docs）。
     *
     * <p><b>为什么安全跳过</b>：{@link BomTreeRenderService} 的递归 CTE spine（root_no/material_no/
     * bom_version/parent_no/node_path）本就<b>不需要</b> {@code __v6_id} 锚点——它只做结构定位，不是可
     * 回填的业务行（真正需要锚点回填的树页签"业务行"侧走 {@code $view}，走 {@link SqlViewExecutor} 的
     * 2 参 {@code rewrite} 独立注入，不受影响）；且 {@code queryRecursive} 的最终外层查询显式
     * {@code SELECT root_no, material_no, bom_version, parent_no, node_path FROM (...) q} 只按列名取 5 列，
     * 即使不注入锚点也不影响既有列输出。
     *
     * <p>2 参重载（{@link SqlViewExecutor}/{@code QuoteBackfillColumnMapper}/
     * {@code QuoteViewValidationService} 三个既有调用方）逐位不变，本参数默认值仍是 {@code true}。
     *
     * @param injectAnchor false = 跳过主位探测与锚点插入（仅做表替换 + 遮蔽）；true = 原行为
     */
    public static Result rewrite(String sqlTemplate, Connection conn, boolean injectAnchor) throws SQLException {
        String masked = mask(sqlTemplate);
        Set<String> ctes = cteNames(masked);
        List<TableMatch> matches = findTableTokens(masked, ctes);
        // repair-260830：库函数补参与白名单表命中互相独立——模板可能只调 f_material_element_price
        // 而不直接 FROM 任何白名单表，此时仍必须补 :pq（否则函数体里那遍 BOM 读取看不见 pending）。
        List<Edit> fnEdits = pendingAwareFunctionEdits(sqlTemplate, masked);
        if (matches.isEmpty()) {
            String onlyFn = fnEdits.isEmpty() ? sqlTemplate : applyEdits(sqlTemplate, fnEdits);
            return new Result(onlyFn, false, null, null, Set.of(), null);
        }

        boolean setOp = hasTopLevelSetOp(masked);

        Set<String> touched = new HashSet<>();
        List<Edit> edits = new ArrayList<>(fnEdits);
        for (TableMatch mt : matches) {
            touched.add(mt.table);
            String replBody = buildReplacementSubquery(mt.table, conn);
            edits.add(new Edit(mt.start, mt.end, mt.keyword + " " + replBody + " " + mt.alias));
        }

        String primaryTable = null, primaryAlias = null;
        boolean anchorInjected = false;
        // repair-0727 B1：主位分支的原文区间（仅 set-op 且成功注入时非空），供 buildPrimaryBranchSql 用。
        int[] primaryBranchRange = null;

        if (injectAnchor && setOp) {
            // repair-0727 B1（需求说明 §3.2）：顶层集合运算不再整体降级为"不可回写"——按顶层
            // UNION/INTERSECT/EXCEPT 切成 N 个分支，逐分支独立判定 + 注入，各分支列数/位置天然对齐
            // （SQL 语义保证），互不影响：某分支不含白名单表或含 GROUP BY，只让该分支的锚点列取
            // NULL::uuid，不阻断整个视图可回填。
            for (int[] range : splitTopLevelBranches(masked)) {
                int bStart = range[0], bEnd = range[1];
                List<TableMatch> branchMatches = new ArrayList<>();
                for (TableMatch mt : matches) {
                    if (mt.start >= bStart && mt.start < bEnd) branchMatches.add(mt);
                }
                // 分支同深度（相对分支自身顶层，即整篇 depth==0）含 GROUP BY——聚合结果一行对应多源行，
                // 裸 id 引用既非法也无意义，本分支恒 NULL::uuid（不看是否命中白名单表）。
                boolean groupBy = hasGroupByInRange(masked, bStart, bEnd, 0);
                TableMatch chosen = null;
                if (!groupBy) {
                    // ⚠️ 只看分支自身顶层（depth==0 相对整篇文档，即分支未嵌套进任何子查询）的命中——
                    // 不能像非 set-op 单分支那样"退化为任意深度第一个 FROM"：分支内常见形如
                    // NOT EXISTS (SELECT 1 FROM material_bom_item x WHERE ...) 的相关子查询同样含
                    // 白名单表 token，但那是子查询自己的行来源，不是本分支的行来源——若误选为 chosen，
                    // 插入位置会落进子查询自己的 SELECT 列表（该子查询的列数对 EXISTS 语义无影响，
                    // 不报错），本分支自己的outer SELECT 实际未增加任何列，与"真的注入了顶层锚点"的
                    // 其它分支列数错位，报 "each UNION query must have the same number of columns"
                    // （2026-07-27 对 cp_view/ll_view 实测复现；与 jg_view/mc_view/wg_view 注释里
                    // "铁律1"记录的同根问题对称，只是这里是子查询而非 CTE）。
                    for (TableMatch mt : branchMatches) {
                        if ("FROM".equals(mt.keyword) && depthAt(masked, mt.start) == 0) { chosen = mt; break; }
                    }
                    if (chosen == null) {
                        for (TableMatch mt : branchMatches) {
                            if ("JOIN".equals(mt.keyword) && depthAt(masked, mt.start) == 0) { chosen = mt; break; }
                        }
                    }
                }

                int anchorPos = chosen != null ? chosen.start : bEnd;
                int selectPos = findOwningSelect(masked, anchorPos);
                if (selectPos < 0) continue; // 理论不发生（每个分支必有自己的 SELECT）：保守跳过不注入

                int insertPos = selectPos + 6; // "SELECT".length()
                Matcher distinctM = DISTINCT_KW.matcher(
                    sqlTemplate.substring(insertPos, Math.min(insertPos + 24, sqlTemplate.length())));
                if (distinctM.find()) insertPos += distinctM.end();

                String frag;
                if (chosen != null) {
                    frag = " " + chosen.alias + ".id AS " + ANCHOR_COLUMN + ",";
                    if (!anchorInjected) {
                        // Result.primaryTable/primaryAlias = 第一个（按分支出现顺序）成功注入真实
                        // 锚点的分支——不是"第一个命中白名单表的分支"，二者仅在该分支同时命中 GROUP BY
                        // 时才有差异（此时该分支锚点是 NULL，不该被当作 primary，见需求说明 §3.2）。
                        anchorInjected = true;
                        primaryTable = chosen.table;
                        primaryAlias = chosen.alias;
                        primaryBranchRange = range;
                    }
                } else {
                    frag = " NULL::uuid AS " + ANCHOR_COLUMN + ",";
                }
                edits.add(new Edit(insertPos, insertPos, frag));
            }
        } else if (injectAnchor) {
            // 非 set-op：原单分支主位探测逻辑不变（顶层没有则退化为任意深度第一个 FROM）。
            TableMatch primary = null;
            for (TableMatch mt : matches) {
                if ("FROM".equals(mt.keyword) && depthAt(masked, mt.start) == 0) { primary = mt; break; }
            }
            if (primary == null) {
                for (TableMatch mt : matches) {
                    if ("FROM".equals(mt.keyword)) { primary = mt; break; }
                }
            }
            if (primary != null && !hasGroupByAtDepth(masked, depthAt(masked, primary.start))) {
                int selectPos = findOwningSelect(masked, primary.start);
                if (selectPos >= 0) {
                    int insertPos = selectPos + 6; // "SELECT".length()
                    Matcher distinctM = DISTINCT_KW.matcher(
                        sqlTemplate.substring(insertPos, Math.min(insertPos + 24, sqlTemplate.length())));
                    if (distinctM.find()) insertPos += distinctM.end();
                    String anchorFrag = " " + primary.alias + ".id AS " + ANCHOR_COLUMN + ",";
                    edits.add(new Edit(insertPos, insertPos, anchorFrag));
                    primaryTable = primary.table;
                    primaryAlias = primary.alias;
                    anchorInjected = true;
                }
            }
        }

        // 统一按起始位置降序应用编辑（同起点则先应用范围更大的，即表替换优先于零长度锚点插入不会发生冲突，
        // 因为锚点插入点恒在 SELECT 关键字之后、任何表 token 之前，位置互斥不重叠；跨分支同理，各分支
        // 文本区间互不重叠）。repair-260830 的函数补参编辑落在 f_material_element_price(...) 的闭括号处，
        // 与表 token 区间、锚点插入点同样互不重叠。
        String rewrittenSql = applyEdits(sqlTemplate, edits);

        String primaryBranchSql = primaryBranchRange != null
            ? buildPrimaryBranchSql(sqlTemplate, masked, edits, primaryBranchRange) : null;

        return new Result(rewrittenSql, anchorInjected, primaryTable, primaryAlias, touched, primaryBranchSql);
    }

    /**
     * repair-0727 B1/B2：从已计算好的全局 {@code edits}（仍是相对 {@code sqlTemplate} 原文坐标，未应用）
     * 中筛出落在主位分支区间内的编辑，重放到该分支的原文子串上，产出一份独立可执行的分支 SQL。
     *
     * <p>若该分支不是文档里的第一个分支、且文档存在顶层 WITH 前导（分支可能依赖某个 CTE），一并拼接
     * 前导文本，保证分支单独执行时仍语法合法——即使该分支实际未引用任何 CTE，多余的 WITH 前导对
     * {@code LIMIT 0} 探测无副作用（未引用的 CTE 不影响输出列/结果）。
     */
    private static String buildPrimaryBranchSql(String sqlTemplate, String masked, List<Edit> edits, int[] range) {
        int bStart = range[0], bEnd = range[1];
        List<Edit> branchEdits = new ArrayList<>();
        for (Edit e : edits) {
            if (e.start() >= bStart && e.end() <= bEnd) {
                branchEdits.add(new Edit(e.start() - bStart, e.end() - bStart, e.replacement()));
            }
        }
        branchEdits.sort((a, b) -> {
            int c = Integer.compare(b.start(), a.start());
            return c != 0 ? c : Integer.compare(b.end(), a.end());
        });
        StringBuilder bsb = new StringBuilder(sqlTemplate.substring(bStart, bEnd));
        for (Edit e : branchEdits) bsb.replace(e.start(), e.end(), e.replacement());

        int firstSelect = firstTopLevelSelect(masked);
        if (firstSelect >= 0 && firstSelect < bStart) {
            String preamble = sqlTemplate.substring(0, firstSelect);
            return preamble + bsb;
        }
        return bsb.toString();
    }
}
