package com.cpq.datasource.resolver;

import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I2: GLOBAL_VARIABLE 数据源解析 — 委托 {@link GlobalVariableService#resolveValue}.
 *
 * <p>config 期望字段:
 * <ul>
 *   <li>code: 全局变量 code (必填)</li>
 *   <li>key_values: 静态 key 映射 (列名 → 字面值)</li>
 *   <li>key_field_refs: 动态 key 映射 (列名 → driver row 字段名); 留空走同名映射</li>
 * </ul>
 */
@ApplicationScoped
public class GlobalVariableResolver implements DataSourceResolver {

    private static final Logger LOG = Logger.getLogger(GlobalVariableResolver.class);

    @Inject
    GlobalVariableService globalVariableService;

    @Override
    public String type() { return "GLOBAL_VARIABLE"; }

    @Override
    public Object resolve(Map<String, Object> config, Map<String, Object> driverRow) {
        Object codeObj = config.get("code");
        if (codeObj == null) return null;
        String code = codeObj.toString();
        try {
            GlobalVariableDefinition def = globalVariableService.getByCode(code).orElse(null);
            if (def == null) return null;
            // SCALAR: 直接走 resolveValue, keyValues 传空
            if (!def.isLookup()) return globalVariableService.resolveValue(code, Map.of());

            @SuppressWarnings("unchecked")
            Map<String, Object> staticKeys = (Map<String, Object>) config.get("key_values");
            @SuppressWarnings("unchecked")
            Map<String, Object> dynRefs    = (Map<String, Object>) config.get("key_field_refs");

            Map<String, Object> resolved = new LinkedHashMap<>();
            for (String col : def.keyColumns) {
                Object v = null;
                if (staticKeys != null && staticKeys.containsKey(col)) {
                    v = staticKeys.get(col);
                } else if (driverRow != null) {
                    // 优先 dynRefs[col] 映射; 否则同名映射 driverRow[col]
                    String driverField = (dynRefs != null && dynRefs.get(col) != null)
                            ? dynRefs.get(col).toString() : col;
                    v = driverRow.get(driverField);
                }
                if (v == null) return null;
                resolved.put(col, v);
            }
            return globalVariableService.resolveValue(code, resolved);
        } catch (Exception e) {
            LOG.warnf("GLOBAL_VARIABLE resolve failed code=%s: %s", code, e.getMessage());
            return null;
        }
    }
}
