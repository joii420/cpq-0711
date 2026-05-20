package com.cpq.datasource.resolver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * I2: DataSourceResolver 注册表 — CDI 启动时自动收集所有 DataSourceResolver 实现.
 *
 * <p>统一入口 {@link #resolve(String, Map, Map)} 按 type 分发.
 * 未注册的 type 抛 IllegalArgumentException, 调用方应在响应层兜底.
 */
@ApplicationScoped
public class DataSourceResolverRegistry {

    private static final Logger LOG = Logger.getLogger(DataSourceResolverRegistry.class);

    private final Map<String, DataSourceResolver> byType = new HashMap<>();

    @Inject
    Instance<DataSourceResolver> resolvers;

    @jakarta.annotation.PostConstruct
    void init() {
        for (DataSourceResolver r : resolvers) {
            String t = r.type();
            if (t == null || t.isBlank()) continue;
            if (byType.containsKey(t)) {
                LOG.warnf("Duplicate DataSourceResolver type %s — keep first: %s",
                        t, byType.get(t).getClass().getSimpleName());
                continue;
            }
            byType.put(t, r);
            LOG.infof("Registered DataSourceResolver type=%s impl=%s",
                    t, r.getClass().getSimpleName());
        }
    }

    /** 列出所有已注册 type. UI 「数据源类型」下拉可调本接口动态渲染. */
    public java.util.Set<String> registeredTypes() {
        return byType.keySet();
    }

    /**
     * 按 type 分发解析.
     *
     * @param type      数据源类型字符串 (DATABASE_QUERY / GLOBAL_VARIABLE / BNF_PATH / HTTP_API)
     * @param config    type 各自的 config 配置 Map
     * @param driverRow 当前 driver 行字段 (可空)
     * @return 解析后的标量值; null 表示失败/未命中
     */
    public Object resolve(String type, Map<String, Object> config, Map<String, Object> driverRow) {
        if (type == null) throw new IllegalArgumentException("type 不能为空");
        DataSourceResolver r = byType.get(type);
        if (r == null) {
            throw new IllegalArgumentException("未注册的数据源类型: " + type
                    + " (已注册: " + byType.keySet() + ")");
        }
        return r.resolve(config == null ? Map.of() : config, driverRow);
    }
}
