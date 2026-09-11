package com.cpq.task260910b;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;

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
 * task-260910 · <b>测试分片 S-B（读取侧与判据 · 只读 + 少量私有造数）</b>的公共基座。
 *
 * <h3>本片认领的 AC（断言全部指回 {@code 需求文档.md §③} 原文）</h3>
 * {@code AC-5}（已有零件能搜到新表料号）· {@code AC-6}（客户维度隔离，阳性可证伪）·
 * {@code AC-7}（已有零件搜索也按客户过滤）· {@code AC-8}（多材质全部带出）·
 * {@code AC-9}（判据是 JOIN 命中，不是 {@code output_material_type}）·
 * {@code AC-21}（指纹复用不受影响）· {@code AC-22}（换序仍命中 + 确认页提示已有顺序）。
 *
 * <p>🚫 本基座与本包所有用例<b>未读任何实现代码</b>
 * （{@code com/cpq/configure/}、{@code com/cpq/quotation/}、{@code com/cpq/component/}、
 * {@code com/cpq/dataset/}、{@code cpq-frontend/src/} 一个文件都没打开）。
 * 请求形状取自 {@code api.md §2.1 / §2.2 / §2.4}，断言取自 AC 原文。
 *
 * <h3>🚨 分片纪律（{@code testing.md §4.5} / {@code test.md §1~§2}）</h3>
 * <ul>
 *   <li>造数前缀 {@link #PREFIX}（{@code T260910B-}）+ 本轮 {@link #RUN_ID}；
 *       🚫 不读、不改 {@code T260910A-} / {@code T260910C-} / {@code T260910G-} 任何数据。</li>
 *   <li>🚫 <b>无全局计数断言</b>。唯一需要「条数」的 {@code AC-6} 已按 {@code test.md §2}
 *       收窄到 {@code WHERE material_no LIKE 'T260910B-%'} 的自造对照上，
 *       🚫 不断言现网的 5。</li>
 *   <li>🚫 <b>不改任何全局状态</b>：不动用户启停用 / 角色 / 模板发布态 / 系统开关 /
 *       公共基础数据。{@code CUST-0001} / {@code CUST-0004} / {@code material_recipe} /
 *       {@code process_master} 一个字节都不写（只读引用）。</li>
 *   <li>🚫 无 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的删除 / 清库（{@code CLAUDE.md §3.2} 红线）。
 *       {@link #cleanup()} 的命中面被 {@link #PREFIX} 与本轮自建 id 限死。</li>
 * </ul>
 *
 * <h3>🚨 为什么每条断言前都有一道「前提自证」</h3>
 * {@code testing.md §5.5}：<b>判据可能是零证据</b>。本片的三个高危形态：
 * <ol>
 *   <li><b>恒为空的样本</b> —— 搜索返回空数组时，「不含 X」这类断言<b>恒真</b>。
 *       ⇒ {@link #assertNonEmpty} 先把非空钉住。</li>
 *   <li><b>恒为 1 的维度</b> —— 若被测料号只挂 1 个客户，「客户隔离」断言必然通过。
 *       ⇒ {@link #assertDualCustomerPremise} 用<b>不带客户谓词的同一段 SQL</b> 反证
 *       「不过滤就是 2 行」，把 {@code AC-6} 的证伪前提当场立起来。</li>
 *   <li><b>鉴权把请求挡在业务层外</b> —— 401/403 下「返 0 条」照样通过。
 *       ⇒ {@link #assertReachedBusinessLayer} 写在每条断言链最前面。</li>
 * </ol>
 */
public abstract class SbBase {

    // ═══════════════════ 分片命名空间 ═══════════════════

    /** 分片前缀（主线分配，写死）。 */
    protected static final String PREFIX = "T260910B-";

    /** 本轮 JVM 唯一标记 —— 两轮运行互不撞名，也不会把上轮残留误当本轮数据。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    // ═══════════════════ 端点（api.md §2）═══════════════════

    protected static final String SEARCH_PARTS = "/api/cpq/quotations/configure/search-parts";
    protected static final String OUTSOURCED_PARTS = "/api/cpq/quotations/configure/outsourced-parts";
    protected static final String LOOKUP_FP = "/api/cpq/configure-product/lookup-fingerprint";
    protected static final String CONFIGURE = "/api/cpq/configure-product/quotations/";

    // ═══════════════════ 夹具基线（需求文档 §③ 夹具基线，2026-09-10 本轮实查确认）═══════════════════

    /** {@code CUST-0001}（四位码 {@code 0526}）—— 🚫 只读。 */
    protected static final String CUST_1 = "CUST-0001";
    /** {@code CUST-0004}（四位码 {@code 0028}）—— 🚫 只读。 */
    protected static final String CUST_4 = "CUST-0004";

    /**
     * {@code AC-5} 的锚点料号 —— 🔴 <b>2026-09-10 按 {@code D-16} 换成 {@code S0013}</b>（🚫 只读，本片不造不删）。
     *
     * <p><b>为什么换</b>：上一轮用的 {@code T260907M-AC17ANCHOR} 实测<b>只挂 {@code CUST-0001}</b>，
     * 而 {@code AC-5} 的前置写的是「{@code CUST-0004} 的报价单」⇒ 在 {@code D-2}
     * （查询必须带客户、不许跨客户）之下，以 {@code CUST-0004} 搜它<b>必然 0 条</b> ——
     * 那正好是 {@code AC-7} 的语义，两条 AC 按字面直接互斥。
     *
     * <p><b>{@code S0013} 的可证伪性</b>（{@code D-16} 实测，本轮已复核）：
     * 它在老表 {@code material_master} 里 {@code count(*)=0} ⇒ <b>走老 SQL 必然 0 命中</b>，
     * 切表后才命中。且 {@code CUST-0004} 下全部 2672 个料号在 {@code material_master} 里都不存在
     * ⇒ 该形态是全体常态，锚点不脆弱。
     */
    protected static final String AC5_ANCHOR = "S0013";

    /** {@code AC-5} 的锚点所属客户（{@code D-16}：以 {@code CUST-0004} 发起搜索）。 */
    protected static final String AC5_ANCHOR_CUST = CUST_4;

    /**
     * {@code AC-7} 方向的<b>现网只读</b>对照料号（{@code D-16} 明确「逐字不动」）：
     * 实测只挂 {@code CUST-0001} ⇒ 以 {@code CUST-0004} 搜它必须 0 条。
     * 🚫 只读，本片不造不删。
     */
    protected static final String AC7_NET_C1ONLY = "T260907M-AC17ANCHOR";

    /** {@code AC-9}① 点名的「声明成品但 JOIN 落空」料号（🚫 只读）。 */
    protected static final String AC9_MISS_PART = "PERF0909-B00001";
    /** 它那一行的投入料号 —— 一个零件料号，JOIN {@code material_recipe} 必然落空。 */
    protected static final String AC9_MISS_INPUT = "T260907T-RM01";

    // ── 本片自造的对照料号（前缀限死）──
    /** {@code AC-7} / {@code AC-6}③：只挂 {@code CUST-0001} 的零件料号。 */
    protected final String C1ONLY = PREFIX + "C1ONLY";
    /** {@code AC-8}：挂 2 个以上材质的料号（造在 {@code CUST-0004} 下，供搜索命中）。 */
    protected final String MULTI = PREFIX + "MULTI";
    /** {@code AC-6}①②：同一料号同时挂两个客户的<b>外购件</b>对照。 */
    protected final String OUT_DUAL = PREFIX + "OUTDUAL";
    /** {@code AC-6}③ 阴性对照：只属于 {@code CUST-0001} 的外购件。 */
    protected final String OUT_C1ONLY = PREFIX + "OUTC1";

    /** 只读引用的材质 code（JOIN {@code material_recipe.code} 必然命中）。 */
    protected static final String RECIPE_A = "00006";     // AgNi10
    protected static final String RECIPE_B = "00168";     // 301/Cu/301
    protected static final String RECIPE_C = "00001";     // Ag
    /** 一个<b>不是材质</b>的投入料号（JOIN 必然落空）—— 用于 AC-9 反向多判。 */
    protected final String NON_RECIPE_INPUT = PREFIX + "NOTARECIPE";

    /** 只读引用的工序（{@code process_master} 实查存在）。 */
    protected static final String PROC_1 = "Z100";   // 焊接
    protected static final String PROC_2 = "Z101";   // 铆接

    @Inject
    protected EntityManager em;

    /** 本轮自建的「客户 + 报价单」夹具（AC-21 / AC-22 用），{@code @AfterEach} 逐个还原。 */
    protected final List<Fx> fixtures = new ArrayList<>();

    protected record Fx(UUID customerId, String customerNo, UUID quotationId) {}

    // ═══════════════════ SQL 助手 ═══════════════════

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
        Object o = q.getSingleResult();
        return o == null ? 0L : ((Number) o).longValue();
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

    protected void exec(String sql, Object... p) {
        QuarkusTransaction.requiringNew().run(() -> {
            Query q = em.createNativeQuery(sql);
            bind(q, p);
            q.executeUpdate();
        });
    }

    protected boolean tableExists(String table) {
        return scalarLong("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name=?1", table) > 0;
    }

    /**
     * 🚨 清理前必须先问 {@code information_schema}「这张表有没有这一列」。
     *
     * <p>2026-09-10 本片第一轮实测踩到：清理清单里写了
     * {@code DELETE FROM capacity WHERE customer_no=?}，而 {@code capacity} <b>没有 customer_no 列</b>
     * ⇒ 该语句抛 {@code 42703}，把整个 {@code requiringNew()} 事务连带回滚 ⇒
     * <b>本轮自建的 customer 一行都没删掉</b>，然后在<b>下一个测试类</b>的残留自检里报「清理未净」。
     * 失败信息指向的是后面那个类，读报告的人会去查它的业务代码 —— 典型的
     * 「上一轮残留伪装成本轮缺陷」（{@code testing.md §4.3} 第 3 层）。
     */
    protected boolean columnExists(String table, String column) {
        return scalarLong("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 AND column_name=?2", table, column) > 0;
    }

    // ═══════════════════ 私有造数（🚫 只碰 T260910B- 前缀）═══════════════════

    /**
     * 在 {@code ds_quote_material} 造一行私有料号。
     *
     * @param materialType {@code '外购件'} 或 null（零件）—— {@code api.md §2.2} 的候选筛选维度
     */
    protected void seedMaterial(String customerNo, String materialNo, String name,
                               String materialType, String spec) {
        exec("INSERT INTO ds_quote_material "
                + "(material_no, material_name, specification, dimension, material_type, "
                + " source, customer_no, created_at, created_by) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, 'MANUAL', ?6, now(), ?7)",
                materialNo, name, spec, "1x2x3", materialType, customerNo, "T260910B/" + RUN_ID);
    }

    /**
     * 在 {@code ds_quote_material_bom} 造一行 BOM。
     *
     * <p>🔑 {@code outputMaterialType} 由调用方<b>刻意</b>指定成与「是不是材质」<b>无关</b>的值 ——
     * {@code AC-9} 的全部内容就是「判据是 JOIN 命中，不是这一列」。
     *
     * @param inputMaterialNo 投入料号；给 {@code material_recipe.code} 则 JOIN 命中，
     *                        给 {@link #NON_RECIPE_INPUT} 则必然落空
     */
    protected void seedBom(String customerNo, String materialNo, int itemSeq,
                           String inputMaterialNo, String outputMaterialType, String ratio) {
        exec("INSERT INTO ds_quote_material_bom "
                + "(material_no, item_seq, input_material_no, output_material_type, material_ratio, "
                + " component_qty, version_no, row_fingerprint, source, customer_no, created_at, created_by) "
                + "VALUES (?1, ?2, ?3, ?4, CAST(?5 AS numeric), 1, 1, "
                + "        md5(random()::text) || md5(random()::text), 'MANUAL', ?6, now(), ?7)",
                materialNo, itemSeq, inputMaterialNo, outputMaterialType, ratio,
                customerNo, "T260910B/" + RUN_ID);
    }

    /**
     * 造齐本片全部只读对照料号，并<b>逐条自证造成功</b>。
     *
     * <p>🚨 不自证的后果：造数静默失败（列名变了 / 约束挡住）时，
     * 后面「搜不到它」这类断言会<b>恒真</b>，报绿而什么都没验（{@code testing.md §5.5} 形态③）。
     */
    protected void seedReadSideFixtures() {
        cleanupSeeds();   // 幂等：先清本前缀残留，避免上一轮崩溃留下的行让本轮断言错位

        // ① AC-7 / AC-6③：只挂 CUST-0001 的零件料号
        seedMaterial(CUST_1, C1ONLY, PREFIX + "只属于CUST-0001的零件", null, "SPEC-C1ONLY");

        // ② AC-8：挂 2 个材质（00006 / 00168 都能 JOIN 上 material_recipe.code）
        //    + 第 3 行刻意给一个 JOIN 落空的投入料号（AC-9 反向多判的自造样本）
        seedMaterial(CUST_4, MULTI, PREFIX + "多材质零件", null, "SPEC-MULTI");
        seedBom(CUST_4, MULTI, 10, RECIPE_A, "成品", "70");        // JOIN 命中，但声明成品 ⇒ 必须带出
        seedBom(CUST_4, MULTI, 20, RECIPE_B, null, "30");          // JOIN 命中，声明 NULL ⇒ 必须带出
        seedBom(CUST_4, MULTI, 30, NON_RECIPE_INPUT, "RECIPE", null); // JOIN 落空，却声明 RECIPE ⇒ 🚫 不许带出

        // ③ AC-6①②：同一外购件料号挂两个客户（FT-2 的证伪前提）
        seedMaterial(CUST_1, OUT_DUAL, PREFIX + "双客户外购件", "外购件", "SPEC-DUAL");
        seedMaterial(CUST_4, OUT_DUAL, PREFIX + "双客户外购件", "外购件", "SPEC-DUAL");

        // ④ AC-6③ 阴性对照：只属于 CUST-0001 的外购件
        seedMaterial(CUST_1, OUT_C1ONLY, PREFIX + "只属于CUST-0001的外购件", "外购件", "SPEC-OUTC1");

        // ── 造数自证 ──
        assertEquals(1L, scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no=?2", C1ONLY, CUST_1),
                "造数自证：" + C1ONLY + " 应在 " + CUST_1 + " 下恰好 1 行");
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no=?2", C1ONLY, CUST_4),
                "造数自证：" + C1ONLY + " 🚫 不许出现在 " + CUST_4 + " 下（否则 AC-7 变成零证据）");
        assertEquals(1L, scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no=?2", MULTI, CUST_4),
                "造数自证：" + MULTI + " 应在 " + CUST_4 + " 下恰好 1 行");
        assertEquals(2L, scalarLong("SELECT count(*) FROM ds_quote_material_bom b "
                + "JOIN material_recipe mr ON mr.code = b.input_material_no "
                + "WHERE b.material_no=?1 AND b.customer_no=?2", MULTI, CUST_4),
                "造数自证：" + MULTI + " 应恰好有 2 行 BOM 能 JOIN 上 material_recipe（AC-8 的 N=2）");
        assertEquals(1L, scalarLong("SELECT count(*) FROM ds_quote_material_bom b "
                + "LEFT JOIN material_recipe mr ON mr.code = b.input_material_no "
                + "WHERE b.material_no=?1 AND b.customer_no=?2 AND mr.code IS NULL", MULTI, CUST_4),
                "造数自证：" + MULTI + " 应恰好有 1 行 BOM JOIN 落空（AC-9 反向多判样本）");
        assertEquals(2L, scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no=?1", OUT_DUAL),
                "造数自证：" + OUT_DUAL + " 应跨两个客户共 2 行 —— 这是 AC-6 证伪前提（FT-2）");
        System.out.println("[S-B·seed " + RUN_ID + "] 对照料号已就绪："
                + C1ONLY + "(" + CUST_1 + ") / " + MULTI + "(" + CUST_4 + ",2材质+1落空) / "
                + OUT_DUAL + "(双客户) / " + OUT_C1ONLY + "(" + CUST_1 + ")");
    }

    /**
     * 🔑 <b>AC-6 的证伪前提（FT-2 的可执行部分）</b>：用<b>不带客户谓词</b>的同一段数据源 SQL
     * 反证「不过滤就是 2 行」。
     *
     * <p>{@code test.md §4 FT-2} 要求「把 {@code WHERE customer_no=:customerNo} 去掉重跑 ⇒ 必须变红」。
     * 本片<b>不能改实现代码</b>，⇒ 在测试内用同一份数据源做<b>反事实对照</b>：
     * 不过滤客户时 {@link #OUT_DUAL} 出<b>双份</b>。这证明「每客户各 1 条」不是恒真 ——
     * 若现网恰好每料号只挂 1 客户，本方法会当场硬失败，而不是让 AC-6 恒绿。
     */
    protected void assertDualCustomerPremise() {
        long noFilter = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_type='外购件' AND material_no = ?1", OUT_DUAL);
        long withFilter1 = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_type='外购件' AND customer_no=?1 AND material_no=?2", CUST_1, OUT_DUAL);
        long withFilter4 = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_type='外购件' AND customer_no=?1 AND material_no=?2", CUST_4, OUT_DUAL);
        System.out.println("[S-B·FT-2 反事实] " + OUT_DUAL + " 不带客户谓词=" + noFilter
                + " 行；带 " + CUST_1 + "=" + withFilter1 + " 行；带 " + CUST_4 + "=" + withFilter4 + " 行");
        assertEquals(2L, noFilter, "🚨 FT-2 证伪前提不成立：不过滤客户时 " + OUT_DUAL + " 只有 "
                + noFilter + " 行 ⇒ 「每客户各 1 条」变成恒真断言，AC-6 会假绿。"
                + "\n  ⇒ 先修夹具，再看 AC-6 结论。");
        assertEquals(1L, withFilter1, "带 " + CUST_1 + " 谓词应恰好 1 行");
        assertEquals(1L, withFilter4, "带 " + CUST_4 + " 谓词应恰好 1 行");
    }

    // ═══════════════════ 登录（本片正确性前提）═══════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /**
     * <b>本片一律用它起手，🚫 不要直接 {@code RestAssured.given()}</b>。
     *
     * <p>{@code test.md §6} 登记的环境缺陷：test profile 的 RBAC 开关自相矛盾 ⇒
     * 不带 session 的请求一律 <b>401 假红</b>；而「返 0 条」这类断言在 401 下<b>照样通过</b>（假绿）。
     */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            System.out.println("[S-B] 缓存会话失效（/auth/me=" + me.statusCode() + "），重新登录");
            ADMIN_COOKIES = null;
        }
        // 只解锁，🚫 不改 admin 的密码 / 状态 / 角色（testing.md §4.3：不得改变共享库的全局状态）
        exec("UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL WHERE username = 'admin'");
        Response last = null;
        for (int i = 0; i < 4; i++) {
            last = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", "admin", "password", "Admin@2026"))
                    .post("/api/cpq/auth/login").thenReturn();
            if (last.statusCode() == 200) {
                ADMIN_COOKIES = new LinkedHashMap<>(last.getCookies());
                assertFalse(ADMIN_COOKIES.isEmpty(), "登录返 200 却没拿到 cookie（会话机制变了？）");
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(), "登录拿到 cookie 但 /auth/me 仍不通（"
                        + me.statusCode() + "）⇒ 会话未生效，此时所有业务断言都不可信。body=" + me.asString());
                System.out.println("[S-B] admin 登录成功，role=" + me.jsonPath().getString("data.role"));
                return ADMIN_COOKIES;
            }
            System.out.println("[S-B] 第 " + (i + 1) + " 次登录失败 status=" + last.statusCode()
                    + (last.statusCode() == 429 ? "（登录限流 30/min/IP）" : "")
                    + (last.statusCode() >= 500 ? "（疑似 Redis 不可用：test.md §6 已登记，"
                        + "跑测试加 QUARKUS_REDIS_HOSTS 覆盖）" : ""));
            try { Thread.sleep(3000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("[S-B] 🔴【环境未就绪，非产品缺陷】admin 登录连续 4 次失败，最后 status="
                + last.statusCode() + " body=" + last.asString()
                + "\n  429=登录限流；423/401=账号被锁或密码不对；5xx=Redis/会话存储不可用（test.md §6）。"
                + "\n  ⚠️ 本片全部 AC 判定为『未验证』—— skip != pass。");
    }

    // ═══════════════════ 请求（形状取自 api.md §2）═══════════════════

    /** {@code api.md §2.1}：{@code customerNo} 🆕 必填。 */
    protected Response searchParts(String customerNo, String q) {
        return given().queryParam("customerNo", customerNo).queryParam("q", q)
                .get(SEARCH_PARTS).thenReturn();
    }

    /** 不带 {@code customerNo} 的老形状 —— 用于「改动前 / 改动后」A/B 对照。 */
    protected Response searchPartsLegacy(String q) {
        return given().queryParam("q", q).get(SEARCH_PARTS).thenReturn();
    }

    /** {@code api.md §2.2}：{@code customerNo} 🆕 必填。 */
    protected Response outsourcedParts(String customerNo, String keyword) {
        RequestSpecification s = given().queryParam("page", 1).queryParam("size", 200);
        if (customerNo != null) s = s.queryParam("customerNo", customerNo);
        if (keyword != null) s = s.queryParam("keyword", keyword);
        return s.get(OUTSOURCED_PARTS).thenReturn();
    }

    // ═══════════════════ 假绿 / 假红守卫 ═══════════════════

    /** 🚨 鉴权或路由把请求挡在业务层外时，任何业务断言都不是 AC 结论。 */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403,
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— "
                        + "这是 harness 故障，不是 AC 结论。body=" + trunc(res.asString()));
        assertTrue(res.statusCode() != 404,
                when + "：端点 404 —— 该端点尚未实现或路径与 api.md §2 不一致。body="
                        + trunc(res.asString()));
        assertTrue(res.statusCode() < 500,
                when + "：服务端 " + res.statusCode() + " —— 先排查环境（DB / Redis），"
                        + "再谈业务结论。body=" + trunc(res.asString()));
    }

    /** 🚨 空结果 ⇒ 后续「不含 X」「等于 N」全部恒真（{@code testing.md §3}）。 */
    protected void assertNonEmpty(List<?> l, String what) {
        assertNotNull(l, what + "：结果为 null —— 断言会空跑（假绿）");
        assertFalse(l.isEmpty(), what + "：结果为空列表（0 行）—— 空列表 / 0 行 / 「—」/「加载中…」"
                + "一律不算通过（test.md §2）。此时后续断言全部恒真，判定为未验证。");
    }

    protected static String trunc(String s) {
        return s == null ? "null" : (s.length() > 1200 ? s.substring(0, 1200) + "…(截断)" : s);
    }

    // ═══════════════════ AC-21 / AC-22 用的私有「客户 + 报价单」═══════════════════

    /** 建一套 committed 的私有客户 + 报价单。🚫 不碰 CUST-0001 / CUST-0004。 */
    protected Fx newFixture(String label) {
        UUID customerId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        // customer.code 落各表 customer_no（varchar(20)）⇒ 必须 ≤20 字符
        String customerNo = "T2610B" + customerId.toString().replace("-", "").substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            Object admin = em.createNativeQuery("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1")
                    .getResultList().stream().findFirst().orElse(null);
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");
            em.createNativeQuery("INSERT INTO customer "
                            + "(id,name,code,level,product_category_id,accumulated_amount,status,version,created_at,updated_at) "
                            + "VALUES (:id,:name,:code,'STANDARD',NULL,0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("id", customerId)
                    .setParameter("name", PREFIX + "客户-" + label + "-" + RUN_ID)
                    .setParameter("code", customerNo).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation "
                            + "(id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,"
                            + " bound_global_variables_snapshot,product_category_id,user_data_version,created_at,updated_at) "
                            + "VALUES (:id,:qno,:cid,:qname,CAST(:uid AS uuid),'DRAFT',0,0,'{}'::jsonb,NULL,0,NOW(),NOW())")
                    .setParameter("id", quotationId)
                    .setParameter("qno", PREFIX + "QT-" + RUN_ID + "-" + label)
                    .setParameter("cid", customerId)
                    .setParameter("qname", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString()).executeUpdate();
        });
        Fx fx = new Fx(customerId, customerNo, quotationId);
        fixtures.add(fx);
        System.out.println("[S-B·fx] " + label + " customerNo=" + customerNo + " quotation=" + quotationId);
        return fx;
    }

    /** 选配提交请求体（形状沿用既有契约；{@code api.md §2.3} 本期只<b>加</b> bindExistingMaterialNo）。 */
    @SafeVarargs
    protected final Map<String, Object> submitBody(String customerProductNo, Map<String, Object>... parts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", "SIMPLE");
        body.put("customerProductNo", customerProductNo);
        body.put("customerProductName", PREFIX + "产品");
        body.put("parts", List.of(parts));
        return body;
    }

    protected Map<String, Object> newPart(String name, String spec, String dimension, String weight,
                                          List<Map<String, Object>> materials, List<String> processNos) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("partType", "PART");
        p.put("partMode", "new");
        p.put("spec", spec);
        p.put("dimension", dimension);
        p.put("unitWeightGrams", weight);
        p.put("materials", materials);
        p.put("processNos", processNos);
        p.put("quantity", 1);
        return p;
    }

    protected Map<String, Object> material(String recipeCode, String configNo, String ratio) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipeCode", recipeCode);
        m.put("configNo", configNo);
        m.put("ratio", ratio);
        m.put("elements", null);
        return m;
    }

    protected Response configure(Fx fx, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
                .post(CONFIGURE + fx.quotationId()).thenReturn();
    }

    // ═══════════════════ 清理（命中面被前缀 / 自建 id 限死）═══════════════════

    /** 只清本片前缀的只读对照料号。🚫 无 TRUNCATE / DROP / 无 WHERE 删除。 */
    protected void cleanupSeeds() {
        String like = PREFIX + "%";
        exec("DELETE FROM ds_quote_material_bom WHERE material_no LIKE ?1 OR input_material_no LIKE ?1", like);
        exec("DELETE FROM ds_quote_material WHERE material_no LIKE ?1", like);
        if (tableExists("ds_quote_element_bom")) {
            exec("DELETE FROM ds_quote_element_bom WHERE material_no LIKE ?1", like);
        }
    }

    /**
     * 还原本轮写进共享库的一切（等价 {@code finally}，用例中途崩溃也照样执行）。
     *
     * <p>🚫 每条 DELETE 都带收敛谓词：本轮自建的 {@code customer_no} / {@code quotation_id}
     * 或 {@link #PREFIX} 前缀。不存在无 WHERE 的删除、TRUNCATE、DROP、清库。
     */
    @AfterEach
    void cleanup() {
        List<String> errs = new ArrayList<>();
        for (Fx fx : fixtures) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    String cust = fx.customerNo();
                    List<String> partNos = new ArrayList<>(strList(
                            "SELECT DISTINCT material_no FROM ds_quote_customer_part WHERE customer_no=?1 "
                            + "UNION SELECT DISTINCT quote_part_no FROM sel_part_signature WHERE customer_no=?1 "
                            + "UNION SELECT DISTINCT material_no FROM ds_quote_material WHERE customer_no=?1",
                            cust));
                    partNos.removeIf(java.util.Objects::isNull);

                    em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=:q")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation WHERE id=:q")
                            .setParameter("q", fx.quotationId()).executeUpdate();

                    // 🚨 逐表先问「有没有 customer_no 这一列」——见 columnExists 的注释：
                    //    capacity 没有该列，硬写会把整个清理事务连带回滚。
                    for (String t : List.of("sel_product_no", "sel_part_signature", "ds_quote_customer_part",
                            "ds_quote_material", "ds_quote_material_bom", "ds_quote_element_bom",
                            "ds_quote_self_process_fee", "ds_quote_assembly_fee",
                            "ds_quote_material_bom_record", "ds_quote_element_bom_record",
                            "unit_price", "capacity", "material_customer_map", "quote_customer_code")) {
                        if (tableExists(t) && columnExists(t, "customer_no")) {
                            int n = em.createNativeQuery("DELETE FROM " + t + " WHERE customer_no=:c")
                                    .setParameter("c", cust).executeUpdate();
                            if (n > 0) System.out.println("[S-B·cleanup] " + t + " 删 " + n + " 行 (cust=" + cust + ")");
                        }
                    }
                    if (!partNos.isEmpty() && tableExists("material_master")) {
                        em.createNativeQuery("DELETE FROM material_master mm WHERE mm.material_no IN (:p) "
                                        + "AND NOT EXISTS (SELECT 1 FROM ds_quote_customer_part c "
                                        + "                 WHERE c.material_no = mm.material_no)")
                                .setParameter("p", partNos).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                            .setParameter("id", fx.customerId()).executeUpdate();
                });
            } catch (RuntimeException e) {
                errs.add("fx " + fx.customerNo() + ": " + e);
            }
        }
        fixtures.clear();
        try {
            cleanupSeeds();
        } catch (RuntimeException e) {
            errs.add("seeds: " + e);
        }
        // 残留自检（🚫 只查本片命名空间，不做全局计数）
        long leftMat = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no LIKE ?1", PREFIX + "%");
        long leftBom = scalarLong("SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE material_no LIKE ?1 OR input_material_no LIKE ?1", PREFIX + "%");
        // 🚫 收窄到<b>本轮</b> RUN_ID：并发片 / 上一轮残留不该把本轮打红（testing.md §4.5 全局计数禁令）
        long leftCust = scalarLong("SELECT count(*) FROM customer WHERE name LIKE ?1", "%" + RUN_ID + "%");
        System.out.println("[S-B·cleanup " + RUN_ID + "] 残留 ds_quote_material=" + leftMat
                + " ds_quote_material_bom=" + leftBom + " customer=" + leftCust
                + (errs.isEmpty() ? "" : " ⚠️ 清理异常=" + errs));
        assertTrue(errs.isEmpty(), "清理过程有异常（可能留下残留，请人工核）：" + errs);
        assertEquals(0L, leftMat, "清理未净：ds_quote_material 还剩 " + leftMat + " 行 " + PREFIX);
        assertEquals(0L, leftBom, "清理未净：ds_quote_material_bom 还剩 " + leftBom + " 行 " + PREFIX);
        assertEquals(0L, leftCust, "清理未净：customer 还剩 " + leftCust + " 行（本轮 RUN_ID=" + RUN_ID + "）");
    }
}
