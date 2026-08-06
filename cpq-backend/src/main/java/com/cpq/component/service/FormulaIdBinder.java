package com.cpq.component.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.cpq.quotation.service.FormulaCalculator;

/**
 * BL-0098：公式绑定的 id 化工具（纯静态，无 CDI 依赖，可直接单测）。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link #ensureFormulaIds} —— 给公式对象补齐不可变 {@code id}（作用域 = 组件内，
 *       不需要全局唯一；组件复制 / 导入 bundle 原样带走）；</li>
 *   <li>{@link #bindFormulaIdsToFields} —— 把 FORMULA 字段的<b>隐式</b>绑定固化成显式
 *       {@code formula_id}（复用 {@link FormulaCalculator#resolveFormulaIdForField} 的求值期口径）；</li>
 *   <li>{@link #validateExplicitBinding} —— 固化后仍无绑定则拒绝保存，杜绝新增隐式配置。</li>
 * </ol>
 *
 * <p><b>为什么固化必须复用 FormulaCalculator</b>：{@code resolveFormula} 的回退链是求值期的
 * 唯一真相。固化逻辑若自己实现一遍，两处口径一旦漂移，固化结果就与实际算法不符 ——
 * 那正是 BL-0098 本身的问题在另一个层面重演。
 */
public final class FormulaIdBinder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FormulaCalculator CALC = new FormulaCalculator();

    private FormulaIdBinder() {}

    /** 给缺 id（含空白串）的公式对象补 UUID；已有 id 原样保留。 */
    public static void ensureFormulaIds(List<Map<String, Object>> formulas) {
        if (formulas == null) return;
        for (Map<String, Object> f : formulas) {
            if (f == null) continue;
            Object id = f.get("id");
            if (id == null || String.valueOf(id).isBlank()) {
                f.put("id", UUID.randomUUID().toString());
            }
        }
    }

    /**
     * 把每个 FORMULA 字段的绑定固化成 {@code formula_id}。
     *
     * <p>已有 {@code formula_id} 的字段跳过；解析不到公式的字段<b>不写</b>（不编造），
     * 由 {@link #validateExplicitBinding} 拦截并给出可读报错。
     * {@code formula_name} <b>保留</b>作展示冗余，不删除（存量兼容 + 排查时人能看懂）。
     *
     * <p><b>快照语义</b>：{@code fieldsNode}/{@code formulasNode} 在循环外一次性生成，
     * 循环内对 {@code fields} 的写入不会反馈到快照。这是<b>刻意</b>的 ——
     * 位置回退依据的是「本次保存进来的原始配置」，不能让前一个字段刚固化出的 {@code formula_id}
     * 改变后一个字段的解析结果（否则同一份输入按不同遍历顺序会得到不同绑定）。
     */
    public static void bindFormulaIdsToFields(List<Map<String, Object>> fields,
                                              List<Map<String, Object>> formulas) {
        if (fields == null || formulas == null) return;
        JsonNode fieldsNode = MAPPER.valueToTree(fields);
        JsonNode formulasNode = MAPPER.valueToTree(formulas);

        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            if (f == null || !isFormulaField(f)) continue;
            if (f.get("conditional_formula") != null) {
                // 条件公式字段不走字段级 formula_id，改为固化它内部的 rules[]/default 引用。
                bindConditionalRefs(f, formulas);
                continue;
            }
            Object existing = f.get("formula_id");
            if (existing != null && !String.valueOf(existing).isBlank()) continue;

            String id = CALC.resolveFormulaIdForField(fieldsNode.get(i), fieldsNode, formulasNode, null, i);
            if (id != null && !id.isBlank()) {
                f.put("formula_id", id);
            }
        }
    }

    /**
     * task-0805 B6：列出固化后仍未显式绑定的 FORMULA 字段名（条件公式豁免，理由同
     * {@link #validateExplicitBinding}）。{@link #validateExplicitBinding} 内部复用本方法
     * 生成报错清单——两处口径必须只有一份，不能各写一遍判断条件。
     *
     * <p>供 commit 端 {@code ignoreUnboundFormulas=true} 时降级为警告放行（不再直接
     * 400 拒绝），而不必用 {@code try/catch(IllegalArgumentException)} 吞异常——那样会
     * 连带吞掉同一循环里其它来源的 IAE。
     */
    public static List<String> listUnboundFormulaFields(List<Map<String, Object>> fields) {
        List<String> unbound = new ArrayList<>();
        if (fields == null) return unbound;
        for (Map<String, Object> f : fields) {
            if (f == null || !isFormulaField(f)) continue;
            if (f.get("conditional_formula") != null) continue;   // 条件公式豁免
            Object id = f.get("formula_id");
            if (id == null || String.valueOf(id).isBlank()) {
                unbound.add(String.valueOf(f.get("name")));
            }
        }
        return unbound;
    }

    /**
     * 校验每个 FORMULA 字段都已显式绑定。
     *
     * @throws IllegalArgumentException 存在未绑定字段时抛出，消息点名具体字段
     */
    public static void validateExplicitBinding(List<Map<String, Object>> fields) {
        List<String> unbound = listUnboundFormulaFields(fields);
        if (!unbound.isEmpty()) {
            throw new IllegalArgumentException(
                "以下公式字段未绑定公式，请在字段配置中显式选择：" + String.join("、", unbound)
                + "（BL-0098：系统不再按位置自动匹配公式）");
        }
    }

    /**
     * BL-0098：用 id 反查出的**当前**公式名，刷新各处的名字冗余。
     *
     * <p><b>为什么必须有这一步</b>：{@code formula_name} / {@code conditional_formula.rules[].formula}
     * / {@code default} 在绑 id 之后降级为展示冗余，但 {@link ComponentService} 的
     * {@code validateFormulas} 仍会校验「这些名字必须存在于公式列表」。用户在 UI 改一个公式名时，
     * 引用处的名字冗余不会跟着变 —— 若不刷新，保存会被那条校验以「绑定的公式 'X' 不存在」挡下，
     * 于是「绑 id 后改名不断链」在数据层成立、在 UI 上却根本改不了名。
     *
     * <p><b>必须在 {@code validateFormulas} 之前调用</b>，否则校验先看到陈旧名字就已经抛错了。
     *
     * <p><b>id 查不到时刻意不动名字</b>：那是「公式被删」的真错误，应当让既有校验报出来，
     * 而不是靠刷新名字把它掩盖掉。
     */
    public static void refreshNameRedundancyFromIds(List<Map<String, Object>> fields,
                                                    List<Map<String, Object>> formulas) {
        if (fields == null || formulas == null) return;
        for (Map<String, Object> f : fields) {
            if (f == null || !isFormulaField(f)) continue;
            Object cfRaw = f.get("conditional_formula");
            if (cfRaw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cf = (Map<String, Object>) cfRaw;
                Object rulesRaw = cf.get("rules");
                if (rulesRaw instanceof List<?> rules) {
                    for (Object rRaw : rules) {
                        if (rRaw instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> rule = (Map<String, Object>) rRaw;
                            refreshName(rule, "formula_id", "formula", formulas);
                        }
                    }
                }
                refreshName(cf, "default_formula_id", "default", formulas);
                continue;
            }
            refreshName(f, "formula_id", "formula_name", formulas);
        }
    }

    /** 按 {@code holder[idKey]} 反查公式，把它的当前名字写回 {@code holder[nameKey]}；查不到则不动。 */
    private static void refreshName(Map<String, Object> holder, String idKey, String nameKey,
                                    List<Map<String, Object>> formulas) {
        Object idRaw = holder.get(idKey);
        if (idRaw == null || String.valueOf(idRaw).isBlank()) return;
        String id = String.valueOf(idRaw);
        for (Map<String, Object> fm : formulas) {
            if (fm == null) continue;
            if (id.equals(String.valueOf(fm.get("id")))) {
                Object nm = fm.get("name");
                if (nm != null && !String.valueOf(nm).isBlank()) {
                    holder.put(nameKey, String.valueOf(nm));
                }
                return;
            }
        }
        // id 查不到 = 公式被删 → 保持原名字，交给 validateFormulas 报错（不掩盖真错误）
    }

    /**
     * BL-0098 补充范围：把条件公式内部的公式引用固化成 id。
     *
     * <p>条件公式结构 {@code {rules:[{when, formula, formula_id}], default, default_formula_id}}，
     * 历史上 {@code formula}/{@code default} 存的是公式<b>名</b> —— 公式一改名，
     * {@code FormulaCalculator} 查不到就<b>静默丢掉该规则/默认分支</b>（列还有值，只是悄悄换了分支，
     * 比普通字段整列不出值更难发现）。这里给每处引用补上 id，求值端优先按 id 认。
     */
    @SuppressWarnings("unchecked")
    private static void bindConditionalRefs(Map<String, Object> field, List<Map<String, Object>> formulas) {
        Object cfRaw = field.get("conditional_formula");
        if (!(cfRaw instanceof Map)) return;
        Map<String, Object> cf = (Map<String, Object>) cfRaw;

        Object rulesRaw = cf.get("rules");
        if (rulesRaw instanceof List<?> rules) {
            for (Object rRaw : rules) {
                if (rRaw instanceof Map) {
                    stampRef((Map<String, Object>) rRaw, "formula", "formula_id", formulas);
                }
            }
        }
        stampRef(cf, "default", "default_formula_id", formulas);
    }

    /**
     * 把 {@code holder[nameKey]} 指向的公式名解析成 id 写入 {@code holder[idKey]}。
     * 已有 id → 原样保留（id 是权威，不被名字覆盖）；名字解析不到 → 不写（不编造）。
     */
    private static void stampRef(Map<String, Object> holder, String nameKey, String idKey,
                                 List<Map<String, Object>> formulas) {
        Object existing = holder.get(idKey);
        if (existing != null && !String.valueOf(existing).isBlank()) return;
        Object nameRaw = holder.get(nameKey);
        if (nameRaw == null) return;
        String name = String.valueOf(nameRaw);
        if (name.isBlank()) return;
        for (Map<String, Object> fm : formulas) {
            if (fm == null) continue;
            if (name.equals(String.valueOf(fm.get("name")))) {
                Object id = fm.get("id");
                if (id != null && !String.valueOf(id).isBlank()) {
                    holder.put(idKey, String.valueOf(id));
                }
                return;
            }
        }
    }

    private static boolean isFormulaField(Map<String, Object> f) {
        Object t = f.get("field_type");
        if (t == null) t = f.get("fieldType");
        return "FORMULA".equals(t);
    }
}
