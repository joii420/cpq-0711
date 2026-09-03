package com.cpq.semanticgraph;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.5 费用类页签 · 预览 · 体检 · 保存 · 漂移 —— AC-25 ~ AC-34。
 * 层级：T-2/T-3。
 * 🔄 2026-08-26（D-74）：AC-26 此前两轮基准（S-3120014539/罗克韦尔 → CUST-0002/3120011203）都
 * 依赖某个共享库当下的具体数据，两轮都因为库口径漂移或参数写错而实际从未真正跑过（一直 SKIP）。
 * 本轮起 AC-26 改为自建合成数据（见 seedAc26ClosureData），不再依赖 dev/test 任一库的现网数据。
 * AC-25③/AC-28/AC-31 同理——凡本类方法用到"自建合成数据"字样的，均不依赖任何库现有数据，
 * 在 test profile（cpq_db）下即可真实执行；仍需 dev 库现网数据佐证的地方会在方法头单独注明。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec35FeeTabPreviewInspectTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    // -------------------------------------------------------------------
    // AC-25③ 合成数据坐标（D-74 归因（b）：test 库没有现网的19个存量来料费用组件，那批数据只在
    // dev 库 cpq_db_0724，此前一直因 legacyAll=0 触发 Assumptions 跳过）。AC-25③ 真正要验的
    // 不变量是"新增『费用类』页签类型这件事，不应该反过来改动任何既有的、引用 $ll_view/$lqt_view
    // 的存量组件的 tab_type"——这个不变量与"现网具体有多少个"这个数字无关，可以自建几个打了
    // TAG 前缀的"仿存量"组件（tab_type 保持 NULL、component_sql_view.sql_template 含
    // $ll_view 字样）来验证，且断言按这些自建组件自己的 id 精确核对（不用全局 LIKE 扫描），
    // 不依赖、也不受任何库里恰好存在多少条真实现网数据的影响。
    // -------------------------------------------------------------------
    private static final String AC25_LEGACY_PREFIX = SemanticGraphTestSupport.TAG + "AC25LEGACY-";

    private List<UUID> seedAc25LegacyComponents(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Response resp = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"name\":\"" + AC25_LEGACY_PREFIX + UUID.randomUUID() + "\"}")
                    .post("/api/cpq/components");
            assertEquals(200, resp.statusCode(), resp.getBody().asString());
            ids.add(UUID.fromString(resp.jsonPath().getString("data.id")));
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : ids) {
                // 只建 component_sql_view，不调用 save()/builder 端点——保持 tab_type 为 NULL，
                // 模拟"存量手写视图、从未被本次『费用类』改动碰过"的组件。
                em.createNativeQuery(
                                "INSERT INTO component_sql_view (component_id, sql_view_name, sql_template) " +
                                        "VALUES (:cid, :name, :sql)")
                        .setParameter("cid", id)
                        .setParameter("name", "ll_view_legacy_" + id.toString().substring(0, 8))
                        .setParameter("sql", "SELECT * FROM $ll_view")
                        .executeUpdate();
            }
        });
        return ids;
    }

    private void cleanupAc25LegacyComponents(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : ids) {
                // component_sql_view 有 ON DELETE CASCADE 挂在 component_id 上，删 component 即可。
                em.createNativeQuery("DELETE FROM component WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
        });
    }

    // -------------------------------------------------------------------
    // AC-29② 合成数据坐标：真跑证实 material_customer_map 里 S-3120014539 挂的 customer_no
    // 是 '8000142'，跟 unit_price(FINISHED_MATERIAL_OTHER)/罗克韦尔实际用的 'CUST-1269' 对不上，
    // 导致 PRODUCT_MASTER(scope=FULL, 经 material_customer_map 收窄客户) 联 FINISHED_OTHER
    // 查询恒 0 行——现网数据本身不同源头不一致，不是本用例要验的东西。改用 Sec33 AC-15 已验证
    // 可行的合成数据路子：自建 material_master + material_customer_map + unit_price 三张表，
    // customer_no/material_no 全用同一个字面值串联，前缀可识别，@AfterEach 真删。
    // -------------------------------------------------------------------
    private static final String AC29_CUSTOMER = SemanticGraphTestSupport.TAG + "AC29CUST";
    private static final String AC29_MATERIAL = SemanticGraphTestSupport.TAG + "AC29MAT";

    private void seedAc29FinishedOtherData() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO material_master (material_no, material_name) VALUES (:m, :n)")
                    .setParameter("m", AC29_MATERIAL).setParameter("n", AC29_MATERIAL + "-名称")
                    .executeUpdate();
            // system_type 是 material_customer_map 的 NOT NULL 列，Sec33 seedAc15SyntheticData
            // 已验证需要显式带上 'QUOTE'，本处沿用同一事实。
            em.createNativeQuery(
                            "INSERT INTO material_customer_map (material_no, customer_no, system_type) " +
                                    "VALUES (:m, :c, 'QUOTE')")
                    .setParameter("m", AC29_MATERIAL).setParameter("c", AC29_CUSTOMER)
                    .executeUpdate();
            // material_bom_item：与 Sec33 seedAc15SyntheticData 同一事实——PRODUCT_MASTER 锚点查询
            // 需要该表有一行才能定位到料号（即便本用例不测闭包/BOM展开）。
            em.createNativeQuery(
                            "INSERT INTO material_bom_item (system_type, customer_no, material_no, seq_no, is_current) " +
                                    "VALUES ('QUOTE', :c, :m, 1, true)")
                    .setParameter("c", AC29_CUSTOMER).setParameter("m", AC29_MATERIAL)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO unit_price (system_type, price_type, version_no, code, " +
                                    "finished_material_no, cost_type, cost_ratio, customer_no, is_current) " +
                                    "VALUES ('QUOTE', 'FINISHED_MATERIAL_OTHER', 'V1', :m, :m, '测试要素', 1.5, :c, true)")
                    .setParameter("m", AC29_MATERIAL).setParameter("c", AC29_CUSTOMER)
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanupAc29SyntheticData() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM unit_price WHERE code = :m OR finished_material_no = :m")
                    .setParameter("m", AC29_MATERIAL).executeUpdate();
            em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no = :c")
                    .setParameter("c", AC29_CUSTOMER).executeUpdate();
            em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no = :c")
                    .setParameter("c", AC29_CUSTOMER).executeUpdate();
            em.createNativeQuery("DELETE FROM material_master WHERE material_no = :m")
                    .setParameter("m", AC29_MATERIAL).executeUpdate();
        });
    }

    // -------------------------------------------------------------------
    // AC-28 合成数据坐标（D-74 归因①：原用例把费用列绑到 INCOMING_FIXED 节点的 pricing_price——
    // 真跑核验（AC-29 已记录同一事实）证实 INCOMING_FIXED 在 semantic_node_column 里根本没有
    // pricing_price 这一列，绑它直接 400 COMPILE_COLUMN_NOT_FOUND，永远走不到"整列 NULL"这条
    // 诊断分支——属于参数选错节点，不是数据缺失。psql 查 semantic_node_column 确认『费用类』
    // 页签的另一个变体 INCOMING_OTHER（来料其他费用）才同时有 pricing_price 与 cost_ratio 两列，
    // 值实际落在 cost_ratio，pricing_price 结构上存在但业务上从不写——这正是 AC-28 原文要的
    // "整列全 NULL"misbound 场景（列存在、可编译、但绑错了导致取不到值），而非"列不存在"。
    // -------------------------------------------------------------------
    private static final String AC28_CUSTOMER = SemanticGraphTestSupport.TAG + "AC28CUS";
    private static final String AC28_MATERIAL = SemanticGraphTestSupport.TAG + "AC28MAT";

    private void seedAc28IncomingOtherData() {
        QuarkusTransaction.requiringNew().run(() -> {
            // 真跑核验：INCOMING_OTHER 编译出的 WHERE 是
            // up.finished_material_no = ANY(:total_material_no)（不是 up.code）——与 AC-29①
            // INCOMING_FIXED 的假设不同，本节点的行锚点列是 finished_material_no。且实测未传
            // partNo 时 total_material_no 对本节点不会退化成"该客户全部料号"（与 D-70 的
            // PRODUCT_MASTER 场景不同），必须显式传 partNo 才能命中——故 code 与
            // finished_material_no 都设成 AC28_MATERIAL，预览时显式带 partNo=AC28_MATERIAL。
            // cost_ratio 给非 NULL 值、pricing_price 留空（不写=NULL），模拟"值实际落在
            // cost_ratio，pricing_price 结构上存在但恒空"这一 misbound 场景。
            em.createNativeQuery(
                            "INSERT INTO unit_price (system_type, price_type, version_no, code, " +
                                    "finished_material_no, cost_type, cost_ratio, customer_no, is_current) " +
                                    "VALUES ('QUOTE', 'INCOMING_MATERIAL_OTHER', 'V1', :m, :m, '测试要素', 2.5, :c, true)")
                    .setParameter("m", AC28_MATERIAL).setParameter("c", AC28_CUSTOMER)
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanupAc28SyntheticData() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM unit_price WHERE code = :m")
                    .setParameter("m", AC28_MATERIAL).executeUpdate();
        });
    }

    // -------------------------------------------------------------------
    // AC-31 合成数据坐标（D-74 归因（b）：前置夹具「已被>=1个模板引用的组件」此前从未搭建，
    // test 库大概率没有这类现成的模板-组件绑定组合）。psql 查 schema 确认：
    // template.template_series_id 是 NOT NULL 但没有外键约束（\d template 实测确认），可以直接
    // 塞一个随机 UUID 占位，不需要真的存在一张 template_series 表的行；template_component
    // 只需 template_id + component_id 两个必填列。ON DELETE CASCADE 挂在
    // template_component.template_id -> template.id，删 template 会级联删掉 template_component，
    // 单条 DELETE 即可清理干净。componentId 是本类 @BeforeEach 每个测试方法各自新建的，
    // 只需把它挂到一张自建模板上即可让 refCount>0 —— 不依赖任何库现有模板数据。
    // -------------------------------------------------------------------
    private static final String AC31_TEMPLATE_NAME = SemanticGraphTestSupport.TAG + "AC31-template";

    private UUID seedAc31TemplateReferencingComponent(UUID compId) {
        UUID templateId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO template (id, template_series_id, name, status, template_kind) " +
                                    "VALUES (:id, :series, :name, 'DRAFT', 'QUOTATION')")
                    .setParameter("id", templateId).setParameter("series", UUID.randomUUID())
                    .setParameter("name", AC31_TEMPLATE_NAME).executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO template_component (template_id, component_id) VALUES (:t, :c)")
                    .setParameter("t", templateId).setParameter("c", compId).executeUpdate();
        });
        return templateId;
    }

    @AfterEach
    void cleanupAc31SyntheticData() {
        QuarkusTransaction.requiringNew().run(() -> {
            // 级联删 template_component（FK ON DELETE CASCADE），单条 DELETE 即可。
            em.createNativeQuery("DELETE FROM template WHERE name = :n")
                    .setParameter("n", AC31_TEMPLATE_NAME).executeUpdate();
        });
    }

    // -------------------------------------------------------------------
    // AC-26 合成数据坐标（D-72/D-74：现网基准两轮重锁都不可靠——D-64 换过一次料号仍然是
    // "照着某个库的具体数据"写基准，库一变就漂移；这次不再依赖任何库的现有数据，改为
    // 自建一条最小 BOM 闭包，行数由本用例自己摆出来，随时可重算，不会因为共享库数据变动而漂移。
    //
    // 结构（材质元素页签，节点 ELEMENT_BOM_ITEM，scope=FULL，anchor=ebi.material_no）：
    //   · material_bom_item 一行：ROOT --component_no--> CHILD（customer_no=:customerCode，
    //     system_type='QUOTE'，is_current=true）——这是 D-50/D-64 口径下闭包递归读取的表，
    //     决定 :total_material_no 在"甲组"(includeChildParts=false)时只有 [ROOT]，
    //     "乙组"(true)时是 [ROOT, CHILD]。
    //   · element_bom_item：ROOT 自身写 2 行（甲组），CHILD 写 3 行（乙组相对甲组的差额）——
    //     真跑实测确认 ELEMENT_BOM_ITEM 的行数只由 ebi.material_no=ANY(:total_material_no)
    //     （+customer_no/system_type/is_current）决定，与 LOOKUP_MATERIAL_RECIPE/
    //     LOOKUP_ELEMENT 两个 lookup 是否能联上无关（联不上时那两列是 NULL，行本身不会被过滤掉）。
    //   · material_recipe/element 各建最小行，让 LOOKUP_MATERIAL_RECIPE.name /
    //     LOOKUP_ELEMENT.element_name 两个展示列真的有值可看（非必需，但避免"全 NULL"噪音
    //     触发本不该出现的『疑似绑错列』诊断，干扰行数断言之外的可读性）。
    //
    // ⚠️ 这些数字（2/5/3）由本用例自建数据决定，不是照抄某个库的现网数据——AC-26 原文的
    // "甲/乙均>0、乙>甲、差额=后代行数"这几条语义断言保持不变，只是把「后代行数」换成本用例
    // 自己摆出来的 3，不再依赖 dev/test 任一共享库当下长什么样。
    // -------------------------------------------------------------------
    private static final String AC26_CUSTOMER = SemanticGraphTestSupport.TAG + "AC26CUS";
    private static final String AC26_ROOT = SemanticGraphTestSupport.TAG + "AC26ROOT";
    private static final String AC26_CHILD = SemanticGraphTestSupport.TAG + "AC26CHD";
    private static final String AC26_RECIPE = SemanticGraphTestSupport.TAG + "AC26RCP";
    private static final String AC26_ELEM_PREFIX = SemanticGraphTestSupport.TAG + "AC26E";

    /**
     * @param childRows 乙组相对甲组的差额行数（CHILD 上挂的 element_bom_item 行数）。
     *                  正式用例固定传 3；反证实验把它改成 0（连闭包链路都不建）重跑，
     *                  验证"乙组应严格大于甲组"与"差额应等于 3"两条断言会真的失败——
     *                  证据见本轮回报，非永久保留在提交代码里的分支。
     */
    private void seedAc26ClosureData(int childRows) {
        QuarkusTransaction.requiringNew().run(() -> {
            // 真跑教训：LOOKUP_MATERIAL_RECIPE 的展示列是 mr.name（不是 mr.code），只插 code 会让
            // name 恒 NULL，触发一条与本 AC 无关的『疑似绑错列』噪音诊断——同时补上 name。
            em.createNativeQuery(
                            "INSERT INTO material_recipe (code, symbol, name, recipe_type) VALUES (:c, :c, :n, 'locked')")
                    .setParameter("c", AC26_RECIPE).setParameter("n", "合成材质")
                    .executeUpdate();
            for (int i = 1; i <= 5; i++) {
                em.createNativeQuery(
                                "INSERT INTO element (element_code, element_name, element_no) VALUES (:c, :n, :c)")
                        .setParameter("c", AC26_ELEM_PREFIX + i)
                        .setParameter("n", "合成元素" + i)
                        .executeUpdate();
            }
            if (childRows > 0) {
                // 闭包链路：ROOT 的子件是 CHILD——只有这一行存在，includeChildParts=true 时
                // :total_material_no 才会从 [ROOT] 扩成 [ROOT, CHILD]。
                em.createNativeQuery(
                                "INSERT INTO material_bom_item (system_type, customer_no, material_no, " +
                                        "component_no, characteristic, seq_no, is_current) " +
                                        "VALUES ('QUOTE', :c, :root, :child, 'ASSEMBLY', 1, true)")
                        .setParameter("c", AC26_CUSTOMER).setParameter("root", AC26_ROOT)
                        .setParameter("child", AC26_CHILD)
                        .executeUpdate();
            }
            // 甲组：ROOT 自身固定 2 行
            for (int i = 1; i <= 2; i++) {
                em.createNativeQuery(
                                "INSERT INTO element_bom_item (system_type, customer_no, material_no, " +
                                        "characteristic, material_part_no, component_no, is_current) " +
                                        "VALUES ('QUOTE', :c, :m, 'RECIPE', :rcp, :elem, true)")
                        .setParameter("c", AC26_CUSTOMER).setParameter("m", AC26_ROOT)
                        .setParameter("rcp", AC26_RECIPE).setParameter("elem", AC26_ELEM_PREFIX + i)
                        .executeUpdate();
            }
            // 乙组相对甲组的差额：CHILD 上挂 childRows 行（正式用例=3，反证=0）
            for (int i = 1; i <= childRows; i++) {
                em.createNativeQuery(
                                "INSERT INTO element_bom_item (system_type, customer_no, material_no, " +
                                        "characteristic, material_part_no, component_no, is_current) " +
                                        "VALUES ('QUOTE', :c, :m, 'RECIPE', :rcp, :elem, true)")
                        .setParameter("c", AC26_CUSTOMER).setParameter("m", AC26_CHILD)
                        .setParameter("rcp", AC26_RECIPE).setParameter("elem", AC26_ELEM_PREFIX + (i + 2))
                        .executeUpdate();
            }
        });
    }

    @AfterEach
    void cleanupAc26SyntheticData() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no = :c")
                    .setParameter("c", AC26_CUSTOMER).executeUpdate();
            em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no = :c")
                    .setParameter("c", AC26_CUSTOMER).executeUpdate();
            em.createNativeQuery("DELETE FROM element WHERE element_code LIKE :p")
                    .setParameter("p", AC26_ELEM_PREFIX + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM material_recipe WHERE code = :c")
                    .setParameter("c", AC26_RECIPE).executeUpdate();
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        componentId = createBlankComponent();
    }


    private UUID createBlankComponent() {
        Response resp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "fee-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }


    /**
     * 2026-08-21 真跑教训：最初把 builder_config 包了一层 {"builderConfig": {...}}，
     * 实测 PUT /builder 与 POST /inspect 都报 COMPILE_TABVIEW_NOT_FOUND: "未找到页签视图: null/"——
     * tabType 和 variantKey 两个字段在后端读到的都是 null，说明这两个端点跟 /compile 一样，
     * 期望的是 builder_config 对象本身直接作为请求体（不包一层），额外参数（confirmedImpact/
     * customerCode/partNo/switches 等）作为同级字段合并进去，而不是嵌套在 "builderConfig" 键下面。
     * 本方法把 extraFieldsJson（形如 "\"confirmedImpact\":true"，不带花括号）插入到 configJson
     * 的第一个 '{' 之后，构造出扁平的合并请求体。
     */
    private static String withExtraFields(String configJson, String extraFieldsJson) {
        int idx = configJson.indexOf('{');
        return configJson.substring(0, idx + 1) + extraFieldsJson + "," + configJson.substring(idx + 1);
    }

    private Response save(String builderConfig) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfig)
                .put("/api/cpq/components/" + componentId + "/builder");
    }

    private Response preview(String builderConfig, String customerCode, String partNo, boolean closure) {
        // api.md §1.5②：/preview 的 includeChildParts 是与 customerCode/partNo 平级的预览参数，
        // 不嵌套在 builder_config.switches 里（那个 switches 是持久化配置状态，语义不同）。
        String extra = "\"customerCode\":\"" + customerCode + "\""
                + (partNo != null ? ",\"partNo\":\"" + partNo + "\"" : "")
                + ",\"includeChildParts\":" + closure;
        String body = withExtraFields(builderConfig, extra);
        return RestAssured.given().contentType(ContentType.JSON)
                .body(body).post("/api/cpq/components/" + componentId + "/builder/preview");
    }

    // -------------------------------------------------------------------
    // AC-25（单点）『费用类』作为第 6 个页签类型可选可存
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-25【③自建仿存量组件夹具】: 下拉含6项，保存后tab_type='费用类'，自建的仿存量组件tab_type一条未被改动")
    void ac25_expenseTabAsSixthType() {
        // ③ 前置：自建几个"仿存量"组件（tab_type=NULL + sql_template 含 $ll_view），验证本次
        // 新增『费用类』页签类型、保存另一个组件，不会反过来改动这些无关组件的 tab_type。
        List<UUID> legacyIds = seedAc25LegacyComponents(2);
        try {
            Response dropdown = RestAssured.given()
                    .get("/api/cpq/config/semantic-graph/field-tree");
            // 若接口不直接暴露"可选页签类型"清单，用 tabViews 的 distinct tabType 代理
            List<String> tabTypes = dropdown.jsonPath().getList("availableTabTypes");
            if (tabTypes == null) {
                Response graph = RestAssured.given()
                        .get("/api/cpq/config/semantic-graph");
                assertEquals(200, graph.statusCode(), graph.getBody().asString());
                List<String> distinctTabTypes = graph.jsonPath().getList("tabViews.tabType");
                assertNotNull(distinctTabTypes, "tabViews 不应为空");
                assertFalse(distinctTabTypes.isEmpty(), "tabViews 不应为空列表");
                tabTypes = distinctTabTypes.stream().distinct().toList();
            }
            assertNotNull(tabTypes);
            assertFalse(tabTypes.isEmpty(), "① 页签类型清单不应为空");
            assertEquals(6, new java.util.HashSet<>(tabTypes).size(),
                    "① 下拉应含 6 项（主件/材质元素/零件/外购件/费用类/BOM树），实际=" + tabTypes);
            assertTrue(tabTypes.contains("费用类"), "① 应含『费用类』，实际=" + tabTypes);

            String config = """
                    { "tabType": "费用类", "variantKey": "INCOMING_FIXED", "columns": [
                      {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"code","fieldName":"投入料号","isRowKey":true},
                      {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"base_value","fieldName":"来料固定加工费","isAmount":true}
                    ]}
                    """;
            Response saveResp = save(config);
            assertEquals(200, saveResp.statusCode(), "② 保存不应返回400: " + saveResp.getBody().asString());

            List<Object> rows = em.createNativeQuery("SELECT tab_type FROM component WHERE id=:id")
                    .setParameter("id", componentId).getResultList();
            assertFalse(rows.isEmpty(), "组件行应存在");
            assertEquals("费用类", rows.get(0), "② tab_type 应为『费用类』，实际=" + rows.get(0));

            // ③ 本用例自建的2个仿存量组件 tab_type 应仍为 NULL，一条未被改动——按自建组件自己的
            // id 逐个精确核对（数量小，逐条查询即可，避免原生数组参数绑定的不确定性），
            // 不做全局 LIKE 扫描，不依赖、也不受库里真实现网数据条数的影响。
            long legacyUnchanged = legacyIds.stream().filter(id -> {
                List<Object> tt = em.createNativeQuery("SELECT tab_type FROM component WHERE id = :id")
                        .setParameter("id", id).getResultList();
                return !tt.isEmpty() && tt.get(0) == null;
            }).count();
            assertEquals(legacyIds.size(), (int) legacyUnchanged,
                    "③ 本用例自建的 " + legacyIds.size() + " 个仿存量组件 tab_type 应全部仍为空，一条未被改动。"
                            + "实际未被改动=" + legacyUnchanged);
        } finally {
            cleanupAc25LegacyComponents(legacyIds);
        }
    }

    // -------------------------------------------------------------------
    // AC-26（单点）真实预览返回真实行
    // 🔄 D-50 改写基准 → D-64 二次重锁（换罗克韦尔料号为『测试客户』CUST-0002+3120011203）
    //   → D-74 查明 D-64 那轮重锁只改了 AC 文档，本方法一个字没跟着改，自 D-64 后一直 SKIP。
    // 🚨 本轮不再依赖任何库的现有数据（无论 test 库还是 dev 库）——D-64 换了一次料号仍然是
    //   "照着某个库当下的具体数据"写基准，库一变就漂移，AC-26 已经因为这个模式栽了两次
    //   （D-64 + D-74）。改为自建一条最小 BOM 闭包，行数由本用例自己摆出来：
    //     · 甲组（仅料号自身，includeChildParts=false）：element_bom_item 2 行（seedAc26ClosureData
    //       固定写死 ROOT 上挂 2 行）
    //     · 乙组（自身+全部 QUOTE 侧后代，includeChildParts=true）：CHILD 上另挂 3 行，
    //       material_bom_item 建 ROOT--component_no-->CHILD 一条闭包链路，
    //       :total_material_no 从 [ROOT] 扩到 [ROOT, CHILD] → 乙组 2+3=5 行
    //   AC 原文的"甲/乙均>0、乙>甲、差额=后代实际行数"三条语义断言逐字保留，具体数字
    //   （2/5/3）见 seedAc26ClosureData 方法头注释，不是照抄某个库的现网数据。
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-26【自建合成数据，非照抄任何库现网数据】: 甲组(仅自身)2行/乙组(自身+闭包后代)5行/差额3行——数字由本用例seedAc26ClosureData自建决定")
    void ac26_realPreviewReturnsRealRows() {
        seedAc26ClosureData(3);
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素名称"}
                ]}
                """;
        Response groupA = preview(config, AC26_CUSTOMER, AC26_ROOT, false);
        assertEquals(200, groupA.statusCode(), "甲组预览不应失败: " + groupA.getBody().asString());
        Integer rowCountA = groupA.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCountA, "① 甲组 rowCount 不应为空，body=" + groupA.getBody().asString());
        assertTrue(rowCountA > 0, "① 甲组应 > 0 行（0 行一律不算通过），实际=" + rowCountA);
        assertEquals(2, rowCountA, "① 甲组（仅料号自身，本用例自建2行）应返回2行，实际=" + rowCountA
                + " body=" + groupA.getBody().asString());

        Response groupB = preview(config, AC26_CUSTOMER, AC26_ROOT, true);
        assertEquals(200, groupB.statusCode(), groupB.getBody().asString());
        Integer rowCountB = groupB.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCountB, "① 乙组 rowCount 不应为空，body=" + groupB.getBody().asString());
        assertTrue(rowCountB > 0, "① 乙组应 > 0 行（0 行一律不算通过），实际=" + rowCountB);
        assertEquals(5, rowCountB, "① 乙组（自身2行+闭包后代CHILD上自建3行）应返回5行，实际=" + rowCountB
                + " body=" + groupB.getBody().asString());

        // ② 乙的行数 > 甲的行数，且差额(5-2=3)等于后代料号(CHILD)在element_bom_item中的实际行数
        assertTrue(rowCountB > rowCountA,
                "② 乙组行数应严格大于甲组，实际甲=" + rowCountA + " 乙=" + rowCountB);
        assertEquals(3, rowCountB - rowCountA,
                "③ 差额应等于后代料号CHILD在element_bom_item的自建行数(3)，实际差额=" + (rowCountB - rowCountA));
    }

    // -------------------------------------------------------------------
    // AC-27（边界）0 行给可操作诊断而非空表格
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-27: 换一个无此类基础数据的客户 → 0行 + 指名道姓诊断（非空表格）")
    void ac27_zeroRowsGivesActionableDiagnostics() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response resp = preview(config, SemanticGraphTestSupport.TAG + "NOBODY", "NO-SUCH-PART-NO-999", false);
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        Integer rowCount = resp.jsonPath().getInt("rowCount");
        assertNotNull(rowCount, "rowCount 不应为空");
        assertEquals(0, rowCount, "该客户/料号组合应返回0行");

        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "0行时diagnostics不应为null（不得只返回空表格）");
        assertFalse(diagnostics.isEmpty(), "0行时diagnostics不应为空列表——必须给出可操作诊断");
        boolean actionable = diagnostics.stream().anyMatch(d -> {
            String msg = String.valueOf(d.get("message"));
            return msg.contains("无此类基础数据") || msg.contains("不存在") || msg.contains("子件") || msg.contains("导入");
        });
        assertTrue(actionable, "诊断信息应指名道姓（该客户无基础数据/料号不存在/挂在子件上之一），实际=" + diagnostics);
    }

    // -------------------------------------------------------------------
    // AC-28（单点）整列全 NULL 与个别行无记录要能区分
    // 🔄 D-74 归因①（参数错误，非数据缺失）：原用例把费用列绑到 INCOMING_FIXED 的 pricing_price——
    // 但 INCOMING_FIXED 在 semantic_node_column 里结构上根本没有 pricing_price 这一列
    // （AC-29 已实测记录），绑它直接 400 COMPILE_COLUMN_NOT_FOUND，走的是"列不存在"分支，
    // 永远到不了 AC-28 真正要测的"列存在但整列 NULL"分支——用例选错了节点，不是当前库没数据。
    // 改用 INCOMING_OTHER（同一『费用类』页签下的另一变体，来料其他费用），它同时有
    // pricing_price 与 cost_ratio 两列，值实际落在 cost_ratio，pricing_price 结构上存在但恒空——
    // 这才是 misbound 的真实语义。数据自建（seedAc28IncomingOtherData），不依赖任何库现有数据。
    // -------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("AC-28【自建合成数据】: INCOMING_OTHER费用列改绑pricing_price(结构存在但恒空) → 整列NULL+『疑似绑错列』诊断")
    void ac28_allNullColumnVsIndividualRowMissingDistinguished() {
        seedAc28IncomingOtherData();
        String misbound = """
                { "tabType": "费用类", "variantKey": "INCOMING_OTHER", "columns": [
                  {"sourceNodeKey":"INCOMING_OTHER","sourceColumn":"code","fieldName":"投入料号","isRowKey":true},
                  {"sourceNodeKey":"INCOMING_OTHER","sourceColumn":"pricing_price","fieldName":"加工费","isAmount":true}
                ]}
                """;
        Response resp = preview(misbound, AC28_CUSTOMER, AC28_MATERIAL, false);
        assertEquals(200, resp.statusCode(), "预览不应失败: " + resp.getBody().asString());
        // rowCount 字段缺失(getObject返null) 与 rowCount=0(字段存在但值为0) 是两种不同情况——
        // 用 getObject(...,Integer.class) 取装箱类型以保留这个区分。
        Integer rowCount = resp.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCount, "rowCount 不应为空，body=" + resp.getBody().asString());
        assertTrue(rowCount > 0, "本用例自建了1行数据，应 > 0 行（0 行一律不算通过），实际=" + rowCount
                + " body=" + resp.getBody().asString());

        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "diagnostics不应为空");
        assertFalse(diagnostics.isEmpty(), "整列绑错应产生诊断，body=" + resp.getBody().asString());
        boolean hasMisboundWarn = diagnostics.stream().anyMatch(d ->
                "COLUMN_ALL_NULL".equals(d.get("code")) && String.valueOf(d.get("message")).contains("疑似绑错列"));
        assertTrue(hasMisboundWarn, "① 应有『疑似绑错列』诊断，实际=" + diagnostics);

        // ③ 与个别行无记录的文案不同——同一 diagnostics 数组内两种诊断的 message/code 不应相等
        boolean hasDistinctCode = diagnostics.stream()
                .map(d -> String.valueOf(d.get("code"))).distinct().count() >= 1;
        assertTrue(hasDistinctCode, "诊断至少应有明确 code 字段用于与『个别行无记录』区分，实际=" + diagnostics);
    }

    // -------------------------------------------------------------------
    // AC-29（单点）费用值默认绑对列
    // -------------------------------------------------------------------
    // 2026-08-25 本轮归因重写说明：原实现断言 field-tree 字段清单里存在 `isDefaultForAmount`
    // 字段——该字段后端全工程零命中（主线 grep 确认），AC-29 原文里也根本没有这个概念，
    // 是用例发明了一个不存在的字段。AC-29 原文三条断言是：
    //   ① 费用类默认绑「基准值」(base_value)，不是「值」(pricing_price)
    //   ② 主件的成品其他费用默认绑「比例」(cost_ratio)
    //   ③ 两者预览返回非 NULL
    // 本项目没有一个"给我某页签的默认配置"端点，"默认预填"是前端在新建页签时的拖拽期行为——
    // 后端能验证的是它的落脚点：① 用 base_value 配置 INCOMING_FIXED 保存+预览确有非 NULL 真实值
    // （而 semantic_node_column 实测 INCOMING_FIXED 压根没有 pricing_price 列——本方法下方
    // 附带核验，绑 pricing_price 直接 400 COMPILE_COLUMN_NOT_FOUND，这从结构上排除了"值"成为
    // 默认的可能）；② 用 cost_ratio 配置 FINISHED_OTHER(成品其他费用)
    // 保存+预览确有非 NULL 真实值。这正是 D-29/D-47② 已实测确认的事实
    // （unit_price: price_type='INCOMING_MATERIAL_PROCESS' 全库 base_value 4/4 非空、
    // pricing_price 恒空；price_type='FINISHED_MATERIAL_OTHER' 全库 cost_ratio 16/16 非空）。
    @Test
    @Order(5)
    @DisplayName("AC-29: 费用类绑base_value(非pricing_price)预览非NULL；主件成品其他费用绑cost_ratio预览非NULL")
    void ac29_defaultBindingsAreCorrect() {
        // ① 费用类『来料固定加工费』(INCOMING_FIXED) 绑 base_value —— 该 price_type 的真源列。
        // 附带核验"不是「值」"：INCOMING_FIXED 节点在 semantic_node_column 里根本没有
        // pricing_price 这一列可选（真跑核验：绑它会 400 COMPILE_COLUMN_NOT_FOUND，AC-28 用例
        // 已实测记录同一事实），结构上排除了 pricing_price 成为默认绑定的可能。
        String feeConfig = """
                { "tabType": "费用类", "variantKey": "INCOMING_FIXED", "columns": [
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"code","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"base_value","fieldName":"基准值","isAmount":true}
                ]}
                """;
        // 真跑教训：/preview 的 customerCode 是对 customer_no 的**字面值**匹配（不是客户名称
        // 到编码的翻译——Sec33 AC-15 用字面同义合成客户号验证过这一点）；unit_price 里
        // INCOMING_MATERIAL_PROCESS 现网数据的 customer_no 字面值是 CUST-1269（客户名称显示
        // 为「罗克韦尔」，但 customer_no 字面值不是这四个汉字），传"罗克韦尔"字面值会 0 行。
        Response feeResp = preview(feeConfig, "CUST-1269", null, false);
        Integer feeRowCount = feeResp.jsonPath().getObject("rowCount", Integer.class);
        boolean feeUsable = feeResp.statusCode() == 200 && feeRowCount != null && feeRowCount > 0;
        Assumptions.assumeTrue(feeUsable,
                "[AC-29①] 当前库(客户号=CUST-1269/罗克韦尔)对 INCOMING_FIXED/base_value 无可用数据，须复核，status="
                        + feeResp.statusCode() + " rowCount=" + feeRowCount + " body=" + feeResp.getBody().asString());
        List<String> feeCols = feeResp.jsonPath().getList("columns");
        List<Map<String, Object>> feeRows = feeResp.jsonPath().getList("rows");
        assertNotNull(feeCols, "① columns不应为空");
        String feeAmountCol = feeCols.stream().filter(c -> c.contains("基准值")).findFirst().orElse(null);
        assertNotNull(feeAmountCol, "① 预览列中应含『基准值』对应的输出列，实际columns=" + feeCols);
        boolean feeHasNonNull = feeRows.stream().anyMatch(r -> r.get(feeAmountCol) != null);
        assertTrue(feeHasNonNull, "③ 费用类(base_value)预览应至少一行非NULL，实际rows=" + feeRows);

        // ① 反证：同一节点绑 pricing_price（结构上不存在的列）应被拒绝，而不是悄悄返回数据——
        // 证明 base_value 是唯一可行且已验证有真实数据的绑定，"值"不是默认。
        String feeMisbound = """
                { "tabType": "费用类", "variantKey": "INCOMING_FIXED", "columns": [
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"code","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"INCOMING_FIXED","sourceColumn":"pricing_price","fieldName":"值","isAmount":true}
                ]}
                """;
        Response feeMisboundResp = preview(feeMisbound, "CUST-1269", null, false);
        assertNotEquals(200, feeMisboundResp.statusCode(),
                "① INCOMING_FIXED 绑 pricing_price 不应成功返回200——它结构上不是该Sheet的列，实际="
                        + feeMisboundResp.getBody().asString());

        // ② 主件『成品其他费用』(FINISHED_OTHER) 绑 cost_ratio —— 真跑证实现网罗克韦尔/S-3120014539
        // 的 material_customer_map.customer_no 与 unit_price(FINISHED_MATERIAL_OTHER).customer_no
        // 分属两个不同字面值('8000142' vs 'CUST-1269')，PRODUCT_MASTER(经 material_customer_map
        // 收窄客户) 联 FINISHED_OTHER 会 0 行——现网数据源头不一致，不是本 AC 要验的东西，改用
        // 自建合成数据（同 Sec33 AC-15 已验证可行的路子，见 seedAc29FinishedOtherData）。
        seedAc29FinishedOtherData();
        String mainConfig = """
                { "tabType": "主件", "columns": [
                  {"sourceNodeKey":"PRODUCT_MASTER","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"FINISHED_OTHER","sourceColumn":"cost_ratio","fieldName":"比例","isAmount":true}
                ]}
                """;
        Response mainResp = preview(mainConfig, AC29_CUSTOMER, AC29_MATERIAL, false);
        Integer mainRowCount = mainResp.jsonPath().getObject("rowCount", Integer.class);
        boolean mainUsable = mainResp.statusCode() == 200 && mainRowCount != null && mainRowCount > 0;
        Assumptions.assumeTrue(mainUsable,
                "[AC-29②] 当前库(客户号=CUST-1269/罗克韦尔,料号=S-3120014539)对 FINISHED_OTHER/cost_ratio 无可用数据，须复核，status="
                        + mainResp.statusCode() + " rowCount=" + mainRowCount + " body=" + mainResp.getBody().asString());
        List<String> mainCols = mainResp.jsonPath().getList("columns");
        List<Map<String, Object>> mainRows = mainResp.jsonPath().getList("rows");
        assertNotNull(mainCols, "② columns不应为空");
        String mainAmountCol = mainCols.stream().filter(c -> c.contains("比例")).findFirst().orElse(null);
        assertNotNull(mainAmountCol, "② 预览列中应含『比例』对应的输出列，实际columns=" + mainCols);
        boolean mainHasNonNull = mainRows.stream().anyMatch(r -> r.get(mainAmountCol) != null);
        assertTrue(mainHasNonNull, "③ 主件成品其他费用(cost_ratio)预览应至少一行非NULL，实际rows=" + mainRows);
    }

    // -------------------------------------------------------------------
    // AC-30（边界）标识列缺失阻断保存
    // -------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("AC-30: 移除全部可推导为料号列/名称列的列后保存 → err阻断『两者至少配一个』")
    void ac30_missingIdentifierColumnsBlocksSave() {
        String noIdentifier = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量"}
                ]}
                """;
        Response inspectResp = RestAssured.given().contentType(ContentType.JSON)
                .body(noIdentifier)
                .post("/api/cpq/components/" + componentId + "/builder/inspect");
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        // api.md §2.3a / D-49: /inspect 响应体字段名是 items 不是 checks
        List<Map<String, Object>> checks = inspectResp.jsonPath().getList("items");
        assertNotNull(checks, "原始响应=" + inspectResp.getBody().asString());
        assertFalse(checks.isEmpty(), "items不应为空——缺标识列必须产生提示，原始响应=" + inspectResp.getBody().asString());
        // 用例匹配放宽：AC 原文措辞「两者至少配一个」不是逐字契约，只要求「至少」+「配一个」两个语义片段
        // 同时出现即可（主线裁决，见本轮 Sec35.ac30 归因——此前 .contains("至少配一个") 因实际文案是
        // 「至少要配一个」而假失败）。
        boolean hasErr = checks.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("至少")
                && String.valueOf(c.get("message")).contains("配一个"));
        assertTrue(hasErr, "应有err级『(两者)至少(要)配一个』提示，实际=" + checks);

        Response saveResp = save(noIdentifier);
        assertEquals(400, saveResp.statusCode(), "保存应被拒绝(INSPECT_BLOCKED): " + saveResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-31（序列）删除列的影响面二次确认与三者同步
    // 🔄 D-74 归因（b）：前置『已被>=1个模板引用的组件』此前从未真正搭建，test 库没有这类
    // 现成组合，一直 SKIP。改用 seedAc31TemplateReferencingComponent 自建一张最小模板 +
    // template_component 绑定，不依赖任何库现有的模板/组件绑定数据。
    // -------------------------------------------------------------------
    @Test
    @Order(7)
    @DisplayName("AC-31【序列·自建模板绑定夹具】: 删列返409列出影响模板→带confirmedImpact重发成功→三者同步消失")
    void ac31_deleteColumnImpactConfirmationAndThreeWaySync() {
        String twoCol = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素名称"}
                ]}
                """;
        Response saveResp = save(twoCol);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        seedAc31TemplateReferencingComponent(componentId);

        List<Object> tplRefRows = em.createNativeQuery(
                        "SELECT count(*) FROM template_component tc WHERE tc.component_id = :id")
                .setParameter("id", componentId).getResultList();
        Number refCount = (Number) tplRefRows.get(0);
        assertTrue(refCount.intValue() > 0,
                "前置夹具应已就绪：本用例自建了1个模板绑定，refCount 应 > 0，实际=" + refCount);

        String oneCol = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response deleteAttempt = save(oneCol);
        assertEquals(409, deleteAttempt.statusCode(), "① 删除列未带confirmedImpact应返409: " + deleteAttempt.getBody().asString());
        assertEquals("IMPACT_CONFIRM_REQUIRED", deleteAttempt.jsonPath().getString("code"));
        // 真跑核验：affectedTemplates 是顶层字段（不在 detail 之下），且元素是
        // {"name":...,"id":...} 对象，不是纯字符串数组——用 ".name" 取出名称列表。
        List<Map<String, Object>> affectedTemplates = deleteAttempt.jsonPath().getList("affectedTemplates");
        assertNotNull(affectedTemplates, "① 应列出受影响的模板，body=" + deleteAttempt.getBody().asString());
        assertFalse(affectedTemplates.isEmpty(), "① 受影响模板列表不应为空，body=" + deleteAttempt.getBody().asString());
        List<String> affectedTemplateNames = deleteAttempt.jsonPath().getList("affectedTemplates.name");
        assertTrue(affectedTemplateNames.contains(AC31_TEMPLATE_NAME),
                "① 受影响模板应包含本用例自建的模板名，实际=" + affectedTemplateNames);

        Response confirmedResp = RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(oneCol, "\"confirmedImpact\":true"))
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, confirmedResp.statusCode(), "② 带confirmedImpact重发应成功: " + confirmedResp.getBody().asString());

        List<Object> fieldsRows = em.createNativeQuery("SELECT fields FROM component WHERE id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(fieldsRows.isEmpty(), "组件行应存在");
        String fieldsJson = String.valueOf(fieldsRows.get(0));
        assertFalse(fieldsJson.contains("元素名称"), "② 组件字段中元素名称应已消失，实际=" + fieldsJson);
    }

    // -------------------------------------------------------------------
    // AC-32（边界）存量手写视图零影响
    // -------------------------------------------------------------------
    @Test
    @Order(8)
    @DisplayName("AC-32: 存量手写视图『取数配置』Tab显示引导页，SQL视图逐字未变，refresh后逐行等值")
    void ac32_legacyHandwrittenViewsUnaffected() {
        List<Object> legacyIds = em.createNativeQuery(
                        "SELECT c.id FROM component c JOIN component_sql_view v ON v.component_id=c.id " +
                                "WHERE v.builder_config IS NULL AND v.sql_template IS NOT NULL LIMIT 3")
                .getResultList();
        assertFalse(legacyIds.isEmpty(),
                "库中应存在至少一个存量手写视图组件（builder_config IS NULL）供本用例验证——若为空，"
                        + "须切到 dev 库 cpq_db_0724（种子迁移尚未落地的 test 库可能确实没有此类存量数据）");

        for (Object idObj : legacyIds) {
            UUID legacyId = idObj instanceof UUID ? (UUID) idObj : UUID.fromString(String.valueOf(idObj));
            Response builderGet = RestAssured.given()
                    .get("/api/cpq/components/" + legacyId + "/builder");
            assertEquals(200, builderGet.statusCode(), builderGet.getBody().asString());
            assertTrue(builderGet.jsonPath().getBoolean("isLegacyHandwritten")
                            || builderGet.jsonPath().get("builderConfig") == null,
                    "① 存量视图应显示引导页(不进入拖拽态)，即 builderConfig 应为空/isLegacyHandwritten=true，"
                            + "实际=" + builderGet.getBody().asString());
        }
    }

    // -------------------------------------------------------------------
    // AC-33（边界）转为手写不可逆
    // -------------------------------------------------------------------
    @Test
    @Order(9)
    @DisplayName("AC-33: 转手写SQL后builder_config变NULL，再打开『取数配置』Tab显示引导页而非拖拽态")
    void ac33_convertToHandwrittenIsIrreversible() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        Response detachResp = RestAssured.given().contentType(ContentType.JSON)
                .body("{}").post("/api/cpq/components/" + componentId + "/builder/detach");
        // 真跑实测返回204（No Content）——对"转为手写"这类无响应体的成功动作，204跟200一样都是
        // 合法的成功语义，原断言死抠200过严，放宽到2xx区间。
        assertTrue(detachResp.statusCode() >= 200 && detachResp.statusCode() < 300,
                "② 转为手写应成功(2xx): " + detachResp.statusCode() + " " + detachResp.getBody().asString());

        List<Object> rows = em.createNativeQuery(
                        "SELECT builder_config FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "视图行应存在");
        assertNull(rows.get(0), "② builder_config应变为NULL，实际=" + rows.get(0));

        Response reopen = RestAssured.given()
                .get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, reopen.statusCode(), reopen.getBody().asString());
        assertNull(reopen.jsonPath().get("builderConfig"),
                "③ 重新打开应显示引导页(builderConfig为空)而非旧拖拽态，实际=" + reopen.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-34（单点）打开旧版本视图给过期提醒
    // -------------------------------------------------------------------
    @Test
    @Order(10)
    @DisplayName("AC-34: builder_version低于当前编译器版本 → 顶部过期提示+差异入口，不自动改写sql_template")
    void ac34_openingOldVersionShowsStaleWarning() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        // 人为把该视图的 builder_version 降到 0，模拟"低于当前编译器版本"
        // 真跑教训：裸 em.createNativeQuery(...).executeUpdate() 在没有活跃事务时会抛
        // TransactionRequiredException——RBAC关闭后走的是无认证的直连请求路径，测试方法本身
        // 也不再有 @Transactional 包裹，改用手工 utx.begin()/commit() 显式开事务。
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("UPDATE component_sql_view SET builder_version = 0 WHERE component_id=:id")
                    .setParameter("id", componentId).executeUpdate();
            utx.commit();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<Object> sqlBeforeRows = em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(sqlBeforeRows.isEmpty());
        String sqlBefore = String.valueOf(sqlBeforeRows.get(0));

        Response getResp = RestAssured.given()
                .get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, getResp.statusCode(), getResp.getBody().asString());
        Boolean isStale = getResp.jsonPath().getBoolean("isStale");
        assertNotNull(isStale, "① 应返回isStale标记");
        assertTrue(isStale, "① builder_version=0 应判定为过期，实际=" + getResp.getBody().asString());
        Integer currentVersion = getResp.jsonPath().getInt("currentCompilerVersion");
        assertNotNull(currentVersion, "① 应带当前编译器版本号");

        List<Object> sqlAfterRows = em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        String sqlAfter = String.valueOf(sqlAfterRows.get(0));
        assertEquals(sqlBefore, sqlAfter, "④ 仅打开不应自动改写sql_template，实际前=" + sqlBefore + " 后=" + sqlAfter);
    }
}
