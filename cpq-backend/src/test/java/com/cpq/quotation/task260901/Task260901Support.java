package com.cpq.quotation.task260901;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260901 后端契约测试的公共读写工具。
 *
 * <p>🔑 全部走 <b>HTTP + 原始 JSON 串</b>，不引用任何 {@code SaveDraftRequest} 之类的 Java DTO ——
 * 断言口径只能来自 {@code api.md} 契约，不能来自实现的类结构。
 * DTO 字段一改名，测试**应该**红（那是契约变了）；如果测试跟着实现的字段名走，就永远不会红。
 */
final class Task260901Support {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Task260901Support() {}

    // ────────────────────────── HTTP ──────────────────────────

    static Response putDraft(UUID quotationId, String jsonBody) {
        return RestAssured.given().contentType(ContentType.JSON).body(jsonBody)
                .when().put("/api/cpq/quotations/" + quotationId + "/draft");
    }

    static Response getQuotation(UUID quotationId) {
        return RestAssured.given().when().get("/api/cpq/quotations/" + quotationId);
    }

    static Response postEmpty(UUID quotationId, String action) {
        return RestAssured.given().contentType(ContentType.JSON)
                .when().post("/api/cpq/quotations/" + quotationId + "/" + action);
    }

    static Response putQuoteCardEdit(UUID lineItemId, String jsonBody) {
        return RestAssured.given().contentType(ContentType.JSON).body(jsonBody)
                .when().put("/api/cpq/quotations/line-items/" + lineItemId + "/quote-card-edit");
    }

