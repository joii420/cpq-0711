package com.cpq.component.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.FormulaCalculator.FormulaBindingInfo;

/**
 * task-0805（R1/R2）：公式绑定完整性的**只读**扫描器。
 *
 * <p>供导出（B3）与导入预览（B4）复用同一份检查逻辑，产出「某 FORMULA 字段/条件公式引用
 * 将绑到哪条公式」的清单——供导出端提前暴露、导入预览端逐字段展示去向，而不是像 commit
 * 那样直到提交才用 400 拒绝（见需求文档 §1）。
 *
 * <p><b>三条铁律（本类必须遵守）</b>：
 * <ol>
 *   <li><b>只读就是只读</b>：入口第一件事就是对入参 {@code fields}/{@code formulas} 深拷贝，
 *       之后所有处理（含为求解析口径一致而调用的 {@link FormulaIdBinder#ensureFormulaIds}）
 *       只作用于深拷贝，绝不回写调用方对象、绝不写库。</li>
 *   <li><b>解析口径只有一份</b>：绑定去向完全委派给
 *       {@link FormulaCalculator#inspectFormulaBindingForField} /
 *       {@link FormulaCalculator#inspectConditionalRuleBinding} /
 *       {@link FormulaCalculator#inspectConditionalDefaultBinding}（求值期唯一真相），
 *       本类不自行判断「绑没绑上」，只负责把解析结果转成展示用的 status/message。</li>
 *   <li><b>先补 id 再解析</b>：老 bundle 里公式普遍没有 {@code id}（实测 31 条公式 0 条带 id），
 *       若不先在深拷贝上跑一遍 {@link FormulaIdBinder#ensureFormulaIds}，靠名字命中的绑定会
     *       算出 {@code resolvedFormulaId=null}——这与「导入后实际会写入的 formula_id」不符。
     *       这一步与 {@link FormulaIdBinder#bindFormulaIdsToFields} 内部的补 id 时机完全一致
     *       （先 ensureFormulaIds，再用同一份 id 化后的 formulas 快照做解析）。</li>
 * </ol>
 */
