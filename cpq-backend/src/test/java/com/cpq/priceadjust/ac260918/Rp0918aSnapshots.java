package com.cpq.priceadjust.ac260918;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报价单与其版本记录（{@code quotation_price_revision}）的读取、指纹与「按口径独立计算的期望快照」。
 * 期望快照的口径<b>只取自 AC-12 原文</b>：键 = 产品行 id；行卡片值为 SQL NULL ⇒ JSON null；
 * 没有组件数据的行 ⇒ {@code {}}；{@code component_id} 为空 ⇒ 键 {@code "null"}。
 */
public final class Rp0918aSnapshots {

    private final Rp0918aDb db;

    public Rp0918aSnapshots(Rp0918aDb db) {
        this.db = db;
    }

    public record Revision(UUID id, String revisionNo, UUID basedVersionId, boolean sealed,
                           List<String> upgradedMaterialNos, JsonNode quoteCardValues, JsonNode costingCardValues,
                           JsonNode snapshotRows, String lastUpdatedAt, String quoteTotalAmount) {
    }

    public List<Revision> revisions(UUID quotationId) {
        List<Revision> out = new ArrayList<>();
        for (Object[] r : db.rows("SELECT id, revision_no, based_version_id, sealed, upgraded_material_nos::text, "
            + "quote_card_values::text, costing_card_values::text, snapshot_rows::text, last_updated_at::text, "
            + "quote_total_amount::text FROM quotation_price_revision WHERE quotation_id = :q ORDER BY created_at, id",
            "q", quotationId)) {
            List<String> mats = new ArrayList<>();
            JsonNode matNode = parse((String) r[4]);
            if (matNode != null) for (JsonNode m : matNode) mats.add(m.asText());
            out.add(new Revision((UUID) r[0], (String) r[1], (UUID) r[2], Boolean.TRUE.equals(r[3]), mats,
                parse((String) r[5]), parse((String) r[6]), parse((String) r[7]), (String) r[8], (String) r[9]));
        }
        return out;
    }

    public Revision current(UUID quotationId, UUID versionId) {
        for (Revision r : revisions(quotationId)) if (versionId.equals(r.basedVersionId())) return r;
        return null;
    }

    public Revision initial(UUID quotationId) {
        for (Revision r : revisions(quotationId)) if (r.basedVersionId() == null) return r;
        return null;
    }

    /** 版本记录行的「身份 + 最后更新时间」列表（AC-7：行数与各行 last_updated_at 不变）。 */
    public List<String> revisionStamps(UUID quotationId) {
        List<String> out = new ArrayList<>();
        for (Object o : db.column("SELECT id::text || '|' || coalesce(based_version_id::text, 'INITIAL') || '|' || sealed "
            + "|| '|' || last_updated_at::text || '|' || md5(coalesce(snapshot_rows::text, '')) FROM quotation_price_revision "
            + "WHERE quotation_id = :q ORDER BY id", "q", quotationId)) {
            out.add((String) o);
        }
        return out;
    }

    /** 全部产品行的 subtotal + quote_card_values 聚合 md5（AC-7），附带行数。 */
    public String allLinesSubtotalAndQuoteCardDigest(UUID quotationId) {
        return db.text("SELECT count(*) || ':' || md5(string_agg(id::text || '|' || coalesce(subtotal::text, '∅') || '|' "
            + "|| coalesce(md5(quote_card_values::text), '∅'), ',' ORDER BY id)) FROM quotation_line_item "
            + "WHERE quotation_id = :q", "q", quotationId);
    }

    /** 全部产品行的页签数据（snapshot_rows + row_data）聚合 md5（AC-7 附加项）。 */
    public String allComponentDataDigest(UUID quotationId) {
        return db.text("SELECT count(*) || ':' || md5(string_agg(cd.id::text || '|' || coalesce(cd.snapshot_rows::text, '∅') "
            + "|| '|' || coalesce(cd.row_data::text, '∅') || '|' || cd.row_version, ',' ORDER BY cd.id)) "
            + "FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id = cd.line_item_id "
            + "WHERE li.quotation_id = :q", "q", quotationId);
    }

    /** 除 {@code excluded} 外全部产品行 4 列的聚合 md5（AC-11 加强项：不止抽 3 行）。 */
    public String untouchedLinesDigest(UUID quotationId, Collection<UUID> excluded) {
        return db.text("SELECT count(*) || ':' || md5(string_agg(li.id::text || '|' || coalesce(li.subtotal::text, '∅') "
            + "|| '|' || coalesce(md5(li.quote_card_values::text), '∅') || '|' || coalesce((SELECT string_agg("
            + "coalesce(cd.component_id::text, 'null') || ':' || coalesce(md5(cd.snapshot_rows::text), '∅') || ':' || "
            + "coalesce(md5(cd.row_data::text), '∅'), ',' ORDER BY cd.component_id NULLS FIRST, cd.id) "
            + "FROM quotation_line_component_data cd WHERE cd.line_item_id = li.id), '∅'), ',' ORDER BY li.id)) "
            + "FROM quotation_line_item li WHERE li.quotation_id = :q AND NOT (li.id IN (:ex))",
            "q", quotationId, "ex", new ArrayList<>(excluded));
    }

