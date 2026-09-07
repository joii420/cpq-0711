package com.cpq.task260907;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/**
 * task-260907「取数配置器补齐」12 条生效 AC（AC-1~AC-12，AC-13 已废止）的公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260907-取数配置器补齐/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md}。
 * <b>🚫 本套用例不读实现代码</b>（不读 {@code cpq-backend/src/main/java/com/cpq/builder|semanticgraph|dataset/**}、
 * {@code cpq-frontend/src/pages/component|services/**}），只读立项文档、库 schema、库里的配置数据与既有测试代码。
 *
 * <h3>🚨 最高纪律：不写死现网数字（test.md §1「优先写不变量」）</h3>
 * 立项文档里的 47 行 / 19 行 / 222 个组件 / 11→14 项<b>全部来自共享 dev 库当前数据</b>，会漂移。
 * 本套一律<b>执行期从库里现算期望值</b>，断言的是结构不变量：
 * 「不含退役值」「dialect 恒等于入参」「两表列都出现」「LEFT 侧行数 = 主表行数」「A/B 逐字相同」。
 * 实测数字只 {@code println} 供报告记录。
 * <p>📌 本项目实证教训：{@code task-260819} 的 AC-113/AC-122 因判据写死数字，同一条 AC 上栽了三次。
 *
 * <h3>🚨 三条会让整轮测试作废的前提（test.md §0）</h3>
 * <ol>
 *   <li>{@code field-tree} 的方言入参名是 <b>{@code dialect}</b>，不是 {@code dataset} ——
 *       JAX-RS 静默忽略未知查询参数，传错会让三方言返回同一份缺省 QUOTE 结果。
 *       {@link #assertDialectParamIsHonored} 是这条路径的阳性对照。</li>
 *   <li>与 dev server 抢 {@code target/} 会产生 {@code NoClassDefFoundError} 型<b>假红</b> ⇒ 用隔离副本跑。</li>
 *   <li>🚫 不 cd 回主仓跑 {@code mvnw}（会测另一棵树）。</li>
 * </ol>
 *
 * <h3>🚨 环境纪律（CLAUDE.md §3.2「测试也算」）</h3>
 * {@code test} profile 的库<b>就是共享开发库</b> {@code 10.177.152.12:5432/cpq_db_0724}。
 * ⇒ 全套用例不出现 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的 {@code DELETE} / 清库 / 全局配置重置；
 * 每条 DELETE 的命中面被「本轮自建的 {@code t260907_<RUN_ID>} 前缀 / 自建 id」限死；
 * 🚫 不动 {@code semantic_tab_view} / {@code semantic_node} 的存量数据，🚫 不改 V413 种子。
 */
abstract class Task260907Base {

    /**
     * 组件名等「长字段」的前缀。
     * 🚨 <b>命名空间由主线统一分配</b>（2026-09-07）：共享库上同时有三条会话在造夹具，
     * 本线测试固定用 {@code T260907Q}；后端代理用 {@code T260907B}；导入线用 {@code T260907T}。
     * 撞命名空间的危害不是脏数据，是<b>互删</b> —— 两边都按 {@code LIKE 'T260907%'} 清理时谁先跑谁把对方删了，
     * 症状是<b>随机挂且极像业务回归</b>。
     * ⇒ 🚫 本类所有清理谓词一律用<b>本轮自建的完整值或 {@code T260907Q<RUN_ID>} 前缀</b>，
     * 🚫 绝不出现 {@code LIKE 'T260907%'} 这种会扫到别人的写法。
     */
    protected static final String PREFIX = "T260907Q_";

    /** 本次 JVM 运行的唯一标记 —— 两轮运行不可能撞唯一约束，也不会把上轮残留误报成本轮 duplicate key。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /**
     * 夹具<b>料号</b>用的短前缀（10 字符）。
     * <p>🚨 <b>不能用 {@link #PREFIX}</b>：V6 表 {@code material_bom_item} 的
     * {@code material_no / component_no / customer_no} 都是 <b>varchar(20)</b>
     * （{@code ds_quote_*} 侧是 128，所以只在写 V6 时才炸）——
     * 2026-09-07 首跑实测报 {@code value too long for type character varying(20)}，
     * 那个错长得像业务缺陷，其实是夹具超长。
     */
    protected static final String FX = "T260907Q" + RUN_ID.substring(0, 6);

    /** 夹具客户号（≤20 字符，同样受 varchar(20) 约束）。 */
    protected static final String FX_CUST = "T260907Q" + RUN_ID.substring(0, 8);

    protected static final List<String> DIALECTS = List.of("QUOTE", "COST_BASIC", "COST_DETAIL");

