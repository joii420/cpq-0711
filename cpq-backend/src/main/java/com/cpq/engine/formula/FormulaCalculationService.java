package com.cpq.engine.formula;

import com.cpq.common.DecimalJexl;
import com.cpq.common.PrecisionPolicy;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Blocking
public class FormulaCalculationService {

    private static final Logger LOG = Logger.getLogger(FormulaCalculationService.class);

    // task-0801 B3（求值点 #1）：DecimalJexl.newEngine() 统一配 BigDecimal 算术
    // （MathContext + DIVISION_SCALE），并配合 buildJexlExpression 数字字面量 "B" 后缀
    // （见 toNumericString / component_subtotal / global_variable / number 各分支）修复 R-3。
    private final JexlEngine jexl = DecimalJexl.newEngine();

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
                    // task-0801 B3：数字字面量追加 "B" 后缀（JEXL BigDecimal 字面量语法），
                    // 否则仍按 Double 解析，与已加 B 后缀的其它 token 混算时精度不对齐（R-3）。
                    expr.append(value).append('B');
                    break;
                case "component_subtotal":
                    String compCode = (String) token.get("component_code");
                    BigDecimal subtotal = componentSubtotals != null && compCode != null
                            ? componentSubtotals.getOrDefault(compCode, BigDecimal.ZERO) : BigDecimal.ZERO;
                    expr.append(subtotal.toPlainString()).append('B');
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
                    expr.append(gvVal != null ? gvVal.toPlainString() : "0").append('B');
                    break;
                case "datasource_field":
                    // K1: 引用同行 DATA_SOURCE 字段的解析结果. token.name = 字段名,
                    // 求值期 rowData[name] 应已含 DATA_SOURCE 解析后的值 (前端 ProductCard
                    // 在公式求值前先解 DATA_SOURCE 写入 row, 与 INPUT_NUMBER/FIXED_VALUE 同处理).
                    String dsName = (String) token.get("name");
                    Object dsVal = (dsName != null && rowData != null) ? rowData.get(dsName) : null;
                    expr.append(toNumericString(dsVal));
                    break;
                case "tree_ref":
                case "tree_attr":
                    // task-0803：BOM 父子取值（tree_ref/tree_attr）。这条旧版求值路径
                    // （calculateRowFormulas / calculateProductSubtotal）不构建树上下文
                    // （无 TreeEvalContext，rowData 只是单行字段值），无法解析父/子行关系。
                    // 与 FormulaCalculator.evalTreeRef/evalTreeAttr 在"拿不到树上下文"时的
                    // 兜底口径对齐：一律按 0 处理，绝不能落进 default 分支被静默忽略/拼出
                    // 语法不完整的表达式（那样只会更难排查）。
                    expr.append("0B");
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
            // task-0801 B3 Step3：引擎已配 BigDecimal 算术，result 通常直接是 BigDecimal；
            // 优先直接强转，避免多一次字符串往返；不再 setScale(4) 截断（呈现边界由 B5 统一规整）。
            if (result instanceof BigDecimal bd) {
                return bd;
            }
            if (result instanceof Number) {
                return PrecisionPolicy.of(result);
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            LOG.warn("JEXL evaluation failed for expression '" + expression + "': " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private String toNumericString(Object val) {
        // task-0801 B3：数字字面量追加 "B" 后缀（JEXL BigDecimal 字面量语法，见 DecimalJexl），
        // 否则仍按 Double 解析（R-3）。
        if (val == null) return "0B";
        if (val instanceof Number) return val.toString() + "B";
        try {
            return new BigDecimal(val.toString()).toPlainString() + "B";
        } catch (NumberFormatException e) {
            return "0B";
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
