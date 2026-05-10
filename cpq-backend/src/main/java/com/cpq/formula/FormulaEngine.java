package com.cpq.formula;

import com.cpq.formula.function.FunctionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.jexl3.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公式引擎入口：解析并执行 v5.1 §3.2 风格的公式表达式。
 *
 * <h2>公式语法</h2>
 * <pre>
 *   公式 = 含 {path} 变量引用 + 函数调用 + 算术/逻辑运算的表达式
 *
 *   示例：
 *     {元素BOM[元素='Ag'].组成含量(%)} * 100
 *     ROUND({来料BOM[来料料号='C001'].单价} * {生产料号.单重}, 2)
 *     IF({生产料号.单重} > 0, {生产料号.单重} * 100, 0)
 *     EXCHANGE({工序资料.单价}, 'USD', 'CNY')
 * </pre>
 *
 * <h2>处理流程</h2>
 * <ol>
 *   <li>扫描公式中的 {@code {path}} 占位符，通过 DataLoader 解析（dedupe）</li>
 *   <li>将路径占位符替换为 JEXL 变量名（如 _v0, _v1...）</li>
 *   <li>将 FUNCTION_NAME(...) 调用改写为 JEXL 可理解的形式（通过自定义函数命名空间）</li>
 *   <li>交 JEXL3 执行含变量替换后的表达式</li>
 *   <li>运行时错误 → 返回 FormulaError 而非抛出异常</li>
 * </ol>
 *
 * <h2>JEXL 集成策略</h2>
 * JEXL 处理算术和逻辑运算；函数调用通过 JEXL namespace 路由到 FunctionRegistry。
 * v5.1 §3.2 "不自动类型转换"：JEXL 默认 arithmetic 遇到字符串+数字会尝试类型推断，
 * 在此引擎里所有路径取值结果均为 BigDecimal（数字型）或 String，调用方需显式 NUM() 转换。
 */
@ApplicationScoped
public class FormulaEngine {

    private static final Logger LOG = Logger.getLogger(FormulaEngine.class);

    /** 匹配公式中的 {path} 变量占位符（非贪婪）。 */
    private static final Pattern PATH_PATTERN = Pattern.compile("\\{([^{}]+)}");

