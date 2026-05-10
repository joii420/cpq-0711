package com.cpq.elementprice;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ElementPriceResource (Phase 4 #19+#20).
 *
 * <p>Test strategy:
 * <ul>
 *   <li>RBAC is disabled in test profile (cpq.security.rbac.enabled=false), so
 *       most tests run without a session cookie.</li>
 *   <li>T7 (403 permission check) verifies that {@code @RoleAllowed({"SYSTEM_ADMIN"})}
 *       annotation is present on the method — bypassed in test profile, so we verify
 *       at reflection level (annotation metadata check).</li>
 *   <li>T8 (price <= 0 → 400) tests Bean Validation enforcement.</li>
 * </ul>
 *
 * <p>Test data: element_daily_price rows are inserted via EntityManager + UserTransaction.
 * mat_bom rows (ELEMENT type) seed the available-elements list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ElementPriceResource — Phase 4 #19+#20 元素价格中心 v1")
class ElementPriceResourceTest {

    private static final String BASE = "/api/cpq/element-prices";

    /** Admin user UUID used as manually_filled_by */
    private static final UUID ADMIN_USER_ID = UUID.fromString("77000000-0000-0000-0000-000000000001");

    /** Part number to seed ELEMENT BOM rows */
    private static final String TEST_PART_NO = "EP-TEST-PART-001";

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @BeforeEach
    void setup() throws Exception {
        // Ensure clean transaction state
        try {
            if (utx.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                utx.rollback();
            }
        } catch (Exception ignored) {}

        utx.begin();
        em.joinTransaction();

        // Seed admin user
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, " +
                "is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'ep-admin', '元素管理员', 'ep-admin@test.com', 'hash', 'SYSTEM_ADMIN', " +
                "'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", ADMIN_USER_ID)
                .executeUpdate();

        // Seed mat_part (required FK for mat_bom)
        em.createNativeQuery(
                "INSERT INTO mat_part(part_no, part_name, status_code, created_at, updated_at) " +
                "VALUES (:pn, 'EP Test Part', 'Y', NOW(), NOW()) " +
                "ON CONFLICT (part_no) DO NOTHING")
                .setParameter("pn", TEST_PART_NO)
                .executeUpdate();

        // Seed mat_bom ELEMENT rows (Ag, Cu, Au) — used by available-elements endpoint
        for (String[] elem : new String[][]{
                {"Ag", "1"}, {"Cu", "2"}, {"Au", "3"}}) {
            em.createNativeQuery(
                    "INSERT INTO mat_bom(id, bom_type, hf_part_no, seq_no, element_name, " +
                    "created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), 'ELEMENT', :pn, :seq, :elem, NOW(), NOW()) " +
                    "ON CONFLICT (bom_type, hf_part_no, seq_no, " +
                    "COALESCE(input_material_no,''), COALESCE(element_name,'')) DO NOTHING")
                    .setParameter("pn", TEST_PART_NO)
                    .setParameter("seq", Integer.parseInt(elem[1]))
                    .setParameter("elem", elem[0])
                    .executeUpdate();
        }

        // Clean element_daily_price test rows to ensure deterministic state
        em.createNativeQuery(
                "DELETE FROM element_daily_price WHERE element_name IN ('Ag','Cu','Au') " +
                "AND fetch_status = 'MANUAL'")
                .executeUpdate();

        utx.commit();
    }

    // =========================================================================
    // T1: POST /manual → inserts new row, fetch_status=MANUAL, price_date=today
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("T1: POST /manual inserts new MANUAL row for today")
    void postManual_insertsNewRow() {
        String body = """
                {
                  "elementName": "Ag",
                  "price": 406.50,
                  "currency": "CNY",
                  "unit": "g",
                  "note": "test price"
                }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/manual")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.elementName", equalTo("Ag"))
            .body("data.price", equalTo(406.50f))
            .body("data.currency", equalTo("CNY"))
            .body("data.unit", equalTo("g"))
            .body("data.priceDate", equalTo(LocalDate.now().toString()))
            .body("data.note", equalTo("test price"))
            .body("data.enteredByName", notNullValue());
    }

    // =========================================================================
    // T2: POST /manual same element same day → overwrite (ON CONFLICT DO UPDATE)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("T2: POST /manual same element same day overwrites existing row")
    void postManual_sameElementSameDay_overwrites() throws Exception {
        // First insert
        String body1 = """
                { "elementName": "Ag", "price": 400.00, "currency": "CNY", "unit": "g" }
                """;
        given().contentType(JSON).body(body1).post(BASE + "/manual")
               .then().statusCode(200);

        // Second insert — different price
        String body2 = """
                { "elementName": "Ag", "price": 420.00, "currency": "CNY", "unit": "g", "note": "updated" }
                """;
        given()
            .contentType(JSON)
            .body(body2)
        .when()
            .post(BASE + "/manual")
        .then()
            .statusCode(200)
            .body("data.price", equalTo(420.00f))
            .body("data.note", equalTo("updated"));

        // Verify only one MANUAL row for Ag today in DB
        utx.begin();
        em.joinTransaction();
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price " +
                "WHERE element_name='Ag' AND fetch_status='MANUAL' AND price_date = CURRENT_DATE")
                .getSingleResult();
        utx.commit();
        Assertions.assertEquals(1, count.intValue(), "Should have exactly 1 MANUAL row for Ag today");
    }

    // =========================================================================
    // T3: GET /reference?elementName=Ag&priceDate=today → returns latest MANUAL row
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("T3: GET /reference returns latest MANUAL row ≤ priceDate")
    void getReference_returnsLatestManualRow() throws Exception {
        // Seed a MANUAL row for yesterday and today
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price(id, element_name, source_id, price_date, raw_price, " +
                "currency, price_unit, fetch_status, manually_filled_by, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'Ag', NULL, :d1, 390.00, 'CNY', 'g', 'MANUAL', :uid, NOW(), NOW())")
                .setParameter("d1", yesterday)
                .setParameter("uid", ADMIN_USER_ID)
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element_daily_price(id, element_name, source_id, price_date, raw_price, " +
                "currency, price_unit, fetch_status, manually_filled_by, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'Ag', NULL, :d2, 406.00, 'CNY', 'g', 'MANUAL', :uid, NOW(), NOW())")
                .setParameter("d2", today)
                .setParameter("uid", ADMIN_USER_ID)
                .executeUpdate();
        utx.commit();

        given()
            .queryParam("elementName", "Ag")
            .queryParam("priceDate", today.toString())
        .when()
            .get(BASE + "/reference")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.elementName", equalTo("Ag"))
            .body("data.price", equalTo(406.00f))
            .body("data.priceDate", equalTo(today.toString()));
    }

    // =========================================================================
    // T4: GET /reference with no price → HTTP 200, data=null
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("T4: GET /reference for unknown element returns HTTP 200 with data=null")
    void getReference_noPrice_returnsNullData() {
        given()
            .queryParam("elementName", "Pt")    // no data seeded for Pt
            .queryParam("priceDate", LocalDate.now().toString())
        .when()
            .get(BASE + "/reference")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", nullValue());
    }

    // =========================================================================
    // T5: GET /history?elementName=&from=&to= → returns rows in descending order
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("T5: GET /history returns MANUAL rows in descending date order")
    void listHistory_returnedInDescendingOrder() throws Exception {
        LocalDate d1 = LocalDate.now().minusDays(5);
        LocalDate d2 = LocalDate.now().minusDays(3);
        LocalDate d3 = LocalDate.now().minusDays(1);

        utx.begin();
        em.joinTransaction();
        for (Object[] row : new Object[][]{
                {d1, new BigDecimal("380.00")},
                {d2, new BigDecimal("390.00")},
                {d3, new BigDecimal("400.00")}}) {
            em.createNativeQuery(
                    "INSERT INTO element_daily_price(id, element_name, source_id, price_date, raw_price, " +
                    "currency, price_unit, fetch_status, manually_filled_by, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), 'Cu', NULL, :d, :p, 'CNY', 'kg', 'MANUAL', :uid, NOW(), NOW())")
                    .setParameter("d", row[0])
                    .setParameter("p", row[1])
                    .setParameter("uid", ADMIN_USER_ID)
                    .executeUpdate();
        }
        utx.commit();

        given()
            .queryParam("elementName", "Cu")
            .queryParam("from", d1.toString())
            .queryParam("to", d3.toString())
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", hasSize(3))
            // First item should be most recent
            .body("data[0].priceDate", equalTo(d3.toString()))
            .body("data[0].price", equalTo(400.00f))
            .body("data[2].priceDate", equalTo(d1.toString()))
            .body("data[2].price", equalTo(380.00f));
    }

    // =========================================================================
    // T6: GET /available-elements → returns Ag, Au, Cu from mat_bom
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("T6: GET /available-elements returns distinct elements from mat_bom")
    void listAvailableElements_returnsMatBomElements() {
        given()
        .when()
            .get(BASE + "/available-elements")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", hasItems("Ag", "Cu", "Au"));
    }

    // =========================================================================
    // T7: SALES_REP calling POST /manual → @RoleAllowed(SYSTEM_ADMIN) annotation check
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("T7: POST /manual has @RoleAllowed(SYSTEM_ADMIN) annotation (RBAC metadata check)")
    void postManual_hasSystemAdminAnnotation() throws Exception {
        // RBAC is disabled in test profile so we cannot test the 403 via HTTP directly.
        // Instead, verify the annotation is present at the method level.
        java.lang.reflect.Method method = ElementPriceResource.class.getMethod(
                "upsertManual", UpsertManualPriceRequest.class,
                io.vertx.core.http.HttpServerRequest.class);

        com.cpq.common.security.RoleAllowed anno = method.getAnnotation(
                com.cpq.common.security.RoleAllowed.class);

        Assertions.assertNotNull(anno, "POST /manual must have @RoleAllowed");
        Assertions.assertArrayEquals(new String[]{"SYSTEM_ADMIN"}, anno.value(),
                "POST /manual must be restricted to SYSTEM_ADMIN");
    }

    // =========================================================================
    // T8: POST /manual price <= 0 → 400 Bad Request
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("T8: POST /manual with price=0 returns 400")
    void postManual_zeroPriceReturns400() {
        String body = """
                { "elementName": "Ag", "price": 0, "currency": "CNY", "unit": "g" }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/manual")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T9: POST /manual negative price → 400 Bad Request
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("T9: POST /manual with negative price returns 400")
    void postManual_negativePriceReturns400() {
        String body = """
                { "elementName": "Ag", "price": -10.5, "currency": "CNY", "unit": "g" }
                """;

        given()
            .contentType(JSON)
            .body(body)
        .when()
            .post(BASE + "/manual")
        .then()
            .statusCode(400);
    }

    // =========================================================================
    // T10: GET /history default date range (no from/to) works without error
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("T10: GET /history without from/to uses default 30-day range")
    void listHistory_defaultDateRange_noError() throws Exception {
        // Seed one row within default range (today)
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO element_daily_price(id, element_name, source_id, price_date, raw_price, " +
                "currency, price_unit, fetch_status, manually_filled_by, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'Au', NULL, CURRENT_DATE, 500.00, 'CNY', 'g', " +
                "'MANUAL', :uid, NOW(), NOW())")
                .setParameter("uid", ADMIN_USER_ID)
                .executeUpdate();
        utx.commit();

        given()
            .queryParam("elementName", "Au")
        .when()
            .get(BASE + "/history")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", hasSize(greaterThanOrEqualTo(1)));
    }
}
