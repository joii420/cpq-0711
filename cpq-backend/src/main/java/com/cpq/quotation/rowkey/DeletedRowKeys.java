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
 *
 * <p><b>repair-0727 B1 — nodeId 维度</b>（api.md §2.2）：树页签同一料号可能挂在多个不同
 * {@code __nodeId} 下，driverRow 内容完全相同（DAG 重复子件）时 fp 也相同，单靠 fp 无法区分
 * 删哪一条（BL-0055 残留，本次被 task-0721 树接入激活）。{@link Tombstone} 增加可空
 * {@code nodeId} 字段，匹配规则：
 * <pre>
 * 一行被判删 ⟺ 存在墓碑 t 满足：
 *     t.fp == row.fp
 *   且 ( t.nodeId 为空  或  row.__nodeId 为空  或  t.nodeId == row.__nodeId )
 * </pre>
 * <b>向后兼容</b>：旧墓碑（无 nodeId，{@code t.nodeId()==null}）× 树行 → 按上式条件恒真 → 退化
 * fp 单键匹配，与改造前逐字节一致；任意墓碑 × 非树行（{@code row.__nodeId==null}）→ 同样退化
 * fp 单键 → 非树页签行为逐字节不变。旧 3 参 {@link #keepMask(List, List, List)} delegate 到新 4 参
 * 重载、nodeIds 全传 null，即"每行 nodeId 视为空" → 与上式条件下永远满足第二个条件 → 恒等于
 * 改造前的纯 fp 匹配（该重载的全部既有单测保持通过，零回归）。
 */
public final class DeletedRowKeys {

    private static final ObjectMapper M = new ObjectMapper();

    private DeletedRowKeys() {}

    /** 墓碑记录：effKey + fp + nodeId（可空）三字段标识一条被删的 driver 默认行。 */
    public record Tombstone(String effKey, String fp, String nodeId) {
        /** 兼容旧两参构造（nodeId=null，即非树行 / 未升级调用方）。 */
        public Tombstone(String effKey, String fp) {
            this(effKey, fp, null);
        }
    }

    /**
     * 解析 JSON 墓碑数组字符串。
     * 格式：[{"effKey":"...","fp":"...","nodeId":"..."(可选)}, ...]
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
                JsonNode nodeIdNode = n.get("nodeId");
                String nodeId = (nodeIdNode != null && !nodeIdNode.isNull()) ? nodeIdNode.asText(null) : null;
                if (nodeId != null && nodeId.isBlank()) nodeId = null;
                if (!e.isEmpty()) out.add(new Tombstone(e, f, nodeId));
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
     * 按 fp（内容指纹）单键过滤，返回逐行 keep 掩码。旧 3 参签名，delegate 到新 4 参重载，
     * {@code nodeIds} 全传 null → 逐字节旧行为（见类注释「向后兼容」）。
     *
     * <p><b>2026-07-14 删错行修复</b>：原实现按 effKey+fp 双命中，但 effKey 由 computeRowKey 算，
     * 前端与服务端对同一行可不一致（driverRow 值在 {@code _料件} 下、rowKeyField 名为 {@code 料件} 时，
     * 前端解析失败退化成索引 "0"、服务端经字段定义解析成内容 "AgNi11#-Ⅰ"）→ 双命中的 effKey 对不上
     * → 墓碑在服务端匹配失败 → 删不掉行（前端只靠乐观态显示删了、且错位）。
     * <p>fp 是 driverRow 内容派生、前后端 {@code rowFingerprint} 逐字节一致的可靠身份（设计文档：
     * 「fp 本就唯一，uniqFp ≈ fp」），故改按 fp 单键匹配。effKeys 参数保留仅为签名兼容，不再参与匹配。
     * <p>边界：同 nodeId（或均无 nodeId）下字节级完全重复的两行 fp 相同 → 删一个会连删（此场景
     * 身份本就不可区分；归 BL-0055，§12.1 已知残留边界）。
     *
     * @param effKeys 各行 effKey（保留兼容，不参与匹配）
     * @param fps     各行指纹
     * @param deleted 墓碑列表
     * @return keep[i]=true 保留，false 删除
     */
    public static boolean[] keepMask(List<String> effKeys, List<String> fps, List<Tombstone> deleted) {
        return keepMask(effKeys, fps, null, deleted);
    }

    /**
     * repair-0727 B1 — 新 4 参重载：fp + nodeId 双维度匹配（api.md §2.2）。
     *
     * <p>匹配规则（每行 i，每条墓碑 t）：{@code t.fp==fps.get(i)} 且
     * （{@code t.nodeId()==null} 或 {@code nodeIds.get(i)==null} 或 {@code t.nodeId().equals(nodeIds.get(i))}）
     * → 该行判删。effKeys 同旧重载，保留仅为签名兼容，不参与匹配。
     *
     * @param effKeys 各行 effKey（保留兼容，不参与匹配）
     * @param fps     各行指纹（与 baseRows 等长）
     * @param nodeIds 各行 {@code __nodeId}（与 baseRows 等长；null 或整体传 null 列表 = 该行/全部行视作非树行）
     * @param deleted 墓碑列表
     * @return keep[i]=true 保留，false 删除
     */
    public static boolean[] keepMask(List<String> effKeys, List<String> fps, List<String> nodeIds,
                                      List<Tombstone> deleted) {
        boolean[] keep = new boolean[fps.size()];
        java.util.Arrays.fill(keep, true);
        if (deleted == null || deleted.isEmpty()) return keep;
        for (int i = 0; i < fps.size(); i++) {
            String fp = fps.get(i);
            String nodeId = (nodeIds != null && i < nodeIds.size()) ? nodeIds.get(i) : null;
            if (isDeleted(fp, nodeId, deleted)) keep[i] = false;
        }
        return keep;
    }

    /**
     * repair-0727 B5 — 单行版本：同 {@link #keepMask} 的匹配语义（api.md §2.2），供逐行场景
     * （如 {@code RowKeyUniquenessService} 提交期判重过滤）直接判断，无需先拼等长数组。
     *
     * @param fp      该行指纹
     * @param nodeId  该行 {@code __nodeId}（null = 非树行）
     * @param deleted 墓碑列表
     * @return true = 该行已被删除（不应参与判重/渲染）
     */
    public static boolean isDeleted(String fp, String nodeId, List<Tombstone> deleted) {
        if (deleted == null || deleted.isEmpty() || fp == null) return false;
        for (Tombstone t : deleted) {
            if (t.fp() == null || !t.fp().equals(fp)) continue;
            if (t.nodeId() == null || nodeId == null || t.nodeId().equals(nodeId)) return true;
        }
        return false;
    }
}
