package com.cpq.task260907;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code api.md} 的薄封装 —— <b>只按契约文档拼请求，不 import 任何实现类</b>。
 *
 * <p>路径与字段名逐条对应 {@code api.md} §1~§4。契约变了这里就该红，那正是它的作用。
 */
final class QuoteImportApi {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private QuoteImportApi() {
    }

    // ── api.md §1 POST /dataset/quote/quotation-import ──────────────

    /** 带 customerId 的正常调用。 */
    static Response quotationImport(String session, String customerId, byte[] xlsx, String fileName) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .multiPart("customerId", customerId)
                .multiPart("file", fileName, xlsx, XLSX)
                .when().post("/api/cpq/dataset/quote/quotation-import");
    }

    /** AC-2：不传 customerId —— 期望 400「customerId 不能为空」。 */
    static Response quotationImportNoCustomer(String session, byte[] xlsx, String fileName) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .multiPart("file", fileName, xlsx, XLSX)
                .when().post("/api/cpq/dataset/quote/quotation-import");
    }

    // ── api.md §2 GET /dataset/quote/quotation-import/{recordId} ────

    static Response pollImport(String session, String recordId) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/quote/quotation-import/{id}", recordId);
    }

    // ── api.md §3 POST /dataset/quote/create-quotation ──────────────

    static Response createQuotation(String session, Map<String, Object> body) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/cpq/dataset/quote/create-quotation");
    }

    /** api.md §3 的必填字段。categoryId / costingTemplateId 可空。 */
    static Map<String, Object> createBody(String importRecordId, String customerId, String name,
                                          String categoryId, String customerTemplateId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("importRecordId", importRecordId);
        m.put("customerId", customerId);
        m.put("name", name);
        if (categoryId != null) {
            m.put("categoryId", categoryId);
        }
        m.put("customerTemplateId", customerTemplateId);
        return m;
    }

    // ── api.md §4 停用端点（AC-14：期望 410 Gone） ───────────────────

    static Response legacyQuoteImport(String session, byte[] xlsx, String fileName) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .multiPart("file", fileName, xlsx, XLSX)
                .when().post("/api/cpq/basic-data-import/v6/quote");
    }

    static Response legacyCreateQuotation(String session, Map<String, Object> body) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .contentType(ContentType.JSON)
                .body(body == null ? new LinkedHashMap<String, Object>() : body)
                .when().post("/api/cpq/basic-data-import/v6/quote/create-quotation");
    }

    // ── api.md §5 零改动端点（AC-15 回归对照面） ─────────────────────

    static Response datasetSheets(String session) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/quote/sheets");
    }

    static Response datasetParts(String session, int page, int size) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("page", page).queryParam("size", size)
                .when().get("/api/cpq/dataset/quote/parts");
    }

    static Response datasetOverview(String session, String axis) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/quote/parts/{axis}/overview", axis);
    }

    static Response datasetRows(String session, String axis, String sheetKey) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/quote/parts/{axis}/sheets/{k}/rows", axis, sheetKey);
    }

    static Response datasetVersions(String session, String axis, String sheetKey) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/quote/parts/{axis}/sheets/{k}/versions", axis, sheetKey);
    }

    /** api.md §5 零改动：建单第 2 步复用，契约不变。 */
    static Response autoDefaults(String session, String customerId) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("customerId", customerId)
                .when().get("/api/cpq/templates/auto-defaults");
    }

    static Response datasetLookup(String session, String masterType) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("keyword", "")
                .when().get("/api/cpq/dataset/quote/lookup/{t}", masterType);
    }
}
