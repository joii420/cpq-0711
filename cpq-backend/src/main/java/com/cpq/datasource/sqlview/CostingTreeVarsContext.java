package com.cpq.datasource.sqlview;

import java.util.List;

/** 新核价管线的两个料号数组变量(线程级)。取代 SpineKeysContext。 */
public final class CostingTreeVarsContext {
    public static final class Vars {
        public final List<String> productionPartNos; // :production_part_nos(递归 SQL 用)
        public final List<String> totalMaterialNo;   // :total_material_no(页签 SQL 用)
        public Vars(List<String> productionPartNos, List<String> totalMaterialNo) {
            this.productionPartNos = productionPartNos; this.totalMaterialNo = totalMaterialNo;
        }
    }
    private static final ThreadLocal<Vars> TL = new ThreadLocal<>();
    public static void set(Vars v) { TL.set(v); }
    public static Vars get() { return TL.get(); }
    public static void clear() { TL.remove(); }
    private CostingTreeVarsContext() {}
}
