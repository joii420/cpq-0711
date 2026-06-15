package com.cpq.formula.predicate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/** JsonNode ↔ ConditionPredicate。null/缺省 → null predicate（不过滤）。 */
public final class ConditionPredicateJson {
    private ConditionPredicateJson() {}

    public static ConditionPredicate fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.has("bool")) {
            BoolOp op = BoolOp.valueOf(node.get("bool").asText().toUpperCase());
            List<ConditionPredicate> ch = new ArrayList<>();
            JsonNode children = node.path("children");
            if (children.isArray()) for (JsonNode c : children) {
                ConditionPredicate cp = fromJson(c);
                if (cp != null) ch.add(cp);
            }
            return new Bool(op, ch);
        }
        if (node.has("op")) {
            return new Comparison(
                CmpOp.from(node.get("op").asText()),
                operand(node.path("lhs")),
                operand(node.path("rhs")));
        }
        return null;
    }

    private static Operand operand(JsonNode n) {
        String kind = n.path("kind").asText("");
        return switch (kind) {
            case "sourceField" -> new SourceField(n.path("field").asText(""));
            case "hostField"   -> new HostField(n.path("field").asText(""));
            case "literal"     -> new Literal(n.path("value").asText(""));
            default -> throw new IllegalArgumentException("未知 operand kind: " + kind);
        };
    }
}
