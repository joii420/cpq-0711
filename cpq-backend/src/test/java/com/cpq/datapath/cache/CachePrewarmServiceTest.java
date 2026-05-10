package com.cpq.datapath.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CachePrewarmService 单元测试（X.3 缓存层）。
 *
 * <p>手动装配依赖（不依赖 CDI）。
 */
@DisplayName("CachePrewarmService — 预热钩子单元测试")
class CachePrewarmServiceTest {

    private CachedPathParser pathParser;
    private CachedSqlCompiler sqlCompiler;
    private CachedSchemaContextProvider schemaProvider;
    private CachePrewarmService prewarmService;

    @BeforeEach
    void setUp() throws Exception {
        pathParser     = new CachedPathParser(10000L, "30m");
        sqlCompiler    = new CachedSqlCompiler(5000L, "30m");
        schemaProvider = new CachedSchemaContextProvider(200L, "10m");

        prewarmService = new CachePrewarmService();
        // 手动注入（非 CDI 环境）
        setField(prewarmService, "cachedPathParser",       pathParser);
        setField(prewarmService, "cachedSqlCompiler",      sqlCompiler);
        setField(prewarmService, "schemaContextProvider",  schemaProvider);
    }

    // ── 1. prewarm 后 AST 缓存命中率 > 0 ────────────────────────────────

    @Test
    @DisplayName("prewarm 后再次 parse 相同路径应命中 AST 缓存")
    void prewarm_fillsAstCache() {
        List<String> paths = List.of("mat_part.part_no", "mat_bom.element_name");
        prewarmService.prewarm(paths);

        // 再次 parse：应全部 hit
        pathParser.parse("mat_part.part_no");
        pathParser.parse("mat_bom.element_name");

        CacheStats astStats = pathParser.getRawCache().stats();
        assertEquals(2, astStats.hitCount(), "prewarm 后再次 parse 应全部 hit");
    }

    // ── 2. prewarm 后 SQL 缓存命中率 > 0 ────────────────────────────────

    @Test
    @DisplayName("prewarm 后再次 compile 相同路径应命中 SQL 缓存")
    void prewarm_fillsSqlCache() {
        List<String> paths = List.of("mat_part.part_no");
        prewarmService.prewarm(paths);

        // 再次 compile：应 hit
        var ast = pathParser.parse("mat_part.part_no");
        sqlCompiler.compile(ast, schemaProvider.getDefaultContext());

        CacheStats sqlStats = sqlCompiler.getRawCache().stats();
        assertEquals(1, sqlStats.hitCount(), "prewarm 后再次 compile 应 hit");
    }

    // ── 3. prewarm 返回正确摘要 ──────────────────────────────────────────

    @Test
    @DisplayName("prewarm 返回正确的 total 与 succeeded 计数")
    void prewarm_returnsCorrectSummary() {
        List<String> paths = List.of(
                "mat_part.part_no",
                "mat_bom.element_name",
                "元素BOM[元素='Ag'].组成含量"
        );
        CachePrewarmService.PrewarmResult result = prewarmService.prewarm(paths);

        assertEquals(3, result.total());
        // 3 条路径均可解析，但 SQL 可能因 schema 映射而失败；
        // succeeded 计数：AST 成功即算（即使 SQL 失败也不计入 failed）
        // 实际上 mat_part.part_no / mat_bom.element_name / 元素BOM.组成含量 都在 defaultContext 中有映射
        assertEquals(3, result.succeeded());
        assertTrue(result.failed().isEmpty());
    }

    // ── 4. 非法路径不中断其余预热 ───────────────────────────────────────

    @Test
    @DisplayName("包含非法路径时，其余有效路径仍正常预热")
    void prewarm_skipsBadPaths_continuesOthers() {
        List<String> paths = List.of(
                "mat_part.part_no",
                "",                       // 非法：空字符串
                "mat_bom.element_name"
        );
        CachePrewarmService.PrewarmResult result = prewarmService.prewarm(paths);

        assertEquals(3, result.total());
        assertEquals(2, result.succeeded(), "只有 2 条有效路径应成功");
        assertEquals(1, result.failed().size(), "1 条非法路径应记录失败");
    }

    // ── 5. 空列表 prewarm 返回全零 ──────────────────────────────────────

    @Test
    @DisplayName("空列表 prewarm 返回全零摘要")
    void prewarm_emptyList_returnsZeros() {
        CachePrewarmService.PrewarmResult result = prewarmService.prewarm(List.of());
        assertEquals(0, result.total());
        assertEquals(0, result.succeeded());
        assertTrue(result.failed().isEmpty());
    }

    // ── 辅助：反射注入 ────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = CachePrewarmService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
