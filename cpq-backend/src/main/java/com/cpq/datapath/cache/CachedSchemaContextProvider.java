package com.cpq.datapath.cache;

import com.cpq.datapath.sql.SchemaContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * SchemaContext 元数据缓存提供者（X.3 三层缓存 — 第三层）。
 *
 * <p>缓存 {@link SchemaContext} 实例，key = version 字符串。
 * v1 阶段只有一个 "default" 版本（硬编码 14 张物理表映射）；
 * X.4/X.6 扩列时可通过 {@link #getContext(String, java.util.function.Supplier)} 注册新版本，
 * 旧版本 key 的缓存条目不受影响，10 分钟后自然过期。
 *
 * <p>使用手写 Caffeine 实例，原因同 {@link CachedPathParser}。
 */
@ApplicationScoped
public class CachedSchemaContextProvider {

    /** 内置 default context 的版本 key */
    public static final String DEFAULT_VERSION = "v1";

    private final Cache<String, SchemaContext> metadataCache;

    public CachedSchemaContextProvider(
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-metadata\".maximum-size",
                            defaultValue = "200") long maxSize,
            @ConfigProperty(name = "quarkus.cache.caffeine.\"datapath-metadata\".expire-after-write",
                            defaultValue = "10m") String expireAfterWrite) {
        long expireMinutes = CachedPathParser.parseDuration(expireAfterWrite);
        this.metadataCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .recordStats()
                .build();
    }

    /**
     * 获取 default SchemaContext（v1，硬编码 14 张物理表）。
     * 结果被缓存；10 分钟后自动过期。
     */
    public SchemaContext getDefaultContext() {
        return metadataCache.get(DEFAULT_VERSION, v -> SchemaContext.defaultContext());
    }

    /**
     * 按版本号获取 SchemaContext。若缓存中无此 version，则调用 loader 加载并缓存。
     * X.4/X.6 扩列时传入新 version 即可触发重新加载。
     *
     * @param version 版本字符串（例如 "v1", "v2"）
     * @param loader  缓存未命中时的构建函数
     */
    public SchemaContext getContext(String version, java.util.function.Supplier<SchemaContext> loader) {
        return metadataCache.get(version, v -> loader.get());
    }

    /**
     * 主动失效某版本的 SchemaContext 缓存（例如 DDL 扩列后调用）。
     */
    public void invalidate(String version) {
        metadataCache.invalidate(version);
    }

    /** 清空全部 metadata 缓存（慎用）。 */
    public void invalidateAll() {
        metadataCache.invalidateAll();
    }

    /** 返回底层 Caffeine 缓存实例（供 CacheStatsResource 读取统计）。 */
    public Cache<String, SchemaContext> getRawCache() {
        return metadataCache;
    }
}
