package com.cpq.datapath.cache;

import com.cpq.datapath.CpqPathParser;
import com.cpq.datapath.ast.PathExpression;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * AST 缓存包装器（X.3 三层缓存 — 第一层）。
 *
 * <p>对 {@link CpqPathParser#parse(String)} 做缓存包装，cache key = 路径字符串（大小写敏感）。
 *
 * <p>直接使用 Caffeine API 而非 {@code @CacheResult} 注解，原因：
 * Quarkus {@code @CacheResult} 与 {@code @Transactional} 拦截器顺序不稳定，
 * 在某些场景下会在事务提交前执行缓存失效，导致缓存数据不一致。
 * 使用手写 Caffeine 实例可完全绕开此问题（与 SystemConfigService 的取舍相同）。
 */
@ApplicationScoped
public class CachedPathParser {

    private final CpqPathParser delegate = new CpqPathParser();

    private final Cache<String, PathExpression> astCache;

    public CachedPathParser(
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-ast\".maximum-size",
                            defaultValue = "10000") long maxSize,
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-ast\".expire-after-write",
                            defaultValue = "30m") String expireAfterWrite) {
        long expireMinutes = parseDuration(expireAfterWrite);
        this.astCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .recordStats()
                .build();
    }

    /**
     * 解析路径字符串，优先从 AST 缓存返回。Cache key = path（大小写敏感）。
     *
     * @param path 路径字符串（与 {@link CpqPathParser#parse} 相同语义）
     * @return 解析好的 PathExpression
     */
    public PathExpression parse(String path) {
        return astCache.get(path, delegate::parse);
    }

    /**
     * 主动失效某个路径的缓存条目（模板发布时可按需调用）。
     */
    public void invalidate(String path) {
        astCache.invalidate(path);
    }

    /** 清空整个 AST 缓存（慎用，主要供测试和管理端点使用）。 */
    public void invalidateAll() {
        astCache.invalidateAll();
    }

    /** 返回底层 Caffeine 缓存实例（供 CacheStatsResource 读取统计）。 */
    public Cache<String, PathExpression> getRawCache() {
        return astCache;
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    /**
     * 解析形如 "30m" / "10m" / "60s" 的时长字符串，返回分钟数。
     * 仅处理 m/s 两种单位；其他情况默认 30 分钟。
     */
    static long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return 30L;
        raw = raw.trim();
        if (raw.endsWith("m")) {
            try { return Long.parseLong(raw.substring(0, raw.length() - 1)); } catch (NumberFormatException ignored) {}
        } else if (raw.endsWith("s")) {
            try { return Math.max(1L, Long.parseLong(raw.substring(0, raw.length() - 1)) / 60); } catch (NumberFormatException ignored) {}
        }
        return 30L;
    }
}