    static JsonNode json(Response r) {
        try {
            return MAPPER.readTree(r.asString());
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + r.asString(), e);
        }
    }

    /** 断言 200 并返回 data 节点；失败时把响应原文带出来（否则排错要重跑一遍）。 */
    static JsonNode ok(Response r, String what) {
        assertEquals(200, r.statusCode(), what + " 应返回 200，实际 " + r.statusCode() + "，响应：" + r.asString());
        JsonNode body = json(r);
        assertEquals(200, body.path("code").asInt(-1), what + " 响应 code 应为 200，响应：" + r.asString());
        JsonNode data = body.path("data");
        assertTrue(!data.isMissingNode() && !data.isNull(), what + " 响应 data 不应为空，响应：" + r.asString());
        return data;
    }

    // ────────────────────────── 请求体构造（api.md §1.2） ──────────────────────────

    /** 单头 patch + 三数组的完整请求体。 */
    static String draftBody(long baseVersion, String headerFieldsJson, String added, String modified, String removed) {
        StringBuilder sb = new StringBuilder("{\"baseVersion\":").append(baseVersion);
        if (headerFieldsJson != null && !headerFieldsJson.isBlank()) sb.append(',').append(headerFieldsJson);
        sb.append(",\"added\":").append(added == null ? "[]" : added);
        sb.append(",\"modified\":").append(modified == null ? "[]" : modified);
        sb.append(",\"removed\":").append(removed == null ? "[]" : removed);
        return sb.append('}').toString();
    }

    /** 一条 modified 行：以 id 复用既有行，带一个页签的 componentData。 */
    static String modifiedLine(UUID lineId, UUID templateId, int sortOrder, UUID componentId, String tabName, String rowDataJson) {
        return "{\"id\":\"" + lineId + "\",\"templateId\":\"" + templateId + "\",\"sortOrder\":" + sortOrder +
               ",\"compositeType\":\"SIMPLE\",\"componentData\":[{" +
               "\"componentId\":\"" + componentId + "\",\"tabName\":\"" + tabName + "\"," +
               "\"rowData\":" + quote(rowDataJson) + ",\"sortOrder\":0}]}";
    }

    /**
     * 一条 modified 行，且**显式携带 processNos**（api.md §1.2 列明 LineItemDraft 含该字段）。
     * 与 {@link #modifiedLine} 配对使用，用来区分两种可能的后端语义：
     * 「payload 里没带 processNos ⇒ 视为清空」还是「无论带不带都清空」。
     */
    static String modifiedLineWithProcesses(UUID lineId, UUID templateId, int sortOrder, UUID componentId,
                                            String tabName, String rowDataJson, List<String> processNos,
                                            List<String> compositeDefCodes) {
        StringBuilder pn = new StringBuilder("[");
        for (int i = 0; i < processNos.size(); i++) {
            if (i > 0) pn.append(',');
            pn.append('"').append(processNos.get(i)).append('"');
        }
        pn.append(']');
        // api.md §1.2：LineItemDraft 同时含 processNos 与 compositeProcesses，真实 payload 两者都带
        StringBuilder cp = new StringBuilder("[");
        for (int i = 0; i < compositeDefCodes.size(); i++) {
            if (i > 0) cp.append(',');
            cp.append("{\"defCode\":\"").append(compositeDefCodes.get(i))
              .append("\",\"seqNo\":").append(i + 1)
              .append(",\"participatingParts\":[\"T260901-PART\"]")
              .append(",\"paramValues\":{\"T260901-KEY\":\"").append(compositeDefCodes.get(i)).append("\"}}");
        }
        cp.append(']');
        return "{\"id\":\"" + lineId + "\",\"templateId\":\"" + templateId + "\",\"sortOrder\":" + sortOrder +
               ",\"compositeType\":\"SIMPLE\",\"processNos\":" + pn +
               ",\"compositeProcesses\":" + cp + ",\"componentData\":[{" +
               "\"componentId\":\"" + componentId + "\",\"tabName\":\"" + tabName + "\"," +
               "\"rowData\":" + quote(rowDataJson) + ",\"sortOrder\":0}]}";
    }

    /**
     * 一条 added 行：id 必须为 null，带 tempId 供响应认领（api.md §1.3）。
     * sortOrder 在增量协议下**必填**（api.md §1.2 「三处与数组下标解耦」）。
     */
    static String addedLine(String tempId, UUID productId, UUID templateId, int sortOrder,
                            UUID componentId, String tabName, String rowDataJson, String partNo) {
        return "{\"id\":null,\"tempId\":\"" + tempId + "\",\"productId\":\"" + productId + "\"," +
               "\"templateId\":\"" + templateId + "\",\"sortOrder\":" + sortOrder + ",\"compositeType\":\"SIMPLE\"," +
               "\"productPartNo\":\"" + partNo + "\",\"annualVolume\":1,\"componentData\":[{" +
               "\"componentId\":\"" + componentId + "\",\"tabName\":\"" + tabName + "\"," +
               "\"rowData\":" + quote(rowDataJson) + ",\"sortOrder\":0}]}";
    }

    /**
     * {@code rowData} 在既有契约里是**字符串**（{@code SaveDraftRequest.ComponentDataDraft.rowData}
     * 在既有测试 {@code SaveDraftComponentDataUpsertTest} 里就是 String 字面量），
     * 故这里把 JSON 数组整体转义成 JSON 字符串。
     */
    static String quote(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ────────────────────────── DB 只读 ──────────────────────────

    @SuppressWarnings("unchecked")
    static Object scalar(EntityManager em, String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        List<Object> rows = q.getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    static long count(EntityManager em, String sql, Object... params) {
        Object v = scalar(em, sql, params);
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    /** {@code user_data_version}。列不存在时**显式失败**，不返回 0 兜底（兜底会让 N→N+1 静默变成 0→0）。 */
    static long userDataVersion(EntityManager em, UUID quotationId) {
        long colExists = count(em,
                "SELECT count(*) FROM information_schema.columns WHERE table_name='quotation' AND column_name='user_data_version'");
        assertEquals(1L, colExists,
                "quotation.user_data_version 列不存在 —— ③ 版本指纹尚未落库（迁移未跑？），AC-11/12/13/14 无从验证");
        Object v = scalar(em, "SELECT user_data_version FROM quotation WHERE id=?1", quotationId);
        assertNotNull(v, "报价单不存在或 user_data_version 为空：" + quotationId);
        return Long.parseLong(v.toString());
    }

    static String rowDataText(EntityManager em, UUID componentDataId) {
        Object v = scalar(em, "SELECT row_data::text FROM quotation_line_component_data WHERE id=?1", componentDataId);
        return v == null ? null : v.toString();
    }

    /**
     * PG 行版本号（xmin）—— 判「这一行到底有没有被 UPDATE 过」的物理判据。
     * 内容比对只能证明"内容没变"，证明不了"没写过"；AC-9 说的正是「不产生 UPDATE」。
     */
    static String xminOfComponentData(EntityManager em, UUID componentDataId) {
        Object v = scalar(em, "SELECT xmin::text FROM quotation_line_component_data WHERE id=?1", componentDataId);
        return v == null ? null : v.toString();
    }

    static String xminOfLineItem(EntityManager em, UUID lineItemId) {
        Object v = scalar(em, "SELECT xmin::text FROM quotation_line_item WHERE id=?1", lineItemId);
        return v == null ? null : v.toString();
    }

    static String cardValues(EntityManager em, UUID lineItemId, String column) {
        Object v = scalar(em, "SELECT " + column + "::text FROM quotation_line_item WHERE id=?1", lineItemId);
        return v == null ? null : v.toString();
    }

    static long lineCount(EntityManager em, UUID quotationId) {
        return count(em, "SELECT count(*) FROM quotation_line_item WHERE quotation_id=?1", quotationId);
    }

    static long processCount(EntityManager em, UUID lineItemId) {
        return count(em, "SELECT count(*) FROM quotation_line_process WHERE line_item_id=?1", lineItemId);
    }

    static long componentDataCount(EntityManager em, UUID lineItemId) {
        return count(em, "SELECT count(*) FROM quotation_line_component_data WHERE line_item_id=?1", lineItemId);
    }

    static long lineSnapshotCount(EntityManager em, UUID lineItemId) {
        return count(em, "SELECT count(*) FROM quotation_line_item_snapshot WHERE line_item_id=?1", lineItemId);
    }

    static long compositeProcessCount(EntityManager em, UUID lineItemId) {
        return count(em, "SELECT count(*) FROM quotation_line_composite_process WHERE line_item_id=?1", lineItemId);
    }

    /** 组合工艺的内容指纹（AC-20 要求「记录数**与内容**都一致」，光比条数不够）。 */
    static String compositeProcessFingerprint(EntityManager em, UUID lineItemId) {
        Object v = scalar(em,
                // 同上：按内容排序，避免行重建导致的假红
                "SELECT coalesce(md5(string_agg(def_code||coalesce(seq_no::text,'')||coalesce(param_values::text,'')" +
                "||coalesce(participating_parts::text,''), ',' ORDER BY def_code)),'<empty>') " +
                "FROM quotation_line_composite_process WHERE line_item_id=?1", lineItemId);
        return v == null ? "<empty>" : v.toString();
    }

    static String processFingerprint(EntityManager em, UUID lineItemId) {
        Object v = scalar(em,
                // 🚨 按**内容**排序，不能按 id：工序行在保存时可能被 delete+insert 重建，
                //    新行拿到新 uuid 会打乱 ORDER BY id 的顺序，于是内容没变指纹也变 ——
                //    那样断的就不是 AC-20 说的「内容一致」，而是「行 id 没变」，判据跑偏。
                "SELECT coalesce(md5(string_agg(coalesce(process_no,''), ',' ORDER BY coalesce(process_no,''))),'<empty>') " +
                "FROM quotation_line_process WHERE line_item_id=?1", lineItemId);
        return v == null ? "<empty>" : v.toString();
    }
}
