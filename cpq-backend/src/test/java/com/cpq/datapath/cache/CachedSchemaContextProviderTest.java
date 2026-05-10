package com.cpq.datapath.cache;

import com.cpq.datapath.sql.SchemaContext;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CachedSchemaContextProvider 单元测试（X.3 缓存层）。
 */
@DisplayName("CachedSchemaContextProvider — 元数据缓存单元测试")
class CachedSchemaContextProviderTest {

    private CachedSchemaContextProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CachedSchemaContextProvider(200L, "10m");
    }

    // ── 1. getDefaultContext 两次：第二次 hit ─────────────────────────────

    @Test
    @DisplayName("getDefaultContext 调用两次：第二次应命中缓存")
    void getDefaultContext_secondCallHitsCache() {
        SchemaContext c1 = provider.getDefaultContext();
        SchemaContext c2 = provider.getDefaultContext();

        assertSame(c1, c2, "两次返回应为同一对象");

        CacheStats stats = provider.getRawCache().stats();
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.hitCount());
    }

    // ── 2. getDefaultContext 返回的 SchemaContext version 为 v1 ──────────

    @Test
    @DisplayName("默认上下文 version 应为 v1")
    void defaultContext_versionIsV1() {
        SchemaContext ctx = provider.getDefaultContext();
        assertEquals("v1", ctx.getVersion());
    }

    // ── 3. getContext 按版本隔离 ──────────────────────────────────────────

    @Test
    @DisplayName("getContext 不同版本独立缓存条目")
    void getContext_differentVersionsAreIsolated() {
        SchemaContext v1 = provider.getContext("v1", SchemaContext::defaultContext);
        SchemaContext v2 = provider.getContext("v2", () ->
                SchemaContext.builder().version("v2")
                        .tableMapping("mat_part", "mat_part_v2").build());

        assertEquals("v1", v1.getVersion());
        assertEquals("v2", v2.getVersion());

        // v1 entry 可以从 builder 方法直接解析表
        assertTrue(v1.resolveTable("元素BOM").isPresent());
        // v2 entry 只含 mat_part 映射
        assertTrue(v2.resolveTable("mat_part").isPresent());
        assertFalse(v2.resolveTable("元素BOM").isPresent());
    }

    // ── 4. invalidate 后 loader 重新调用 ─────────────────────────────────

    @Test
    @DisplayName("invalidate 后 getDefaultContext 应触发重新加载")
    void invalidate_causesReload() {
        provider.getDefaultContext();   // miss
        provider.invalidate(CachedSchemaContextProvider.DEFAULT_VERSION);
        provider.getDefaultContext();   // miss again

        CacheStats stats = provider.getRawCache().stats();
        assertEquals(2, stats.missCount(), "invalidate 后应再次 miss");
    }

    // ── 5. invalidateAll 清空 ─────────────────────────────────────────────

    @Test
    @DisplayName("invalidateAll 后缓存大小应归零")
    void invalidateAll_emptiesCache() {
        provider.getDefaultContext();
        provider.getContext("v2", () -> SchemaContext.builder().version("v2").build());
        assertEquals(2, provider.getRawCache().estimatedSize());

        provider.invalidateAll();
        assertEquals(0, provider.getRawCache().estimatedSize());
    }
}