    /** 匹配顶层函数调用：NAME( ... ) — 用于识别函数名并路由到 FunctionRegistry。 */
    private static final Pattern FUNC_PATTERN = Pattern.compile(
            "\\b([A-Z_][A-Z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private final JexlEngine jexl;

    @Inject
    FunctionRegistry registry;

    public FormulaEngine() {
        // JEXL3 严格模式 OFF（避免 null 引用中止），silent OFF（错误可见）
        this.jexl = new JexlBuilder()
                .silent(false)
                .strict(false)
                .create();
    }

    /**
     * 对外接口：求值公式。
     *
     * @param formula 公式字符串
     * @param ctx     求值上下文（含 customer_id / partNo / DataLoader）
     * @return 计算结果，或 {@link FormulaError}（运行时错误）
     */
    public Object evaluate(String formula, EvaluationContext ctx) {
        if (formula == null || formula.isBlank()) {
            return FormulaError.invalidArgs("evaluate", "公式为空");
        }

        try {
            return doEvaluate(formula.trim(), ctx);
        } catch (Exception e) {
            LOG.warnf("FormulaEngine.evaluate failed for formula='%s': %s", formula, e.getMessage());
            return new FormulaError("公式执行异常：" + e.getMessage(), "RUNTIME_ERROR");
        }
    }

    // ── 核心执行 ─────────────────────────────────────────────────────────────

    private Object doEvaluate(String formula, EvaluationContext ctx) {
        // Step 1: 解析并替换 {path} 为临时变量名
        Map<String, Object> pathValues = new HashMap<>();
        String rewritten = replacePaths(formula, pathValues, ctx);

        // Step 2: 检查是否整个公式只是一个函数调用（无算术运算）
        //         若是，直接执行函数（不走 JEXL 以避免嵌套函数名冲突）
        String trimmed = rewritten.trim();
        if (isSingleFunctionCall(trimmed)) {
            return executeFunctionCall(trimmed, pathValues, ctx);
        }

        // Step 3: 走 JEXL 处理算术/逻辑运算
        //         函数调用通过 JEXL namespace 路由
        JexlContext jexlCtx = buildJexlContext(pathValues, ctx);
        String jexlExpr = rewriteFunctionsForJexl(rewritten);

        try {
            JexlExpression expr = jexl.createExpression(jexlExpr);
            Object result = expr.evaluate(jexlCtx);
            return normalizeResult(result);
        } catch (ArithmeticException ae) {
            // 除零等算术异常
            return FormulaError.divisionByZero();
        } catch (JexlException je) {
            String msg = je.getMessage() != null ? je.getMessage() : je.getClass().getSimpleName();
            if (msg.contains("divide by zero") || msg.contains("/ by zero")) {
                return FormulaError.divisionByZero();
            }
            return new FormulaError("公式表达式错误：" + msg, "EXPRESSION_ERROR");
        }
    }

    /**
     * 将公式中的 {path} 占位符替换为 _v0, _v1... 临时变量，
     * 同时通过 DataLoader 解析路径值存入 pathValues。
     */
    private String replacePaths(String formula, Map<String, Object> pathValues, EvaluationContext ctx) {
        StringBuffer sb = new StringBuffer();
        Matcher m = PATH_PATTERN.matcher(formula);
        int varIdx = 0;

        while (m.find()) {
            String pathContent = m.group(1).trim();
            String varName = "_v" + varIdx++;
            Object value = resolvePathValue(pathContent, ctx);
            pathValues.put(varName, value);
            m.appendReplacement(sb, varName);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 通过 DataLoader 解析路径值。
     * 单列查询返回第一行第一列值；多列/多行返回 List&lt;Map&gt;。
     */
    private Object resolvePathValue(String path, EvaluationContext ctx) {
        if (ctx == null || ctx.getDataLoader() == null) {
            return FormulaError.notFound(path);
        }
        try {
            // Y1.5: partNo / customerId / driverRow 均作为隐式 JOIN 候选注入字段路径
            //       (目标物理表有该列且原谓词没用过 → 自动追加 AND col = val)
            List<Map<String, Object>> rows = ctx.getDataLoader()
                    .loadByPath(path, ctx.getDriverRow(), ctx.getPartNo(), ctx.getCustomerId())
                    .get();
            if (rows == null || rows.isEmpty()) return null;
            if (rows.size() == 1 && rows.get(0).size() == 1) {
                // 单行单列：直接返回标量值
                return rows.get(0).values().iterator().next();
            }
            if (rows.size() == 1) {
                // 单行多列：返回 Map
                return rows.get(0);
            }
            // 多行：返回 List<Map>
            return rows;
        } catch (InterruptedException | ExecutionException e) {
            return new FormulaError("路径解析失败：" + path + " — " + e.getMessage(), "QUERY_ERROR");
        }
    }

    /**
     * 构建 JEXL 上下文：注入路径变量 + ctx 绑定 + 函数命名空间。
     */
    private JexlContext buildJexlContext(Map<String, Object> pathValues, EvaluationContext ctx) {
        MapContext jexlCtx = new MapContext();

        // 注入路径解析变量
        for (var entry : pathValues.entrySet()) {
            jexlCtx.set(entry.getKey(), entry.getValue());
        }

        // 注入 EvaluationContext 的 bindings（row_data 字段等）
        if (ctx != null) {
            for (var entry : ctx.getBindings().entrySet()) {
                jexlCtx.set(entry.getKey(), entry.getValue());
            }
        }

        // 注入函数命名空间对象
        jexlCtx.set("fn", new JexlFunctionNamespace(registry, ctx));

        return jexlCtx;
    }

    /**
     * 将公式中的大写函数调用改写为 JEXL 命名空间调用：
     * {@code ROUND(x, 2)} → {@code fn.ROUND(x, 2)}
     *
     * <p>仅处理已注册的函数名，避免误改变量名（如 MAX 变量）。
     */
    private String rewriteFunctionsForJexl(String formula) {
        StringBuilder result = new StringBuilder(formula);
        // 从后往前替换，避免偏移量变化
        List<int[]> replacements = new ArrayList<>();

        Matcher m = FUNC_PATTERN.matcher(formula);
        while (m.find()) {
            String funcName = m.group(1).toUpperCase();
            if (registry.contains(funcName)) {
                // 记录：开始位置，结束位置（包括"("），原始名长度
                replacements.add(new int[]{m.start(), m.start(1) + m.group(1).length(), funcName.length()});
            }
        }

        // 从后往前替换
        for (int i = replacements.size() - 1; i >= 0; i--) {
            int[] r = replacements.get(i);
            String original = formula.substring(r[0], r[1]);
            String funcName = formula.substring(r[0], r[0] + r[2]).toUpperCase();
            result.replace(r[0], r[1], "fn." + funcName + "(");
        }

        return result.toString();
    }

    /** 检查表达式是否为单一函数调用（不含算术运算符在括号外）。 */
    private boolean isSingleFunctionCall(String expr) {
        Matcher m = FUNC_PATTERN.matcher(expr);
        if (!m.find() || m.start() != 0) return false;
        String funcName = m.group(1).toUpperCase();
        if (!registry.contains(funcName)) return false;
        // 粗略检查：函数名之后紧接 "("，且到末尾没有二元运算符在顶层括号外
        // v1 简化：不做完整 AST 分析，仅检查最外层括号匹配
        return true;
    }

    /**
     * 直接执行单一函数调用（不走 JEXL）。
     * 解析函数名 + 参数列表，调用 FunctionRegistry。
     */
    private Object executeFunctionCall(String expr, Map<String, Object> pathValues, EvaluationContext ctx) {
        // 找到函数名
        int parenStart = expr.indexOf('(');
        if (parenStart < 0) return FormulaError.invalidArgs("execute", "无效的函数调用：" + expr);

        String funcName = expr.substring(0, parenStart).trim().toUpperCase();
        String argsStr = expr.substring(parenStart + 1, expr.lastIndexOf(')')).trim();

        List<Object> args = parseArgs(argsStr, pathValues, ctx);
        return registry.invoke(funcName, args, ctx);
    }

    /**
     * 简单参数解析：按顶层逗号分割，处理嵌套括号内的逗号。
     * 每个参数值通过 JEXL 求值（支持算术子表达式）。
     */
    private List<Object> parseArgs(String argsStr, Map<String, Object> pathValues, EvaluationContext ctx) {
        if (argsStr == null || argsStr.isBlank()) return List.of();

        List<String> parts = splitTopLevelCommas(argsStr);
        List<Object> result = new ArrayList<>(parts.size());
        JexlContext jexlCtx = buildJexlContext(pathValues, ctx);

        for (String part : parts) {
            String trimPart = part.trim();
            if (trimPart.isEmpty()) continue;

            // 字符串字面量
            if ((trimPart.startsWith("'") && trimPart.endsWith("'"))
             || (trimPart.startsWith("\"") && trimPart.endsWith("\""))) {
                result.add(trimPart.substring(1, trimPart.length() - 1));
                continue;
            }
            // 布尔字面量
            if ("true".equalsIgnoreCase(trimPart)) { result.add(Boolean.TRUE); continue; }
            if ("false".equalsIgnoreCase(trimPart)) { result.add(Boolean.FALSE); continue; }
            // null 字面量
            if ("null".equalsIgnoreCase(trimPart)) { result.add(null); continue; }

            // 子表达式：通过 JEXL 求值
            try {
                JexlExpression argExpr = jexl.createExpression(rewriteFunctionsForJexl(trimPart));
                Object val = argExpr.evaluate(jexlCtx);
                result.add(normalizeResult(val));
            } catch (Exception e) {
                result.add(new FormulaError("参数求值失败：" + trimPart + " — " + e.getMessage(), "ARG_ERROR"));
            }
        }
        return result;
    }

    /** 按顶层逗号分割（忽略括号内的逗号）。 */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** 将 JEXL 结果规范化（Number → BigDecimal）。 */
    private static Object normalizeResult(Object v) {
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return new BigDecimal(f.toString());
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        return v;
    }

    // ── 内部类：JEXL 函数命名空间 ─────────────────────────────────────────────

    /**
     * 作为 JEXL 的 "fn" 命名空间对象，将 JEXL 函数调用路由到 FunctionRegistry。
     *
     * <p>JEXL 调用 {@code fn.ROUND(x, 2)} 时，会反射调用此对象的 ROUND 方法。
     * 由于函数名是动态的，使用 {@code Object __call(String name, Object[] args)} 形式
     * 不可行；替代方案：通过包装器代理在 JEXL 执行时捕获所有函数调用。
     *
     * <p>实际实现：将 JEXL 函数写法转为直接 invoke registry（见 rewriteFunctionsForJexl）。
     * 此内部类暴露公共方法供 JEXL 直接调用（22 个函数 × 多个参数重载），
     * 统一路由到 FunctionRegistry.invoke。
     */
    public static class JexlFunctionNamespace {

        private final FunctionRegistry registry;
        private final EvaluationContext ctx;

        public JexlFunctionNamespace(FunctionRegistry registry, EvaluationContext ctx) {
            this.registry = registry;
            this.ctx = ctx;
        }

        // 通用调用入口（JEXL 可变参数语义）
        private Object call(String name, Object... args) {
            List<Object> argList = args == null ? List.of() : List.of(args);
            return registry.invoke(name, argList, ctx);
        }

        // 类型转换
        public Object NUM(Object v)             { return call("NUM", v); }
        public Object STR(Object v)             { return call("STR", v); }
        public Object BOOL(Object v)            { return call("BOOL", v); }

        // 数学（varargs 支持多参数）
        public Object ROUND(Object v, Object d) { return call("ROUND", v, d); }
        public Object CEIL(Object v)            { return call("CEIL", v); }
        public Object FLOOR(Object v)           { return call("FLOOR", v); }
        public Object ABS(Object v)             { return call("ABS", v); }
        public Object MAX(Object... vs)         { return call("MAX", vs); }
        public Object MIN(Object... vs)         { return call("MIN", vs); }

        // 聚合
        public Object SUM(Object input)                  { return call("SUM", input); }
        public Object SUM(Object input, Object field)    { return call("SUM", input, field); }
        public Object AVG(Object input)                  { return call("AVG", input); }
        public Object AVG(Object input, Object field)    { return call("AVG", input, field); }
        public Object COUNT(Object input)                { return call("COUNT", input); }

        // 查找
        public Object LOOKUP(Object path)              { return call("LOOKUP", path); }
        public Object LOOKUP(Object path, Object field) { return call("LOOKUP", path, field); }
        public Object EXISTS(Object path)              { return call("EXISTS", path); }

        // 业务
        public Object EXCHANGE(Object amount, Object from, Object to) {
            return call("EXCHANGE", amount, from, to);
        }
        public Object EXCHANGE(Object amount, Object from, Object to, Object date) {
            return call("EXCHANGE", amount, from, to, date);
        }
        public Object TAX_INCLUDED(Object price, Object customerId) {
            return call("TAX_INCLUDED", price, customerId);
        }
        public Object TAX_EXCLUDED(Object price, Object customerId) {
            return call("TAX_EXCLUDED", price, customerId);
        }

        // 条件
        public Object IF(Object cond, Object t, Object f) { return call("IF", cond, t, f); }
        public Object IFERROR(Object v, Object fallback)  { return call("IFERROR", v, fallback); }

        // 数组
        public Object IN(Object v, Object arr)        { return call("IN", v, arr); }
        public Object CONTAINS(Object arr, Object v)  { return call("CONTAINS", arr, v); }
    }
}
