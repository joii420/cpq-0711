package com.cpq.component.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 核价 BOM 递归展开（P1）闭包结果。
 *
 * <p>由 {@code BomClosureService.compute(rootPartNo, versionOverrides)} 产出三样：
 * <ul>
 *   <li>{@link #partSet}：根 + 全部子孙料号（去环），喂给 {@code :hfPartNos} 外包过滤；</li>
 *   <li>{@link #spine}：BOM 树骨架，每行 = 一个节点 occurrence（DAG 重复子件 → 多 occurrence），
 *       含 {@code nodeId}/{@code parentId}/{@code lvl}/{@code hfPartNo}/{@code parentNo}/{@code bomVersion}；</li>
 *   <li>{@link #cyclePartNos}：成环料号（PG16 CYCLE 标记），供前端告警。</li>
 * </ul>
 *
 * <p>设计：{@code docs/superpowers/specs/2026-06-04-核价BOM递归展开-design.md} §3。
 */
public class BomClosureResult {

    /** 根 + 全部子孙料号（DISTINCT，去环）。喂 {@code :hfPartNos}。 */
    public List<String> partSet = new ArrayList<>();

    /** BOM 树骨架（每行一个节点 occurrence，含重复子件的多 occurrence）。 */
    public List<SpineNode> spine = new ArrayList<>();

    /** 成环料号（去重）。非空 → 前端告警「已截断展开」。 */
    public List<String> cyclePartNos = new ArrayList<>();

    public BomClosureResult() {}

    /**
     * 树节点骨架（per-occurrence）。
     *
     * <p>{@code nodeId} = 边 id 路径（{@code material_bom_item.id} 用 {@code /} 拼接），per-occurrence 唯一；
     * 根 occurrence 的 {@code nodeId} = {@code ""}（空串）。
     * 前端建树必须用 {@code parentId → nodeId} 连边（不是料号），否则 DAG 会塌成非树。
     */
    public static class SpineNode {
        /** 边路径（"e1/e3/e7"）；根 = ""。per-occurrence 唯一。 */
        public String nodeId;
        /** 父节点身份；根 = null（无父）；根的直接子节点 = ""（= 根 nodeId）。 */
        public String parentId;
        /** 层级，根 = 1。 */
        public int lvl;
        /** 本节点料号（显示 + 业务数据对号）。 */
        public String hfPartNo;
        /** 父料号（显示用）；根 = null。 */
        public String parentNo;
        /** 本节点生效 BOM 版本（P1 = 当前版 is_current；可能为 null=该料号无自身 BOM）。 */
        public String bomVersion;
        /** 该 occurrence 是否成环节点。 */
        public boolean isCycle;

        public SpineNode() {}

        public SpineNode(String nodeId, String parentId, int lvl,
                         String hfPartNo, String parentNo, String bomVersion, boolean isCycle) {
            this.nodeId = nodeId;
            this.parentId = parentId;
            this.lvl = lvl;
            this.hfPartNo = hfPartNo;
            this.parentNo = parentNo;
            this.bomVersion = bomVersion;
            this.isCycle = isCycle;
        }
    }
}
