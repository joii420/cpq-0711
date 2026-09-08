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

    /**
     * 导入一份 Excel。
     *
     * <h3>task-260907：报价数据集的 {@code customerNo} 是<b>必填 multipart 表单字段</b></h3>
     * 本任务（B-4 / AC-4）给报价侧导入加了「必须指定客户」的硬校验
     * （{@code DatasetImportService#requireCustomerNo}）。缺它会在<b>进入 Excel 校验之前</b>
     * 就被请求级 400 拦下：
     * <pre>{@code {"code":400,"message":"导入报价数据必须指定客户（customerNo）"}}</pre>
     * ⇒ 本方法补上该字段，让请求满足<b>新契约</b>后再进入各用例真正要验的那层。
     *
     * <p>🚩 这不是「改用例去迁就实现」：AC-45/46/47 的断言一个字未动，改的是
     * <b>HTTP 客户端</b>——它此前按旧契约（只发 {@code file}）构造请求，而契约新增了必填字段。
     * 实证：补上之后 {@code tc01}/{@code tc02} 直接转绿，且它们断言的
     * {@code data.errors} <b>本来就在</b>（实现没有少返，是请求根本没走到校验那层）。
     *
     * <p>⚠️ 走 multipart 字段而不是 query 参数 —— 2026-09-07 实测按 query 发同样被拒
     * （见 {@code CustDimBase#importQuote} 的同款注释）。
     *
     * <p>⚠️ 核价两套不带客户维度，传了反而会被写入器拒（防口径漂移），故只对报价侧补。
     */
    static Response importFile(String session, String dataset, byte[] xlsx, String fileName) {
        return importFile(session, dataset, xlsx, fileName,
                QUOTE.equals(dataset) ? DatasetAcTestBase.CUSTOMER_ROCKWELL : null);
    }

    /** 显式指定请求级 {@code customerNo} 的重载；传 null = 不带该字段（用于验「缺失即拒」）。 */
    static Response importFile(String session, String dataset, byte[] xlsx, String fileName,
                               String customerNo) {
        var req = RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .multiPart("file", fileName, xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if (customerNo != null) {
            req = req.multiPart("customerNo", customerNo);
        }
        return req.when().post("/api/cpq/dataset/{dataset}/import", dataset);
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
