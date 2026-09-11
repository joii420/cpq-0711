package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260910 B-23 / AC-24（D-28「修法甲」）：spine 五元组去重。
 *
 * <p>本类同时钉住两个**方向相反**的断言，防止后人把修法甲误读成「把 occurrence 语义删了」：
 * <ul>
 *   <li>🔴 <b>要折叠的</b>：五元组（含 {@code nodePath}）逐字相同 = 同一条边被递归 CTE 扇出多次
 *       （成因见 {@code CostingTreeGrouping.group} 里的大段注释）；</li>
 *   <li>✅ <b>不许折叠的</b>：同子件挂不同父 ⇒ {@code nodePath} 不同 ⇒ 合法多 occurrence，逐个保留。</li>
 * </ul>
 */
class CostingTreeGroupingDedupeTest {

    private CostingTreeNode n(String root, String mat, String ver, String parent, String path) {
        return new CostingTreeNode(root, mat, ver, parent, path);
    }

    /** 🔴 同一条边扇出 2 个五元组全等节点 ⇒ spine 只留 1 个（m 压回 1，行数不再是 m×n）。 */
    @Test void duplicateFiveTupleCollapsesToOneSpineNode() {
        // 活案例形状：0028-2609000018 → 0028-2609000016 在 ds_quote_material_bom 有 2 行(item_seq 1/3)
        // ⇒ node_path 只拼料号、不含 item_seq ⇒ 递归 CTE 扇出 2 个完全相同的节点。
        List<CostingTreeNode> rows = List.of(
            n("P", "P", null, null, "P"),
            n("P", "C1", null, "P", "P/C1"),
            n("P", "C1", null, "P", "P/C1"));   // ← 与上一行逐列相同
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("P");
        assertEquals(2, tree.size(), "spine 应为 根 + C1 各 1 个节点，重复边只留一个");
        assertEquals(1, tree.stream().filter(x -> x.materialNo.equals("C1")).count());
        // nodeId 不再撞号
        Set<String> ids = new HashSet<>();
        for (CostingTreeNode x : tree) assertTrue(ids.add(x.nodeId), "nodeId 必须唯一: " + x.nodeId);
    }

    /**
     * ✅ 去重**不得**误伤合法多 occurrence：同子件挂不同父 ⇒ nodePath 不同 ⇒ 两个节点都保留。
     * 本用例把「合法多 occurrence」与「重复边」混在一张树里，确保去重只咬后者。
     */
    @Test void legitMultiOccurrenceSurvivesWhileDuplicateEdgeCollapses() {
        List<CostingTreeNode> rows = List.of(
            n("P", "P", null, null, "P"),
            n("P", "A", null, "P", "P/A"),
            n("P", "B", null, "P", "P/B"),
            n("P", "X", null, "A", "P/A/X"),     // X 挂在 A 下
            n("P", "X", null, "B", "P/B/X"),     // X 也挂在 B 下 ⇒ 合法多 occurrence，必须都留
            n("P", "X", null, "B", "P/B/X"));    // ← 与上一行逐列相同 ⇒ 重复边，必须折叠
        CostingTreeGrouping.Result r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("P");
        assertEquals(5, tree.size(), "5 个不同五元组 ⇒ 5 个节点（第 6 行是重复边）");
        List<String> xIds = tree.stream().filter(x -> x.materialNo.equals("X"))
                .map(x -> x.nodeId).sorted().toList();
        assertEquals(List.of("P/A/X", "P/B/X"), xIds,
                "同子件挂不同父 = 合法多 occurrence，两个都必须保留（🚫 这条挂了说明去重键漏了 nodePath）");
        assertEquals("P/A", tree.stream().filter(x -> "P/A/X".equals(x.nodeId)).findFirst().orElseThrow().parentId);
        assertEquals("P/B", tree.stream().filter(x -> "P/B/X".equals(x.nodeId)).findFirst().orElseThrow().parentId);
    }

