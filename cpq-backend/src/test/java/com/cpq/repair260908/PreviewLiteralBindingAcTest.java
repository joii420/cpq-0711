package com.cpq.repair260908;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · 分片 <b>S-2</b> —— <b>AC-5 反面 ①</b>：{@code POST /api/cpq/builder/preview} 不被 B-2 误伤。
 *
 * <h3>AC 原文依据</h3>
 * {@code 问题说明.md §⑥ AC-5}：「…同时验证 {@code /preview}、{@code QuoteViewValidationService}、
 * {@code CostingTreeSqlValidator} 三条路径<b>仍正常</b>（它们各自做字面量替换）」。
 * <p>{@code api.md §3}：{@code POST /api/cpq/builder/preview} 走 {@code BuilderService.bindLiterals()}
 * 把 {@code :customerCode} 替换成<b>字面量</b>，<b>不经</b> {@code SqlViewExecutor.rewriteNamedParams}
 * ⇒ B-2 的硬阻断<b>不应</b>影响它。
 *
 * <h3>为什么打 HTTP 端点而不是打 BuilderService</h3>
 * 「不被误伤」这件事的风险点恰恰在<b>装配</b>：B-2 若把阻断放进了某个被 preview 也走到的公共位置，
 * 只测 {@code BuilderService} 的某个零件会照样全绿（{@code PreviewPlaceholderSelfCheckTest}
 * 的注释里记过同型教训：「只测零件的话，『preview() 忘了调宏展开』这种装配缺陷会全绿通过」）。
 * ⇒ 打真实端点，走全套 CDI 接线。
 *
 * <h3>🚨 量具自证（{@code test.md §3}）</h3>
 * 本条断言的是「200」而不是「400」，但同样有量具问题：<b>200 也可能是空跑</b>
 * （不传 {@code partNo} 时预览恒 0 行 —— {@code task-260907} 2026-09-07 实测）。
 * ⇒ 本类三重守卫：① 编译产物必须<b>确实含</b> {@code :customerCode}（否则这条反向断言无的放矢）；
 * ② preview 必须 <b>200</b>；③ 返回的 {@code rows} 必须<b>非空且含我自己造的那一行</b>。
 *
 * <h3>🚨 证伪设计（{@code test.md §4}）</h3>
 * 把 B-2 的硬阻断做成「不区分执行路径的全局阻断」（即误伤 preview），
 * {@link #t11_previewWithCustomerCode_still200AndReturnsMyRow} 会在 {@code assertEquals(200, ...)} 处变红。
 * <p>反向的空跑守卫也可被证伪：把 {@code :customerCode} 的前置断言删掉，本条就退化成
 * 「一个不含客户谓词的预览也能 200」—— 那与 AC-5 无关。故该前置断言<b>不许放宽</b>。
 *
 * <h3>🚨 共库纪律</h3>
 * 造数带分片前缀 {@link #FX}；只断言自己造的那一行；🚫 无全局计数断言；
 * 清理在 {@code @AfterEach}（finally 语义），命中面被本轮前缀 / 自建 id 限死；
 * 🚫 无 TRUNCATE / DROP / 无 WHERE 的删除 / 清库 / 全局状态重置
 * （⚠️ 刻意<b>不</b>执行「解锁 admin」之类的 UPDATE —— 那是共享库全局状态）。
 *
 * <h3>本类不读实现代码</h3>
 * 断言来源只有 {@code 问题说明.md §⑥}、{@code api.md §3} 与既有测试代码。
 */
@QuarkusTest
class PreviewLiteralBindingAcTest {

    private static final String PREFIX = "R260908S2_";
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    /** 料号前缀，15 字符（V6/ds 表历史上有 varchar(20) 列宽约束）。 */
    private static final String FX = "R260908S2" + RUN_ID;
    private static final String MY_PART = FX + "P";
    private static final String MY_CUST = "R260908S2C" + RUN_ID;

    /** AC-5 反面① 的靶表 —— 就是缺陷① 的现场表（{@code 问题说明.md §②} 复现 SQL）。 */
    private static final String ANCHOR_TABLE = "ds_quote_material";

    @Inject EntityManager em;

    private final List<UUID> createdComponentIds = new ArrayList<>();
    private boolean fixtureWritten = false;

    // ═══════════════════════════ 清理（finally 语义） ═══════════════════════════

    @AfterEach
    void cleanup() {
        List<String> errors = new ArrayList<>();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (fixtureWritten) {
                    em.createNativeQuery("DELETE FROM " + ANCHOR_TABLE + " WHERE material_no LIKE :p")
                            .setParameter("p", FX + "%").executeUpdate();
                }
                for (UUID cid : createdComponentIds) {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :id")
                            .setParameter("id", cid).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE id = :id")
                            .setParameter("id", cid).executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            errors.add("清理失败: " + e);
        }
        if (!errors.isEmpty()) System.out.println("[S-2·cleanup] ⚠️ " + errors);

        long mats = count("SELECT count(*) FROM " + ANCHOR_TABLE + " WHERE material_no LIKE '" + FX + "%'");
        long comps = count("SELECT count(*) FROM component WHERE name LIKE '" + PREFIX + "%" + RUN_ID + "%'");
        System.out.println("[S-2·residue] " + ANCHOR_TABLE + "=" + mats + " component=" + comps);
        assertEquals(0L, mats + comps, "还原自检：本轮夹具仍有残留（" + ANCHOR_TABLE + "=" + mats
                + " component=" + comps + "）—— 共享 dev 库必须清干净");

        createdComponentIds.clear();
        fixtureWritten = false;
    }

    // ═══════════════════════════ AC-5 反面 ① ═══════════════════════════

    /**
     * <b>AC-5 反面 ①</b>：编译产物含 {@code :customerCode} 时，
     * {@code POST /builder/preview} 传上 {@code customerCode} 仍 <b>200</b>，且返回<b>我自己那一行</b>。
     *
     * <p>四步，前三步都是量具自证，🚫 一步都不许省：
     * <ol>
     *   <li>找到 QUOTE 侧锚点为 {@value #ANCHOR_TABLE} 的 ACTIVE 坐标（该表有 {@code customer_no} 列
     *       ⇒ B-1 会给它补客户谓词）；</li>
     *   <li>建自己的组件 + 自己的物料行（带分片前缀）；</li>
     *   <li>编译一次，<b>断言产物确实含 {@code :customerCode}</b> ——
     *       不含就说明这条反向断言无的放矢（B-1 未落地 / 坐标选错），此时结论既不是通过也不是不通过；</li>
     *   <li>preview 传 {@code partNo} + {@code customerCode} ⇒ 断言 200 且 rows 里能找到我的料号。</li>
     * </ol>
     */
    @Test
    void t11_previewWithCustomerCode_still200AndReturnsMyRow() {
        // ── 1. 坐标（🚫 不写死「主件」—— 坐标值是配置数据，会漂移） ──────────────────
        List<Object[]> coords = rows(
                "SELECT v.tab_type, coalesce(v.variant_key,''), n.node_key "
              + "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
              + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND n.physical_table='" + ANCHOR_TABLE + "' "
              + "ORDER BY v.tab_type, v.variant_key LIMIT 1");
        assertFalse(coords.isEmpty(), "前置未满足：QUOTE 方言下找不到锚点为 " + ANCHOR_TABLE
                + " 的 ACTIVE semantic_tab_view ⇒ 本条反向断言无处落脚。"
                + "这是地基/配置问题，🚫 不是 AC-5 的结论。");
        String tabType = String.valueOf(coords.get(0)[0]);
        String variantKey = String.valueOf(coords.get(0)[1]);
        String nodeKey = String.valueOf(coords.get(0)[2]);

        assertTrue(physicalColumns(ANCHOR_TABLE).contains("customer_no"),
                "前置未满足：" + ANCHOR_TABLE + " 没有 customer_no 列 ⇒ B-1 的客户谓词按列存在性触发，"
                + "本条反向断言不会遇到 :customerCode，等于空跑。");

        String partNoCol = scalar("SELECT db_column FROM semantic_node_column c "
                + "JOIN semantic_node n ON n.id = c.node_id "
                + "WHERE n.node_key = '" + nodeKey + "' AND n.dialect='QUOTE' AND c.status='ACTIVE' "
                + "AND 'PART_NO' = ANY(c.roles) ORDER BY c.db_column LIMIT 1");
        assertNotNull(partNoCol, "前置未满足：节点 " + nodeKey + " 上找不到 PART_NO 角色的列 ⇒ 建不出可预览的配置");
        System.out.println("[S-2·AC-5①] 坐标 QUOTE/" + tabType + "/" + variantKey
                + " node=" + nodeKey + " partNoCol=" + partNoCol);

        // ── 2. 夹具（分片私有） ────────────────────────────────────────────────
        assertEquals(0L, count("SELECT count(*) FROM " + ANCHOR_TABLE + " WHERE material_no LIKE '" + FX + "%'"),
                "构造自检：本轮料号前缀 " + FX + " 已存在 ⇒ 「我的行出现了」会被残留冒充");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO " + ANCHOR_TABLE + " (material_no, material_name, customer_no, source, created_at) "
              + "VALUES (:mn, :nm, :cn, 'TEST-R260908S2', now())")
                .setParameter("mn", MY_PART).setParameter("nm", PREFIX + "料-" + MY_PART)
                .setParameter("cn", MY_CUST).executeUpdate());
        fixtureWritten = true;
        assertEquals(1L, count("SELECT count(*) FROM " + ANCHOR_TABLE + " WHERE material_no = '" + MY_PART
                        + "' AND customer_no = '" + MY_CUST + "'"),
                "夹具自检：我的物料行没落库 ⇒ 后面「rows 里能找到我的料号」会红成看似产品缺陷");

        UUID componentId = createBlankComponent("AC5PREVIEW");
        String cfg = "{\"dialect\":\"QUOTE\",\"tabType\":\"" + tabType + "\",\"variantKey\":\"" + variantKey
                + "\",\"columns\":[{\"sourceNodeKey\":\"" + nodeKey + "\",\"sourceColumn\":\"" + partNoCol
                + "\",\"fieldName\":\"_销售料号\",\"isRowKey\":true,\"isPartNo\":true}]}";

        // ── 3. 编译：产物必须确实含 :customerCode（否则本条反向断言无的放矢） ──────────
        Response comp = given().contentType(ContentType.JSON).body(cfg)
                .post("/api/cpq/components/" + componentId + "/builder/compile").thenReturn();
        assertReachedBusinessLayer(comp, "compile");
        assertEquals(200, comp.statusCode(), "AC-5 反面①：编译应 200，实际=" + comp.statusCode()
                + "\n  请求=" + cfg + "\n  body=" + comp.asString());
        String sql = comp.jsonPath().getString("sql");
        assertNotNull(sql, "编译产物取不到 sql ⇒ 断言会空跑。body=" + comp.asString());
        System.out.println("---- AC-5 反面① 编译产物 ----\n" + sql);
        assertTrue(sql.contains(":customerCode"),
                "前置未满足：编译产物<b>不含</b> :customerCode ⇒ 「preview 不被 :customerCode 的硬阻断误伤」"
                + "这条断言没有靶子，200 只是空跑。可能原因：B-1 未落地 / 坐标选错。"
                + "🚫 此时不许把 AC-5 反面① 记成通过。产物=\n" + sql);

        // ── 4. preview：传全参 ⇒ 200 且能找到我的料号 ────────────────────────────
        String body = "{\"partNo\":\"" + MY_PART + "\",\"customerCode\":\"" + MY_CUST + "\","
                + cfg.substring(cfg.indexOf('{') + 1);
        Response prev = given().contentType(ContentType.JSON).body(body)
                .post("/api/cpq/components/" + componentId + "/builder/preview").thenReturn();
        assertReachedBusinessLayer(prev, "preview");

        System.out.println("---- AC-5 反面① preview 响应 ----\n  status=" + prev.statusCode()
                + "\n  body=" + abbreviate(prev.asString()));
        assertEquals(200, prev.statusCode(), "AC-5 反面①：/builder/preview 走 BuilderService.bindLiterals() "
                + "做字面量替换，不经 SqlViewExecutor.rewriteNamedParams ⇒ B-2 的硬阻断不应影响它（api.md §3 ①）。"
                + "实际 status=" + prev.statusCode() + " body=" + prev.asString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewRows = (List<Map<String, Object>>) prev.jsonPath().get("rows");
        assertNotNull(previewRows, "AC-5 反面①：响应缺 rows 字段 ⇒ 无法判断是真跑通还是空跑。body=" + prev.asString());
        // 🚨 空跑守卫：不传 partNo 时预览恒 0 行（task-260907 实测）；0 行的 200 不算「仍正常」
        assertFalse(previewRows.isEmpty(), "AC-5 反面①：preview 返 200 但 0 行 ⇒ 无法区分"
                + "「路径正常」与「客户谓词被绑成 NULL 后恒假」。这不是通过。body=" + prev.asString());

        boolean found = previewRows.stream()
                .anyMatch(r -> r.values().stream().anyMatch(v -> MY_PART.equals(String.valueOf(v))));
        assertTrue(found, "AC-5 反面①：preview 有行但找不到我造的料号 " + MY_PART
                + " ⇒ 取到的是别人的数据，不能作为本片的证据。rows=" + previewRows);

        System.out.println("[S-2] ✅ AC-5 反面① 通过：编译产物含 :customerCode，preview 传全参 200 且含 "
                + MY_PART + "（共 " + previewRows.size() + " 行）");

        // 诊断（🚫 不作断言）：不传 customerCode 时 /preview 自己会报「缺变量」——
        // 那是 preview 端点的既有行为（task-260907 已记录），与 B-2 的硬阻断是两回事。
        String noCust = "{\"partNo\":\"" + MY_PART + "\"," + cfg.substring(cfg.indexOf('{') + 1);
        Response bare = given().contentType(ContentType.JSON).body(noCust)
                .post("/api/cpq/components/" + componentId + "/builder/preview").thenReturn();
        System.out.println("[S-2·诊断] preview 不传 customerCode ⇒ status=" + bare.statusCode()
                + " body=" + abbreviate(bare.asString())
                + "\n  ↑ 仅供报告：这是 /preview 自己的缺变量提示，不是 B-2 的硬阻断，本类不据此断言。");
    }

    // ═══════════════════════════ HTTP / SQL 小工具 ═══════════════════════════

    private static Map<String, String> SESSION;

    /**
     * test profile 里 {@code cpq.security.rbac.enabled=false}（见 {@code src/test/resources/application.properties}），
     * 正常情况下裸 {@code given()} 即可。仅当真的被 401 挡住时才走一次登录。
     * <p>🚫 刻意<b>不</b>执行「解锁 admin / 改 admin 状态」之类的 UPDATE —— 那是共享库全局状态
     * （{@code testing.md §4.3}），本片是私有写片，不许碰。
     */
    private RequestSpecification given() {
        return SESSION == null ? RestAssured.given() : RestAssured.given().cookies(SESSION);
    }

    /** 🚨 假绿守卫：鉴权/路由把请求挡在业务层之外时，「断言非 200」会照样通过。 */
    private void assertReachedBusinessLayer(Response res, String when) {
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            login();   // 只在真被挡住时才尝试，且失败即硬报「基础设施故障」
            throw new AssertionError(when + "：请求被鉴权拦下（" + res.statusCode()
                    + "）—— 这是 harness 故障不是 AC 结论。已尝试建立会话，请重跑。body=" + res.asString());
        }
        assertFalse(res.statusCode() == 404 && res.asString().contains("RESTEASY"),
                when + "：端点 404 ⇒ 路径与 api.md 不一致或端点未实现。body=" + res.asString());
        assertFalse(res.statusCode() == 405, when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + res.asString());
    }

    private void login() {
        Response r = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "Admin@2026"))
                .post("/api/cpq/auth/login").thenReturn();
        if (r.statusCode() == 200 && !r.getCookies().isEmpty()) {
            SESSION = new LinkedHashMap<>(r.getCookies());
        } else {
            System.out.println("[S-2] ⚠️ 登录失败 status=" + r.statusCode()
                    + " —— 🚫 这是登录基础设施故障，不是被测功能的结论。"
                    + "429=限流；423/401=账号被锁或被 E2E 置成 INACTIVE。"
                    + "🚫 本片不自行 UPDATE user 表解锁（那是共享库全局状态），请报主线。");
        }
    }

    private UUID createBlankComponent(String label) {
        Response resp = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + PREFIX + label + "_" + RUN_ID + "\"}")
                .post("/api/cpq/components").thenReturn();
        assertReachedBusinessLayer(resp, "建空白组件(" + label + ")");
        assertEquals(200, resp.statusCode(), "建空白组件应 200，实际=" + resp.statusCode() + " body=" + resp.asString());
        UUID id = UUID.fromString(resp.jsonPath().getString("data.id"));
        createdComponentIds.add(id);
        return id;
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 2000 ? s : s.substring(0, 2000) + " …(truncated)");
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private String scalar(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toString();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    private List<String> physicalColumns(String table) {
        List<String> out = new ArrayList<>();
        for (Object o : em.createNativeQuery("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + table + "'").getResultList()) {
            out.add(String.valueOf(o));
        }
        assertFalse(out.isEmpty(), "前置未满足：information_schema 里查不到表 " + table + " 的列");
        return out;
    }
}
