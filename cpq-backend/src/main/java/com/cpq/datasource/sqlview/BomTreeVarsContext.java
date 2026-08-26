package com.cpq.datasource.sqlview;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 新核价管线的两个料号数组变量(线程级)。取代 SpineKeysContext。
 * task-0713 B3 扩展：携带 {@code :versionFilter} 宏所需的 override map + 渲染/列出模式。 */
public final class BomTreeVarsContext {

    /** RENDER=按 override 渲染指定版本(否则 is_current)；LIST=放开版本过滤(供下拉收集 distinct)。 */
    public enum Mode { RENDER, LIST }

    public static final class Vars {
        public final List<String> productionPartNos; // :production_part_nos(递归 SQL 用)
        public final List<String> totalMaterialNo;   // :total_material_no(页签 SQL 用)
        /** componentId → (partNo → 目标 viewVersion)。null/缺失 componentId = 该组件无 override(等价 is_current)。 */
        public final Map<UUID, Map<String, String>> overridesByComponent;
        public final Mode mode;
        /**
         * task-260819 B-21（D-56/AC-62）：料号 → 归属的根成品料号列表（一个子件可能同时属于多个根，
         * AC-62③「不串单」要求同一子件行必须同时出现在各自根的桶里，各自独立）。与
         * {@code totalMaterialNo} 同一次树遍历产出（{@code BomTreeRenderService#collectTotalMaterialNoUnion}），
         * 供 {@code ComponentDriverService#expandMulti} 按行 {@code hf_part_no} 回分时消费——
         * D-50/D-56 后 {@code hf_part_no} 恒为锚点自身列，不再是根成品，回分职责整体移交这里。
         * {@code null} = 未提供该映射（如核价树渲染自身两阶段 set，不需要它），回分处按既有的
         * "按自身入桶"兜底语义原样处理。
         */
        public final Map<String, List<String>> rootsByMaterial;
        /**
         * task-260819 B+（D-58，wrap 修法第 6 处以内）：根成品料号 → 它自己的 BOM 闭包（含自身，
         * {@code CostingTreeGrouping.Result#cardMaterialNo} 原样带出，与 {@code totalMaterialNo}/
         * {@code rootsByMaterial} 同一次树遍历产出，不为它再查一次）。
         *
         * <p>供 {@code ComponentDriverService} 6 处 {@code loadByPath} 调用点在下发给
         * {@code SqlViewExecutor}（outer {@code hf_part_no = ANY(:hfPartNos)} 包装）之前加宽用：
         * <ul>
         *   <li><b>单料号路径</b>（{@code expand} 系，:470/:536/:573/:580/:619）——🚨 只能加宽到
         *       {@code materialsByRoot.get(thisPartNo)}（这一个成品自己的闭包），<b>不能</b>用
         *       {@code totalMaterialNo}（整单料号池）代替：单卡路径没有 {@code rootsByMaterial}
         *       那样的回分步骤，加宽到整单池会把别的成品的行错误地带进这一卡（D-58 明令）。</li>
         *   <li><b>合桶路径</b>（{@code expandMulti}，:722）——用 {@code totalMaterialNo}（整单池）
         *       加宽即可，因为后面有 {@code rootsByMaterial} fan-out 回分兜底，不会串单。</li>
         * </ul>
         * {@code null} = 未提供（如核价树渲染自身两阶段 set），widen 处按查不到时"不加宽，原样透传"
         * 兜底，逐位不变（AC-10 零回归）。
         */
        public final Map<String, List<String>> materialsByRoot;

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo) {
            this(productionPartNos, totalMaterialNo, null, Mode.RENDER, null, null);
        }

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent) {
            this(productionPartNos, totalMaterialNo, overridesByComponent, Mode.RENDER, null, null);
        }

        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent, Mode mode) {
            this(productionPartNos, totalMaterialNo, overridesByComponent, mode, null, null);
        }

        /** task-260819 B-21：五参构造——携带「后代→根」映射，专供报价侧 Pass1 合桶回分用。 */
        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent, Mode mode,
                     Map<String, List<String>> rootsByMaterial) {
            this(productionPartNos, totalMaterialNo, overridesByComponent, mode, rootsByMaterial, null);
        }

        /** task-260819 B+（D-58）：六参构造——额外携带「根→自身闭包」映射，供单料号路径的 outer wrap 加宽用。 */
        public Vars(List<String> productionPartNos, List<String> totalMaterialNo,
                     Map<UUID, Map<String, String>> overridesByComponent, Mode mode,
                     Map<String, List<String>> rootsByMaterial,
                     Map<String, List<String>> materialsByRoot) {
            this.productionPartNos = productionPartNos;
            this.totalMaterialNo = totalMaterialNo;
            this.overridesByComponent = overridesByComponent;
            this.mode = mode != null ? mode : Mode.RENDER;
            this.rootsByMaterial = rootsByMaterial;
            this.materialsByRoot = materialsByRoot;
        }
    }

    private static final ThreadLocal<Vars> TL = new ThreadLocal<>();
    public static void set(Vars v) { TL.set(v); }
    public static Vars get() { return TL.get(); }
    public static void clear() { TL.remove(); }
    private BomTreeVarsContext() {}
}
