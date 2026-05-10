package com.cpq.engine.formula;

import com.cpq.globalvariable.GlobalVariableService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.jexl3.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Blocking
public class FormulaCalculationService {

    private static final Logger LOG = Logger.getLogger(FormulaCalculationService.class);

    private final JexlEngine jexl = new JexlBuilder()
            .strict(false)
            .silent(true)
            .create();

    @Inject
    ObjectMapper objectMapper;

    /**
     * V104: Lazy-injected reference to GlobalVariableService.
     * 用 Instance 包装是为了避免 engine.formula 包硬依赖 globalvariable 包 (CDI 启动顺序无关)。
     * 公式 token 命中 global_variable 才解引用; 未引用全局变量的公式零额外开销。
     */
    @Inject
    Instance<GlobalVariableService> globalVariableServiceRef;

    /**
     * Calculate row formulas for a given component within a snapshot.
     *
     * @param componentsSnapshotJson JSON array of component definitions containing expression arrays
     * @param componentCode          the code of the component to calculate formulas for
     * @param rowData                field name -> value map for the current row
     * @param crossComponentSubtotals component_code -> subtotal map for cross-component references
     * @return map of formula field name -> calculated BigDecimal value
     */
    public Map<String, BigDecimal> calculateRowFormulas(
            String componentsSnapshotJson,
            String componentCode,
            Map<String, Object> rowData,
            Map<String, BigDecimal> crossComponentSubtotals) {

        Map<String, BigDecimal> results = new HashMap<>();

        if (componentsSnapshotJson == null || componentCode == null) {
            return results;
        }

        try {
            List<Map<String, Object>> components = objectMapper.readValue(
                    componentsSnapshotJson, new TypeReference<>() {});

            // Find the component with the matching code
            Map<String, Object> targetComponent = null;
            for (Map<String, Object> comp : components) {
                if (componentCode.equals(comp.get("code"))) {
                    targetComponent = comp;
                    break;
                }
            }

            if (targetComponent == null) {
                LOG.debug("Component not found: " + componentCode);
                return results;
            }

            // Look for columns with formula expressions
            Object columnsObj = targetComponent.get("columns");
            if (!(columnsObj instanceof List)) {
                return results;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) columnsObj;
            for (Map<String, Object> column : columns) {
                if (!"FORMULA".equals(column.get("field_type"))) {
                    continue;
                }
                String fieldName = (String) column.get("field_name");
                Object expressionObj = column.get("expression");
                if (fieldName == null || expressionObj == null) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) expressionObj;
                String jexlExpr = buildJexlExpression(tokens, rowData, crossComponentSubtotals, null);

                BigDecimal value = evaluateJexl(jexlExpr);
                results.put(fieldName, value);
            }
        } catch (Exception e) {
            LOG.error("Failed to calculate row formulas: " + e.getMessage(), e);
        }

