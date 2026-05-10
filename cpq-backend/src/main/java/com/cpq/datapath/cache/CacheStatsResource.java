package com.cpq.datapath.cache;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存监控端点（X.3）。
 *
 * <p>GET /api/cpq/datapath/cache/stats — 返回三层缓存的命中率/大小/淘汰次数。
 * 仅 SYSTEM_ADMIN 可访问。
 */
@Path("/api/cpq/datapath/cache")
@Produces(MediaType.APPLICATION_JSON)
public class CacheStatsResource {

    @Inject
    CachedPathParser cachedPathParser;

    @Inject
    CachedSqlCompiler cachedSqlCompiler;

    @Inject
    CachedSchemaContextProvider schemaContextProvider;

    /**
     * 返回三层缓存的统计信息。
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "code": 200,
     *   "data": {
     *     "datapath-ast":      { "hitRate": 0.92, "hitCount": 1234, "missCount": 108, "evictionCount": 0, "estimatedSize": 108 },
     *     "datapath-sql":      { "hitRate": 0.89, ... },
     *     "datapath-metadata": { "hitRate": 1.0,  ... }
     *   }
     * }
     * }</pre>
     */
    @GET
    @Path("/stats")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Map<String, Object>>> stats() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        result.put("datapath-ast",      toMap(cachedPathParser.getRawCache().stats(),
                                              cachedPathParser.getRawCache().estimatedSize()));
        result.put("datapath-sql",      toMap(cachedSqlCompiler.getRawCache().stats(),
                                              cachedSqlCompiler.getRawCache().estimatedSize()));
        result.put("datapath-metadata", toMap(schemaContextProvider.getRawCache().stats(),
                                              schemaContextProvider.getRawCache().estimatedSize()));
        return ApiResponse.success(result);
    }

    private static Map<String, Object> toMap(CacheStats stats, long estimatedSize) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hitRate",       Math.round(stats.hitRate() * 10000.0) / 10000.0);  // 4 位小数
        m.put("hitCount",      stats.hitCount());
        m.put("missCount",     stats.missCount());
        m.put("loadCount",     stats.loadCount());
        m.put("evictionCount", stats.evictionCount());
        m.put("estimatedSize", estimatedSize);
        return m;
    }
}