    /** AC-11：单行 4 列（snapshot_rows / row_data / quote_card_values / subtotal）各自的 md5。 */
    public Map<String, String> lineFourDigests(UUID lineId) {
        Object[] r = db.rows("SELECT md5(coalesce(li.subtotal::text, '∅')), md5(coalesce(li.quote_card_values::text, '∅')), "
            + "md5(coalesce((SELECT string_agg(coalesce(cd.component_id::text, 'null') || ':' || coalesce(cd.snapshot_rows::text, '∅'), "
            + "',' ORDER BY cd.component_id NULLS FIRST, cd.id) FROM quotation_line_component_data cd WHERE cd.line_item_id = li.id), '∅')), "
            + "md5(coalesce((SELECT string_agg(coalesce(cd.component_id::text, 'null') || ':' || coalesce(cd.row_data::text, '∅'), "
            + "',' ORDER BY cd.component_id NULLS FIRST, cd.id) FROM quotation_line_component_data cd WHERE cd.line_item_id = li.id), '∅')) "
            + "FROM quotation_line_item li WHERE li.id = :id", "id", lineId).get(0);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("subtotal", (String) r[0]);
        m.put("quote_card_values", (String) r[1]);
        m.put("snapshot_rows", (String) r[2]);
        m.put("row_data", (String) r[3]);
        return m;
    }

    /**
     * 期望快照三列（AC-12）：直接从 {@code quotation_line_item} / {@code quotation_line_component_data} 当前内容
     * 在 Java 侧按口径拼出，不经过被测实现的任何代码。返回 {quote, costing, rows}。
     */
    public ObjectNode[] expectedWholeQuoteSnapshot(UUID quotationId) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode quote = f.objectNode();
        ObjectNode costing = f.objectNode();
        ObjectNode rows = f.objectNode();
        for (Object[] r : db.rows("SELECT id::text, quote_card_values::text, costing_card_values::text "
            + "FROM quotation_line_item WHERE quotation_id = :q", "q", quotationId)) {
            String lid = (String) r[0];
            quote.set(lid, r[1] == null ? f.nullNode() : parse((String) r[1]));
            costing.set(lid, r[2] == null ? f.nullNode() : parse((String) r[2]));
            rows.set(lid, f.objectNode());
        }
        for (Object[] r : db.rows("SELECT cd.line_item_id::text, cd.component_id::text, cd.snapshot_rows::text "
            + "FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id = cd.line_item_id "
            + "WHERE li.quotation_id = :q", "q", quotationId)) {
            ObjectNode perLine = (ObjectNode) rows.get((String) r[0]);
            String key = r[1] == null ? "null" : (String) r[1];
            perLine.set(key, r[2] == null ? f.nullNode() : parse((String) r[2]));
        }
        return new ObjectNode[]{quote, costing, rows};
    }

    /** 两棵 JSON 的第一处差异路径；相等返回 null。 */
    public static String firstDiff(JsonNode expected, JsonNode actual, String path) {
        if (expected == null && actual == null) return null;
        if (expected == null || actual == null) return path + ": expected=" + expected + " actual=" + abbreviate(actual);
        if (expected.equals(actual)) return null;
        if (expected.isObject() && actual.isObject()) {
            Iterator<String> it = expected.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (!actual.has(k)) return path + "." + k + ": 实际缺失";
                String d = firstDiff(expected.get(k), actual.get(k), path + "." + k);
                if (d != null) return d;
            }
            Iterator<String> it2 = actual.fieldNames();
            while (it2.hasNext()) {
                String k = it2.next();
                if (!expected.has(k)) return path + "." + k + ": 实际多出 " + abbreviate(actual.get(k));
            }
            return path + ": 对象不等（原因未定位）";
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return path + ": 数组长度 " + expected.size() + " vs " + actual.size();
            for (int i = 0; i < expected.size(); i++) {
                String d = firstDiff(expected.get(i), actual.get(i), path + "[" + i + "]");
                if (d != null) return d;
            }
            return path + ": 数组不等（原因未定位）";
        }
        return path + ": expected=" + abbreviate(expected) + " actual=" + abbreviate(actual);
    }

    public static String abbreviate(JsonNode n) {
        if (n == null) return "null";
        String s = n.toString();
        return s.length() > 200 ? s.substring(0, 200) + "…(" + s.length() + " chars)" : s;
    }

    public static JsonNode parse(String text) {
        if (text == null) return null;
        try {
            return Rp0918aApi.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + (text.length() > 200 ? text.substring(0, 200) : text), e);
        }
    }
}
