package com.cpq.datasource.sqlview;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 新核价管线的两个料号数组变量(线程级)。取代 SpineKeysContext。
 * task-0713 B3 扩展：携带 {@code :versionFilter} 宏所需的 override map + 渲染/列出模式。 */
public final class CostingTreeVarsContext {

    /** RENDER=按 override 渲染指定版本(否则 is_current)；LIST=放开版本过滤(供下拉收集 distinct)。 */
    public enum Mode { RENDER, LIST }

    public static final class Vars {
        public final List<String> productionPartNos; // :production_part_nos(递归 SQL 用)
        public final List<String> totalMaterialNo;   // :total_material_no(页签 SQL 用)
        /** componentId → (partNo → 目标 viewVersion)。null/缺失 componentId = 该组件无 override(等价 is_current)。 */
        public final Map<UUID, Map<String, String>> overridesByComponent;
        public final Mode mode;

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo) {
            this(productionPartNos, totalMaterialNo, null, Mode.RENDER);
        }

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent) {
            this(productionPartNos, totalMaterialNo, overridesByComponent, Mode.RENDER);
        }

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent, Mode mode) {
            this.productionPartNos = productionPartNos;
            this.totalMaterialNo = totalMaterialNo;
            this.overridesByComponent = overridesByComponent;
            this.mode = mode != null ? mode : Mode.RENDER;
        }
    }

    private static final ThreadLocal<Vars> TL = new ThreadLocal<>();
    public static void set(Vars v) { TL.set(v); }
    public static Vars get() { return TL.get(); }
    public static void clear() { TL.remove(); }
    private CostingTreeVarsContext() {}
}
