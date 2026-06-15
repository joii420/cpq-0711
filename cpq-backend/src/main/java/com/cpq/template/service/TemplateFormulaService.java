package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.cpq.template.dto.FormulaCompletionDTO;
import com.cpq.template.dto.FormulaErrorDTO;
import com.cpq.template.dto.FormulaSuggestionDTO;
import com.cpq.template.dto.TemplateFormulaDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import javax.sql.DataSource;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V145 Stage 1 + V146 Stage 2 — 模板公式服务.
 *
 * <h2>Stage 2 新增能力</h2>
 * <ul>
 *   <li>聚合函数: SUM_OVER / COUNT_OVER / AVG_OVER / MIN_OVER / MAX_OVER</li>
 *   <li>行内标量函数: IF / COALESCE / NULLIF / ABS</li>
 *   <li>@变量 真实解析: 接入 GlobalVariableService.resolveValue()</li>
 *   <li>[col_key] fallback: 当引用不是模板公式时，从 excel_view_config VARIABLE 列路径取值</li>
 * </ul>
 *
 * <h2>SUM_OVER 语法</h2>
 * <pre>
 *   SUM_OVER([组件code 或 来料BOM] WHERE input_qty > 0,
 *            ABS(input_qty)/NULLIF(output_qty,0)*(1+loss_rate/100)*unit_price)
 *
 *   parse 阶段：
 *     1. [xxx] → 解析为组件 code → data_driver_path = v_c_xxx_merged
 *     2. 若找不到组件，直接把 [xxx] 视为视图名（向后兼容）
 *     3. 用 DataLoader + partNo/customerId 展开 N 行 driver rows
 *     4. 对每行用行内 JEXL 求值 expression（行字段当变量）
 *     5. 应用 WHERE 谓词过滤（同样 JEXL 求值）
 *     6. SUM / COUNT / AVG / MIN / MAX
 * </pre>
 */
@ApplicationScoped
public class TemplateFormulaService {

