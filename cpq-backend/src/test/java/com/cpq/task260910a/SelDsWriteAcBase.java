package com.cpq.task260910a;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 · <b>分片 S-A（写入侧落表）</b> 的公共基座 —— 服务 AC-1 / AC-2 / AC-3 / AC-4。
 *
 * <h3>断言来源（🚫 不从实现派生）</h3>
 * 每条断言指回 {@code dev-docs/task-260910-选配切ds新表与已有料号绑定/需求文档.md §③} 的 AC 原文，
 * 列映射指回同目录 {@code api.md §4}。本套用例<b>一行都没读</b>
 * {@code com.cpq.configure.** / com.cpq.quotation.** / com.cpq.component.** / com.cpq.dataset.**}
 * 与 {@code cpq-frontend/src/**}（派工 prompt 段 c 点名禁读）。
 * 渲染侧的 SQL 契约<b>从数据库 {@code component_sql_view.sql_template} 现读</b>，不是从 Java 代码抄的。
 *
 * <h3>🧩 分片隔离口径（{@code test.md §1 / §2}）</h3>
 * <ul>
 *   <li><b>造数前缀 {@code T260910A-}</b>：客户编号 / 客户产品编号 / 零件名 / 合成外购件料号全部带它。</li>
 *   <li><b>真正的隔离维度是 {@code customer_no}</b>：本片每个用例自建<b>独有客户</b>
 *       （{@code T260910A-} + 8 位 UUID 片段，共 17 字符，卡在 {@code varchar(20)} 以内），
 *       ⇒ 选配<b>自动发号</b>铸出的销售料号（形如 {@code 0028-2609000013}）虽带不上前缀，
 *       但它只挂在本片自建客户下，别片看不见也碰不到。</li>
 *   <li>🚫 <b>断言一律按 {@code WHERE customer_no='<本用例自建客户>'} 收窄，无一条全局计数</b>
 *       （{@code test.md §2}：「表共 N 行」会被别片打红，且红得像业务回归）。
 *       {@code capacity} 无 {@code customer_no} 列 ⇒ 改按「本轮铸出的料号集合」收窄。</li>
 *   <li>🚫 <b>不读也不改 {@code T260910B-} / {@code T260910C-} / {@code T260910G-} 前缀的数据</b>，
 *       也不碰 {@code CUST-0001} / {@code CUST-0004} 名下任何行（AC 原文的夹具基线用的是它们，
 *       但那是<b>共享客户</b>，往它下面写就等于往别片和真人的环境里写）。</li>
 * </ul>
 *
 * <h3>🚨 §3.2 红线（不可逆操作）</h3>
 * 本套用例<b>无 DROP / TRUNCATE / 清库 / 无 WHERE 的 DELETE</b>。
 * {@code @AfterEach} 里的每条 DELETE 都被「本用例自建的 {@code customer_no} / {@code quotation_id}
 * / 本轮料号 + {@code source='MANUAL'}」限死，且删除行数一律打印出来（数字能被人看见，
 * 判据一旦写宽会立刻暴露成「删了 40 行但本轮只造了 3 个料号」）。
 *
 * <h3>环境（{@code test.md §5 / §6}）</h3>
 * <ul>
 *   <li>库：{@code test} profile 现连 <b>{@code cpq_db_test}</b>（{@code application-test.properties:32} 实查，
 *       BL-0232）。{@link #assertEnvSanity()} 每个用例开跑前把<b>实际连的库名</b>打出来 ——
 *       🚫 不凭配置文件记载。</li>
 *   <li>登录：选配端点类级 {@code @RoleAllowed} + test profile {@code rbac.enabled=true}
 *       ⇒ <b>不带 session 一律 401 假红</b>。{@link #given()} 统一带 admin session，
 *       且登录后用 {@code /auth/me} <b>验明正身</b>（cookie 拿到 ≠ cookie 生效）。</li>
 *   <li>Redis：{@code application-test.properties} 指向不可用地址时登录直接 500
 *       ⇒ 跑测试须带 {@code QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0'}。
 *       {@link #adminSession()} 把 5xx 明确标成「基础设施故障，不是业务结论」。</li>
 * </ul>
 */
public abstract class SelDsWriteAcBase {

    /** 🧩 本片造数前缀（派工 prompt 段 d，写死不许改）。 */
    protected static final String PREFIX = "T260910A-";

    /** 本轮 JVM 唯一标记 —— 让并发轮/上一轮残留与本轮构造性分开。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    protected static final String CONFIGURE = "/api/cpq/configure-product/quotations/";

    /** AC-1 原文的材质：{@code AgCu90} 占比 100（实查 {@code cpq_db_test}：ACTIVE，配置 {@code AgCu90-01} 2 个元素）。 */
    protected static final String RECIPE_AGCU90 = "AgCu90";
    protected static final String CONFIG_AGCU90 = "AgCu90-01";

    /** AC 原文的工序：{@code Z008} 成品清洗（{@code process_category='加工'}）。 */
    protected static final String PROC_Z008 = "Z008";
    /** AC 原文的工序：{@code Z100} 焊接（{@code process_category='组装'}）。 */
    protected static final String PROC_Z100 = "Z100";

    /** 🧩 本片自造的外购件料号（{@code test.md §1} 指定，{@code material_master.material_no} varchar(20) ⇒ 13 字符 OK）。 */
    protected static final String OUT1 = PREFIX + "OUT1";

    @Inject
    protected EntityManager em;

    protected final List<Fx> fixtures = new ArrayList<>();

    /** 一套「客户 + 报价单」夹具。{@code customerNo} 同时是 {@code ds_quote_*} 的 {@code customer_no} 维度。 */
    protected record Fx(UUID customerId, String customerNo, UUID quotationId) {}

    // ─────────────────────────── 环境自检 ───────────────────────────

    /**
     * 🚨 <b>每个用例第一枪</b>：把实际连的库名 / 前置基础数据打出来并硬断言。
     *
     * <p>不打这一枪时，「连错库」的表现是<b>所有断言都以业务失败的面目失败</b>
     * （{@code CLAUDE.md §1}：库选错的症状是「改动/数据看不到效果」）。
     */
    protected void assertEnvSanity() {
        String db = scalar("SELECT current_database()");
        System.out.println("[env] 实际连的库 = " + db + "  RUN_ID=" + RUN_ID);
        assertNotNull(db, "取不到 current_database() ⇒ 数据源没起来");
        assertEquals(1L, count("SELECT count(*) FROM material_recipe WHERE code='" + RECIPE_AGCU90
                        + "' AND status='ACTIVE'"),
                "前置：材质 " + RECIPE_AGCU90 + " 应存在且 ACTIVE（AC-1 原文夹具基线）。"
                        + "0 条 ⇒ **环境前置缺失**，不是被测功能的结论。当前库=" + db);
        assertEquals(1L, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                        + "WHERE r.code='" + RECIPE_AGCU90 + "' AND c.config_no='" + CONFIG_AGCU90 + "' AND c.status='ACTIVE'"),
                "前置：材质配置 " + CONFIG_AGCU90 + " 应存在且 ACTIVE。当前库=" + db);
        for (String p : List.of(PROC_Z008, PROC_Z100)) {
            assertEquals(1L, count("SELECT count(*) FROM process_master WHERE process_no='" + p + "'"),
                    "前置：工序 " + p + " 应存在于 process_master（AC 原文夹具基线）。当前库=" + db);
        }
        for (String t : List.of("ds_quote_self_process_fee", "ds_quote_assembly_fee", "unit_price", "capacity")) {
            assertTrue(tableExists(t), "前置：表 " + t + " 不存在 ⇒ 本片全部断言无从验起。当前库=" + db);
        }
    }

    /** {@code process_master} 里任一「组装」类工序（AC-2 原文：{@code process_category IN ('ASSEMBLY','组装')}）。 */
    protected String anAssemblyProcessNo() {
        String no = scalar("SELECT process_no FROM process_master "
                + "WHERE process_category IN ('ASSEMBLY','组装') ORDER BY process_no LIMIT 1");
        assertNotNull(no, "AC-2 前置：process_master 里应有至少 1 个 process_category IN ('ASSEMBLY','组装') 的工序。"
                + "0 条 ⇒ 组合工序无从选起，本用例会假绿 ⇒ 硬失败");
        return no;
    }

    /** {@code process_master} 的计价单位口径（AC-1 原文：空则 {@code 'KG'}）。 */
    protected String expectedPricingUnit(String processNo) {
        String u = scalar("SELECT coalesce(nullif(trim(standard_unit),''),'KG') FROM process_master "
                + "WHERE process_no='" + processNo + "'");
        assertNotNull(u, "取不到工序 " + processNo + " 的 standard_unit ⇒ AC-1 的 pricing_unit 断言会打空");
        return u;
    }

    /**
     * 🚨 <b>观察手段的阳性对照</b>（{@code testing.md §4.4}：断言「某事没发生」时，
     * 必须配一个阳性对照证明观察手段抓得到该事件）。
     *
     * <p>AC-1②/AC-3③ 断的是「{@code unit_price} 零新增」。这条断言最危险的失效方式不是判错，
     * 而是<b>探测查询本身抓不到东西</b> —— 那时它对任何实现都恒绿。
     * ⇒ 用<b>库里的存量行</b>把同一形状的查询打一遍，必须命中 &gt; 0。
     *
     * <p>📌 实查证据（{@code cpq_db_0724}，2026-09-10）：老路径确实往 {@code unit_price} 写过
     * 选配数据 —— {@code customer_no='CUST-0001'} / {@code finished_material_no='0526-2609000005'}
     * / {@code code='0526-2609000004'} / {@code cost_type='自制加工费'}。本方法就是拿这一类行验探针。
     */
    protected void assertUnitPriceProbeCanSeeRows() {
        Object[] sample = rows("SELECT code, finished_material_no, customer_no, operation_no "
                + "FROM unit_price WHERE cost_type='自制加工费' LIMIT 1").stream().findFirst().orElse(null);
        assertNotNull(sample, "阳性对照失败：unit_price 里连一条 cost_type='自制加工费' 的存量行都没有 "
                + "⇒ 无法证明「零新增」探针抓得到东西，此时 AC-1②/AC-3③ 可能是恒绿的空断言。"
                + "这是**环境前置缺失**，不是被测功能的结论。");
        String code = String.valueOf(sample[0]);
        long byCode = count("SELECT count(*) FROM unit_price WHERE code='" + code + "'");
        System.out.println("[阳性对照] 用存量行验探针：unit_price WHERE code='" + code + "' ⇒ " + byCode
                + " 行（样本=" + java.util.Arrays.toString(sample) + "）");
        assertTrue(byCode > 0, "阳性对照失败：按 code 探测 unit_price 存量行返回 0 行 ⇒ 探针是瞎的，"
                + "「零新增」断言不可信");
    }

    /**
     * 🚨 同上，{@code capacity} 侧的<b>探针阳性对照</b>（AC-2② 断的是「{@code capacity} 零新增」）。
     *
     * <p>📌 实查证据（{@code cpq_db_0724}，2026-09-10）：老路径确实往 {@code capacity} 写过选配组合工艺 ——
     * {@code material_no='0526-2609000005'} / {@code process_no='Z100'} / {@code resource_group_no='QUOTE_ASSEMBLY'}。
     */
    protected void assertCapacityProbeCanSeeRows() {
        String mn = scalar("SELECT material_no FROM capacity WHERE resource_group_no='QUOTE_ASSEMBLY' LIMIT 1");
        assertNotNull(mn, "阳性对照失败：capacity 里连一条 resource_group_no='QUOTE_ASSEMBLY' 的存量行都没有 "
                + "⇒ 无法证明「零新增」探针抓得到东西，AC-2② 可能是恒绿的空断言。"
                + "这是**环境前置缺失**，不是被测功能的结论。");
        long byMn = count("SELECT count(*) FROM capacity WHERE material_no='" + mn + "'");
        System.out.println("[阳性对照] 用存量行验探针：capacity WHERE material_no='" + mn + "' ⇒ " + byMn + " 行");
        assertTrue(byMn > 0, "阳性对照失败：按 material_no 探测 capacity 存量行返回 0 行 ⇒ 探针是瞎的");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 建一套 <b>committed</b> 的「客户 + 报价单」。
     *
     * <p>⚠️ <b>为什么不能用 {@code @TestTransaction}</b>：AC 断言的是「HTTP 提交后落了哪些库行」，
     * 而端点在自己的事务里跑 —— 测试事务回滚既看不到它也拦不住它。⇒ 夹具必须 committed，
     * 还原改由 {@link #restoreFixtures()}（{@code @AfterEach}，等价 finally）承担。
     */
    protected Fx newFixture(String label) {
        UUID customerId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        // 🧩 前缀 + UUID 片段：9 + 8 = 17 字符，卡在 customer_no varchar(20) 以内
        String customerNo = PREFIX + customerId.toString().replace("-", "").substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            Object admin = em.createNativeQuery("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1")
                    .getResultList().stream().findFirst().orElse(null);
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");
            em.createNativeQuery(
                            "INSERT INTO customer (id,name,code,level,accumulated_amount,status,version,created_at,updated_at) "
                                    + "VALUES (:id,:name,:code,'STANDARD',0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("id", customerId)
                    .setParameter("name", PREFIX + "客户-" + label + "-" + RUN_ID)
                    .setParameter("code", customerNo)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,"
                                    + "bound_global_variables_snapshot,user_data_version,created_at,updated_at) "
                                    + "VALUES (:id,:qno,:cid,:qname,CAST(:uid AS uuid),'DRAFT',0,0,'{}'::jsonb,0,NOW(),NOW())")
                    .setParameter("id", quotationId)
                    .setParameter("qno", PREFIX + "QT-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("qname", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString())
                    .executeUpdate();
        });
        Fx fx = new Fx(customerId, customerNo, quotationId);
        fixtures.add(fx);
        System.out.println("[夹具] 客户=" + customerNo + " 报价单=" + quotationId);
        return fx;
    }

    /**
     * 造本片自己的外购件 {@link #OUT1}（{@code test.md §1} 指定）。
     *
     * <p>🚨 <b>为什么必须自造、不能用 AC 原文的 {@code S0011}</b>：`S0011` 挂在共享客户
     * {@code CUST-0001}/{@code CUST-0004} 下。切表后候选与存在性校验按 {@code customer_no} 过滤（D-2），
     * 而本片用的是自建独有客户 ⇒ {@code S0011} 在本片客户下<b>不存在</b>，
     * 用它提交只会得到「外购件不存在」这类<b>前置错误</b>，AC-3 反而验不了。
     * ⇒ 在本片客户下造一条同形态的外购件，AC-3 的判据（费用类别从自制变组装）逐字不变。
     *
     * <p>两处都写，是为了对「读侧切没切表」这件事<b>保持中立</b>（读侧属 S-B 片，不是本片的判据）：
     * {@code material_master}（改前的存在性校验源）+ {@code ds_quote_material}（改后的源，带客户维度）。
     */
    protected String createOutsourcedPart(Fx fx) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO material_master (id,material_no,material_name,material_type,created_at,updated_at) "
                                    + "SELECT gen_random_uuid(),:no,:nm,'外购件',NOW(),NOW() "
                                    + "WHERE NOT EXISTS (SELECT 1 FROM material_master WHERE material_no=:no)")
                    .setParameter("no", OUT1).setParameter("nm", PREFIX + "外购件密封圈").executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO ds_quote_material (material_no,material_name,specification,dimension,"
                                    + "unit_weight,material_type,category_code,customer_no,source,created_at) "
                                    + "VALUES (:no,:nm,'Φ8','8×8×2',2.5,'外购件','000000',:c,'MANUAL',NOW()) "
                                    + "ON CONFLICT (customer_no, material_no) DO NOTHING")
                    .setParameter("no", OUT1).setParameter("nm", PREFIX + "外购件密封圈")
                    .setParameter("c", fx.customerNo()).executeUpdate();
        });
        assertEquals(1L, count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + OUT1
                        + "' AND customer_no='" + fx.customerNo() + "' AND material_type='外购件'"),
                "前置自检：外购件 " + OUT1 + " 应已在本片客户 " + fx.customerNo() + " 下建好（material_type='外购件'）");
        System.out.println("[夹具] 自造外购件 " + OUT1 + " @ " + fx.customerNo());
        return OUT1;
    }

    // ═════════════════════ 管理员 session（本套用例的正确性前提）═════════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /** 🚨 本套用例一律用它起手 —— 直接用 {@code RestAssured.given()} 会被 {@code RoleFilter} 挡在业务层外（401 假红）。 */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            System.out.println("[S-A] 缓存会话已失效（/auth/me=" + me.statusCode() + "），重新登录");
            ADMIN_COOKIES = null;
        }
        // 只解锁，🚫 不改 admin 的密码/状态/角色（testing.md §4.3：不得改变共享库的全局状态）
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
                // 🚨 阳性对照：cookie 拿到 ≠ cookie 生效
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(),
                        "登录拿到 cookie 但 /auth/me 仍不通（" + me.statusCode() + "）⇒ 会话未生效，"
                                + "此时所有业务断言都不可信。body=" + me.asString());
                assertEquals("SYSTEM_ADMIN", me.jsonPath().getString("data.role"),
                        "🚨 会话角色不是 SYSTEM_ADMIN ⇒ 「管理员应能调通」的断言失去意义");
                System.out.println("[S-A] admin 登录成功，cookies=" + ADMIN_COOKIES.keySet());
                return ADMIN_COOKIES;
            }
            System.out.println("[S-A] 第 " + (i + 1) + " 次登录失败 status=" + last.statusCode()
                    + (last.statusCode() == 429 ? "（登录限流 30/min/IP）" : "")
                    + (last.statusCode() >= 500 ? "（疑似 Redis 不可用：SessionHelper.createSession→hset"
                        + " 抛 CONNECTION_CLOSED ⇒ 需带 QUARKUS_REDIS_HOSTS 重跑）" : ""));
            try { Thread.sleep(3000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("admin 登录连续 4 次失败，最后 status=" + last.statusCode() + " body=" + last.asString()
                + "\n🚫 这是**登录基础设施故障**，不是被测功能的结论：429=限流；423/401=账号锁或密码不对；"
                + "5xx=Redis/会话存储不可用（带 QUARKUS_REDIS_HOSTS 重跑）。请先修环境再看业务断言。");
    }

    // ─────────────── 请求构造（结构以 api.md §2.3 为准）───────────────

    protected Response configure(Fx fx, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
                .post(CONFIGURE + fx.quotationId()).thenReturn();
    }

    @SafeVarargs
    protected final Map<String, Object> submitBody(String productType, String customerProductNo,
                                                   Map<String, Object>... parts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", productType);
        body.put("customerProductNo", customerProductNo);
        body.put("customerProductName", PREFIX + "产品");
        body.put("parts", List.of(parts));
        return body;
    }

    /** 新建零件（{@code partType=PART} / {@code partMode=new}）。 */
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

    /** 外购件（{@code partType=OUTSOURCED}）。 */
    protected Map<String, Object> outsourcedPart(String outsourcedPartNo, List<String> processNos) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", PREFIX + "外购件");
        p.put("partType", "OUTSOURCED");
        p.put("outsourcedPartNo", outsourcedPartNo);
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

    /** 组合工艺（{@code defCode} = {@code process_master.process_no}）。 */
    protected Map<String, Object> compositeProcess(String processNo, List<Integer> participating) {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("defCode", processNo);
        cp.put("participatingPartIndexes", participating);
        cp.put("params", Map.of());
        return cp;
    }

    // ─────────────────────────── 断言辅助 ───────────────────────────

    /** 🚨 假绿守卫：鉴权/路由把请求挡在业务层外时，状态码不是业务码 —— 那是 harness 故障伪装成业务结论。 */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403,
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— "
                        + "这是 harness 故障，不是 AC 结论。实际响应=" + res.asString());
        assertTrue(res.statusCode() != 404,
                when + "：端点 404 ⇒ 该端点尚未实现或路径与 api.md 不一致。实际响应=" + res.asString());
    }

    protected void assertSubmitOk(Response res, String when) {
        assertReachedBusinessLayer(res, when);
        assertEquals(200, res.statusCode(), when + "：应提交成功，实际=" + res.statusCode() + " " + res.asString());
        System.out.println("[" + when + "] 200 " + res.asString());
        assertFreshCast(res, when);
    }

    /**
     * 🚨 <b>指纹复用假绿守卫</b>：提交返 200 但 {@code fingerprintMatched=true} 时
     * <b>一个新料号都没铸</b>，此时再去查「新表有行」会被<b>存量行</b>骗过 ⇒ 断言看似通过、实则空验证。
     *
     * <p>本片每个夹具都新建独立客户（{@link #newFixture}），按契约不可能命中指纹；
     * 一旦命中说明夹具隔离被破坏（或客户被别片复用），属 <b>harness 故障</b>，不是 AC 结论。
     */
    protected void assertFreshCast(Response res, String when) {
        Object fpm = res.jsonPath().get("fingerprintMatched");
        Object reused = res.jsonPath().get("reusedHfPartNos");
        System.out.println("[" + when + "·指纹守卫] fingerprintMatched=" + fpm + " reusedHfPartNos=" + reused);
        assertNotEquals(Boolean.TRUE, fpm,
                when + "：fingerprintMatched=true ⇒ 本次提交复用了既有料号、一个新号都没铸，"
                        + "后续「新表有行」的断言会被存量行骗过（空验证）。这是 harness 故障，不是 AC 结论。"
                        + " 响应=" + res.asString());
        assertTrue(reused == null || ((java.util.List<?>) reused).isEmpty(),
                when + "：reusedHfPartNos 非空（" + reused + "）⇒ 同上，存在复用料号，断言可能空跑");
    }

    /** 本次提交在报价单里落下的全部行：{@code [料号, composite_type, 是否根行]}。 */
    protected List<Object[]> lineItemsOf(Fx fx) {
        return rows("SELECT product_part_no_snapshot, composite_type, (parent_line_item_id IS NULL) "
                + "FROM quotation_line_item WHERE quotation_id='" + fx.quotationId() + "' "
                + "ORDER BY composite_type, sort_order");
    }

    /**
     * 根行（{@code SIMPLE}/{@code COMPOSITE}）的销售料号 —— AC-1/AC-2/AC-4 的「轴」。
     * 🚨 断言前先保证非空（{@code testing.md §3}「断言从未执行 = 假绿」）。
     */
    protected String rootPartNo(Fx fx) {
        String pn = scalar("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND parent_line_item_id IS NULL "
                + "ORDER BY sort_order LIMIT 1");
        assertNotNull(pn, "提交后报价单里应有根行、且带销售料号 —— 取不到说明后面的断言会空跑。行结构="
                + lineItemsOf(fx).stream().map(java.util.Arrays::toString).toList());
        assertFalse(pn.isBlank(), "销售料号不得为空串");
        return pn;
    }

    /** COMPOSITE 子件料号（{@code composite_type='PART'} 且 {@code parent_line_item_id} 非空）。 */
    protected List<String> childPartNos(Fx fx) {
        return col("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND parent_line_item_id IS NOT NULL "
                + "ORDER BY sort_order").stream()
                .filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** 按零件名反查本片客户下铸出的料号（AC-4 要把「哪个子件配了哪道工序」对上）。 */
    protected String castPartNoByName(Fx fx, String materialName) {
        return scalar("SELECT material_no FROM ds_quote_material WHERE customer_no='" + fx.customerNo()
                + "' AND material_name='" + materialName + "' ORDER BY id DESC LIMIT 1");
    }

    /** 本片客户下 {@code ds_quote_self_process_fee} 的全部行（分片安全：按 customer_no 收窄）。 */
    protected List<Object[]> selfProcessRows(Fx fx) {
        return rows("SELECT material_no, input_material_no, operation_no, operation_item_seq, item_seq, "
                + "currency, pricing_unit, value::text, version_no, source "
                + "FROM ds_quote_self_process_fee WHERE customer_no='" + fx.customerNo() + "' "
                + "ORDER BY item_seq, operation_item_seq");
    }

    /** 本片客户下 {@code ds_quote_assembly_fee} 的全部行（分片安全：按 customer_no 收窄）。 */
    protected List<Object[]> assemblyRows(Fx fx) {
        return rows("SELECT material_no, assembly_operation, assembly_fee::text, item_seq, currency, "
                + "pricing_unit, defect_rate::text, version_no, source "
                + "FROM ds_quote_assembly_fee WHERE customer_no='" + fx.customerNo() + "' "
                + "ORDER BY item_seq");
    }

    protected static String fmt(List<Object[]> rows) {
        return rows.stream().map(java.util.Arrays::toString).toList().toString();
    }

    // ─────────────────────────── 只读工具 ───────────────────────────

    protected String scalar(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        if (r.isEmpty() || r.get(0) == null) return null;
        return r.get(0).toString();
    }

    protected long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected boolean tableExists(String table) {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='"
                + table + "'") > 0;
    }

    // ─────────────────────────── 还原（等价 finally）───────────────────────────

    /**
     * 还原本片写进共享库的一切。
     *
     * <p>🚫 每条 DELETE 都带收敛谓词（本用例自建的 {@code customer_no} / {@code quotation_id}
     * / 本轮铸出的料号 + {@code source='MANUAL'}），不存在无 WHERE 的删除，不存在 TRUNCATE / DROP。
     * <p>🚨 {@code source='MANUAL'} 是<b>白名单</b>口径，不是黑名单：这些表里躺着导入侧的基础数据
     * （{@code source='IMPORT'}），🚫 不许写成 {@code source <> 'IMPORT'}。
     */
    @AfterEach
    void restoreFixtures() {
        List<String> errors = new ArrayList<>();
        for (Fx fx : fixtures) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    String cust = fx.customerNo();
                    // 本轮在本客户下铸出的料号（多源并集，漏一个源就会留残留）
                    List<Object> partNos = col(
                            "SELECT DISTINCT quote_part_no FROM sel_part_signature WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT material_no FROM ds_quote_customer_part WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT material_no FROM ds_quote_material WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT material_no FROM material_customer_map WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT product_part_no_snapshot FROM quotation_line_item "
                                    + "  WHERE quotation_id='" + fx.quotationId() + "' AND product_part_no_snapshot IS NOT NULL");
                    List<String> pns = partNos.stream().filter(java.util.Objects::nonNull)
                            .map(Object::toString).toList();

                    em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=:q")
                            .setParameter("q", fx.quotationId()).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation WHERE id=:q")
                            .setParameter("q", fx.quotationId()).executeUpdate();

                    if (tableExists("sel_product_no")) {
                        em.createNativeQuery("DELETE FROM sel_product_no WHERE customer_no=:c")
                                .setParameter("c", cust).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM sel_part_signature WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();

                    // 本任务两张新落点 + 它们的归档/记录表（🚨 都按 customer_no + MANUAL 收窄）
                    int delSpf = 0, delAsm = 0, delSpfH = 0, delAsmH = 0, delSpfR = 0, delAsmR = 0;
                    delSpf = em.createNativeQuery("DELETE FROM ds_quote_self_process_fee WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();
                    delAsm = em.createNativeQuery("DELETE FROM ds_quote_assembly_fee WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();
                    if (tableExists("ds_quote_self_process_fee_history")) {
                        delSpfH = em.createNativeQuery("DELETE FROM ds_quote_self_process_fee_history WHERE customer_no=:c AND source='MANUAL'")
                                .setParameter("c", cust).executeUpdate();
                    }
                    if (tableExists("ds_quote_assembly_fee_history")) {
                        delAsmH = em.createNativeQuery("DELETE FROM ds_quote_assembly_fee_history WHERE customer_no=:c AND source='MANUAL'")
                                .setParameter("c", cust).executeUpdate();
                    }
                    if (tableExists("ds_quote_self_process_fee_record")) {
                        delSpfR = em.createNativeQuery("DELETE FROM ds_quote_self_process_fee_record WHERE customer_no=:c")
                                .setParameter("c", cust).executeUpdate();
                    }
                    if (tableExists("ds_quote_assembly_fee_record")) {
                        delAsmR = em.createNativeQuery("DELETE FROM ds_quote_assembly_fee_record WHERE customer_no=:c")
                                .setParameter("c", cust).executeUpdate();
                    }

                    // 选配写的其余 ds_quote_* / _record（本客户 + MANUAL）
                    int delCp = em.createNativeQuery("DELETE FROM ds_quote_customer_part WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();
                    int delMb = em.createNativeQuery("DELETE FROM ds_quote_material_bom WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();
                    int delEb = em.createNativeQuery("DELETE FROM ds_quote_element_bom WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();
                    int delMbR = 0, delEbR = 0;
                    if (tableExists("ds_quote_material_bom_record")) {
                        delMbR = em.createNativeQuery("DELETE FROM ds_quote_material_bom_record WHERE customer_no=:c")
                                .setParameter("c", cust).executeUpdate();
                    }
                    if (tableExists("ds_quote_element_bom_record")) {
                        delEbR = em.createNativeQuery("DELETE FROM ds_quote_element_bom_record WHERE customer_no=:c")
                                .setParameter("c", cust).executeUpdate();
                    }
                    int delMat = em.createNativeQuery("DELETE FROM ds_quote_material WHERE customer_no=:c AND source='MANUAL'")
                            .setParameter("c", cust).executeUpdate();

                    // V6 老表侧（若实现仍在双写，这里同样按本客户收窄清掉，避免残留）
                    int delUp = em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    int delEbi = em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    int delMbi = em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();

                    // capacity 无 customer_no ⇒ 只删本轮铸出的料号那几行
                    int delCap = 0;
                    if (!pns.isEmpty()) {
                        delCap = em.createNativeQuery("DELETE FROM capacity WHERE material_no IN (:p)")
                                .setParameter("p", pns).executeUpdate();
                    }
                    // 发号台账（本客户）
                    String code4 = scalar("SELECT code FROM quote_customer_code WHERE customer_no='" + cust + "'");
                    int delSeq = 0;
                    if (code4 != null) {
                        delSeq = em.createNativeQuery("DELETE FROM quote_material_no_seq WHERE customer_code=:k")
                                .setParameter("k", code4).executeUpdate();
                    }
                    int delQcc = em.createNativeQuery("DELETE FROM quote_customer_code WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();

                    // material_master：只删「已无任何引用」的本轮料号 + 本片自造外购件
                    List<String> mmTargets = new ArrayList<>(pns);
                    mmTargets.add(OUT1);
                    for (String pn : mmTargets) {
                        em.createNativeQuery("DELETE FROM material_master mm WHERE mm.material_no=:p "
                                        + "AND NOT EXISTS (SELECT 1 FROM material_bom_item b WHERE b.material_no=mm.material_no) "
                                        + "AND NOT EXISTS (SELECT 1 FROM material_customer_map m WHERE m.material_no=mm.material_no) "
                                        + "AND NOT EXISTS (SELECT 1 FROM sel_part_signature s WHERE s.quote_part_no=mm.material_no)")
                                .setParameter("p", pn).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                            .setParameter("id", fx.customerId()).executeUpdate();

                    // 🚨 删除行数一律打印：判据写宽会立刻暴露成「删了一大把但本轮只造了 N 个料号」
                    System.out.println("[还原] cust=" + cust + " 本轮料号=" + pns
                            + " | spf=" + delSpf + " asm=" + delAsm + " spf_hist=" + delSpfH + " asm_hist=" + delAsmH
                            + " spf_rec=" + delSpfR + " asm_rec=" + delAsmR
                            + " | cust_part=" + delCp + " mat_bom=" + delMb + " el_bom=" + delEb
                            + " mb_rec=" + delMbR + " eb_rec=" + delEbR + " material=" + delMat
                            + " | unit_price=" + delUp + " capacity=" + delCap
                            + " element_bom_item=" + delEbi + " material_bom_item=" + delMbi
                            + " | qcc=" + delQcc + " seq=" + delSeq);
                });
            } catch (RuntimeException e) {
                errors.add("fixture " + fx.customerNo() + ": " + e);
            }
        }
        List<Fx> done = List.copyOf(fixtures);
        fixtures.clear();
        if (!errors.isEmpty()) System.out.println("[还原] 🚨 清理时的异常（须登记给主线）：" + errors);
        assertNoResidue(done);
    }

    /** 还原自检 —— 让「脏数据」以残留的名义硬失败，而不是伪装成下一轮的业务缺陷。 */
    protected void assertNoResidue(List<Fx> done) {
        for (Fx fx : done) {
            String c = fx.customerNo();
            for (String t : List.of("ds_quote_self_process_fee", "ds_quote_assembly_fee",
                    "ds_quote_material", "ds_quote_material_bom", "ds_quote_element_bom",
                    "ds_quote_customer_part", "unit_price", "material_bom_item", "element_bom_item",
                    "sel_part_signature", "material_customer_map")) {
                assertEquals(0L, count("SELECT count(*) FROM " + t + " WHERE customer_no='" + c + "'"),
                        "还原自检：" + t + " 仍有 " + c + " 的残留 ⇒ 必须登记给主线");
            }
            assertEquals(0L, count("SELECT count(*) FROM customer WHERE code='" + c + "'"),
                    "还原自检：customer 仍有 " + c + " 的残留");
        }
        assertEquals(0L, count("SELECT count(*) FROM material_master WHERE material_no='" + OUT1 + "'"),
                "还原自检：本片自造外购件 " + OUT1 + " 仍留在 material_master ⇒ 必须登记给主线");
    }
}
