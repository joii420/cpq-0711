package com.cpq.costing.service;

import com.cpq.basicdata.entity.ComparisonTag;
import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.ComparisonDTO;
import com.cpq.costing.dto.CostingSheetDTO;
import com.cpq.costing.entity.CostingSheet;
import com.cpq.costing.entity.CostingTemplate;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CostingSheetService {

    private static final Logger LOG = Logger.getLogger(CostingSheetService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Excel 模板 FORMULA 列里 `[col_key]` 的引用模式 */
    private static final Pattern COL_REF_PATTERN = Pattern.compile("\\[([A-Za-z][A-Za-z0-9_]*)\\]");

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    public CostingSheetDTO getByQuotation(UUID quotationId) {
        CostingSheet s = CostingSheet.find("quotationId = ?1", quotationId).firstResult();
        if (s == null) {
            // 反 AP-12（懒资源硬 404）：DRAFT 报价单尚未生成核价表行很常见，
            // 不应让前端 viewMode=costing 切换时整页报错。返回空骨架 DTO，让 UI 显示
            // "尚未配置核价表"或初始化按钮。同模块 buildComparison 已经是空 DTO 兜底。
            CostingSheetDTO empty = new CostingSheetDTO();
            empty.quotationId = quotationId;
            empty.rows = List.of();
            empty.columns = List.of();
            return empty;
        }
        return CostingSheetDTO.from(s);
    }

    /**
     * 比对视图：实时按 Excel 模板对每个 lineItem 求值, 按列的 comparison_tag 聚合.
     *
     * V115 重写: 不再依赖已废弃的 costing_sheet 表 (V96 改用 lineItem 动态求值后没人维护此表).
     * 新流程:
     *   1. 反查关联的 costing 端 Excel 模板 (quotation.costing_card_template_id → costing_template)
     *   2. 遍历 quotation_line_item × Excel 模板 columns, 实时求值 (BNF path + FORMULA col_key 引用)
     *   3. 按 col.comparison_tag 把所有 lineItem 的 cell 值 SUM 起来 → costingByTag
     *   4. 报价端: 暂从 quotation.totalAmount 拿 UNIT_TOTAL_COST/TOTAL (后续可扩展按维度数据源)
     *   5. 按 tag 在 comparison_tag 表的 group_name / sort_order 分组排序输出
     */
    @SuppressWarnings("unchecked")
    public ComparisonDTO buildComparison(UUID quotationId) {
        ComparisonDTO result = new ComparisonDTO();
        result.basicFieldDiffs = new ArrayList<>();
        result.tagGroups = new ArrayList<>();
        result.summary = new ComparisonDTO.BigDecimalSummary();

        Quotation q = Quotation.findById(quotationId);
        if (q == null) return result;

        // 反查关联的核价端 Excel 模板. 优先 costing_card_template_id, 否则用 customer_template_id 作 fallback
        UUID linkedTplId = q.costingCardTemplateId != null ? q.costingCardTemplateId : q.customerTemplateId;
        CostingTemplate ct = null;
        if (linkedTplId != null) {
            ct = CostingTemplate.<CostingTemplate>find(
                    "linkedTemplateId = ?1 AND status = 'PUBLISHED' ORDER BY isDefault DESC, updatedAt DESC",
                    linkedTplId).firstResult();
        }
        if (ct == null) {
            // 没绑定 Excel 模板, 比对视图为空骨架 (前端会显示 0 条)
            return result;
        }

        List<Map<String, Object>> ctCols = parseList(ct.columns);
        if (ctCols.isEmpty()) return result;

        // 加载所有标签信息 (含 V114 注册的 13 个新 tag)
        Map<String, String> tagLabelMap = new LinkedHashMap<>();
        Map<String, String> tagToGroup = new LinkedHashMap<>();
        Map<String, Integer> tagGroupOrder = new HashMap<>();
        Map<String, Integer> tagOrder = new HashMap<>();
        for (ComparisonTag t : ComparisonTag.<ComparisonTag>list("status = 'ACTIVE'")) {
            tagLabelMap.put(t.code, t.label);
            tagToGroup.put(t.code, t.groupName);
            tagGroupOrder.put(t.code, t.groupSortOrder == null ? 999 : t.groupSortOrder);
            tagOrder.put(t.code, t.tagSortOrder == null ? 999 : t.tagSortOrder);
        }

        // 拿 lineItems
        List<QuotationLineItem> lineItems = QuotationLineItem.list(
                "quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        UUID customerId = q.customerId;

        // 每个 lineItem × 每列 求值, 然后按 tag SUM
        Map<String, BigDecimal> costingByTag = new LinkedHashMap<>();
        for (QuotationLineItem li : lineItems) {
            String partNo = resolvePartNo(li);
            if (partNo == null || partNo.isBlank()) continue;

            // pass 1: VARIABLE 列求值
            Map<String, Object> cellValues = new LinkedHashMap<>();
            for (Map<String, Object> col : ctCols) {
                if (!"VARIABLE".equals(col.get("source_type"))) continue;
                String colKey = (String) col.get("col_key");
                String varPath = (String) col.get("variable_path");
                if (varPath == null || varPath.isBlank()) {
                    cellValues.put(colKey, null);
                    continue;
                }
                String trimmed = varPath.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")
                        && trimmed.length() > 2 && !trimmed.substring(1, trimmed.length() - 1).contains(".")) {
                    // legacy `{HF_PART_NO}` 占位 — 比对视图不关心字符串元数据, 跳过
                    cellValues.put(colKey, null);
                    continue;
                }
                cellValues.put(colKey, evaluateBnfPath(trimmed, partNo, customerId));
            }
            // pass 2: FORMULA 列引用同行其他列计算
            for (Map<String, Object> col : ctCols) {
                if (!"FORMULA".equals(col.get("source_type"))) continue;
                String colKey = (String) col.get("col_key");
                String formula = (String) col.get("formula");
                cellValues.put(colKey, evaluateInlineFormula(formula, cellValues));
            }
            // pass 3: 累加到 tag
            for (Map<String, Object> col : ctCols) {
                String tag = (String) col.get("comparison_tag");
                if (tag == null) continue;
                String colKey = (String) col.get("col_key");
                BigDecimal n = toDecimal(cellValues.get(colKey));
                if (n != null) {
                    costingByTag.merge(tag, n, BigDecimal::add);
                }
            }
        }

        // 客户报价端: 暂仅 UNIT_TOTAL_COST/TOTAL 用 totalAmount; 后续按维度数据源接入
        Map<String, BigDecimal> quotationByTag = new LinkedHashMap<>();
        if (q.totalAmount != null) {
            quotationByTag.put("UNIT_TOTAL_COST", q.totalAmount);
            quotationByTag.put("TOTAL", q.totalAmount);
        }

        // 构建分组比对 — 按 tag_sort_order 在组内排序; 按 group_sort_order 跨组排序
        List<String> allTagsSorted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t : costingByTag.keySet()) if (seen.add(t)) allTagsSorted.add(t);
        for (String t : quotationByTag.keySet()) if (seen.add(t)) allTagsSorted.add(t);
        // 排序: group_sort_order 升序, tag_sort_order 升序
        allTagsSorted.sort(Comparator
                .<String>comparingInt(t -> tagGroupOrder.getOrDefault(t, 999))
                .thenComparingInt(t -> tagOrder.getOrDefault(t, 999))
                .thenComparing(t -> t));

        Map<String, List<ComparisonDTO.TagDiff>> grouped = new LinkedHashMap<>();
        for (String tag : allTagsSorted) {
            ComparisonDTO.TagDiff td = new ComparisonDTO.TagDiff();
            td.tag = tag;
            td.tagLabel = tagLabelMap.getOrDefault(tag, tag);
            td.costingValue = costingByTag.get(tag);
            td.quotationValue = quotationByTag.get(tag);
            BigDecimal cV = toDecimal(td.costingValue);
            BigDecimal qV = toDecimal(td.quotationValue);
            if (cV != null && qV != null) {
                BigDecimal delta = qV.subtract(cV);
                td.delta = delta;
                if (cV.signum() != 0) {
                    BigDecimal pct = delta.multiply(BigDecimal.valueOf(100))
                            .divide(cV, 2, RoundingMode.HALF_UP);
                    td.deltaPct = pct + "%";
                }
            }
            grouped.computeIfAbsent(tagToGroup.getOrDefault(tag, "其他"), k -> new ArrayList<>()).add(td);
        }
        for (var e : grouped.entrySet()) {
            ComparisonDTO.TagGroup g = new ComparisonDTO.TagGroup();
            g.groupName = e.getKey();
            g.tags = e.getValue();
            result.tagGroups.add(g);
        }

        // Summary：毛利分析
        BigDecimal totalCosting = costingByTag.getOrDefault("UNIT_TOTAL_COST", BigDecimal.ZERO);
        BigDecimal totalQuotation = quotationByTag.getOrDefault("UNIT_TOTAL_COST", BigDecimal.ZERO);
        result.summary.costingTotal = totalCosting;
        result.summary.quotationTotal = totalQuotation;
        result.summary.profit = totalQuotation.subtract(totalCosting);
        if (totalQuotation.signum() != 0) {
            result.summary.profitRate = totalQuotation.subtract(totalCosting)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalQuotation, 2, RoundingMode.HALF_UP) + "%";
        }
        // 修改数（从 line component data 中读 _modified_fields，简化为 0）
        result.summary.modifiedFieldsCount = 0;

        return result;
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * V115: 取 lineItem 关联的料号 (优先 product_part_no_snapshot, 否则按 product_id 反查 product 表).
     */
    private String resolvePartNo(QuotationLineItem li) {
        if (li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
            return li.productPartNoSnapshot;
        }
        if (li.productId != null) {
            try {
                Object p = com.cpq.product.entity.Product.findById(li.productId);
                if (p instanceof com.cpq.product.entity.Product prod) {
                    return prod.partNo;
                }
            } catch (Exception ignore) {}
        }
        return null;
    }

    /**
     * V115: 用 FormulaEngine 求 BNF 路径单值. partNo / customerId 注入做隐式 JOIN.
     */
    private BigDecimal evaluateBnfPath(String path, String partNo, UUID customerId) {
        try {
            EvaluationContext.Builder builder = EvaluationContext.builder().dataLoader(dataLoader);
            if (partNo != null) builder.partNo(partNo);
            if (customerId != null) builder.customerId(customerId);
            String expr = path.startsWith("{") ? path : ("{" + path + "}");
            Object result = formulaEngine.evaluate(expr, builder.build());
            if (result instanceof FormulaError) return null;
            return toDecimal(result);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * V115: 求 FORMULA 列的值. formula 形如 `=[B_PURE]+[B_PROC]`, 把 [X] 替换为 cellValues 里的数字,
     * 然后用 JEXL 算. 跟前端 LinkedExcelView.evaluateFormula 行为对齐 (NaN 替换为 0).
     */
    private BigDecimal evaluateInlineFormula(String formula, Map<String, Object> cellValues) {
        if (formula == null || formula.isBlank()) return null;
        String expr = formula.trim();
        if (expr.startsWith("=")) expr = expr.substring(1);
        Matcher m = COL_REF_PATTERN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            BigDecimal v = toDecimal(cellValues.get(m.group(1)));
            m.appendReplacement(sb, v == null ? "0" : v.toPlainString());
        }
        m.appendTail(sb);
        String resolved = sb.toString();
        // 安全校验: 只允许数字 + 运算符 + 括号 + 空白
        if (!resolved.matches("[\\d+\\-*/().\\s]*")) {
            return null;
        }
        try {
            org.apache.commons.jexl3.JexlEngine jexl = new org.apache.commons.jexl3.JexlBuilder().strict(false).silent(true).create();
            Object r = jexl.createExpression(resolved).evaluate(new org.apache.commons.jexl3.MapContext());
            if (r instanceof Number n) return new BigDecimal(n.toString());
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
