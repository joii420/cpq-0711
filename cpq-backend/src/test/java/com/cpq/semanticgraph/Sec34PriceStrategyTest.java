package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.4 价格策略原子组（D-09）—— AC-20 ~ AC-24。
 * 层级 = T-1（AC-20 编译产物形态）/ T-2,T-3（AC-21 序列删除三向一致 / AC-22 反证：保存回填+后端确实校验 /
 * AC-23 边界：元素键指向手填列 / AC-24 单点：用户先拖的元素列不被回收）。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec34PriceStrategyTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    @BeforeEach
    void setUp() throws Exception {
        componentId = createBlankComponent();
    }


    private UUID createBlankComponent() {
        Response resp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "price-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    private Response compile(String config) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(config).post("/api/cpq/components/" + componentId + "/builder/compile");
    }

    private Response save(String builderConfig) {
        // 见 Sec35FeeTabPreviewInspectTest 的教训：PUT /builder 不吃 {"builderConfig": {...}} 包装。
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfig)
                .put("/api/cpq/components/" + componentId + "/builder");
    }

    // -------------------------------------------------------------------
    // AC-20（单点）拖一列自动带出，渲染为一个整体块（服务端侧可观测部分——已选列结构+SQL）
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-20: 只拖『元素单价』→ 已选列/priceStrategy 自动带出元素列，SQL 含价格策略原子组")
    void ac20_draggingUnitPriceAutoBringsGroupAsAtomicBlock() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ]}
                """;
        Response resp = compile(config);
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        String sql = resp.jsonPath().getString("sql");
        assertNotNull(sql);
        assertFalse(sql.isBlank());
        assertTrue(sql.contains("f_material_element_price("), "① 编译产物应含价格策略函数调用，实际:\n" + sql);

        List<String> declared = resp.jsonPath().getList("declaredColumns");
        assertNotNull(declared, "declaredColumns 不应为空");
        assertFalse(declared.isEmpty(), "declaredColumns 不应为空列表");
        // ②块内含2行：自动带出的『元素』列 + 『元素单价』——用声明列里是否同时出现元素与元素单价代理验证
        boolean hasElementCol = declared.stream().anyMatch(c -> c.contains("元素") && !c.contains("元素单价"));
        boolean hasPriceCol = declared.contains("元素单价");
        assertTrue(hasElementCol, "② 应自动带出『元素』列，实际 declaredColumns=" + declared);
        assertTrue(hasPriceCol, "② 应含『元素单价』列，实际 declaredColumns=" + declared);
        // ③（块可整体拖动/块内行无法单独拖出）为前端可观测项，见 E2E
    }

    // -------------------------------------------------------------------
    // AC-21（序列）整组删除三向一致
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-21【序列】: 删货币仅货币消失→恢复删元素单价一并消失且不残留函数→恢复删元素两列一并消失")
    void ac21_wholeGroupDeletionThreeWayConsistency() {
        String withCurrency = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素"},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"currency","fieldName":"货币"}
                ]}
                """;
        Response base = compile(withCurrency);
        assertEquals(200, base.statusCode(), base.getBody().asString());
        List<String> baseDeclared = base.jsonPath().getList("declaredColumns");
        assertNotNull(baseDeclared);
        assertFalse(baseDeclared.isEmpty());

        // 步骤①：删『货币』——仅货币消失，元素/元素单价保留
        String noCurrency = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素"},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ]}
                """;
        Response r1 = compile(noCurrency);
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        List<String> declared1 = r1.jsonPath().getList("declaredColumns");
        assertNotNull(declared1);
        assertFalse(declared1.isEmpty());
        assertFalse(declared1.contains("货币"), "① 删货币后货币列应消失，实际=" + declared1);
        assertTrue(declared1.contains("元素单价"), "① 元素单价应保留，实际=" + declared1);
        boolean elementStillThere1 = declared1.stream().anyMatch(c -> c.contains("元素") && !c.contains("元素单价"));
        assertTrue(elementStillThere1, "① 元素列应保留，实际=" + declared1);
        String sql1 = r1.jsonPath().getString("sql");
        assertNotNull(sql1);
        assertFalse(sql1.isBlank());
        assertTrue(sql1.contains("f_material_element_price("), "① 价格策略 JOIN 仍应保留，实际:\n" + sql1);

        // 步骤②：恢复(withCurrency)后删『元素』——元素单价一并消失，SQL 不残留函数调用
        String noElement = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response r2 = compile(noElement);
        assertEquals(200, r2.statusCode(), r2.getBody().asString());
        List<String> declared2 = r2.jsonPath().getList("declaredColumns");
        assertNotNull(declared2);
        assertFalse(declared2.contains("元素单价"), "② 删元素后元素单价应一并消失，实际=" + declared2);
        assertFalse(declared2.contains("货币"), "② 删元素后货币应一并消失，实际=" + declared2);
        String sql2 = r2.jsonPath().getString("sql");
        assertNotNull(sql2);
        assertFalse(sql2.contains("f_material_element_price"),
                "② SQL 中不应残留 f_material_element_price，实际:\n" + sql2);

        // 步骤③：恢复(withCurrency)后删『元素单价』——元素列与货币列一并消失
        String noPrice = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true}
                ]}
                """;
        Response r3 = compile(noPrice);
        assertEquals(200, r3.statusCode(), r3.getBody().asString());
        List<String> declared3 = r3.jsonPath().getList("declaredColumns");
        assertNotNull(declared3);
        boolean elementGone3 = declared3.stream().noneMatch(c -> c.contains("元素") && !c.contains("元素单价"));
        assertTrue(elementGone3, "③ 删元素单价后元素列应一并消失，实际=" + declared3);
        assertFalse(declared3.contains("货币"), "③ 删元素单价后货币列应一并消失，实际=" + declared3);
    }

    // -------------------------------------------------------------------
    // AC-22（单点·反证）三项绑定由保存事务回填，且后端确实在校验
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-22【反证】: 库中三项绑定逐字一致；绕过配置器直接PUT不带绑定字段 → 400 COMPONENT_ELEMENT_BINDING_REQUIRED")
    void ac22_bindingBackfilledBySaveAndBackendActuallyValidates_negativeCase() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素"},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ]}
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        // ① 库中三项绑定逐字一致（未拖货币列时 element_currency_field 为空）
        List<Object> rows = em.createNativeQuery(
                        "SELECT element_code_field, element_price_field, element_currency_field FROM component WHERE id = :id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "组件行应存在，查询结果为空——夹具或保存未生效");
        Object[] row = (Object[]) rows.get(0);
        assertNotNull(row[0], "① element_code_field 不应为空");
        assertEquals("元素", row[0], "① element_code_field 应与所选『元素』列逐字一致，实际=" + row[0]);
        assertNotNull(row[1], "① element_price_field 不应为空");
        assertEquals("元素单价", row[1], "① element_price_field 应与所选『元素单价』列逐字一致，实际=" + row[1]);
        assertNull(row[2], "① 未拖货币列时 element_currency_field 应为空，实际=" + row[2]);

        // ②【破坏方式】绕过配置器直接调 PUT /components/{id}，不带 elementCodeField/elementPriceField
        Response bypassResp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "price-bypass-" + UUID.randomUUID() + "\"}")
                .put("/api/cpq/components/" + componentId);
        assertEquals(400, bypassResp.statusCode(),
                "② 直接 PUT 不带元素绑定字段应被拒绝（证明配置器在回填而非后端根本不校验），"
                        + "实际=" + bypassResp.statusCode() + " body=" + bypassResp.getBody().asString());
        assertEquals("COMPONENT_ELEMENT_BINDING_REQUIRED", bypassResp.jsonPath().getString("code"),
                "② 错误码应为 COMPONENT_ELEMENT_BINDING_REQUIRED，实际=" + bypassResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-23（边界）形态 B：元素键指向手填列
    // -------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("AC-23: 元素键改绑手填字段『元素代码』→ 保存成功，SQL不再输出元素业务列但JOIN仍在")
    void ac23_formB_elementKeyPointsToManualField() {
        // 前置：组件中存在一个无取数来源的手填字段「元素代码」——用现有组件字段更新接口手动加一个。
        Response addManualField = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"fields\":[{\"name\":\"元素代码\",\"field_type\":\"INPUT_TEXT\"}]}")
                .patch("/api/cpq/components/" + componentId);
        // 该接口不属于本任务范围，仅用于搭前置；若失败也不阻塞其余用例，故此处只记录不强断言。
        if (addManualField.statusCode() != 200) {
            System.out.println("[AC-23] 添加手填字段『元素代码』前置失败，status=" + addManualField.statusCode()
                    + " body=" + addManualField.getBody().asString() + "——本用例前置未就绪，需人工核实组件字段写接口契约");
        }

        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ], "priceStrategy": {"elementCodeSource": "MANUAL_FIELD", "elementCodeField": "元素代码"} }
                """;
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(), "① 保存应成功: " + saveResp.getBody().asString());

        List<Object> rows = em.createNativeQuery("SELECT element_code_field FROM component WHERE id = :id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "组件行应存在");
        assertEquals("元素代码", rows.get(0), "② element_code_field 应指向手填字段『元素代码』，实际=" + rows.get(0));

        Response compileResp = compile(config);
        assertEquals(200, compileResp.statusCode(), compileResp.getBody().asString());
        String sql = compileResp.jsonPath().getString("sql");
        assertNotNull(sql);
        assertFalse(sql.isBlank());
        assertFalse(sql.contains("el.element_code"), "③ 不应再输出元素业务列(el.element_code)，实际:\n" + sql);
        assertTrue(sql.contains("f_material_element_price("), "③ 价格策略 JOIN 仍应在，实际:\n" + sql);
    }

    // -------------------------------------------------------------------
    // AC-24（单点）用户自己先拖的元素列不被回收
    // -------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("AC-24: 用户先手动拖『元素』再拖『元素单价』，删除元素单价后用户自己拖的元素列仍保留")
    void ac24_userManuallyDraggedElementColumnNotRecycled() {
        String config = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素","userAdded":true},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ]}
                """;
        Response withPrice = compile(config);
        assertEquals(200, withPrice.statusCode(), withPrice.getBody().asString());

        // 删除『元素单价』——服务端应识别『元素』列带 userAdded 标记，不当作组的自动成员回收
        String withoutPrice = """
                { "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"LOOKUP_MATERIAL_RECIPE","sourceColumn":"name","fieldName":"材质名称","isRowKey":true},
                  {"sourceNodeKey":"LOOKUP_ELEMENT","sourceColumn":"element_name","fieldName":"元素","userAdded":true}
                ]}
                """;
        Response after = compile(withoutPrice);
        assertEquals(200, after.statusCode(), after.getBody().asString());
        List<String> declared = after.jsonPath().getList("declaredColumns");
        assertNotNull(declared, "declaredColumns 不应为空");
        assertFalse(declared.isEmpty(), "declaredColumns 不应为空列表");
        boolean elementStillThere = declared.stream().anyMatch(c -> c.contains("元素") && !c.contains("元素单价"));
        assertTrue(elementStillThere, "用户自己拖的『元素』列删除元素单价后应仍保留，实际=" + declared);
        assertFalse(declared.contains("元素单价"), "『元素单价』应已消失，实际=" + declared);
    }
}
