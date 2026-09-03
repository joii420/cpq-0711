package com.cpq.task260902;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

/**
 * {@code api.md} 的薄封装 —— <b>只按契约文档拼请求，不 import 任何实现类</b>。
 *
 * <p>路径与字段名逐条对应 {@code api.md} §1~§8。契约变了这里就该红，那正是它的作用。
 */
final class DatasetApi {

    static final String QUOTE = "quote";
    static final String COST_BASIC = "cost-basic";
    static final String COST_DETAIL = "cost-detail";

    private DatasetApi() {
    }

    // ── §1 POST /dataset/{dataset}/import ───────────────────────────

    static Response importFile(String session, String dataset, byte[] xlsx, String fileName) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .multiPart("file", fileName, xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when()
                .post("/api/cpq/dataset/{dataset}/import", dataset);
    }

    /** 未登录版本 —— AC-31 的「写端点鉴权」用。 */
    static Response importFileNoSession(String dataset, byte[] xlsx, String fileName) {
        return RestAssured.given()
                .multiPart("file", fileName, xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when()
                .post("/api/cpq/dataset/{dataset}/import", dataset);
    }

    // ── §2 GET /dataset/{dataset}/sheets ────────────────────────────

    static Response sheets(String session, String dataset) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/{dataset}/sheets", dataset);
    }

    // ── §3 GET /dataset/{dataset}/parts ─────────────────────────────

    static Response parts(String session, String dataset, String keyword) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("page", 0).queryParam("size", 50);
        if (keyword != null) {
            req = req.queryParam("keyword", keyword);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts", dataset);
    }

    // ── §4 GET /parts/{axisValue}/overview ──────────────────────────

    static Response overview(String session, String dataset, String axisValue) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/{dataset}/parts/{axis}/overview", dataset, axisValue);
    }

    // ── §5 GET /parts/{axisValue}/sheets/{sheetKey}/rows ────────────

    static Response rows(String session, String dataset, String axisValue, String sheetKey, Integer version) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session);
        if (version != null) {
            req = req.queryParam("version", version);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                dataset, axisValue, sheetKey);
    }

    // ── §6 GET /parts/{axisValue}/sheets/{sheetKey}/versions ────────

    static Response versions(String session, String dataset, String axisValue, String sheetKey) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/versions",
                        dataset, axisValue, sheetKey);
    }

    // ── §7 PUT /parts/{axisValue}/sheets/{sheetKey}/rows ────────────

    static Response saveRows(String session, String dataset, String axisValue, String sheetKey,
                             int baseVersion, List<Map<String, Object>> rows) {
        return RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .contentType(ContentType.JSON)
                .body(Map.of("baseVersion", baseVersion, "rows", rows))
                .when()
                .put("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                        dataset, axisValue, sheetKey);
    }

    static Response saveRowsNoSession(String dataset, String axisValue, String sheetKey,
                                      int baseVersion, List<Map<String, Object>> rows) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("baseVersion", baseVersion, "rows", rows))
                .when()
                .put("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                        dataset, axisValue, sheetKey);
    }

    // ── §8 GET /dataset/{dataset}/lookup/{masterType} ───────────────

    static Response lookup(String session, String dataset, String masterType, String keyword) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("keyword", keyword == null ? "" : keyword)
                .when().get("/api/cpq/dataset/{dataset}/lookup/{masterType}", dataset, masterType);
    }
}
