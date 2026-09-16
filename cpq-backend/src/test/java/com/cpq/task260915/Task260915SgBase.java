package com.cpq.task260915;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * task-260915「组件导出/导入往返保真」· <b>分片 S-全局</b> 的公共基座（AC-8 / AC-15）。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260915-组件导出导入往返保真/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md}，分片纪律指回同目录 {@code test.md §3「S-全局」}。
 * <b>🚫 本套用例不读实现代码</b>（不读 {@code cpq-backend/src/main/java/com/cpq/component/**}、
 * {@code cpq-frontend/src/pages/component/**}），只读立项文档、库 schema、库里的配置数据与既有测试代码。
 *
 * <h3>🚦 本片为什么串行殿后（test.md §3）</h3>
 * AC-8 / AC-15 都要<b>保存组件 / 保存取数配置器</b>，而取数配置器保存会触发
 * <b>全量视图重编译</b>（影响全库所有 builder 视图的编译产物），<b>无法还原</b>。
 * ⇒ 必须等 S-A / S-B 全部跑完后单独跑。{@link #printGlobalFootprint} 把这次的全局足迹取证留档。
 *
 * <h3>🚨 环境纪律（CLAUDE.md §3.2「测试也算」）</h3>
 * 本套<b>不出现</b> {@code TRUNCATE} / {@code DROP} / 无 WHERE 的 {@code DELETE} / 清库 / 全局配置重置。
 * 每条 DELETE 的命中面被「本轮自建的目录 id / 组件 id」限死；
 * 🚫 不动 {@code semantic_tab_view} / {@code semantic_node} 存量数据；
 * 🚫 不读不改别片（{@code RT-SA-260915-*} / {@code RT-SB-260915-*}）的数据；
 * 🚫 不许用 {@code DB_NAME=} 把测试打回共享开发库 {@code cpq_db_0724}。
 *
 * <h3>🚨 共库片纪律：禁止全局计数断言（testing.md §4.5）</h3>
 * 本片所有 count/差集断言一律带 {@code directory_id = <自建目录>} 或 {@code id = <自建组件>} 限定。
 * 全库级数字（如「全库 builder_version 非空行数」）只 {@code println} 供报告记录，<b>绝不进断言</b>。
 */
abstract class Task260915SgBase {

    protected static final ObjectMapper M = new ObjectMapper();

    /**
     * 本片专属造数前缀（主线派工指定）。
     * 🚨 清理谓词一律用「本轮自建的完整 id」，<b>绝不</b>出现 {@code LIKE 'RT-%'} 这种会扫到别片的写法 ——
     * 撞命名空间的危害不是脏数据，是<b>互删</b>，症状是随机挂且极像业务回归（testing.md §4.3）。
     */
    protected static final String PREFIX = "RT-SG-260915-";

    /** 本次 JVM 运行的唯一标记 —— 两轮运行不会撞唯一约束，也不会把上轮残留误报成本轮 duplicate key。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /**
     * 「数据源 = 物料BOM」的 builder_config —— 建出来的就是 AC-8 前置要求的
     * 「{@code builder_config->>'tabType' = 'BOM'} 且 {@code component.tab_type IS NULL}」组件。
     *
     * <p>⚠️ {@code tabType} 的<b>存储值是 {@code BOM}</b>，显示名才是「BOM 树」；
     * 传显示名会得到 {@code 400 COMPILE_TABVIEW_NOT_FOUND}（既有测试 {@code Task260904Base} 已踩过并留碑）。
     * <p>⚠️ 请求体是<b>裸 builder_config</b>，🚫 不能包一层 {@code {"builderConfig": {...}}}。
     */
    protected static final String CFG_MATERIAL_BOM = """
            { "tabType": "BOM", "variantKey": "", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"MATERIAL_BOM","sourceColumn":"input_material_no","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
              {"sourceNodeKey":"MATERIAL_BOM","sourceColumn":"component_qty","fieldName":"组成数量"}
            ]}
            """;

    /** 「数据源 = 自制加工费」的 builder_config（非树页签）—— 只用于 AC-8 的<b>阴性对照</b>。 */
    protected static final String CFG_SELF_PROCESS_FEE = """
            { "tabType": "费用类", "variantKey": "SELF_PROCESS_FEE", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"SELF_PROCESS_FEE","sourceColumn":"input_material_no","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
              {"sourceNodeKey":"SELF_PROCESS_FEE","sourceColumn":"value","fieldName":"自制加工费"}
            ]}
            """;

    @Inject
    protected EntityManager em;

    /** 本轮自建的目录 id（清理谓词的唯一依据）。 */
    protected final List<UUID> createdDirs = new ArrayList<>();
    /** 本轮自建的组件 id（含未落目录的）。 */
    protected final List<UUID> createdComponents = new ArrayList<>();

    // ═══════════════════════════ SQL 小工具（只读）═══════════════════════════

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    // ═══════════════════════════ HTTP（管理员会话）═══════════════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /**
     * 🚨 一律用它起手，🚫 不要用裸 {@code RestAssured.given()}：
     * test profile 开了 RBAC（{@code cpq.security.rbac.enabled=true}，实查 application-test.properties:102），
     * 不带 session 一律 401，而 401 会伪装成「端点没做」或「业务校验拒绝」。
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
                + "429=登录限流；423/401=账号被锁或被 E2E 置成 INACTIVE；5xx=Redis session 存储不可用。");
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

    // ═══════════════════════════ 目录 / 组件（自建，私有写）═══════════════════════════

    /** {@code POST /api/cpq/component-directories}：建本片专属目录，名字带 {@link #PREFIX}。 */
    protected UUID createDirectory(String tag) {
        String name = PREFIX + tag + "-" + RUN_ID;
        Response r = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"sortOrder\":0}")
                .post("/api/cpq/component-directories").thenReturn();
        assertReachedBusinessLayer(r, "建目录(" + name + ")");
        assertEquals(200, r.statusCode(), "建目录应 200，实际=" + r.statusCode() + " body=" + r.asString());
        UUID id = UUID.fromString(r.jsonPath().getString("data.id"));
        createdDirs.add(id);
        System.out.println("[SG] 自建目录 " + name + " = " + id);
        return id;
    }

    /** {@code POST /api/cpq/components}：在自建目录里建组件（组件管理里「新建页签组件」）。 */
    protected UUID createComponentInDir(UUID dirId, String label, String fieldsJson, String formulasJson) {
        String name = PREFIX + label + "-" + RUN_ID;
        String body = "{\"name\":\"" + name + "\",\"directoryId\":\"" + dirId + "\""
                + (fieldsJson == null ? "" : ",\"fields\":" + fieldsJson)
                + (formulasJson == null ? "" : ",\"formulas\":" + formulasJson)
                + "}";
        Response r = given().contentType(ContentType.JSON).body(body)
                .post("/api/cpq/components").thenReturn();
        assertReachedBusinessLayer(r, "建组件(" + name + ")");
        assertEquals(200, r.statusCode(), "建组件应 200，实际=" + r.statusCode() + " body=" + r.asString());
        UUID id = UUID.fromString(r.jsonPath().getString("data.id"));
        createdComponents.add(id);
        return id;
    }

    /** {@code PUT /api/cpq/components/{id}}：组件保存（配置期路径，双判据在这条链路上生效）。 */
    protected Response saveComponent(UUID componentId, String body) {
        Response r = given().contentType(ContentType.JSON).body(body)
                .put("/api/cpq/components/" + componentId).thenReturn();
        System.out.println("[SG·component-save] " + componentId + " → " + r.statusCode()
                + (r.statusCode() == 200 ? "" : " " + r.asString()));
        return r;
    }

    /** 一条含 {@code tree_ref}（父取值）的公式，形状取自既有测试 {@code FormulaGateAcTest}。 */
    protected static String treeRefFormulaBody(String formulaName, String targetField) {
        return "{\"formulas\":[{\"name\":\"" + formulaName + "\",\"expression\":["
                + "{\"type\":\"tree_ref\",\"dir\":\"PARENT\",\"agg\":\"NONE\",\"targetExpr\":["
                + "{\"type\":\"field\",\"value\":\"" + targetField + "\"}]}]}]}";
    }

    // ═══════════════════════════ 取数配置器（api.md / 既有测试）═══════════════════════════

    /** {@code PUT /api/cpq/components/{id}/builder} —— 🚨 触发全量视图重编译（本片归 S-全局 的原因）。 */
    protected Response saveBuilder(UUID componentId, String builderConfigJson) {
        Response r = given().contentType(ContentType.JSON).body(builderConfigJson)
                .put("/api/cpq/components/" + componentId + "/builder").thenReturn();
        System.out.println("[SG·builder-save] component=" + componentId + " → " + r.statusCode()
                + (r.statusCode() == 200 ? "" : " " + r.asString()));
        return r;
    }

    protected void saveBuilderOk(UUID componentId, String builderConfigJson, String acRef) {
        Response r = saveBuilder(componentId, builderConfigJson);
        assertReachedBusinessLayer(r, acRef + " saveBuilder");
        assertEquals(200, r.statusCode(), acRef + "：取数配置器保存应成功，实际=" + r.statusCode() + " body=" + r.asString());
    }

    /** {@code GET /api/cpq/components/{id}/builder} —— 「在取数配置器中打开」。 */
    protected Response readBuilder(UUID componentId, String acRef) {
        Response r = given().get("/api/cpq/components/" + componentId + "/builder").thenReturn();
        assertReachedBusinessLayer(r, acRef + " readBuilder");
        assertEquals(200, r.statusCode(), acRef + "：打开配置器应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r;
    }

    /**
     * 从 {@code GET /builder} 的响应里取出 {@code builderConfig} 节点。
     * <p>🚨 取不到 / 取到空对象 ⇒ <b>硬失败</b>，不许退化成「字段缺失就跳过」——
     * 那正是 AC-15① 要证伪的现象（「打开是空白/未配置」）。
     */
    protected JsonNode builderConfigOf(UUID componentId, String acRef) {
        Response r = readBuilder(componentId, acRef);
        JsonNode root;
        try {
            root = M.readTree(r.asString());
        } catch (Exception e) {
            throw new AssertionError(acRef + "：GET /builder 响应不是合法 JSON。body=" + r.asString(), e);
        }
        JsonNode cfg = root.path("builderConfig");
        if (cfg.isMissingNode() || cfg.isNull()) cfg = root.path("data").path("builderConfig");
        assertFalse(cfg.isMissingNode() || cfg.isNull(),
                acRef + "：GET /builder 响应里没有 builderConfig 节点 ⇒ 「配置不丢」的断言会空跑。body=" + r.asString());
        return cfg;
    }

    // ═══════════════════════════ 导出 / 导入（api.md §一、§三）═══════════════════════════

    /** {@code GET /api/cpq/component-directories/{id}/export}（只读，不写库）。 */
    protected String exportRaw(UUID dirId, String acRef) {
        Response r = given().get("/api/cpq/component-directories/{id}/export", dirId).thenReturn();
        assertReachedBusinessLayer(r, acRef + " export");
        assertEquals(200, r.statusCode(), acRef + "：导出应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r.asString();
    }

    /**
     * 把导出响应解析成 bundle 根节点。
     * <p>既有测试实测导出<b>不套 {@code data} 信封</b>（{@code Task0805ExportBindingReportTest} 直接断言
     * {@code bindingReport.unboundCount}），但为免信封漂移导致「断言打在空节点上」的静默假绿，
     * 这里两种都认，且<b>认不出就硬失败</b>。
     */
    protected JsonNode parseBundle(String raw, String acRef) {
        JsonNode root;
        try {
            root = M.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(acRef + "：导出响应不是合法 JSON。body=" + raw, e);
        }
        if (root.path("components").isArray()) return root;
        if (root.path("data").path("components").isArray()) return root.path("data");
        return fail(acRef + "：导出响应里找不到 components 数组（既不在根也不在 data 下）"
                + " ⇒ 后续所有「包里有没有某字段」的断言都会空跑。body=" + raw);
    }

    /** {@code POST /api/cpq/component-directories/{id}/import/commit}。 */
    protected Response importCommit(UUID targetDir, String bundleJson, String conflictPolicy, String acRef) {
        Response r = given().contentType(ContentType.JSON).body(bundleJson)
                .post("/api/cpq/component-directories/{id}/import/commit?conflictPolicy={p}"
                        + "&ignoreMissingDeps=true&ignoreUnboundFormulas=true", targetDir, conflictPolicy)
                .thenReturn();
        assertReachedBusinessLayer(r, acRef + " import/commit");
        System.out.println("[SG·import-commit] dir=" + targetDir + " → " + r.statusCode()
                + (r.statusCode() == 200 ? "" : "\n  body=" + r.asString()));
        return r;
    }

    /**
     * 导入结果里「已创建」的条目数组。AC-8 原文写的是 {@code createdItems}，
     * 既有实现返回的字段名是 {@code created}（{@code Task0805CommitIgnoreUnboundTest} 实测）。
     * 🚨 两个都找不到 ⇒ 硬失败并点名，不许静默当成 0 条。
     */
    protected JsonNode createdItemsOf(Response commitResp, String acRef) {
        JsonNode root;
        try {
            root = M.readTree(commitResp.asString());
        } catch (Exception e) {
            throw new AssertionError(acRef + "：导入响应不是合法 JSON。body=" + commitResp.asString(), e);
        }
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode created = data.path("createdItems");
        if (!created.isArray()) created = data.path("created");
        assertTrue(created.isArray(), acRef + "：导入响应里既没有 createdItems 也没有 created 数组 "
                + "⇒ 「createdItems 含该组件」的断言会空跑。body=" + commitResp.asString());
        return created;
    }

    /** created 数组里是否含某个 code（{@code originalCode} 或 {@code finalCode} 命中即算）。 */
    protected static boolean createdContainsCode(JsonNode created, String code) {
        for (JsonNode n : created) {
            if (code.equals(n.path("originalCode").asText(null))
                    || code.equals(n.path("finalCode").asText(null))) return true;
        }
        return false;
    }

    // ═══════════════════════════ EXPLAIN（AC-15②）═══════════════════════════

    private static final Pattern NAMED_PARAM = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * 把 {@code sql_template} 里的具名占位符绑上「查不到任何东西」的字面量后 {@code EXPLAIN}。
     *
     * <p>用查不到的哨兵值是刻意的：AC-15② 要的是「<b>SQL 仍能正常执行</b>（EXPLAIN 不报错）」，
     * 即<b>语法 + 引用的表/列仍然成立</b>，不是「能查出几行」。EXPLAIN 只做解析与计划，<b>不执行、不写库</b>。
     *
     * <p>🚨 出现未登记的占位符时<b>硬失败并点名</b>，🚫 不许绑 NULL 蒙混过去 ——
     * 绑 NULL 会得到「could not determine data type」，那个错和「视图真的坏了」长得一模一样。
     */
    protected void explainOk(String sqlTemplate, String acRef) {
        assertNotNull(sqlTemplate, acRef + "：sql_template 为 NULL ⇒ EXPLAIN 断言会空跑。");
        assertFalse(sqlTemplate.isBlank(), acRef + "：sql_template 为空串 ⇒ EXPLAIN 断言会空跑。");

        String bound = sqlTemplate
                .replace(":total_material_no", "ARRAY['__RTSG_NO_SUCH_PART__']::text[]")
                .replace(":customerCode", "'__RTSG_NO_SUCH_CUST__'");

        List<String> leftover = new ArrayList<>();
        Matcher m = NAMED_PARAM.matcher(bound);
        while (m.find()) leftover.add(m.group(1));
        assertTrue(leftover.isEmpty(), acRef + "：sql_template 里还有未登记的具名占位符 " + leftover
                + " ⇒ 这是 「harness 缺口」（夹具没跟上契约），不是 AC 结论。"
                + "请在 explainOk() 里补一条绑定后重跑。SQL=\n" + sqlTemplate);

        try {
            em.createNativeQuery("EXPLAIN " + bound).getResultList();
        } catch (Exception e) {
            throw new AssertionError(acRef + "：保存后 sql_template 无法通过 EXPLAIN（AC-15② 不达成）。"
                    + "\n  异常=" + e.getClass().getSimpleName() + ": " + rootMessage(e)
                    + "\n  SQL=\n" + bound, e);
        }
        System.out.println("[" + acRef + "] ✅ EXPLAIN 通过（" + bound.length() + " 字符）");
    }

    protected static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage();
    }

    // ═══════════════════════ 全局副作用取证（S-全局 的交代）═══════════════════════

    /**
     * 「不属于本片组件」的 {@code component_sql_view} 编译产物指纹。
     * <p>🚫 <b>只打印，不进断言</b>：全量重编译是本功能的既有行为，它变了不代表缺陷；
     * 而把它写成断言就会变成 testing.md §4.5 点名的「全局计数断言」。
     * 它的用途是<b>让主线看得见这次跑测试到底动了多大面</b>（test.md §3「会动的全局状态」）。
     */
    protected String foreignViewFingerprint() {
        String notMine = createdComponents.isEmpty() ? "TRUE"
                : "component_id NOT IN (" + createdComponents.stream()
                        .map(u -> "'" + u + "'").reduce((a, b) -> a + "," + b).orElse("NULL") + ")";
        String s = scalar("SELECT count(*)::text || '|' || coalesce(md5(string_agg("
                + "sql_view_name || '§' || coalesce(sql_template,'') || '§' || coalesce(builder_version::text,'')"
                + ", '¶' ORDER BY id)),'-') FROM component_sql_view WHERE " + notMine);
        return s == null ? "(null)" : s;
    }

    /** {@code semantic_tab_view} / {@code semantic_node} 基线指纹 —— 本片<b>不得</b>动它们。 */
    protected String semanticBaselineFingerprint() {
        String s = scalar("SELECT (SELECT count(*) FROM semantic_tab_view)::text || '/' "
                + "|| (SELECT count(*) FROM semantic_node)::text || '|' "
                + "|| coalesce(md5((SELECT string_agg(dialect||tab_type||coalesce(variant_key,'')||status, ',' ORDER BY id) "
                + "FROM semantic_tab_view)),'-')");
        return s == null ? "(null)" : s;
    }

    protected void printGlobalFootprint(String phase, String viewFp, String semFp) {
        System.out.println("[SG·全局足迹·" + phase + "] 他人视图编译产物指纹=" + viewFp
                + " | 语义图基线指纹=" + semFp);
    }

    // ═══════════════════════════ 还原（@AfterEach = finally）═══════════════════════════

    /**
     * 还原本片写进共享库的一切<b>可还原部分</b>。
     * <p>🚫 每条 DELETE 都带收敛谓词（本轮自建的目录 id / 组件 id），不存在无 WHERE 的删除，
     * 不存在 {@code TRUNCATE} / {@code DROP}。
     * <p>🚨 <b>视图重编译无法还原</b> —— 这正是本片必须串行殿后的原因（test.md §3）。
     * 这里把「不可还原的部分」显式打印出来，交主线随 test-report.md 上报，🚫 不许默默吞掉。
     */
    @AfterEach
    void cleanupSg() {
        List<String> errors = new ArrayList<>();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID dir : createdDirs) {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                            + "(SELECT id FROM component WHERE directory_id = :dir)")
                            .setParameter("dir", dir).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                            .setParameter("dir", dir).executeUpdate();
                }
                for (UUID c : createdComponents) {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                            .setParameter("c", c).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE id = :c")
                            .setParameter("c", c).executeUpdate();
                }
                for (UUID dir : createdDirs) {
                    em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                            .setParameter("id", dir).executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            errors.add(rootMessage(e));
        }

        // 残留自检：只查自己那批，🚫 不做全库计数
        for (UUID dir : createdDirs) {
            long left = count("SELECT count(*) FROM component WHERE directory_id = '" + dir + "'");
            if (left > 0) errors.add("目录 " + dir + " 仍残留 " + left + " 个组件");
        }
        System.out.println("[SG·还原] 目录=" + createdDirs + " 组件=" + createdComponents
                + (errors.isEmpty() ? " ✅ 已清空" : " ⚠️ 清理异常：" + errors));
        System.out.println("[SG·不可还原] 本片调用过 PUT /builder ⇒ 全量视图重编译的产物无法回退；"
                + "按 test.md §3 由主线随 test-report.md 上报，本用例不尝试「还原」它。");

        createdComponents.clear();
        createdDirs.clear();
    }
}
