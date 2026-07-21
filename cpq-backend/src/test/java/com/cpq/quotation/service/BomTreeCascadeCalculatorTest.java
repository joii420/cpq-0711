package com.cpq.quotation.service;

import com.cpq.quotation.service.BomTreeCascadeCalculator.CascadeResult;
import com.cpq.quotation.service.BomTreeCascadeCalculator.Mode;
import com.cpq.quotation.service.BomTreeCascadeCalculator.TreeNodeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B7.1 — DAG 重复子件级联删除单测（backtask B13 最高风险区）。
 *
 * <p>Fixture（现网实例，见 test.md 附录 A）：
 * <pre>
 * 3120018220 (root)
 *   └ 2120011658
 *       └ 3110520789 (occurrence A)
 *           └ 2101110225 (A-child)
 *   └ 2120011659
 *       └ 3110520789 (occurrence B)
 *           └ 2101110225 (B-child)
 * </pre>
 * {@code 3110520789} 与 {@code 2101110225} 各有 2 个 occurrence（经不同父路径）。
 */
class BomTreeCascadeCalculatorTest {

    private static final String ROOT = "3120018220";
    private static final String N_ROOT = ROOT;
    private static final String N_658 = ROOT + "/2120011658";
    private static final String N_659 = ROOT + "/2120011659";
    private static final String N_789_A = N_658 + "/3110520789";
    private static final String N_789_B = N_659 + "/3110520789";
    private static final String N_225_A = N_789_A + "/2101110225";
    private static final String N_225_B = N_789_B + "/2101110225";

    private List<TreeNodeRef> fixture() {
        return List.of(
            new TreeNodeRef(N_ROOT, ROOT, 0),
            new TreeNodeRef(N_658, "2120011658", 1),
            new TreeNodeRef(N_789_A, "3110520789", 2),
            new TreeNodeRef(N_225_A, "2101110225", 3),
            new TreeNodeRef(N_659, "2120011659", 1),
            new TreeNodeRef(N_789_B, "3110520789", 2),
            new TreeNodeRef(N_225_B, "2101110225", 3)
        );
    }

