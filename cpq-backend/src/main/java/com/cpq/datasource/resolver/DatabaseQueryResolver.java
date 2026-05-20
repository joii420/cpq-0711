package com.cpq.datasource.resolver;

import com.cpq.datasource.dto.DataSourceTestResult;
import com.cpq.datasource.service.DataSourceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * I2: DATABASE_QUERY 数据源解析 — 委托现有 {@link DataSourceService#execute}.
 *
 * <p>config 期望字段:
 * <ul>
 *   <li>datasource_id: 已注册数据源 UUID (必填)</li>
 *   <li>params: 参数映射 (列名 → 静态值, driverRow 同名字段自动覆盖)</li>
 * </ul>
 *
 * <p>结果取 DataSourceTestResult.result 字段, 失败返 null.
 */
@ApplicationScoped
public class DatabaseQueryResolver implements DataSourceResolver {

    private static final Logger LOG = Logger.getLogger(DatabaseQueryResolver.class);

    @Inject
    DataSourceService dataSourceService;

    @Override
    public String type() { return "DATABASE_QUERY"; }

    @Override
    public Object resolve(Map<String, Object> config, Map<String, Object> driverRow) {
        Object idObj = config.get("datasource_id");
        if (idObj == null) return null;
        try {
            UUID dsId = UUID.fromString(idObj.toString());
            Map<String, String> params = new HashMap<>();
            // 1) config.params 作为基础值
            Object paramsObj = config.get("params");
            if (paramsObj instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) paramsObj).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        params.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
            }
            // 2) driverRow 同名字段覆盖 (动态绑定)
            if (driverRow != null) {
                for (Map.Entry<String, Object> e : driverRow.entrySet()) {
                    if (params.containsKey(e.getKey()) && e.getValue() != null) {
                        params.put(e.getKey(), e.getValue().toString());
                    }
                }
            }
            DataSourceTestResult r = dataSourceService.execute(dsId, params);
            if (r == null || !r.success) return null;
            return r.extractedValue;
        } catch (Exception e) {
            LOG.warnf("DATABASE_QUERY resolve failed datasource_id=%s: %s", idObj, e.getMessage());
            return null;
        }
    }
}
