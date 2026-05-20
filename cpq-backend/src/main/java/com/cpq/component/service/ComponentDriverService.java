package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.Component;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.dataloader.PartVersionContext;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
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

    @Inject
    GlobalVariableService globalVariableService;

    /** V190+: 合成 key 前缀, 标识 basicDataValues 中"全局变量行级值"条目 (避免与 BNF path key 冲突) */
    public static final String GVAR_KEY_PREFIX = "@gvar:";

    public static String gvarKey(String code) {
        return GVAR_KEY_PREFIX + code;
    }

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
        return expand(componentId, customerId, partNo, partVersion, null, null);
    }

    /**
     * V195 hotfix: 带 snapshot override 的 expand. 让 template snapshot 作为 driver_path/fields 真理源,
     * 不再依赖 component 表 (避免同 component 被多模板共用导致 driver 不一致).
     */
    public ExpandDriverResponse expand(UUID componentId, UUID customerId, String partNo, Integer partVersion,
                                       String overrideDataDriverPath, String overrideFieldsJson) {
        // cache key 加 override 哈希避免不同 snapshot 共享 cache 串号
        String overrideTag = "";
        if (overrideDataDriverPath != null || overrideFieldsJson != null) {
            overrideTag = ":ov" + Integer.toHexString(java.util.Objects.hash(overrideDataDriverPath, overrideFieldsJson));
        }
        String key = cacheKey(componentId, customerId, partNo, partVersion) + overrideTag;
        ExpandDriverResponse cached = expandCache.getIfPresent(key);
        if (cached != null) {
            LOG.debugf("[expand-driver cache] HIT key=%s", key);
            return cached;
        }
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }

        // V195 override: 优先用 snapshot 提供的 driver_path / fields
        String effectiveDriverPath = (overrideDataDriverPath != null && !overrideDataDriverPath.isBlank())
                ? overrideDataDriverPath : component.dataDriverPath;
        String effectiveFieldsJson = (overrideFieldsJson != null && !overrideFieldsJson.isBlank())
                ? overrideFieldsJson : component.fields;

        LOG.infof("[Y1.5 expand-driver] componentId=%s code=%s dataDriverPath=%s (override=%s) partNo=%s customerId=%s partVersion=%s",
                componentId, component.code, effectiveDriverPath,
                (overrideDataDriverPath != null), partNo, customerId, partVersion);

        ExpandDriverResponse resp = new ExpandDriverResponse();
        resp.driverPath = effectiveDriverPath;
        resp.rows = new ArrayList<>();

        if (effectiveDriverPath == null || effectiveDriverPath.isBlank()) {
            // hotfix: 没有 dataDriverPath 的组件视为「产品级单行」 — 用 (partNo, customerId) 作虚拟
            // driver row, 解所有 BASIC_DATA 字段路径塞 basicDataValues. 前端按 driver 分支取值,
            // 绕开 usePathFormulaCache → globalPathCache 路径 (后者偶有 cache key 不一致问题).
            //
            // 这样 COMP-CFG-MATERIAL-RECIPE 等产品级组件 (v_part_material_recipe.code 等) 直接通过
            // 行级 basicDataValues 拿到值, 不再永远"加载中".
            List<String> basicDataPaths = parseBasicDataPaths(effectiveFieldsJson);
            List<GvarDefaultTask> gvarTasks = parseGvarDefaultTasks(effectiveFieldsJson);
            if (basicDataPaths.isEmpty() && gvarTasks.isEmpty()) {
                resp.rowCount = 0;
                LOG.infof("[Y1.5 expand-driver] dataDriverPath EMPTY + no BASIC_DATA, skip (component=%s)", component.code);
                expandCache.put(key, resp);
                return resp;
            }
            // 虚拟 driver row: 仅含 partNo / customerId, 让 ImplicitJoinRewriter 能注入谓词
            Map<String, Object> virtualRow = new LinkedHashMap<>();
            if (partNo != null && !partNo.isBlank()) {
                virtualRow.put("hf_part_no", partNo);
                virtualRow.put("part_no", partNo);
            }
            if (customerId != null) {
                virtualRow.put("customer_id", customerId);
            }
            ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
            row.driverRow = virtualRow;
            row.basicDataValues = new LinkedHashMap<>();
            for (String fieldPath : basicDataPaths) {
                Object value = evaluatePath(fieldPath, virtualRow, customerId, partNo);
                row.basicDataValues.put(fieldPath, value);
            }
            for (GvarDefaultTask task : gvarTasks) {
                row.basicDataValues.put(gvarKey(task.code), resolveGvarForRow(task, virtualRow));
            }
            resp.rows.add(row);
            resp.rowCount = 1;
            LOG.infof("[Y1.5 expand-driver] no-driver virtual single row (component=%s, basicDataPaths=%d)",
                    component.code, basicDataPaths.size());
            expandCache.put(key, resp);
            return resp;
        }

        // 注入 partVersion 到 ThreadLocal，DataLoader/ImplicitJoinRewriter 在版本化表查询时读取
        PartVersionContext.set(partVersion);
        try {
            // 1. 查 driver 行 — 用 (partNo, customerId) 作为基础上下文隐式 JOIN
            List<Map<String, Object>> driverRows;
            try {
                driverRows = dataLoader.loadByPath(effectiveDriverPath, null, partNo, customerId).get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warnf("Driver path resolve failed: path=%s, err=%s",
                        effectiveDriverPath, e.getMessage());
                throw new BusinessException("driver 路径查询失败: " + e.getMessage());
            }
            if (driverRows == null) driverRows = List.of();
            resp.rowCount = driverRows.size();

            // 2. 拿 BASIC_DATA 字段路径列表 + V190 default_source GLOBAL_VARIABLE 解析任务
            List<String> basicDataPaths = parseBasicDataPaths(effectiveFieldsJson);
            List<GvarDefaultTask> gvarTasks = parseGvarDefaultTasks(effectiveFieldsJson);

            // 3. 对每一行,逐路径求值 + 逐全局变量求值
            for (Map<String, Object> driverRow : driverRows) {
                ExpandDriverResponse.Row row = new ExpandDriverResponse.Row();
                row.driverRow = driverRow;
                row.basicDataValues = new LinkedHashMap<>();
                for (String fieldPath : basicDataPaths) {
                    Object value = evaluatePath(fieldPath, driverRow, customerId, partNo);
                    row.basicDataValues.put(fieldPath, value);
                }
                // V190: 把字段 default_source 按本 driver 行解出的 GLOBAL_VARIABLE 值塞入合成 key
                for (GvarDefaultTask task : gvarTasks) {
                    Object value = resolveGvarForRow(task, driverRow);
                    row.basicDataValues.put(gvarKey(task.code), value);
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

    /** V190: default_source GLOBAL_VARIABLE 任务 — code + 动态 key 映射 */
    private static class GvarDefaultTask {
        final String code;
        final Map<String, String> keyFieldRefs;  // key 列名 → driver 行字段名
        GvarDefaultTask(String code, Map<String, String> keyFieldRefs) {
            this.code = code; this.keyFieldRefs = keyFieldRefs;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GvarDefaultTask> parseGvarDefaultTasks(String fieldsJson) {
        List<GvarDefaultTask> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                // 路径 A: INPUT_NUMBER/TEXT 的 default_source.GLOBAL_VARIABLE
                collectGvarTask(f.get("default_source"), out, "code", "key_field_refs");
                // 路径 B (hotfix): DATA_SOURCE 的 datasource_binding.GLOBAL_VARIABLE
                collectGvarTask(f.get("datasource_binding"), out, "global_variable_code", "key_field_refs");
            }
        } catch (Exception e) {
            LOG.warnf("parse default_source from fields failed: %s", e.getMessage());
        }
        return out;
    }

    /** 从 default_source / datasource_binding map 中抽取 GLOBAL_VARIABLE 任务. dedupe by code. */
    @SuppressWarnings("unchecked")
    private static void collectGvarTask(Object configObj, List<GvarDefaultTask> out,
                                        String codeField, String refsField) {
        if (!(configObj instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) configObj;
        if (!"GLOBAL_VARIABLE".equals(String.valueOf(m.get("type")))) return;
        Object codeObj = m.get(codeField);
        if (codeObj == null) return;
        String code = codeObj.toString();
        if (code.isBlank() || "null".equals(code)) return;
        Map<String, String> refs = new LinkedHashMap<>();
        Object refsObj = m.get(refsField);
        if (refsObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) refsObj).entrySet()) {
                refs.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        if (out.stream().noneMatch(t -> t.code.equals(code))) {
            out.add(new GvarDefaultTask(code, refs));
        }
    }

    /** V190: 按 driver row 解 GLOBAL_VARIABLE 值; key_field_refs 名映射 def.keyColumns → driver row 字段 */
    private Object resolveGvarForRow(GvarDefaultTask task, Map<String, Object> driverRow) {
        try {
            GlobalVariableDefinition def = globalVariableService.getByCode(task.code).orElse(null);
            if (def == null || !def.isLookup()) return null;
            Map<String, Object> keyValues = new LinkedHashMap<>();
            for (String col : def.keyColumns) {
                // 优先按 key_field_refs[col] 取 driver 行字段; 默认同名映射 col → driverRow[col]
                String driverField = task.keyFieldRefs.getOrDefault(col, col);
                Object v = driverRow.get(driverField);
                if (v == null) return null;  // 缺 key → 不解
                keyValues.put(col, v);
            }
            return globalVariableService.resolveValue(task.code, keyValues);
        } catch (Exception e) {
            LOG.warnf("resolveGvarForRow failed for code=%s: %s", task.code, e.getMessage());
            return null;
        }
    }

    private static void addPathIfPresent(List<String> out, Object pathObj) {
        if (pathObj == null) return;
        String path = String.valueOf(pathObj).trim();
        if (path.isEmpty()) return;
        if (!path.startsWith("{")) path = "{" + path + "}";
        if (!out.contains(path)) out.add(path);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseBasicDataPaths(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                // BASIC_DATA 字段: 主 basic_data_path
                if ("BASIC_DATA".equals(String.valueOf(f.get("field_type")))) {
                    addPathIfPresent(out, f.get("basic_data_path"));
                }
                // V184: 任意字段(典型 INPUT_NUMBER)的 default_basic_data_path —
                // 用户行值为空时回退到该路径取全局变量默认值
                addPathIfPresent(out, f.get("default_basic_data_path"));
                // V190 default_source.BNF_PATH (典型 INPUT_NUMBER 兜底走 BNF 路径)
                Object ds = f.get("default_source");
                if (ds instanceof Map<?, ?> dsMap) {
                    if ("BNF_PATH".equals(String.valueOf(dsMap.get("type")))) {
                        addPathIfPresent(out, dsMap.get("path"));
                    }
                }
                // Phase J: DATA_SOURCE.BNF_PATH 子类型 — datasource_binding.bnf_path
                // 没在这里采集会导致 batch-expand 返的 basicDataValues 不含该路径键,
                // 前端 DATA_SOURCE 渲染分支永久 "加载中" (AP-31 协议传播补)
                if ("DATA_SOURCE".equals(String.valueOf(f.get("field_type")))) {
                    Object binding = f.get("datasource_binding");
                    if (binding instanceof Map<?, ?> bMap) {
                        if ("BNF_PATH".equals(String.valueOf(bMap.get("type")))) {
                            addPathIfPresent(out, bMap.get("bnf_path"));
                        }
                    }
                }
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
