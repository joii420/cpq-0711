package com.cpq.component;

import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B9 — expand 缓存 key 补 {@code totalMaterialNo} 维度单测（纯函数，无 DB）。
 *
 * <p>背景：{@code ComponentDriverService.cacheKey} 原 4-arg 不含 {@code total_material_no} 维度，
 * 两个产品 BOM 料号集合不同时，同一组件的 expand 结果在 30s TTL 内互相串号（AP-37 型缺维度缓存
 * bug，核价侧现存隐患）。本测试验证不同 totalMaterialNo 下同组件缓存 key 不再相同。
 */
class ComponentDriverServiceCacheKeyTest {

    @AfterEach
    void clearCtx() {
        BomTreeVarsContext.clear();
    }

    private static String currentTotalMaterialNoHash() throws Exception {
        Method m = ComponentDriverService.class.getDeclaredMethod("currentTotalMaterialNoHash");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    @Test
    void fiveArgCacheKey_differentTotalMaterialNoHash_producesDifferentKeys() {
        UUID cid = UUID.randomUUID();
        UUID custId = UUID.randomUUID();
        String k1 = ComponentDriverService.cacheKey(cid, custId, "P1", null, "aaa111");
        String k2 = ComponentDriverService.cacheKey(cid, custId, "P1", null, "bbb222");
        assertNotEquals(k1, k2, "不同 totalMaterialNoHash 的 cache key 必须不同");
    }

    @Test
    void fiveArgCacheKey_nullHash_matchesFourArgOverload() {
        UUID cid = UUID.randomUUID();
        UUID custId = UUID.randomUUID();
        String k4 = ComponentDriverService.cacheKey(cid, custId, "P1", 3);
        String k5 = ComponentDriverService.cacheKey(cid, custId, "P1", 3, null);
        assertEquals(k4 + ":tmn_", k5, "null hash 应退化为占位符,不破坏既有 4-arg 语义");
    }

    @Test
    void fiveArgCacheKey_blankHash_treatedAsNull() {
        UUID cid = UUID.randomUUID();
        UUID custId = UUID.randomUUID();
        String kNull = ComponentDriverService.cacheKey(cid, custId, "P1", null, null);
        String kBlank = ComponentDriverService.cacheKey(cid, custId, "P1", null, "  ");
        assertEquals(kNull, kBlank);
    }

    @Test
    void currentTotalMaterialNoHash_noContext_returnsNull() throws Exception {
        BomTreeVarsContext.clear();
        assertNull(currentTotalMaterialNoHash());
    }

    @Test
    void currentTotalMaterialNoHash_differentMaterialSets_differentHash() throws Exception {
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, List.of("A", "B", "C")));
        String h1 = currentTotalMaterialNoHash();
        assertNotNull(h1);

        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, List.of("X", "Y")));
        String h2 = currentTotalMaterialNoHash();
        assertNotNull(h2);

        assertNotEquals(h1, h2, "不同产品 BOM 料号集合应产生不同 hash,避免 expand 缓存跨产品串号");
    }

    @Test
    void currentTotalMaterialNoHash_sameMaterialSet_sameHash() throws Exception {
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, List.of("A", "B", "C")));
        String h1 = currentTotalMaterialNoHash();
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, List.of("A", "B", "C")));
        String h2 = currentTotalMaterialNoHash();
        assertEquals(h1, h2, "相同内容的料号集合应命中同一缓存 key(不因对象实例不同而误判串号)");
    }

    @Test
    void currentTotalMaterialNoHash_emptyList_returnsNull() throws Exception {
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(null, List.of()));
        assertNull(currentTotalMaterialNoHash(), "空料号集合应退化为 null(与未设置上下文一致)");
    }
}