public final class FormulaBindingInspector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FormulaCalculator CALC = new FormulaCalculator();

    private FormulaBindingInspector() {}

    /** 单组件扫描：componentCode/componentName 仅用于填充结果条目，供导出端汇总展示。 */
    public static Report inspect(String componentCode, String componentName, JsonNode fields, JsonNode formulas) {
        Report report = new Report();

        if (fields == null || fields.isNull() || !fields.isArray()) {
            return report; // 没有 fields 就不可能有 FORMULA 字段，空报告
        }

        // 铁律①：入口即深拷贝，后续任何处理都不触碰调用方原始对象。
        JsonNode fieldsCopy = fields.deepCopy();
        JsonNode formulasSrc = (formulas == null || formulas.isNull() || !formulas.isArray())
                ? MAPPER.createArrayNode() : formulas.deepCopy();

        // 铁律③：先在深拷贝上补 id（与 FormulaIdBinder.bindFormulaIdsToFields 同一时序），
        // 使「靠名字命中」的绑定也能给出真实会写入的 formula_id，而不是 null。
        List<Map<String, Object>> formulaList = toMapList(formulasSrc);
        FormulaIdBinder.ensureFormulaIds(formulaList);
        JsonNode idEnrichedFormulas = MAPPER.valueToTree(formulaList);

        int fullIdx = 0;
        for (JsonNode f : fieldsCopy) {
            if (!"FORMULA".equals(fieldTypeOf(f))) {
                fullIdx++;
                continue;
            }
            String name = fieldNameOf(f);
            JsonNode cf = conditionalFormulaOf(f);
            // task-0805 判定口径与 FormulaIdBinder 对齐(反馈修复)：只要 conditional_formula 存在
            // (非 null 且是 object) 就走条件公式分支——不额外要求 rules 一定是数组。
            // FormulaIdBinder.listUnboundFormulaFields/validateExplicitBinding/bindFormulaIdsToFields
            // 都只判 `f.get("conditional_formula") != null` 即豁免；本类之前多判了一层
            // `cf.path("rules").isArray()`，导致「有 conditional_formula 但缺 rules 数组
            // (如只配了 default)」的字段被当成普通 FORMULA 字段解析——commit 端放行、
            // preview 端却大概率报 UNRESOLVABLE 进 blockers，形成「预览比提交更严」的假阻断。
            if (cf != null && cf.isObject()) {
                // rules 缺失/不是数组 → 按空规则集处理，不报错、不出规则条目。
                JsonNode rules = cf.path("rules");
                if (rules.isArray()) {
                    int ruleIdx = 0;
                    for (JsonNode rule : rules) {
                        String existingId = idOf(rule, "formula_id", "formulaId");
                        FormulaBindingInfo info = CALC.inspectConditionalRuleBinding(f, idEnrichedFormulas, ruleIdx);
                        report.items.add(buildItem(componentCode, componentName,
                                name + " › 规则" + (ruleIdx + 1), info, existingId));
                        ruleIdx++;
                    }
                }
                // 默认分支：字段完全没配置默认(既无 default 名字也无 default_formula_id) → 不出条目
                // (不能把"没配"和"配了但解析不到"混为一谈，前者不是绑定问题)。
                if (hasAnyNonBlank(cf, "default", "default_formula_id", "defaultFormulaId")) {
                    String defExistingId = idOf(cf, "default_formula_id", "defaultFormulaId");
                    FormulaBindingInfo defInfo = CALC.inspectConditionalDefaultBinding(f, idEnrichedFormulas);
                    report.items.add(buildItem(componentCode, componentName,
                            name + " › 默认", defInfo, defExistingId));
                }
            } else {
                String existingId = idOf(f, "formula_id", "formulaId");
                FormulaBindingInfo info = CALC.inspectFormulaBindingForField(
                        f, fieldsCopy, idEnrichedFormulas, null, fullIdx);
                report.items.add(buildItem(componentCode, componentName, name, info, existingId));
            }
            fullIdx++;
        }

        report.totalFormulaRefs = report.items.size();
        report.unboundCount = (int) report.items.stream().filter(i -> "UNRESOLVABLE".equals(i.status)).count();
        return report;
    }

    /** 汇总多组件报告（导出端 B3 用：跨整个目录合并成一份 bindingReport）。 */
    public static Report merge(List<Report> reports) {
        Report merged = new Report();
        if (reports != null) {
            for (Report r : reports) {
                if (r == null || r.items == null) continue;
                merged.items.addAll(r.items);
            }
        }
        merged.totalFormulaRefs = merged.items.size();
        merged.unboundCount = (int) merged.items.stream().filter(i -> "UNRESOLVABLE".equals(i.status)).count();
        return merged;
    }

    private static Item buildItem(String componentCode, String componentName, String fieldName,
                                  FormulaBindingInfo info, String existingId) {
        Item item = new Item();
        item.componentCode = componentCode;
        item.componentName = componentName;
        item.fieldName = fieldName;
        if (info != null) {
            item.resolvedFormulaId = info.id;
            item.resolvedFormulaName = info.name;
            item.status = statusForOrigin(info.origin);
            item.message = null;
        } else {
            item.status = "UNRESOLVABLE";
            item.message = existingId != null
                    ? "绑定的公式已不存在（id=" + existingId + "）"
                    : "未绑定公式，且无法按名称/位置推导";
        }
        return item;
    }

    /** §2.1 status 判定表（唯一口径）：BY_ID→BOUND；BY_NAME/BY_ASSIGNMENT/BY_FIELD_NAME→RESOLVED_BY_NAME；BY_POSITION→RESOLVED_BY_POSITION。 */
    private static String statusForOrigin(String origin) {
        if ("BY_ID".equals(origin)) return "BOUND";
        if ("BY_POSITION".equals(origin)) return "RESOLVED_BY_POSITION";
        return "RESOLVED_BY_NAME"; // BY_NAME / BY_ASSIGNMENT / BY_FIELD_NAME
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) return new ArrayList<>();
        return MAPPER.convertValue(arrayNode, new TypeReference<List<Map<String, Object>>>() {});
    }

    private static String idOf(JsonNode holder, String snakeKey, String camelKey) {
        String v = holder.has(snakeKey) ? holder.path(snakeKey).asText(null) : holder.path(camelKey).asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    /** 是否至少有一个 key 在 holder 上取到非空白文本值。用于判断「条件公式默认分支到底有没有配置」。 */
    private static boolean hasAnyNonBlank(JsonNode holder, String... keys) {
        for (String k : keys) {
            String v = holder.path(k).asText(null);
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    private static String fieldTypeOf(JsonNode f) {
        if (f.has("fieldType")) return f.path("fieldType").asText("");
        return f.path("field_type").asText("");
    }

    private static String fieldNameOf(JsonNode f) {
        String n = f.path("name").asText("");
        if (!n.isEmpty()) return n;
        return f.path("key").asText("");
    }

    private static JsonNode conditionalFormulaOf(JsonNode f) {
        return f.has("conditional_formula") ? f.path("conditional_formula") : f.path("conditionalFormula");
    }

    /** 单/多组件扫描结果。 */
    public static final class Report {
        public int unboundCount;
        public int totalFormulaRefs;
        public List<Item> items = new ArrayList<>();
    }

    /** 单条绑定检查结果（§2.1 bindingReport.items 同构）。 */
    public static final class Item {
        public String componentCode;
        public String componentName;
        public String fieldName;
        public String resolvedFormulaId;
        public String resolvedFormulaName;
        /** BOUND | RESOLVED_BY_NAME | RESOLVED_BY_POSITION | UNRESOLVABLE */
        public String status;
        public String message;
    }
}
