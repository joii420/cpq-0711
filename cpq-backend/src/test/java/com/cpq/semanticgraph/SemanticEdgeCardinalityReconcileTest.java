package com.cpq.semanticgraph;

import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticEdgeKey;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.service.SemanticGraphValidator;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI 断言① 边基数（task-260819 B-17，AC-35）。
 *
 * <p>🔑 「从表读边定义」：本测试查询 {@code semantic_edge} 表（不是任何 Java 常量/枚举声明）。
 * 这正是 D-27 之后 AC-35 的口径变化——真源在库里，CI 断言也必须查库，不能再查代码。
 *
 * <p>反证型（AC-35②）：{@link #corruptedCardinality_mustFail()} 证明「人为改错后测试确实失败」——
 * 用 {@code element_bom_item.material_part_no} 单列（真实数据里一个材质料号对应多个元素，
 * 本就不唯一）冒充一条 MANY_TO_ONE 边的右键，断言校验器必须报 FAIL 并指出重复的键。
 * 之所以不直接改 {@code semantic_edge} 表后 rollback，是因为该表是全应用共享的不可变快照来源，
 * 在测试里改写真实边声明（哪怕事务回滚）风险大于收益——直接对校验器函数喂"错误声明"同样能
 * 证明"断言确实会失败"，且不触碰共享数据。
 */
@QuarkusTest
@DisplayName("SemanticEdgeCardinalityReconcileTest — AC-35 边基数断言（从表读取，非代码声明）")
public class SemanticEdgeCardinalityReconcileTest {

    @Inject SemanticGraphValidator validator;

    @Test
    @TestTransaction
    @DisplayName("正常数据下：全部 MANY_TO_ONE 边的基数断言均通过（PASS 或 THIN，不允许 FAIL）")
    void allManyToOneEdges_passOrThin() {
        List<SemanticEdge> edges = SemanticEdge.list("cardinality = ?1 and status = 'ACTIVE'", "MANY_TO_ONE");
        assertFalse(edges.isEmpty(), "种子数据应至少含若干 MANY_TO_ONE 边");

        for (SemanticEdge e : edges) {
            SemanticNode to = SemanticNode.findById(e.toNodeId);
            assertNotNull(to, "边 " + e.id + " 的 to_node 必须存在（RESTRICT FK 保证）");
            if (to.physicalTable == null) continue; // FUNCTION 节点无物理表，基数断言不适用

            List<SemanticEdgeKey> keys = SemanticEdgeKey.list("edgeId = ?1 order by seq", e.id);
            if (keys.isEmpty()) continue; // SAME/PRICE 等无独立右键的边不适用本断言

            List<String> rightCols = keys.stream().map(k -> k.rightColumn).sorted().toList();
            SemanticGraphValidator.CheckResult r = validator.checkEdgeCardinality(to.physicalTable, rightCols);
            assertNotEquals("FAIL", r.status,
                    "边 " + e.id + " (" + to.physicalTable + "." + String.join(",", rightCols) + ") 基数断言失败: " + r.message);
        }
    }

    @Test
    @DisplayName("反证：右键选得不够窄（真实存在重复）→ 断言必须 FAIL 并指出重复键（AC-35②）")
    void corruptedCardinality_mustFail() {
        // element_bom_item 按 material_part_no 单列分组：一个材质料号天然对应多个元素行，必然有重复。
        // 这正是 ELEMENT_BOM 节点的真实 grain 需要 (material_part_no, component_no) 两列的原因——
        // 若把边错误声明为仅按 material_part_no 唯一（MANY_TO_ONE），基数断言必须能抓到。
        SemanticGraphValidator.CheckResult r = validator.checkEdgeCardinality("element_bom_item", List.of("material_part_no"));
        assertEquals("FAIL", r.status, "错误的单列基数声明必须被断言否决");
        assertNotNull(r.detail);
        assertTrue(r.detail.containsKey("duplicates"), "失败详情必须列出重复的键值");
        @SuppressWarnings("unchecked")
        List<Object> dups = (List<Object>) r.detail.get("duplicates");
        assertFalse(dups.isEmpty(), "必须至少指出一组重复");
    }
}
