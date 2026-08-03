package com.cpq.quotation.service.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * task-0803：BOM 树父子关系解析。
 *
 * <p>纯 JUnit（不用 {@code @QuarkusTest}）—— BL-0095 致测试库所有 Quarkus 测试当前起不来。
 */
class TreeRelationsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode rows(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 三层树：根 n1 → 子 n2/n3 → 孙 n4（挂在 n2 下）。 */
    private JsonNode threeLevel() {
        return rows("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0,"driverRow":{},"basicDataValues":{}},
              {"__nodeId":"n2","__parentId":"n1","__lvl":1,"driverRow":{},"basicDataValues":{}},
              {"__nodeId":"n3","__parentId":"n1","__lvl":1,"driverRow":{},"basicDataValues":{}},
              {"__nodeId":"n4","__parentId":"n2","__lvl":2,"driverRow":{},"basicDataValues":{}}
            ]
            """);
    }

    @Test
    @DisplayName("T1: 识别为树页签 —— 任意行带 __nodeId 即是")
    void detectsTree() {
        assertTrue(TreeRelations.isTreeRows(threeLevel()));
        assertFalse(TreeRelations.isTreeRows(rows("[{\"driverRow\":{}}]")));
        assertFalse(TreeRelations.isTreeRows(rows("[]")));
    }

    @Test
    @DisplayName("T2: 父行下标 —— 根行返回 -1")
    void parentIndex() {
        TreeRelations tr = TreeRelations.of(threeLevel(), null);
        assertEquals(-1, tr.parentOf(0), "根行无父");
        assertEquals(0, tr.parentOf(1));
        assertEquals(0, tr.parentOf(2));
        assertEquals(1, tr.parentOf(3), "n4 的父是 n2（下标 1）");
    }

    @Test
    @DisplayName("T3: 直接子行下标 —— 只算亲儿子，不含孙辈")
    void childIndexes() {
        TreeRelations tr = TreeRelations.of(threeLevel(), null);
        assertEquals(List.of(1, 2), tr.childrenOf(0), "根的直接子是 n2/n3，不含孙 n4");
        assertEquals(List.of(3), tr.childrenOf(1));
        assertEquals(List.of(), tr.childrenOf(2), "n3 是叶子");
        assertEquals(List.of(), tr.childrenOf(3));
    }

    @Test
    @DisplayName("T4: 树属性 —— 层级 / 是否叶子 / 是否根")
    void treeAttrs() {
        TreeRelations tr = TreeRelations.of(threeLevel(), null);
        assertEquals(0, tr.lvl(0));
        assertEquals(2, tr.lvl(3));
        assertTrue(tr.isRoot(0));
        assertFalse(tr.isRoot(1));
        assertFalse(tr.isLeaf(0));
        assertTrue(tr.isLeaf(2), "n3 无子 → 叶子");
        assertTrue(tr.isLeaf(3));
    }

    @Test
    @DisplayName("T5: 墓碑行不计入 C(r) —— 子全被删则父变叶子（需求 §4.3.1 推论）")
    void tombstonedChildrenExcluded() {
        TreeRelations tr = TreeRelations.of(threeLevel(), java.util.Set.of("n2", "n3"));
        assertEquals(List.of(), tr.childrenOf(0));
        assertTrue(tr.isLeaf(0), "两个子都被删 → 根变叶子");
        assertEquals(-1, tr.parentOf(3), "父 n2 已删 → n4 视为无父");
    }

    @Test
    @DisplayName("T6: DAG —— 同料号挂多父按 nodeId 区分，不串边")
    void dagDistinctByNodeId() {
        JsonNode dag = rows("""
            [
              {"__nodeId":"a","__parentId":null,"__lvl":0,"driverRow":{"material_no":"P1"}},
              {"__nodeId":"b","__parentId":"a","__lvl":1,"driverRow":{"material_no":"P2"}},
              {"__nodeId":"c","__parentId":"a","__lvl":1,"driverRow":{"material_no":"P3"}},
              {"__nodeId":"b/x","__parentId":"b","__lvl":2,"driverRow":{"material_no":"SHARED"}},
              {"__nodeId":"c/x","__parentId":"c","__lvl":2,"driverRow":{"material_no":"SHARED"}}
            ]
            """);
        TreeRelations tr = TreeRelations.of(dag, null);
        assertEquals(List.of(3), tr.childrenOf(1), "同料号 SHARED 挂两个父，各自只算自己那条边");
        assertEquals(List.of(4), tr.childrenOf(2));
    }
}