    /**
     * 系统列（「业务列 = 物理列 − 系统列」）。逐字沿用 {@code task-260904} AC-20① 的定义，
     * 🚫 不许为了让用例变绿往里加东西。
     */
    protected static final Set<String> SYSTEM_COLUMNS = new LinkedHashSet<>(List.of(
            "id", "version_no", "row_fingerprint", "source", "created_at", "created_by", "updated_at", "updated_by"));

    /** F-4 的三张年降表（需求文档 §② F-4）。 */
    protected static final List<String> ANNUAL_TABLES = List.of(
            "ds_quote_annual_discount", "ds_quote_assembly_fee_annual", "ds_quote_incoming_annual");

    /** F-1 要接入的客户料号表。 */
    protected static final String CUSTOMER_PART_TABLE = "ds_quote_customer_part";
    protected static final String MATERIAL_TABLE = "ds_quote_material";
    protected static final String MATERIAL_BOM_TABLE = "ds_quote_material_bom";

    /** AC-1② 点名的四列（「至少」可拖）—— 逐字取自需求文档，🚫 不多不少地当成「恰好」。 */
    protected static final List<String> AC1_CUSTOMER_PART_COLUMNS = List.of(
            "customer_no", "customer_part_name", "customer_product_no", "customer_drawing_no");

    @Inject
    protected EntityManager em;

    // ═══════════════════════════ SQL 小工具 ═══════════════════════════

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected List<String> strCol(String sql) {
        List<String> out = new ArrayList<>();
        for (Object o : col(sql)) out.add(o == null ? null : o.toString());
        return out;
    }

