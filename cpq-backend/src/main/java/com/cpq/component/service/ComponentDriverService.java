package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.Component;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.dataloader.PartVersionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Y1.5 组件按 driver 路径展开服务。
 *
 * <p>给定 component(含 dataDriverPath + fields[BASIC_DATA].basic_data_path):
 * <ol>
 *   <li>用 dataDriverPath 查询 N 行(driver rows)</li>
 *   <li>对每一行,迭代所有 BASIC_DATA 字段,把 driver 行作为隐式 JOIN 谓词注入</li>
 *   <li>组装 ExpandDriverResponse 返回,前端按 row 渲染 N 行</li>
 * </ol>
 *
 * <p>无 dataDriverPath → rowCount=0 直接返回(前端走单行老逻辑)
 *
 * <p>进程级缓存：TTL=30s after-write，maximumSize=5000，
 * key = "componentId:customerId:partNo"（null 用 "_" 占位）。
 * 基础数据导入完成后调用 {@link #evictAll()} 清空，保证导入数据立即可见。
 */
@ApplicationScoped
public class ComponentDriverService {

    private static final Logger LOG = Logger.getLogger(ComponentDriverService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 进程级 Caffeine 缓存（ApplicationScoped Bean 生命周期内单例）。
     * key: "componentId:customerId:partNo"（null 部分用 "_" 占位）
     * TTL: 30 秒 after write，maximumSize: 5000
     */
    private final Cache<String, ExpandDriverResponse> expandCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(5000)
            .build();

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    /**
     * 构建缓存 key（4-arg，含 partVersion 维度）。
     * key 格式: "componentId:customerId:partNo:partVersion"（null 用 "_" 占位）
     */
    public static String cacheKey(UUID componentId, UUID customerId, String partNo, Integer partVersion) {
        return componentId + ":"
                + (customerId != null ? customerId.toString() : "_")
                + ":" + (partNo != null && !partNo.isBlank() ? partNo : "_")
                + ":" + (partVersion != null ? partVersion.toString() : "_");
    }

    /**
     * 构建缓存 key（3-arg 向后兼容重载，partVersion=null）。
     * 委托给 4-arg 重载，key 末段为 "_"。
     */
    public static String cacheKey(UUID componentId, UUID customerId, String partNo) {
        return cacheKey(componentId, customerId, partNo, null);
    }

    /**
     * 清空所有缓存条目。在基础数据导入事务提交后调用，让新数据立即可见。
     */
    public void evictAll() {
        long sizeBefore = expandCache.estimatedSize();
        expandCache.invalidateAll();
        LOG.infof("[expand-driver cache] evictAll called, estimated entries before evict=%d", sizeBefore);
    }

    /**
     * 向后兼容 3-arg 重载，partVersion=null（不注入版本过滤）。
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo) {
        return expand(componentId, customerId, partNo, null);
    }

    /**
     * 4-arg 重载：含 partVersion，set/clear PartVersionContext 确保 ImplicitJoinRewriter
     * 在 DataLoader 查询时注入 AND part_version=N 谓词，避免历史版本数据叠加重复。
     *
     * @param partVersion 料号版本号；null = 不注入版本过滤（行为等同旧版）
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion) {
        // 进程级缓存：先查 cache，hit 直接返回（key 含 partVersion 维度）
        String key = cacheKey(componentId, customerId, partNo, partVersion);
        ExpandDriverResponse cached = expandCache.getIfPresent(key);
        if (cached != null) {
            LOG.debugf("[expand-driver cache] HIT key=%s", key);
            return cached;
        }
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }

        // [Y1.5 DEBUG] 排查日志 — 确认 dataDriverPath 落库 + 入参
        LOG.infof("[Y1.5 expand-driver] componentId=%s code=%s dataDriverPath=%s partNo=%s customerId=%s partVersion=%s",
                componentId, component.code, component.dataDriverPath, partNo, customerId, partVersion);

        ExpandDriverResponse resp = new ExpandDriverResponse();
        resp.driverPath = component.dataDriverPath;
        resp.rows = new ArrayList<>();

        if (component.dataDriverPath == null || component.dataDriverPath.isBlank()) {
            resp.rowCount = 0;
            LOG.infof("[Y1.5 expand-driver] dataDriverPath EMPTY, skip expansion (component=%s)", component.code);
            expandCache.put(key, resp);
            return resp;
        }

        // 注入 partVersion 到 ThreadLocal，DataLoader/ImplicitJoinRewriter 在版本化表查询时读取
        PartVersionContext.set(partVersion);
        try {
            // 1. 查 driver 行 — 用 (partNo, customerId) 作为基础上下文隐式 JOIN
            List<Map<String, Object>> driverRows;
            try {
                driverRows = dataLoader.loadByPath(component.dataDriverPath, null, partNo, customerId).get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warnf("Driver path resolve failed: path=%s, err=%s",
                        component.dataDriverPath, e.getMessage());
                throw new BusinessException("driver 路径查询失败: " + e.getMessage());
            }
            if (driverRows == null) driverRows = List.of();
            resp.rowCount = driverRows.size();

            // 2. 拿 BASIC_DATA 字段路径列表
            List<String> basicDataPaths = parseBasicDataPaths(component.fields);

            // 3. 对每一行,逐路径求值
            for (Map<String, Object> driverRow : driverRows) {
                ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
                row.driverRow = driverRow;
                row.basicDataValues = new LinkedHashMap<>();
                for (String fieldPath : basicDataPaths) {
                    Object value = evaluatePath(fieldPath, driverRow, customerId, partNo);
                    row.basicDataValues.put(fieldPath, value);
                }
                resp.rows.add(row);
            }
        } finally {
            PartVersionContext.clear();
        }

        LOG.infof("[Y1.5 expand-driver] expanded: id=%s code=%s rows=%d partVersion=%s",
                componentId, component.code, resp.rowCount, partVersion);
        // miss 路径成功计算后写入缓存（异常会在上方抛出，不会执行到此处，确保错误不缓存）
        expandCache.put(key, resp);
        return resp;
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<String> parseBasicDataPaths(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                if (!"BASIC_DATA".equals(String.valueOf(f.get("field_type")))) continue;
                Object pathObj = f.get("basic_data_path");
                if (pathObj == null) continue;
                String path = String.valueOf(pathObj).trim();
                if (path.isEmpty()) continue;
                // 统一加上花括号(与前端 cache key 一致)
                if (!path.startsWith("{")) path = "{" + path + "}";
                if (!out.contains(path)) out.add(path);
            }
        } catch (Exception e) {
            LOG.warnf("parse component.fields failed: %s", e.getMessage());
        }
        return out;
    }

    private Object evaluatePath(String fieldPath, Map<String, Object> driverRow,
                                 UUID customerId, String partNo) {
        // 短路：当 basic_data_path 的叶字段已经存在于 driverRow（典型的"自表"场景，
        // driver_path 与 basic_data_path 查同一张表，例如
        // driver=mat_fee[fee_type IN (..)]、field=mat_fee[..]·dim_element_name），
        // 直接从 driver 行取值即可——无需再下发一个被 ImplicitJoinRewriter 注入
        // 整行列条件、最终被自己 = 自己 收窄到无意义、又因 IN 多类型时夹击错配的 SQL。
        // 风险面：如果 driver 与 target 是不同表但恰好同名列，会"早走"读到 driver
        // 的字段而不是 target 的。CPQ 内列命名按语义统一，这种冲突极少；遇到再加白名单。
        String leafField = extractLeafField(fieldPath);
        if (leafField != null && driverRow != null && driverRow.containsKey(leafField)) {
            return driverRow.get(leafField);
        }

        EvaluationContext.Builder builder = EvaluationContext.builder()
                .dataLoader(dataLoader)
                .driverRow(driverRow);
        if (customerId != null) builder.customerId(customerId);
        if (partNo != null && !partNo.isBlank()) builder.partNo(partNo);

        Object result = formulaEngine.evaluate(fieldPath, builder.build());
        if (result instanceof FormulaError fe) {
            return fe.toString();
        }
        return result;
    }

    /**
     * 从 BASIC_DATA 路径里提取末段字段名。
     *  支持形态：
     *    {table[pred].field}  → field
     *    table.field          → field
     *    {table.field}        → field
     *    table[pred1].sub[pred2].field → field
     */
    private static String extractLeafField(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) return null;
        String s = fieldPath.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        // 找最后一个不在方括号 / 圆括号内的 '.'
        int depth = 0;
        int lastDot = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == '.' && depth == 0) lastDot = i;
        }
        if (lastDot < 0) return null;
        String tail = s.substring(lastDot + 1).trim();
        // 排除带方括号的段（说明叶子也是表引用，不是简单字段）
        if (tail.contains("[") || tail.isEmpty()) return null;
        return tail;
    }
}
