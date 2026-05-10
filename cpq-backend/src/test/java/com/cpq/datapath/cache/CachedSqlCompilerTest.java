package com.cpq.datapath.cache;

import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CachedSqlCompiler 单元测试（X.3 缓存层）。
 */
@DisplayName("CachedSqlCompiler — SQL 缓存单元测试")
class CachedSqlCompilerTest {

    private CachedPathParser pathParser;
    private CachedSqlCompiler sqlCompiler;

    @BeforeEach
    void setUp() {
        pathParser   = new CachedPathParser(10000L, "30m");
        sqlCompiler  = new CachedSqlCompiler(5000L, "30m");
    }

    // ── 1. 同一 AST + 相同版本：第二次命中 SQL cache ──────────────────────

    @Test
    @DisplayName("同一 AST + 相同 schema 版本：第二次应命中 SQL 缓存")
    void sameAstAndVersion_hitsCache() {
        SchemaContext ctx = SchemaContext.defaultContext();   // version = "v1"
        PathExpression ast = pathParser.parse("元素BOM[元素='Ag'].组成含量");

        SqlAndParams s1 = sqlCompiler.compile(ast, ctx);
        SqlAndParams s2 = sqlCompiler.compile(ast, ctx);

        // SQL 内容相同
        assertEquals(s1.sql(), s2.sql());

        CacheStats stats = sqlCompiler.getRawCache().stats();
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.hitCount());
    }

    // ── 2. SchemaContext version 变化 → SQL cache miss ────────────────────

    @Test
    @DisplayName("SchemaContext version 变化应导致 SQL 缓存 miss")
    void differentSchemaVersion_causesCacheMiss() {
        PathExpression ast = pathParser.parse("mat_part.part_no");

        SchemaContext ctxV1 = SchemaContext.builder().version("v1")
                .tableMapping("mat_part", "mat_part").build();
        SchemaContext ctxV2 = SchemaContext.builder().version("v2")
                .tableMapping("mat_part", "mat_part").build();

        sqlCompiler.compile(ast, ctxV1);
        sqlCompiler.compile(ast, ctxV2);  // 不同 version → 不同 key → miss

        CacheStats stats = sqlCompiler.getRawCache().stats();
        assertEquals(2, stats.missCount(), "v1 和 v2 应各触发一次 miss");
        assertEquals(0, stats.hitCount());
    }

    // ── 3. 不同 AST + 相同版本：各自 miss ────────────────────────────────

    @Test
    @DisplayName("不同 AST + 相同版本：各自独立 miss")
    void differentAst_eachMissIndependently() {
        SchemaContext ctx = SchemaContext.defaultContext();

        PathExpression ast1 = pathParser.parse("mat_part.part_no");
        PathExpression ast2 = pathParser.parse("mat_bom.element_name");

        sqlCompiler.compile(ast1, ctx);
        sqlCompiler.compile(ast2, ctx);

        CacheStats stats = sqlCompiler.getRawCache().stats();
        assertEquals(2, stats.missCount());
        assertEquals(0, stats.hitCount());
    }

    // ── 4. 缓存失效后重新编译 ─────────────────────────────────────────────

    @Test
    @DisplayName("invalidate 后再次编译应触发 miss")
    void invalidate_causesRecompile() {
        SchemaContext ctx = SchemaContext.defaultContext();
        PathExpression ast = pathParser.parse("mat_part.part_no");

        sqlCompiler.compile(ast, ctx);
        sqlCompiler.invalidate(ast, ctx);
        sqlCompiler.compile(ast, ctx);   // 重新编译

        CacheStats stats = sqlCompiler.getRawCache().stats();
        assertEquals(2, stats.missCount(), "invalidate 后第二次应再次 miss");
    }

    // ── 5. SQL 内容正确性验证 ─────────────────────────────────────────────

    @Test
    @DisplayName("缓存命中时返回的 SQL 内容与首次编译结果完全一致")
    void cachedSql_contentMatchesOriginal() {
        SchemaContext ctx = SchemaContext.defaultContext();
        PathExpression ast = pathParser.parse("元素BOM[元素='Ag'].组成含量");

        SqlAndParams first  = sqlCompiler.compile(ast, ctx);
        SqlAndParams second = sqlCompiler.compile(ast, ctx);

        assertEquals(first.sql(),            second.sql());
        assertEquals(first.params(),         second.params());
        assertEquals(first.selectedColumns(), second.selectedColumns());
    }
}
