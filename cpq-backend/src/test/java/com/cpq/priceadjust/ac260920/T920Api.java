package com.cpq.priceadjust.ac260920;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 被测接口封装（契约来源：本任务 api.md + main-api.md §12.X）。
 * 身份由 {@link #as} 决定：默认匿名（RBAC 关的 profile 下即可访问）；AC-29 传入带会话 cookie 的请求规格。
 */
public final class T920Api {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String BASE = "/api/cpq/price-adjust";
    public static final String REVIEWS = BASE + "/reviews";

    private final Supplier<RequestSpecification> as;

    public T920Api(Supplier<RequestSpecification> as) {
        this.as = as;
    }

    public static T920Api anonymous() {
        return new T920Api(RestAssured::given);
    }

    public static T920Api withCookies(Map<String, String> cookies) {
        return new T920Api(() -> RestAssured.given().cookies(cookies));
    }

    public static JsonNode json(Response r) {
        try {
            String s = r.asString();
            return MAPPER.readTree(s == null || s.isBlank() ? "null" : s);
        } catch (Exception e) {
            throw new AssertionError("响应不是 JSON: HTTP " + r.statusCode() + " " + r.asString(), e);
        }
    }

    // ── 版本生成（main-api §12.X.3） ──────────────────────────────────────────
    public UUID generateVersion(String customerNo) {
        Response r = as.get().contentType(ContentType.JSON)
            .body("{\"customerNo\":\"" + customerNo + "\",\"confirmSupersede\":true}")
            .post(BASE + "/versions/generate");
        if (r.statusCode() != 201 && r.statusCode() != 200) {
            throw new AssertionError("生成版本 HTTP " + r.statusCode() + " " + r.asString());
        }
        JsonNode j = json(r);
        String id = j.path("versionId").asText(null);
        if (id == null) id = j.path("data").path("versionId").asText(null);
        if (id == null) throw new AssertionError("生成版本响应没有 versionId: " + r.asString());
        return UUID.fromString(id);
    }

    // ── 审核列表 / 单行 / 点击即算（api.md §1 §2） ─────────────────────────────
    public Response listRaw(Map<String, Object> query) {
        RequestSpecification s = as.get();
        for (Map.Entry<String, Object> e : query.entrySet()) s = s.queryParam(e.getKey(), e.getValue());
        return s.get(REVIEWS);
    }

    public JsonNode list(Map<String, Object> query) {
        Response r = listRaw(query);
        if (r.statusCode() != 200) throw new AssertionError("审核列表 HTTP " + r.statusCode() + " " + r.asString());
        return json(r);
    }

    public static Map<String, Object> q(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public Response row(UUID reviewId) {
        return as.get().get(REVIEWS + "/" + reviewId + "/row");
    }

    public Response computeNow(UUID reviewId) {
        return as.get().contentType(ContentType.JSON).body("{}").post(REVIEWS + "/" + reviewId + "/compute-now");
    }

    public Response detail(UUID reviewId) {
        return as.get().get(REVIEWS + "/" + reviewId);
    }

    public Response recomputeBudget(UUID reviewId) {
        return as.get().contentType(ContentType.JSON).body("{}").post(REVIEWS + "/" + reviewId + "/recompute-budget");
    }

    // ── 影响面 / 通过 / 驳回（api.md §3 §5） ──────────────────────────────────
    public Response impact(Collection<UUID> ids) {
        return as.get().contentType(ContentType.JSON).body("{\"reviewIds\":" + idArray(ids) + "}").post(REVIEWS + "/impact");
    }

    public Response approve(Collection<UUID> ids) {
        return as.get().contentType(ContentType.JSON)
            .body("{\"reviewIds\":" + idArray(ids) + ",\"comment\":\"T260920 S-1\"}").post(REVIEWS + "/approve");
    }

    public Response reject(Collection<UUID> ids) {
        return as.get().contentType(ContentType.JSON)
            .body("{\"reviewIds\":" + idArray(ids) + ",\"reason\":\"T260920 S-1 驳回守门\"}").post(REVIEWS + "/reject");
    }

    // ── 策略 / 比对列（main-api §12.X.1 §12.X.2；AC-24 的两个触发入口） ─────────
    public JsonNode getStrategy(String customerNo) {
        Response r = as.get().get(BASE + "/strategies/" + customerNo);
        if (r.statusCode() != 200) throw new AssertionError("GET 策略 HTTP " + r.statusCode() + " " + r.asString());
        return unwrap(json(r));
    }

    /** 以 GET 结果为底，只改 costDiffThreshold 后 PUT（其余字段原样回写，避免猜格式）。 */
    public JsonNode putStrategyThreshold(String customerNo, String threshold) {
        JsonNode cur = getStrategy(customerNo);
        ObjectNode body = MAPPER.createObjectNode();
        for (String f : new String[]{"enabled", "cycleType", "cycleWeekday", "cycleDayOfMonth", "cycleNthWeek",
            "executeTime", "materialScopeMode"}) {
            if (cur.has(f)) body.set(f, cur.get(f));
        }
        body.put("costDiffThreshold", threshold);
        Response r = as.get().contentType(ContentType.JSON).body(body.toString()).put(BASE + "/strategies/" + customerNo);
        if (r.statusCode() != 200) throw new AssertionError("PUT 策略 HTTP " + r.statusCode() + " " + r.asString());
        return unwrap(json(r));
    }

    public JsonNode getColumns(String customerNo, UUID seriesId) {
        Response r = as.get().queryParam("customerNo", customerNo).queryParam("templateSeriesId", seriesId.toString())
            .get(BASE + "/comparison-columns");
        if (r.statusCode() != 200) throw new AssertionError("GET 比对列 HTTP " + r.statusCode() + " " + r.asString());
        return unwrap(json(r));
    }

    /** 以 GET 结果为底，把每一列的 threshold 改成给定值后 PUT。 */
    public Response putColumnsThreshold(String customerNo, UUID seriesId, String threshold) {
        JsonNode cur = getColumns(customerNo, seriesId);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("customerNo", customerNo);
        body.put("templateSeriesId", seriesId.toString());
        var cols = MAPPER.createArrayNode();
        for (JsonNode c : cur.path("columns")) {
            ObjectNode cc = ((ObjectNode) c).deepCopy();
            cc.put("threshold", threshold);
            cols.add(cc);
        }
        if (cols.isEmpty()) throw new AssertionError("前提：比对列 GET 应至少返回默认列: " + cur);
        body.set("columns", cols);
        return as.get().contentType(ContentType.JSON).body(body.toString()).put(BASE + "/comparison-columns");
    }

    // ── 工具 ────────────────────────────────────────────────────────────────
    /** 兼容裸 DTO 与 ApiResponse 包装两种形态（main-api §4：多数端点有包装，价格调整模块多为裸 DTO）。 */
    public static JsonNode unwrap(JsonNode j) {
        if (j != null && j.has("data") && j.has("code") && j.has("message") && j.get("data").isObject()) return j.get("data");
        return j;
    }

    /** api.md 头部：409 一律走 ReviewNotReadyException 信封，业务码在 {@code data.code}。 */
    public static String errorCode(Response r) {
        return json(r).path("data").path("code").asText(null);
    }

    public static JsonNode invalidItems(Response r) {
        return json(r).path("data").path("invalidItems");
    }

    private static String idArray(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (UUID id : ids) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(id).append('"');
        }
        return sb.append(']').toString();
    }
}
