package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Excel CARD_FORMULA 列编排：列依赖拓扑排序 + 环检测（求值编排在 Task 6 注入依赖完成）。 */
public class CardFormulaEvaluator {

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\[\\]]+)]");

    /** 提取公式里"裸 col_key"（不含 . 的占位）作为列间依赖。 */
    static Set<String> columnDeps(String formula, Set<String> allCols) {
        Set<String> deps = new LinkedHashSet<>();
        if (formula == null) return deps;
        Matcher m = BRACKET.matcher(formula);
        while (m.find()) {
            String ref = m.group(1).trim();
            if (!ref.contains(".") && allCols.contains(ref)) deps.add(ref);
        }
        return deps;
    }

    /** Kahn 拓扑排序；存在环抛 BusinessException。入参: col_key → formula。 */
    public static List<String> topoOrder(Map<String, String> formulas) {
        Set<String> cols = formulas.keySet();
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String c : cols) deps.put(c, columnDeps(formulas.get(c), cols));
        for (String c : cols) indeg.put(c, deps.get(c).size()); // 入度 = 本列依赖数
        Deque<String> q = new ArrayDeque<>();
        for (String c : cols) if (indeg.get(c) == 0) q.add(c);
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            order.add(c);
            for (String other : cols) {
                if (deps.get(other).contains(c)) {
                    indeg.put(other, indeg.get(other) - 1);
                    if (indeg.get(other) == 0) q.add(other);
                }
            }
        }
        if (order.size() != cols.size()) {
            Set<String> cyc = new LinkedHashSet<>(cols);
            cyc.removeAll(order);
            throw new BusinessException(400, "Excel 列公式存在循环引用: " + cyc);
        }
        return order;
    }
}
