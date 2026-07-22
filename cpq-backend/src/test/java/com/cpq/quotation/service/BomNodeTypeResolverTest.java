package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.common.exception.TreeConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BomNodeTypeResolver} 六条判定链单测（需求说明 §4.3 规则二）。
 * 不碰 DB，纯逻辑（TabHitContext 手工构造）。
 */
class BomNodeTypeResolverTest {

    private final BomNodeTypeResolver resolver = new BomNodeTypeResolver();

    @Test
    void rule1_hitMaterialTab_resolvesMaterial() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "AgNi11#");
        var r = resolver.resolveStrict("AgNi11#", ctx);
        assertEquals(BomNodeTypeResolver.MATERIAL, r.nodeType);
        assertFalse(r.structural);
    }

    @Test
    void rule2_hitPartTab_resolvesPart() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("零件", "2120011658");
        var r = resolver.resolveStrict("2120011658", ctx);
        assertEquals(BomNodeTypeResolver.PART, r.nodeType);
        assertFalse(r.structural);
    }

    @Test
    void rule3_notInPartTab_butChildIsMaterial_resolvesPartStructural() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        // 3110520789 未出现在任何页签，但其子 2101110225 命中材质元素页签
        ctx.addHit("材质元素", "2101110225");
        ctx.addChild("3110520789", "2101110225");
        var r = resolver.resolveStrict("3110520789", ctx);
        assertEquals(BomNodeTypeResolver.PART, r.nodeType);
        assertTrue(r.structural);
    }

    @Test
    void rule4_hitOutsourcedTab_resolvesOutsourced() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("外购件", "S-1630010773");
        var r = resolver.resolveStrict("S-1630010773", ctx);
        assertEquals(BomNodeTypeResolver.OUTSOURCED, r.nodeType);
    }

    @Test
    void rule5_hitFinishedTab_strictThrows400() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("主件", "3120018220");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolveStrict("3120018220", ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("成品"));
    }

    @Test
    void rule5_hitFinishedTab_lenientReturnsNull() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("主件", "3120018220");
        assertNull(resolver.resolveLenient("3120018220", ctx));
    }

    @Test
    void rule6_zeroHit_strictThrows400() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolveStrict("UNKNOWN-PART", ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不是有效的报价产品"));
    }

    @Test
    void rule6_zeroHit_lenientReturnsNull() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        assertNull(resolver.resolveLenient("UNKNOWN-PART", ctx));
    }

    @Test
    void conflict_hitTwoDifferentTypes_strictThrows409WithConflictTabs() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "X-1");
        ctx.addHit("零件", "X-1");
        TreeConflictException ex = assertThrows(TreeConflictException.class,
                () -> resolver.resolveStrict("X-1", ctx));
        assertEquals(409, ex.getCode());
        assertEquals(2, ex.getConflictTabs().size());
        assertTrue(ex.getConflictTabs().contains("材质元素"));
        assertTrue(ex.getConflictTabs().contains("零件"));
    }

    @Test
    void conflict_hitTwoDifferentTypes_lenientReturnsNull() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "X-1");
        ctx.addHit("外购件", "X-1");
        assertNull(resolver.resolveLenient("X-1", ctx));
    }

    /**
     * 2026-07-21 裁决：同一料号命中同类型的<b>多个</b>页签（如两个「材质元素」Tab）不算冲突，
     * 类型无歧义，正常判定为材质。
     */
    @Test
    void sameTypeMultipleTabs_notAConflict() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "AgNi11#");   // 页签 A
        ctx.addHit("材质元素", "AgNi11#");   // 页签 B（同类型另一个 Tab，命中同一料号）
        var r = resolver.resolveStrict("AgNi11#", ctx);
        assertEquals(BomNodeTypeResolver.MATERIAL, r.nodeType);
    }

    /**
     * 2026-07-21 裁决 Q4：规则三只查【直接子节点】，不做任意深度子孙检索。
     * A 的直接子是 B（B 本身零命中，不是「材质元素」），B 的子 C 才命中材质 —— 这不满足 A 的规则三
     * （A 的直接子 B 不是材质），A 应零命中(拒绝)；B 的直接子 C 命中材质 → B 判零件(结构推导)。
     */
    @Test
    void rule3_onlyDirectChild_notTransitive() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "C");
        ctx.addChild("A", "B");
        ctx.addChild("B", "C");

        var rb = resolver.resolveStrict("B", ctx);
        assertEquals(BomNodeTypeResolver.PART, rb.nodeType);
        assertTrue(rb.structural);

        // A 的直接子 B 未命中材质元素页签 → 规则三不成立 → 规则六(零命中)拒绝
        BusinessException ex = assertThrows(BusinessException.class, () -> resolver.resolveStrict("A", ctx));
        assertEquals(400, ex.getCode());
        assertNull(resolver.resolveLenient("A", ctx));
    }

    /** 防环：循环引用不应死循环（规则三只查一层，天然无递归风险），最终按零命中处理（lenient 返回 null）。 */
    @Test
    void cyclicChildren_doesNotInfiniteLoop() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addChild("A", "B");
        ctx.addChild("B", "A");
        assertNull(resolver.resolveLenient("A", ctx));
    }

    /**
     * DAG 场景（现网实例）：3110520789 同时挂在 2120011658 / 2120011659 下。
     * 3110520789 自身的直接子 2101110225 命中材质元素页签 → 按规则三判零件(结构推导)；
     * 该判定只依赖 3110520789 自己的直接子集合，与"经由哪个父件访问到它"无关(materialNo 维度,非 node 维度)。
     */
    @Test
    void dagSharedChild_resolvesConsistentlyRegardlessOfParent() {
        var ctx = new BomNodeTypeResolver.TabHitContext();
        ctx.addHit("材质元素", "2101110225");
        ctx.addChild("2120011658", "3110520789");
        ctx.addChild("2120011659", "3110520789");
        ctx.addChild("3110520789", "2101110225");
        var r1 = resolver.resolveStrict("3110520789", ctx);
        var r2 = resolver.resolveStrict("3110520789", ctx);
        assertEquals(BomNodeTypeResolver.PART, r1.nodeType);
        assertEquals(BomNodeTypeResolver.PART, r2.nodeType);
    }
}
