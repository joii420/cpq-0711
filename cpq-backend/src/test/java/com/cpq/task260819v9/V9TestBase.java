package com.cpq.task260819v9;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * task-260819 v9「三数据集范围替换」验收用例的公共基座。
 *
 * <p>断言一律回到 {@code 需求文档.md §9.4} 的 AC 原文（AC-101 ~ AC-126）。
 * 本类<b>不 import 任何 {@code src/main} 下的类</b>，也不含业务判据 —— 用例从 AC 派生，不从实现派生。
 *
 * <h3>🚨 共享库红线（CLAUDE.md §3.2）</h3>
 * {@code mvnw test} 走 {@code test} profile，而 {@code application-test.properties:24} 的默认库就是
 * <b>{@code cpq_db_0724} —— 共享开发库本身</b>。因此：
 * <ul>
 *   <li>🚫 全套用例<b>不含</b> {@code TRUNCATE} / {@code DROP} / 无 {@code WHERE} 的 {@code DELETE} / {@code UPDATE}。</li>
 *   <li>✅ 自建数据一律带 {@link #TAG} 前缀，清理面被 <b>正向条件</b>限死（{@code WHERE ... LIKE 'V9T-%'}）。</li>
 *   <li>🚫 别的会话的数据一个字节都不动 —— 尤其 {@code ds_quote_material} 现存 42 行（「产品管理优化」会话灌的）。</li>
 *   <li>🚫 {@code element} / {@code process_master} / {@code material_recipe} / {@code customer} 等共享主数据只读。</li>
 * </ul>
 *
 * <h3>⚠️ 三条写作纪律（需求文档 §9.4 抬头 · D-72 / D-74 教训）</h3>
 * <ol>
 *   <li><b>任何行数基准必须紧邻被测操作重取</b>，并写明「哪个库、什么查询」。本类的 {@link #scalarLong}
 *       就是取基准的唯一入口，每处调用点都在断言的同一个方法体里。🚫 不许照抄文档里的 5 / 14 / 42。</li>
 *   <li>🚫 <b>禁止写死版本号。</b>{@code ds_cost_basic_material_bom} 的 {@code 3120014539} 现为 v7
 *       （2026-09-03 实查，比 test.md 记的 v5 又涨了 2），是别的会话跑升版实验留下的，<b>会漂移</b>。
 *       一律断言不变量或运行期重取。</li>
 *   <li>🚨 <b>{@code skip != pass}。</b>本套用例<b>一律不用 {@code Assumptions.assumeTrue}</b>。
 *       环境前置未就绪时<b>硬失败</b>，失败信息里显式写「这是环境前置未就绪，不是产品缺陷；本条 AC 未验证」，
 *       让它在报告里以「红」的身份出现，而不是以「跳过」的身份被误计入通过。</li>
 * </ol>
 *
 * <h3>⚠️ RBAC</h3>
 * {@code src/test/resources/application.properties:5} 的 {@code rbac.enabled=false} 被
 * {@code application-test.properties:86} 的 {@code true} 覆盖 ⇒ 不带 session 的请求恒 401。
 * 全部请求走 {@link #session()}；登录带 Redis 限流（30 次/分/IP），所以 session <b>静态缓存</b>，
 * 否则一轮跑下来会自己把自己限流，症状是「从某条起全部 401」，长得像鉴权坏了。
 */
public abstract class V9TestBase {

    /** 🚨 本套用例唯一的自建数据前缀。清理面靠它限死，别改。 */
    protected static final String TAG = "V9T-";

    /**
     * 🔴 <b>契约缺口，需主线裁决</b>：{@code api.md} 停在 2026-08-21，<b>没有按 v9 更新</b> ——
     * 里面既没有「数据集 / 方言」字段的定义，也没有 D-86 版本列表专用端点的定义。
     * D-77 裁决是「{@code dialect} 扩到三值」，B-22/D-59 明写后端读请求体的 {@code cfg.dialect}，
     * 因此本套用例按 {@code "dialect"} 拼请求。若后端实际用了别的键名（如 {@code dataset}），
     * <b>改这一个常量即可</b>，不必逐个用例改。
     */
    protected static final String DIALECT_FIELD = "dialect";

    protected static final String QUOTE = "QUOTE";
    protected static final String COST_BASIC = "COST_BASIC";
    protected static final String COST_DETAIL = "COST_DETAIL";

    /** §9.2「不进图」的 4 张报价侧表：年降 3 张（N-18）+ 客户料号（N-19）。 */
    protected static final List<String> NOT_IN_GRAPH = List.of(
            "ds_quote_assembly_fee_annual", "ds_quote_incoming_annual", "ds_quote_annual_discount",
            "ds_quote_customer_part");

    /** AC-102 点名的 8 张 V6 表。 */
    protected static final List<String> V6_TABLES = List.of(
            "material_bom_item", "element_bom_item", "unit_price", "annual_discount",
            "capacity", "plating_scheme", "material_customer_map", "material_master");

    @Inject
    protected EntityManager em;

    private static String cachedSession;

    // ═══════════════════════ 登录 ═══════════════════════

    protected String session() {
        if (cachedSession == null) {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                    .when().post("/api/cpq/auth/login");
            if (r.statusCode() != 200) {
                throw new AssertionError("登录失败 admin → HTTP " + r.statusCode() + "\n  响应体：" + r.asString()
                        + "\n  ⚠️ 依次排查：① Redis 登录限流（30 次/分/IP，本类已静态缓存以避免自撞）；"
                        + "② admin 被 E2E 置成 INACTIVE（历史事故，见 RECORD）；③ locked_until。"
                        + "\n  ⚠️ 这是环境前置未就绪，不是产品缺陷。");
            }
            cachedSession = r.cookie("CPQ_SESSION");
            assertNotNull(cachedSession, "登录 200 但没拿到 CPQ_SESSION cookie");
        }
        return cachedSession;
    }

    // ═══════════════════════ 只读 SQL 助手 ═══════════════════════

    protected long scalarLong(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        bind(q, params);
        return ((Number) q.getSingleResult()).longValue();
    }

    protected String scalarStr(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        bind(q, params);
        List<?> l = q.getResultList();
        return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
    }

    @SuppressWarnings("unchecked")
    protected List<String> strList(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        bind(q, params);
        List<Object> raw = q.getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rowList(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        bind(q, params);
        return (List<Object[]>) q.getResultList();
    }

    private void bind(jakarta.persistence.Query q, Object[] params) {
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
    }

    /** 写操作统一入口 —— 只允许带正向条件的、作用于自建数据的语句。 */
    protected void inTx(Runnable r) {
        QuarkusTransaction.requiringNew().run(r);
    }

    protected int exec(String sql, Object... params) {
        int[] n = new int[1];
        inTx(() -> {
            var q = em.createNativeQuery(sql);
            bind(q, params);
            n[0] = q.executeUpdate();
        });
        return n[0];
    }

    // ═══════════════════════ 数据集元数据（一律从库里发现，🚫 不写死表名清单） ═══════════════════════

    /** 某数据集前缀下的全部主表（排除 {@code _history}）。 */
    protected List<String> dsMainTables(String prefix) {
        return strList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_type='BASE TABLE' "
                + "AND table_name LIKE ?1 AND table_name NOT LIKE '%\\_history' ORDER BY 1", prefix + "%");
    }

    /** 全部 45 张 {@code ds_*} 主表。 */
    protected List<String> allDsMainTables() {
        return dsMainTables("ds_");
    }

    /** 某主表的实际列集合（{@code information_schema} 权威）。表/视图都适用。 */
    protected List<String> columnsOf(String relation) {
        return strList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 ORDER BY 1", relation);
    }

    /** 带版本（有 {@code _history} 兄弟表）的核价侧主表 —— S-31 要建全版本视图的那一批。 */
    protected List<String> versionedCostMainTables() {
        return strList("SELECT t.table_name FROM information_schema.tables t "
                + "JOIN information_schema.tables h ON h.table_schema='public' "
                + "  AND h.table_name = t.table_name || '_history' "
                + "WHERE t.table_schema='public' AND t.table_type='BASE TABLE' "
                + "AND t.table_name LIKE 'ds\\_cost\\_%' AND t.table_name NOT LIKE '%\\_history' ORDER BY 1");
    }

    /** {@code v_<主表>_all} → {@code <主表>}；非该形态原样返回。 */
    protected static String normalizeToMainTable(String physicalTable) {
        if (physicalTable == null) {
            return null;
        }
        if (physicalTable.startsWith("v_") && physicalTable.endsWith("_all")) {
            return physicalTable.substring(2, physicalTable.length() - 4);
        }
        return physicalTable;
    }

    // ═══════════════════════ 仓库路径 / md5 ═══════════════════════

    /** 由 cwd（通常是 {@code cpq-backend/}）向上找到含 {@code dev-docs} 的仓库根。 */
    protected static Path repoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("dev-docs"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new AssertionError("找不到仓库根（向上 6 层都没有 dev-docs），cwd=" + Path.of("").toAbsolutePath()
                + "\n  ⚠️ 环境前置未就绪，不是产品缺陷。请在 worktree 的 cpq-backend/ 下跑 ./mvnw test。");
    }

    protected static Path taskDir() {
        return repoRoot().resolve("dev-docs").resolve("task-260819-取数配置器");
    }

    protected static Path dataset902Dir() {
        return repoRoot().resolve("dev-docs").resolve("task-260902-报价与核价建表与导入方案新规范");
    }

    protected static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected static String md5(String s) {
        return md5(s.getBytes(StandardCharsets.UTF_8));
    }

    // ═══════════════════════ builder 端点薄封装（按 api.md §2 拼，不 import 实现类） ═══════════════════════

    protected Response compile(String componentId, Map<String, Object> config) {
        return RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON).body(config)
                .when().post("/api/cpq/components/{cid}/builder/compile", componentId);
    }

    protected Response preview(String componentId, Map<String, Object> configPlusPreviewArgs) {
        return RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON).body(configPlusPreviewArgs)
                .when().post("/api/cpq/components/{cid}/builder/preview", componentId);
    }

    protected Response save(String componentId, Map<String, Object> configPlusConfirm) {
        return RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON).body(configPlusConfirm)
                .when().put("/api/cpq/components/{cid}/builder", componentId);
    }

    /** 组装一个「裸 builder_config」请求体（api.md §1.5③ / §2.4：一律扁平，🚫 不套 {@code builderConfig} 外层）。 */
    protected Map<String, Object> config(String dialect, String tabType, String variantKey,
                                         List<Map<String, Object>> columns) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("builderVersion", 1);
        cfg.put(DIALECT_FIELD, dialect);
        cfg.put("tabType", tabType);
        cfg.put("variantKey", variantKey);
        cfg.put("columns", columns);
        cfg.put("priceStrategy", null);
        return cfg;
    }

    protected Map<String, Object> column(String sourceNodeKey, String sourceColumn, String fieldName) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sourceNodeKey", sourceNodeKey);
        c.put("sourceColumn", sourceColumn);
        c.put("fieldName", fieldName);
        c.put("fieldType", "BASIC_DATA");
        c.put("isAmount", false);
        c.put("inSubtotal", false);
        return c;
    }

    // ═══════════════════════ 语义图发现（🚫 不写死节点 key —— 种子由 B-42 机器生成，命名不由我定） ═══════════════════════

    /** 取某方言某页签类型的页签视图 id；不存在返回 null（调用方负责硬失败并说明是环境前置）。 */
    protected String tabViewId(String dialect, String tabType, String variantKey) {
        String sql = "SELECT id::text FROM semantic_tab_view WHERE dialect=?1 AND tab_type=?2 AND "
                + (variantKey == null ? "variant_key IS NULL" : "variant_key=?3");
        return variantKey == null ? scalarStr(sql, dialect, tabType) : scalarStr(sql, dialect, tabType, variantKey);
    }

    /**
     * 取某方言某页签类型的锚点节点 {@code (node_key, physical_table, short_name, node_id)}；不存在返回 null。
     *
     * <p>⚠️ 页签类型「BOM 树」在服务端存的就是 {@code 'BOM 树'}（带空格），前端本地常量是 {@code 'BOM'} ——
     * 这是 {@code api.md v9-3} 登记的存量跨端不一致，本任务不改。传参一律用服务端口径。
     */
    protected Object[] anchorNode(String dialect, String tabType) {
        List<Object[]> r = rowList(
                "SELECT n.node_key, n.physical_table, n.short_name, n.id::text FROM semantic_tab_view v "
                        + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                        + "WHERE v.dialect=?1 AND v.tab_type=?2 AND v.status='ACTIVE' LIMIT 1", dialect, tabType);
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 取某节点的一列 {@code (db_column, display_name)}；不存在返回 null。
     *
     * <p>🚨 <b>必须按 node id 取，不能按 node_key 取</b> —— {@code api.md v9-2} 实测：
     * V410 种子里 {@code node_key} <b>跨方言重名</b>（{@code MATERIAL} / {@code MATERIAL_BOM} /
     * {@code ELEMENT_BOM} 等 10 个键在三套数据集里各有一份）。按 key 查会跨方言串到别人那一行，
     * 拿到的列可能根本不属于当前方言的表 —— 那样断言就是打在错的对象上，而且<b>不报错</b>。
     */
    protected Object[] someColumnById(String nodeId) {
        List<Object[]> r = rowList(
                "SELECT c.db_column, c.display_name FROM semantic_node_column c "
                        + "WHERE c.node_id = CAST(?1 AS uuid) AND c.status='ACTIVE' "
                        + "ORDER BY c.sort_order NULLS LAST, c.db_column LIMIT 1", nodeId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 取某方言下 {@code node_key} 对应节点的 {@code short_name}（同样必须带方言，见 {@link #someColumnById}）。 */
    protected String shortNameOf(String dialect, String nodeKey) {
        return scalarStr("SELECT short_name FROM semantic_node WHERE dialect=?1 AND node_key=?2 "
                + "AND status='ACTIVE' LIMIT 1", dialect, nodeKey);
    }

    /** 环境前置未就绪时的统一硬失败信息 —— 明确它不是产品缺陷，也不许被读成「跳过 = 通过」。 */
    protected static String notReady(String ac, String what, String owner) {
        return "[" + ac + "] 环境前置未就绪：" + what + "（应由 " + owner + " 交付）。"
                + "\n  ⚠️ 这是前置未就绪，不是产品缺陷；本条 AC 判定为『未验证』。"
                + "\n  🚫 不得因为『看起来只是没做完』就把它当成通过 —— skip != pass（需求文档 §9.4 纪律③）。";
    }
}
