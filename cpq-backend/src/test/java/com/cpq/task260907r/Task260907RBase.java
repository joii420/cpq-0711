package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * task-260907 第二段（{@code _record} 数据层 + 元素价格快照 + 核价通过回填升版）验收用例的公共基座。
 *
 * <h3>断言来源 —— 🚫 不读实现</h3>
 * 每条断言指回 {@code dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/需求文档.md §③}
 * 的 AC 原文，接口形状指回同目录 {@code api.md}。本套用例<b>一行都不从</b>
 * {@code com.cpq.quotation.service.**} / {@code com.cpq.dataset.**} / {@code com.cpq.priceadjust.**} 反推。
 * 理由：从实现反推的测试与实现共享同一个理解偏差 —— 实现理解错了需求，测试跟着错，于是永远全绿。
 *
 * <h3>🚨 共享库纪律（{@code CLAUDE.md} §3.2 / {@code test.md} §二）</h3>
 * {@code ./mvnw test} 走 {@code application-test.properties}，其默认库<b>就是共享开发库
 * {@code cpq_db_0724}</b>（不是独立测试库；本机 {@code localhost:5432} 备用库实测未运行）。因此：
 * <ul>
 *   <li>🚫 <b>严禁 TRUNCATE / DROP / 无 WHERE 的 DELETE</b>，哪怕写在 {@code @BeforeAll} 里。</li>
 *   <li>✅ 只读断言 + {@link #inRollback} 构造性零残留是<b>首选</b>：事务永不提交，连清理动作都不需要，
 *       用例中途崩溃也不会留脏数据。</li>
 *   <li>✅ 必须 committed 的夹具（HTTP 端到端用例看不见未提交事务）一律带前缀
 *       {@link #PREFIX} = {@code T260907R-}，清理只按前缀删自己的，并且<b>先 count 再删</b>。</li>
 *   <li>⚠️ 现网已有别的会话留下的 {@code T260907-M1} / {@code T260907T-} 夹具 —— <b>本套一律不碰、不复用</b>。
 *       料号 {@code S-3120014539} 有被手工改过版本号的迹象，<b>同样不作夹具</b>。</li>
 *   <li>🚫 不改共享库的全局状态（用户启停用、角色、模板发布态、系统配置）。登录只解锁 admin，
 *       不改其密码/状态/角色。</li>
 * </ul>
 *
 * <h3>🚨 假绿防线（{@code test.md} §一 的四个风险点）</h3>
 * <ol>
 *   <li><b>11/13 张带版本表现网 0 行</b>（8 张费用表 + 3 张年降表全部 count=0）⇒ 凡「逐表如何如何」的断言
 *       一律<b>自造夹具</b>，并在断言前先 {@link #assertFixtureNonEmpty} 证明基底非空，
 *       否则循环 0 次、断言空跑，用例照样报绿。</li>
 *   <li><b>跨版路径现网造不出来</b> ⇒ 跨版用例必须<b>自己先把组升上去</b>，不许指望现网数据。</li>
 *   <li><b>{@code origin_id} 失效必须先被证明</b> ⇒ 跨版支路的前置断言：升版前后主表 {@code id} 集合<b>交集为空</b>
 *       （{@link #assertIdSetsDisjoint}）。不先证明这一点，「指纹兜底生效了」无从谈起。</li>
 *   <li><b>进程级假绿</b>（{@code RECORD.md} 2026-09-06 实证：{@code task-260819} 因 V417 不在 master，
 *       Quarkus 根本没起来，44 个用例全 skip，主线差点据此下反向结论）⇒ {@link #assertProcessAlive} 在每个
 *       用例前打一枪阳性对照，拿不到预期响应就<b>硬失败</b>，不许让后面的断言以「业务失败」的面目出现。</li>
 *   <li>🆕 <b>「不变量」裹住了活数据，它就还是变量</b>（{@code 需求文档.md} §③ 末「共同纪律①」，
 *       2026-09-07 并发会话实证：<b>master 同码</b>重采基线，14 个端点 13 个逐字节相同、1 个仍漂移
 *       —— {@code total} 47→49 是别的会话写的。同码、纯数据漂移，diff 照红）⇒ 比对范围一律用
 *       {@link #scopedDigest} 按本次夹具的轴值/前缀收窄，<b>🚫 禁止整表 md5</b>；确需整体比时拆
 *       {@link #assertStructureIdentical}（结构层严格）+ 数据层逐条归因两层。</li>
 *   <li>🆕 <b>diff 之前没先断言 HTTP 状态码</b>（同上会话实证：一轮 session 过期，14 个端点全返 401，
 *       diff 忠实报出「14/14 全漂移」，差点产出「本任务把所有维护端点都搞坏了」的<b>假红</b>报告）
 *       ⇒ 任何 diff 类断言之前先过 {@link #requireStatusBeforeDiff}，状态码不对直接判<b>用例环境失败</b>，
 *       🚫 不许让它流进 diff 结论。</li>
 * </ol>
 */
public abstract class Task260907RBase {

    // ─────────────────────────── 常量 ───────────────────────────

    /** 🚨 本任务统一夹具前缀。收尾核对一律按它查；查出 0 条 = 查错了前缀，不是「很干净」。 */
    public static final String PREFIX = "T260907R-";

    /** {@code customer.code} / {@code customer_no} 落 varchar(20) ⇒ 前缀必须短。 */
    public static final String CUST_PREFIX = "T2609R";

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 13 张带版本的 {@code ds_quote_*} 主表 —— 🚫 <b>不作为 AC-1 的判据</b>（AC 原文明令「不锁表数」）。
     * 此处只用于夹具与日志可读性；AC-1① 的判据是 {@code 需求文档.md} 给的那条集合关系 SQL。
     */
    protected static final List<String> VERSIONED_TABLES_HINT = List.of(
            "ds_quote_material_bom", "ds_quote_element_bom",
            "ds_quote_incoming_fixed_fee", "ds_quote_incoming_other_fee", "ds_quote_incoming_recovery",
            "ds_quote_self_process_fee", "ds_quote_finished_other_fee", "ds_quote_sub_component_fee",
            "ds_quote_assembly_fee", "ds_quote_assembly_fee_annual", "ds_quote_plating_fee",
            "ds_quote_incoming_annual", "ds_quote_annual_discount");

    /** 免版本三张表（AC-1③：它们<b>不该</b>有 {@code _record}）。 */
    protected static final List<String> UNVERSIONED_TABLES_HINT =
            List.of("ds_quote_material", "ds_quote_customer_part", "ds_quote_plating_scheme");

    protected static final String PREVIEW = "/api/cpq/quotations/%s/costing-approve/preview";
    protected static final String APPROVE = "/api/cpq/quotations/%s/costing-approve";

    @Inject
    protected EntityManager em;

    /**
     * 🚨 <b>轴归属登记：{@code material_no → 该组所属的 customer_no / Fx}</b>。
     *
     * <h3>为什么必须有它（2026-09-07 合并 master 后实测倒逼）</h3>
     * 上游把报价侧的轴从 {@code material_no} 一维改成<b>复合轴 {@code (customer_no, material_no)}</b>。
     * ⇒ 本套用例里最常见的那个形态 —— <b>先造主表组、再另建一张单去表征它</b> —— 在<b>换了客户</b>之后
     * 就<b>不再指向同一个组</b>了。
     *
     * <p>🔬 实测症状（{@code AnchorFourTiersAcTest} 4 条全红）：预览返
     * {@code baseVersionNo:0 / currentVersionNo:0 / result:CREATED / unanchoredRows 全 NO_ANCHOR}
     * —— <b>主表那一组根本查不到</b>。
     * ⚠️ 这个症状看起来<b>非常像</b>「锚定机制坏了」，实际是<b>夹具指错了组</b>。
     * 不显式登记轴归属，下一个人还会再踩一次，而且大概率会去修实现。
     *
     * <p>⇒ {@link #newSubmittedOrder} 在建单前先查这张表：该料号已有归属客户 ⇒ <b>复用它</b>；
     * 没有 ⇒ 新建客户并登记。这样「同一个 materialNo」在一个用例里<b>恒等于同一个轴</b>，
     * 而不依赖每个调用点自己记得传对客户。
     * <p>🔑 要<b>故意</b>造跨客户的两个组时，显式用 {@link #newFixture} + {@link #submitOrderOn}，
     * 🚫 不要绕过本登记表。
     */
    private final Map<String, Fx> axisOwner = new LinkedHashMap<>();

    /**
     * 🚨 <b>本进程自己造过的轴值</b>（{@code material_no}）—— {@link #cleanupOwnDatasetRows} 只删这些。
     *
     * <h3>🕰️ 2026-09-07 收窄（差点酿事故）</h3>
     * 原来清理写的是 {@code DELETE ... WHERE material_no LIKE 'T260907R-%'} —— <b>整前缀全清</b>。
     * 那在「只有我一个人用这个前缀」时是对的，但主线已告知：<b>后端代理同期也在用
     * {@code T260907R-} 前缀</b>做 C′ 证据，且我俩在同一个 worktree、同一个共享库。
     * ⇒ 我每跑完一条用例，就会把它正在用的夹具<b>连带删掉</b>，
     * 而症状会出现在<b>它那边</b>，表现为「夹具莫名其妙没了 / 断言空跑」——
     * 极难归因到「另一个进程的 @AfterEach」。
     *
     * <p>🔑 判据从<b>「前缀是我的命名空间」</b>改成<b>「这一行是我这个进程亲手造的」</b>。
     * 前者在多方共用同一前缀时<b>不再成立</b>；后者永远成立。
     * ⚠️ 代价是「没登记到的行会残留」—— <b>残留远好过误删别人的在途夹具</b>：
     * 残留是可见的、可事后清的；误删是沉默的、且对方正在用。
     */
    private final Set<String> createdAxisValues = new LinkedHashSet<>();

    /** 登记一个本进程造出来的轴值。所有会往 {@code ds_quote_*} 写行的入口都要调它。 */
    protected void trackAxis(String materialNo) {
        if (materialNo != null && !materialNo.isBlank()) createdAxisValues.add(materialNo);
    }

    /** 本轮 committed 夹具登记，{@link #cleanupOwnFixtures} 只按前缀删这些。 */
    protected final List<UUID> createdQuotations = new ArrayList<>();
    protected final List<UUID> createdCustomers = new ArrayList<>();

    private static Map<String, String> ADMIN_COOKIES;

    // ─────────────────────────── 假绿防线 ───────────────────────────

    @BeforeEach
    void processAliveGate() {
        assertProcessAlive();
    }

    /**
     * 🚨 风险点 4 的阳性对照：证明<b>被测进程真的起来了</b>。
     *
     * <p>不打这一枪的后果不是「少一步」，而是：Quarkus 起不来时用例可能以 skip / 集体 error 的形态出现，
     * 而读报告的人会把它读成「没问题」或「业务缺陷」。这里用一个<b>已知语义的端点</b>做阳性对照 ——
     * 带 {@code @RoleAllowed} 的业务端点在未登录时必须返 401：拿到 401 = 应用在跑 + 鉴权链路正常。
     * 拿到 200 反而是异常（RBAC 被关了，那 AC-5 的 403 断言就失去意义）。
     */
    protected void assertProcessAlive() {
        Response r = RestAssured.given().when().get("/api/cpq/components").thenReturn();
        assertEquals(401, r.statusCode(),
                "🚨 进程级前置未满足：未登录访问 /api/cpq/components 期望 401（应用在跑且 RBAC 生效），"
                        + "实际 " + r.statusCode() + "。这是**环境结论**不是业务结论 —— "
                        + "拿不到 401 就不许解读本类任何测试结果。body=" + r.asString());
    }

    // ─────────────────────────── 就绪闸（⛔ B-1/B-3 建表迁移落库前，本套无法执行）───────────────────────────

    /** 13 张主表里，尚未建出 {@code _record} 的表（AC-1① 判据 SQL 的原文）。 */
    @SuppressWarnings("unchecked")
    protected List<String> tablesMissingRecord() {
        return em.createNativeQuery(
                        "SELECT t.table_name FROM information_schema.tables t "
                                + "WHERE t.table_schema='public' AND t.table_name LIKE 'ds\\_quote\\_%' "
                                + "  AND t.table_name NOT LIKE '%\\_history' AND t.table_name NOT LIKE '%\\_record' "
                                + "  AND EXISTS(SELECT 1 FROM information_schema.columns c "
                                + "             WHERE c.table_name=t.table_name AND c.column_name='version_no') "
                                + "  AND NOT EXISTS(SELECT 1 FROM information_schema.tables r "
                                + "                 WHERE r.table_name = t.table_name || '_record') "
                                + "ORDER BY 1")
                .getResultList();
    }

    protected boolean recordLayerReady() {
        return tablesMissingRecord().isEmpty();
    }

    /**
     * ⛔ 前置硬检查：{@code _record} 层未建成时<b>立刻硬失败并说清这是环境前置</b>。
     *
     * <p>🚫 <b>刻意不用 {@code Assumptions.assumeTrue}</b>：assume 会把用例标成 skip，
     * 而 skip 在 surefire 汇总里长得和「通过」几乎一样 —— 那正是 {@code test.md} 风险点 4
     * 要防的形态（44 个用例全 skip 被读成没问题）。宁可红，红是可见的。
     */
    protected void requireRecordLayer() {
        List<String> missing = tablesMissingRecord();
        if (!missing.isEmpty()) {
            fail("⛔ 环境前置未满足（**不是被测功能的结论**）：以下带版本主表尚无 _record 表 —— "
                    + missing + "（共 " + missing.size() + " 张）。"
                    + "本任务 B-1/B-3 建表迁移被上游 `报价侧加客户维度` 的 DDL 阻塞，尚未落库。"
                    + "用例已按 AC 写完，待迁移应用后即可执行。");
        }
    }

    // ─────────────────────────── SQL 小工具 ───────────────────────────

    protected Object scalar(String sql) {
        List<?> rs = em.createNativeQuery(sql).getResultList();
        return rs.isEmpty() ? null : rs.get(0);
    }

    protected long count(String sql) {
        Object v = scalar(sql);
        return v == null ? 0L : ((Number) v).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected boolean relationExists(String name) {
        return scalar("SELECT to_regclass('public." + sqlSafe(name) + "')::text") != null;
    }

    /** 表名/列名只允许字母数字下划线 —— 拼 SQL 前的最后一道闸。 */
    protected static String sqlSafe(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法标识符：" + ident);
        }
        return ident;
    }

    /**
     * 🚨 <b>按轴值收窄</b>的内容指纹 —— AC-7 / AC-15 / AC-16 / AC-17 的比对一律走这个。
     *
     * <p>🔑 取全行 {@code to_jsonb} 的有序聚合，而不是 count / max(updated_at)：
     * 后者对「值被改回同一个数」「行被换掉但行数不变」这两类都是瞎的。
     *
     * <p>🚫 <b>刻意不提供「整表 digest」入口</b>（风险点 5，2026-09-07 需求文档「共同纪律①」）：
     * 整表 md5 会罩住共享库里别的会话正在写的行 —— 它长得像不变量，实际是个<b>必然漂移</b>的判据，
     * 于是同一份代码也能把 diff 跑红，产出假红报告。范围必须收窄到本次夹具自己的轴值。
     *
     * <p>⚠️ 同时内建<b>非空守卫</b>：空集合的 digest 恒等于 {@code <EMPTY>} 的 md5，
     * A/B 会退化成「空 vs 空」恒相等恒通过（{@code test.md} §六实证过的假绿）。
     *
     * @param axisValues 本次夹具自己的轴值，🚫 不许传空集合
     */
    protected String scopedDigest(String table, String axisCol, List<String> axisValues, String what) {
        return scopedDigest(table, axisCol, axisValues, what, /*requireNonEmpty*/ true);
    }

    /**
     * @param requireNonEmpty false 用于<b>合法可空</b>的比对面（如全新料号组的 {@code _history} 本来就 0 行）。
     *   ⚠️ 传 false 时**必须**同时对一个非空的面做断言，否则整条用例又退回「空 vs 空」恒真。
     *   （2026-09-07 实测：AC-7 的 {@code _history} 基线对新建组恒为 0 行，守卫过严会把它误判成夹具没造出来。）
     */
    protected String scopedDigest(String table, String axisCol, List<String> axisValues, String what,
                                  boolean requireNonEmpty) {
        sqlSafe(table);
        sqlSafe(axisCol);
        assertFalse(axisValues.isEmpty(),
                "🚨 " + what + "：比对范围的轴值集合为空 ⇒ 这会变成整表比对或空集比对，两者都不是有效判据。");
        StringBuilder in = new StringBuilder();
        for (String v : axisValues) {
            if (in.length() > 0) in.append(',');
            in.append('\'').append(v.replace("'", "''")).append('\'');
        }
        String where = axisCol + " IN (" + in + ")";
        long n = count("SELECT count(*) FROM " + table + " WHERE " + where);
        if (requireNonEmpty) {
            assertFixtureNonEmpty(n, what + "（" + table + " 上轴值 " + axisValues + " 的行数）");
        }
        Object v = scalar("SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text),'<EMPTY>')) "
                + "FROM (SELECT to_jsonb(x) AS t FROM " + table + " x WHERE " + where + ") s");
        return v == null ? null : v.toString();
    }

    /**
     * 🚨 <b>任何 diff 类断言之前必须先过这一关</b>（需求文档「共同纪律②」/ {@code test.md} 风险点 6）。
     *
     * <p>状态码不对 ⇒ 直接判<b>用例环境失败</b>，🚫 不许让它流进 diff 结论。
     * 🔬 实证：并发会话的测试代理有一轮 session 过期，14 个端点全返 401，diff 忠实报出
     * 「14/14 全漂移」—— 差点产出一份「本任务把所有维护端点都搞坏了」的假红报告。
     */
    protected void requireStatusBeforeDiff(Response r, int expected, String what) {
        if (r.statusCode() != expected) {
            fail("🚨 用例环境失败（**不是 diff 结论、不是业务缺陷**）：" + what + " 期望 HTTP " + expected
                    + "，实际 " + r.statusCode() + "。"
                    + (r.statusCode() == 401 || r.statusCode() == 403
                        ? "401/403 通常是会话过期或角色不对 —— 此时任何 diff 都会「全漂移」，那是假红。" : "")
                    + " body=" + r.asString());
        }
    }

    /**
     * 结构层严格比对（AC-16①）：字段集与嵌套结构逐字相同，<b>不含具体取值</b>。
     *
     * <p>把 JSON 拍扁成「路径 + 类型」的有序集合 —— 值漂移（别的会话写库、时间戳）不影响它，
     * 而字段增删改名、嵌套层级变化一定被抓到。这是「拆两层」里严格的那一层。
     */
    protected static java.util.SortedSet<String> jsonShape(JsonNode node) {
        java.util.SortedSet<String> out = new java.util.TreeSet<>();
        collectShape(node, "$", out);
        return out;
    }

    private static void collectShape(JsonNode n, String path, java.util.SortedSet<String> out) {
        if (n == null || n.isMissingNode()) return;
        if (n.isObject()) {
            out.add(path + ":object");
            n.fieldNames().forEachRemaining(f -> collectShape(n.get(f), path + "." + f, out));
        } else if (n.isArray()) {
            out.add(path + ":array");
            // 只看第 0 个元素的形状：数组长度属「数据层」，长度变化不算结构变化
            if (n.size() > 0) collectShape(n.get(0), path + "[]", out);
        } else {
            out.add(path + ":" + n.getNodeType().name().toLowerCase());
        }
    }

    protected void assertStructureIdentical(JsonNode before, JsonNode after, String what) {
        java.util.SortedSet<String> a = jsonShape(before);
        java.util.SortedSet<String> b = jsonShape(after);
        assertFixtureNonEmpty(a.size(), what + " 改动前的结构路径集");
        if (!a.equals(b)) {
            java.util.SortedSet<String> onlyA = new java.util.TreeSet<>(a); onlyA.removeAll(b);
            java.util.SortedSet<String> onlyB = new java.util.TreeSet<>(b); onlyB.removeAll(a);
            fail(what + " 结构层不一致（AC-16① 要求逐字相同）：仅改动前有=" + onlyA + "；仅改动后有=" + onlyB);
        }
    }

    /** 某表某轴值的整组行（jsonb 文本，按 id 排序），用于逐字比对。 */
    protected List<Object> groupRowsJson(String table, String axisCol, String axisValue) {
        sqlSafe(table);
        sqlSafe(axisCol);
        return col("SELECT to_jsonb(x)::text FROM " + table + " x WHERE " + axisCol + " = '"
                + axisValue.replace("'", "''") + "' ORDER BY x.id");
    }

    protected Set<Long> idSet(String table, String axisCol, String axisValue) {
        sqlSafe(table);
        sqlSafe(axisCol);
        Set<Long> out = new LinkedHashSet<>();
        for (Object o : col("SELECT id FROM " + table + " WHERE " + axisCol + " = '"
                + axisValue.replace("'", "''") + "'")) {
            out.add(((Number) o).longValue());
        }
        return out;
    }

    // ─────────────────────────── 空验证守卫 ───────────────────────────

    /**
     * 🚨 断言前必须先证明基底非空 —— {@code test.md} §六：
     * 「按列表首个轴值取样恰好 0 行，A/B 变成『空 vs 空』，恒相等恒通过」。
     */
    protected void assertFixtureNonEmpty(long actual, String what) {
        assertTrue(actual > 0,
                "🚨 空验证守卫：" + what + " 实测 " + actual + " 行。基底为空时后面的断言会以「通过」的形态空跑，"
                        + "此刻的绿不构成任何证据。请先把夹具造出来再断言。");
    }

    /** 🚨 风险点 3：跨版支路的前置 —— 必须先实证 {@code id} 确已换掉（交集为空）。 */
    protected void assertIdSetsDisjoint(Set<Long> before, Set<Long> after, String what) {
        assertFixtureNonEmpty(before.size(), what + " 升版前的 id 集合");
        assertFixtureNonEmpty(after.size(), what + " 升版后的 id 集合");
        Set<Long> inter = new LinkedHashSet<>(before);
        inter.retainAll(after);
        assertTrue(inter.isEmpty(),
                "🚨 跨版前置未成立：" + what + " 升版前后主表 id 集合仍有交集 " + inter
                        + " ⇒ origin_id 并没有失效，那么『指纹兜底生效了』这件事根本无从谈起，"
                        + "本用例后续断言不成立。before=" + before + " after=" + after);
    }

    // ─────────────────────────── 事务 ───────────────────────────

    private static final class RollbackSignal extends RuntimeException {
        RollbackSignal() { super(null, null, false, false); }
    }

    /**
     * 构造性零残留：在一个新事务里造数据 + 跑断言，结束<b>主动回滚</b>。
     *
     * <p>🚫 不用「INSERT + finally DELETE」：共享库上的 DELETE 属 §3.2 红线，
     * 而且用例中途崩溃时 finally 也可能跑不到。回滚不依赖任何清理动作。
     * ⚠️ 仅适用于<b>纯 DB 层</b>断言；HTTP 端到端用例看不见未提交事务，必须走 committed 夹具。
     */
    protected void inRollback(Runnable body) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                body.run();
                throw new RollbackSignal();
            });
        } catch (RollbackSignal ignored) {
            // 正常路径：数据已回滚
        }
    }

    protected void inTx(Runnable body) {
        QuarkusTransaction.requiringNew().run(body);
    }

    // ─────────────────────────── committed 夹具 ───────────────────────────

    protected record Fx(UUID customerId, String customerNo, UUID quotationId, String quotationNo) {}

    /** 建一套 committed 的「客户 + DRAFT 报价单」。前缀 {@link #PREFIX}，登记后由 {@link #cleanupOwnFixtures} 清。 */
    protected Fx newFixture(String label) {
        UUID customerId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        String customerNo = CUST_PREFIX + customerId.toString().replace("-", "").substring(0, 8);
        String qno = PREFIX + "QT-" + quotationId.toString().substring(0, 8);
        inTx(() -> {
            Object admin = scalar("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1");
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");
            em.createNativeQuery(
                            "INSERT INTO customer (id,name,code,level,accumulated_amount,status,version,created_at,updated_at) "
                                    + "VALUES (:id,:name,:code,'STANDARD',0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("id", customerId)
                    .setParameter("name", PREFIX + "客户-" + label)
                    .setParameter("code", customerNo)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,"
                                    + "bound_global_variables_snapshot,user_data_version,created_at,updated_at) "
                                    + "VALUES (:id,:qno,:cid,:qname,CAST(:uid AS uuid),'DRAFT',0,0,'{}'::jsonb,0,NOW(),NOW())")
                    .setParameter("id", quotationId)
                    .setParameter("qno", qno)
                    .setParameter("cid", customerId)
                    .setParameter("qname", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString())
                    .executeUpdate();
        });
        createdCustomers.add(customerId);
        createdQuotations.add(quotationId);
        return new Fx(customerId, customerNo, quotationId, qno);
    }

    /**
     * 造一行 {@code ds_quote_material_bom}（committed）。指纹按内容算，跨版重锚要靠它。
     *
     * <h3>🕰️ 2026-09-07 补 {@code customer_no}（上游 V425）</h3>
     * 上游 {@code V425__task260907_ds_quote_customer_no} 把 {@code customer_no} 建成
     * <b>{@code NOT NULL} 且无 default</b>（只读查证：{@code pg_attribute.attnotnull = t}）。
     * 原来这条 INSERT 不带该列 ⇒ 合并后<b>24 次 {@code ConstraintViolation}</b> 全出自这里与
     * {@link #seedEbomMainGroup}。
     * ⚠️ 那是<b>夹具欠上游 DDL</b>，🚫 不是被测实现的回归 —— 归因时别弄反。
     *
     * <p>🔑 {@code customerNo} 现在是<b>轴的一维</b>（轴 = {@code (customer_no, material_no)}）
     * ⇒ 它<b>不是随便填的占位值</b>：要让后续某张单表征这一组，那张单的客户必须是同一个。
     * 见 {@link #newSubmittedOrder} 的轴归属登记。
     */
    protected void insertMaterialBomRow(String customerNo, String materialNo, int itemSeq,
                                        String inputMaterialNo, String componentQty, int versionNo) {
        trackAxis(materialNo);
        em.createNativeQuery(
                        "INSERT INTO ds_quote_material_bom "
                                + "(customer_no,material_no,item_seq,input_material_no,component_qty,version_no,row_fingerprint,source,created_at) "
                                + "VALUES (:cn,:mn,:seq,:in,CAST(:q AS numeric),:v,:fp,'TEST',now())")
                .setParameter("cn", customerNo)
                .setParameter("mn", materialNo)
                .setParameter("seq", itemSeq)
                .setParameter("in", inputMaterialNo)
                .setParameter("q", componentQty)
                .setParameter("v", versionNo)
                .setParameter("fp", contentFingerprint(materialNo, itemSeq, inputMaterialNo, componentQty))
                .executeUpdate();
    }

    /**
     * 夹具行的占位指纹 —— 🚫 <b>不是被测的 {@code row_fingerprint} 算法</b>，
     * 只是为了满足 {@code char(64) NOT NULL} 且同内容同指纹（跨版重锚用例要靠这个性质）。
     */
    protected static String contentFingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) sb.append(p).append('');
        String hex = Integer.toHexString(sb.toString().hashCode());
        return ("f".repeat(Math.max(0, 64 - hex.length())) + hex).substring(0, 64);
    }

    /**
     * 按前缀清理本轮 committed 夹具 —— <b>先 count 再删</b>（§3.2 第一步：说不出数字就不许执行）。
     * 🚫 只删本前缀命中的行，绝不按表清。
     *
     * <h3>🕰️ 2026-09-07 补漏（本方法一开始漏了 {@code ds_quote_*}）</h3>
     * 首版只清 {@code quotation} / {@code customer}，<b>没清 {@code seedMainGroup} 往
     * {@code ds_quote_*} 写的夹具行</b>。实测后果：一轮跑完在共享库 {@code cpq_db_0724} 留下
     * <b>43 行</b>（11 个轴值组，{@code source='TEST'}）——
     * 而且它是<b>沉默</b>的：用例因 {@code pending()} 报错退出，报告只会说「⛔ 待接实现」，
     * 没有任何一行提示「顺手在共享库留了 43 行」。
     *
     * <p>⚠️ 危险在于它会<b>累积</b>：夹具轴值带随机后缀，每跑一轮多一批，
     * 下一个人查 {@code ds_quote_material_bom} 时会看到一堆来路不明的料号，
     * 且很容易被当成业务数据或别的任务的残留。
     * ⇒ 清理必须覆盖<b>所有</b>本轮可能写到的表，不能只覆盖「我记得的那两张」。
     */
    protected void cleanupOwnFixtures() {
        // 🚫 不再用 createdQuotations.isEmpty() 提前返回 —— seedMainGroup 可能在
        //    newFixture 之前就写了 ds_quote_* 行，提前返回会把它们漏掉。
        cleanupOwnDatasetRows();
        if (createdQuotations.isEmpty() && createdCustomers.isEmpty()) return;
        inTx(() -> {
            for (UUID qid : createdQuotations) {
                long n = count("SELECT count(*) FROM quotation WHERE id = '" + qid + "'");
                if (n == 0) continue;
                // 🚨 先清掉所有**外键指向 quotation** 的下游行。
                //    清单从 pg_constraint 派生，🚫 不手工维护 —— 见方法注释里的三次漏清教训。
                deleteReferencingRows(qid);
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                                + "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q AND quotation_number LIKE :p")
                        .setParameter("q", qid).setParameter("p", PREFIX + "%").executeUpdate();
            }
            for (UUID cid : createdCustomers) {
                em.createNativeQuery("DELETE FROM customer WHERE id = :c AND code LIKE :p")
                        .setParameter("c", cid).setParameter("p", CUST_PREFIX + "%").executeUpdate();
            }
        });
        createdQuotations.clear();
        createdCustomers.clear();
        axisOwner.clear();
        createdAxisValues.clear();
    }

    /**
     * 清掉本轮往 {@code ds_quote_*}（主表 / {@code _history} / {@code _record}）写的前缀化夹具行。
     *
     * <p>🔑 <b>表清单动态派生</b>，🚫 不硬编码 —— 硬编码的清单在「以后又多一张表」时会<b>沉默地漏清</b>，
     * 而漏清的症状（共享库里慢慢堆积来路不明的料号）几个月后才会被人发现，且很难归因。
     *
     * <p>🚨 收窄条件 = {@code material_no LIKE 'T260907R-%'}（本任务前缀）。
     * 该前缀是本任务独占的命名空间，绝不碰别人的（现网既有的 {@code T260907-M1} / {@code T260907T-}
     * 都不匹配它）。⇒ 命中面永远可量化、永远是自己的行。
     * 🚫 绝不按表清（{@code TRUNCATE} / 无 WHERE 的 DELETE 属 §3.2 红线）。
     *
     * <h3>🕰️ 2026-09-07 第二次补漏：🚫 不能再加 {@code source='TEST'} 这一条</h3>
     * 首版为了「双重保险」加了 {@code AND source='TEST'}。实测它<b>漏清</b>：
     * <pre>
     *   ds_quote_element_bom_record | 1 行 | source = QUOTE_DRAFT
     * </pre>
     * 因为 {@code _record} 的行<b>不是我插的，是被测应用自己写的</b> ——
     * 应用当然不会把 {@code source} 写成 {@code 'TEST'}。
     *
     * <p>⚠️ 这与上一次漏清（{@code ds_quote_*} 43 行）<b>是同一类错误的第二次发作</b>：
     * <b>「我以为我知道自己的夹具会碰到哪些行」</b>。第一次错在漏了表，第二次错在漏了写入方 ——
     * <b>夹具触发的写入，写入方是应用而不是我</b>。
     * ⇒ 判据只能用<b>我独占的命名空间（前缀）</b>，🚫 不能用「我以为我会写成什么样」的标记。
     */
    protected void cleanupOwnDatasetRows() {
        if (createdAxisValues.isEmpty()) return;
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) (List<?>) em.createNativeQuery(
                        "SELECT c.table_name FROM information_schema.columns c "
                                + "WHERE c.table_schema='public' AND c.table_name LIKE 'ds\\_quote\\_%' "
                                + "  AND c.column_name='material_no' "
                                + "ORDER BY 1")
                .getResultList();
        if (tables.isEmpty()) return;
        List<String> axes = List.copyOf(createdAxisValues);
        inTx(() -> {
            for (String t : tables) {
                sqlSafe(t);
                // §3.2 第一步：先量化命中面，说不出数字就不删
                long n = count("SELECT count(*) FROM " + t + " WHERE material_no IN ("
                        + inList(axes) + ")");
                if (n == 0) continue;
                em.createNativeQuery("DELETE FROM " + t + " WHERE material_no IN (:p)")
                        .setParameter("p", axes).executeUpdate();
                System.out.println("[" + PREFIX + "cleanup] " + t + " 清掉本进程自造轴值 " + n + " 行");
            }
        });
    }

    /** 把轴值拼成 SQL 字面量列表（只用于 count；DELETE 走绑定参数）。 */
    private static String inList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append('\'').append(v.replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    /**
     * 删掉所有<b>外键指向 {@code quotation.id}</b> 的下游行（限定本报价单）。
     *
     * <h3>🕰️ 2026-09-07 第三次补漏（同一类错误的第三次发作）</h3>
     * {@code submit} 会连带写 {@code costing_order}，而我的清理不认识它：
     * <pre>
     *   ConstraintViolation: update or delete on table "quotation" violates foreign key
     *   constraint "costing_order_quotation_id_fkey" on table "costing_order"
     * </pre>
     * ⇒ 清理整个失败，夹具全留在共享库里。
     *
     * <p>三次漏清的共因始终是同一个：<b>我以为我知道自己的夹具会碰到哪些行</b>。
     * <ol>
     *   <li>第一次漏了<b>表</b>（{@code ds_quote_*} 43 行）；</li>
     *   <li>第二次漏了<b>写入方</b>（{@code _record} 由应用写，{@code source} 不是 {@code TEST}）；</li>
     *   <li>第三次漏了<b>下游连带写入</b>（{@code submit} → {@code costing_order}）。</li>
     * </ol>
     * ⇒ 所以这里<b>不列表</b>，而是从 {@code pg_constraint} <b>派生</b>出「谁引用了 quotation」。
     * 以后再多一张下游表，它自动被覆盖 —— 手工清单则会再一次沉默地漏掉。
     */
    protected void deleteReferencingRows(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object[]> refs = em.createNativeQuery(
                        "SELECT c.conrelid::regclass::text AS child_table, a.attname AS child_col "
                                + "FROM pg_constraint c "
                                + "JOIN unnest(c.conkey) WITH ORDINALITY k(attnum, ord) ON true "
                                + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum "
                                + "WHERE c.contype = 'f' AND c.confrelid = 'quotation'::regclass")
                .getResultList();
        for (Object[] ref : refs) {
            String child = String.valueOf(ref[0]);
            String col = String.valueOf(ref[1]);
            if (!child.matches("[A-Za-z0-9_.\"]+") || !col.matches("[A-Za-z0-9_]+")) continue;
            long n = count("SELECT count(*) FROM " + child + " WHERE " + col + " = '" + quotationId + "'");
            if (n == 0) continue;
            em.createNativeQuery("DELETE FROM " + child + " WHERE " + col + " = :q")
                    .setParameter("q", quotationId).executeUpdate();
            System.out.println("[" + PREFIX + "cleanup] " + child + "." + col + " 清掉下游行 " + n);
        }
    }

    // ═══════════════════════ ds 原生模板夹具（2026-09-07 打通）═══════════════════════
    //
    // 🔑 为什么用 ds_quote_element_bom 而不是 ds_quote_material_bom：
    //    「报价模板 · ds 原生 v1.0」的 13 个组件里**没有物料BOM**（实查 template_component），
    //    ⇒ ds_quote_material_bom 走不通报价单页签这条路，造不出 _record。
    //    而「T260907-物料与元素BOM」→ ds_quote_element_bom 这条已实测跑通（FX-01/02/03）。
    //    🚫 不是「换个容易的表」，是**换到本模板唯一能表征的那张带版本 BOM 表**。

    /** 报价模板 · ds 原生 v1.0（PUBLISHED，13 组件）。 */
    public static final UUID DS_TEMPLATE_ID = UUID.fromString("df379593-8f9c-4974-b90f-1429b3349869");
    /** T260907-物料与元素BOM ⇒ 锚 ds_quote_element_bom。 */
    public static final UUID COMP_ELEMENT_BOM = UUID.fromString("196aadee-b89f-4f81-984b-4c6747b59149");
    public static final String TAB_ELEMENT_BOM = "T260907-物料与元素BOM";
    /** 本套 AC 用例的被测主表（轴列 material_no）。 */
    public static final String EBOM = "ds_quote_element_bom";

    /** 一行元素BOM 的业务值（字段名 = 组件 fields 的 name，实测与主表 12 个业务列 1:1）。 */
    protected record EbomRow(int itemSeq, String elementCode, String contentPct, String netUsage) {}

    /** 造主表整组（committed），版本号自定。轴 = {@code material_no}。 */
    /**
     * 造主表整组（committed）。轴 = {@code material_no}。
     *
     * <h3>🕰️ 2026-09-07 修缺陷 1：必须灌满 12 列</h3>
     * 原来只 INSERT 6 列，而 {@link #ebomRowData} 给 12 列 ⇒ 另 6 列主表是 NULL、报价单有值
     * ⇒ <b>两侧数据是真的不同</b>，判 {@code UPGRADED} 是对的，是我的夹具制造了差异。
     * 🔬 后端的 {@code [ds-record][anchor-miss]} 逐列诊断把它打了出来：
     * <pre>
     *   ≠ gross_usage : base=[]  record=[2.5]
     *   ≠ loss_rate   : base=[]  record=[1]
     *   …（6 列全是「主表空 / 报价单有值」）
     * </pre>
     * ⇒ 夹具两侧的列集合必须对齐，否则「零变更」这个前提根本不成立。
     */
    protected void seedEbomMainGroup(Fx owner, String materialNo, List<EbomRow> rows, int versionNo) {
        registerAxisOwner(materialNo, owner);
        trackAxis(materialNo);
        String customerNo = owner.customerNo();
        inTx(() -> {
            for (EbomRow r : rows) {
                em.createNativeQuery(
                                "INSERT INTO " + EBOM + " (customer_no,material_no,material_part_no,item_seq,element_code,"
                                        + "content_pct,loss_rate,gross_usage,gross_usage_unit,"
                                        + "net_usage,net_usage_unit,recovery_discount,recovery_qty,"
                                        + "version_no,row_fingerprint,source,created_at) "
                                        + "VALUES (:cn,:mn,:mp,:seq,:ec,CAST(:cp AS numeric),CAST(:lr AS numeric),"
                                        + "CAST(:gu AS numeric),:guu,CAST(:nu AS numeric),:nuu,"
                                        + "CAST(:rd AS numeric),CAST(:rq AS numeric),"
                                        + ":v,:fp,'TEST',now())")
                        .setParameter("cn", customerNo)
                        .setParameter("mn", materialNo)
                        .setParameter("mp", PREFIX + "MAT")
                        .setParameter("seq", r.itemSeq())
                        .setParameter("ec", r.elementCode())
                        .setParameter("cp", r.contentPct())
                        .setParameter("lr", "1")
                        .setParameter("gu", "2.5")
                        .setParameter("guu", "kg")
                        .setParameter("nu", r.netUsage())
                        .setParameter("nuu", "kg")
                        .setParameter("rd", "10")
                        .setParameter("rq", "0.1")
                        .setParameter("v", versionNo)
                        .setParameter("fp", contentFingerprint(materialNo, r.itemSeq(), r.elementCode(),
                                r.contentPct(), r.netUsage()))
                        .executeUpdate();
            }
        });
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '"
                + materialNo + "'"), "夹具主表组 " + materialNo + " 行数");
    }

    /** rowData JSON（字段名照组件 fields）。 */
    protected String ebomRowData(String materialNo, List<EbomRow> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            EbomRow r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"销售料号\":\"").append(materialNo)
              .append("\",\"材质料号\":\"").append(PREFIX).append("MAT")
              .append("\",\"项次\":\"").append(r.itemSeq())
              .append("\",\"元素\":\"").append(r.elementCode())
              .append("\",\"组成含量（%）\":\"").append(r.contentPct())
              .append("\",\"损耗率%\":\"1\",\"毛用量\":\"2.5\",\"毛用量单位\":\"kg\"")
              .append(",\"净用量\":\"").append(r.netUsage())
              .append("\",\"净用量单位\":\"kg\",\"回收折扣(%)\":\"10\",\"回收量\":\"0.1\"}");
        }
        return sb.append(']').toString();
    }

    /** {@code PUT /draft}：以 added 建一个 line item，带元素BOM 页签的 N 行。 */
    protected Response saveDraftAdded(Fx fx, String materialNo, List<EbomRow> rows) {
        String body = "{\"baseVersion\":0,\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "t1\","
                + "\"templateId\":\"" + DS_TEMPLATE_ID + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + materialNo + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + jsonStr(ebomRowData(materialNo, rows)) + ",\"sortOrder\":0}]}],"
                + "\"modified\":[],\"removed\":[]}";
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

    /**
     * 预览 → 取 {@code previewToken} → 确认核价通过。
     *
     * <p>🚨 {@code previewToken} 必填（缺失 400，api.md §2）⇒ 必须先预览。
     */
    protected void approveWithPreview(Fx fx, String what) {
        Response pv = getPreview(fx.quotationId());
        requireStatusBeforeDiff(pv, 200, what + " 预览");
        JsonNode tok = json(pv).path("data").path("previewToken");
        assertTrue(!tok.isMissingNode() && !tok.isNull() && !tok.asText().isBlank(),
                what + "：预览响应缺 previewToken，无法确认。data=" + json(pv).path("data"));
        Response ap = postApprove(fx.quotationId(), tok.asText(), PREFIX + what);
        requireStatusBeforeDiff(ap, 200, what + " 确认核价通过");
    }

    /**
     * 用一张「走 CREATED 的报价单」把主表某组造出来 —— <b>指纹由 {@code VersionedGroupWriter} 自己算</b>。
     *
     * <h3>🚨 为什么不用 {@link #seedEbomMainGroup} 直接 INSERT</h3>
     * 夹具自造的 {@code row_fingerprint} 与 writer 的 {@code RowFingerprints.compute} <b>必然不同</b>，
     * 而 {@code UNCHANGED} 判据是 {@code sameMultiset(dbFps, newFps)}
     * ⇒ 手工摆的主表<b>永远判不出 UNCHANGED</b>，而且它<b>不报错、只是永远不相等</b>（极隐蔽）。
     * ⇒ 凡是后续要比对「变没变」的用例，主表基线一律用本方法造。
     * {@link #seedEbomMainGroup} 只留给「不关心指纹、只要有行」的场景。
     *
     * @return 造这一组用掉的那张单（已 APPROVED）
     */
    protected Fx seedMainViaCreatedOrder(String label, String materialNo, List<EbomRow> rows) {
        Fx seeder = newSubmittedOrder(label, materialNo, rows);
        approveWithPreview(seeder, label);
        long n = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + materialNo + "'");
        assertEquals((long) rows.size(), n,
                label + " 前置：主表该组应有 " + rows.size() + " 行，实际 " + n);
        return seeder;
    }

    /** 主表该组「业务列元组」按 {@code item_seq} 索引 —— 🚫 不含随升版正常变化的系统/版本列。 */
    protected Map<Integer, String> ebomBusinessRowsBySeq(String materialNo) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq, "
                + "coalesce(element_code,'~') || '|' || coalesce(content_pct::text,'~') || '|' "
                + "|| coalesce(net_usage::text,'~') || '|' || coalesce(loss_rate::text,'~') || '|' "
                + "|| coalesce(gross_usage::text,'~') || '|' || coalesce(recovery_qty::text,'~') "
                + "FROM " + EBOM + " WHERE material_no = '" + materialNo.replace("'", "''") + "' "
                + "ORDER BY item_seq, id")) {
            out.merge(((Number) r[0]).intValue(), String.valueOf(r[1]), (a, b) -> a + " ;; " + b);
        }
        return out;
    }

    protected Response submit(Fx fx) {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON)
                .when().post("/api/cpq/quotations/" + fx.quotationId() + "/submit").thenReturn();
    }

    /**
     * 登记「这个料号组归哪个客户」。
     * 🚫 <b>刻意要求传整个 {@link Fx} 而不是一个 {@code customerNo} 字符串</b>：
     * 后续 {@link #newSubmittedOrderForCustomer} 建单要用 {@code customerId}，
     * 只登记字符串会让登记表里躺着 {@code customerId=null} 的半个对象，
     * 而它会在<b>很远的地方</b>以「INSERT 违反 NOT NULL」的面目炸掉。
     */
    protected void registerAxisOwner(String materialNo, Fx owner) {
        assertNotNull(owner.customerId(), "轴归属登记必须带真实客户（customerId 不能为 null）");
        axisOwner.putIfAbsent(materialNo, owner);
    }

    /**
     * 建一张 <b>SUBMITTED</b> 的新链路报价单，其元素BOM 页签表征 {@code materialNo} 这一组。
     *
     * <p>🚨 <b>轴感知</b>（2026-09-07）：该料号若已有归属客户（{@link #axisOwner}），
     * <b>复用同一个客户</b> —— 否则轴 {@code (customer_no, material_no)} 不同，
     * 建出来的是<b>另一个组</b>，而症状会伪装成「锚定机制坏了」。
     */
    protected Fx newSubmittedOrder(String label, String materialNo, List<EbomRow> rows) {
        Fx owner = axisOwner.get(materialNo);
        if (owner != null) {
            System.out.println("[axis] " + label + " 复用料号 " + materialNo + " 的归属客户 "
                    + owner.customerNo() + "（轴 = (customer_no, material_no)，换客户就是另一个组）");
            return newSubmittedOrderForCustomer(label, owner, materialNo, rows);
        }
        Fx fx = newFixture(label);
        axisOwner.put(materialNo, fx);
        return submitOrderOn(fx, label, materialNo, rows);
    }

    /**
     * 在<b>已存在</b>的 {@code owner} 客户名下建一张 SUBMITTED 单（AC-10 前置「A、B 同客户」，
     * 以及复合轴下「表征同一组」的通用要求）。
     */
    protected Fx newSubmittedOrderForCustomer(String label, Fx owner, String materialNo, List<EbomRow> rows) {
        UUID qid = UUID.randomUUID();
        String qno = PREFIX + "QT-" + qid.toString().substring(0, 8);
        inTx(() -> {
            Object admin = scalar("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1");
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");
            em.createNativeQuery(
                            "INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,"
                                    + "tax_amount,bound_global_variables_snapshot,user_data_version,created_at,updated_at) "
                                    + "VALUES (:id,:qno,:cid,:qname,CAST(:uid AS uuid),'DRAFT',0,0,'{}'::jsonb,0,NOW(),NOW())")
                    .setParameter("id", qid).setParameter("qno", qno)
                    .setParameter("cid", owner.customerId())
                    .setParameter("qname", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString())
                    .executeUpdate();
        });
        createdQuotations.add(qid);
        Fx fx = new Fx(owner.customerId(), owner.customerNo(), qid, qno);
        if (!axisOwner.containsKey(materialNo)) axisOwner.put(materialNo, fx);
        return submitOrderOn(fx, label, materialNo, rows);
    }

    /** 在给定 Fx 上 saveDraft + submit，并断言 {@code _record} 非空（否则后续断言空跑）。 */
    protected Fx submitOrderOn(Fx fx, String label, String materialNo, List<EbomRow> rows) {
        trackAxis(materialNo);
        requireStatusBeforeDiff(saveDraftAdded(fx, materialNo, rows), 200, label + " saveDraft");
        requireStatusBeforeDiff(submit(fx), 200, label + " submit");
        assertEquals("SUBMITTED", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                label + " 提交后状态应为 SUBMITTED");
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "'"), label + " 的 _record 行数（夹具没写出 _record 则后续断言空跑）");
        return fx;
    }

    protected static String jsonStr(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ─────────────────────────── HTTP ───────────────────────────

    /**
     * admin 登录（{@code SYSTEM_ADMIN}，覆盖 {@code costing-approve} 的 {@code @RoleAllowed}）。
     *
     * <p>⚠️ RBAC 在测试期是<b>开着</b>的：{@code src/test/resources/application.properties} 虽写了
     * {@code rbac.enabled=false}，但 {@code src/main/resources/application-test.properties:94} 又设回 true，
     * 而 <b>profile 专属配置优先级更高</b>。不登录 ⇒ 所有业务断言以 401 的面目失败。
     *
     * <p>🚫 只解锁 admin，不改其密码/状态/角色（{@code testing.md}：不得改变共享库的全局状态。
     * {@code RECORD.md} 有实证 —— E2E 反复跑把 admin 置成 INACTIVE，之后所有用例连同真人操作一起坏）。
     */
    protected Map<String, String> adminCookies() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            ADMIN_COOKIES = null;
        }
        inTx(() -> em.createNativeQuery(
                        "UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL WHERE username = 'admin'")
                .executeUpdate());
        Response last = null;
        for (int i = 0; i < 4; i++) {
            last = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", "admin", "password", "Admin@2026"))
                    .post("/api/cpq/auth/login").thenReturn();
            if (last.statusCode() == 200) {
                ADMIN_COOKIES = new LinkedHashMap<>(last.getCookies());
                assertFalse(ADMIN_COOKIES.isEmpty(), "登录返 200 却没拿到 cookie（会话机制变了？）");
                // 🚨 阳性对照：cookie 拿到 ≠ cookie 生效。不打这一枪，「会话没生效」会以业务失败的面目
                //    出现在几十条断言上。
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(),
                        "登录拿到 cookie 但 /auth/me 仍不通（" + me.statusCode() + "）⇒ 会话未生效，"
                                + "此时所有业务断言都不可信。body=" + me.asString());
                assertEquals("SYSTEM_ADMIN", me.jsonPath().getString("data.role"),
                        "🚨 会话角色不是 SYSTEM_ADMIN ⇒ 「核价通过应能调通」的断言失去意义");
                return ADMIN_COOKIES;
            }
            try { Thread.sleep(500L * (i + 1)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("admin 登录连续失败，最后一次 status="
                + (last == null ? "N/A" : last.statusCode()) + "。这是环境前置，不是业务结论。");
    }

    protected Response getPreview(UUID quotationId) {
        return RestAssured.given().cookies(adminCookies())
                .when().get(String.format(PREVIEW, quotationId)).thenReturn();
    }

    protected Response postApprove(UUID quotationId, String previewToken, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        if (previewToken != null) body.put("previewToken", previewToken);
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(body)
                .when().post(String.format(APPROVE, quotationId)).thenReturn();
    }

    protected JsonNode json(Response r) {
        try {
            return MAPPER.readTree(r.asString());
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + r.asString(), e);
        }
    }

    /** 断言 200 并返回 data 节点；失败时把响应原文带出来（否则排错要重跑一遍）。 */
    protected JsonNode ok(Response r, String what) {
        assertEquals(200, r.statusCode(),
                what + " 应返回 200，实际 " + r.statusCode() + "，响应：" + r.asString());
        JsonNode body = json(r);
        JsonNode data = body.path("data");
        assertTrue(!data.isMissingNode() && !data.isNull(), what + " 响应 data 不应为空，响应：" + r.asString());
        return data;
    }

    /** {@code api.md §1} 的 {@code dsBackfill} 段。 */
    protected JsonNode dsBackfill(JsonNode previewData) {
        JsonNode n = previewData.path("dsBackfill");
        assertTrue(!n.isMissingNode() && !n.isNull(),
                "api.md §1 契约：预览响应必须含 dsBackfill 段，实际缺失。data=" + previewData);
        return n;
    }

    protected static BigDecimal dec(Object o) {
        return o == null ? null : new BigDecimal(o.toString());
    }
}
