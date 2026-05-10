package com.cpq.formula.resource;

import com.cpq.formula.dto.EvaluateResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 公式求值进程级缓存 — 静态 holder，任何模块皆可调用 evictAll()。
 *
 * <p>key 格式：{@code expression:customerId:partNo}（null 用 "_" 占位）。
 * <ul>
 *   <li>TTL: 30 秒 after-write</li>
 *   <li>maximumSize: 10000 条目（formula 数量比 expand-driver 多）</li>
 *   <li>仅缓存 success=true 响应；错误响应不缓存，避免临时错误固化</li>
 *   <li>含 bindings / driverRow 的请求绕过缓存，因 key 难以稳定化</li>
 * </ul>
 */
public final class FormulaEvalCache {

    private static final Logger LOG = Logger.getLogger(FormulaEvalCache.class);

    private static final Cache<String, EvaluateResponse> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    private FormulaEvalCache() {}

    /**
     * 构建缓存 key。
     *
     * @param expression  公式表达式（非空）
     * @param customerId  客户 UUID（可 null）
     * @param partNo      料号（可 null / 空）
     * @return key 字符串
     */
    public static String buildKey(String expression, UUID customerId, String partNo) {
        return expression
                + ":" + (customerId != null ? customerId.toString() : "_")
                + ":" + (partNo != null && !partNo.isBlank() ? partNo : "_");
    }

    /** 按 key 查缓存，未命中返回 null。 */
    public static EvaluateResponse getIfPresent(String key) {
        return CACHE.getIfPresent(key);
    }

    /** 写入缓存（仅供 success=true 的响应使用）。 */
    public static void put(String key, EvaluateResponse value) {
        CACHE.put(key, value);
    }

    /**
     * 清空所有缓存条目。
     * 在基础数据导入事务提交后调用，让新导入数据对公式求值立即可见。
     */
    public static void evictAll() {
        long sizeBefore = CACHE.estimatedSize();
        CACHE.invalidateAll();
        LOG.infof("[formula-eval cache] evictAll called, estimated entries before evict=%d", sizeBefore);
    }
}
