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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.4 价格策略原子组（D-09）—— AC-20 ~ AC-24。
 *
 * <h3>🔄 2026-09-05（用户裁决 {@code D-123}）：本类 <b>5 条全部保留</b>，只把夹具换成 v9 对象</h3>
 * <p>🔑 <b>换对象取证（2026-09-05 实查 {@code cpq_db_0724}，先证明对象存在再改用例）</b>：
 * <pre>
 * SELECT node_key,dialect,node_kind,func_signature FROM semantic_node WHERE node_kind='FUNCTION';
 *   → FUNC_ELEMENT_PRICE | QUOTE | FUNCTION | f_material_element_price(:customerCode, :priceBaseDate)
 *
 * SELECT v.dialect,v.tab_type,n.node_key,tvn.role FROM semantic_tab_view v
 *   JOIN semantic_tab_view_node tvn ON tvn.view_id=v.id JOIN semantic_node n ON n.id=tvn.node_id
 *   WHERE v.dialect='QUOTE' AND v.tab_type='材质元素';
 *   → QUOTE | 材质元素 | ELEMENT_BOM         | MAIN
 *     QUOTE | 材质元素 | FUNC_ELEMENT_PRICE  | AUX
 *
 * SELECT edge_kind,cardinality,count(*) FROM semantic_edge GROUP BY 1,2;
 *   → NARROW/ONE_TO_MANY 28 · PRICE/MANY_TO_ONE 1   （那条 PRICE 边就是 ELEMENT_BOM → FUNC_ELEMENT_PRICE）
 * </pre>
 * ⇒ <b>价格策略原子组在 v9 图里原封不动地活着</b>（{@code V413} 删的是 V6 的 Sheet/查名节点，
 * 没有删这个 FUNCTION 节点），只是锚点从 V6 的 {@code ELEMENT_BOM_ITEM}（{@code element_bom_item} 表）
 * 换成了 v9 的 {@code ELEMENT_BOM}（{@code ds_quote_element_bom} 表）、元素列从查名节点
 * {@code LOOKUP_ELEMENT.element_name} 换成了锚点自带的 {@code ELEMENT_BOM.element_code}（登记名「元素」，
 * {@code elemKey=true}）。<b>判据一个字没改。</b>
 *
 * <h3>⚠️ 换对象时踩到的一个坑（记下来，别再犯）</h3>
 * 原用例用 {@code c.contains("元素") && !c.contains("元素单价")} 来判断「元素列还在不在」。
 * 在 v9 上这个写法<b>必然误判</b> —— 锚点简称就叫「物料与元素BOM」，于是
 * {@code _物料与元素BOM_材质料号} 也 {@code contains("元素")}。
 * 本轮改为<b>从种子算出精确别名</b>（{@code _<short_name>_<display_name>}）后按等值比对。
 *
 * <p>层级 = T-1（AC-20 编译产物形态）/ T-2,T-3（AC-21 序列 / AC-22 反证 / AC-23 边界 / AC-24 单点）。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec34PriceStrategyTest — AC-20~24 全部换 v9 对象后保留（价格策略节点在 v9 图里仍然存在）")