        return results;
    }

    /**
     * Calculate product subtotal from a subtotal formula.
     *
     * @param subtotalFormulaJson   JSON array of expression tokens
     * @param componentSubtotals    component_code -> subtotal map
     * @param productAttributes     product attribute name -> value map
     * @return calculated subtotal
     */
    public BigDecimal calculateProductSubtotal(
            String subtotalFormulaJson,
            Map<String, BigDecimal> componentSubtotals,
            Map<String, Object> productAttributes) {

        if (subtotalFormulaJson == null || subtotalFormulaJson.isBlank()) {
            return BigDecimal.ZERO;
        }

        try {
            List<Map<String, Object>> tokens = objectMapper.readValue(
                    subtotalFormulaJson, new TypeReference<>() {});

            String jexlExpr = buildJexlExpression(tokens, null, componentSubtotals, productAttributes);
            return evaluateJexl(jexlExpr);
        } catch (Exception e) {
            LOG.error("Failed to calculate product subtotal: " + e.getMessage(), e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Validate frontend/backend calculation consistency.
     *
     * @return true if the absolute difference is within 0.01
     */
    public boolean validateConsistency(BigDecimal frontendValue, BigDecimal backendValue) {
        if (frontendValue == null || backendValue == null) {
            return frontendValue == null && backendValue == null;
        }
        return frontendValue.subtract(backendValue).abs()
                .compareTo(new BigDecimal("0.01")) <= 0;
    }

    String buildJexlExpression(
            List<Map<String, Object>> tokens,
            Map<String, Object> rowData,
            Map<String, BigDecimal> componentSubtotals,
            Map<String, Object> productAttributes) {

        StringBuilder expr = new StringBuilder();
        for (Map<String, Object> token : tokens) {
            String type = (String) token.get("type");
            String value = token.get("value") != null ? token.get("value").toString() : null;

            switch (type) {
                case "field":
                    Object fieldVal = rowData != null && value != null ? rowData.get(value) : null;
                    expr.append(toNumericString(fieldVal));
                    break;
                case "operator":
                    String op = value;
                    if ("\u00d7".equals(op)) op = "*";
                    else if ("\u00f7".equals(op)) op = "/";
                    expr.append(op);
                    break;
                case "bracket_open":
                    expr.append("(");
                    break;
                case "bracket_close":
                    expr.append(")");
                    break;
                case "number":
                    expr.append(value);
                    break;
                case "component_subtotal":
                    String compCode = (String) token.get("component_code");
                    BigDecimal subtotal = componentSubtotals != null && compCode != null
                            ? componentSubtotals.getOrDefault(compCode, BigDecimal.ZERO) : BigDecimal.ZERO;
                    expr.append(subtotal.toPlainString());
                    break;
                case "product_attribute":
                    String attrName = (String) token.get("attribute_name");
                    Object attrVal = productAttributes != null && attrName != null
                            ? productAttributes.get(attrName) : null;
                    expr.append(toNumericString(attrVal));
                    break;
                case "global_variable":
                    // V104: 注册表查 def → 解 key → 取值. globalVariableService 由 CDI 注入,
                    // 通过 @Inject Instance 懒求 (避免循环依赖, 公式包不强依赖 globalvariable 包)
                    BigDecimal gvVal = resolveGlobalVariable(token, rowData);
                    expr.append(gvVal != null ? gvVal.toPlainString() : "0");
                    break;
                default:
                    LOG.warn("Unknown token type: " + type);
                    break;
            }
        }
        return expr.toString();
    }

    private BigDecimal evaluateJexl(String expression) {
        try {
            JexlExpression jexlExpr = jexl.createExpression(expression);
            JexlContext context = new MapContext();
            Object result = jexlExpr.evaluate(context);
            if (result instanceof Number) {
                return new BigDecimal(result.toString()).setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            LOG.warn("JEXL evaluation failed for expression '" + expression + "': " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private String toNumericString(Object val) {
        if (val == null) return "0";
        if (val instanceof Number) return val.toString();
        try {
            return new BigDecimal(val.toString()).toPlainString();
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /**
     * V104: 解 global_variable token. token 形态:
     *   {type:'global_variable', code:'ELEM_PRICE',
     *    key_values:{element_code:'Cu'}}                       — 静态 key
     *   {type:'global_variable', code:'ELEM_PRICE',
     *    key_field_refs:{element_code:'电镀元素'}}              — 动态 key (按 rowData 取)
     *
     * 解析失败一律返回 null, 调用方按 0 兜底, 跟其他 token 行为对齐。
     */
    private BigDecimal resolveGlobalVariable(Map<String, Object> token, Map<String, Object> rowData) {
        try {
            String code = (String) token.get("code");
            if (code == null || code.isBlank()) return null;
            GlobalVariableService svc = globalVariableServiceRef.isResolvable()
                    ? globalVariableServiceRef.get() : null;
            if (svc == null) return null;
            var def = svc.getByCode(code).orElse(null);
            if (def == null) return null;

            @SuppressWarnings("unchecked")
            Map<String, Object> staticKeys = (Map<String, Object>) token.get("key_values");
            @SuppressWarnings("unchecked")
            Map<String, Object> dynRefs    = (Map<String, Object>) token.get("key_field_refs");

            Map<String, Object> resolved = new HashMap<>();
            for (String col : def.keyColumns) {
                Object v = null;
                if (staticKeys != null && staticKeys.containsKey(col)) {
                    v = staticKeys.get(col);
                } else if (dynRefs != null && dynRefs.containsKey(col) && rowData != null) {
                    v = rowData.get(dynRefs.get(col));
                }
                if (v == null) return null;
                resolved.put(col, v);
            }
            return svc.resolveValue(code, resolved);
        } catch (Exception e) {
            LOG.warnf("global_variable resolve failed: %s", e.getMessage());
            return null;
        }
    }
}
