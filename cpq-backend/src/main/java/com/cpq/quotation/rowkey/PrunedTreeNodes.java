package com.cpq.quotation.rowkey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * repair-0727 B5 — 树节点级墓碑（{@code quotation_line_item.deleted_tree_nodes}）解析 + 前缀匹配
 * 共用工具。抽取自 {@code CardSnapshotService}（task-0721 B7/B11 引入的 {@code parsePrunedNodeIds}
 * / {@code filterPrunedTreeRows}）——本次 {@code RowKeyUniquenessService}（提交期判重）需要与渲染/
 * 物化侧使用<b>同一份</b>剪枝过滤实现（AP-22 同源纪律：判重看到的"已删行"必须与页面上实际不可见的
 * 行严格一致，不得另写一套前缀匹配逻辑各读各的），故提取为独立可复用 static 工具，
 * {@code CardSnapshotService} 的两个私有方法委托本类实现（DRY，行为逐字节不变）。
 */
public final class PrunedTreeNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrunedTreeNodes() {}

    /** 解析 {@code quotation_line_item.deleted_tree_nodes}（JSON 字符串数组）。null/空白/非数组 → 空列表。 */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (!s.isBlank()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 该 nodeId 是否命中剪枝墓碑（自身节点 或 其任意祖先节点被剪）。
     * 前缀匹配语义与 {@code BomTreeRenderService}/{@code QuotationTreeService} 的 nodeId 路径命名空间
     * 一致：{@code nodeId.equals(p) || nodeId.startsWith(p + "/")}。
     *
     * @param nodeId        待判定的行 {@code __nodeId}（null/空白 → 非树行，恒不剪枝）
     * @param prunedNodeIds {@link #parse} 解出的剪枝节点列表
     */
    public static boolean isPruned(String nodeId, List<String> prunedNodeIds) {
        if (nodeId == null || nodeId.isBlank() || prunedNodeIds == null || prunedNodeIds.isEmpty()) return false;
        for (String p : prunedNodeIds) {
            if (nodeId.equals(p) || nodeId.startsWith(p + "/")) return true;
        }
        return false;
    }
}
