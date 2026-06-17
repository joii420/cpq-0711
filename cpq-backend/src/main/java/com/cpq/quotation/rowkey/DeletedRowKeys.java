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
     * effKey 与 fp 双命中过滤，返回逐行 keep 掩码。
     *
     * <p>实现：用嵌套 Map&lt;effKey, Set&lt;fp&gt;&gt; 存储墓碑，独立双字段比较，
     * 消除字符串拼接的理论碰撞（与前端 some(t=&gt;t.effKey===ek&amp;&amp;t.fp===fp) 语义等价）。
     *
     * @param effKeys 各行的 effKey（与 fps 等长）
     * @param fps     各行的指纹（与 effKeys 等长）
     * @param deleted 墓碑列表
     * @return keep[i]=true 表示第 i 行保留，false 表示被删除
     */
    public static boolean[] keepMask(List<String> effKeys, List<String> fps, List<Tombstone> deleted) {
        // 构建 Map<effKey, Set<fp>> 索引
        Map<String, Set<String>> index = new HashMap<>();
        for (Tombstone t : deleted) {
            index.computeIfAbsent(t.effKey(), k -> new HashSet<>()).add(t.fp());
        }
        boolean[] keep = new boolean[effKeys.size()];
        for (int i = 0; i < effKeys.size(); i++) {
            String ek = effKeys.get(i);
            String fp = fps.get(i);
            Set<String> fpSet = index.get(ek);
            // 双命中：effKey 命中 AND fp 命中 → 删除（keep=false）
            keep[i] = fpSet == null || !fpSet.contains(fp);
        }
        return keep;
    }
}
