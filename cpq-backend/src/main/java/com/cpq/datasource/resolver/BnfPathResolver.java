package com.cpq.datasource.resolver;

import com.cpq.formula.dataloader.DataLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * I2: BNF_PATH 数据源解析 — 调 {@link DataLoader#loadByPath} 单值返回.
 *
 * <p>config 期望字段:
 * <ul>
 *   <li>path: BNF 路径字符串 (必填, 如 mat_part.unit_weight)</li>
 *   <li>customer_id: 可空 (求值上下文)</li>
 *   <li>part_no: 可空 (求值上下文)</li>
 * </ul>
 *
 * <p>多值结果: 取第一行第一列; 失败返 null.
 */
@ApplicationScoped
public class BnfPathResolver implements DataSourceResolver {

    private static final Logger LOG = Logger.getLogger(BnfPathResolver.class);

    @Inject
    DataLoader dataLoader;

    @Override
    public String type() { return "BNF_PATH"; }

    @Override
    public Object resolve(Map<String, Object> config, Map<String, Object> driverRow) {
        Object pathObj = config.get("path");
        if (pathObj == null) return null;
        String path = pathObj.toString();
        if (path.isBlank()) return null;
        try {
            Object cidObj = config.get("customer_id");
            java.util.UUID customerId = cidObj == null ? null : java.util.UUID.fromString(cidObj.toString());
            String partNo = config.get("part_no") == null ? null : config.get("part_no").toString();
            List<Map<String, Object>> rows = dataLoader.loadByPath(path, null, partNo, customerId).get();
            if (rows == null || rows.isEmpty()) return null;
            Map<String, Object> first = rows.get(0);
            if (first == null || first.isEmpty()) return null;
            return first.values().iterator().next();
        } catch (Exception e) {
            LOG.warnf("BNF_PATH resolve failed path=%s: %s", path, e.getMessage());
            return null;
        }
    }
}