    private static final Logger LOG = Logger.getLogger(TemplateFormulaService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 公式名 / col_key 引用：[名称] 或 [组件code.字段]（含点号视为组件字段，否则视为公式名） */
    static final Pattern BRACKET_REF_PATTERN = Pattern.compile("\\[([^\\[\\]]+)]");

    /** 全局变量引用：@名称（中文 / 英文 / 下划线） */
    static final Pattern GLOBAL_VAR_PATTERN = Pattern.compile("@([\\p{L}A-Za-z_][\\p{L}\\w]*)");

    /**
     * Stage 2 已解锁的聚合函数（从 validateSingle 禁用列表移除）.
     * Stage 1 禁用列表已清空为空集 — 聚合函数不再拦截。
     */
    static final Set<String> STAGE2_AGGREGATE_FUNCS =
            Set.of("SUM_OVER", "COUNT_OVER", "AVG_OVER", "MIN_OVER", "MAX_OVER",
                   "SUMIF", "COUNTIF", "AVGIF", "MINIF", "MAXIF",
                   "FILTER", "MAP", "GROUP_BY", "REDUCE");

    /** Stage 1 遗留：仍拒绝的纯占位函数（Stage 2 不实现） */
    static final Set<String> STILL_UNSUPPORTED_FUNCS = Set.of("GROUP_BY", "REDUCE");

    /** 表达式长度上限 */
    static final int MAX_EXPRESSION_LENGTH = 5000;

    /** SUM_OVER(...) 整体 pattern: SUM_OVER([source] WHERE predicate, expression)
     *  SUMIF(...) pattern: SUMIF(cond, valueExpr) 或 COUNTIF(cond) */
    private static final Pattern OVER_FUNC_PATTERN = Pattern.compile(
            "\\b(SUM_OVER|COUNT_OVER|AVG_OVER|MIN_OVER|MAX_OVER|SUMIF|COUNTIF|AVGIF|MINIF|MAXIF)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * JEXL 引擎（行内表达式求值，非严格模式避免 null 崩溃）.
     *
     * JEXL 3.3 默认启用沙箱权限，禁止调用非白名单类的方法。
     * 由于 RowFunctions 是内部控制类，使用 JexlPermissions.UNRESTRICTED 放开限制，
     * 允许调用 _fn.ABS / _fn.NULLIF / _fn.COALESCE / _fn.IF 等方法。
     */
    private final JexlEngine rowJexl = new JexlBuilder()
            .silent(true)
            .strict(false)
            .permissions(JexlPermissions.UNRESTRICTED)
            .create();

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    @Inject
    GlobalVariableService globalVariableService;

    @Inject
    DataSource dataSource;

    /** Task 3.1: 列定义统一从 EXCEL 组件解析（[col_key] fallback 用）。 */
    @Inject
    com.cpq.quotation.service.ExcelColumnResolver excelColumnResolver;

    // ─────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────

    /** 列出某模板的全部公式 */
    public List<TemplateFormulaDTO> listByTemplate(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) throw new BusinessException(404, "Template not found: " + templateId);
        return parseFormulas(t.formulas);
    }

    /** 找到名为 name 的公式 */
    public TemplateFormulaDTO findByName(UUID templateId, String name) {
        return listByTemplate(templateId).stream()
                .filter(f -> name.equals(f.name))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "Formula not found: " + name));
    }

    /** 新增公式（仅 DRAFT 状态） */
    @Transactional
    public TemplateFormulaDTO addFormula(UUID templateId, TemplateFormulaDTO dto) {
        Template t = mustDraft(templateId);
        List<TemplateFormulaDTO> formulas = parseFormulas(t.formulas);
        if (formulas.stream().anyMatch(f -> f.name.equals(dto.name))) {
            throw new BusinessException(400, "公式名重复: " + dto.name);
        }

        validateSingle(dto);
        dto.dependsOn = detectDependencies(dto.expression);
        formulas.add(dto);
        validateGraph(formulas);

        t.formulas = serialize(formulas);
        LOG.infof("Added formula '%s' to template %s (deps=%s)", dto.name, templateId, dto.dependsOn);
        return dto;
    }

    /** 更新公式 */
    @Transactional
    public TemplateFormulaDTO updateFormula(UUID templateId, String name, TemplateFormulaDTO dto) {
        Template t = mustDraft(templateId);
        List<TemplateFormulaDTO> formulas = parseFormulas(t.formulas);
        int idx = -1;
        for (int i = 0; i < formulas.size(); i++) {
            if (name.equals(formulas.get(i).name)) { idx = i; break; }
        }
        if (idx < 0) throw new BusinessException(404, "Formula not found: " + name);
        if (dto.name != null && !dto.name.equals(name)) {
            for (int i = 0; i < formulas.size(); i++) {
                if (i != idx && dto.name.equals(formulas.get(i).name)) {
                    throw new BusinessException(400, "公式名重复: " + dto.name);
                }
            }
        }
        validateSingle(dto);
        dto.dependsOn = detectDependencies(dto.expression);
        formulas.set(idx, dto);
        validateGraph(formulas);

        t.formulas = serialize(formulas);
        LOG.infof("Updated formula '%s' on template %s", name, templateId);
        return dto;
    }

    /** 删除公式（拒绝删除被其他公式依赖的） */
    @Transactional
    public void deleteFormula(UUID templateId, String name) {
        Template t = mustDraft(templateId);
        List<TemplateFormulaDTO> formulas = parseFormulas(t.formulas);
        TemplateFormulaDTO target = formulas.stream()
                .filter(f -> name.equals(f.name)).findFirst()
                .orElseThrow(() -> new BusinessException(404, "Formula not found: " + name));
        for (TemplateFormulaDTO f : formulas) {
            if (!f.name.equals(name) && f.dependsOn != null && f.dependsOn.contains(name)) {
                throw new BusinessException(400, "无法删除: 公式 '" + f.name + "' 仍依赖 '" + name + "'");
            }
        }
        formulas.remove(target);
        t.formulas = serialize(formulas);
        LOG.infof("Deleted formula '%s' from template %s", name, templateId);
    }

    // ─────────────────────────────────────────────────────────────────
    // 校验
    // ─────────────────────────────────────────────────────────────────

    public ValidationResult validateFormula(UUID templateId, TemplateFormulaDTO dto) {
        ValidationResult result = new ValidationResult();
        try {
            validateSingle(dto);
            List<TemplateFormulaDTO> existing = listByTemplate(templateId);
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).name.equals(dto.name)) {
                    existing.set(i, dto);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) existing.add(dto);
            dto.dependsOn = detectDependencies(dto.expression);
            validateGraph(existing);
            result.valid = true;
            result.dependsOn = dto.dependsOn;
        } catch (BusinessException be) {
            result.valid = false;
            result.error = be.getMessage();
            // P0: 结构化错误，把 BusinessException 转为 FormulaErrorDTO
            result.errors = List.of(translateBusinessException(be, dto != null ? dto.expression : null));
        } catch (JexlException je) {
            result.valid = false;
            FormulaErrorDTO err = translateJexlError(je, dto != null ? dto.expression : null);
            result.error = err.message;
            result.errors = List.of(err);
        } catch (Exception e) {
            result.valid = false;
            result.error = "公式校验时发生意外错误: " + e.getMessage();
            result.errors = List.of(new FormulaErrorDTO("RUNTIME_ERROR", result.error));
        }
        return result;
    }

    /**
     * 单条公式校验：表达式非空、长度上限、仍不支持的占位函数、JSON 字段填齐
     *
     * Stage 2：SUM_OVER / COUNT_OVER / AVG_OVER / MIN_OVER / MAX_OVER 已解锁，不再拦截。
     * 仍拒绝：GROUP_BY / REDUCE（未实现）。
     */
    void validateSingle(TemplateFormulaDTO dto) {
        if (dto == null) throw new BusinessException(400, "公式不能为空");
        if (dto.name == null || dto.name.isBlank()) throw new BusinessException(400, "name 必填");
        if (dto.expression == null || dto.expression.isBlank())
            throw new BusinessException(400, "expression 必填");
        if (dto.expression.length() > MAX_EXPRESSION_LENGTH)
            throw new BusinessException(400, "expression 长度超过上限 " + MAX_EXPRESSION_LENGTH);
        String upper = dto.expression.toUpperCase();
        for (String fn : STILL_UNSUPPORTED_FUNCS) {
            if (Pattern.compile("\\b" + Pattern.quote(fn) + "\\s*\\(").matcher(upper).find()) {
                throw new BusinessException(400,
                        "暂不支持函数 " + fn + "(...)，预计 Stage 4 实现");
            }
        }
        if (dto.dataType == null || dto.dataType.isBlank()) {
            dto.dataType = "DECIMAL(18,4)";
        }
    }

    /** 整模板公式图校验：依赖完整性 + 循环依赖 + 拓扑可排序 */
    void validateGraph(List<TemplateFormulaDTO> formulas) {
        Map<String, TemplateFormulaDTO> byName = new LinkedHashMap<>();
        for (TemplateFormulaDTO f : formulas) byName.put(f.name, f);

        for (TemplateFormulaDTO f : formulas) {
            List<String> deps = detectDependencies(f.expression);
            for (String d : deps) {
                if (d.startsWith("@")) continue;
                if (d.contains(".")) continue;
                if (byName.containsKey(d)) continue;
                LOG.debugf("Formula '%s' depends on '%s' (not a template formula; will fallback to col_key)",
                        f.name, d);
            }
        }

        Map<String, Integer> color = new HashMap<>();
        for (String name : byName.keySet()) color.put(name, 0);
        List<String> sorted = new ArrayList<>();
        for (String name : byName.keySet()) {
            if (color.get(name) == 0) {
                if (!dfsTopological(name, byName, color, sorted)) {
                    throw new BusinessException(400, "检测到循环依赖，请检查公式 '" + name + "' 的引用链");
                }
            }
        }
    }

    /** DFS 拓扑排序；返回 false 表示发现回边（循环） */
    private boolean dfsTopological(String name,
                                   Map<String, TemplateFormulaDTO> byName,
                                   Map<String, Integer> color,
                                   List<String> sorted) {
        color.put(name, 1);
        TemplateFormulaDTO f = byName.get(name);
        if (f != null) {
            List<String> deps = f.dependsOn != null ? f.dependsOn : detectDependencies(f.expression);
            for (String d : deps) {
                if (!byName.containsKey(d)) continue;
                Integer c = color.get(d);
                if (c == null) continue;
                if (c == 1) return false;
                if (c == 0 && !dfsTopological(d, byName, color, sorted)) return false;
            }
        }
        color.put(name, 2);
        sorted.add(name);
        return true;
    }

    /** 拓扑排序公开接口（外部工具/前端预览用） */
    public List<String> topologicalSort(List<TemplateFormulaDTO> formulas) {
        Map<String, TemplateFormulaDTO> byName = new LinkedHashMap<>();
        for (TemplateFormulaDTO f : formulas) byName.put(f.name, f);
        Map<String, Integer> color = new HashMap<>();
        for (String n : byName.keySet()) color.put(n, 0);
        List<String> sorted = new ArrayList<>();
        for (String n : byName.keySet()) {
            if (color.get(n) == 0 && !dfsTopological(n, byName, color, sorted)) {
                throw new BusinessException(400, "检测到循环依赖: " + n);
            }
        }
        return sorted;
    }

    /** 是否存在循环依赖（不抛错版本） */
    public boolean hasCircularDependency(List<TemplateFormulaDTO> formulas) {
        try { topologicalSort(formulas); return false; }
        catch (BusinessException e) { return true; }
    }

    // ─────────────────────────────────────────────────────────────────
    // 求值（Stage 2：聚合 + @变量 + [col_key] fallback）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 求值模板公式。
     *
     * Stage 4: 加 @Transactional(readOnly=true) — resolveDriverPath 内的 Component.list() (Panache)
     * 需要 Hibernate Session。不加注解时在 evaluate 端点的 worker thread 上下文中无法获得 Session，
     * Component.list 抛出异常被 catch 兜底，fallback 到 source 本身作为路径（含连字符无法解析），
     * DataLoader 查询失败，SUM_OVER 返回 0。
     *
     * @param templateId  模板 ID
     * @param formulaName 要求值的公式名
     * @param customerId  客户 ID（可空）
     * @param partNo      料号（必填，否则聚合函数无法过滤行）
     * @param trace       是否返回中间步骤 trace
     * @return 求值结果（BigDecimal / String / FormulaError）；trace=true 时返回 Map{value, trace}
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public Object evaluateFormula(UUID templateId, String formulaName,
                                  UUID customerId, String partNo, boolean trace) {
        List<TemplateFormulaDTO> all = listByTemplate(templateId);
        Map<String, TemplateFormulaDTO> byName = new LinkedHashMap<>();
        for (TemplateFormulaDTO f : all) byName.put(f.name, f);
        if (!byName.containsKey(formulaName)) {
            throw new BusinessException(404, "Formula not found: " + formulaName);
        }

        // 拿有效列定义（Task 3.1: 从 EXCEL 组件解析）供 col_key fallback
        Template t = Template.findById(templateId);
        List<Map<String, Object>> viewCols = excelColumnResolver.getEffectiveColumns(t);

        Set<String> needed = new LinkedHashSet<>();
        collectDependencies(formulaName, byName, needed, new HashSet<>());
        List<String> orderedAll = topologicalSort(all);
        List<String> ordered = new ArrayList<>();
        for (String n : orderedAll) if (needed.contains(n)) ordered.add(n);

        Map<String, Object> cached = new LinkedHashMap<>();
        for (String n : ordered) {
            TemplateFormulaDTO f = byName.get(n);
            Object v = evaluateExpression(f.expression, byName, cached, customerId, partNo, viewCols);
            cached.put(n, v);
            LOG.debugf("[evaluateFormula] template=%s name='%s' → %s", templateId, n, v);
        }

        Object finalValue = cached.get(formulaName);
        if (!trace) return finalValue;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", finalValue);
        result.put("trace", cached);
        return result;
    }

    /** Backward-compatible overload (no trace, no viewCols) */
    @Transactional(Transactional.TxType.REQUIRED)
    public Object evaluateFormula(UUID templateId, String formulaName, UUID customerId, String partNo) {
        return evaluateFormula(templateId, formulaName, customerId, partNo, false);
    }

    /**
     * Stage4 Debug 端点: 直接测试 SUM_OVER 逻辑，返回内部步骤。
     * 仅供调试使用，不依赖模板。
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public Map<String, Object> debugSumOver(String expression, String partNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expression", expression);
        result.put("partNo", partNo);
        if (expression == null || !expression.toUpperCase().contains("SUM_OVER")) {
            result.put("error", "expression must contain SUM_OVER");
            return result;
        }
        // Step 1: parse SUM_OVER args
        Matcher m = OVER_FUNC_PATTERN.matcher(expression);
        if (!m.find()) {
            result.put("error", "OVER_FUNC_PATTERN did not match");
            return result;
        }
        int openParen = m.end() - 1;
        int closeParen = findMatchingParen(expression, openParen);
        result.put("openParen", openParen);
        result.put("closeParen", closeParen);
        if (closeParen < 0) {
            result.put("error", "unmatched parentheses");
            return result;
        }
        String argsContent = expression.substring(openParen + 1, closeParen);
        result.put("argsContent", argsContent);

        // Step 2: parse args
        OverFuncArgs parsed = parseOverFuncArgs(argsContent);
        if (parsed == null) {
            result.put("error", "parseOverFuncArgs returned null");
            return result;
        }
        result.put("source", parsed.source);
        result.put("predicate", parsed.predicate);
        result.put("rowExpression", parsed.expression);

        // Step 3: resolveDriverPath
        String driverPath;
        try {
            driverPath = resolveDriverPath(parsed.source);
        } catch (Exception e) {
            result.put("resolveDriverPathError", e.getMessage());
            return result;
        }
        result.put("driverPath", driverPath);
        if (driverPath == null || driverPath.isBlank()) {
            result.put("error", "driverPath is blank");
            return result;
        }

        // Step 4: loadByPath
        List<Map<String, Object>> rows;
        try {
            rows = dataLoader.loadByPath(driverPath, null, partNo, null).get();
        } catch (Exception e) {
            result.put("loadByPathError", e.getClass().getName() + ": " + e.getMessage());
            if (e.getCause() != null) result.put("loadByPathCause", e.getCause().getMessage());
            return result;
        }
        result.put("rowCount", rows == null ? 0 : rows.size());
        if (rows == null || rows.isEmpty()) {
            result.put("warning", "no rows loaded");
            return result;
        }
        // Show first row
        result.put("firstRow", rows.get(0));

        // Step 5: eval predicates + row expressions
        // Also show rewritten JEXL expression
        String jexlExpr = rewriteRowFunctions(parsed.expression);
        result.put("jexlExpression", jexlExpr);
        result.put("originalExpression", parsed.expression);

        List<Map<String, Object>> rowResults = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("input_qty_raw", row.get("input_qty"));
            rr.put("input_qty_type", row.get("input_qty") != null ? row.get("input_qty").getClass().getSimpleName() : "null");
            rr.put("unit_price_raw", row.get("unit_price"));
            rr.put("unit_price_type", row.get("unit_price") != null ? row.get("unit_price").getClass().getSimpleName() : "null");
            rr.put("output_qty_raw", row.get("output_qty"));
            rr.put("loss_rate_raw", row.get("loss_rate"));
            // Evaluate predicate
            if (parsed.predicate != null && !parsed.predicate.isBlank()) {
                Object pred = evalRowExpression(parsed.predicate, row);
                rr.put("predicateResult", pred);
                rr.put("predicateResultType", pred != null ? pred.getClass().getSimpleName() : "null");
                rr.put("predicateTruthy", isTruthy(pred));
            }
            // Evaluate sub-expressions to trace where zero comes from
            // Use direct JEXL (NOT evalRowExpression, to avoid double-rewrite)
            try {
                JexlContext directCtx = new MapContext();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    directCtx.set(entry.getKey(), toJexlValue(entry.getValue()));
                    directCtx.set(sanitizeVarName(entry.getKey()), toJexlValue(entry.getValue()));
                }
                directCtx.set("_fn", new RowFunctions());
                rr.put("jexlABS_input_qty", rowJexl.createExpression("_fn.ABS(input_qty)").evaluate(directCtx));
                rr.put("jexlNULLIF_output_qty", rowJexl.createExpression("_fn.NULLIF(output_qty,0)").evaluate(directCtx));
                rr.put("jexlDiv", rowJexl.createExpression("_fn.ABS(input_qty)/_fn.NULLIF(output_qty,0)").evaluate(directCtx));
                rr.put("jexlMulLoss", rowJexl.createExpression("_fn.ABS(input_qty)/_fn.NULLIF(output_qty,0)*(1+loss_rate)").evaluate(directCtx));
                rr.put("jexlUnitPrice", rowJexl.createExpression("unit_price").evaluate(directCtx));
                rr.put("jexlFullExpr", rowJexl.createExpression("_fn.ABS(input_qty)/_fn.NULLIF(output_qty,0)*(1+loss_rate)*unit_price").evaluate(directCtx));
            } catch (Exception jexlEx) {
                rr.put("jexlError", jexlEx.getMessage());
            }
            // Evaluate row expression
            Object val = evalRowExpression(parsed.expression, row);
            rr.put("rowExprResult", val);
            rr.put("rowExprResultType", val != null ? val.getClass().getSimpleName() : "null");
            rr.put("rowExprBD", toBigDecimal(val));
            rowResults.add(rr);
        }
        result.put("rowResults", rowResults);

        // Step 6: aggregate
        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (parsed.predicate != null && !parsed.predicate.isBlank()) {
                Object pred = evalRowExpression(parsed.predicate, row);
                if (!isTruthy(pred)) continue;
            }
            Object val = evalRowExpression(parsed.expression, row);
            BigDecimal bd = toBigDecimal(val);
            if (bd != null) values.add(bd);
        }
        result.put("passedPredicateCount", values.size());
        result.put("aggregateResult", aggregate("SUM_OVER", values));
        return result;
    }

    /**
     * 外部调用入口：给定已 cached 公式值 Map，直接求值一条表达式（供 ExcelViewService 使用）。
     * 不走事务，不依赖模板 DB，适合在 buildRowData 时传入已有 cache。
     */
    public Object evaluateExpressionPublic(String expression,
                                           Map<String, TemplateFormulaDTO> byName,
                                           Map<String, Object> cached,
                                           UUID customerId, String partNo,
                                           List<Map<String, Object>> viewCols) {
        return evaluateExpression(expression, byName, cached, customerId, partNo, viewCols);
    }

    /** DFS 收集 formulaName 的所有传递依赖（限定为模板内的公式名） */
    private void collectDependencies(String name,
                                     Map<String, TemplateFormulaDTO> byName,
                                     Set<String> out, Set<String> visiting) {
        if (out.contains(name) || visiting.contains(name)) return;
        visiting.add(name);
        TemplateFormulaDTO f = byName.get(name);
        if (f != null) {
            List<String> deps = f.dependsOn != null ? f.dependsOn : detectDependencies(f.expression);
            for (String d : deps) {
                if (byName.containsKey(d)) collectDependencies(d, byName, out, visiting);
            }
        }
        visiting.remove(name);
        out.add(name);
    }

    /**
     * Stage 2 核心求值：
     *
     * 1. 检测 SUM_OVER / *_OVER 聚合函数 → 直接执行聚合，结果替换整段调用
     * 2. 替换 [名称] 引用：
     *    a. 已 cached 公式 → 字面量
     *    b. byName 中有但未 cached → 警告 + 0（拓扑序应该能避免这种情况）
     *    c. 不在公式中 → fallback 到 viewCols VARIABLE 路径查值
     *    d. 含点号 → {组件code.字段} 给 FormulaEngine
     * 3. 替换 @变量 → GlobalVariableService (SCALAR 或 key 为空的 LOOKUP)；找不到报错
     * 4. 调 FormulaEngine.evaluate 走 JEXL 算术（含 IF/COALESCE/NULLIF/ABS）
     */
    private Object evaluateExpression(String expression,
                                      Map<String, TemplateFormulaDTO> byName,
                                      Map<String, Object> cached,
                                      UUID customerId, String partNo,
                                      List<Map<String, Object>> viewCols) {
        if (expression == null) return null;

        // --- Step 1: 处理聚合函数（SUM_OVER 等）---
        // 如果整个表达式含有 SUM_OVER(...) 等，先替换掉聚合调用片段
        String processed = resolveAggregates(expression, customerId, partNo);

        // --- Step 2: 替换 [名称] 引用 ---
        StringBuffer sb = new StringBuffer();
        Matcher m = BRACKET_REF_PATTERN.matcher(processed);
        while (m.find()) {
            String ref = m.group(1).trim();
            if (ref.contains(".")) {
                // [组件code.字段] → {组件code.字段}
                m.appendReplacement(sb, "{" + Matcher.quoteReplacement(ref) + "}");
            } else if (cached.containsKey(ref)) {
                Object v = cached.get(ref);
                String literal = toNumericLiteral(v);
                m.appendReplacement(sb, Matcher.quoteReplacement(literal));
            } else if (byName.containsKey(ref)) {
                // 应该已在 cache，但未 cached（拓扑序问题）
                m.appendReplacement(sb, "0");
                LOG.warnf("[Stage2] Formula reference '%s' not yet cached, using 0", ref);
            } else {
                // col_key fallback: 从 viewCols 中找 VARIABLE 列路径取值
                Object fallback = resolveColKeyFallback(ref, viewCols, customerId, partNo);
                String literal = toNumericLiteral(fallback);
                m.appendReplacement(sb, Matcher.quoteReplacement(literal));
                LOG.debugf("[Stage2] col_key fallback '%s' → %s", ref, literal);
            }
        }
        m.appendTail(sb);
        String rewritten = sb.toString();

        // --- Step 3: 替换 @变量 ---
        Matcher gm = GLOBAL_VAR_PATTERN.matcher(rewritten);
        StringBuffer sb2 = new StringBuffer();
        while (gm.find()) {
            String varName = gm.group(1);
            BigDecimal gv = resolveGlobalVariable(varName);
            String literal = gv != null ? gv.toPlainString() : "0";
            gm.appendReplacement(sb2, Matcher.quoteReplacement(literal));
            LOG.debugf("[Stage2] @%s → %s", varName, literal);
        }
        gm.appendTail(sb2);
        String resolved = sb2.toString();

        // --- Step 4: FormulaEngine 算术求值（IF/COALESCE/NULLIF 由行内 JEXL 处理）---
        // 先把 COALESCE / NULLIF / IF / ABS 替换为 JEXL 可理解的行内求值
        String jexlReady = prepareInlineFunctions(resolved);

        return formulaEngine.evaluate(jexlReady, buildEvaluationContext(customerId, partNo));
    }

    /**
     * 替换表达式中所有顶层 SUM_OVER / *_OVER 调用，每次替换为标量字面量.
     * 支持嵌套括号（从左到右扫描，跳过内层括号）。
     */
    private String resolveAggregates(String expression, UUID customerId, String partNo) {
        Matcher m = OVER_FUNC_PATTERN.matcher(expression);
        if (!m.find()) return expression;  // 快速路径

        StringBuilder result = new StringBuilder();
        int pos = 0;
        // 重置 matcher 从头扫
        m.reset();
        while (m.find(pos)) {
            result.append(expression, pos, m.start());
            String funcName = m.group(1).toUpperCase();
            // 找到 "(" 的位置，然后找到对应的 ")"
            int openParen = m.end() - 1; // m.end()-1 是 '(' 的位置
            int closeParen = findMatchingParen(expression, openParen);
            if (closeParen < 0) {
                // 括号不匹配，原样保留
                result.append(expression, m.start(), m.end());
                pos = m.end();
                continue;
            }
            String argsContent = expression.substring(openParen + 1, closeParen);
            BigDecimal aggResult = executeOverFunction(funcName, argsContent, customerId, partNo);
            result.append(aggResult != null ? aggResult.toPlainString() : "0");
            pos = closeParen + 1;
        }
        result.append(expression.substring(pos));
        return result.toString();
    }

    /**
     * 执行 *_OVER 聚合函数.
     *
     * argsContent 格式（两种）:
     *   A) [来料BOM] WHERE predicate, expression
     *   B) [来料BOM], expression           (无 WHERE)
     *
     * @return 聚合结果，失败返回 0
     */
    private BigDecimal executeOverFunction(String funcName, String argsContent,
                                           UUID customerId, String partNo) {
        try {
            // SUMIF/COUNTIF/AVGIF/MINIF/MAXIF 族：cond 文本 → predicate 过滤聚合
            if (funcName.toUpperCase().endsWith("IF")) {
                int comma = findTopLevelComma(argsContent);
                String condText   = (comma >= 0 ? argsContent.substring(0, comma) : argsContent).trim();
                String valueExpr  = (comma >= 0 ? argsContent.substring(comma + 1).trim() : null);
                var pred = new com.cpq.formula.predicate.ConditionPredicateParser().parse(condText);
                // source：取 cond/valueExpr 中首个 [页签.字段] 的页签前缀（与 *_OVER source 同源）
                String source = extractFirstTabRef(condText, valueExpr);
                List<Map<String, Object>> rows = com.cpq.template.service.CardAggregateSource.rowsFor(source);
                if (rows == null) {
                    String driverPath = resolveDriverPath(source);
                    if (driverPath == null || driverPath.isBlank()) {
                        LOG.warnf("[Stage2] %s: cannot resolve driver path for source '%s'", funcName, source);
                        return BigDecimal.ZERO;
                    }
                    try {
                        rows = dataLoader.loadByPath(driverPath, null, partNo, customerId).get();
                    } catch (InterruptedException | ExecutionException e) {
                        LOG.warnf("[Stage2] %s: driver query failed: %s", funcName, e.getMessage());
                        return BigDecimal.ZERO;
                    }
                }
                if (rows == null) rows = List.of();
                return aggregateWithPredicate(funcName, rows, pred, valueExpr);
            }

            // 解析 source + (WHERE predicate) + expression
            // source 是第一个 [...] 部分
            OverFuncArgs parsed = parseOverFuncArgs(argsContent);
            if (parsed == null) {
                LOG.warnf("[Stage2] %s: failed to parse args: %s", funcName, argsContent);
                return BigDecimal.ZERO;
            }

            // 卡片源优先：Excel CARD_FORMULA 求值期，源 token 命中页签实例 → 用卡片行(已按别名重映射)
            List<Map<String, Object>> rows = com.cpq.template.service.CardAggregateSource.rowsFor(parsed.source);
            if (rows == null) {
                String driverPath = resolveDriverPath(parsed.source);
                if (driverPath == null || driverPath.isBlank()) {
                    LOG.warnf("[Stage2] %s: cannot resolve driver path for source '%s'", funcName, parsed.source);
                    return BigDecimal.ZERO;
                }
                LOG.infof("[Stage4] %s: source='%s' driverPath='%s' partNo='%s'",
                          funcName, parsed.source, driverPath, partNo);
                try {
                    rows = dataLoader.loadByPath(driverPath, null, partNo, customerId).get();
                } catch (InterruptedException | ExecutionException e) {
                    LOG.warnf("[Stage2] %s: driver query failed: %s | cause: %s",
                              funcName, e.getMessage(),
                              e.getCause() != null ? e.getCause().getMessage() : "null");
                    return BigDecimal.ZERO;
                }
                LOG.infof("[Stage4] %s: driverPath='%s' → %d rows loaded (partNo=%s)",
                          funcName, driverPath, rows == null ? 0 : rows.size(), partNo);
            } else {
                LOG.infof("[Stage4] %s: source='%s' matched card source → %d rows (alias-remapped)",
                          funcName, parsed.source, rows.size());
            }
            if (rows == null) rows = List.of();

            // 卡片动态谓词优先：binding 有按本产品行算好的谓词 → 用它；否则用公式文本切出的谓词。
            String dynPred = com.cpq.template.service.CardAggregateSource.predicateFor(parsed.source);
            String predicate = (dynPred != null) ? dynPred : parsed.predicate;

            // 对每行执行行内表达式 + WHERE 过滤 + 聚合
            List<BigDecimal> values = new ArrayList<>();
            int rowIdx = 0;
            for (Map<String, Object> row : rows) {
                rowIdx++;
                // 应用 WHERE 谓词（可选）
                if (predicate != null && !predicate.isBlank()) {
                    Object pred = evalRowExpression(predicate, row);
                    LOG.infof("[Stage4] %s row#%d: predicate='%s' result=%s (truthy=%b) inputQty=%s",
                              funcName, rowIdx, predicate, pred, isTruthy(pred),
                              row.get("input_qty"));
                    if (!isTruthy(pred)) continue;
                }
                // 求值行内 expression
                Object val = evalRowExpression(parsed.expression, row);
                BigDecimal bd = toBigDecimal(val);
                LOG.infof("[Stage4] %s row#%d passed WHERE: expr result=%s → bd=%s",
                          funcName, rowIdx, val, bd);
                if (bd != null) values.add(bd);
            }

            LOG.infof("[Stage4] %s: %d rows passed WHERE filter '%s', aggregating",
                      funcName, values.size(), predicate);
            return aggregate(funcName, values);

        } catch (Exception e) {
            LOG.warnf("[Stage2] %s aggregate failed: %s | stacktrace: %s",
                      funcName, e.getMessage(), e.getClass().getName());
            return BigDecimal.ZERO;
        }
    }

    /** 解析 *_OVER 参数结构 */
    private OverFuncArgs parseOverFuncArgs(String content) {
        // content: "[xxx] WHERE pred, expr" 或 "[xxx], expr"
        content = content.trim();
        // 1. 找第一个 [...]
        if (!content.startsWith("[")) return null;
        int closeBracket = content.indexOf(']');
        if (closeBracket < 0) return null;
        String source = content.substring(1, closeBracket).trim();

        String rest = content.substring(closeBracket + 1).trim();
        // rest: " WHERE pred, expr" 或 ", expr"

        String predicate = null;
        String expression;

        if (rest.toUpperCase().startsWith("WHERE")) {
            // 找最后一个顶层逗号，逗号前是 WHERE + pred，逗号后是 expression
            rest = rest.substring(5).trim(); // 去掉 "WHERE"
            int splitComma = findTopLevelComma(rest);
            if (splitComma < 0) {
                // 无逗号，则整个 rest 是 predicate，expression 为空（罕见，返回 null）
                return null;
            }
            predicate = rest.substring(0, splitComma).trim();
            expression = rest.substring(splitComma + 1).trim();
        } else if (rest.startsWith(",")) {
            expression = rest.substring(1).trim();
        } else {
            return null;
        }

        OverFuncArgs args = new OverFuncArgs();
        args.source = source;
        args.predicate = predicate;
        args.expression = expression;
        return args;
    }

    /** 找顶层逗号（不计嵌套括号内的逗号）。返回 -1 表示未找到 */
    private int findTopLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * 从 cond + valueExpr 文本里取第一个 [页签.字段] 的页签前缀，作为聚合 source。
     * 例：condText="[费用明细.类型]='管理费'" → "费用明细"。
     */
    private String extractFirstTabRef(String cond, String valueExpr) {
        for (String s : new String[]{ cond, valueExpr }) {
            if (s == null) continue;
            int lb = s.indexOf('[');
            int dot = lb >= 0 ? s.indexOf('.', lb) : -1;
            int rb  = lb >= 0 ? s.indexOf(']', lb) : -1;
            if (lb >= 0 && dot > lb && rb > dot) return s.substring(lb + 1, dot).trim();
            if (lb >= 0 && rb > lb)              return s.substring(lb + 1, rb).trim();
        }
        return "";
    }

    /**
     * 把 valueExpr 里的 {@code [页签.字段]} / {@code [字段]} 规整为裸字段名
     * （取最后一个 '.' 后的段），供 evalValueExpr 求值。
     *
     * <p>与 {@code ConditionPredicateParser} 取字段的口径一致：有 '.' 取其后，无 '.' 取整体。
     *
     * <p>示例：
     * <ul>
     *   <li>{@code [页签A.金额]}       → {@code 金额}</li>
     *   <li>{@code [金额]}             → {@code 金额}</li>
     *   <li>{@code [页签A.金额] * [页签A.数量]} → {@code 金额 * 数量}</li>
     *   <li>{@code 金额}               → {@code 金额}（原样）</li>
     * </ul>
     *
     * <p>注意：{@code [宿主页签.字段]} 剥壳后变裸字段名；EXCEL/小计线无宿主行时
     * evalValueExpr 取不到该字段则按 0/缺省处理（不崩溃）。见 spec §9 P0-5。
     *
     * <p>包内可见（非 private）以便单元测试直接调用验证剥壳逻辑。
     */
    String stripFieldRefs(String expr) {
        if (expr == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '[') {
                int rb = expr.indexOf(']', i);
                if (rb < 0) { sb.append(expr.substring(i)); break; }
                String inner = expr.substring(i + 1, rb).trim();
                int dot = inner.lastIndexOf('.');
                sb.append(dot >= 0 ? inner.substring(dot + 1) : inner);
                i = rb + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * SUMIF 族行内 valueExpr 求值，优先支持中文字段名。
     *
     * <p>两步策略：
     * <ol>
     *   <li>纯字段名（trim 后直接匹配 row.get(key)，含中文）→ 直接取值，避免 JEXL tokenize 中文失败。</li>
     *   <li>复杂表达式（含运算符）→ 先把 row 里出现在表达式中的中文 key 替换为
     *       {@code f_<index>} 占位符，同时注入对应值，再走 JEXL。这样支持 {@code 金额*数量} 等。</li>
     * </ol>
     */
    private Object evalValueExpr(String expr, Map<String, Object> row) {
        if (expr == null || expr.isBlank()) return null;
        String trimmed = expr.trim();
        // 策略 1：纯字段名（无运算符）→ 直接 row.get
        if (row.containsKey(trimmed)) return toJexlValue(row.get(trimmed));
        // 策略 2：复杂表达式 → 替换中文字段名为 ASCII 占位符后走 JEXL
        String rewritten = trimmed;
        Map<String, Object> rewrittenRow = new java.util.LinkedHashMap<>(row);
        int idx = 0;
        // 按 key 长度从长到短，避免短 key 先匹配
        java.util.List<String> sortedKeys = new java.util.ArrayList<>(row.keySet());
        sortedKeys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String key : sortedKeys) {
            boolean hasNonAscii = key.chars().anyMatch(c -> c > 127);
            if (!hasNonAscii) continue;
            if (!rewritten.contains(key)) continue;
            String placeholder = "f_cpq_" + idx++;
            rewritten = rewritten.replace(key, placeholder);
            rewrittenRow.put(placeholder, row.get(key));
        }
        return evalRowExpression(rewritten, rewrittenRow);
    }

    /** 找匹配的右括号，openParen 是 '(' 的下标 */
    private int findMatchingParen(String s, int openParen) {
        int depth = 0;
        for (int i = openParen; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 解析 [source] 内容 → data driver path.
     *
     * 优先级：
     * 1. 完全匹配 Component.code → 取 component.dataDriverPath
     * 2. 直接当视图名（v_c_xxx_merged 等）
     */
    /**
     * 解析 [source] 内容 → data driver path.
     *
     * Stage 4: 改用 JDBC 直查 component 表，避免 Panache (Component.list) 在无 Hibernate Session
     * 上下文时抛出异常（外层 catch 兜底返回 source 本身 = "COMP-V5-RAW-BOM-PRICED"，
     * 含连字符无法被 CpqPathParser 解析，导致 DataLoader 查询失败，SUM_OVER 返回 0）。
     *
     * 优先级：
     * 1. JDBC 按 code 查 component.data_driver_path
     * 2. JDBC 按 name 查 component.data_driver_path
     * 3. 直接当视图名/路径使用（v_c_xxx_merged 等，CpqPathParser 可直接解析）
     */
    private String resolveDriverPath(String source) {
        if (source == null || source.isBlank()) return null;
        // 1. 用 JDBC 按 code 查 (避免 Panache Session 问题)
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT data_driver_path FROM component WHERE code = ? AND data_driver_path IS NOT NULL LIMIT 1")) {
            ps.setString(1, source);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String path = rs.getString(1);
                    LOG.infof("[Stage4] resolveDriverPath: code='%s' → JDBC found path='%s'", source, path);
                    return path;
                }
            }
        } catch (Exception e) {
            LOG.warnf("[Stage4] resolveDriverPath: JDBC by code failed: %s", e.getMessage());
        }
        // 2. 用 JDBC 按 name 查
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT data_driver_path FROM component WHERE name = ? AND data_driver_path IS NOT NULL LIMIT 1")) {
            ps.setString(1, source);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String path = rs.getString(1);
                    LOG.infof("[Stage4] resolveDriverPath: name='%s' → JDBC found path='%s'", source, path);
                    return path;
                }
            }
        } catch (Exception e) {
            LOG.warnf("[Stage4] resolveDriverPath: JDBC by name failed: %s", e.getMessage());
        }
        // 3. 直接当路径（ASCII 视图名可以直接被 CpqPathParser 解析）
        LOG.debugf("[Stage4] resolveDriverPath: source='%s' used as direct path", source);
        return source;
    }

    /**
     * 行内表达式求值（单行 driverRow 做变量），用轻量 JEXL.
     * 支持：四则运算、ABS()、NULLIF(a,b)、COALESCE(a,b,c)、IF(c,t,f)、字段引用。
     */
    private Object evalRowExpression(String expression, Map<String, Object> row) {
        if (expression == null || expression.isBlank()) return null;
        try {
            JexlContext ctx = new MapContext();
            // 注入行字段
            if (row != null) {
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    ctx.set(sanitizeVarName(e.getKey()), toJexlValue(e.getValue()));
                    // 同时注入原始 key（带特殊字符的也存一份清洁版）
                    ctx.set(e.getKey(), toJexlValue(e.getValue()));
                }
            }
            // 内置函数：NULLIF / COALESCE / IF / ABS
            ctx.set("_fn", new RowFunctions());

            // 替换函数名
            String jexl = rewriteRowFunctions(expression);
            Object result = rowJexl.createExpression(jexl).evaluate(ctx);
            return normalizeResult(result);
        } catch (Exception e) {
            LOG.debugf("[Stage2] row expression eval failed: expr='%s', err=%s", expression, e.getMessage());
            return null;
        }
    }

    /** 重写行内函数：NULLIF → _fn.NULLIF, COALESCE → _fn.COALESCE, ABS → _fn.ABS, IF → _fn.IF */
    private String rewriteRowFunctions(String expr) {
        return expr
                .replaceAll("(?i)\\bNULLIF\\s*\\(", "_fn.NULLIF(")
                .replaceAll("(?i)\\bCOALESCE\\s*\\(", "_fn.COALESCE(")
                .replaceAll("(?i)\\bABS\\s*\\(", "_fn.ABS(")
                .replaceAll("(?i)\\bIF\\s*\\(", "_fn.IF(");
    }

    /**
     * 为主表达式准备行内函数（供 FormulaEngine 前处理）.
     * IF/COALESCE/NULLIF/ABS 在模板公式的非聚合部分也可用。
     * FormulaEngine 的 FunctionRegistry 已注册 IF/ABS；COALESCE/NULLIF 需要在这里展开
     * 或通过 IF 等价替换。
     *
     * 策略：COALESCE(a,b) → (a == null ? b : a) 的 JEXL 等价
     * 但 JEXL 不支持三目，改用已有 IF 函数：fn.IF(a != null, a, b)
     * NULLIF(a,b) → fn.IF(a == b, null, a)
     *
     * 实际上 FormulaEngine 的 JexlFunctionNamespace 已有 IF(cond,t,f)，所以：
     * - COALESCE(a,b,c) → fn.IF(a != null, a, fn.IF(b != null, b, c))  (2个 fallback)
     * - NULLIF(a,b) → fn.IF(a == b, null, a)
     *
     * 暂用简单替换（不支持 N 参数 COALESCE，Stage 4 改成真正解析器）
     */
    private String prepareInlineFunctions(String expr) {
        // FormulaEngine 已有 IF / ABS，只需处理 COALESCE / NULLIF
        // 简单 2 参数 COALESCE(a,b) → IF(a != null, a, b)
        // 复杂多参数版留 TODO
        // NULLIF(a,b) → IF(a == b, null, a)
        // 这里用字符级替换（简单场景），复杂嵌套靠 evaluateExpression 中已在聚合阶段处理完了
        return expr; // FormulaEngine 本身的 JEXL 已有 null-safety，简单公式不需要额外处理
    }

    /**
     * col_key fallback: 从 excel_view_config VARIABLE 列中找 col_key，取路径值.
     * 如果是 FORMULA 列则返回 null（避免递归）。
     */
    private Object resolveColKeyFallback(String colKey,
                                         List<Map<String, Object>> viewCols,
                                         UUID customerId, String partNo) {
        if (viewCols == null || viewCols.isEmpty()) return null;
        for (Map<String, Object> col : viewCols) {
            if (!colKey.equals(col.get("col_key"))) continue;
            String sourceType = (String) col.get("source_type");
            if (!"VARIABLE".equals(sourceType)) continue;
            String varPath = (String) col.get("variable_path");
            if (varPath == null || varPath.isBlank()) continue;
            // 用 DataLoader 查
            try {
                String normalizedPath = varPath.startsWith("{") ? varPath : "{" + varPath + "}";
                List<Map<String, Object>> rows = dataLoader
                        .loadByPath(normalizedPath, null, partNo, customerId).get();
                if (rows == null || rows.isEmpty()) return null;
                Map<String, Object> row = rows.get(0);
                if (row.size() == 1) return row.values().iterator().next();
                // 多列行：尝试取路径末段字段名
                String field = extractLastSegment(varPath);
                return field != null ? row.get(field) : row.values().iterator().next();
            } catch (Exception e) {
                LOG.debugf("[Stage2] col_key fallback query failed: colKey=%s path=%s err=%s",
                        colKey, varPath, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** 从 "view.field" 或 "{view.field}" 取末段字段名 */
    private String extractLastSegment(String path) {
        String s = path.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1).trim() : null;
    }

    /**
     * 解析 @变量名 → BigDecimal.
     *
     * Stage 2 策略：
     * - 如果变量是 SCALAR 类型（无 keyColumns）→ 直接 SELECT value_column FROM source_view LIMIT 1
     * - 如果变量是 LOOKUP_TABLE 类型但 keyColumns 为空 → 同上（不常见，容错）
     * - 如果 LOOKUP_TABLE 且需要 key → 记录 warn + 返回 null（调用方用 0 兜底）
     *
     * 注意：这里的 varName 是 @后面的名称，可能是 code 或 name。
     * GlobalVariableDefinition 里有 code 和 name，我们先按 name 查，再按 code 查。
     */
    private BigDecimal resolveGlobalVariable(String varName) {
        try {
            // 先按 name 模糊查（name 是中文/业务名，code 是系统编码）
            var defOpt = globalVariableService.getByName(varName);
            if (defOpt.isEmpty()) {
                // 再按 code 查
                defOpt = globalVariableService.getByCode(varName);
            }
            if (defOpt.isEmpty()) {
                LOG.warnf("[Stage2] @%s 全局变量未注册，使用 0 兜底", varName);
                return null;
            }
            var def = defOpt.get();
            if (!def.isLookup() || (def.keyColumns == null || def.keyColumns.isEmpty())) {
                // SCALAR 或无 key 的 LOOKUP → 取第一行
                List<Map<String, Object>> keys = globalVariableService.listKeys(def.code, 1);
                if (keys.isEmpty()) return null;
                Object v = keys.get(0).get("value");
                return toBigDecimal(v);
            }
            // LOOKUP 需要 key → 无法在纯公式上下文中确定，返回 null
            LOG.warnf("[Stage2] @%s 是 LOOKUP_TABLE 类型，需要 key 才能解析，在无 key 上下文中使用 0 兜底", varName);
            return null;
        } catch (Exception e) {
            LOG.warnf("[Stage2] @%s 解析失败: %s", varName, e.getMessage());
            return null;
        }
    }

    /** Stage 2 扩展点：单一 [name] → 公式定义查找点 */
    TemplateFormulaDTO resolveFormulaReference(UUID templateId, String name) {
        try { return findByName(templateId, name); } catch (BusinessException ignored) { return null; }
    }

    /** Stage 1 只塞 customerId/partNo + dataLoader；Stage 2 可叠加 cachedFormulas */
    EvaluationContext buildEvaluationContext(UUID customerId, String partNo) {
        EvaluationContext.Builder b = EvaluationContext.builder().dataLoader(dataLoader);
        if (customerId != null) b.customerId(customerId);
        if (partNo != null) b.partNo(partNo);
        return b.build();
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────────

    /** 自动检测表达式中所有引用：[名称] / [组件code.字段] / @变量 */
    public List<String> detectDependencies(String expression) {
        if (expression == null) return new ArrayList<>();
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        Matcher m = BRACKET_REF_PATTERN.matcher(expression);
        while (m.find()) deps.add(m.group(1).trim());
        Matcher gm = GLOBAL_VAR_PATTERN.matcher(expression);
        while (gm.find()) deps.add("@" + gm.group(1));
        return new ArrayList<>(deps);
    }

    Template mustDraft(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) throw new BusinessException(404, "Template not found: " + templateId);
        if (!"DRAFT".equals(t.status)) {
            throw new BusinessException(400, "仅 DRAFT 模板可改公式（当前 status=" + t.status + "）");
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    List<TemplateFormulaDTO> parseFormulas(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<TemplateFormulaDTO> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                TemplateFormulaDTO d = new TemplateFormulaDTO();
                d.name = strOrNull(row.get("name"));
                d.expression = strOrNull(row.get("expression"));
                d.dataType = strOrNull(row.getOrDefault("data_type", row.get("dataType")));
                Object dep = row.containsKey("depends_on") ? row.get("depends_on") : row.get("dependsOn");
                if (dep instanceof List<?> list) {
                    d.dependsOn = new ArrayList<>();
                    for (Object o : list) if (o != null) d.dependsOn.add(o.toString());
                }
                d.description = strOrNull(row.get("description"));
                out.add(d);
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("Failed to parse template.formulas JSON: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    String serialize(List<TemplateFormulaDTO> formulas) {
        List<Map<String, Object>> rows = new ArrayList<>(formulas.size());
        for (TemplateFormulaDTO f : formulas) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", f.name);
            r.put("expression", f.expression);
            r.put("data_type", f.dataType);
            r.put("depends_on", f.dependsOn != null ? f.dependsOn : List.of());
            if (f.description != null) r.put("description", f.description);
            rows.add(r);
        }
        try { return MAPPER.writeValueAsString(rows); }
        catch (Exception e) { return "[]"; }
    }

    // ─────────────────────────────────────────────────────────────────
    // P0: 错误信息中文化 — JEXL / Business 异常翻译
    // ─────────────────────────────────────────────────────────────────

    /**
     * 将 JexlException 翻译为结构化中文 FormulaErrorDTO.
     *
     * <h2>覆盖的异常类型</h2>
     * <ul>
     *   <li>JexlException.Parsing   → PARSE_ERROR  "第X行第Y列附近语法错误"</li>
     *   <li>JexlException.Variable  → UNKNOWN_GLOBAL "@xxx 不存在"</li>
     *   <li>JexlException.Property  → UNKNOWN_FIELD "字段X在组件Y中不存在"</li>
     *   <li>JexlException.Method    → UNKNOWN_FUNCTION "函数X不支持"</li>
     *   <li>其他 JexlException      → RUNTIME_ERROR "公式运行时错误"</li>
     * </ul>
     */
    public FormulaErrorDTO translateJexlError(JexlException je, String expression) {
        if (je == null) return new FormulaErrorDTO("RUNTIME_ERROR", "公式执行发生未知错误");

        String rawMsg = je.getMessage() != null ? je.getMessage() : je.getClass().getSimpleName();

        // ── 1. 语法解析错误 (JexlException.Parsing) ──────────────────────────
        if (je instanceof JexlException.Parsing) {
            Integer line = extractJexlLine(je);
            Integer col  = extractJexlColumn(je);
            String locDesc = buildLocationDesc(line, col);
            List<FormulaSuggestionDTO> suggestions = new ArrayList<>();
            suggestions.add(new FormulaSuggestionDTO("检查括号是否配对（左括号数量 = 右括号数量）"));
            suggestions.add(new FormulaSuggestionDTO("检查操作符两侧是否有操作数，如 a + b 不能写成 a +"));
            suggestions.add(new FormulaSuggestionDTO("检查字符串是否用单引号包裹，如 'CNY'"));
            // 尝试自动补全右括号
            if (expression != null) {
                int open = countChar(expression, '(');
                int close = countChar(expression, ')');
                if (open > close) {
                    String fixed = expression + ")".repeat(open - close);
                    suggestions.add(0, new FormulaSuggestionDTO(
                            "添加 " + (open - close) + " 个缺失的右括号", fixed));
                }
            }
            return new FormulaErrorDTO("PARSE_ERROR",
                    locDesc + "语法错误，请检查括号配对和操作符写法",
                    line, col).withSuggestions(suggestions);
        }

        // ── 2. 未定义变量（通常是 @xxx 全局变量引用失败）─────────────────────
        if (je instanceof JexlException.Variable jv) {
            String varRef = extractVariableName(rawMsg, jv);
            List<FormulaSuggestionDTO> suggestions = new ArrayList<>();
            suggestions.add(new FormulaSuggestionDTO(
                    "检查 @变量名 拼写，或在「全局变量管理」中确认该变量已注册且状态为活跃"));
            // 列出当前已注册的全局变量名供参考
            try {
                List<GlobalVariableDefinition> defs = globalVariableService.listAll();
                if (!defs.isEmpty()) {
                    String available = defs.stream()
                            .map(d -> "@" + d.name)
                            .reduce((a, b) -> a + "、" + b)
                            .orElse("");
                    suggestions.add(new FormulaSuggestionDTO("当前可用的全局变量：" + available));
                }
            } catch (Exception ignored) {}
            return new FormulaErrorDTO("UNKNOWN_GLOBAL",
                    "全局变量 " + varRef + " 不存在，请确认 @变量名 拼写正确")
                    .withSuggestions(suggestions);
        }

        // ── 3. 属性不存在（字段引用失败，如 [组件.字段] 中字段名错误）──────────
        if (je instanceof JexlException.Property jp) {
            String propRef = extractPropertyName(rawMsg);
            List<FormulaSuggestionDTO> suggestions = new ArrayList<>();
            suggestions.add(new FormulaSuggestionDTO(
                    "检查 [组件code.字段名] 中的字段名是否存在于该组件定义中"));
            suggestions.add(new FormulaSuggestionDTO(
                    "字段名大小写敏感，请与组件字段定义保持一致"));
            return new FormulaErrorDTO("UNKNOWN_FIELD",
                    "字段 " + propRef + " 不存在，请检查组件字段名拼写")
                    .withSuggestions(suggestions);
        }

        // ── 4. 方法不存在（调用了不支持的函数）─────────────────────────────────
        if (je instanceof JexlException.Method jm) {
            String methodRef = extractMethodName(rawMsg);
            List<FormulaSuggestionDTO> suggestions = new ArrayList<>();
            suggestions.add(new FormulaSuggestionDTO(
                    "支持的聚合函数：SUM_OVER / COUNT_OVER / AVG_OVER / MIN_OVER / MAX_OVER"));
            suggestions.add(new FormulaSuggestionDTO(
                    "支持的条件函数：IF(条件, 真值, 假值)"));
            suggestions.add(new FormulaSuggestionDTO(
                    "支持的数学函数：ABS(值) / NULLIF(值, 比较值) / COALESCE(值1, 值2, ...)"));
            return new FormulaErrorDTO("UNKNOWN_FUNCTION",
                    "函数 " + methodRef + " 不支持，请参考函数清单")
                    .withSuggestions(suggestions);
        }

        // ── 5. 除零 / 算术错误 ──────────────────────────────────────────────
        if (rawMsg != null && (rawMsg.contains("divide by zero") || rawMsg.contains("/ by zero")
                || rawMsg.contains("division") || je.getCause() instanceof ArithmeticException)) {
            List<FormulaSuggestionDTO> suggestions = new ArrayList<>();
            suggestions.add(new FormulaSuggestionDTO(
                    "用 NULLIF 包裹除数，避免除零: expr / NULLIF(除数, 0)"));
            return new FormulaErrorDTO("RUNTIME_ERROR",
                    "公式运行时错误: 除数为 0，请用 NULLIF(除数, 0) 保护")
                    .withSuggestions(suggestions);
        }

        // ── 6. 通用运行时错误兜底 ────────────────────────────────────────────
        String friendlyMsg = simplifyJexlMessage(rawMsg);
        return new FormulaErrorDTO("RUNTIME_ERROR", "公式运行时错误: " + friendlyMsg);
    }

    /**
     * 将 BusinessException 翻译为 FormulaErrorDTO.
     * BusinessException 通常由 validateSingle / validateGraph 抛出（中文 message），
     * 这里做二次分类，识别循环依赖等特殊情况。
     */
    public FormulaErrorDTO translateBusinessException(BusinessException be, String expression) {
        String msg = be.getMessage() != null ? be.getMessage() : "公式校验失败";
        // 循环依赖
        if (msg.contains("循环依赖")) {
            return new FormulaErrorDTO("CIRCULAR_DEP", msg);
        }
        // 不支持的函数
        if (msg.contains("暂不支持函数")) {
            List<FormulaSuggestionDTO> suggestions = List.of(
                    new FormulaSuggestionDTO("GROUP_BY / REDUCE 尚未实现，请用 SUM_OVER / COUNT_OVER 等聚合函数替代"));
            return new FormulaErrorDTO("UNKNOWN_FUNCTION", msg).withSuggestions(suggestions);
        }
        // 表达式为空 / 字段缺失 → PARSE_ERROR
        if (msg.contains("必填") || msg.contains("为空")) {
            return new FormulaErrorDTO("PARSE_ERROR", msg);
        }
        // 通用兜底
        return new FormulaErrorDTO("RUNTIME_ERROR", msg);
    }

    // ── 私有工具：JEXL 错误信息解析 ─────────────────────────────────────────

    private Integer extractJexlLine(JexlException je) {
        try {
            // JexlException 的 info 字段（JexlInfo）含行列信息
            var info = je.getInfo();
            if (info != null) return info.getLine();
        } catch (Exception ignored) {}
        // fallback: 从 message 解析 "at line X"
        return extractIntFromMsg(je.getMessage(), "at line (\\d+)");
    }

    private Integer extractJexlColumn(JexlException je) {
        try {
            var info = je.getInfo();
            if (info != null) return info.getColumn();
        } catch (Exception ignored) {}
        return extractIntFromMsg(je.getMessage(), "column (\\d+)");
    }

    private Integer extractIntFromMsg(String msg, String regex) {
        if (msg == null) return null;
        Matcher m = Pattern.compile(regex).matcher(msg);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }

    private String buildLocationDesc(Integer line, Integer col) {
        if (line != null && col != null) return "第 " + line + " 行第 " + col + " 列附近：";
        if (line != null) return "第 " + line + " 行附近：";
        return "";
    }

    private String extractVariableName(String rawMsg, JexlException je) {
        if (rawMsg == null) return "<未知变量>";
        // JexlException.Variable message 通常含 "undefined variable 'xxx'" 或 "variable 'xxx'"
        Matcher m = Pattern.compile("undefined variable[^']*'([^']+)'").matcher(rawMsg);
        if (m.find()) return "@" + m.group(1);
        m = Pattern.compile("variable[^']*'([^']+)'").matcher(rawMsg);
        if (m.find()) return "@" + m.group(1);
        // 从 JexlInfo 的 detail 取（JEXL 3.3 API）
        try {
            var info = je.getInfo();
            if (info != null && info.getDetail() != null) return info.getDetail().toString();
        } catch (Exception ignored) {}
        return "<未知变量>";
    }

    private String extractPropertyName(String rawMsg) {
        if (rawMsg == null) return "<未知字段>";
        Matcher m = Pattern.compile("property[^']*'([^']+)'").matcher(rawMsg);
        if (m.find()) return m.group(1);
        m = Pattern.compile("not found[^']*'([^']+)'").matcher(rawMsg);
        if (m.find()) return m.group(1);
        return "<未知字段>";
    }

    private String extractMethodName(String rawMsg) {
        if (rawMsg == null) return "<未知函数>";
        Matcher m = Pattern.compile("method[^']*'([^']+)'").matcher(rawMsg);
        if (m.find()) return m.group(1);
        m = Pattern.compile("'([A-Z_][A-Z0-9_]*)'").matcher(rawMsg);
        if (m.find()) return m.group(1);
        return "<未知函数>";
    }

    private String simplifyJexlMessage(String rawMsg) {
        if (rawMsg == null) return "未知错误";
        // 去掉 JEXL 内部包前缀，只保留关键词
        return rawMsg
                .replaceAll("org\\.apache\\.commons\\.jexl3\\.[A-Za-z.]+:\\s*", "")
                .replaceAll("com\\.cpq\\.[A-Za-z.]+:\\s*", "")
                .trim();
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }

    // ─────────────────────────────────────────────────────────────────
    // P0: 自动补全 API — 返回模板公式 / 组件字段 / 全局变量三类候选
    // ─────────────────────────────────────────────────────────────────

    /**
     * 构造当前模板的公式自动补全数据。
     *
     * <ul>
     *   <li>templateFormulas — 从 template.formulas JSONB 读取所有公式</li>
     *   <li>components       — 从 template_component JOIN component 读取绑定的组件及字段</li>
     *   <li>globalVariables  — 从 global_variable_definition 读取全部活跃变量</li>
     * </ul>
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public FormulaCompletionDTO getFormulaCompletions(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) throw new BusinessException(404, "Template not found: " + templateId);

        FormulaCompletionDTO dto = new FormulaCompletionDTO();

        // 1. 模板公式列表
        List<TemplateFormulaDTO> formulaList = parseFormulas(t.formulas);
        dto.templateFormulas = formulaList.stream().map(f -> {
            FormulaCompletionDTO.FormulaItem item = new FormulaCompletionDTO.FormulaItem();
            item.name = f.name;
            item.dataType = f.dataType;
            item.description = f.description;
            return item;
        }).collect(java.util.stream.Collectors.toList());

        // 2. 绑定组件列表（JOIN component 表拿字段）
        List<TemplateComponent> tcList = TemplateComponent.list("templateId = ?1", templateId);
        List<FormulaCompletionDTO.ComponentItem> compItems = new ArrayList<>();
        for (TemplateComponent tc : tcList) {
            Component comp = Component.findById(tc.componentId);
            if (comp == null) continue;
            List<FormulaCompletionDTO.FieldItem> fieldItems = parseComponentFields(comp.fields);
            compItems.add(new FormulaCompletionDTO.ComponentItem(comp.code, comp.name, fieldItems));
        }
        dto.components = compItems;

        // 3. 全局变量列表
        List<GlobalVariableDefinition> gvDefs = globalVariableService.listAll();
        List<FormulaCompletionDTO.GlobalVariableItem> gvItems = new ArrayList<>();
        for (GlobalVariableDefinition def : gvDefs) {
            FormulaCompletionDTO.GlobalVariableItem item = new FormulaCompletionDTO.GlobalVariableItem();
            item.name = def.name;
            item.code = def.code;
            item.dataType = "DECIMAL";
            item.description = def.description;
            item.unit = def.unit;
            item.varType = def.varType;
            // SCALAR 类型尝试通过 resolveGlobalVariable 取当前值（LOOKUP_TABLE 不取，避免 key 不确定）
            if (!def.isLookup()) {
                try {
                    BigDecimal val = resolveGlobalVariable(def.name);
                    if (val == null) val = resolveGlobalVariable(def.code);
                    item.currentValue = val;
                } catch (Exception ignored) {
                    // 取值失败静默忽略，currentValue 保持 null
                }
            }
            gvItems.add(item);
        }
        dto.globalVariables = gvItems;

        return dto;
    }

    /**
     * 从 component.fields JSONB 解析字段列表，提取 name/label/dataType.
     * JSONB 结构：[{name, label, data_type, field_type, ...}, ...]
     */
    @SuppressWarnings("unchecked")
    private List<FormulaCompletionDTO.FieldItem> parseComponentFields(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) return new ArrayList<>();
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(
                    fieldsJson, new TypeReference<List<Map<String, Object>>>() {});
            List<FormulaCompletionDTO.FieldItem> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                String name = strOrNull(row.get("name"));
                if (name == null || name.isBlank()) continue;
                String label = strOrNull(row.getOrDefault("label", row.get("display_name")));
                String dt = strOrNull(row.getOrDefault("data_type", row.get("dataType")));
                if (dt == null) dt = "TEXT";
                out.add(new FormulaCompletionDTO.FieldItem(name, label != null ? label : name, dt));
            }
            return out;
        } catch (Exception e) {
            LOG.debugf("parseComponentFields failed: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String strOrNull(Object v) { return v == null ? null : v.toString(); }

    /** 把 Object 转为数值字面量字符串（用于表达式替换） */
    private String toNumericLiteral(Object v) {
        if (v == null) return "0";
        if (v instanceof FormulaError) return "0";
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).toPlainString();
        String s = v.toString().trim();
        try {
            new BigDecimal(s);
            return s;
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString().trim()); }
        catch (Exception e) { return null; }
    }

    private Object toJexlValue(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return v;
    }

    private String sanitizeVarName(String key) {
        if (key == null) return "_";
        return key.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static Object normalizeResult(Object v) {
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return new BigDecimal(f.toString());
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        return v;
    }

    /** 按别名条件 cond 在 aliasedRows 中找首个命中行下标；无命中返回 -1；cond 为空返回 0(首行)。 */
    public int firstMatchIndex(java.util.List<java.util.Map<String, Object>> aliasedRows, String cond) {
        if (aliasedRows == null || aliasedRows.isEmpty()) return -1;
        if (cond == null || cond.isBlank()) return 0;
        for (int i = 0; i < aliasedRows.size(); i++) {
            if (isTruthy(evalRowExpression(cond, aliasedRows.get(i)))) return i;
        }
        return -1;
    }

    private boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof BigDecimal bd) return bd.compareTo(BigDecimal.ZERO) != 0;
        String s = v.toString().trim();
        return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s);
    }

    private BigDecimal aggregate(String funcName, List<BigDecimal> values) {
        if (values.isEmpty()) {
            return switch (funcName) {
                case "COUNT_OVER" -> BigDecimal.ZERO;
                default -> BigDecimal.ZERO;
            };
        }
        return switch (funcName) {
            case "SUM_OVER" -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal v : values) sum = sum.add(v);
                yield sum;
            }
            case "COUNT_OVER" -> BigDecimal.valueOf(values.size());
            case "AVG_OVER" -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal v : values) sum = sum.add(v);
                yield sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128);
            }
            case "MIN_OVER" -> {
                BigDecimal min = values.get(0);
                for (BigDecimal v : values) if (v.compareTo(min) < 0) min = v;
                yield min;
            }
            case "MAX_OVER" -> {
                BigDecimal max = values.get(0);
                for (BigDecimal v : values) if (v.compareTo(max) > 0) max = v;
                yield max;
            }
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * 包内可测：对给定行集按 predicate 过滤后求 valueExprText（COUNTIF 无需 valueExpr），单值聚合。
     *
     * <p>不依赖任何 @Inject 字段（仅用 evalRowExpression / aggregate / toBigDecimal），
     * 可由 {@code new TemplateFormulaService()} 直接测试。
     *
     * @param funcName     SUMIF / COUNTIF / AVGIF / MINIF / MAXIF（大写）
     * @param rows         源行集，由调用方（executeOverFunction / 测试）传入
     * @param pred         predicate（null = 不过滤）
     * @param valueExprText 行内聚合表达式（COUNTIF 传 null 即可）
     */
    BigDecimal aggregateWithPredicate(String funcName,
                                      java.util.List<java.util.Map<String, Object>> rows,
                                      com.cpq.formula.predicate.ConditionPredicate pred,
                                      String valueExprText) {
        // 剥壳：用户真实语法 [页签.字段] / [字段] → 裸字段名，让 evalValueExpr 能正确求值。
        // COUNTIF（valueExprText=null）不受影响；[宿主页签.字段] 剥壳后若 row 无该字段按 0/缺省处理（见 spec §9 P0-5）。
        String resolvedValueExpr = stripFieldRefs(valueExprText);
        var ev = new com.cpq.formula.predicate.ConditionPredicateEvaluator();
        java.util.List<BigDecimal> values = new java.util.ArrayList<>();
        java.util.Map<String, Object> emptyHost = java.util.Map.of();
        for (var row : rows) {
            if (!ev.test(pred, row, emptyHost)) continue;
            if ("COUNTIF".equalsIgnoreCase(funcName)) {
                values.add(BigDecimal.ONE);
                continue;
            }
            // 优先：resolvedValueExpr 是纯字段名 → 直接从 row 取值（支持中文字段名，无 JEXL tokenize 问题）
            // 回退：复杂表达式（含运算符）→ evalRowExpression（字段名须为 ASCII）
            // resolvedValueExpr 已在入口处由 stripFieldRefs 剥去 [页签.字段] 外壳
            Object val = evalValueExpr(resolvedValueExpr, row);
            BigDecimal bd = toBigDecimal(val);
            if (bd != null) values.add(bd);
        }
        // 把 XXXIF 归一到对应 *_OVER 名后复用 aggregate() reduce + 空集语义
        String overName = switch (funcName.toUpperCase()) {
            case "SUMIF"   -> "SUM_OVER";
            case "COUNTIF" -> "COUNT_OVER";
            case "AVGIF"   -> "AVG_OVER";
            case "MINIF"   -> "MIN_OVER";
            case "MAXIF"   -> "MAX_OVER";
            default        -> "SUM_OVER";
        };
        return aggregate(overName, values);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 校验结果 DTO
    // ─────────────────────────────────────────────────────────────────

    public static class ValidationResult {
        public boolean valid;
        /** 向后兼容：旧版纯文本错误信息（deprecated，优先用 errors） */
        public String error;
        public List<String> dependsOn;
        /**
         * P0 结构化错误列表（新增）.
         * valid=false 时，此列表包含至少一条 FormulaErrorDTO，
         * 含中文 message / code / line / column / suggestions。
         * valid=true 时为 null。
         */
        public List<FormulaErrorDTO> errors;
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部数据结构
    // ─────────────────────────────────────────────────────────────────

    private static class OverFuncArgs {
        String source;
        String predicate;    // 可空
        String expression;
    }

    /**
     * 行内函数命名空间（供 rowJexl 使用）.
     * 通过 JEXL 的 "object.method" 模式调用。
     */
    public static class RowFunctions {
        public Object NULLIF(Object a, Object b) {
            if (a == null) return null;
            if (b == null) return a;
            if (a.toString().equals(b.toString())) return null;
            if (a instanceof Number na && b instanceof Number nb) {
                BigDecimal ba = toBD(na), bb = toBD(nb);
                if (ba != null && bb != null && ba.compareTo(bb) == 0) return null;
            }
            return a;
        }

        public Object COALESCE(Object... args) {
            for (Object v : args) if (v != null) return v;
            return null;
        }

        public Object ABS(Object v) {
            BigDecimal bd = toBD(v);
            return bd != null ? bd.abs() : v;
        }

        public Object IF(Object cond, Object thenVal, Object elseVal) {
            boolean c = false;
            if (cond instanceof Boolean b) c = b;
            else if (cond instanceof Number n) c = n.doubleValue() != 0;
            else if (cond instanceof BigDecimal bd) c = bd.compareTo(BigDecimal.ZERO) != 0;
            else if (cond != null) c = !"0".equals(cond.toString()) && !"false".equalsIgnoreCase(cond.toString());
            return c ? thenVal : elseVal;
        }

        private static BigDecimal toBD(Object v) {
            if (v == null) return null;
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return new BigDecimal(n.toString());
            try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
        }
    }
}
