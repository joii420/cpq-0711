package com.cpq.component.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * task-0729 B7 · 元素列绑定推导（§11.15.3.4 五步算法，被替代原方案降级为迁移期预填 +
 * 保存期漏配检测的辅助手段——🔒 运行期禁止用它反推 SQL，只用于：
 * <ol>
 *   <li>{@code GET /components/{id}/element-binding-suggest} 的推荐值；</li>
 *   <li>迁移期批量预填（V370 手工回填已用同一套人工核对值，本类是它的可复算版本）。</li>
 * </ol>
 *
 * <p>五步（逐字对应真实模板验证过，见 COMP-0027「材料成本」/ COMP-0049「物料与元素BOM」两条
 * 真实 sql_template）：
 * <pre>
 * ① 正则捕获取价函数（f_customer_element_price / f_material_element_price）后面的标识符 → 别名
 * ② &lt;别名&gt;.unit_price AS &lt;列名&gt; → 价格列名；同法 &lt;别名&gt;.currency AS &lt;列名&gt; → 货币列名
 * ③ ON &lt;别名&gt;.element_code = &lt;表达式&gt; → 捕获该表达式（如 ebi.component_no）
 * ④ &lt;表达式&gt; AS &lt;列名2&gt; → 元素编码列名（逐字匹配③捕获的表达式，非启发式猜测）
 * ⑤ 在 fields[] 里找 default_source.path == "$&lt;viewName&gt;.&lt;列名&gt;" 或
 *    basic_data_path == "$&lt;viewName&gt;.&lt;列名&gt;"（BASIC_DATA 类型字段两种存法并存，
 *    COMP-0049「元素代码」字段即后者反例）→ 该字段 name 即为角色字段值
 * </pre>
 *
 * <p>禁止硬编码别名 {@code cep}——虽然规则文档约定"别名=字段名逐字不加下划线"，仍须①动态捕获
 * （需求说明 §11.15.3 三条实现纪律之一）。
 */
public final class ElementBindingDerivation {

