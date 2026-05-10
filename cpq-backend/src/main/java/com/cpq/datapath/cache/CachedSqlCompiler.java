package com.cpq.datapath.cache;

import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.sql.PathToSqlGenerator;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * SQL 编译缓存包装器（X.3 三层缓存 — 第二层）。
 *
 * <p>对 {@link PathToSqlGenerator#compile(PathExpression, SchemaContext)} 做缓存包装。
 * Cache key = {@code ast.toString() + "|" + schemaContext.getVersion()}。
 *
 * <p>当 SchemaContext.version 变化（X.4/X.6 扩列）时，key 不匹配 → 缓存自然失效。
 *
 * <p>与 {@link CachedPathParser} 相同，使用手写 Caffeine 实例绕开 @CacheResult 拦截器顺序问题。
 */
@ApplicationScoped
public class CachedSqlCompiler {

    private final PathToSqlGenerator delegate = new PathToSqlGenerator();

    private final Cache<String, SqlAndParams> sqlCache;

    public CachedSqlCompiler(
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-sql\".maximum-size",
                            defaultValue = "5000") long maxSize,
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-sql\".expire-after-write",
                            defaultValue = "30m") String expireAfterWrite) {
        long expireMinutes = CachedPathParser.parseDuration(expireAfterWrite);
        this.sqlCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .recordStats()
                .build();
    }

    /**
     * 将 AST 编译为参数化 SQL，优先从缓存返回。
     * Cache key = {@code ast.toString() + "|" + ctx.getVersion()}。
     *
     * @param ast 已解析的路径表达式
     * @param ctx Schema 上下文（含 version 字段）
     * @return 编译后的 SQL 和参数列表
     */
    public SqlAndParams compile(PathExpression ast, SchemaContext ctx) {
        String cacheKey = ast.toString() + "|" + ctx.getVersion();
        return sqlCache.get(cacheKey, k -> delegate.compile(ast, ctx));
    }

    /**
     * 主动失效某 AST + schema 版本对应的 SQL 缓存条目。
     */
    public void invalidate(PathExpression ast, SchemaContext ctx) {
        sqlCache.invalidate(ast.toString() + "|" + ctx.getVersion());
    }

    /** 清空整个 SQL 缓存（慎用）。 */
    public void invalidateAll() {
        sqlCache.invalidateAll();
    }

    /** 返回底层 Caffeine 缓存实例（供 CacheStatsResource 读取统计）。 */
    public Cache<String, SqlAndParams> getRawCache() {
        return sqlCache;
    }
}