    protected static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected Set<String> physicalColumns(String table) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + table + "' ORDER BY ordinal_position")) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 业务列 = 物理列 − 系统列。<b>执行期现算</b>，🚫 不写死列清单
     * （AC-11② 列的是 2026-09-07 的实测值，会随 schema 漂移；此处只用它的<i>定义</i>）。
     */
    protected Set<String> businessColumns(String table) {
        Set<String> cols = physicalColumns(table);
        assertFalse(cols.isEmpty(), "前置未满足：表 " + table + " 在 information_schema 里查不到列 "
                + "⇒ 「面板出全部业务列」的断言会退化成空集比空集（恒真）。");
        cols.removeAll(SYSTEM_COLUMNS);
        assertFalse(cols.isEmpty(), "前置未满足：表 " + table + " 去掉系统列后业务列为 0 ⇒ 断言会空跑。");
        return cols;
    }

    // ═══════════════════════════ 基线文件定位 ═══════════════════════════

    /**
     * 定位 A 侧基线文件。
     * <p>优先读系统属性 {@code -Dtask260907.baseline.dir}（从 scratch 隔离副本跑时必须传，
     * 因为副本里没有 {@code dev-docs/}）；否则从 {@code user.dir} 向上最多 8 层找。
     * <p>🚫 <b>A 侧基线不重采</b>（test.md §2）：重采 = 拿当前值当基线 = 断言退化成恒真。
     */
    protected static File locateBaseline(String fileName) {
        String override = System.getProperty("task260907.baseline.dir");
        if (override != null && !override.isBlank()) {
            File f = new File(override, fileName);
            if (f.isFile()) return f;
        }
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File c = new File(dir, "dev-docs/task-260907-取数配置器补齐/证据/baseline/" + fileName);
            if (c.isFile()) return c;
        }
        return null;
    }

    // ═══════════════════════════ HTTP（管理员会话）═══════════════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /**
     * 🚨 一律用它起手，🚫 不要用裸 {@code RestAssured.given()}：
     * test profile 开了 RBAC，不带 session 一律 401，而 401 会伪装成「端点没做」或「业务校验拒绝」。
     */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            ADMIN_COOKIES = null;
        }
        // 只解锁，🚫 不改 admin 的密码/状态/角色（testing.md §4.3：不得改变共享库的全局状态）。
        // ⚠️ E2E 反复跑会把 admin 置成 INACTIVE —— 那会让本套用例全体以 401 失败，看起来像鉴权回归。
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
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
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(), "🚨 阳性对照失败：拿到 cookie 但 /auth/me 仍不通（"
                        + me.statusCode() + "）⇒ 会话未生效，此时所有业务断言都不可信。body=" + me.asString());
                return ADMIN_COOKIES;
            }
            try { Thread.sleep(2000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("admin 登录连续 4 次失败，最后 status=" + (last == null ? "?" : last.statusCode())
                + " body=" + (last == null ? "?" : last.asString())
                + "\n🚫 这是**登录基础设施故障**，不是被测功能的结论："
                + "429=登录限流；423/401=账号被锁或被 E2E 置成 INACTIVE；5xx=会话存储不可用。");
    }

    /** 🚨 假绿守卫：鉴权/路由把请求挡在业务层之外时，「断言非 200」会照样通过。 */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403,
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— 这是 harness 故障，不是 AC 结论。body=" + res.asString());
        assertFalse(res.statusCode() == 404 && res.asString().contains("RESTEASY"),
                when + "：端点 404 ⇒ 路径与 api.md 不一致或端点未实现。body=" + res.asString());
        assertFalse(res.statusCode() == 405,
                when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + res.asString());
    }

    // ═══════════════════════════ field-tree（api.md §1）═══════════════════════════

    protected Response fieldTree(String tabType, String variantKey, String dialect) {
        Response r = given()
                .queryParam("tabType", tabType)
                .queryParam("variantKey", variantKey == null ? "" : variantKey)
                .queryParam("dialect", dialect)   // 🚨 参数名是 dialect，不是 dataset（api.md §0）
                .get("/api/cpq/config/semantic-graph/field-tree").thenReturn();
        assertReachedBusinessLayer(r, "field-tree(" + tabType + "/" + variantKey + "/" + dialect + ")");
        return r;
    }

    protected Response fieldTreeOk(String tabType, String variantKey, String dialect, String acRef) {
        Response r = fieldTree(tabType, variantKey, dialect);
        assertEquals(200, r.statusCode(), acRef + "：field-tree(" + tabType + "/" + variantKey + "/" + dialect
                + ") 应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r;
    }

    /** 某方言里任取一个 ACTIVE 坐标（用来「进门」拿 availableSources）。🚫 不写死「主件」——坐标值是配置数据。 */
    protected String[] anyActiveCoordinate(String dialect) {
        List<Object[]> rs = rows("SELECT tab_type, coalesce(variant_key,'') FROM semantic_tab_view "
                + "WHERE dialect='" + dialect + "' AND status='ACTIVE' ORDER BY tab_type, variant_key LIMIT 1");
        assertFalse(rs.isEmpty(), "库里 dialect=" + dialect + " 没有任何 ACTIVE 的 semantic_tab_view 行 "
                + "⇒ 本方言下所有断言都会空跑。这是地基故障，不是 AC 结论。");
        return new String[]{String.valueOf(rs.get(0)[0]), String.valueOf(rs.get(0)[1])};
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> availableSources(String dialect, String acRef) {
        String[] coord = anyActiveCoordinate(dialect);
        Response r = fieldTreeOk(coord[0], coord[1], dialect, acRef);
        Object raw = r.jsonPath().get("availableSources");
        assertNotNull(raw, acRef + "：响应里没有 availableSources 字段（api.md §1.2）。body=" + r.asString());
        List<Map<String, Object>> src = (List<Map<String, Object>>) raw;
        assertFalse(src.isEmpty(), acRef + "：dialect=" + dialect + " 的 availableSources 为空 "
                + "⇒ 后续「含/不含某项」的断言全部空跑（testing.md §3 假绿）。");
        return src;
    }

    /**
     * 🚨 <b>阳性对照：证明 {@code dialect} 入参真的被消费了。</b>
     * 传错参数名会被静默忽略并回落到默认方言 —— 那时「每条的 dialect 恒等于入参」照样成立，用例照样绿。
     */
    protected void assertDialectParamIsHonored(String acRef) {
        Set<String> qk = sourceKeySet(availableSources("QUOTE", acRef));
        Set<String> ck = sourceKeySet(availableSources("COST_DETAIL", acRef));
        assertFalse(qk.equals(ck), acRef + "：QUOTE 与 COST_DETAIL 的 availableSources 完全相同 "
                + "⇒ 🚨 dialect 入参很可能没被消费（参数名不匹配会被 JAX-RS 静默忽略并回落到默认方言）。"
                + "此时所有按方言分支的断言都不可信。QUOTE=" + qk + " COST_DETAIL=" + ck);
        System.out.println("[" + acRef + "] ✅ dialect 阳性对照通过：QUOTE(" + qk.size() + ") ≠ COST_DETAIL(" + ck.size() + ")");
    }

    protected static Set<String> sourceKeySet(List<Map<String, Object>> sources) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> s : sources) {
            out.add(s.get("sourceKey") + "/" + s.get("tabType") + "/"
                    + (s.get("variantKey") == null ? "" : s.get("variantKey")));
        }
        return out;
    }

    protected static List<String> labelsOf(List<Map<String, Object>> sources) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> s : sources) out.add(String.valueOf(s.get("label")));
        return out;
    }

    // ═══════════════════════════ groups（字段面板）═══════════════════════════

    protected record Group(String groupKey, String groupKind, List<String> sourceColumns) { }

    @SuppressWarnings("unchecked")
    protected List<Group> groupsOf(String tabType, String variantKey, String dialect, String acRef) {
        Response r = fieldTreeOk(tabType, variantKey, dialect, acRef);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) r.jsonPath().get("groups");
        assertNotNull(raw, acRef + "：响应无 groups 字段。body=" + r.asString());
        assertFalse(raw.isEmpty(), acRef + "：" + dialect + "/" + tabType + "/" + variantKey
                + " 的 groups 为空 ⇒ 字段面板断言会空跑。body=" + r.asString());
        List<Group> out = new ArrayList<>();
        for (Map<String, Object> g : raw) {
            List<Map<String, Object>> fs = (List<Map<String, Object>>) g.get("fields");
            List<String> cols = new ArrayList<>();
            if (fs != null) for (Map<String, Object> f : fs) cols.add(String.valueOf(f.get("sourceColumn")));
            out.add(new Group(String.valueOf(g.get("groupKey")), String.valueOf(g.get("groupKind")), cols));
        }
        return out;
    }

    /** 面板里出现的全部 sourceColumn（跨分组合并）。 */
    protected static Set<String> allColumnsOf(List<Group> groups) {
        Set<String> out = new LinkedHashSet<>();
        for (Group g : groups) out.addAll(g.sourceColumns());
        return out;
    }

    /** 该坐标锚点节点的 {@code semantic_node.id}（🚨 必须按 id 查列，同名 node_key 在三方言下是不同节点）。 */
    protected String anchorNodeId(String dialect, String tabType, String variantKey) {
        return scalar("SELECT n.id::text FROM semantic_tab_view v JOIN semantic_node n ON n.id=v.anchor_node_id "
                + "WHERE v.dialect='" + dialect + "' AND v.tab_type='" + tabType + "' "
                + "AND coalesce(v.variant_key,'')='" + (variantKey == null ? "" : variantKey) + "' AND v.status='ACTIVE'");
    }

    protected String anchorPhysicalTable(String dialect, String tabType, String variantKey) {
        return scalar("SELECT n.physical_table FROM semantic_tab_view v JOIN semantic_node n ON n.id=v.anchor_node_id "
                + "WHERE v.dialect='" + dialect + "' AND v.tab_type='" + tabType + "' "
                + "AND coalesce(v.variant_key,'')='" + (variantKey == null ? "" : variantKey) + "' AND v.status='ACTIVE'");
    }

    protected String partNoColumnOf(String nodeId) {
        return scalar("SELECT db_column FROM semantic_node_column WHERE node_id='" + nodeId
                + "' AND status='ACTIVE' AND 'PART_NO' = ANY(roles) ORDER BY db_column LIMIT 1");
    }

    // ═══════════════════════════ compile / preview / save（api.md §2）═══════════════════════════

    /** 组装一份「裸 builder_config」—— 🚫 不套 {@code {"builderConfig": {...}}} 外层（Sec35 2026-08-21 踩过）。 */
    protected static String cfg(String dialect, String tabType, String variantKey, String... columnJson) {
        return "{\"dialect\":\"" + dialect + "\",\"tabType\":\"" + tabType + "\",\"variantKey\":\""
                + (variantKey == null ? "" : variantKey) + "\",\"columns\":[" + String.join(",", columnJson) + "]}";
    }

    protected static String colJson(String nodeKey, String column, String fieldName, boolean rowKey, boolean partNo) {
        return "{\"sourceNodeKey\":\"" + nodeKey + "\",\"sourceColumn\":\"" + column + "\",\"fieldName\":\"" + fieldName
                + "\",\"isRowKey\":" + rowKey + ",\"isPartNo\":" + partNo + "}";
    }

    protected static String colJson(String nodeKey, String column, String fieldName) {
        return colJson(nodeKey, column, fieldName, false, false);
    }

    protected Response compileCfg(UUID componentId, String builderConfigJson) {
        Response r = given().contentType(ContentType.JSON).body(builderConfigJson)
                .post("/api/cpq/components/" + componentId + "/builder/compile").thenReturn();
        assertReachedBusinessLayer(r, "compile(" + componentId + ")");
        return r;
    }

    protected String compileSql(UUID componentId, String builderConfigJson, String acRef) {
        Response r = compileCfg(componentId, builderConfigJson);
        assertEquals(200, r.statusCode(), acRef + "：编译应 200，实际=" + r.statusCode()
                + "\n  请求=" + builderConfigJson + "\n  body=" + r.asString());
        String sql = r.jsonPath().getString("sql");
        assertNotNull(sql, acRef + "：编译产物里取不到 sql ⇒ 断言会空跑。body=" + r.asString());
        assertFalse(sql.isBlank(), acRef + "：编译产物 sql 为空串 ⇒ 「不含 X」类断言会恒成立。");
        return sql;
    }

    /**
     * {@code POST /builder/preview}：请求体 = 裸 builder_config + <b>平级</b>的预览参数。
     *
     * <h4>🔑 2026-09-07 实测（本轮亲测，非转述）</h4>
     * <b>不传 {@code partNo} 时 {@code rowCount} 恒为 0</b>（轴参数 {@code :total_material_no} 为空数组）。
     * ⇒ 任何「预览有多少行」的断言若不带 {@code partNo}，都会在 0 上恒成立 —— 这是本任务最大的空跑陷阱。
     */
    protected Response preview(UUID componentId, String builderConfigJson, String partNo) {
        return preview(componentId, builderConfigJson, partNo, null);
    }

    /**
     * @param customerCode 客户谓词的取值。
     *   🔑 2026-09-07 用户裁决给「物料」数据源加了 {@code customer_no = :customerCode} 谓词 ⇒
     *   主件/树页签多出一个 <b>必需变量</b>。preview 报「缺变量」时<b>是预期行为不是缺陷</b>，
     *   把本参数传上即可。
     */
    protected Response preview(UUID componentId, String builderConfigJson, String partNo, String customerCode) {
        String extra = (partNo == null ? "\"customerCode\":null" : "\"partNo\":\"" + partNo + "\"")
                + (customerCode == null ? "" : ",\"customerCode\":\"" + customerCode + "\"");
        String body = "{" + extra + "," + builderConfigJson.substring(builderConfigJson.indexOf('{') + 1);
        Response r = given().contentType(ContentType.JSON).body(body)
                .post("/api/cpq/components/" + componentId + "/builder/preview").thenReturn();
        assertReachedBusinessLayer(r, "preview(" + componentId + ", partNo=" + partNo + ")");
        return r;
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> previewRows(UUID componentId, String builderConfigJson, String partNo, String acRef) {
        return previewRows(componentId, builderConfigJson, partNo, null, acRef);
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> previewRows(UUID componentId, String builderConfigJson, String partNo,
                                                    String customerCode, String acRef) {
        Response r = preview(componentId, builderConfigJson, partNo, customerCode);
        assertEquals(200, r.statusCode(), acRef + "：preview 应 200，实际=" + r.statusCode() + " body=" + r.asString());
        Object raw = r.jsonPath().get("rows");
        assertNotNull(raw, acRef + "：preview 响应缺 rows。body=" + r.asString());
        return (List<Map<String, Object>>) raw;
    }

    protected Response saveBuilder(UUID componentId, String builderConfigJson) {
        Response r = given().contentType(ContentType.JSON).body(builderConfigJson)
                .put("/api/cpq/components/" + componentId + "/builder").thenReturn();
        assertReachedBusinessLayer(r, "saveBuilder(" + componentId + ")");
        return r;
    }

    protected Response readBuilder(UUID componentId, String acRef) {
        Response r = given().get("/api/cpq/components/" + componentId + "/builder").thenReturn();
        assertReachedBusinessLayer(r, acRef + " readBuilder");
        assertEquals(200, r.statusCode(), acRef + "：读回 builder 配置应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r;
    }

    // ═══════════════════════ 直接执行「配置器产出的 SQL」（全量口径）═══════════════════════

    /**
     * 把编译产物里的 {@code :total_material_no} 换成字面量数组后执行。
     *
     * <h4>为什么需要它 —— AC-2① 的可执行性问题</h4>
     * AC-2① 写「预览行数 = 47」，但 {@code /builder/preview} 是<b>按 {@code partNo} 收窄</b>的
     * （不传 partNo 恒 0 行，2026-09-07 实测）。⇒ 「47 行」在预览端点上根本复现不出来。
     * 本方法把配置器<b>真实产出的 SQL</b> 拿到全量轴（该表全部料号）上执行，
     * 才能验到 AC-2 的实质：<b>物料为主、LEFT JOIN、一行不丢一行不多</b>。
     *
     * @param axis {@code :total_material_no} 的取值
     */
    protected String bindAxis(String compiledSql, List<String> axis) {
        return bindAxis(compiledSql, axis, null);
    }

    /**
     * @param customerCode 绑 {@code :customerCode}。
     *   🔑 2026-09-07 用户裁决给「物料」加了客户谓词后，编译产物多出这个占位符；
     *   不绑会报 {@code No argument for named parameter ':customerCode'} ——
     *   那是<b>夹具没跟上契约</b>，不是产品缺陷。
     */
    protected String bindAxis(String compiledSql, List<String> axis, String customerCode) {
        assertFalse(axis.isEmpty(), "构造自检：轴为空数组 ⇒ 生成的 SQL 恒返 0 行，任何行数断言都会空跑。");
        StringBuilder arr = new StringBuilder("ARRAY[");
        for (int i = 0; i < axis.size(); i++) {
            if (i > 0) arr.append(',');
            arr.append('\'').append(axis.get(i).replace("'", "''")).append('\'');
        }
        arr.append("]::text[]");
        String bound = compiledSql.replace(":total_material_no", arr.toString());
        assertFalse(bound.contains(":total_material_no"), "轴参数替换失败");
        if (bound.contains(":customerCode")) {
            bound = bound.replace(":customerCode", customerCode == null
                    ? "NULL::text" : "'" + customerCode.replace("'", "''") + "'");
        }
        return bound;
    }

    /** {@code SELECT <selectList> FROM ( <编译产物> ) t}。ORDER BY 在子查询里对 PG 合法。 */
    protected List<Object[]> runCompiled(String compiledSql, List<String> axis, String selectList) {
        return runCompiled(compiledSql, axis, selectList, null);
    }

    protected List<Object[]> runCompiled(String compiledSql, List<String> axis, String selectList, String customerCode) {
        return rows("SELECT " + selectList + " FROM (" + bindAxis(compiledSql, axis, customerCode) + ") t");
    }

    protected long runCompiledCount(String compiledSql, List<String> axis) {
        return runCompiledCount(compiledSql, axis, null);
    }

    protected long runCompiledCount(String compiledSql, List<String> axis, String customerCode) {
        return count("SELECT count(*) FROM (" + bindAxis(compiledSql, axis, customerCode) + ") t");
    }

    // ═══════════════════════════ 组件（真实入口建/删）═══════════════════════════

    protected final List<UUID> createdComponentIds = new ArrayList<>();

    protected UUID createBlankComponent(String label) {
        Response resp = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + PREFIX + label + "_" + RUN_ID + "\"}")
                .post("/api/cpq/components").thenReturn();
        assertReachedBusinessLayer(resp, "建空白组件(" + label + ")");
        assertEquals(200, resp.statusCode(), "建空白组件应 200，实际=" + resp.statusCode() + " body=" + resp.asString());
        UUID id = UUID.fromString(resp.jsonPath().getString("data.id"));
        createdComponentIds.add(id);
        return id;
    }

    // ═══════════════════════════ BOM 夹具（AC-4 / AC-6 / AC-7 / AC-12）═══════════════════════════

    /**
     * 一套自建的 DAG 形态基础数据。<b>全部带 {@code t260907_<RUN_ID>} 前缀</b>，@AfterEach 精确删净。
     *
     * <pre>
     *   R ─┬─▶ C1 ─┐
     *      └─▶ C2 ─┴─▶ G      G 同时挂在 C1 / C2 下（AC-7：合法 DAG，不去重）
     * </pre>
     * 闭包轴 = {R, C1, C2, G}。<b>边式契约</b>下页签 SQL 应产出：
     * 4 条边（子件 ∈ 轴）+ 1 条根分支（R 无父边）= 5 行，其中 G 出现 2 次、且 G 是<b>孙级</b>（AC-4②）。
     */
    protected static final class BomFx {
        public String root, c1, c2, grandchild;
        /** 只有 root 在 {@code ds_quote_customer_part} 里有对应行 ⇒ AC-2②/AC-6① 的「有/无客户料号」两侧。 */
        public String customerNo;
        public String customerProductNo;
        /** 是否同时往 V6 {@code material_bom_item} 写了同构的边（渲染链路要用）。 */
        public boolean v6Edges;

        public List<String> axis() { return List.of(root, c1, c2, grandchild); }
    }

    protected final List<BomFx> bomFixtures = new ArrayList<>();

    protected BomFx buildBomFixture(String label, boolean alsoV6Edges) {
        BomFx f = new BomFx();
        // 🚨 料号总长必须 ≤ 20（V6 表列宽），故用短前缀 FX 而不是 PREFIX
        // FX 已 14 字符，V6 列宽 20 ⇒ label 只留 1 字符、后缀最多 2 字符（R/C1/C2/G）
        String tag = FX + label.substring(label.length() - 1);
        f.root = tag + "R";
        f.c1 = tag + "C1";
        f.c2 = tag + "C2";
        f.grandchild = tag + "G";
        for (String mn : List.of(f.root, f.c1, f.c2, f.grandchild)) {
            assertTrue(mn.length() <= 20, "构造自检：夹具料号 " + mn + " 长度 " + mn.length()
                    + " > 20，写 V6 material_bom_item 会报 value too long（那个错长得像业务缺陷）");
        }
        f.customerNo = FX_CUST;
        f.customerProductNo = tag + "CPN";
        f.v6Edges = alsoV6Edges;

        // 构造自检：这四个料号必须原本不存在，否则「我造的行出现了」会被存量行冒充
        for (String mn : f.axis()) {
            assertEquals(0L, count("SELECT count(*) FROM " + MATERIAL_TABLE + " WHERE material_no='" + mn + "'"),
                    "构造自检：夹具料号 " + mn + " 竟已存在于 " + MATERIAL_TABLE + " ⇒ 断言会被存量行冒充");
        }

        QuarkusTransaction.requiringNew().run(() -> {
            for (String mn : f.axis()) {
                em.createNativeQuery("INSERT INTO " + MATERIAL_TABLE
                                + " (material_no, material_name, source, created_at) VALUES (:mn,:nm,'TEST-t260907',now())")
                        .setParameter("mn", mn).setParameter("nm", PREFIX + "料-" + mn).executeUpdate();
            }
            insertDsEdge(f.root, f.c1, 10);
            insertDsEdge(f.root, f.c2, 20);
            insertDsEdge(f.c1, f.grandchild, 10);
            insertDsEdge(f.c2, f.grandchild, 10);   // AC-7：同一子件挂两个父件 = 合法 DAG

            // 只给 root 造客户料号 ⇒ c1/c2/G 是「没有客户料号的物料」（AC-2②/AC-6①）
            em.createNativeQuery("INSERT INTO " + CUSTOMER_PART_TABLE
                            + " (customer_no, customer_part_name, customer_product_no, customer_drawing_no, material_no, source, created_at) "
                            + "VALUES (:cn,:pn,:cpn,:dn,:mn,'TEST-t260907',now())")
                    .setParameter("cn", f.customerNo).setParameter("pn", PREFIX + "客户品名")
                    .setParameter("cpn", f.customerProductNo).setParameter("dn", PREFIX + "图号")
                    .setParameter("mn", f.root).executeUpdate();

            if (alsoV6Edges) {
                insertV6Edge(f.customerNo, f.root, f.c1, 10);
                insertV6Edge(f.customerNo, f.root, f.c2, 20);
                insertV6Edge(f.customerNo, f.c1, f.grandchild, 10);
                insertV6Edge(f.customerNo, f.c2, f.grandchild, 10);
            }
        });
        bomFixtures.add(f);
        System.out.println("[t260907·fixture] " + label + " root=" + f.root + " c1=" + f.c1 + " c2=" + f.c2
                + " g=" + f.grandchild + " customerNo=" + f.customerNo + " v6=" + alsoV6Edges);

        // 阳性对照：夹具真的落库了（不落库时后面「我的行出现了」会红成看似产品缺陷）
        assertEquals(4L, count("SELECT count(*) FROM " + MATERIAL_BOM_TABLE
                + " WHERE material_no LIKE '" + tag + "%'"),
                "夹具自检：ds_quote_material_bom 应落 4 条边");
        return f;
    }

    private void insertDsEdge(String parent, String child, int seq) {
        em.createNativeQuery("INSERT INTO " + MATERIAL_BOM_TABLE
                        + " (material_no, item_seq, input_material_no, component_qty, version_no, row_fingerprint, source, created_at) "
                        + "VALUES (:p,:s,:c,1,1,:fp,'TEST-t260907',now())")
                .setParameter("p", parent).setParameter("s", seq).setParameter("c", child)
                .setParameter("fp", md5(parent + "|" + child + "|" + seq + "|" + RUN_ID)
                        + md5(child + "|" + parent + "|" + RUN_ID))   // 64 字符
                .executeUpdate();
    }

    private void insertV6Edge(String customerNo, String parent, String child, int seq) {
        em.createNativeQuery("INSERT INTO material_bom_item (id,system_type,customer_no,material_no,component_no,"
                        + "item_seq,is_current,composition_qty,created_at,updated_at) "
                        + "VALUES (gen_random_uuid(),'QUOTE',:cn,:p,:c,:s,true,1,NOW(),NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent).setParameter("c", child)
                .setParameter("s", seq).executeUpdate();
    }

    // ═══════════════════════════ 还原 ═══════════════════════════

    /**
     * 还原本套用例写进共享库的一切。
     * <p>🚫 每条 DELETE 都带收敛谓词（自建 id / 自建 customer_no / {@code t260907_<RUN_ID>} 前缀），
     * 不存在无 WHERE 的删除，不存在 TRUNCATE / DROP（CLAUDE.md §3.2）。
     * <p>🚨 写在 {@code @AfterEach}（等价 finally）：用例中途崩溃也会执行。
     */
    @AfterEach
    void cleanupTask260907() {
        List<String> errors = new ArrayList<>();
        for (BomFx f : bomFixtures) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("DELETE FROM " + MATERIAL_BOM_TABLE
                            + " WHERE material_no LIKE :p OR input_material_no LIKE :p")
                            .setParameter("p", FX + "%").executeUpdate();
                    em.createNativeQuery("DELETE FROM " + CUSTOMER_PART_TABLE + " WHERE customer_no = :cn")
                            .setParameter("cn", f.customerNo).executeUpdate();
                    em.createNativeQuery("DELETE FROM " + MATERIAL_TABLE + " WHERE material_no LIKE :p")
                            .setParameter("p", FX + "%").executeUpdate();
                    if (f.v6Edges) {
                        em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no = :cn")
                                .setParameter("cn", f.customerNo).executeUpdate();
                    }
                });
            } catch (RuntimeException e) {
                errors.add("清理 BOM 夹具失败: " + e);
            }
        }
        bomFixtures.clear();

        if (!createdComponentIds.isEmpty()) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    for (UUID cid : createdComponentIds) {
                        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :id")
                                .setParameter("id", cid).executeUpdate();
                        em.createNativeQuery("DELETE FROM component WHERE id = :id")
                                .setParameter("id", cid).executeUpdate();
                    }
                });
            } catch (RuntimeException e) {
                errors.add("清理自建组件失败: " + e);
            }
            createdComponentIds.clear();
        }

        if (!errors.isEmpty()) System.out.println("[t260907·cleanup] ⚠️ " + errors);
        assertResidueFree();
    }

    /** 清完立刻自检：脏库必须以「残留」的名义硬失败，🚫 不许伪装成下一轮的业务缺陷。 */
    protected void assertResidueFree() {
        long comps = count("SELECT count(*) FROM component WHERE name LIKE '" + PREFIX + "%" + RUN_ID + "%'");
        long mats = count("SELECT count(*) FROM " + MATERIAL_TABLE + " WHERE material_no LIKE '" + FX + "%'");
        long edges = count("SELECT count(*) FROM " + MATERIAL_BOM_TABLE + " WHERE material_no LIKE '" + FX + "%'");
        long cps = count("SELECT count(*) FROM " + CUSTOMER_PART_TABLE + " WHERE customer_no = '" + FX_CUST + "'");
        long v6 = count("SELECT count(*) FROM material_bom_item WHERE material_no LIKE '" + FX + "%' "
                + "OR component_no LIKE '" + FX + "%'");
        System.out.println("[t260907·residue] component=" + comps + " material=" + mats + " ds_edge=" + edges
                + " customer_part=" + cps + " v6_edge=" + v6);
        assertEquals(0L, comps + mats + edges + cps + v6,
                "还原自检：本轮夹具仍有残留（component=" + comps + " material=" + mats + " ds_edge=" + edges
                        + " customer_part=" + cps + " v6_edge=" + v6 + "）—— 共享 dev 库必须清干净");
    }

    // ═══════════════════════════ 通用断言助手 ═══════════════════════════

    /**
     * 🚨 <b>守卫顺序铁律</b>（test.md §3④，本任务线真踩过）：
     * <b>先断言集合非空，再断言集合内元素合格</b>。
     * 反过来写（「收集不合格项，列表为空即通过」）在「一个都没采到」时恒真通过。
     */
    protected static void assertNonEmptyThenAllMatch(java.util.Collection<String> actual,
                                                     java.util.Collection<String> mustContain,
                                                     String what, String acRef) {
        assertFalse(actual.isEmpty(), acRef + "：" + what + " 为空集 ⇒ 「应包含 X」的断言会以另一种方式失败、"
                + "「不应包含 Y」的断言会恒真通过。这是空跑，不是结论。");
        List<String> missing = new ArrayList<>();
        for (String m : mustContain) if (!actual.contains(m)) missing.add(m);
        assertTrue(missing.isEmpty(), acRef + "：" + what + " 缺少 " + missing + "。实际=" + actual);
    }
}