    /** 五元组的每一列都参与身份判定 —— 任一列不同即视为不同节点，不许折叠。 */
    @Test void everyTupleColumnParticipatesInIdentity() {
        // bomVersion 不同（其余 4 列相同）⇒ 2 个节点
        var byVer = CostingTreeGrouping.group(List.of(
            n("P", "C", "v1", "P", "P/C"),
            n("P", "C", "v2", "P", "P/C")));
        assertEquals(2, byVer.treeRowsByRoot.get("P").size(), "bomVersion 不同 ⇒ 不折叠");

        // parentNo 不同（node_path 相同，理论上不该出现，但键必须区分）⇒ 2 个节点
        var byParent = CostingTreeGrouping.group(List.of(
            n("P", "C", null, "A", "P/C"),
            n("P", "C", null, "B", "P/C")));
        assertEquals(2, byParent.treeRowsByRoot.get("P").size(), "parentNo 不同 ⇒ 不折叠");

        // rootNo 不同 ⇒ 本来就分桶，两棵树各 1 个
        var byRoot = CostingTreeGrouping.group(List.of(
            n("P1", "C", null, "P1", "P1/C"),
            n("P2", "C", null, "P2", "P2/C")));
        assertEquals(1, byRoot.treeRowsByRoot.get("P1").size());
        assertEquals(1, byRoot.treeRowsByRoot.get("P2").size());

        // null 与空串 nodePath 不可互相折叠（两者都走兜底分支，但是两个不同的配置错误行）
        var byNullVsBlank = CostingTreeGrouping.group(new ArrayList<>(List.of(
            n("P", "C", null, "P", ""),
            new CostingTreeNode("P", "C", null, "P", null))));
        assertEquals(2, byNullVsBlank.treeRowsByRoot.get("P").size(), "null 与 \"\" 不可折叠");
    }

    /**
     * 去重的唯一变化产物是 {@code treeRowsByRoot} —— {@code cardMaterialNo} / {@code totalMaterialNo}
     * 与「原始行」口径**逐位一致**（两者都是 Set，且首次出现顺序不受去重影响）。
     *
     * <p>这条直接支撑「{@code BomTreeRenderService#collectTotalMaterialNoUnion} 不受影响」：
     * 该调用点只消费这两个产物，不碰 {@code treeRowsByRoot}。
     */
    @Test void setProductsAreBitIdenticalWithAndWithoutDuplicates() {
        List<CostingTreeNode> clean = List.of(
            n("P", "P", null, null, "P"),
            n("P", "C1", null, "P", "P/C1"),
            n("P", "C2", null, "P", "P/C2"),
            n("P", "M", null, "C1", "P/C1/M"));
        List<CostingTreeNode> dup = List.of(
            n("P", "P", null, null, "P"),
            n("P", "C1", null, "P", "P/C1"),
            n("P", "C1", null, "P", "P/C1"),
            n("P", "C2", null, "P", "P/C2"),
            n("P", "C2", null, "P", "P/C2"),
            n("P", "M", null, "C1", "P/C1/M"),
            n("P", "M", null, "C1", "P/C1/M"));

        var a = CostingTreeGrouping.group(clean);
        var b = CostingTreeGrouping.group(dup);
        assertEquals(a.totalMaterialNo, b.totalMaterialNo, "totalMaterialNo 必须逐位一致（含顺序）");
        assertEquals(new ArrayList<>(a.cardMaterialNo.get("P")), new ArrayList<>(b.cardMaterialNo.get("P")),
                "cardMaterialNo 必须逐位一致（含顺序）");
        assertEquals(a.treeRowsByRoot.get("P").size(), b.treeRowsByRoot.get("P").size(),
                "spine 也必须一致 —— 重复边被折叠后与干净数据同形");
    }

    /** 阴性对照的单测镜像：每条边 1 行（S0004 那套数据的形状）⇒ 去重后逐位不变。 */
    @Test void singleEdgePerRowIsUntouched() {
        List<CostingTreeNode> rows = List.of(
            n("S0004", "S0004", null, null, "S0004"),
            n("S0004", "S0005", null, "S0004", "S0004/S0005"),
            n("S0004", "00006", null, "S0005", "S0004/S0005/00006"),
            n("S0004", "S0006", null, "S0004", "S0004/S0006"),
            n("S0004", "00168", null, "S0006", "S0004/S0006/00168"),
            n("S0004", "S0007", null, "S0004", "S0004/S0007"));
        var r = CostingTreeGrouping.group(rows);
        List<CostingTreeNode> tree = r.treeRowsByRoot.get("S0004");
        assertEquals(6, tree.size(), "每条边 1 行 ⇒ 6 个 spine 节点，去重不得少一个");
        assertEquals(List.of("S0004", "S0004/S0005", "S0004/S0005/00006",
                             "S0004/S0006", "S0004/S0006/00168", "S0004/S0007"),
                tree.stream().map(x -> x.nodeId).toList(), "顺序也必须逐位不变");
    }
}
