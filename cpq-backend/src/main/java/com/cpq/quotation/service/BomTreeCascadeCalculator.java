package com.cpq.quotation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * task-0721 B7.1 — 删除影响面纯函数计算（剪枝 PRUNE / 行删除 ROW 统一入口）。
 *
 * <p>不碰 DB，输入是「树上全部节点」的快照（{@link TreeNodeRef} 列表，来自某个树页签组件
 * {@code snapshot_rows} 反序列化后的系统列）与「已确认的墓碑节点集合」，纯逻辑计算：
 * <ol>
 *   <li>本次操作【新增】要移除的节点集合（PRUNE=节点+其全部子孙，按 {@code nodeId} 前缀匹配；
 *       ROW=该节点本身，不含子孙——行级删除不隐含结构性剪枝）；</li>
 *   <li>受影响料号 = 新增移除节点集合的 distinct {@code partNo}；</li>
 *   <li>对每个受影响料号，重算其在「全部节点 − 已墓碑节点 − 本次新增移除节点」上的剩余 occurrence：
 *       <b>&gt;0 → 保留</b>（{@code retainedMaterials}），<b>=0 → 级联</b>（{@code cascadeMaterials}）。</li>
 * </ol>
 *
 * <p><b>顺序无关性（UT-DEL-3）</b>：每次调用都从「全部节点」出发按当前 {@code alreadyDeletedNodeIds}
 * 重新计数，不依赖历史操作的中间状态，故无论先剪哪一支、后剪哪一支，最终墓碑集合与级联判定完全一致。
 *
 * <p><b>DAG 场景（现网实例）</b>：同一 {@code partNo}（如 {@code 3110520789}）可在多个不同
 * {@code nodeId}（不同 node_path）上出现多次——这正是本类核心要处理的「剩余 occurrence」计数场景。
 */
public final class BomTreeCascadeCalculator {

    private BomTreeCascadeCalculator() {}

    /** 树上一个节点的最小信息（供级联计算用）。 */
    public static final class TreeNodeRef {
        public final String nodeId;
        public final String partNo;
        public final int lvl;

        public TreeNodeRef(String nodeId, String partNo, int lvl) {
            this.nodeId = nodeId;
            this.partNo = partNo;
            this.lvl = lvl;
        }
    }

    /** 删除模式。 */
    public enum Mode { PRUNE, ROW }

    /** 影响面计算结果。 */
    public static final class CascadeResult {
        /** 本次操作【新增】要移除的节点（PRUNE=子树全部节点；ROW=单节点）。 */
        public final List<TreeNodeRef> removedNodes;
        /** 无剩余 occurrence → 需级联删除其在其余所有页签中数据的料号集合。 */
        public final Set<String> cascadeMaterials;
        /** 仍有剩余 occurrence → 不删，附剩余数量。 */
        public final Map<String, Integer> retainedMaterials;

        CascadeResult(List<TreeNodeRef> removedNodes, Set<String> cascadeMaterials,
                      Map<String, Integer> retainedMaterials) {
            this.removedNodes = removedNodes;
            this.cascadeMaterials = cascadeMaterials;
            this.retainedMaterials = retainedMaterials;
        }
    }

    /**
     * @param allNodes            树上全部节点（含已墓碑的——由调用方传入完整快照，本函数自己按
     *                            {@code alreadyDeletedNodeIds} 过滤，不依赖调用方提前过滤）
     * @param alreadyDeletedNodeIds 此前已确认墓碑的节点 id 集合（{@code quotation_line_item.deleted_tree_nodes}）
     * @param mode                PRUNE=整枝；ROW=单节点
     * @param nodeId              PRUNE=被剪节点 id；ROW=被删行所在节点 id
     */
    public static CascadeResult compute(List<TreeNodeRef> allNodes, Set<String> alreadyDeletedNodeIds,
                                         Mode mode, String nodeId) {
        Set<String> already = alreadyDeletedNodeIds != null ? alreadyDeletedNodeIds : Set.of();

        // ① 本次新增移除节点集合
        Set<String> newlyRemoved = new LinkedHashSet<>();
        if (mode == Mode.PRUNE) {
            String prefix = nodeId + "/";
            for (TreeNodeRef n : allNodes) {
                if (already.contains(n.nodeId)) continue; // 已墓碑节点不算"新增"移除
                if (n.nodeId.equals(nodeId) || n.nodeId.startsWith(prefix)) {
                    newlyRemoved.add(n.nodeId);
                }
            }
        } else { // ROW：仅该节点本身，不含子孙（行级删除不隐含结构性剪枝）
            if (nodeId != null && !already.contains(nodeId)) {
                newlyRemoved.add(nodeId);
            }
        }

        // ② 全部"移除后"节点集合 = 已墓碑 ∪ 本次新增
        Set<String> removedAll = new LinkedHashSet<>(already);
        removedAll.addAll(newlyRemoved);

        // ③ 剩余 occurrence 计数（按 partNo，排除 removedAll 中的节点）
        Map<String, Integer> remainCountByMaterial = new LinkedHashMap<>();
        for (TreeNodeRef n : allNodes) {
            if (removedAll.contains(n.nodeId)) continue;
            remainCountByMaterial.merge(n.partNo, 1, Integer::sum);
        }

        // ④ 受影响料号 = 本次新增移除节点集合的 distinct partNo
        List<TreeNodeRef> removedNodeRefs = new ArrayList<>();
        LinkedHashSet<String> affectedMaterials = new LinkedHashSet<>();
        for (TreeNodeRef n : allNodes) {
            if (newlyRemoved.contains(n.nodeId)) {
                removedNodeRefs.add(n);
                affectedMaterials.add(n.partNo);
            }
        }

        // ⑤ 逐料号判定：剩余>0 保留；=0 级联
        Set<String> cascadeMaterials = new LinkedHashSet<>();
        Map<String, Integer> retained = new LinkedHashMap<>();
        for (String m : affectedMaterials) {
            int remain = remainCountByMaterial.getOrDefault(m, 0);
            if (remain > 0) {
                retained.put(m, remain);
            } else {
                cascadeMaterials.add(m);
            }
        }

        return new CascadeResult(removedNodeRefs, cascadeMaterials, retained);
    }
}
