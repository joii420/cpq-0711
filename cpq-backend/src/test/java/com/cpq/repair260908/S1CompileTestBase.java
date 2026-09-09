package com.cpq.repair260908;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>S-1 分片（编译器 · 零写入）</b>的公共基座。
 *
 * <h3>🚨 本片的写入面 = 空</h3>
 * 只做三件事：① 只读查 {@code component_sql_view} / {@code semantic_*} / {@code information_schema}；
 * ② 调 {@code POST /api/cpq/components/{cid}/builder/compile}（<b>编译不落库</b>）；
 * ③ 以<b>字面量绑定</b>方式只读执行编译产物（{@code SELECT} 而已）。
 * <b>不 INSERT / 不 UPDATE / 不 DELETE / 不建表 / 不造数</b> ⇒ 无需造数前缀，可与任意片并行。
 *
 * <h3>🚫 共库纪律（testing.md §4.5）</h3>
 * 本片<b>不做任何全局计数断言</b>。🚫 不写「{@code component_sql_view} 共 28 条」——
 * 并发线随时可能建新视图，那种断言会被打红，而且<b>红得像业务回归</b>。
 * 一律断言「点名的那些视图」+「对全部扫到的视图都成立的不变量」。
 *
 * <h3>🚨 语义图指纹守卫（2026-09-08 主线交办）</h3>
 * {@code SemanticGraphLoader} 读 {@code semantic_node_column} 时<b>没有 ORDER BY</b>，
 * 编译器按 PG <b>堆顺序</b>遍历 ⇒ 任何对该表的 {@code UPDATE} 都会把行的新版本写到别的页面、
 * 重排字段顺序、进而改变编译产物里 {@code SELECT} 列的次序。
 * 实测：迁移 V433 只 UPDATE 了 11 行的 {@code roles}，就让 11 个核价数据源的字段顺序错位。
 * <p>⇒ <b>凡「产物应当逐字节不变」的断言，判红之前必须先证明这四张表没被动过</b>
 * （{@link #graphFingerprint()}）。指纹不一致 ⇒ 归因为<b>并发写入</b>，
 * 用 {@link #concurrentGraphWrite} 的措辞硬失败并要求重跑，
 * 🚫 不许解读成「我的改动引入的核价回归」。
 *
 * <h3>⚠️ 不 import 任何 {@code src/main} 下的类</h3>
 * 用例从 AC 派生，不从实现派生。所有接口调用按 {@code api.md} / 既有测试（{@code V9TestBase}）拼。
 */
public abstract class S1CompileTestBase {

    // ═══════════════════ 被点名的对象（AC 原文里写死的，不是我造的） ═══════════════════

    /** AC-1b 的被测视图：{@code QT-20260908-0624}「产品」页签。 */
    protected static final String AC1B_VIEW = "builder_a71947b68d50";

    /** AC-1b 的被测报价单。 */
    protected static final String AC1B_QUOTATION_ID = "4ce0fcc4-a73b-4672-ba45-6387e03491ad";

    /** AC-1b 的客户。 */
    protected static final String AC1B_CUSTOMER = "CUST-0004";

    /** AC-16 点名的 3 个 {@code COST_BASIC} 视图。 */
    protected static final List<String> AC16_VIEWS =
            List.of("builder_a515014e6ed3", "builder_32ab8212df6c", "builder_9291b050b6a9");

    /**
     * {@code ./证据/材料-并发线交接-260908.md §3} 的 28 个视图清单 —— <b>AC-2b 要与它背靠背比对</b>。
     *
     * <p>🔑 它是<b>对方独立算出来</b>的集合。我这边用完全不同的判据（语义图锚点 + {@code information_schema}
     * 列存在性）再算一遍；两边不一致就说明有一边判据错了。
     * 🚫 但<b>不断言「总数恰为 28」</b> —— 并发线随时可能建新视图（共库纪律）。
     * 只断言「这 28 个我都扫到了，且分类与对方一致」。
     */
    protected static final List<String> HANDOFF_28 = List.of(
            "builder_32ab8212df6c", "builder_452bbdbe13f7", "builder_eb834021ed65",
            "builder_661e1a387f89", "builder_b9126e2bdef8", "builder_bc018fbefa1b",
            "builder_cb4c1af4eabf", "builder_c127b4ddb20e", "builder_d277107524d3",
            "builder_04375490927f", "builder_ba7c13ca994f", "builder_ca66ea9a93aa",
            "builder_39c738327543", "builder_c6a71e5e217a", "builder_f6d51727bd1e",
            "builder_196aadeeb89f", "builder_42dd870ccd17", "builder_fb378075db5d",
            "builder_6c4acfecbe39", "builder_a2e1bb386be1", "builder_ea992eb4216d",
            "builder_7277969cc41c", "builder_a515014e6ed3", "builder_a71947b68d50",
            "builder_9cc11850f425", "builder_9291b050b6a9", "builder_ba81142a5ddb",
            "builder_dc3297cce9a3");

    @Inject
    protected EntityManager em;

    private static String cachedSession;

    // ═══════════════════ 只读 SQL 助手 ═══════════════════

    private void bind(Query q, Object[] p) {
        for (int i = 0; i < p.length; i++) {
            q.setParameter(i + 1, p[i]);
        }
    }

    protected String scalarStr(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        List<?> l = q.getResultList();
        return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
    }

    protected long scalarLong(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        return ((Number) q.getSingleResult()).longValue();
    }

    protected List<String> strList(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        List<String> out = new ArrayList<>();
        for (Object o : q.getResultList()) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rowList(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        return (List<Object[]>) q.getResultList();
    }

    /** 某表 / 视图的实际列（{@code information_schema} 权威）。判据用它，🚫 不按方言、不按表名前缀猜。 */
    protected List<String> columnsOf(String relation) {
        return strList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 ORDER BY 1", relation);
    }

    protected boolean hasCustomerNo(String relation) {
        return relation != null && columnsOf(relation).contains("customer_no");
    }

    // ═══════════════════ 语义图指纹守卫 ═══════════════════

    /**
     * 四张语义图表的<b>内容 + 物理堆序</b>指纹。
     *
     * <p>🔑 <b>按 {@code ctid} 排序</b>而不是按 {@code id}：编译器读的是无 ORDER BY 的 {@code listAll()}，
     * 拿到的就是堆序。只哈希内容会漏掉「内容没变但行被搬了页」这种情况 ——
     * 而那恰恰是 V433 造成字段错位的机制。
     *
     * <p>⚠️ 已知残余风险（写进报告，不要当成已消除）：PG 的 {@code synchronize_seqscans} 默认开启，
     * 并发顺序扫可能从中途页开始 ⇒ 即便指纹一致，两次 {@code listAll()} 仍可能返回不同顺序。
     * 本守卫能挡住「被 UPDATE 搬过」这一类，<b>挡不住</b>扫描起点漂移。
     * 根治只能靠实现侧补 {@code ORDER BY}（并发线在做）。
     */
    protected String graphFingerprint() {
        return scalarStr("""
                SELECT
                  (SELECT count(*)||':'||coalesce(md5(string_agg(id::text||'#'||coalesce(roles::text,'')
                       ||'#'||coalesce(sort_order::text,'')||'#'||coalesce(db_column,'')
                       ||'#'||coalesce(status,''), ',' ORDER BY ctid)),'-') FROM semantic_node_column)
                  ||' | '||
                  (SELECT count(*)||':'||coalesce(md5(string_agg(id::text||'#'||coalesce(physical_table,'')
                       ||'#'||coalesce(status,''), ',' ORDER BY ctid)),'-') FROM semantic_node)
                  ||' | '||
                  (SELECT count(*)||':'||coalesce(md5(string_agg(id::text||'#'||coalesce(edge_kind,'')
                       ||'#'||coalesce(status,''), ',' ORDER BY ctid)),'-') FROM semantic_edge)
                  ||' | '||
                  (SELECT count(*)||':'||coalesce(md5(string_agg(id::text||'#'||coalesce(tab_type,'')
                       ||'#'||coalesce(variant_key,''), ',' ORDER BY ctid)),'-') FROM semantic_tab_view)
                """);
    }

    /** 指纹漂移时的统一措辞 —— 明确它<b>不是本次改动引入的核价回归</b>。 */
    protected static String concurrentGraphWrite(String ac, String before, String after, String detail) {
        return "[" + ac + "] 🚨【并发写入干扰，非产品缺陷 —— 请重跑，🚫 不要报成核价回归】"
                + "\n  两次编译之间 semantic_* 指纹变了："
                + "\n    before = " + before
                + "\n    after  = " + after
                + "\n  成因：SemanticGraphLoader 读 semantic_node_column 无 ORDER BY，编译器按 PG 堆顺序遍历；"
                + "任何 UPDATE 都会把行搬到别的页面 ⇒ SELECT 列顺序变 ⇒ 产物 md5 变（V433 实证：只 UPDATE 11 行 roles"
                + "，就让 11 个核价数据源字段错位）。"
                + "\n  处置：等并发线的 `Sort.by(nodeId, sortOrder)` 进 master，或挑无并发窗口重跑。"
                + "\n  ⚠️ 本条 AC 判定为『未验证』，🚫 不得记为通过，也不得记为回归。"
                + (detail == null ? "" : "\n  附：" + detail);
    }

    /** 环境前置未就绪的统一措辞（{@code skip != pass}，一律硬失败）。 */
    protected static String notReady(String ac, String what, String owner) {
        return "[" + ac + "] 🔴【环境/前置未就绪，非产品缺陷】" + what
                + (owner == null ? "" : "（应由 " + owner + " 交付）")
                + "\n  ⚠️ 本条 AC 判定为『未验证』。🚫 不得因为『看起来只是没做完』就当成通过 —— skip != pass。";
    }

    // ═══════════════════ 登录 + 编译端点 ═══════════════════

    protected String session() {
        if (cachedSession == null) {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                    .when().post("/api/cpq/auth/login");
            if (r.statusCode() != 200) {
                throw new AssertionError(notReady("登录", "admin 登录返 " + r.statusCode()
                        + "，body=" + r.asString()
                        + "\n  依次排查：① Redis 登录限流（30 次/分/IP，本类已静态缓存 session 避免自撞）；"
                        + "② admin 被 E2E 置成 INACTIVE（历史事故）；③ locked_until。", null));
            }
            cachedSession = r.cookie("CPQ_SESSION");
            assertNotNull(cachedSession, "登录 200 但没拿到 CPQ_SESSION cookie");
        }
        return cachedSession;
    }

    /**
     * 一个待验视图的全部只读事实。
     *
     * @param anchorTable        <b>从语义图取</b>的锚点物理表（{@code semantic_tab_view.anchor_node_id}
     *                           → {@code semantic_node.physical_table}）。
     *                           🚫 <b>不是</b>正则从 SQL 里抽的 —— 那正是并发线翻车的做法。
     * @param storedSql          库里现存的 {@code sql_template}（= 改动前基线，B-6 跑之前有效）
     * @param builderConfigJson  {@code builder_config} 原文，原样回灌 compile 端点
     */
    public record ViewRow(String viewName, String componentId, String dialect, String tabType,
                          String variantKey, String anchorTable, boolean anchorHasCustomerNo,
                          String storedSql, String builderConfigJson) {
    }

    /**
     * 扫库里全部 {@code builder_*} 视图。
     *
     * <p>🔑 锚点靠 {@code (dialect, tabType, variantKey)} 三元组回查语义图。
     * ⚠️ {@code semantic_tab_view.variant_key} 存的是<b>空串</b>不是 NULL，而 {@code builder_config->>'variantKey'}
     * 有的是空串、有的是 JSON null ⇒ 两边都要 {@code coalesce(nullif(...,''),'')} 归一，
     * 否则非「费用类」的 13 个视图会全部 join 不上、{@code anchorTable} 全 null，
     * 而断言会「因为查不到所以没检查」地静默通过。
     */
    protected List<ViewRow> builderViews() {
        List<Object[]> rows = rowList("""
                SELECT v.sql_view_name,
                       v.component_id::text,
                       v.builder_config->>'dialect',
                       v.builder_config->>'tabType',
                       coalesce(v.builder_config->>'variantKey',''),
                       n.physical_table,
                       v.sql_template,
                       v.builder_config::text
                FROM component_sql_view v
                LEFT JOIN semantic_tab_view tv
                       ON tv.dialect  = v.builder_config->>'dialect'
                      AND tv.tab_type = v.builder_config->>'tabType'
                      AND coalesce(tv.variant_key,'') = coalesce(v.builder_config->>'variantKey','')
                      AND tv.status = 'ACTIVE'
                LEFT JOIN semantic_node n ON n.id = tv.anchor_node_id
                WHERE v.sql_view_name LIKE 'builder\\_%'
                ORDER BY v.sql_view_name
                """);
        List<ViewRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            String anchor = r[5] == null ? null : String.valueOf(r[5]);
            out.add(new ViewRow(
                    String.valueOf(r[0]), String.valueOf(r[1]),
                    r[2] == null ? null : String.valueOf(r[2]),
                    r[3] == null ? null : String.valueOf(r[3]),
                    String.valueOf(r[4]),
                    anchor, anchor != null && hasCustomerNo(anchor),
                    r[6] == null ? null : String.valueOf(r[6]),
                    r[7] == null ? null : String.valueOf(r[7])));
        }
        return out;
    }

    /**
     * 按视图存的 {@code builder_config} 重放编译器（= B-6 {@code recompile=true} 干的事），返回产物 SQL。
     *
     * <p>🚨 <b>零写入</b>：{@code /builder/compile} 只编译不落库（{@code api.md §2}；
     * {@code V9CompileArtifactTest} 类注释亦如此声明）。
     */
    protected String recompile(ViewRow v) {
        assertNotNull(v.builderConfigJson(), notReady("recompile",
                v.viewName() + " 的 builder_config 为空，无法重放编译器", "取数配置器"));
        Response r = RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON).body(v.builderConfigJson())
                .when().post("/api/cpq/components/{cid}/builder/compile", v.componentId());
        assertEquals(200, r.statusCode(), "重编译 " + v.viewName() + "（component=" + v.componentId()
                + "，dialect=" + v.dialect() + "/" + v.tabType() + "/" + v.variantKey()
                + "）应 200，实际 " + r.statusCode() + "\n  body=" + trunc(r.asString())
                + "\n  ⚠️ 若这里挂了，B-6 的 recompile=true 也会挂（同一条路径），请报主线。");
        String sql = r.jsonPath().getString("sql");
        assertNotNull(sql, "重编译 " + v.viewName() + " 的响应缺 sql 字段。body=" + trunc(r.asString()));
        assertTrue(!sql.isBlank(), "重编译 " + v.viewName() + " 返回空 SQL —— 空产物会让后续所有结构断言空跑");
        return sql;
    }

    // ═══════════════════ 字面量绑定执行（AC-1b 用；模拟 /preview 的 bindLiterals 口径） ═══════════════════

    /**
     * 把 {@code :customerCode} / {@code :total_material_no} 换成<b>字面量</b>后只读执行。
     *
     * <p>为什么不用 JDBC 命名参数：{@code = ANY(:total_material_no)} 要绑 {@code text[]}，
     * Hibernate 原生查询绑数组的行为随版本漂移，绑错时的症状是「0 行」——
     * 与本次缺陷修好后的症状<b>长得一模一样</b>，会把假绿和真绿混在一起。
     * 字面量替换是 {@code BuilderService.bindLiterals()}（{@code /preview}）用的同一口径，可控且可打印。
     *
     * <p>🚨 替换后<b>硬检查不许再有未绑定的 {@code :xxx}</b> —— 否则查询会因别的原因报错/返 0 行，
     * 而我们会把它读成「谓词生效了」。
     */
    protected List<String> runCompiledSingleColumn(String ac, String sql, String customerCode,
                                                   List<String> totalMaterialNo, String outColumn) {
        String bound = sql
                .replace(":customerCode", sqlLiteral(customerCode))
                .replace(":total_material_no", textArrayLiteral(totalMaterialNo));
        String leftover = firstUnboundParam(bound);
        assertTrue(leftover == null, notReady(ac,
                "编译产物里还有未绑定的占位符 `" + leftover + "`，本片只会绑 :customerCode / :total_material_no。"
                        + "\n  🚫 不许带着它硬跑 —— 报错或 0 行会被误读成『谓词生效』。SQL=\n" + sql, null));

        String wrapped = "SELECT x." + outColumn + " FROM (\n" + stripTrailingSemicolon(bound) + "\n) x";
        List<String> got = strList(wrapped);
        System.out.println("[" + ac + "] 执行产物 → " + got.size() + " 行；customerCode=" + customerCode
                + "，闭包 " + totalMaterialNo.size() + " 个料号\n" + wrapped);
        return got;
    }

    private static String stripTrailingSemicolon(String s) {
        String t = s.strip();
        return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
    }

    /** 找出第一个仍未绑定的 {@code :name}（跳过 {@code ::type} 强转）。 */
    protected static String firstUnboundParam(String sql) {
        var m = java.util.regex.Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
        while (m.find()) {
            int s = m.start();
            if (s > 0 && sql.charAt(s - 1) == ':') {
                continue;
            }
            return ":" + m.group(1);
        }
        return null;
    }

    protected static String sqlLiteral(String s) {
        return s == null ? "NULL" : "'" + s.replace("'", "''") + "'";
    }

    protected static String textArrayLiteral(List<String> xs) {
        if (xs == null || xs.isEmpty()) {
            return "ARRAY[]::text[]";
        }
        StringBuilder sb = new StringBuilder("ARRAY[");
        for (int i = 0; i < xs.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(sqlLiteral(xs.get(i)));
        }
        return sb.append("]::text[]").toString();
    }

    // ═══════════════════ 归档基线（AC-4 用） ═══════════════════

    /** 由 cwd（通常 {@code cpq-backend/}）向上找含 {@code dev-docs} 的仓库根。 */
    protected static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("dev-docs"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new AssertionError(notReady("repoRoot",
                "向上 6 层都没找到 dev-docs，cwd=" + Path.of("").toAbsolutePath()
                        + "。请在 worktree 的 cpq-backend/ 下跑 ./mvnw test", null));
    }

    /**
     * 🚨 <b>基线必须归档进任务目录</b>（testing.md：证据形式的判据 = 下一轮跑测试会不会把它删掉）。
     * 🚫 不许放 {@code target/} —— 那一轮 clean 就没了，然后 AC-4 会变成「基线缺失 ⇒ 未验证」，
     * 或者更糟：变成「空对空 ⇒ 相同」。
     */
    protected static Path baselineDir() {
        return repoRoot().resolve("dev-docs").resolve("task-260819-取数配置器")
                .resolve("repair-260908-页签重复行与跨客户串号")
                .resolve("证据").resolve("基线-核价编译产物-改动前");
    }

    protected static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected static String trunc(String s) {
        return s == null ? "null" : (s.length() > 1500 ? s.substring(0, 1500) + "…(截断)" : s);
    }

    /** 打印一张对照表，便于人工与 psql 结果背靠背核对（证据要留得下来）。 */
    protected static void dump(String title, Map<String, ?> m) {
        System.out.println("── " + title + " ──");
        m.forEach((k, v) -> System.out.println("   " + k + " = " + v));
    }

    protected static Map<String, Object> newMap() {
        return new LinkedHashMap<>();
    }
}
