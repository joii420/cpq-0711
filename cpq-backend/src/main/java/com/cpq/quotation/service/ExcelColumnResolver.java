package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 3.1：Excel 视图"列定义"统一解析器。
 *
 * <p>列定义归属从 {@code Template.excelViewConfig} 迁移到被引用的 {@code component_type='EXCEL'}
 * 组件的 {@code excel_columns}。所有列定义读取站点统一走 {@link #getEffectiveColumns(Template)}。
 *
 * <p>独立 {@code @ApplicationScoped} bean 而非塞进 ExcelViewService，避免与
 * TemplateFormulaService 形成循环依赖（ExcelViewService→TemplateFormulaService，
 * TemplateFormulaService 也需要列定义；两者都注入本 resolver 即可）。
 *
 * <p>新 excelViewConfig 形状（对象，version 2）：
 * <pre>{ "version":2, "import_settings":{...}, "excel_component_id":"&lt;uuid&gt;",
 *        "column_overrides":[{"col_key":"A","hidden":true}] }</pre>
 * import_settings 仍留在 excelViewConfig，读取不变。
 */
@ApplicationScoped
public class ExcelColumnResolver {

    private static final Logger LOG = Logger.getLogger(ExcelColumnResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 统一列定义解析入口。逻辑：
     * <ol>
     *   <li>解析 {@code t.excelViewConfig}；空/null → 返回空列表。</li>
     *   <li>旧裸数组格式（无 excel_component_id）→ 原样返回（向后兼容，老模板继续渲染）。</li>
     *   <li>对象格式：读 {@code excel_component_id} → {@code Component.findById}；
     *       为空或 componentType != "EXCEL" → 返回空列表。</li>
     *   <li>base = component.excelColumns 解析数组；应用 column_overrides 合并后返回。</li>
     * </ol>
     */
    public List<Map<String, Object>> getEffectiveColumns(Template t) {
        if (t == null || t.excelViewConfig == null || t.excelViewConfig.isBlank()) {
            return new ArrayList<>();
        }
        Object parsed;
        try {
            parsed = MAPPER.readValue(t.excelViewConfig, Object.class);
        } catch (Exception e) {
            LOG.warnf("[ExcelColumnResolver] getEffectiveColumns parse failed tmpl=%s: %s",
                t.id, e.getMessage());
            return new ArrayList<>();
        }
        // 2. 旧裸数组格式 → 原样返回
        if (isLegacyArrayConfig(parsed)) {
            return parseJsonArray(t.excelViewConfig);
        }
        if (!(parsed instanceof Map)) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) parsed;
        Object compIdObj = config.get("excel_component_id");
        if (compIdObj == null || compIdObj.toString().isBlank()) {
            // 对象格式但无 excel_component_id（如仅含 import_settings 的旧对象）→ 无列定义
            return new ArrayList<>();
        }
        Component excelComp;
        try {
            excelComp = Component.findById(UUID.fromString(compIdObj.toString()));
        } catch (Exception e) {
            LOG.warnf("[ExcelColumnResolver] bad excel_component_id='%s' tmpl=%s: %s",
                compIdObj, t.id, e.getMessage());
            return new ArrayList<>();
        }
        if (excelComp == null || !"EXCEL".equals(excelComp.componentType)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> base = parseJsonArray(excelComp.excelColumns);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overrides = config.get("column_overrides") instanceof List
            ? (List<Map<String, Object>>) config.get("column_overrides")
            : null;
        return mergeColumnOverrides(base, overrides);
    }

    /**
     * PURE：把每个 override 合并进 col_key 匹配的 base 行，仅拷贝 override 中**存在**的键
     * （非 putAll，避免 null 覆盖 base 已有值）。返回 base 的浅拷贝列表（不改入参）。
     */
    public static List<Map<String, Object>> mergeColumnOverrides(
            List<Map<String, Object>> baseColumns, List<Map<String, Object>> overrides) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (baseColumns != null) {
            for (Map<String, Object> b : baseColumns) {
                result.add(b == null ? new LinkedHashMap<>() : new LinkedHashMap<>(b));
            }
        }
        if (overrides == null || overrides.isEmpty()) return result;
        Map<String, Map<String, Object>> ovByKey = new LinkedHashMap<>();
        for (Map<String, Object> ov : overrides) {
            if (ov == null) continue;
            Object ck = ov.get("col_key");
            if (ck != null) ovByKey.put(ck.toString(), ov);
        }
        for (Map<String, Object> row : result) {
            Object ck = row.get("col_key");
            if (ck == null) continue;
            Map<String, Object> ov = ovByKey.get(ck.toString());
            if (ov == null) continue;
            for (Map.Entry<String, Object> e : ov.entrySet()) {
                if ("col_key".equals(e.getKey())) continue; // col_key 是匹配键，不覆盖
                row.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /**
     * PURE：判断解析后的 config 是否为旧裸数组格式（老格式）vs 含 excel_component_id 的对象（新格式）。
     */
    public static boolean isLegacyArrayConfig(Object parsedConfig) {
        return parsedConfig instanceof List;
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
