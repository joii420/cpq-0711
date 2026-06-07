package com.cpq.component.service;

import com.cpq.component.dto.BomClosureResult;
import com.cpq.component.dto.BomClosureResult.SpineNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BomClosureService} 单测。
 *
 * <p>关键纪律：BomClosureService 走裸 {@code dataSource.getConnection()}（独立连接），
 * 故测试数据必须用 {@link QuarkusTransaction#requiringNew()} <b>先提交</b>再 compute，
 * 否则同测试事务内未提交的行对服务的新连接不可见。命名空间前缀 {@code TBC} 隔离 + 收尾清理。
 */
@QuarkusTest
class BomClosureServiceTest {

    @Inject BomClosureService service;
    @Inject EntityManager em;

    private void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery(
                "DELETE FROM material_bom_item WHERE material_no LIKE 'TBC%' OR component_no LIKE 'TBC%'")
              .executeUpdate());
    }

    /** 插一条 BOM 边，bom_version 默认 'V1'。 */
    private void edge(String parentNo, String childNo) {
        edge(parentNo, childNo, "V1");
    }

    /** 插一条 BOM 边，显式指定该父件 BOM 版本（material_no=父 的 bom_version）。 */
    private void edge(String parentNo, String childNo, String bomVersion) {
        em.createNativeQuery(
            "INSERT INTO material_bom_item " +
            "(system_type, customer_no, material_no, component_no, is_current, bom_version) " +
            "VALUES ('PRICING', '_GLOBAL_', :p, :c, true, :v)")
          .setParameter("p", parentNo)
          .setParameter("c", childNo)
          .setParameter("v", bomVersion)
          .executeUpdate();
    }

    private void seed(Runnable edges) {
        QuarkusTransaction.requiringNew().run(edges);
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private SpineNode nodeByPart(BomClosureResult r, String partNo) {
        List<SpineNode> hits = r.spine.stream()
            .filter(n -> partNo.equals(n.hfPartNo)).collect(Collectors.toList());
        assertFalse(hits.isEmpty(), "spine 应含料号 " + partNo);
        return hits.get(0);
    }

    // ---- 1) 多层树: P→A, P→B, B→C ----
    @Test
    void multiLayerTree() {
        seed(() -> { edge("TBCP", "TBCA"); edge("TBCP", "TBCB"); edge("TBCB", "TBCC"); });

        BomClosureResult r = service.compute("TBCP", Map.of());

        assertTrue(r.partSet.containsAll(List.of("TBCP", "TBCA", "TBCB", "TBCC")),
            "partSet 应含 {P,A,B,C}: " + r.partSet);
        assertEquals(4, r.partSet.size(), "partSet 去重后 4 个: " + r.partSet);
        assertTrue(r.cyclePartNos.isEmpty());

        SpineNode root = nodeByPart(r, "TBCP");
        assertEquals(1, root.lvl);
        assertEquals("", root.nodeId, "根 nodeId 为空串");
        assertNull(root.parentId, "根无父");

        SpineNode b = nodeByPart(r, "TBCB");
        assertEquals(2, b.lvl);
        assertEquals("", b.parentId, "B 是根直接子, parentId=''(=根 nodeId)");

        SpineNode c = nodeByPart(r, "TBCC");
        assertEquals(3, c.lvl);
        assertEquals(b.nodeId, c.parentId, "C 的 parentId 必须 = B 的 nodeId(按边路径连边)");
        // 版本语义（改后）：节点 bomVersion = 子件自身当前 BOM 版本（material_no=本节点 的 bom_version）。
        // C 是叶子（从不作为 material_no 出现）→ 自身无 BOM → null。
        assertNull(c.bomVersion, "叶子 C 无自身 BOM → 版本应为 null");
        // 根 P 是父件 → 自身 BOM 版本 V1。
        assertEquals("V1", root.bomVersion, "根 P 自身 BOM 版本 V1");
    }

    // ---- 2) DAG 重复子件: P→A, P→B, B→A ----
    @Test
    void dagRepeatedChildSplitsByNodeId() {
        seed(() -> { edge("TBCDP", "TBCDA"); edge("TBCDP", "TBCDB"); edge("TBCDB", "TBCDA"); });

        BomClosureResult r = service.compute("TBCDP", Map.of());

        // partSet 去重: A 只出现一次
        assertEquals(1, r.partSet.stream().filter("TBCDA"::equals).count(),
            "partSet 中 A 去重一次: " + r.partSet);
        assertTrue(r.partSet.containsAll(List.of("TBCDP", "TBCDA", "TBCDB")));

        // spine: A 出现 2 个 occurrence, nodeId 不同
        List<SpineNode> aNodes = r.spine.stream()
            .filter(n -> "TBCDA".equals(n.hfPartNo)).collect(Collectors.toList());
        assertEquals(2, aNodes.size(), "A 应有 2 个 occurrence(DAG 重复子件)");
        assertNotEquals(aNodes.get(0).nodeId, aNodes.get(1).nodeId, "两 occurrence nodeId 必须不同");

        // 一个 A 的父是根(parentId=""), 另一个 A 的父是 B
        SpineNode b = nodeByPart(r, "TBCDB");
        List<String> aParentIds = aNodes.stream().map(n -> n.parentId).collect(Collectors.toList());
        assertTrue(aParentIds.contains(""), "应有一个 A 直挂根(parentId='')");
        assertTrue(aParentIds.contains(b.nodeId), "应有一个 A 挂在 B 下(parentId=B.nodeId)");
    }

    // ---- 3) 环: A→B→A ----
    @Test
    void cycleDetectedAndTerminates() {
        seed(() -> { edge("TBCCA", "TBCCB"); edge("TBCCB", "TBCCA"); });

        // 不死循环 = 能正常返回
        BomClosureResult r = service.compute("TBCCA", Map.of());

        assertFalse(r.cyclePartNos.isEmpty(), "应检测到成环料号");
        assertTrue(r.cyclePartNos.contains("TBCCA"),
            "成环清单应含回到根的料号 A: " + r.cyclePartNos);
        // partSet 不含成环那一行(去环), 但根+B 仍在
        assertTrue(r.partSet.contains("TBCCB"));
    }

    // ---- 4) 单层: P→A ----
    @Test
    void singleLayer() {
        seed(() -> edge("TBCSP", "TBCSA"));

        BomClosureResult r = service.compute("TBCSP", Map.of());

        assertEquals(2, r.partSet.size(), "partSet={P,A}: " + r.partSet);
        assertTrue(r.partSet.containsAll(List.of("TBCSP", "TBCSA")));
        SpineNode a = nodeByPart(r, "TBCSA");
        assertEquals(2, a.lvl);
        assertEquals("", a.parentId);
    }

    // ---- 5) 空: 根无子件 ----
    @Test
    void emptyNoChildren() {
        // 不插任何边, 根料号在库里不作为 material_no 出现
        BomClosureResult r = service.compute("TBCEMPTY", Map.of());

        assertEquals(List.of("TBCEMPTY"), r.partSet, "空 BOM → partSet 仅根");
        assertEquals(1, r.spine.size(), "spine 仅根行");
        SpineNode root = r.spine.get(0);
        assertEquals(1, root.lvl);
        assertEquals("", root.nodeId);
        assertNull(root.parentId);
        assertTrue(r.cyclePartNos.isEmpty());
    }

    // ---- own-version 语义：版本取子件自身 BOM 版本，非边版本 ----
    @Test
    void bomVersionIsOwnCurrentVersion_notEdgeVersion() {
        // P 的 BOM 版本=VP（含子 B）；B 的 BOM 版本=VB（含子 C）
        seed(() -> { edge("TBCOP", "TBCOB", "VP"); edge("TBCOB", "TBCOC", "VB"); });

        BomClosureResult r = service.compute("TBCOP", Map.of());

        SpineNode b = nodeByPart(r, "TBCOB");
        // 边版本(旧语义)会是 VP(P 的 BOM 版本)；自身版本(新语义)是 VB(B 自己的 BOM 版本)
        assertEquals("VB", b.bomVersion, "B 应取自身 BOM 版本 VB，不是被父带入的边版本 VP");

        SpineNode c = nodeByPart(r, "TBCOC");
        assertNull(c.bomVersion, "C 叶子无自身 BOM → null");

        SpineNode root = nodeByPart(r, "TBCOP");
        assertEquals("VP", root.bomVersion, "根 P 自身 BOM 版本 VP");
    }
}
