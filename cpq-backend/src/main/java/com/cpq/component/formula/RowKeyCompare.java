package com.cpq.component.formula;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 行键集合包含判定（顺序无关），前后端同语义（镜像 formulaSerialize.ts comparable/isSubset）。
 *
 * <p>用于判断两个页签的行键集合是否可比较（一方是另一方的子集），
 * 从而决定跨页签 cross_tab_ref 能否按行键对齐求值。
 */
public final class RowKeyCompare {
    private RowKeyCompare() {}

    /** 判断 sub 是否是 sup 的子集（顺序无关）。 */
    public static boolean isSubset(List<String> sub, List<String> sup) {
        Set<String> s = new HashSet<>(sup);
        return sub.stream().allMatch(s::contains);
    }

    /** 判断 a 和 b 是否可比较（a ⊆ b 或 b ⊆ a）。 */
    public static boolean comparable(List<String> a, List<String> b) {
        return isSubset(a, b) || isSubset(b, a);
    }
}