    private static final Pattern FUNCTION_ALIAS = Pattern.compile(
        "f_(?:customer|material)_element_price\\s*\\([^)]*\\)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private ElementBindingDerivation() {}

    /**
     * 保存期校验用轻量探测（§11.15.3.2）：该 sqlTemplate 是否引用了取价函数。
     * 不需要跑完整五步推导——只用来决定"要不要拦保存"。
     */
    public static boolean referencesElementPriceFunction(String sqlTemplate) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) return false;
        return FUNCTION_ALIAS.matcher(sqlTemplate).find();
    }

    public static final class Result {
        public String elementCodeField;
        public String elementPriceField;
        public String elementCurrencyField;
        public String alias;
        /** HIGH=价格列+元素编码列均定位到唯一字段；LOW=部分步骤命中歧义/缺失，需人工确认。 */
        public String confidence = "LOW";
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * @param sqlTemplate  该组件某个 {@code component_sql_view.sql_template}（调用方按 componentId
     *                     逐个视图尝试，第一个命中取价函数的视图即为推导对象）
     * @param sqlViewName  该 sqlTemplate 对应的 {@code sql_view_name}（用于拼 {@code $<viewName>.<列名>}）
     * @param fields       该组件 {@code fields} 数组（JsonNode，已解析）
     */
    public static Result derive(String sqlTemplate, String sqlViewName, JsonNode fields) {
        Result r = new Result();
        if (sqlTemplate == null || sqlTemplate.isBlank() || sqlViewName == null || sqlViewName.isBlank()) {
            return r;
        }

        // ① 别名
        Matcher aliasM = FUNCTION_ALIAS.matcher(sqlTemplate);
        if (!aliasM.find()) {
            return r; // 该视图未接取价函数
        }
        String alias = aliasM.group(1);
        r.alias = alias;

        // ② 价格列名 / 货币列名
        String priceCol = findAliasedOutputColumn(sqlTemplate, alias, "unit_price");
        String currencyCol = findAliasedOutputColumn(sqlTemplate, alias, "currency");

        // ③ ON <别名>.element_code = <表达式>
        String elementCodeExpr = findElementCodeExpression(sqlTemplate, alias);

        // ④ <表达式> AS <列名2>（逐字匹配③捕获的表达式）
        String elementCodeCol = elementCodeExpr != null
            ? findExpressionAliasedAs(sqlTemplate, elementCodeExpr) : null;

        // ⑤ fields[] 里按 $<viewName>.<列名> 匹配 default_source.path / basic_data_path
        r.elementPriceField = priceCol != null ? findFieldByViewColumn(fields, sqlViewName, priceCol) : null;
        r.elementCurrencyField = currencyCol != null ? findFieldByViewColumn(fields, sqlViewName, currencyCol) : null;
        r.elementCodeField = elementCodeCol != null ? findFieldByViewColumn(fields, sqlViewName, elementCodeCol) : null;

        if (priceCol == null) r.warnings.add("未在 sqlTemplate 中找到 " + alias + ".unit_price AS <列名>");
        else if (r.elementPriceField == null) r.warnings.add("价格列 " + priceCol + " 未被任何字段的 default_source 引用");
        if (elementCodeExpr == null) r.warnings.add("未在 sqlTemplate 中找到 ON " + alias + ".element_code = <表达式>");
        else if (elementCodeCol == null) r.warnings.add("表达式 " + elementCodeExpr + " 未作为输出列出现（<表达式> AS <列名>）");
        else if (r.elementCodeField == null) r.warnings.add("元素编码列 " + elementCodeCol + " 未被任何字段引用");

        r.confidence = (r.elementCodeField != null && r.elementPriceField != null && r.warnings.isEmpty())
            ? "HIGH" : "LOW";
        return r;
    }

    /** 找 `<alias>.<sourceCol> AS <outputCol>`（大小写不敏感 AS，输出列名允许中文/下划线，遇空白或逗号结束）。 */
    private static String findAliasedOutputColumn(String sql, String alias, String sourceCol) {
        Pattern p = Pattern.compile(
            Pattern.quote(alias) + "\\." + Pattern.quote(sourceCol) + "\\s+(?:[Aa][Ss]\\s+)?([^\\s,]+)");
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /** 找 `ON ... <alias>.element_code = <表达式>`（表达式截止到空白/AND/换行）。 */
    private static String findElementCodeExpression(String sql, String alias) {
        Pattern p = Pattern.compile(
            Pattern.quote(alias) + "\\.element_code\\s*=\\s*([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /** 找 `<表达式> AS <列名>`（表达式需逐字匹配，转义正则特殊字符）。 */
    private static String findExpressionAliasedAs(String sql, String expr) {
        Pattern p = Pattern.compile(Pattern.quote(expr) + "\\s+(?:[Aa][Ss]\\s+)?([^\\s,]+)");
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String candidate = m.group(1);
            // 跳过表达式匹配到它自身出现在 ON 子句里的情形（如 ebi.component_no = cep.element_code）
            if (!candidate.equalsIgnoreCase("cep") && !candidate.contains("element_code")) {
                return candidate;
            }
        }
        return null;
    }

    /** 在 fields[] 里找 default_source.path 或 basic_data_path == "$<viewName>.<col>" 的字段，返回其 name。 */
    private static String findFieldByViewColumn(JsonNode fields, String viewName, String col) {
        if (fields == null || !fields.isArray()) return null;
        String target = "$" + viewName + "." + col;
        for (JsonNode f : fields) {
            String defaultSourcePath = f.path("default_source").path("path").asText(null);
            String basicDataPath = f.path("basic_data_path").asText(null);
            if (target.equals(defaultSourcePath) || target.equals(basicDataPath)) {
                return f.path("name").asText(null);
            }
        }
        return null;
    }
}
