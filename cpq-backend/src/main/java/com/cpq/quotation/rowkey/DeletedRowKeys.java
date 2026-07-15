package com.cpq.quotation.rowkey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/**
 * driver 默认行墓碑工具：指纹 + 双命中过滤。
 * 前端 deletedRows.ts 为对端等价实现，canon 规则与 keepMask 语义须严格对齐。
 *
 * <p>fp 规范化规则：
 * <ul>
 *   <li>取值序列 = rowKeyFieldNames 按序各取字段值，再拼 driverRow 全部键按键名升序的值</li>
 *   <li>canon(v)：null/缺失 → "∅"；boolean → "true"/"false"；
 *       number → 整数无小数点（"7"），否则去尾零（"7.12"、"7.1"）；string → 原串</li>
 *   <li>fp = 值序列以 "" 连接（不哈希，直接规范串）</li>
 * </ul>
 *
 * <p>双命中：一行被判删 ⟺ 存在墓碑的 effKey 与 fp 都等于该行的 effKey 与 fp。
 */
public final class DeletedRowKeys {

    private static final ObjectMapper M = new ObjectMapper();

    private DeletedRowKeys() {}

    /** 墓碑记录：effKey + fp 双字段标识一条被删的 driver 默认行。 */
    public record Tombstone(String effKey, String fp) {}

    /**
     * 解析 JSON 墓碑数组字符串。
     * 格式：[{"effKey":"...","fp":"..."}, ...]
     * null / 空白 / 空数组 → 返回空列表（不抛异常）。
     */
    public static List<Tombstone> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = M.readTree(json);
            if (!arr.isArray()) return List.of();
            List<Tombstone> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String e = n.path("effKey").asText("");
                String f = n.path("fp").asText("");
                if (!e.isEmpty()) out.add(new Tombstone(e, f));
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * canon(v)：将 JsonNode 值规范化为字符串。
     * <ul>
     *   <li>null / MissingNode / NullNode → "∅"</li>
     *   <li>boolean → "true" / "false"</li>
     *   <li>number → stripTrailingZeros；scale≤0 → 整数串；否则 toPlainString</li>
     *   <li>其他 → asText()</li>
     * </ul>
     */
    static String canon(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return "∅";
        if (v.isBoolean()) return v.asBoolean() ? "true" : "false";
        if (v.isNumber()) {
            BigDecimal d = v.decimalValue().stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        }
        return v.asText();
    }

    /**
     * 计算 driverRow 的指纹。
     *
     * @param rowKeyFieldNames 主键字段列表（按序优先拼入）
     * @param driverRow        行数据（Jackson JsonNode，字段名→值）
     * @return 规范化指纹串
     */
    public static String rowFingerprint(List<String> rowKeyFieldNames, JsonNode driverRow) {
        List<String> parts = new ArrayList<>();
        // 1. rowKeyFieldNames 按序取值（允许与全量键重复，契约规定如此）
        if (rowKeyFieldNames != null) {
            for (String name : rowKeyFieldNames) {
                parts.add(canon(driverRow == null ? null : driverRow.get(name)));
            }
        }
        // 2. driverRow 全部键按键名升序取值
        if (driverRow != null) {
            List<String> keys = new ArrayList<>();
            driverRow.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String k : keys) parts.add(canon(driverRow.get(k)));
        }
        return String.join("", parts);
    }

    /**
     * 按 fp（内容指纹）单键过滤，返回逐行 keep 掩码。
     *
     * <p><b>2026-07-14 删错行修复</b>：原实现按 effKey+fp 双命中，但 effKey 由 computeRowKey 算，
     * 前端与服务端对同一行可不一致（driverRow 值在 {@code _料件} 下、rowKeyField 名为 {@code 料件} 时，
     * 前端解析失败退化成索引 "0"、服务端经字段定义解析成内容 "AgNi11#-Ⅰ"）→ 双命中的 effKey 对不上
     * → 墓碑在服务端匹配失败 → 删不掉行（前端只靠乐观态显示删了、且错位）。
     * <p>fp 是 driverRow 内容派生、前后端 {@code rowFingerprint} 逐字节一致的可靠身份（设计文档：
     * 「fp 本就唯一，uniqFp ≈ fp」），故改按 fp 单键匹配。effKeys 参数保留仅为签名兼容，不再参与匹配。
     * <p>边界：字节级完全重复的两行 fp 相同 → 删一个会连删（此场景身份本就不可区分；Phase 2 的
     * uniqFp 若引入 #序号再细分）。
     *
     * @param effKeys 各行 effKey（保留兼容，不参与匹配）
     * @param fps     各行指纹
     * @param deleted 墓碑列表
     * @return keep[i]=true 保留，false 删除
     */
    public static boolean[] keepMask(List<String> effKeys, List<String> fps, List<Tombstone> deleted) {
        Set<String> delFps = new HashSet<>();
        for (Tombstone t : deleted) {
            if (t.fp() != null) delFps.add(t.fp());
        }
        boolean[] keep = new boolean[fps.size()];
        for (int i = 0; i < fps.size(); i++) {
            keep[i] = !delFps.contains(fps.get(i));  // fp 命中 → 删除
        }
        return keep;
    }
}
