package com.cpq.component;

import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import com.cpq.datasource.sqlview.QuotePendingScope;
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
        QuotePendingScope.restore(null);
    }

    private static String currentTotalMaterialNoHash() throws Exception {
        Method m = ComponentDriverService.class.getDeclaredMethod("currentTotalMaterialNoHash");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    /**
     * task-0725 T2：反射调用 {@code expand()} 内联 key 拼接抽取出的私有静态方法
     * {@code buildExtraCacheTags(overrideTag, lineItemTag, childTag, qidTag)}——固化
     * override/lineItem/child/qid 四个既有标签 + {@link QuotePendingScope#cacheTag()} 的拼接顺序。
     */
    private static String buildExtraCacheTags(String overrideTag, String lineItemTag,
                                               String childTag, String qidTag) throws Exception {
        Method m = ComponentDriverService.class.getDeclaredMethod(
                "buildExtraCacheTags", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, overrideTag, lineItemTag, childTag, qidTag);
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

    // ─────────────────── task-0725 T2：pending 可见域缓存维度 ───────────────────

    @Test
    void buildExtraCacheTags_scopeClosed_matchesPreChangeFormat() throws Exception {
        // 关闭态：QuotePendingScope.cacheTag()=="" ⟹ 四个既有标签原样拼接，逐字等于改动前
        // （expand() 内联版本原为 overrideTag + lineItemTag + childTag + qidTag，无第 5 项）。
        String result = buildExtraCacheTags("", "", "", "");
        assertEquals("", result);

        String withTags = buildExtraCacheTags(":ovabc", ":li123", ":cld456", ":q789");
        assertEquals(":ovabc:li123:cld456:q789", withTags,
                "关闭态下拼接结果必须与改动前（无 pending 维度）逐字相同");
    }

    @Test
    void buildExtraCacheTags_scopeOpenVsClosed_differ() throws Exception {
        String closed = buildExtraCacheTags(":ovabc", ":li123", ":cld456", ":q789");
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            String open = buildExtraCacheTags(":ovabc", ":li123", ":cld456", ":q789");
            assertNotEquals(closed, open, "scope 开/关下 ComponentDriverService 的 cache key 必须不同");
            assertTrue(open.startsWith(closed), "既有四个标签必须原样保留在前缀，pending 标签只追加在末尾");
            assertTrue(open.contains(qid.toString().replace("-", "")));
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void buildExtraCacheTags_cannotBeConfusedWithQidTag() throws Exception {
        // 回归防呆：qidTag（":q<qid>"）与 pending 标签（":pq<qid>"）即便 qid 相同也不能合并——
        // 报价侧与核价侧的 _qid 是同一个值，必须靠独立维度区分。此测试用同一个 qid 分别构造
        // qidTag 与 pending 标签，断言两者在最终字符串中都完整出现且不是同一段。
        UUID qid = UUID.randomUUID();
        String qidHex = qid.toString().replace("-", "");
        String qidTag = ":q" + qidHex;
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        try {
            String result = buildExtraCacheTags("", "", "", qidTag);
            String expectedPendingTag = ":pq" + qidHex;
            assertTrue(result.contains(qidTag), "qidTag 段必须保留");
            assertTrue(result.contains(expectedPendingTag), "pending 标签段必须独立存在");
            assertEquals(qidTag + expectedPendingTag, result,
                    "两个维度必须都出现且互不覆盖（顺序：既有四标签在前，pending 标签在最后）");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }
}
