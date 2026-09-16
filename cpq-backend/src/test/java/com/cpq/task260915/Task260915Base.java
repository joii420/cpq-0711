package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915「组件导出/导入往返保真」<b>分片 S-A</b> 的公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260915-组件导出导入往返保真/需求文档.md §③} 的 AC 原文，
 * 接口结构指回同目录 {@code api.md} 与 {@code dev-docs/main-api.md §2.1}。
 * <p>🚫 <b>本套用例一行都不从实现代码反推</b>：不读
 * {@code cpq-backend/src/main/java/com/cpq/component/**}、{@code cpq-frontend/src/pages/component/**}。
 *
 * <h3>本片认领（test.md §3 分片计划 · S-A）</h3>
 * AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 / AC-14 / AC-16 / AC-17 / AC-18。
 * 🚫 AC-7 / AC-8 / AC-9 / AC-10 / AC-11 / AC-12 / AC-13 / AC-15 不归本片，本包<b>一条都不写</b>。
 *
 * <h3>🚨 数据隔离（分片纪律，写死不许改）</h3>
 * <ul>
 *   <li>本片<b>只写自己新建的目录</b>，目录名一律 {@code RT-SA-260915-<RUN_ID>-<tag>}。
 *       别片用 {@code RT-SB-260915-*}，<b>碰都不碰</b>。</li>
 *   <li>只读源目录 {@code 取值配置器测试} <b>纯只读</b>：只 SELECT、只导出，<b>一行都不改</b>。
 *       AC-4 的 {@code tree_config} 与 AC-5 的 {@code INACTIVE} 视图<b>造在自己的副本目录 M 上</b>
 *       （test.md §1「不许在现有共享目录上改数据造样本」）。</li>
 *   <li>🚫 <b>全片无全局计数断言</b>：所有 count 都带 {@code directory_id IN (本轮自建目录)} 限定
 *       （testing.md §4.5「共库并行真正的杀手：全局计数断言」）。</li>
 *   <li>🚫 <b>无 TRUNCATE / DROP / 清库 / 无 WHERE 的 DELETE / 全局配置重置</b>
 *       （CLAUDE.md §3.2「测试也算」）。清理的 DELETE 谓词被本轮自建目录 id 限死。</li>
 * </ul>
 *
 * <h3>🚨 三条会让整轮结论作废的前提</h3>
 * <ol>
 *   <li><b>源目录不能靠硬编码 id 找。</b> test.md §4 记的 {@code 334c394b-…} 是 {@code cpq_db_0724} 的实查值；
 *       本基座<b>按目录名精确等值查</b>（库里还有一个名叫「取值配置器测试2」的目录，
 *       用 {@code LIKE '%取值%'} 会同时命中两个，然后把断言打在错误的目录上）。</li>
 *   <li><b>配对键不能用 {@code code}。</b> {@code component_code_key} 是<b>全局唯一索引</b>
 *       （实查 {@code pg_indexes}）⇒ 把包导进<i>任何</i>目录都会触发 RENAME，
 *       导入后的 {@code code} 必然与源不同。⇒ 一律用提交响应 {@code created[].originalCode → componentId} 配对。
 *       这也正是 AC-6 白名单把 {@code code} 标注「RENAME 策略下允许不同」的原因。</li>
 *   <li><b>「M vs N 相等」单独不成立。</b> 若导入本身丢字段，副本 M 早已残缺，
 *       M 与 N 会「都为 NULL 地相等」⇒ 假绿。
 *       ⇒ 本片的主断言是 <b>源目录 S vs 副本 M</b>（AC-1/2/3/6 原文就是这么写的），
 *       M vs N 只用于 AC-4/AC-5 这两个必须造数的字段和 AC-14 的幂等。</li>
 * </ol>
 *
 * <h3>比对语义</h3>
 * 所有列先 {@code ::text} 取出，再在 Java 侧用 {@link Objects#equals} 比对 ——
 * 这与 SQL 的 {@code IS DISTINCT FROM} <b>同义</b>：两边都是 {@code null} 判相等，
 * {@code null} 与空串 {@code ""} 判<b>不等</b>。
 * 🚫 绝不用 {@code <>}：它遇 {@code NULL} 返回 {@code NULL} 而非 true，
 * 「NULL 被写成空串」这类丢失会被静默漏掉，而那恰是 AC-2 要防的。
 */
abstract class Task260915Base {

    /** 本片造数前缀（派工写死，🚫 不许改）。 */
    protected static final String PREFIX = "RT-SA-260915-";

    /** 本次 JVM 运行唯一标记 —— 两轮运行不会撞名，也不会把上轮残留误当本轮产物。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** 只读源目录名（精确等值，🚫 不用 LIKE）。 */
    protected static final String SOURCE_DIR_NAME = "取值配置器测试";

    /**
     * AC-6 白名单 · {@code component} 侧（需求文档 AC-6 逐条照抄 + 理由）。
     * <ul>
     *   <li>{@code id} —— 导入必然分配新主键，不是配置。</li>
     *   <li>{@code directory_id} —— 导入目标目录由用户指定，需求文档 §② 明确「目录属性保真」不做。</li>
     *   <li>{@code code} —— 全局唯一索引下 RENAME 必然改它；AC-6 白名单原文即注明「RENAME 策略下允许不同」。</li>
     *   <li>{@code created_at} / {@code updated_at} —— 写入时间戳，非配置。</li>
     * </ul>
     */
    protected static final Set<String> COMPONENT_WHITELIST =
            new LinkedHashSet<>(List.of("id", "directory_id", "code", "created_at", "updated_at"));

    /**
     * AC-6 白名单 · {@code component_sql_view} 侧（需求文档 AC-6 逐条照抄 + 理由）。
     * {@code id}（新主键）/ {@code component_id}（指向新组件）/ {@code created_by}（导入者≠原作者）/
     * {@code created_at} / {@code updated_at}（时间戳）。
     */
    protected static final Set<String> SQLVIEW_WHITELIST =
            new LinkedHashSet<>(List.of("id", "component_id", "created_by", "created_at", "updated_at"));

    /**
     * 需求文档 §② 点名的「8 个丢失字段」。AC-18 逐条验它们，其余 AC 靠 AC-6 的全列差集兜底。
     * ⚠️ 这里只是<b>点名清单</b>，不是比对列清单 —— 比对列一律执行期从 {@code information_schema} 现算，
     * 🚫 不写死（AC-6 原文写的 component「18 列」/ component_sql_view「10 列」是文档撰写时的口径，
     * 实查 {@code cpq_db_test}：component 23−5=<b>18</b> ✅、component_sql_view 14−5=<b>9</b> ❌ 与 AC 的 10 对不上，
     * 已在 test-report 里作为「AC 与实际对不上」上报主线。本基座按<b>现算值</b>比对，不按文档数字。）
     */
    protected static final List<String> EIGHT_LOST_COMPONENT_FIELDS = List.of(
            "tree_config", "bom_recursive_expand",
            "element_code_field", "element_price_field", "element_currency_field");
    protected static final List<String> EIGHT_LOST_SQLVIEW_FIELDS = List.of(
            "builder_config", "builder_version", "status");

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本轮自建目录（清理 + test-report 的「待回收清单」都取自它）。 */
    protected final List<UUID> createdDirs = new ArrayList<>();

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

    protected int exec(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(sql).executeUpdate());
    }

    /** SQL 字面量转义（单引号加倍）。入参全部来自本轮自建值 / UUID，不接受外部输入。 */
    protected static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    protected static String uuidList(Collection<UUID> ids) {
        List<String> parts = new ArrayList<>();
        for (UUID id : ids) parts.add("'" + id + "'::uuid");
        return String.join(",", parts);
    }

    // ═══════════════════════════ 列清单（执行期现算，🚫 不写死）═══════════════════════════

    protected List<String> physicalColumns(String table) {
        List<String> out = new ArrayList<>();
        for (Object o : col("SELECT column_name FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name=" + lit(table) + " ORDER BY ordinal_position")) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 参与 AC-6 比对的列 = 物理列 − 白名单。
     * <p>🚨 断言非空：查不到列时差集会退化成「空集比空集」= 恒真 = 假绿。
     */
    protected List<String> comparedColumns(String table, Set<String> whitelist) {
        List<String> cols = physicalColumns(table);
        assertFalse(cols.isEmpty(), "前置未满足：information_schema 里查不到表 " + table + " 的列 "
                + "⇒ AC-6 的逐列差集会退化成空集比空集（恒真）。这是 harness 故障，不是 AC 结论。");
        List<String> out = new ArrayList<>();
        for (String c : cols) if (!whitelist.contains(c)) out.add(c);
        assertFalse(out.isEmpty(), "前置未满足：表 " + table + " 去掉白名单后待比对列为 0 ⇒ AC-6 会空跑。");
        for (String w : whitelist) {
            assertTrue(cols.contains(w), "白名单列 " + table + "." + w
                    + " 在库里不存在 ⇒ 白名单写错了（或 schema 变了），此时白名单在遮蔽别的列。");
        }
        return out;
    }

    /** 取一行的指定列，全部 {@code ::text}。值为 SQL NULL 时 map 里是 Java {@code null}（不是 ""）。 */
    protected Map<String, String> rowText(String table, List<String> cols, UUID id) {
        List<String> sel = new ArrayList<>();
        for (String c : cols) sel.add(c + "::text");
        // 🚨 JPA native query 在<b>只选一列</b>时返回 List<Object>（标量），选多列才返回 List<Object[]>。
        //    2026-09-15 首轮实测：AC-3/AC-4 传单列进来直接 ClassCastException ——
        //    那是<b>本 harness 的口径错</b>，不是被测功能的结论。
        List<?> rs = em.createNativeQuery("SELECT " + String.join(", ", sel)
                + " FROM " + table + " WHERE id='" + id + "'::uuid").getResultList();
        assertEquals(1, rs.size(), "按 id=" + id + " 在 " + table + " 应取到恰好 1 行，实际 " + rs.size() + " 行。");
        Object row = rs.get(0);
        Object[] r = (cols.size() == 1) ? new Object[]{row} : (Object[]) row;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) out.put(cols.get(i), r[i] == null ? null : String.valueOf(r[i]));
        return out;
    }

    // ═══════════════════════════ HTTP（管理员会话）═══════════════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /** 🚨 一律用它起手：test profile 开了 RBAC，不带 session 一律 401，而 401 会伪装成「功能没做」。 */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            ADMIN_COOKIES = null;
        }
        // 只解锁失败计数，🚫 不改 admin 的密码 / 状态 / 角色（testing.md §4.3：不得改变共享库的全局状态）。
        // ⚠️ E2E 反复跑会把 admin 置成 INACTIVE —— 那会让本套全体 401，看起来像鉴权回归。
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

    /** 🚨 假绿守卫：鉴权 / 路由把请求挡在业务层之外时，「断言非 200」会照样通过。 */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403,
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— harness 故障，不是 AC 结论。body=" + res.asString());
        assertFalse(res.statusCode() == 404 && res.asString().contains("RESTEASY"),
                when + "：端点 404 ⇒ 路径与 api.md 不一致或端点未实现。body=" + res.asString());
        assertFalse(res.statusCode() == 405,
                when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + res.asString());
    }

    // ═══════════════════════════ 目录 ═══════════════════════════

    /** 只读源目录 id —— 🚨 按名字<b>精确等值</b>查，库里另有「取值配置器测试2」，LIKE 会同时命中。 */
    protected UUID sourceDirectoryId() {
        List<Object> ids = col("SELECT id::text FROM component_directory WHERE name=" + lit(SOURCE_DIR_NAME));
        assertEquals(1, ids.size(), "前置未满足：按名字精确匹配「" + SOURCE_DIR_NAME + "」应恰好 1 个目录，实际 "
                + ids.size() + " 个 ⇒ 源目录定位不唯一，后续全部断言都可能打在错误目录上。"
                + "（🚫 不要退而用 LIKE —— 库里还有『" + SOURCE_DIR_NAME + "2』）");
        UUID id = UUID.fromString(String.valueOf(ids.get(0)));
        long n = count("SELECT count(*) FROM component WHERE directory_id='" + id + "'::uuid");
        assertTrue(n > 0, "前置未满足：源目录「" + SOURCE_DIR_NAME + "」里 0 个组件 "
                + "⇒ 导出包为空，本片<b>全部</b>往返断言都会空跑（testing.md §3 假绿）。");
        System.out.println("[S-A] 源目录 " + SOURCE_DIR_NAME + " id=" + id + " 组件数=" + n + "（只读，一行不改）");
        return id;
    }

    /** 新建本片专属目录，名字带 {@link #PREFIX} 与 {@link #RUN_ID}，并登记待清理。 */
    protected UUID newDirectory(String tag) {
        String name = PREFIX + RUN_ID + "-" + tag;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortOrder", 99000);
        Response r = given().contentType(ContentType.JSON).body(body)
                .post("/api/cpq/component-directories").thenReturn();
        assertReachedBusinessLayer(r, "新建目录 " + name);
        assertEquals(200, r.statusCode(), "新建目录 " + name + " 应 200，实际 " + r.statusCode() + " body=" + r.asString());
        String id = r.jsonPath().getString("data.id");
        assertNotNull(id, "新建目录返回体里没有 data.id（main-api.md §2.1「新建目录」→ ApiResponse<ComponentDirectoryDTO>）。body=" + r.asString());
        UUID uuid = UUID.fromString(id);
        createdDirs.add(uuid);
        System.out.println("[S-A] 新建目录 " + name + " id=" + uuid);
        return uuid;
    }

    // ═══════════════════════════ 导出 / 预览 / 提交 ═══════════════════════════

    protected record Bundle(String raw, JsonNode json) {
        JsonNode components() { return json.get("components"); }
    }

    /** {@code GET /{id}/export} —— 直返 bundle 实体（非 ApiResponse 包裹，见 main-api.md §2.1）。 */
    protected Bundle exportBundle(UUID dirId, String acRef) {
        Response r = given().get("/api/cpq/component-directories/" + dirId + "/export").thenReturn();
        assertReachedBusinessLayer(r, acRef + " 导出目录 " + dirId);
        assertEquals(200, r.statusCode(), acRef + "：导出应 200，实际 " + r.statusCode() + " body=" + r.asString());
        String raw = r.asString();
        assertFalse(raw == null || raw.isBlank(), acRef + "：导出响应体为空 ⇒ 后续全部字段断言会空跑。");
        try {
            return new Bundle(raw, MAPPER.readTree(raw));
        } catch (Exception e) {
            throw new AssertionError(acRef + "：导出响应不是合法 JSON。body 前 500 字=" 
                    + raw.substring(0, Math.min(500, raw.length())), e);
        }
    }

    /** {@code POST /{id}/import}（预览，只读不写库）。返回 {@code ApiResponse.data}。 */
    protected JsonNode preview(UUID targetDir, String bundleJson, String policy, String acRef) {
        Response r = given().contentType(ContentType.JSON).body(bundleJson)
                .queryParam("conflictPolicy", policy)
                .post("/api/cpq/component-directories/" + targetDir + "/import").thenReturn();
        assertReachedBusinessLayer(r, acRef + " 预览导入 -> " + targetDir);
        assertEquals(200, r.statusCode(), acRef + "：预览应 200，实际 " + r.statusCode() + " body=" + r.asString());
        JsonNode data = readData(r, acRef + " 预览");
        return data;
    }

    /** {@code POST /{id}/import/commit}。返回 {@code ApiResponse.data}（ImportCommitResult）。 */
    protected JsonNode commit(UUID targetDir, String bundleJson, String policy, String acRef) {
        Response r = given().contentType(ContentType.JSON).body(bundleJson)
                .queryParam("conflictPolicy", policy)
                .post("/api/cpq/component-directories/" + targetDir + "/import/commit").thenReturn();
        assertReachedBusinessLayer(r, acRef + " 提交导入 -> " + targetDir);
        assertEquals(200, r.statusCode(), acRef + "：提交导入应 200，实际 " + r.statusCode()
                + "\n  ⚠️ 若这里是 400 且文案含 tabType=(未配置)，正是本任务要修的那条缺陷（需求文档 §① 起因）。"
                + "\n  body=" + r.asString());
        return readData(r, acRef + " 提交");
    }

    private JsonNode readData(Response r, String what) {
        try {
            JsonNode root = MAPPER.readTree(r.asString());
            JsonNode data = root.get("data");
            assertTrue(data != null && !data.isNull(), what + "：ApiResponse.data 为空。body=" + r.asString());
            return data;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(what + "：响应不是合法 JSON。body=" + r.asString(), e);
        }
    }

    // ═══════════════════════════ 往返 + 配对 ═══════════════════════════

    /**
     * 一次完整往返的产物。
     *
     * @param sourceDir     被导出的目录
     * @param bundle        导出的包
     * @param targetDir     导入目标目录（本轮自建）
     * @param dstIdByOrigCode 原始 code → 导入后新组件 id（<b>唯一可靠的配对键</b>，见类 javadoc 前提②）
     * @param finalCodeByOrigCode 原始 code → 落库后的 code
     */
    protected record RoundTrip(UUID sourceDir, Bundle bundle, UUID targetDir,
                               Map<String, UUID> dstIdByOrigCode,
                               Map<String, String> finalCodeByOrigCode) { }

    /** 导出 {@code srcDir} → 导入到新建目录（tag 命名）→ 返回配对表。 */
    protected RoundTrip roundTrip(UUID srcDir, String tag, String acRef) {
        Bundle b = exportBundle(srcDir, acRef);
        UUID target = newDirectory(tag);
        return importInto(srcDir, b, target, "RENAME", acRef);
    }

    protected RoundTrip importInto(UUID srcDir, Bundle b, UUID target, String policy, String acRef) {
        JsonNode pv = preview(target, b.raw(), policy, acRef);
        boolean canCommit = pv.path("canCommit").asBoolean(false);
        assertTrue(canCommit, acRef + "：预览 canCommit=false，无法进入提交 ⇒ 本条 AC 无法验证。"
                + "\n  blockers=" + pv.path("blockers")
                + "\n  checksumValid=" + pv.path("checksumValid")
                + "\n  dependencies=" + pv.path("dependencies"));

        JsonNode res = commit(target, b.raw(), policy, acRef);
        JsonNode created = res.get("created");
        assertTrue(created != null && created.isArray(), acRef + "：提交结果无 created 数组。body=" + res);
        assertTrue(created.size() > 0, acRef + "：提交结果 created 为空 ⇒ 一个组件都没建，"
                + "后续逐字段比对会 0 次循环（testing.md §3 假绿）。body=" + res);

        int bundleSize = b.components() == null ? 0 : b.components().size();
        assertEquals(bundleSize, created.size(), acRef + "：包里 " + bundleSize
                + " 个组件，实际创建 " + created.size() + " 个 ⇒ 有组件被吞掉，后续比对会漏验。body=" + res);

        Map<String, UUID> idByCode = new LinkedHashMap<>();
        Map<String, String> finalByCode = new LinkedHashMap<>();
        for (JsonNode c : created) {
            String orig = c.path("originalCode").asText(null);
            String fin = c.path("finalCode").asText(null);
            String cid = c.path("componentId").asText(null);
            assertNotNull(orig, acRef + "：created 条目缺 originalCode ⇒ 无法配对。item=" + c);
            assertNotNull(cid, acRef + "：created 条目缺 componentId ⇒ 无法配对。item=" + c);
            idByCode.put(orig, UUID.fromString(cid));
            finalByCode.put(orig, fin);
        }
        // 配对完整性：包里每个 code 都要有落点，否则下面的循环会静默少跑几轮。
        for (JsonNode c : b.components()) {
            String code = c.path("code").asText(null);
            assertTrue(idByCode.containsKey(code), acRef + "：包里的组件 " + code
                    + " 在 created 里找不到落点 ⇒ 它的字段一条都没被验证。");
        }
        // 落点必须真在目标目录里（防「建到别处去了」）。
        long inTarget = count("SELECT count(*) FROM component WHERE directory_id='" + target + "'::uuid"
                + " AND id IN (" + uuidList(idByCode.values()) + ")");
        assertEquals(idByCode.size(), inTarget, acRef + "：created 报了 " + idByCode.size()
                + " 个组件，但目标目录 " + target + " 里只找到 " + inTarget + " 个。");
        System.out.println("[" + acRef + "] 往返完成：" + srcDir + " → " + target + "，配对 " + idByCode.size() + " 对");
        return new RoundTrip(srcDir, b, target, idByCode, finalByCode);
    }

    /** 源目录里 code → component.id。 */
    protected Map<String, UUID> componentIdsByCode(UUID dirId) {
        Map<String, UUID> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT code, id::text FROM component WHERE directory_id='" + dirId + "'::uuid ORDER BY code")) {
            out.put(String.valueOf(r[0]), UUID.fromString(String.valueOf(r[1])));
        }
        return out;
    }

    /** 某组件的视图：sql_view_name → 该行的待比对列（::text）。 */
    protected Map<String, Map<String, String>> viewsOf(UUID componentId, List<String> cols) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Object o : col("SELECT id::text FROM component_sql_view WHERE component_id='" + componentId
                + "'::uuid ORDER BY sql_view_name")) {
            UUID vid = UUID.fromString(String.valueOf(o));
            Map<String, String> row = rowText("component_sql_view", cols, vid);
            out.put(row.get("sql_view_name"), row);
        }
        return out;
    }

    /**
     * 按<b>给定组件顺序</b>、组件内按 {@code sql_view_name} 排序，铺平出视图 id 列表。
     *
     * <p>🚨 <b>顺序是本方法的全部意义</b>：它的产物要和另一侧的同名产物<b>按下标配对</b>成归一化 token。
     * 🚫 不能写成 {@code WHERE component_id IN (…) ORDER BY id} —— 那样两侧各自按自己的
     * UUID 大小排序，下标对上的是<b>两个毫不相干的视图</b>，归一化会把 A 的 id 换成 B 的 token，
     * 于是「本该不同」的文本被凑成相同 ⇒ <b>假绿</b>。
     * <p>本方法的对应关系成立，依赖调用方已断言「两侧视图名集合相等」。
     */
    protected List<UUID> viewIdsOf(List<UUID> componentIds) {
        List<UUID> out = new ArrayList<>();
        for (UUID cid : componentIds) {
            for (Object o : col("SELECT id::text FROM component_sql_view WHERE component_id='" + cid
                    + "'::uuid ORDER BY sql_view_name")) {
                out.add(UUID.fromString(String.valueOf(o)));
            }
        }
        return out;
    }

    // ═══════════════════════════ AC-21 · excel_columns.tabKey ═══════════════════════════

    /**
     * {@code tabKey} 里嵌的 UUID 的形状正则。
     *
     * <p>🚨 <b>必须先按形状过滤再转型</b>：{@code tabKey} 里<b>合法地</b>存在
     * {@code idx:<n>} 这种<b>无 id 形态</b>，直接 {@code split_part(...,':',1)::uuid}
     * 会抛 {@code invalid input syntax for type uuid: "idx"} ——
     * 那是 harness 崩溃，会被读成「被测功能出错」。
     *
     * <p>{@code tabKey} 实测有两种带 id 的形状（后端查实）：<b>裸 id</b> 与 <b>{@code <id>:<sortOrder>}</b>。
     * 本正则只锚<b>开头</b>，故两种都能抽出 id 段，且对 {@code idx:2} 返回 {@code NULL}。
     */
    protected static final String TABKEY_UUID_SQL =
            "substring(t->>'tabKey' from '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')";

    /** 某目录下所有组件的 {@code excel_columns} 内嵌 tabKey 抽出的 UUID（{@code idx:n} 这类自动落空被滤掉）。 */
    protected List<String> embeddedTabKeyUuids(UUID dirId) {
        return strCol("SELECT " + TABKEY_UUID_SQL + " AS u"
                + "  FROM component c"
                + "  CROSS JOIN LATERAL jsonb_array_elements(c.excel_columns) col"
                + "  CROSS JOIN LATERAL jsonb_array_elements(col->'tabs') t"
                + " WHERE c.directory_id='" + dirId + "'::uuid"
                + "   AND jsonb_typeof(c.excel_columns)='array'"
                + "   AND " + TABKEY_UUID_SQL + " IS NOT NULL");
    }

    /** 某组件 {@code excel_columns} 里<b>按列顺序</b>取第 0 个 tab 的 {@code tabKey} 原始串（不抽 UUID，原样）。 */
    protected List<String> orderedFirstTabKeys(UUID componentId) {
        return strCol("SELECT col.v->'tabs'->0->>'tabKey'"
                + "  FROM component c"
                + "  CROSS JOIN LATERAL jsonb_array_elements(c.excel_columns) WITH ORDINALITY AS col(v, ord)"
                + " WHERE c.id='" + componentId + "'::uuid ORDER BY col.ord");
    }

    protected List<String> strCol(String sql) {
        List<String> out = new ArrayList<>();
        for (Object o : col(sql)) out.add(o == null ? null : o.toString());
        return out;
    }

    /** 目录下唯一的 EXCEL 组件 id。 */
    protected UUID excelComponentIn(UUID dirId, String acRef) {
        List<Object> ids = col("SELECT id::text FROM component WHERE directory_id='" + dirId
                + "'::uuid AND component_type='EXCEL' ORDER BY code");
        assertFalse(ids.isEmpty(), acRef + " 前置未满足：目录 " + dirId + " 里没有 EXCEL 组件 "
                + "⇒ 「excel_columns 的跨页签引用」这条 AC 没有输入（会空跑）。");
        return UUID.fromString(String.valueOf(ids.get(0)));
    }

    // ═══════════════════════════ id 归一化（AC-6 / AC-14 用）═══════════════════════════

    /**
     * 把「一组对应的 UUID」替换成稳定 token，用于比对<b>跨组件引用被重映射</b>之后的 JSON 文本。
     *
     * <p>🚨 <b>为什么需要它（实查 2026-09-15，{@code cpq_db_test}）</b>：源目录里
     * {@code COMP-2269}（EXCEL 组件）的 {@code excel_columns} 里<b>内嵌了 COMP-2267 / COMP-2268 的 UUID</b>，
     * 而 {@code ComponentExportBundle.Item.id} 的文档用途正是「供导入端重映射跨组件引用」
     * （main-api.md §2.1）。⇒ 导入后这些 id 必然变，{@code excel_columns::text} 必然与源不等。
     *
     * <p>⚠️ <b>本方法不是「让它变绿」的开关</b>：
     * 比对<b>先跑原始文本</b>，把原始差异<b>全部打印</b>；归一化后仍有差异才判失败。
     * 「哪些差异是被归一化解释掉的」逐条进 test-report，交主线裁决 AC-6 是否应把这一类写进白名单。
     */
    protected static String normalizeIds(String text, Map<String, String> idToToken) {
        if (text == null) return null;
        String out = text;
        for (Map.Entry<String, String> e : idToToken.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
            out = out.replace(e.getKey().toUpperCase(), e.getValue());
        }
        return out;
    }

    /**
     * 把「成对的旧 code → 新 code」也纳入归一化。
     *
     * <p>🚨 <b>2026-09-15 首轮实测补入</b>：跨组件引用有<b>两种载体</b>，我初版只覆盖了 id ——
     * {@code COMP-2268(小计)} 的 {@code formulas} 里以
     * {@code "component_code":"COMP-2267"} 引用兄弟组件，导入端把它<b>正确地</b>重映射成了新 code，
     * 于是我的差异引擎把这条<b>正确行为</b>报成了差异。
     * ⇒ 这是<b>本 harness 的口径不全</b>，不是实现缺陷。用户对 AC-6 的裁决是
     * 「成对的旧→新视为同一 token 归一化后比」，code 与 id 在这里完全同源。
     *
     * <p>⚠️ 用<b>带引号</b>的形式 {@code "COMP-2267"} 做替换，不是裸 code ——
     * 裸串替换会让 {@code COMP-226} 命中 {@code COMP-2267} 的前缀（JSON 里 code 恒被引号包住，天然无歧义）。
     */
    protected static void buildCodeTokenMaps(Map<String, String> finalCodeByOrigCode,
                                             Map<String, String> srcMap, Map<String, String> dstMap) {
        int i = 0;
        for (Map.Entry<String, String> e : finalCodeByOrigCode.entrySet()) {
            String token = "@@CODE" + (i++) + "@@";
            srcMap.put("\"" + e.getKey() + "\"", token);
            dstMap.put("\"" + e.getValue() + "\"", token);
        }
    }

    /** 构造 src→token / dst→token 两张表（同一序号 ⇒ 对应关系）。 */
    protected static void buildTokenMaps(List<UUID> srcIds, List<UUID> dstIds, String prefix,
                                         Map<String, String> srcMap, Map<String, String> dstMap) {
        assertEquals(srcIds.size(), dstIds.size(), "归一化 token 表：两侧 id 个数不等（" + srcIds.size()
                + " vs " + dstIds.size() + "）⇒ 对应关系建不起来。");
        for (int i = 0; i < srcIds.size(); i++) {
            srcMap.put(srcIds.get(i).toString(), "@@" + prefix + i + "@@");
            dstMap.put(dstIds.get(i).toString(), "@@" + prefix + i + "@@");
        }
    }

    // ═══════════════════════════ 差异引擎 ═══════════════════════════

    /** 一条差异。{@code null} 与 {@code ""} 是<b>不同</b>的两个值（IS DISTINCT FROM 语义）。 */
    protected record Diff(String pairKey, String table, String column, String srcValue, String dstValue) {
        @Override public String toString() {
            return "  · [" + table + "." + column + "] " + pairKey
                    + "\n      源 = " + show(srcValue)
                    + "\n      新 = " + show(dstValue);
        }
        private static String show(String v) {
            if (v == null) return "<SQL NULL>";
            if (v.isEmpty()) return "<空串 \"\">";
            return v.length() > 400 ? v.substring(0, 400) + "…(共 " + v.length() + " 字符)" : v;
        }
    }

    /**
     * 比对两行的指定列。语义 = {@code IS DISTINCT FROM}（null 与 null 相等；null 与 "" 不等）。
     *
     * @param srcNorm / dstNorm id 归一化表，传 {@code null} 表示不归一化（原始比对）
     */
    protected List<Diff> diffRow(String pairKey, String table, List<String> cols,
                                 Map<String, String> src, Map<String, String> dst,
                                 Map<String, String> srcNorm, Map<String, String> dstNorm) {
        List<Diff> out = new ArrayList<>();
        for (String c : cols) {
            String a = src.get(c);
            String b = dst.get(c);
            if (srcNorm != null) a = normalizeIds(a, srcNorm);
            if (dstNorm != null) b = normalizeIds(b, dstNorm);
            if (!Objects.equals(a, b)) out.add(new Diff(pairKey, table, c, a, b));
        }
        return out;
    }

    /**
     * 🚨 <b>证伪实验（testing.md §4.4）</b>：证明差异引擎<b>真的能报出差异</b>。
     * 首次「0 差异」证明不了引擎接上了 —— 列清单为空、取值全 null、比对没跑，都长成「全部通过」的样子。
     * <p>做法：拿源目录里<b>两个不同的组件</b>互比，必须报出 ≥1 条差异。
     */
    protected void assertDiffEngineIsWired(UUID srcDir, List<String> cols, String acRef) {
        Map<String, UUID> byCode = componentIdsByCode(srcDir);
        assertTrue(byCode.size() >= 2, acRef + " 证伪实验前置未满足：源目录里少于 2 个组件，无法构造「必然不同」的一对。");
        List<UUID> two = new ArrayList<>(byCode.values());
        List<Diff> d = diffRow("证伪对照", "component", cols,
                rowText("component", cols, two.get(0)),
                rowText("component", cols, two.get(1)), null, null);
        assertFalse(d.isEmpty(), acRef + "：🚨 证伪实验失败 —— 拿源目录里两个<b>不同的组件</b>互比，"
                + "差异引擎却报 0 条差异 ⇒ 引擎没接上（列清单为空 / 取值恒 null / 比对没跑）。"
                + "此时本片所有「差异为 0」的结论<b>全部不可信</b>。");
        System.out.println("[" + acRef + "] ✅ 证伪实验通过：两个不同组件互比报出 " + d.size() + " 条差异，差异引擎可用。");
    }

    protected static String fmt(List<Diff> diffs) {
        StringBuilder sb = new StringBuilder();
        for (Diff d : diffs) sb.append('\n').append(d);
        return sb.toString();
    }

    // ═══════════════════════════ 清理（finally 语义）═══════════════════════════

    /**
     * 清掉本轮自建的目录 + 其中的组件 + 视图。
     * <p>🚨 谓词被 {@link #createdDirs}（本轮自建目录 id）<b>限死</b>，
     * 🚫 不出现 TRUNCATE / DROP / 无 WHERE 的 DELETE（CLAUDE.md §3.2）。
     * <p>调试失败现场时用 {@code -Dtask260915.keepFixtures=true} 保留（testing.md §4.5「失败的片保留现场」）。
     */
    @AfterEach
    void cleanupOwnFixtures() {
        if (createdDirs.isEmpty()) return;
        String ids = uuidList(createdDirs);
        System.out.println("[S-A] 本轮自建目录（待回收清单用）：" + createdDirs);

        if (Boolean.getBoolean("task260915.keepFixtures")) {
            System.out.println("[S-A] ⚠️ keepFixtures=true，保留现场不清理。目录 id 见上行。");
            createdDirs.clear();
            return;
        }
        try {
            long comps = count("SELECT count(*) FROM component WHERE directory_id IN (" + ids + ")");
            long views = count("SELECT count(*) FROM component_sql_view WHERE component_id IN"
                    + " (SELECT id FROM component WHERE directory_id IN (" + ids + "))");
            // 影响面量化（CLAUDE.md §3.2 第 1 步）：删之前先说清数字。
            System.out.println("[S-A] 清理命中面：目录 " + createdDirs.size() + " 个 / 组件 " + comps + " 个 / 视图 " + views + " 个");

            long bound = count("SELECT count(*) FROM template_component WHERE component_id IN"
                    + " (SELECT id FROM component WHERE directory_id IN (" + ids + "))");
            if (bound > 0) {
                // 导入端明确「不绑定任何模板」（需求文档 §②）。真绑上了是缺陷，不是清理问题 ⇒ 不越权删，上报。
                System.out.println("[S-A] 🚨 本轮自建组件被 " + bound + " 条 template_component 引用 —— "
                        + "与需求文档 §②「导入不绑定任何模板」矛盾。不清理，保留现场并上报主线。目录=" + createdDirs);
                createdDirs.clear();
                return;
            }
            exec("DELETE FROM component_sql_view WHERE component_id IN"
                    + " (SELECT id FROM component WHERE directory_id IN (" + ids + "))");
            exec("DELETE FROM component WHERE directory_id IN (" + ids + ")");
            exec("DELETE FROM component_directory WHERE id IN (" + ids + ")");
            System.out.println("[S-A] 清理完成。");
        } catch (RuntimeException e) {
            System.out.println("[S-A] ⚠️ 清理失败（保留现场，进 test-report 待回收清单）：" + e);
        } finally {
            createdDirs.clear();
        }
    }
}
