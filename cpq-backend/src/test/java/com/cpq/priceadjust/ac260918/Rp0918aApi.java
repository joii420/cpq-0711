package com.cpq.priceadjust.ac260918;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 价格调整模块对外接口（main-api.md §12.X + 本任务 api.md）的调用封装。所有响应都是裸 DTO（不套信封）。
 * 解析用 Jackson，不用 GPath —— 精度字段是十进制字符串，按 {@link BigDecimal} 比较。
 */
public final class Rp0918aApi {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/cpq/price-adjust";

    private Rp0918aApi() {
    }

    public static JsonNode json(Response r) {
        try {
            return MAPPER.readTree(r.asString());
        } catch (Exception e) {
            throw new AssertionError("响应不是 JSON: HTTP " + r.statusCode() + " " + r.asString(), e);
        }
    }

    // ── 版本生成（用户入口：定价管理 → 立即生成一次） ─────────────────────────

    /** {@code POST /versions/generate}（main-api §12.X.3），返回新版本 id；期望 201。 */
    public static UUID generateVersion(String customerNo, boolean confirmSupersede) {
        Response r = RestAssured.given().contentType(ContentType.JSON)
            .body("{\"customerNo\":\"" + customerNo + "\",\"confirmSupersede\":" + confirmSupersede + "}")
            .post(BASE + "/versions/generate");
        if (r.statusCode() != 201 && r.statusCode() != 200) {
            throw new AssertionError("生成版本 HTTP " + r.statusCode() + " " + r.asString());
        }
        String id = json(r).path("versionId").asText(null);
        if (id == null) throw new AssertionError("生成版本响应没有 versionId: " + r.asString());
        return UUID.fromString(id);
    }

    // ── 审核 ───────────────────────────────────────────────────────────────

    public static Response approve(Collection<UUID> reviewIds) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : reviewIds) {
            if (ids.length() > 0) ids.append(',');
            ids.append('"').append(id).append('"');
        }
        return RestAssured.given().contentType(ContentType.JSON)
            .body("{\"reviewIds\":[" + ids + "],\"comment\":\"RP0918A S-BE\"}")
            .post(BASE + "/reviews/approve");
    }

    /** 通过并返回 jobId；断言 202。 */
    public static UUID approveOk(Collection<UUID> reviewIds) {
        Response r = approve(reviewIds);
        if (r.statusCode() != 202) throw new AssertionError("approve 期望 202，实际 " + r.statusCode() + " " + r.asString());
        String jobId = json(r).path("jobId").asText(null);
        if (jobId == null) throw new AssertionError("approve 响应没有 jobId: " + r.asString());
        return UUID.fromString(jobId);
    }

    public static Response reviewDetail(UUID reviewId) {
        return RestAssured.given().get(BASE + "/reviews/" + reviewId);
    }

    public static JsonNode reviewList(String customerNo) {
        Response r = RestAssured.given().queryParam("customerNo", customerNo).queryParam("size", 500)
            .get(BASE + "/reviews");
        if (r.statusCode() != 200) throw new AssertionError("审核列表 HTTP " + r.statusCode() + " " + r.asString());
        return json(r);
    }

    public static Response recomputeBudget(UUID reviewId) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post(BASE + "/reviews/" + reviewId + "/recompute-budget");
    }

    /** 审核详情「三、逐单明细」里 isBasis=true 的那一行；没有则 null。 */
    public static JsonNode basisRow(JsonNode detail) {
        for (JsonNode q : detail.path("quotations")) {
            if (q.path("isBasis").asBoolean(false)) return q;
        }
        return null;
    }

    public static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String t = n.asText();
        if (t == null || t.isBlank()) return null;
        return new BigDecimal(t);
    }

    // ── 更新任务 ─────────────────────────────────────────────────────────────

    public static Response jobRaw(UUID jobId) {
        return RestAssured.given().get(BASE + "/jobs/" + jobId);
    }

    public static JsonNode job(UUID jobId) {
        Response r = jobRaw(jobId);
        if (r.statusCode() != 200) throw new AssertionError("GET /jobs/" + jobId + " HTTP " + r.statusCode() + " " + r.asString());
        return json(r);
    }

    public static JsonNode items(UUID jobId) {
        Response r = RestAssured.given().queryParam("size", 500).get(BASE + "/jobs/" + jobId + "/items");
        if (r.statusCode() != 200) throw new AssertionError("GET items HTTP " + r.statusCode() + " " + r.asString());
        return json(r).path("content");
    }

    /** itemId → 明细 JSON。 */
    public static Map<String, JsonNode> itemsById(UUID jobId) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode it : items(jobId)) out.put(it.path("itemId").asText(), it);
        return out;
    }

    /** materialNo → 明细 JSON 列表（同一料号可能在多张单上各有一条）。 */
    public static Map<String, List<JsonNode>> itemsByMaterial(UUID jobId) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (JsonNode it : items(jobId)) out.computeIfAbsent(it.path("materialNo").asText(), k -> new ArrayList<>()).add(it);
        return out;
    }

    public static Response retryItem(UUID itemId) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post(BASE + "/job-items/" + itemId + "/retry");
    }

    public static Response retryJob(UUID jobId) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post(BASE + "/jobs/" + jobId + "/retry");
    }

    // ── 等待（直接读库状态，50ms 粒度，计时不被 HTTP 往返放大） ────────────────

    /** 等批次离开 RUNNING；返回终态。超时抛 AssertionError（「永久停在执行中」本身就是被测缺陷）。 */
    public static String awaitJobTerminal(Rp0918aDb db, UUID jobId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String st = null;
        while (System.currentTimeMillis() < deadline) {
            st = db.text("SELECT status FROM material_price_update_job WHERE id = :id", "id", jobId);
            if (st != null && !"RUNNING".equals(st)) return st;
            Rp0918aFixture.sleep(50);
        }
        throw new AssertionError("批次 " + jobId + " 在 " + timeoutMillis + "ms 内未离开 RUNNING（最后状态 " + st + "）");
    }

    /** 等某条明细满足条件（读库）；返回 [status, error_code, error_message, retry_count]。 */
    public static Object[] awaitItem(Rp0918aDb db, UUID itemId, Predicate<Object[]> done, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Object[] last = null;
        while (System.currentTimeMillis() < deadline) {
            List<Object[]> r = db.rows("SELECT status, error_code, error_message, retry_count FROM material_price_update_job_item "
                + "WHERE id = :id", "id", itemId);
            if (!r.isEmpty()) {
                last = r.get(0);
                if (done.test(last)) return last;
            }
            Rp0918aFixture.sleep(50);
        }
        throw new AssertionError("明细 " + itemId + " 在 " + timeoutMillis + "ms 内未达到期望状态，最后 = "
            + (last == null ? "无" : last[0] + "/" + last[1] + "/" + last[2] + "/retry=" + last[3]));
    }

    public static boolean isTerminal(String itemStatus) {
        return itemStatus != null && !"WAITING".equals(itemStatus) && !"RUNNING".equals(itemStatus);
    }
}