class Sec34PriceStrategyTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    private String anchorKey;
    private String anchorShort;
    /** 锚点上「元素」那一列的 db_column 与视图别名。 */
    private String elemColumn;
    private String aliasElement;
    private String aliasPartNo;

    @BeforeEach
    void setUp() {
        componentId = createBlankComponent();

        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.short_name, n.id FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        anchorKey = String.valueOf(anchor[0]);
        anchorShort = String.valueOf(anchor[1]);

        // 「元素键」列：种子里 is_code 且登记名为「元素」的那一列（field-tree 的 elemKey=true 就是它）
        Object[] elem = (Object[]) em.createNativeQuery(
                        "SELECT c.db_column, c.display_name FROM semantic_node_column c "
                                + "WHERE c.node_id = :nid AND c.status='ACTIVE' AND c.display_name = '元素'")
                .setParameter("nid", anchor[2]).getSingleResult();
        elemColumn = String.valueOf(elem[0]);
        aliasElement = "_" + anchorShort + "_" + elem[1];

        Object[] partNo = (Object[]) em.createNativeQuery(
                        "SELECT c.db_column, c.display_name FROM semantic_node_column c "
                                + "WHERE c.node_id = :nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                .setParameter("nid", anchor[2]).getSingleResult();
        aliasPartNo = "_" + anchorShort + "_" + partNo[1];
        partNoColumn = String.valueOf(partNo[0]);

        System.out.println("[Sec34 换对象] 锚点=" + anchorKey + " 简称=" + anchorShort
                + " 元素列=" + elemColumn + "→" + aliasElement + " 料号列=" + partNoColumn + "→" + aliasPartNo);
    }

    private String partNoColumn;

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
        // PUT /builder 不吃 {"builderConfig": {...}} 包装（2026-08-21 真跑教训，见 Sec35 同名方法说明）。
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfig).put("/api/cpq/components/" + componentId + "/builder");
    }

    /** 料号列（满足 B-27「标识列至少配一个」）。 */
    private String partNoCol(String fieldName) {
        return "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"" + partNoColumn
                + "\",\"fieldName\":\"" + fieldName + "\",\"isRowKey\":true,\"isPartNo\":true}";
    }

    private String elemCol(String fieldName, boolean userAdded) {
        return "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"" + elemColumn
                + "\",\"fieldName\":\"" + fieldName + "\"" + (userAdded ? ",\"userAdded\":true" : "") + "}";
    }

    private static String priceCol(String sourceColumn, String fieldName) {
        return "{\"sourceNodeKey\":\"FUNC_ELEMENT_PRICE\",\"sourceColumn\":\"" + sourceColumn
                + "\",\"fieldName\":\"" + fieldName + "\"}";
    }

    private static String cfg(String... columns) {
        return "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"columns\":[" + String.join(",", columns) + "]}";
    }

    // -------------------------------------------------------------------
    // AC-20（单点）拖一列自动带出，渲染为一个整体块
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-20(v9): 只拖『元素单价』→ 自动带出元素列，SQL 含价格策略原子组（f_material_element_price ... cep）")
    void ac20_draggingUnitPriceAutoBringsGroupAsAtomicBlock() {
        Response resp = compile(cfg(partNoCol("材质料号"), priceCol("unit_price", "元素单价")));
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        String sql = resp.jsonPath().getString("sql");
        assertNotNull(sql);
        assertFalse(sql.isBlank());
        System.out.println("[AC-20(v9)] SQL:\n" + sql);

        // ① 价格策略函数 + 固定别名 cep（AC-1 铁律，种子 note 里也写死了这条）
        assertTrue(sql.contains("f_material_element_price("), "① 产物应含价格策略函数调用，实际:\n" + sql);
        assertFalse(sql.contains("f_customer_element_price"), "① 不应出现 f_customer_element_price，实际:\n" + sql);
        assertTrue(sql.matches("(?s).*LEFT JOIN\\s+f_material_element_price\\([^)]*\\)\\s+cep\\b.*"),
                "① 别名须逐字为 cep，实际:\n" + sql);
        // 双条件 JOIN：元素码 + 料号，且料号侧与 hf_part_no 表达式同源
        assertTrue(sql.matches("(?s).*cep\\.element_code\\s*=\\s*\\w+\\." + elemColumn + ".*"),
                "① 缺少元素码 JOIN 条件（cep.element_code = <锚点别名>." + elemColumn + "），实际:\n" + sql);
        assertTrue(sql.matches("(?s).*cep\\.material_no\\s*=\\s*\\w+\\.material_no.*"),
                "① 缺少料号 JOIN 条件（cep.material_no = <锚点别名>.material_no），实际:\n" + sql);
        // 不含 COALESCE(...,0) 形式的价格兜底
        assertFalse(sql.matches("(?s).*COALESCE\\([^)]*,\\s*0\\).*"),
                "① 不应出现 COALESCE(...,0) 价格兜底，实际:\n" + sql);

        List<String> declared = resp.jsonPath().getList("declaredColumns");
        assertNotNull(declared, "declaredColumns 不应为空");
        assertFalse(declared.isEmpty(), "declaredColumns 不应为空列表");
        System.out.println("[AC-20(v9)] declaredColumns=" + declared);
        // ② 块内含 2 行：用户没拖的『元素』列被自动带出 + 『元素单价』
        assertTrue(declared.contains(aliasElement),
                "② 应自动带出元素列 " + aliasElement + "（用户并未拖它），实际=" + declared);
        assertTrue(declared.contains("元素单价"),
                "② 应含『元素单价』（价格策略列别名无前缀），实际=" + declared);
        // ③（块可整体拖动 / 块内行无法单独拖出）为前端可观测项，见 E2E
    }

    // -------------------------------------------------------------------
    // AC-21（序列）整组删除三向一致
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-21(v9)【序列】: 删货币仅货币消失(JOIN 仍在) → 整组删除后元素单价/货币一并消失且 SQL 不残留函数")
    void ac21_wholeGroupDeletionThreeWayConsistency() {
        String withCurrency = cfg(partNoCol("材质料号"), elemCol("元素", false),
                priceCol("unit_price", "元素单价"), priceCol("currency", "货币"));
        Response base = compile(withCurrency);
        assertEquals(200, base.statusCode(), base.getBody().asString());
        List<String> baseDeclared = base.jsonPath().getList("declaredColumns");
        assertNotNull(baseDeclared);
        assertFalse(baseDeclared.isEmpty());
        assertTrue(baseDeclared.contains("货币"), "前置：基线配置应含货币列，实际=" + baseDeclared);

        // 步骤①：删『货币』—— 仅货币消失，元素/元素单价保留，价格策略 JOIN 仍在
        Response r1 = compile(cfg(partNoCol("材质料号"), elemCol("元素", false), priceCol("unit_price", "元素单价")));
        assertEquals(200, r1.statusCode(), r1.getBody().asString());
        List<String> declared1 = r1.jsonPath().getList("declaredColumns");
        assertNotNull(declared1);
        assertFalse(declared1.isEmpty());
        assertFalse(declared1.contains("货币"), "① 删货币后货币列应消失，实际=" + declared1);
        assertTrue(declared1.contains("元素单价"), "① 元素单价应保留，实际=" + declared1);
        assertTrue(declared1.contains(aliasElement), "① 元素列应保留，实际=" + declared1);
        String sql1 = r1.jsonPath().getString("sql");
        assertNotNull(sql1);
        assertTrue(sql1.contains("f_material_element_price("), "① 价格策略 JOIN 仍应保留，实际:\n" + sql1);

        // 步骤②③：整组移除（元素 + 元素单价 + 货币都不拖）—— 三者一并消失，SQL 不残留函数调用
        Response r2 = compile(cfg(partNoCol("材质料号")));
        assertEquals(200, r2.statusCode(), r2.getBody().asString());
        List<String> declared2 = r2.jsonPath().getList("declaredColumns");
        assertNotNull(declared2);
        System.out.println("[AC-21(v9)] 整组移除后 declaredColumns=" + declared2);
        assertFalse(declared2.contains("元素单价"), "② 元素单价应消失，实际=" + declared2);
        assertFalse(declared2.contains("货币"), "② 货币应消失，实际=" + declared2);
        assertFalse(declared2.contains(aliasElement),
                "③ 用户未拖元素列时不应残留自动带出的元素列 " + aliasElement + "，实际=" + declared2);
        String sql2 = r2.jsonPath().getString("sql");
        assertNotNull(sql2);
        assertFalse(sql2.contains("f_material_element_price"),
                "② SQL 中不应残留 f_material_element_price，实际:\n" + sql2);
    }

    // -------------------------------------------------------------------
    // AC-22（单点·反证）三项绑定由保存事务回填，且后端确实在校验
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-22(v9)【反证】: 库中三项绑定逐字一致；绕过配置器直接 PUT 清空绑定 → 400 COMPONENT_ELEMENT_BINDING_REQUIRED")
    void ac22_bindingBackfilledBySaveAndBackendActuallyValidates_negativeCase() {
        String config = cfg(partNoCol("材质料号"), elemCol("元素", false), priceCol("unit_price", "元素单价"));
        System.out.println("[AC-22(v9)] 保存配置=" + config);
        Response saveResp = save(config);
        assertEquals(200, saveResp.statusCode(),
                "🔴 【实测缺陷，非用例问题】形态 A（元素列取自语义图）在 v9 图上<b>存不下来</b>：\n"
                        + "  实际 HTTP=" + saveResp.statusCode() + " body=" + saveResp.getBody().asString() + "\n"
                        + "  —— 保存事务没能从已选列里回填 element_code_field，于是撞上它自己的"
                        + " COMPONENT_ELEMENT_BINDING_REQUIRED 守卫。\n"
                        + "  📊 2026-09-05 单变量对照实验（9 个变体，同一图、同一锚点 ELEMENT_BOM）：\n"
                        + "     A 料号+元素+单价 .......................... 400 missingFields=[elementCodeField]\n"
                        + "     B 料号+单价（靠编译器自动带出元素列）...... 400 同上\n"
                        + "     C 元素列加 isRowKey ....................... 400 同上\n"
                        + "     D 元素列排在料号列之前 .................... 400 同上\n"
                        + "     E 再加货币列 .............................. 400 同上\n"
                        + "     F 带空的 priceStrategy:{} ................. 400 同上\n"
                        + "     G 元素列加 lookupLib=价格策略 ............. 400 同上\n"
                        + "     H 元素/单价都带 fieldType=BASIC_DATA ...... 400 同上\n"
                        + "     I priceStrategy.elementCodeManualField='元素' → 200，回填 code=元素 price=null\n"
                        + "  ⇒ 只有形态 B（手填字段路径）能存；形态 A 的回填在 v9 上 100% 失效。\n"
                        + "  ✅ 编译侧是好的：同一份配置 POST /compile 返回 200，产物含"
                        + " LEFT JOIN f_material_element_price(...) cep 且自动带出元素列（AC-20 本轮通过）。\n"
                        + "  ⇒ 问题只在<b>保存期的绑定回填</b>这一步。\n"
                        + "  💡 根因方向（不下结论，定位归开发/主线）：回填逻辑很可能仍按 V6 口径找元素列"
                        + "（V6 的元素名来自独立查名节点 LOOKUP_ELEMENT；v9 已下沉为锚点自带列"
                        + " ELEMENT_BOM.element_code，field-tree 上以 elemKey=true 标识）。\n"
                        + "  📌 影响：v9 下任何想用价格策略的『材质元素』组件都存不下来，除非用户改走形态 B 手填。");

        // ① 库中三项绑定逐字一致（未拖货币列时 element_currency_field 为空）
        List<Object> rows = em.createNativeQuery(
                        "SELECT element_code_field, element_price_field, element_currency_field FROM component WHERE id = :id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "组件行应存在，查询结果为空——夹具或保存未生效");
        Object[] row = (Object[]) rows.get(0);
        System.out.println("[AC-22(v9)] 回填结果 code=" + row[0] + " price=" + row[1] + " currency=" + row[2]);
        assertNotNull(row[0], "① element_code_field 不应为空");
        assertEquals("元素", row[0], "① element_code_field 应与所选『元素』列的字段名逐字一致，实际=" + row[0]);
        assertNotNull(row[1], "① element_price_field 不应为空");
        assertEquals("元素单价", row[1], "① element_price_field 应与『元素单价』列逐字一致，实际=" + row[1]);
        assertNull(row[2], "① 未拖货币列时 element_currency_field 应为空，实际=" + row[2]);

        // ②【破坏方式】绕过配置器直接 PUT /components/{id}，显式清空两个绑定字段。
        // 该端点是 PATCH 语义（字段为 null 才不覆盖），必须显式传空字符串才能真正触发清空路径 ——
        // 裸传 {"name":...} 只会保持原值不变，会把「后端不校验」误判成「后端校验了」。
        Response bypassResp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"elementCodeField\":\"\",\"elementPriceField\":\"\"}")
                .put("/api/cpq/components/" + componentId);
        assertEquals(400, bypassResp.statusCode(),
                "② 直接 PUT 显式清空元素绑定字段应被拒绝（证明是后端在校验，不是配置器自说自话），实际="
                        + bypassResp.statusCode() + " body=" + bypassResp.getBody().asString());
        // 错误码嵌在 data.code（顶层 code=400 是 HTTP 状态回声）
        assertEquals("COMPONENT_ELEMENT_BINDING_REQUIRED", bypassResp.jsonPath().getString("data.code"),
                "② 错误码应为 COMPONENT_ELEMENT_BINDING_REQUIRED，实际=" + bypassResp.getBody().asString());
    }

    // -------------------------------------------------------------------
    // AC-23（边界）形态 B：元素键指向手填列（D-69）
    // -------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("AC-23(v9): 元素键改绑手填字段『元素代码』→ 保存成功，且编译期不生成价格策略 JOIN（D-69）")
    void ac23_formB_elementKeyPointsToManualField() {
        Response addManualField = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"fields\":[{\"name\":\"元素代码\",\"field_type\":\"INPUT_TEXT\"}]}")
                .put("/api/cpq/components/" + componentId);
        assertEquals(200, addManualField.statusCode(), "前置：添加手填字段『元素代码』应成功，实际="
                + addManualField.statusCode() + " body=" + addManualField.getBody().asString());

        // priceStrategy DTO 只有 elementCodeManualField 一个字段 —— 用别的键名会被 Jackson 静默丢弃，
        // 覆盖从未生效（2026-08-25 修正过的用例错误，别再犯）。
        String config = "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"columns\":["
                + partNoCol("材质料号") + "," + priceCol("unit_price", "元素单价")
                + "],\"priceStrategy\":{\"elementCodeManualField\":\"元素代码\"}}";

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
        System.out.println("[AC-23(v9)] 形态B SQL:\n" + sql);
        // D-69：手填字段的值在编译期并不存在，无法作为 JOIN 左键 ⇒ 整组移除价格策略列、不追加 JOIN。
        // 定价改由既有运行时机制（element_code_field / element_price_field，task-0729）接管。
        assertFalse(sql.contains("f_material_element_price("),
                "③ 形态B下编译期不应生成 f_material_element_price JOIN（D-69），实际:\n" + sql);
    }

    // -------------------------------------------------------------------
    // AC-24（单点）用户自己先拖的元素列不被回收
    // -------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("AC-24(v9): 用户先手动拖『元素』再拖『元素单价』，删除元素单价后用户自己拖的元素列仍保留")
    void ac24_userManuallyDraggedElementColumnNotRecycled() {
        Response withPrice = compile(cfg(partNoCol("材质料号"), elemCol("元素", true), priceCol("unit_price", "元素单价")));
        assertEquals(200, withPrice.statusCode(), withPrice.getBody().asString());

        // 删除『元素单价』—— 服务端应识别『元素』列带 userAdded 标记，不当作组的自动成员回收
        Response after = compile(cfg(partNoCol("材质料号"), elemCol("元素", true)));
        assertEquals(200, after.statusCode(), after.getBody().asString());
        List<String> declared = after.jsonPath().getList("declaredColumns");
        assertNotNull(declared, "declaredColumns 不应为空");
        assertFalse(declared.isEmpty(), "declaredColumns 不应为空列表");
        System.out.println("[AC-24(v9)] 删元素单价后 declaredColumns=" + declared);
        assertTrue(declared.contains(aliasElement),
                "用户自己拖的元素列 " + aliasElement + " 在删除元素单价后应仍保留，实际=" + declared);
        assertFalse(declared.contains("元素单价"), "『元素单价』应已消失，实际=" + declared);
    }
}
