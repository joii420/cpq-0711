package com.cpq.formula.predicate;

import java.util.Map;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/** predicate 求值：(arow 源行, hostRow 宿主行) → boolean。语义见 plan「求值语义」。 */
public class ConditionPredicateEvaluator {

    public boolean test(ConditionPredicate p, Map<String,Object> arow, Map<String,Object> hostRow) {
        if (p == null) return true;
        if (p instanceof Bool b) {
            if (b.op() == BoolOp.AND) {
                for (ConditionPredicate c : b.children()) if (!test(c, arow, hostRow)) return false;
                return true;
            } else {
                for (ConditionPredicate c : b.children()) if (test(c, arow, hostRow)) return true;
                return false;
            }
        }
        Comparison c = (Comparison) p;
        Object lv = resolve(c.lhs(), arow, hostRow);
        Object rv = resolve(c.rhs(), arow, hostRow);
        return switch (c.op()) {
            case EQ -> valEquals(lv, rv);
            case NE -> !valEquals(lv, rv);
            case GT -> { Integer c2 = cmp(lv, rv); yield c2 != null && c2 > 0; }
            case LT -> { Integer c2 = cmp(lv, rv); yield c2 != null && c2 < 0; }
            case GE -> { Integer c2 = cmp(lv, rv); yield c2 != null && c2 >= 0; }
            case LE -> { Integer c2 = cmp(lv, rv); yield c2 != null && c2 <= 0; }
        };
    }

    private Object resolve(Operand o, Map<String,Object> arow, Map<String,Object> hostRow) {
        if (o instanceof SourceField s) return arow == null ? null : arow.get(s.field());
        if (o instanceof HostField h)   return hostRow == null ? null : hostRow.get(h.field());
        return ((Literal) o).value();
    }

    private static boolean isBlank(Object o) {
        return o == null || (o instanceof String s && s.isBlank())
                || (!(o instanceof String) && String.valueOf(o).isBlank());
    }

    private static Double num(Object o) {
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    /** 相等口径：任一 blank → false（见 spec 求值语义）；两边可解析为数 → 数值比较；否则 trim 文本比较。 */
    private boolean valEquals(Object a, Object b) {
        if (isBlank(a) || isBlank(b)) return false;
        Double na = num(a), nb = num(b);
        if (na != null && nb != null) return na.doubleValue() == nb.doubleValue();
        return String.valueOf(a).trim().equals(String.valueOf(b).trim());
    }

    /** 数值比较；任一不可解析/blank → null（调用方据此判 false）。 */
    private Integer cmp(Object a, Object b) {
        if (isBlank(a) || isBlank(b)) return null;
        Double na = num(a), nb = num(b);
        if (na == null || nb == null) return null;
        return Double.compare(na, nb);
    }
}
