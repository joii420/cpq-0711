package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

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
 * task-260902「选配流程重构」验收用例的公共基座。
 *
 * <h3>断言来源</h3>
 * 本套用例的每一条断言都指回 {@code dev-docs/task-260902-选配流程重构/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md}。<b>本基座不含任何业务判据</b>，只做三件事：
 * 夹具构造（committed）、全局状态还原、还原自检。
 *
 * <h3>🚨 环境纪律（{@code test.md §0}）</h3>
 * {@code ./mvnw test} 实测连的是 <b>共享开发库 {@code cpq_db_0724}</b>
 * （{@code application-test.properties:24}），不是独立测试库。因此：
 * <ul>
 *   <li>🚫 <b>不清库、不 TRUNCATE、不 DROP</b>；本类所有 DELETE 的命中面都被
 *       「本用例自建的 customer / quotation」这两个 id 或 {@code T260902-} 前缀限死。</li>
 *   <li>⚠️ <b>本套用例无法用 {@code @TestTransaction}</b>：AC 要求断言的是「HTTP 提交后落了哪些库行」，
 *       而端点在自己的事务里跑，测试事务回滚看不到也拦不住它。⇒ 夹具必须 committed，
 *       还原改由 {@link #restoreFixtures()}（{@code @AfterEach}，等价 finally）承担 ——
 *       这正是 {@code test.md §0} 表格里「除非该数据必须跨事务可见」那一条豁免，
 *       已在 {@code test-report.md} 点名登记并附清理 SQL。</li>
 *   <li>🚨 每条 DELETE 都必须能在用例中途崩溃时照样执行 ⇒ 全部写在 {@code @AfterEach} 里，
 *       不依赖「跑完手工清一下」。</li>
 * </ul>
 *
 * <h3>本套用例会动到的全局状态（{@code testing.md §4.3} 要求登记）</h3>
 * <ol>
 *   <li><b>只增不改</b>：自建 customer / quotation / product_category / sel_template / material_recipe，
 *       全部带 {@code T260902-} 前缀，@AfterEach 精确删除。</li>
 *   <li><b>唯一一处「改现存行」</b>：AC-16 需要「外购件 0 条」的场景，会临时改
 *       {@code material_master.material_type}，由 {@code MaterialAndOutsourcedAcTest} 自己在
 *       {@code finally} 里逐行还原并自检 —— 详见该类的类注释。</li>
 * </ol>
 */
public abstract class SelConfigAcTestBase {

    /** 本任务专属前缀：所有自建数据都带它，删除面靠它限死（{@code test.md §0}）。 */
    protected static final String PREFIX = "T260902-";

    /**
     * 本次 JVM 运行的唯一标记。自建材质的 {@code code} 带上它 ⇒
     * <b>任何两轮运行都不可能撞 {@code material_recipe_code_key}</b>。
     * 🚨 这不是洁癖：不带它时，「上一轮残留」会以「本轮 duplicate key」的面目出现，
     * 而那个报错长得非常像业务缺陷（实测第三轮里它盖掉了 2 条真实用例的结论）。
     */
    protected static final String RUN_ID = UUID.randomUUID().toString().substring(0, 6);

    protected static final String CONFIGURE = "/api/cpq/configure-product/quotations/";
    protected static final String LOOKUP_FP = "/api/cpq/configure-product/lookup-fingerprint";
    protected static final String CHECK_PRODUCT_NO = "/api/cpq/quotations/configure/check-product-no";
    protected static final String OUTSOURCED_PARTS = "/api/cpq/quotations/configure/outsourced-parts";

    // ── fixture基线.md §1.1 的主力双材质 fixture（2026-09-02 实查）──────────────
    /** {@code 00006 / AgNi10}，配置 {@code 00006-01}：Ag=90 / Ni=10。 */
    protected static final String RECIPE_A = "00006";
    protected static final String RECIPE_A_SYMBOL = "AgNi10";
    protected static final String CONFIG_A = "00006-01";
    /** {@code 00123 / AgZnO12/Cu}，配置 {@code 00123-01}：Ag=24.4 / Cu=72.2727 / Zn=3.3273。
     *  ⚠️ symbol 自带 {@code /}，正是 api.md §4.3 长度前缀编码要防的那类文本。 */
    protected static final String RECIPE_B = "00123";
    protected static final String RECIPE_B_SYMBOL = "AgZnO12/Cu";
    protected static final String CONFIG_B = "00123-01";

    // ── fixture基线.md §2 的工序（🚫 不要用 MRO-*，本库从未灌入）──────────────
    protected static final String PROC_1 = "Z100";   // 焊接
    protected static final String PROC_2 = "Z101";   // 铆接

    @Inject
    protected EntityManager em;

    /** 本次用例自建的夹具，@AfterEach 逐个还原。 */
    protected final List<Fx> fixtures = new ArrayList<>();
    /** 本次用例自建的材质 code（{@link #createRecipe}），@AfterEach 精确删除。 */
    protected final List<String> createdRecipeCodes = new ArrayList<>();
    /** 本次用例自建的产品分类 id。 */
    protected final List<UUID> createdCategoryIds = new ArrayList<>();
    /** 本次用例自建的选配模板 id。 */
    protected final List<UUID> createdTemplateIds = new ArrayList<>();

    /** 一套「客户 + 报价单」夹具。customerNo 同时是 V6 表的 {@code customer_no} 维度。 */
    protected record Fx(UUID customerId, String customerNo, UUID quotationId) {}

    /**
     * 🚨 <b>开跑前先清一次上一轮的残留</b>（沿用 {@code MaterialAcTestBase} 的做法）。
     *
     * <p>没有这一步时，上一轮崩溃留下的 {@code T260902-} 材质会让本轮的
     * {@code assertNoResidue} 在<b>每一个</b>用例结束时失败 —— 一次残留污染整份报告，
     * 而且失败信息指向的是「本轮」，读报告的人会去查本轮的业务代码。
     * <p>🚫 删除面仅限 {@code T260902-} 前缀的<b>自建物</b>（材质/分类/模板），
     * 不碰任何存量数据，不碰 customer（它的 code 带随机 UUID，永不撞名）。
     *
     * <h4>🚨 只扫「陈旧」残留 —— 这一条是并发安全的关键</h4>
     * 2026-09-03 第六轮实测：另一轮同套件与本轮重叠运行时，本方法的
     * {@code DELETE ... WHERE code LIKE 'T260902-%'} 把<b>对方在途的材质</b>删掉了 ⇒
     * 对方的用例中途报 {@code 404 材质不存在或已停用} / {@code 409 Referenced record does not exist}，
     * <b>那些报错长得和业务缺陷一模一样</b>（{@code testing.md §4.2}：并行测试的临时资源必须唯一化，
     * 且获取与占用之间不能有「无主窗口」—— 全局前缀删除正是把别人的资源变成了无主）。
     * ⇒ 加 {@code created_at < now() - 30min} 门槛：崩溃残留必然是旧的，
     * 并发轮的在途数据必然是新的，两者构造性分开。
     */
    private static final String STALE = " AND created_at < now() - interval '30 minutes'";

    @BeforeEach
    void sweepResidueFromPreviousRun() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM material_recipe_element WHERE recipe_id IN "
                    + "(SELECT id FROM material_recipe WHERE code LIKE :p" + STALE + ")")
                    .setParameter("p", PREFIX + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM material_recipe_config WHERE recipe_id IN "
                    + "(SELECT id FROM material_recipe WHERE code LIKE :p" + STALE + ")")
                    .setParameter("p", PREFIX + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM material_recipe_composition WHERE recipe_id IN "
                    + "(SELECT id FROM material_recipe WHERE code LIKE :p" + STALE + ")")
                    .setParameter("p", PREFIX + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM material_recipe WHERE code LIKE :p" + STALE)
                    .setParameter("p", PREFIX + "%").executeUpdate();
            if (tableExists("sel_template_item_value")) {
                em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN "
                        + "(SELECT i.id FROM sel_template_item i JOIN sel_template t ON t.id=i.template_id "
                        + " WHERE t.name LIKE :p" + STALE.replace("created_at", "t.created_at") + ")")
                        .setParameter("p", PREFIX + "%").executeUpdate();
            }
            em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id IN "
                    + "(SELECT id FROM sel_template WHERE name LIKE :p" + STALE + ")")
                    .setParameter("p", PREFIX + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM sel_template WHERE name LIKE :p" + STALE)
                    .setParameter("p", PREFIX + "%").executeUpdate();
        });
        // 清完立刻自检：脏库必须以「残留」的名义硬失败，不许伪装成本轮的业务缺陷
        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE code LIKE '" + PREFIX + "%'"
                        + STALE),
                "开跑前自检：上一轮的陈旧 T260902- 材质残留没清掉");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 建一套 committed 的「客户 + 报价单」。
     *
     * @param categoryId 客户绑定的产品分类，可为 null（AC-25 用它构造「分类无选配模板」的客户）
     */
    protected Fx newFixture(String label, UUID categoryId) {
        UUID customerId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        // customer_no 落 material_customer_map.customer_no（varchar(20)）⇒ 必须 ≤20 字符
        String customerNo = "T2609" + customerId.toString().replace("-", "").substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            Object admin = em.createNativeQuery("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1")
                    .getResultList().stream().findFirst().orElse(null);
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");
            em.createNativeQuery(
                            "INSERT INTO customer (id,name,code,level,product_category_id,accumulated_amount,status,version,created_at,updated_at) "
                                    + "VALUES (:id,:name,:code,'STANDARD',:cat,0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("id", customerId)
                    .setParameter("name", PREFIX + "客户-" + label)
                    .setParameter("code", customerNo)
                    .setParameter("cat", categoryId)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,"
                                    + "bound_global_variables_snapshot,product_category_id,user_data_version,created_at,updated_at) "
                                    + "VALUES (:id,:qno,:cid,:qname,CAST(:uid AS uuid),'DRAFT',0,0,'{}'::jsonb,:cat,0,NOW(),NOW())")
                    .setParameter("id", quotationId)
                    .setParameter("qno", PREFIX + "QT-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("qname", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString())
                    .setParameter("cat", categoryId)
                    .executeUpdate();
        });
        Fx fx = new Fx(customerId, customerNo, quotationId);
        fixtures.add(fx);
        return fx;
    }

    protected Fx newFixture(String label) {
        return newFixture(label, null);
    }

    /** 建一个本任务专属的产品分类（AC-25 需要「该分类下没有选配模板」）。 */
    protected UUID createCategory(String label) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "INSERT INTO product_category (id,code,name,status,sort_order,created_at,updated_at) "
                                + "VALUES (:id,:code,:name,'ACTIVE',9902,NOW(),NOW())")
                .setParameter("id", id)
                .setParameter("code", PREFIX + label + "-" + RUN_ID)
                .setParameter("name", PREFIX + "分类" + label + "-" + RUN_ID)
                .executeUpdate());
        createdCategoryIds.add(id);
        return id;
    }

    /**
     * 建一条本任务专属的材质 + 可选的一组含量配置。
     *
     * @param allowCustom   {@code allow_custom_content}，AC-21/AC-22 需要 true
     *                      （🚨 fixture基线 §1.3：现网 <b>0 条</b> 为 true，原 4 条 demo 污染已清理，
     *                      🚫 不得引用 {@code AgCu85/AgCu90/AgNi90/AgNi95}）
     * @param elements      配置内的元素含量（百分数，如 Ag=90 / Ni=10）；传空 List ⇒ 建成
     *                      <b>0 组 ACTIVE 配置</b>的材质（AC-5b / AC-18b 需要，
     *                      🚨 现网 258 条 ACTIVE 材质<b>每条恰好 1 组</b>，一条 0 组的都没有）
     * @return 材质 code（即请求里的 {@code recipeCode}）
     */
    protected String createRecipe(String label, boolean allowCustom, List<String[]> elements) {
        // 🚨 code 必须带 RUN_ID：material_recipe.code 上有 UNIQUE(code)，
        //    上一轮若因崩溃留下残留，下一轮同名 INSERT 会直接撞 material_recipe_code_key，
        //    于是「上一轮的清理没做干净」会伪装成「本轮建夹具失败」——2026-09-03 第三轮实测踩到。
        String code = PREFIX + "M" + label + "-" + RUN_ID;
        String symbol = PREFIX + "M" + label + "-" + RUN_ID;   // material_recipe.symbol varchar(32)
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO material_recipe (id,code,symbol,name,recipe_type,sort_order,status,allow_custom_content,created_at,updated_at) "
                                    + "VALUES (gen_random_uuid(),:code,:sym,:sym,'locked',9902,'ACTIVE',:ac,NOW(),NOW())")
                    .setParameter("code", code).setParameter("sym", symbol)
                    .setParameter("ac", allowCustom).executeUpdate();
            // 元素组成（material_recipe_composition）—— 与 00006 同构：从 element 主表取权威元素
            int i = 0;
            for (String[] el : elements) {
                i++;
                em.createNativeQuery(
                                "INSERT INTO material_recipe_composition (id,recipe_id,element_no,element_code,element_name,sort_order,created_at) "
                                        + "SELECT gen_random_uuid(), r.id, e.element_no, e.element_code, e.element_name, :ord, NOW() "
                                        + "FROM material_recipe r, element e WHERE r.code=:code AND e.element_code=:ec")
                        .setParameter("ord", i).setParameter("code", code).setParameter("ec", el[0])
                        .executeUpdate();
            }
            if (!elements.isEmpty()) {
                em.createNativeQuery(
                                "INSERT INTO material_recipe_config (id,recipe_id,config_no,seq,status,sort_order,created_at,updated_at) "
                                        + "SELECT gen_random_uuid(), r.id, :cfg, 1, 'ACTIVE', 1, NOW(), NOW() "
                                        + "FROM material_recipe r WHERE r.code=:code")
                        .setParameter("cfg", code + "-01").setParameter("code", code).executeUpdate();
                int j = 0;
                for (String[] el : elements) {
                    j++;
                    em.createNativeQuery(
                                    "INSERT INTO material_recipe_element (id,recipe_id,config_id,element_no,element_code,element_name,default_pct,is_locked,sort_order,created_at) "
                                            + "SELECT gen_random_uuid(), r.id, c.id, e.element_no, e.element_code, e.element_name, CAST(:pct AS numeric), true, :ord, NOW() "
                                            + "FROM material_recipe r JOIN material_recipe_config c ON c.recipe_id=r.id, element e "
                                            + "WHERE r.code=:code AND c.config_no=:cfg AND e.element_code=:ec")
                            .setParameter("pct", el[1]).setParameter("ord", j)
                            .setParameter("code", code).setParameter("cfg", code + "-01")
                            .setParameter("ec", el[0]).executeUpdate();
                }
            }
        });
        createdRecipeCodes.add(code);
        // 🚨 构造后立刻自检：不确认前置真的建成，后面的断言可能在验一个不存在的场景（假绿）
        assertEquals(elements.isEmpty() ? 0L : 1L,
                count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                        + "WHERE r.code='" + code + "' AND c.status='ACTIVE'"),
                "构造自检：材质 " + code + " 的 ACTIVE 配置组数不符合预期");
        return code;
    }

    /**
     * 给已存在的材质再挂一组 ACTIVE 含量配置（<b>AC-10 专用</b>）。
     *
     * <p>🚨 AC-10 需要「同一材质下含量<b>逐字相同</b>的两条配置」，而 {@code fixture基线.md §1.2} 实查：
     * 258 条 ACTIVE 材质里只有 {@code 00262/SnO2} 有 2 组，且那两组含量<b>并不相同</b>
     * （一组元素码是脏数据 {@code 10004}）⇒ 现网无此 fixture，必须自建。
     * <p>📌 建在<b>本任务自建的材质</b>上（不是 {@code 00006}），@AfterEach 随该材质整条删除，
     * 不碰任何存量数据。
     */
    protected void addConfig(String recipeCode, String configNo, int seq, List<String[]> elements) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO material_recipe_config (id,recipe_id,config_no,seq,status,sort_order,created_at,updated_at) "
                                    + "SELECT gen_random_uuid(), r.id, :cfg, :seq, 'ACTIVE', :seq, NOW(), NOW() "
                                    + "FROM material_recipe r WHERE r.code=:code")
                    .setParameter("cfg", configNo).setParameter("seq", seq)
                    .setParameter("code", recipeCode).executeUpdate();
            int j = 0;
            for (String[] el : elements) {
                j++;
                em.createNativeQuery(
                                "INSERT INTO material_recipe_element (id,recipe_id,config_id,element_no,element_code,element_name,default_pct,is_locked,sort_order,created_at) "
                                        + "SELECT gen_random_uuid(), r.id, c.id, e.element_no, e.element_code, e.element_name, CAST(:pct AS numeric), true, :ord, NOW() "
                                        + "FROM material_recipe r JOIN material_recipe_config c ON c.recipe_id=r.id, element e "
                                        + "WHERE r.code=:code AND c.config_no=:cfg AND e.element_code=:ec")
                        .setParameter("pct", el[1]).setParameter("ord", j)
                        .setParameter("code", recipeCode).setParameter("cfg", configNo)
                        .setParameter("ec", el[0]).executeUpdate();
            }
        });
        assertEquals((long) elements.size(),
                count("SELECT count(*) FROM material_recipe_element e JOIN material_recipe_config c ON c.id=e.config_id "
                        + "WHERE c.config_no='" + configNo + "'"),
                "构造自检：配置 " + configNo + " 的元素行数不符合预期");
    }

    /** 建一个挂在指定产品分类下的选配模板（AC-25 的「有模板」对照组）。 */
    protected UUID createSelTemplate(UUID categoryId) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO sel_template (id,name,status,version,product_category_id,created_at,updated_at) "
                                    + "VALUES (:id,:name,'ACTIVE',0,:cat,NOW(),NOW())")
                    .setParameter("id", id).setParameter("name", PREFIX + "模板-" + RUN_ID)
                    .setParameter("cat", categoryId).executeUpdate();
            String[] codes = {"MATERIAL", "ELEMENT", "PROCESS"};
            for (int i = 0; i < codes.length; i++) {
                em.createNativeQuery(
                                "INSERT INTO sel_template_item (id,template_id,param_type_code,enabled,sort_order) "
                                        + "VALUES (gen_random_uuid(),:tid,:code,true,:ord)")
                        .setParameter("tid", id).setParameter("code", codes[i]).setParameter("ord", i)
                        .executeUpdate();
            }
        });
        createdTemplateIds.add(id);
        return id;
    }

    // ─────────────────────────── 请求构造（结构以 api.md §1 为准）───────────────────────────

    protected Response configure(Fx fx, Map<String, Object> body) {
        return RestAssured.given().contentType(ContentType.JSON).body(body)
                .post(CONFIGURE + fx.quotationId()).thenReturn();
    }

    /** api.md §1.1：{@code customerProductNo} 必填。 */
    @SafeVarargs
    protected final Map<String, Object> submitBody(String customerProductNo, Map<String, Object>... parts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", "SIMPLE");
        body.put("customerProductNo", customerProductNo);
        body.put("customerProductName", PREFIX + "产品");
        body.put("parts", List.of(parts));
        return body;
    }

    /** api.md §1.2：新建零件（{@code partType=PART} / {@code partMode=new}）。 */
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

    /** api.md §1.2：外购件（{@code partType=OUTSOURCED}）。 */
    protected Map<String, Object> outsourcedPart(String outsourcedPartNo, List<String> processNos) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", PREFIX + "外购件");
        p.put("partType", "OUTSOURCED");
        p.put("outsourcedPartNo", outsourcedPartNo);
        p.put("processNos", processNos);
        p.put("quantity", 1);
        return p;
    }

    /** 标准配方材质：{@code configNo} 与 {@code elements} 互斥，这里给 configNo。 */
    protected Map<String, Object> material(String recipeCode, String configNo, String ratio) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipeCode", recipeCode);
        m.put("configNo", configNo);
        m.put("ratio", ratio);
        m.put("elements", null);
        return m;
    }

    /**
     * 自定义含量材质（AC-21 / AC-22）。
     * ⚠️ <b>元素 pct 的单位沿用 {@code task-260901} 已交付的口径：分数，Σ=1</b>
     * （证据：{@code ConfigureProductMaterialSourceTest} 断言的错误文案「含量合计必须为 1，实际 1.08」）。
     * 而 <b>落库侧 {@code element_bom_item.content} 是百分数</b>（实查现网样本 Ag=90 / Ni=10）——
     * 两侧单位不同是既有事实，AC-21 断言的 88/12 是<b>落库侧</b>的值。
     * 📌 api.md §1.2 没有写明 {@code materials[i].elements} 的单位 ⇒ 已作为契约缺口报主线。
     */
    protected Map<String, Object> materialCustom(String recipeCode, String ratio, List<Map<String, Object>> elements) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipeCode", recipeCode);
        m.put("configNo", null);
        m.put("ratio", ratio);
        m.put("elements", elements);
        return m;
    }

    protected Map<String, Object> el(String elementCode, String pct) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("elementCode", elementCode);
        e.put("pct", pct);
        return e;
    }

    // ─────────────────────────── 断言辅助 ───────────────────────────

    /**
     * 🚨 假绿守卫：鉴权把请求挡在业务逻辑之外时，状态码不是业务码，
     * 断言「非 200」会照样通过 —— 那是 harness 故障伪装成业务结论。
     */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403 && res.asString().contains("UNAUTHORIZED"),
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— "
                        + "这是 harness 故障，不是 AC 结论。实际响应=" + res.asString());
        assertTrue(res.statusCode() != 404,
                when + "：端点 404，说明该端点尚未实现或路径与 api.md 不一致。实际响应=" + res.asString());
    }

    /** 断言「提交成功」并把响应打出来（{@code testing.md §3}：要求打印实际值）。 */
    protected void assertSubmitOk(Response res, String when) {
        assertReachedBusinessLayer(res, when);
        assertEquals(200, res.statusCode(), when + "：应提交成功，实际=" + res.statusCode() + " " + res.asString());
        System.out.println("[" + when + "] 200 " + res.asString());
    }

    /**
     * 本次提交加进报价单的销售料号 —— 从最新的报价行读回。
     * 🚨 断言前先保证非空（{@code testing.md §3}「断言从未执行 = 假绿」）。
     */
    protected String latestLinePartNo(Fx fx) {
        String partNo = scalar("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' ORDER BY created_at DESC, sort_order DESC LIMIT 1");
        assertNotNull(partNo, "提交后报价单里应有行、且带销售料号 —— 取不到说明本次断言会空跑");
        assertFalse(partNo.isBlank(), "销售料号不得为空串");
        return partNo;
    }

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return ((Number) v).longValue();
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

    // ─────────────────────────── 还原 ───────────────────────────

    /**
     * 还原本套用例写进共享库的一切。
     * <p>🚫 每条 DELETE 都带收敛谓词（本用例自建的 customer_no / quotation_id / {@code T260902-} 前缀），
     * 不存在无 WHERE 的删除，不存在 TRUNCATE / DROP。
     */
    @AfterEach
    void restoreFixtures() {
        List<String> cleanupErrors = new ArrayList<>();
        try {
            for (Fx fx : fixtures) {
              try {
                QuarkusTransaction.requiringNew().run(() -> {
                    String cust = fx.customerNo();
                    // 先把本客户名下铸出的销售料号收集起来（material_master 没有 customer 维度，
                    // 只能靠这三张带 customer_no 的表反查）
                    List<Object> partNos = col(
                            "SELECT DISTINCT quote_part_no FROM sel_part_signature WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT material_no FROM material_bom_item WHERE customer_no='" + cust + "' "
                                    + "UNION SELECT DISTINCT material_no FROM material_customer_map WHERE customer_no='" + cust + "'");

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
                    em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
                            .setParameter("c", cust).executeUpdate();

                    // material_master：只删「已经没有任何引用」的本次料号。
                    // 🚨 NOT EXISTS 三条是护栏 —— 万一某个料号被别的客户共用，绝不误删。
                    for (Object pn : partNos) {
                        if (pn == null) continue;
                        em.createNativeQuery("DELETE FROM material_master mm WHERE mm.material_no=:p "
                                        + "AND NOT EXISTS (SELECT 1 FROM material_bom_item b WHERE b.material_no=mm.material_no) "
                                        + "AND NOT EXISTS (SELECT 1 FROM material_customer_map m WHERE m.material_no=mm.material_no) "
                                        + "AND NOT EXISTS (SELECT 1 FROM sel_part_signature s WHERE s.quote_part_no=mm.material_no)")
                                .setParameter("p", pn.toString()).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                            .setParameter("id", fx.customerId()).executeUpdate();
                });
              } catch (RuntimeException e) {
                  // 🚨 一个夹具清不掉，不能连累后面的夹具与材质/分类/模板的清理
                  //    （第三轮实测：fixtures 循环里抛一次，后面的材质删除整段被跳过 ⇒ 残留）
                  cleanupErrors.add("fixture " + fx.customerNo() + ": " + e);
              }
            }

            try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID tid : createdTemplateIds) {
                    if (tableExists("sel_template_item_value")) {
                        em.createNativeQuery("DELETE FROM sel_template_item_value WHERE item_id IN "
                                        + "(SELECT id FROM sel_template_item WHERE template_id=:t)")
                                .setParameter("t", tid).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM sel_template_item WHERE template_id=:t")
                            .setParameter("t", tid).executeUpdate();
                    em.createNativeQuery("DELETE FROM sel_template WHERE id=:t")
                            .setParameter("t", tid).executeUpdate();
                }
                for (String code : createdRecipeCodes) {
                    em.createNativeQuery("DELETE FROM material_recipe_element WHERE config_id IN "
                                    + "(SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code=:c)")
                            .setParameter("c", code).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_recipe_element WHERE recipe_id IN "
                                    + "(SELECT id FROM material_recipe WHERE code=:c)")
                            .setParameter("c", code).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_recipe_config WHERE recipe_id IN "
                                    + "(SELECT id FROM material_recipe WHERE code=:c)")
                            .setParameter("c", code).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_recipe_composition WHERE recipe_id IN "
                                    + "(SELECT id FROM material_recipe WHERE code=:c)")
                            .setParameter("c", code).executeUpdate();
                    em.createNativeQuery("DELETE FROM material_recipe WHERE code=:c")
                            .setParameter("c", code).executeUpdate();
                }
                for (UUID cid : createdCategoryIds) {
                    em.createNativeQuery("DELETE FROM product_category WHERE id=:id")
                            .setParameter("id", cid).executeUpdate();
                }
            });
            } catch (RuntimeException e) {
                cleanupErrors.add("recipes/templates/categories: " + e);
            }
        } finally {
            List<Fx> done = List.copyOf(fixtures);
            fixtures.clear();
            createdRecipeCodes.clear();
            createdCategoryIds.clear();
            createdTemplateIds.clear();
            if (!cleanupErrors.isEmpty()) System.out.println("[还原] 清理时的异常：" + cleanupErrors);
            assertNoResidue(done);
        }
    }

    /**
     * 还原自检 —— 🚨 提到断言层，让「上一轮脏数据」以<b>残留</b>的名义硬失败，
     * 而不是伪装成下一轮的业务缺陷（教训来自 {@code task-260901} 的 FT-3 证伪实验）。
     */
    protected void assertNoResidue(List<Fx> done) {
        for (Fx fx : done) {
            String c = fx.customerNo();
            assertEquals(0, count("SELECT count(*) FROM material_bom_item WHERE customer_no='" + c + "'"),
                    "还原自检：material_bom_item 仍有 " + c + " 的残留");
            assertEquals(0, count("SELECT count(*) FROM element_bom_item WHERE customer_no='" + c + "'"),
                    "还原自检：element_bom_item 仍有 " + c + " 的残留");
            assertEquals(0, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + c + "'"),
                    "还原自检：sel_part_signature 仍有 " + c + " 的残留");
            assertEquals(0, count("SELECT count(*) FROM material_customer_map WHERE customer_no='" + c + "'"),
                    "还原自检：material_customer_map 仍有 " + c + " 的残留");
            assertEquals(0, count("SELECT count(*) FROM customer WHERE code='" + c + "'"),
                    "还原自检：customer 仍有 " + c + " 的残留");
        }
        // 🚨 只认本轮 RUN_ID：另一轮同套件可能正在并发跑，它的在途数据不是我的残留。
        //    第六轮实测就因为这个全局判据，把别人的在途材质当成我的残留报了红。
        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE code LIKE '%-" + RUN_ID + "'"),
                "还原自检：material_recipe 仍有本轮（RUN_ID=" + RUN_ID + "）的残留");
        assertEquals(0, count("SELECT count(*) FROM sel_template WHERE name LIKE '%-" + RUN_ID + "'"),
                "还原自检：sel_template 仍有本轮（RUN_ID=" + RUN_ID + "）的残留");
    }
}
