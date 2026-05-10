package com.cpq.formula.calculator;

import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 衍生属性计算器 V5（替代 v4 DerivedAttributeCalculator）。
 *
 * <p>基于：
 * <ul>
 *   <li>X.2 BNF 解析器（通过 DataLoader 内置调用）</li>
 *   <li>X.3 缓存层（CachedPathParser + CachedSqlCompiler）</li>
 *   <li>X.6 FunctionRegistry + 7 大类函数</li>
 *   <li>X.6 DataLoader（per-request dedupe）</li>
 * </ul>
 *
 * <p>V4 DerivedAttributeCalculator（已 @Deprecated）由路线 X X.5 阶段删除。
 * 本类与之独立，不依赖任何 v4 productdata 实现。
 *
 * <h2>API</h2>
 * <pre>
 *   Map&lt;String, Object&gt; results = calculator.calculate(customerId, partNo, attrs);
 *   // results: {variableCode -> 计算结果 or FormulaError}
 * </pre>
 *
 * <h2>DataLoader 注入</h2>
 * DataLoader 是 {@code @RequestScoped}，在 HTTP 请求上下文中自动注入。
 * 测试中需手动创建 DataLoader 实例。
 */
@ApplicationScoped
public class DerivedAttributeCalculatorV5 {

    private static final Logger LOG = Logger.getLogger(DerivedAttributeCalculatorV5.class);

    @Inject
    FormulaEngine formulaEngine;

    /**
     * DataLoader 是 @RequestScoped，通过 Instance 延迟获取，避免 ApplicationScoped 直接注入 RequestScoped Bean。
     */
    @Inject
    Instance<DataLoader> dataLoaderInstance;

    /**
     * 计算所有衍生属性。
     *
     * @param customerId 客户 UUID（注入 EvaluationContext，业务函数使用）
     * @param partNo     料号（路径查询过滤条件）
     * @param attrs      待计算的衍生属性列表（按 sortOrder 排序，由调用方保证）
     * @return {variableCode → 计算结果} Map；FormulaError 值表示计算失败（单元格级失败，不中断整体）
     */
    public Map<String, Object> calculate(UUID customerId, String partNo, List<DerivedAttribute> attrs) {
        if (attrs == null || attrs.isEmpty()) return Map.of();

        DataLoader loader = dataLoaderInstance.get();

        EvaluationContext ctx = EvaluationContext.builder()
                .customerId(customerId)
                .partNo(partNo)
                .dataLoader(loader)
                .build();

        Map<String, Object> results = new LinkedHashMap<>(attrs.size());

        for (DerivedAttribute attr : attrs) {
            if (attr == null || attr.variableCode == null) continue;
            if (!"ACTIVE".equals(attr.status)) {
                LOG.debugf("Skipping inactive derived attr: %s", attr.variableCode);
                continue;
            }

            String formula = extractFormula(attr);
            if (formula == null || formula.isBlank()) {
                results.put(attr.variableCode, new FormulaError(
                        "衍生属性 " + attr.variableCode + " 缺少公式定义", "MISSING_FORMULA"));
                continue;
            }

            try {
                Object result = formulaEngine.evaluate(formula, ctx);
                results.put(attr.variableCode, result);

                if (result instanceof FormulaError err) {
                    LOG.warnf("DerivedAttribute '%s' formula='%s' returned FormulaError: %s",
                              attr.variableCode, formula, err.getMessage());
                } else {
                    LOG.debugf("DerivedAttribute '%s' = %s", attr.variableCode, result);
                }
            } catch (Exception e) {
                LOG.warnf("DerivedAttribute '%s' formula evaluation threw: %s", attr.variableCode, e.getMessage());
                results.put(attr.variableCode, new FormulaError(
                        "计算异常：" + e.getMessage(), "RUNTIME_ERROR"));
            }
        }

        return results;
    }

    /**
     * 从 DerivedAttribute.computation JSONB 中提取公式字符串。
     *
     * <p>V44 schema 设计：computation JSONB 格式取决于 computationType：
     * <ul>
     *   <li>EXPRESSION: {"formula": "公式字符串"}</li>
     *   <li>LOOKUP/AGGREGATE: 其他结构（v5 公式引擎统一接管）</li>
     * </ul>
     *
     * <p>v5 简化处理：
     * <ul>
     *   <li>若 computation 直接是字符串公式（非 JSON），直接使用</li>
     *   <li>若 computation 是 JSON，尝试提取 "formula" 字段</li>
     * </ul>
     */
    private static String extractFormula(DerivedAttribute attr) {
        String computation = attr.computation;
        if (computation == null || computation.isBlank()) return null;

        String trimmed = computation.trim();

        // 若不是 JSON 对象，直接视为公式字符串
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }
        // EXPRESSION type: {"formula": "..."}
        if ("EXPRESSION".equals(attr.computationType) || attr.computationType == null) {
            // 简单字符串抽取（避免引入 Jackson 依赖）
            return extractJsonField(trimmed, "formula");
        }
        return null;
    }

    /**
     * 简单 JSON 字段提取（避免重量级 JSON 解析依赖）。
     * 格式限制：{"formula": "...公式字符串..."} 且公式内不含嵌套引号（或使用转义）。
     */
    static String extractJsonField(String json, String fieldName) {
        if (json == null) return null;
        String search = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return null;

        char startChar = json.charAt(valueStart);
        if (startChar == '"') {
            // 字符串值：找到结束引号（处理转义）
            StringBuilder sb = new StringBuilder();
            int i = valueStart + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
        return null;
    }
}
