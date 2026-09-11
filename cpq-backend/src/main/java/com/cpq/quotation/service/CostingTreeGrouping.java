package com.cpq.quotation.service;

import java.util.*;

/** 递归行 → 分组产物 + 树 nodeId 生成(纯函数,无 DB)。 */
public final class CostingTreeGrouping {
    private CostingTreeGrouping() {}

    public static final class Result {
        public final Map<String, LinkedHashSet<String>> cardMaterialNo;
        public final List<String> totalMaterialNo;
        public final Map<String, List<CostingTreeNode>> treeRowsByRoot;
        Result(Map<String, LinkedHashSet<String>> c, List<String> t, Map<String, List<CostingTreeNode>> tr) {
            this.cardMaterialNo = c; this.totalMaterialNo = t; this.treeRowsByRoot = tr;
        }
    }

    /** spine 节点去重键分隔符（U+0001，料号 / node_path 里不会出现，避免拼接歧义）。 */
    private static final String NODE_SEP = "\u0001";
    /** null 与空串的区分标记（node_path 为 null 与为 "" 都走 group() 的兜底分支，但不可互相折叠）。 */
    private static final String NULL_MARK = "\u0002";

    /**
     * spine 节点身份键 = {@code (rootNo, materialNo, bomVersion, parentNo, nodePath)} 五元组
     * —— 与递归 CTE 的 5 列投影（{@code BomTreeRenderService#queryRecursive}）逐列一致。
     */
    private static String nodeKey(CostingTreeNode r) {
        return f(r.rootNo) + NODE_SEP + f(r.materialNo) + NODE_SEP + f(r.bomVersion)
                + NODE_SEP + f(r.parentNo) + NODE_SEP + f(r.nodePath);
    }

    private static String f(String s) { return s == null ? NULL_MARK : s; }

    public static Result group(List<CostingTreeNode> rows) {
        Map<String, LinkedHashSet<String>> cardMat = new LinkedHashMap<>();
        LinkedHashSet<String> total = new LinkedHashSet<>();
        Map<String, List<CostingTreeNode>> byRoot = new LinkedHashMap<>();
        // task-260910 B-23 / AC-24（修法甲）：spine 节点按五元组去重，见下方大段说明。
        Set<String> seenNodes = new HashSet<>();

        for (CostingTreeNode r : rows) {
            cardMat.computeIfAbsent(r.rootNo, k -> new LinkedHashSet<>()).add(r.materialNo);
            total.add(r.materialNo);

            // ┌─ 🔒 task-260910 B-23 / AC-24（D-28 闸门 A0 裁决「修法甲」）──────────────────────┐
            // │ 🚫 **这不是把「同料号多 occurrence」语义删掉**，请勿改回去。两者是不同的东西：
            // │
            // │  ✅ 合法的「多 occurrence」= **同一子件挂在不同父件下** ⇒ node_path 不同
            // │     （例：A 既挂 P1 又挂 P1/B ⇒ "P1/A" 与 "P1/B/A"）。五元组含 nodePath，
            // │     这类节点**逐个保留**，一个都不折叠 —— 类注释
            // │     {@code BomTreeRenderService}「同料号多 occurrence 保留」说的正是这一类，
            // │     回归由 CostingTreeGroupingTest#multiOccurrencePreservedWithUniqueNodeIds
            // │     与 #childrenAttachToCorrectOccurrenceViaNodePath 钉住。
            // │
            // │  🔴 被折叠的只是 node_path **逐字相同**的那些 = 同一条边被递归 CTE 扇出多次。
            // │     成因：QUOTE 骨架的 node_path 只拼料号（`b.node_path||'/'||ch.input_material_no`），
            // │     **不含 item_seq / 不含边行 id**；而 ds_quote_material_bom 的边
            // │     (customer_no, material_no, input_material_no) 上没有唯一约束，
            // │     同一条边可以有 ≥2 行（item_seq 不同，全库 7 组 / 14 行）。
            // │     ⇒ 同一条边产出 m 个五元组完全相同的节点，它们的 nodeId 也必然撞号（:40 nodeId=path），
            // │       在 BomTreeRenderService §⑤（约 :678-686）与该边的 n 行业务行相乘 ⇒ **m × n 行重影**
            // │       （活案例：卡片 1bde53ba / 产品 0028-2609000018 渲染 13 行，正确应为 7 行）。
            // │     去重把 m 压回 1 ⇒ 行数 = n = 该边真实的 BOM 行数，**不多不少**。
            // │
            // │ ⚠️ 去重只作用于 treeRowsByRoot（spine 行主轴）。业务行本身**一行不删** ——
            // │    n 行同边业务行仍全部渲染（AC-24 的 A3 断言就是钉这一点：行数必须 == 边的真实行数，
            // │    不是 1）。🚫 不要改成「渲染后按逐字节相同折叠 baseRows」（修法丙，已否决：
            // │    会误伤 item_seq 也相同的合法重复行，且违反 AP-51 driver 行数权威 / AP-60 不拿渲染投影当权威）。
            // │
            // │ 📌 语义最正的解法是给边加 occurrence 维度（修法乙：骨架 CTE + 全库树页签 $view +
            // │    costing_bom_tree_config 4 条 usage + 生成器同步加 item_seq），属强联动改动、
            // │    漏一处的失败形态是静默 0 行 ⇒ 本期不做，已登 BACKLOG。
            // │    数据侧收口（边唯一约束 + 14 行脏重复清理）同样登 BACKLOG（修法丁）。
            // └────────────────────────────────────────────────────────────────────────────────┘
            // 📌 cardMat / total 刻意放在去重之前：两者都是 Set，重复行对它们逐位无影响，
            //    如此「本次改动唯一变化的产物是 treeRowsByRoot」这句话在代码上也是显然的。
            if (seenNodes.add(nodeKey(r))) {
                byRoot.computeIfAbsent(r.rootNo, k -> new ArrayList<>()).add(r);
            }
        }

        for (Map.Entry<String, List<CostingTreeNode>> e : byRoot.entrySet()) {
            List<CostingTreeNode> tree = e.getValue();
            int seq = 0;
            for (CostingTreeNode nd : tree) {
                String path = nd.nodePath;
                if (path == null || path.isBlank()) {
                    // 兜底:node_path 缺失(应被保存期校验拦住)→ 平铺,视为配置错误
                    nd.nodeId = nd.rootNo + "#" + seq;
                    nd.parentId = null;
                    nd.lvl = 1;
                } else {
                    nd.nodeId = path;
                    int i = path.lastIndexOf('/');
                    nd.parentId = (i < 0) ? null : path.substring(0, i);
                    int lvl = 1;
                    for (int k = 0; k < path.length(); k++) if (path.charAt(k) == '/') lvl++;
                    nd.lvl = lvl;
                }
                seq++;
            }
        }
        return new Result(cardMat, new ArrayList<>(total), byRoot);
    }
}
