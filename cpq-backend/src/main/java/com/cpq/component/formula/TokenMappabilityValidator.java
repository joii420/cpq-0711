package com.cpq.component.formula;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class TokenMappabilityValidator {

    public record Result(boolean mappable, String reason) {}

    /**
     * KSUM 子 token（{@code projectToHostKey=true}）内 targetExpr 允许的 token type 白名单。
     *
     * <p>包含 {@code path}，因为前端全局变量 token 的实际 type 是 {@code path}（不是 {@code global_variable}）。
     * 拒：{@code b_field}（宿主列）/ {@code component_subtotal} / {@code quotation_field} /
     *     {@code product_attribute} / {@code previous_row_subtotal}（I1）/ 嵌套 {@code cross_tab_ref}（J）。
     */
    private static final Set<String> KSUM_INNER_ALLOWED_TYPES = Set.of(
        "field", "operator", "number", "bracket_open", "bracket_close", "path"
    );

    /**
     * 判定一条页签/小计公式 token 数组是否可落进宿主行键分组模型（v4-C 命门1）。
     *
     * <p><b>空 match 规则</b>：
     * <ul>
     *   <li>顶层 {@code cross_tab_ref}（非 KSUM 容器/多 source）的 match 为空 → 拒绝。</li>
     *   <li>豁免（外层容器合法 match=[]）：
     *     <ol>
     *       <li>token 含 {@code sources} 长度≥2（多 source 外层）→ 豁免。</li>
     *       <li>token 的 {@code targetExpr} 内含有 {@code projectToHostKey=true} 子 token（KSUM 容器）→ 豁免。</li>
     *     </ol>
     *   </li>
     *   <li>KSUM 子 token 自身（{@code projectToHostKey=true}）的 match 必须非空 —— 无豁免。</li>
     * </ul>
     *
     * <p><b>顶层裸 projectToHostKey（M）</b>：顶层直接出现 {@code projectToHostKey=true} 的 cross_tab_ref → 拒绝。
     *
     * <p><b>KSUM 子 token 镜像校验</b>：对每个 {@code projectToHostKey=true} 子 token：
     * <ol>
     *   <li>match 必须非空（无豁免）。</li>
     *   <li>targetExpr 内所有 field token 的 {@code source} 必须等于子 token 的 {@code source}（禁止跨页签）。</li>
     *   <li>targetExpr 内 token type 只允许白名单类型（{@code field/operator/number/bracket_open/bracket_close/path}）。</li>
     *   <li>targetExpr 内禁止出现 {@code cross_tab_ref}（含再嵌套 projectToHostKey，J 规则）。</li>
     *   <li>I2：顶层公式中已引用的页签（外层 cross_tab_ref source）与 KSUM 子 token 的 source 不得重叠。</li>
     * </ol>
     */
    public Result validate(List<Map<String, Object>> expr) {
        // 收集外层裸 cross_tab_ref 引用的页签 source（用于 I2 检测）
        java.util.Set<String> outerSources = new java.util.HashSet<>();
        for (Map<String, Object> t : expr) {
            if ("cross_tab_ref".equals(t.get("type"))) {
                Boolean proj = (Boolean) t.get("projectToHostKey");
                if (!Boolean.TRUE.equals(proj)) {
                    Object src = t.get("source");
                    if (src instanceof String s && !s.isBlank()) {
                        outerSources.add(s);
                    }
                }
            }
        }

        for (Map<String, Object> t : expr) {
            if (!"cross_tab_ref".equals(t.get("type"))) continue;

            Boolean proj = (Boolean) t.get("projectToHostKey");

            // M: 顶层裸 projectToHostKey=true → 拒绝
            if (Boolean.TRUE.equals(proj)) {
                return new Result(false,
                    "公式顶层存在 projectToHostKey=true 的 KSUM 子引用（M 规则），" +
                    "KSUM 子 token 只能嵌套在外层 cross_tab_ref.targetExpr 内，不可裸出现在公式顶层。");
            }

            Object matchVal = t.get("match");
            boolean emptyMatch = !(matchVal instanceof List) || ((List<?>) matchVal).isEmpty();

            // 检查是否为 KSUM 容器（targetExpr 内含 projectToHostKey=true 子 token）
            boolean isKsumContainer = targetExprContainsKsumSubToken(t);
            // 检查是否为多 source 外层（sources.length >= 2）
            boolean isMultiSource = isMultiSourceToken(t);

            if (emptyMatch && !isKsumContainer && !isMultiSource) {
                // 普通空 match → 拒绝
                return new Result(false,
                    "存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。" +
                    "请改引可比页签或用其整页签小计 [页签(总计)]。");
            }

            // SUMIF 族：predicate 字段存在时，结构必须可解析（复用模型转换做结构校验）
            Object pred = t.get("predicate");
            if (pred != null) {
                try {
                    com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(pred));
                } catch (Exception e) {
                    return new Result(false, "cross_tab_ref.predicate 结构非法: " + e.getMessage());
                }
            }

            // 对 targetExpr 内的 KSUM 子 token 做镜像校验
            Object teObj = t.get("targetExpr");
            if (teObj instanceof List<?> teList) {
                for (Object innerObj : teList) {
                    if (!(innerObj instanceof Map<?, ?> rawInner)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = (Map<String, Object>) rawInner;
                    if (!"cross_tab_ref".equals(inner.get("type"))) continue;

                    Boolean innerProj = (Boolean) inner.get("projectToHostKey");
                    if (!Boolean.TRUE.equals(innerProj)) {
                        // J: targetExpr 内出现非 projectToHostKey 的 cross_tab_ref → 拒绝（J 规则）
                        return new Result(false,
                            "KSUM targetExpr 内出现嵌套 cross_tab_ref（J 规则），" +
                            "不允许嵌套跨页签引用。");
                    }

                    // --- 以下对 KSUM 子 token 做镜像校验 ---
                    String innerSource = inner.get("source") instanceof String s ? s : "";

                    // KSUM 子 token match 必须非空
                    Object innerMatch = inner.get("match");
                    boolean innerEmptyMatch = !(innerMatch instanceof List) || ((List<?>) innerMatch).isEmpty();
                    if (innerEmptyMatch) {
                        return new Result(false,
                            "KSUM 子引用（projectToHostKey=true）的 match 为空（无宿主行键约束），" +
                            "无法按宿主键塌缩。请为 KSUM 子引用指定行键对应关系。");
                    }

                    // I2: KSUM 子 token 的 source 不得与外层裸引用 source 重叠
                    if (!innerSource.isBlank() && outerSources.contains(innerSource)) {
                        return new Result(false,
                            "KSUM 子引用的 source（" + innerSource + "）与外层裸 cross_tab_ref 引用的页签重叠（I2 规则），" +
                            "不可在同一公式中既裸引用又 KSUM 聚合同一页签。");
                    }

                    // targetExpr 内对 KSUM 子 token 自身的 targetExpr 做白名单校验
                    Object subTeObj = inner.get("targetExpr");
                    if (subTeObj instanceof List<?> subTeList) {
                        for (Object subObj : subTeList) {
                            if (!(subObj instanceof Map<?, ?> rawSub)) continue;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> subToken = (Map<String, Object>) rawSub;
                            String subType = subToken.get("type") instanceof String st ? st : "";

                            // J: 嵌套 cross_tab_ref → 拒绝
                            if ("cross_tab_ref".equals(subType)) {
                                return new Result(false,
                                    "KSUM 子引用的 targetExpr 内出现 cross_tab_ref（J 规则），" +
                                    "不允许 KSUM 套 KSUM 或 KSUM 内嵌跨页签引用。");
                            }

                            // 白名单校验
                            if (!subType.isEmpty() && !KSUM_INNER_ALLOWED_TYPES.contains(subType)) {
                                return new Result(false,
                                    "KSUM 子引用的 targetExpr 内出现不允许的 token 类型「" + subType + "」" +
                                    "（白名单：field/operator/number/bracket_open/bracket_close/path）。");
                            }

                            // field token 的 source 必须等于 KSUM 子 token 的 source（禁止跨页签）
                            if ("field".equals(subType)) {
                                Object fieldSource = subToken.get("source");
                                if (fieldSource instanceof String fs && !fs.isBlank()
                                        && !innerSource.isBlank() && !innerSource.equals(fs)) {
                                    return new Result(false,
                                        "KSUM 子引用的 targetExpr 内 field token 的 source（" + fs + "）" +
                                        "与子 token source（" + innerSource + "）不一致（禁止跨页签）。");
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Result(true, null);
    }

    /**
     * 检查 token 的 targetExpr 内是否含有 projectToHostKey=true 的子 token（KSUM 容器判断）。
     */
    private boolean targetExprContainsKsumSubToken(Map<String, Object> token) {
        Object teObj = token.get("targetExpr");
        if (!(teObj instanceof List<?> teList)) return false;
        for (Object item : teList) {
            if (!(item instanceof Map<?, ?> rawItem)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> inner = (Map<String, Object>) rawItem;
            if ("cross_tab_ref".equals(inner.get("type"))
                    && Boolean.TRUE.equals(inner.get("projectToHostKey"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 token 是否为多 source 外层（sources 字段为 List 且长度>=2）。
     */
    private boolean isMultiSourceToken(Map<String, Object> token) {
        Object sourcesObj = token.get("sources");
        return sourcesObj instanceof List<?> list && list.size() >= 2;
    }
}
