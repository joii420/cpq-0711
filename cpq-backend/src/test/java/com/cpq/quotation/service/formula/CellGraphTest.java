package com.cpq.quotation.service.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * task-0803：单元格 (行 × 列) 级拓扑排序与环检测。
 *
 * <p>纯 JUnit（不用 {@code @QuarkusTest}）—— BL-0095。
 */
class CellGraphTest {

    @Test
    @DisplayName("T1: 同行列依赖 —— 被依赖列先于依赖列")
    void sameRowOrder() {
        CellGraph g = new CellGraph(1, 2);
        g.addEdge(0, 1, 0, 0);                       // (r0,c1) 先于 (r0,c0)
        CellGraph.Result r = g.topoOrder();
        assertTrue(r.cycles().isEmpty());
        assertEquals(List.of(1, 0), r.order().stream().map(CellGraph.Cell::col).toList());
    }

    @Test
    @DisplayName("T2: 自下而上 —— 子行先于父行（CSUM 场景）")
    void bottomUpOrder() {
        CellGraph g = new CellGraph(2, 1);
        g.addEdge(1, 0, 0, 0);                       // 子(r1) 先于 父(r0)
        CellGraph.Result r = g.topoOrder();
        assertEquals(List.of(1, 0), r.order().stream().map(CellGraph.Cell::row).toList());
    }

    @Test
    @DisplayName("T3: 自上而下 —— 父行先于子行（PGET 场景）")
    void topDownOrder() {
        CellGraph g = new CellGraph(2, 1);
        g.addEdge(0, 0, 1, 0);
        CellGraph.Result r = g.topoOrder();
        assertEquals(List.of(0, 1), r.order().stream().map(CellGraph.Cell::row).toList());
    }

    @Test
    @DisplayName("T4: 双向混用不成环 —— 两列各走各方向（需求 §4.1 场景 C）")
    void mixedDirectionsNoCycle() {
        // col0 自下而上（r1→r0）；col1 自上而下（r0→r1）
        CellGraph g = new CellGraph(2, 2);
        g.addEdge(1, 0, 0, 0);
        g.addEdge(0, 1, 1, 1);
        CellGraph.Result r = g.topoOrder();
        assertTrue(r.cycles().isEmpty(), "不同列各走各方向不应成环");
        assertEquals(4, r.order().size());
    }

    @Test
    @DisplayName("T5: 成环 —— 环上 cell 全部报出，环外仍可求值")
    void cycleDetected() {
        CellGraph g = new CellGraph(2, 2);
        g.addEdge(0, 0, 1, 0);
        g.addEdge(1, 0, 0, 0);   // 与上一条互指 → 成环
        g.addEdge(0, 1, 1, 1);   // 无关的一条，应能正常排序
        CellGraph.Result r = g.topoOrder();
        assertEquals(2, r.cycles().size(), "环上两个 cell");
        assertTrue(r.cycles().contains(new CellGraph.Cell(0, 0)));
        assertTrue(r.cycles().contains(new CellGraph.Cell(1, 0)));
        assertEquals(2, r.order().size(), "环外两个 cell 仍排得出来");
    }

    @Test
    @DisplayName("T6: 自环（列引用自己）—— 进不了 order，算作环")
    void selfLoopIsCycle() {
        CellGraph g = new CellGraph(1, 1);
        g.addEdge(0, 0, 0, 0);
        CellGraph.Result r = g.topoOrder();
        assertTrue(r.order().isEmpty());
        assertEquals(1, r.cycles().size());
    }

    @Test
    @DisplayName("T7: 空图 —— 0 行或 0 列不炸")
    void emptyGraph() {
        assertTrue(new CellGraph(0, 3).topoOrder().order().isEmpty());
        assertTrue(new CellGraph(3, 0).topoOrder().order().isEmpty());
    }

    @Test
    @DisplayName("T8: 越界边被忽略 —— 不抛异常")
    void outOfRangeEdgeIgnored() {
        CellGraph g = new CellGraph(2, 2);
        g.addEdge(5, 0, 0, 0);
        g.addEdge(0, 0, 9, 9);
        CellGraph.Result r = g.topoOrder();
        assertTrue(r.cycles().isEmpty());
        assertEquals(4, r.order().size());
    }
}