    /** UT-DEL-1（AC-7b 正例）：剪 A 支的 3110520789 → 剩余 occurrence=1（B 支）→ 不级联,进 retainedParts。 */
    @Test
    void ut_del_1_pruneOccurrenceA_retainedNotCascaded() {
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), Set.of(), Mode.PRUNE, N_789_A);
        assertTrue(r.retainedMaterials.containsKey("3110520789"));
        assertEquals(1, r.retainedMaterials.get("3110520789"));
        assertFalse(r.cascadeMaterials.contains("3110520789"), "3110520789 仍有剩余 occurrence,不应级联删除");
        // 移除的节点应含 A 支的 3110520789 及其子孙 2101110225(A-child)
        Set<String> removedIds = idsOf(r);
        assertTrue(removedIds.contains(N_789_A));
        assertTrue(removedIds.contains(N_225_A));
        assertFalse(removedIds.contains(N_789_B), "B 支不应被误删");
        assertFalse(removedIds.contains(N_225_B), "B 支子孙不应被误删");
    }

    /** UT-DEL-2（★DAG 核心 AC-7c）：再剪 B 支 → 此时剩余 occurrence=0 → 级联删除。 */
    @Test
    void ut_del_2_pruneOccurrenceB_afterA_cascades() {
        Set<String> alreadyDeleted = new LinkedHashSet<>(List.of(N_789_A, N_225_A));
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), alreadyDeleted, Mode.PRUNE, N_789_B);
        assertTrue(r.cascadeMaterials.contains("3110520789"), "两支均已剪 → 3110520789 应级联删除");
        assertTrue(r.cascadeMaterials.contains("2101110225"), "2101110225 两个 occurrence 均已剪 → 应级联删除");
        assertFalse(r.retainedMaterials.containsKey("3110520789"));
    }

    /** UT-DEL-3（顺序无关）：反序（先剪 B 支）第一刀仍保留,第二刀(剪A支)才级联,与 UT-DEL-1/2 结果一致。 */
    @Test
    void ut_del_3_reverseOrder_sameFinalResult() {
        CascadeResult first = BomTreeCascadeCalculator.compute(fixture(), Set.of(), Mode.PRUNE, N_789_B);
        assertTrue(first.retainedMaterials.containsKey("3110520789"));
        assertEquals(1, first.retainedMaterials.get("3110520789"));

        Set<String> alreadyDeleted = new LinkedHashSet<>(List.of(N_789_B, N_225_B));
        CascadeResult second = BomTreeCascadeCalculator.compute(fixture(), alreadyDeleted, Mode.PRUNE, N_789_A);
        assertTrue(second.cascadeMaterials.contains("3110520789"));
        assertTrue(second.cascadeMaterials.contains("2101110225"));
    }

    /** UT-DEL-4：剪非叶子节点 2120011658 → 该节点+全部子孙(A 支)全删;2120011659 分支不受影响。 */
    @Test
    void ut_del_4_pruneNonLeafNode_subtreeAllRemoved_siblingUnaffected() {
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), Set.of(), Mode.PRUNE, N_658);
        Set<String> removedIds = idsOf(r);
        assertEquals(Set.of(N_658, N_789_A, N_225_A), removedIds);
        // 2120011658 无其它 occurrence → 级联；3110520789/2101110225 各自还有 B 支 → 保留
        assertTrue(r.cascadeMaterials.contains("2120011658"));
        assertTrue(r.retainedMaterials.containsKey("3110520789"));
        assertTrue(r.retainedMaterials.containsKey("2101110225"));
    }

    /** UT-DEL-5：剪叶子节点(A 支的 2101110225) → 只删该 occurrence,B 支不受影响。 */
    @Test
    void ut_del_5_pruneLeafOccurrence_onlyThatOccurrenceRemoved() {
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), Set.of(), Mode.PRUNE, N_225_A);
        Set<String> removedIds = idsOf(r);
        assertEquals(Set.of(N_225_A), removedIds);
        assertTrue(r.retainedMaterials.containsKey("2101110225"));
        assertEquals(1, r.retainedMaterials.get("2101110225"));
    }

    /** UT-DEL-8 类比：ROW 模式删除单节点(不含子孙),与 PRUNE 语义不同——只影响该节点本身。 */
    @Test
    void rowMode_onlyAffectsSingleNode_notDescendants() {
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), Set.of(), Mode.ROW, N_789_A);
        Set<String> removedIds = idsOf(r);
        assertEquals(Set.of(N_789_A), removedIds, "ROW 模式不应连带子孙 2101110225(A-child)");
        assertTrue(r.retainedMaterials.containsKey("3110520789"), "仍有 B 支 occurrence,应保留");
    }

    /** ROW 模式下,若该节点是该料号唯一 occurrence,同样应触发级联(需求说明"行删除同样适用"）。 */
    @Test
    void rowMode_lastOccurrence_cascades() {
        Set<String> alreadyDeleted = new LinkedHashSet<>(List.of(N_789_B));
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), alreadyDeleted, Mode.ROW, N_789_A);
        assertTrue(r.cascadeMaterials.contains("3110520789"));
    }

    /** UT-DEL-9 类比：一次剪枝同时让多个料号失去唯一 occurrence(级联),另一些仍保留——两清单同时出现。 */
    @Test
    void multipleAffectedMaterials_mixedRetainedAndCascade() {
        // 先假设 2101110225 的 B occurrence 已被剪掉,只剩 A occurrence
        Set<String> alreadyDeleted = new LinkedHashSet<>(List.of(N_225_B));
        // 现在剪 2120011658(含 3110520789-A + 2101110225-A) → 3110520789 还有 B 支(保留),
        // 但 2101110225 的 A 支被剪且 B 支已提前墓碑 → 级联
        CascadeResult r = BomTreeCascadeCalculator.compute(fixture(), alreadyDeleted, Mode.PRUNE, N_658);
        assertTrue(r.retainedMaterials.containsKey("3110520789"));
        assertTrue(r.cascadeMaterials.contains("2101110225"));
        assertTrue(r.cascadeMaterials.contains("2120011658"));
    }

    private static Set<String> idsOf(CascadeResult r) {
        Set<String> s = new LinkedHashSet<>();
        for (TreeNodeRef n : r.removedNodes) s.add(n.nodeId);
        return s;
    }
}
