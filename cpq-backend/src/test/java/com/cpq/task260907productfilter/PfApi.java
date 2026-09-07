package com.cpq.task260907productfilter;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

/**
 * {@code api.md}（task-260907-产品管理客户过滤）的薄封装。
 *
 * <p>🚨 <b>只按契约文档拼请求，不 import 任何 {@code src/main} 下的类</b>。路径与参数名逐条对应
 * {@code api.md} §1~§4。契约变了这里就该跟着改、跟着红，那正是薄封装的作用 ——
 * 断言测的是「实现是否符合 api.md」，不是「实现是否符合我对实现的猜测」。
 */
final class PfApi {

    static final String QUOTE = "quote";
    static final String COST_BASIC = "cost-basic";
    static final String COST_DETAIL = "cost-detail";

    private PfApi() {
    }

    // ── A-1 · GET /dataset/{dataset}/customers（新建，api.md §1） ──────────

    static Response customers(String session, String dataset) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/{dataset}/customers", dataset);
    }

    // ── 既有 · GET /dataset/{dataset}/sheets（发现 sheetKey 用，api.md 未变） ──

    static Response sheets(String session, String dataset) {
        return RestAssured.given().cookie("CPQ_SESSION", session)
                .when().get("/api/cpq/dataset/{dataset}/sheets", dataset);
    }

    // ── A-2 · GET /dataset/{dataset}/parts（加 customerNo，api.md §2） ─────

    static Response parts(String session, String dataset, String customerNo) {
        return parts(session, dataset, customerNo, 0, 100, null);
    }

    static Response parts(String session, String dataset, String customerNo, int page, int size, String keyword) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("page", page).queryParam("size", size);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        if (keyword != null) {
            req = req.queryParam("keyword", keyword);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts", dataset);
    }

    // ── A-3 · GET /parts/{axisValue}/overview（加 customerNo，api.md §2） ──

    static Response overview(String session, String dataset, String axisValue, String customerNo) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts/{axis}/overview", dataset, axisValue);
    }

    // ── A-4 · GET /parts/{axisValue}/sheets/{sheetKey}/rows（加 customerNo） ─

    static Response rows(String session, String dataset, String axisValue, String sheetKey,
                          String customerNo, Integer version) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        if (version != null) {
            req = req.queryParam("version", version);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                dataset, axisValue, sheetKey);
    }

    // ── A-5 · GET /parts/{axisValue}/sheets/{sheetKey}/versions（加 customerNo） ─

    static Response versions(String session, String dataset, String axisValue, String sheetKey, String customerNo) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/versions",
                dataset, axisValue, sheetKey);
    }

    // ── A-6 · PUT /dataset/{dataset}/parts/{axisValue}（加 customerNo，api.md §2） ─

    static Response updatePart(String session, String dataset, String axisValue, String customerNo,
                                Map<String, Object> body) {
        var req = RestAssured.given()
                .cookie("CPQ_SESSION", session)
                .contentType(ContentType.JSON)
                .body(body);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().put("/api/cpq/dataset/{dataset}/parts/{axis}", dataset, axisValue);
    }

    // ── 既有 · GET /dataset/{dataset}/customer-parts（AC-4/AC-14①，不改契约） ─

    static Response customerParts(String session, String dataset, String customerNo) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session)
                .queryParam("page", 0).queryParam("size", 200);
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/customer-parts", dataset);
    }

    /** 便于打印排查：把 items 拍成 List&lt;Map&gt;。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Response r) {
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        return items == null ? List.of() : items;
    }
}
