package com.cpq.formula;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** CondTree 求值器（镜像前端 condTree.ts）。Plan 3a。 */
public final class CondTreeEvaluator {

    private CondTreeEvaluator() {}

    /** 空树/null → true（默认分支）；异常 → false（保守不命中）。 */
    public static boolean eval(JsonNode tree, Function<String, Object> lookup) {
        if (tree == null || tree.isNull() || tree.isMissingNode()) return true;
        try {
            return evalNode(tree, lookup);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean evalNode(JsonNode t, Function<String, Object> lookup) {
        String kind = t.path("kind").asText("");
        if ("group".equals(kind)) {
            boolean and = !"or".equals(t.path("logic").asText("and"));
            JsonNode children = t.path("children");
            if (!children.isArray() || children.size() == 0) return and;
            if (and) {
                for (JsonNode c : children) if (!evalNode(c, lookup)) return false;
                return true;
            } else {
                for (JsonNode c : children) if (evalNode(c, lookup)) return true;
                return false;
            }
        }
        // leaf
        String op = t.path("op").asText("eq");
        Object L = lookup.apply(t.path("left").asText(""));
        JsonNode rhs = t.path("rhs");
        Object R = "column".equals(rhs.path("type").asText("literal"))
            ? lookup.apply(rhs.path("value").asText("")) : rhs.path("value").asText("");
        return cmp(op, L, R);
    }

    private static boolean cmp(String op, Object L, Object R) {
        if ("in".equals(op)) {
            if (L == null) return false;
            List<String> set = new ArrayList<>();
            for (String s : String.valueOf(R == null ? "" : R).split(",")) set.add(s.trim());
            return set.contains(String.valueOf(L).trim());
        }
        Double ln = toNum(L), rn = toNum(R);
        switch (op) {
            case "gt": case "gte": case "lt": case "lte":
                if (ln == null || rn == null) return false;
                if ("gt".equals(op)) return ln > rn;
                if ("gte".equals(op)) return ln >= rn;
                if ("lt".equals(op)) return ln < rn;
                return ln <= rn;
            default: // eq / ne
                boolean eq = (ln != null && rn != null)
                    ? ln.doubleValue() == rn.doubleValue()
                    : String.valueOf(L == null ? "" : L).equals(String.valueOf(R == null ? "" : R));
                return "eq".equals(op) ? eq : !eq;
        }
    }

    private static Double toNum(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    /** 收集条件树引用列名（leaf.left + column 型 rhs）。 */
    public static List<String> columns(JsonNode tree) {
        List<String> out = new ArrayList<>();
        walk(tree, out);
        return out;
    }

    private static void walk(JsonNode t, List<String> out) {
        if (t == null || !t.isObject()) return;
        if ("group".equals(t.path("kind").asText(""))) {
            for (JsonNode c : t.path("children")) walk(c, out);
        } else {
            out.add(t.path("left").asText(""));
            JsonNode rhs = t.path("rhs");
            if ("column".equals(rhs.path("type").asText(""))) out.add(rhs.path("value").asText(""));
        }
    }
}
