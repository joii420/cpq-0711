package com.cpq.datapath.cache;

import com.cpq.datapath.CpqPathParseException;
import com.cpq.datapath.ast.PathExpression;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CachedPathParser 单元测试（X.3 缓存层）。
 *
 * <p>直接 new CachedPathParser 实例（不依赖 CDI），测试缓存行为。
 * 默认构造器：maxSize=10000, expire=30m。
 */
@DisplayName("CachedPathParser — AST 缓存单元测试")
class CachedPathParserTest {

    // 使用 package-visible 构造器（依赖默认配置值的字段注入无法 new，因此直接传参）
    private CachedPathParser parser;

    @BeforeEach
    void setUp() {
        parser = new CachedPathParser(10000L, "30m");
    }

    // ── 1. 同一路径解析两次：第二次命中 cache ─────────────────────────────

    @Test
    @DisplayName("同一路径解析两次：第二次应命中 AST 缓存")
    void samePath_secondCallHitsCache() {
        String path = "元素BOM[元素='Ag'].组成含量";

        PathExpression first  = parser.parse(path);
        PathExpression second = parser.parse(path);

        // 两次返回对象等价
        assertEquals(first, second);

        // 缓存命中率：1 miss + 1 hit
        CacheStats stats = parser.getRawCache().stats();
        assertEquals(1, stats.missCount(), "首次解析应为 miss");
        assertEquals(1, stats.hitCount(),  "第二次解析应为 hit");
    }

    // ── 2. 不同路径：cache miss ────────────────────────────────────────────

    @Test
    @DisplayName("不同路径分别解析：各自独立 miss，不共用缓存条目")
    void differentPaths_eachMissIndependently() {
        parser.parse("生产料号.料号");
        parser.parse("工序资料.工序名称");

        CacheStats stats = parser.getRawCache().stats();
        assertEquals(2, stats.missCount(), "两条不同路径应各有一次 miss");
        assertEquals(0, stats.hitCount(),  "无 hit");
        assertEquals(2, parser.getRawCache().estimatedSize());
    }

    // ── 3. 大小写敏感：A 和 a 是不同 key ─────────────────────────────────

    @Test
    @DisplayName("大小写敏感：'mat_part.part_no' 与 'Mat_Part.Part_No' 是不同缓存条目")
    void caseSensitiveKeys_treatedAsSeparateEntries() {
        // mat_part.part_no 能解析成功（ASCII 路径）
        PathExpression lower = parser.parse("mat_part.part_no");

        // Mat_Part 也是合法 ASCII 标识符，解析结果不同
        PathExpression upper = parser.parse("Mat_Part.Part_No");

        // 两者不等（segment name 不同）
        assertNotEquals(lower.getPrimarySegment().getName(),
                        upper.getPrimarySegment().getName(),
                        "缓存 key 应大小写敏感");

        // 两次 parse 共触发 2 次 miss，0 次 hit
        CacheStats stats = parser.getRawCache().stats();
        assertEquals(2, stats.missCount());
        assertEquals(0, stats.hitCount());
    }

    // ── 4. 简单 ASCII 路径解析正确性 ──────────────────────────────────────

    @Test
    @DisplayName("ASCII 路径解析后缓存条目可复用")
    void asciiPath_cachedAndReusable() {
        PathExpression a1 = parser.parse("mat_bom.element_name");
        PathExpression a2 = parser.parse("mat_bom.element_name");
        assertSame(a1, a2, "两次返回应为同一对象（Caffeine 缓存引用）");
    }

    // ── 5. invalidate 后再解析应重新计算 ────────────────────────────────

    @Test
    @DisplayName("invalidate 后再次解析应触发 miss")
    void invalidate_causesReParse() {
        String path = "plating_plan.plan_code";
        parser.parse(path);
        parser.invalidate(path);
        parser.parse(path);

        CacheStats stats = parser.getRawCache().stats();
        assertEquals(2, stats.missCount(), "invalidate 后第二次应再次 miss");
    }

    // ── 6. 空路径抛出异常，且不缓存 ─────────────────────────────────────

    @Test
    @DisplayName("空路径应抛出 CpqPathParseException，且不留下缓存条目")
    void blankPath_throwsAndNotCached() {
        assertThrows(CpqPathParseException.class, () -> parser.parse("   "));
        assertEquals(0, parser.getRawCache().estimatedSize(), "异常路径不应缓存");
    }

    // ── 7. 带 IN 谓词的路径正常缓存 ─────────────────────────────────────

    @Test
    @DisplayName("含 IN 谓词的路径可正常缓存")
    void pathWithInPredicate_cached() {
        String path = "mat_part[customer_id IN ('c1','c2')].part_no";
        PathExpression first  = parser.parse(path);
        PathExpression second = parser.parse(path);
        assertEquals(first, second);
        assertEquals(1, parser.getRawCache().stats().hitCount());
    }

    // ── 8. 带 LIKE 谓词的路径正常缓存 ───────────────────────────────────

    @Test
    @DisplayName("含 LIKE 谓词的路径可正常缓存")
    void pathWithLikePredicate_cached() {
        String path = "mat_part[part_no LIKE 'AG%'].part_no";
        parser.parse(path);
        parser.parse(path);
        assertEquals(1, parser.getRawCache().stats().hitCount());
    }

    // ── 9. invalidateAll 清空所有条目 ───────────────────────────────────

    @Test
    @DisplayName("invalidateAll 后缓存大小应归零")
    void invalidateAll_emptiesCache() {
        parser.parse("mat_bom.element_name");
        parser.parse("mat_part.part_no");
        assertEquals(2, parser.getRawCache().estimatedSize());

        parser.invalidateAll();
        assertEquals(0, parser.getRawCache().estimatedSize());
    }

    // ── 10. stats 端点数据非空 ──────────────────────────────────────────

    @Test
    @DisplayName("执行解析后，getRawCache().stats() 返回非零请求数")
    void stats_nonEmptyAfterParse() {
        parser.parse("mat_bom.element_name");
        parser.parse("mat_bom.element_name");

        CacheStats stats = parser.getRawCache().stats();
        assertTrue(stats.requestCount() >= 2, "至少 2 次请求（1 miss + 1 hit）");
        assertTrue(stats.hitRate() > 0, "命中率应大于 0");
    }
}
