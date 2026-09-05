package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.common.exception.LeafAddRejectedException;
import com.cpq.common.exception.TreeConflictException;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BOM 树节点类型判定服务。
 *
 * <h2>task-260904 B-5/B-6/B-20：判定来源由「页签命中」改为「主数据」</h2>
 *
 * <p>料号类型本来就是主数据的固有属性，靠页签反推会让判据不稳定（同一料号，A 单配了零件页签就判
 * 零件，B 单没配就判不出来）。实证两张主数据表<b>完全互斥</b>（物料表 45 行 / 材质表 260 行 /
 * 交集 0），判定无歧义：
 *
 * <table>
 *   <tr><th>序</th><th>条件</th><th>判定</th></tr>
 *   <tr><td>五</td><td>命中「主件」页签 <b>或</b> 该料号是本行树的根（{@code __lvl=0}）</td>
 *       <td><b>错误</b>——成品不可作他人叶子挂入（AC-26）</td></tr>
 *   <tr><td>—</td><td>同时在材质表与物料表（结构上不可能，防御）</td><td><b>409 冲突</b></td></tr>
 *   <tr><td>一</td><td>{@code material_recipe.code} 命中</td><td>材质</td></tr>
 *   <tr><td>二/四</td><td>{@code ds_quote_material} 命中，看 {@code material_type}</td>
 *       <td>{@code 外购件}→外购件；{@code 零件}/其它/<b>空</b>→零件（A0-1 裁决）</td></tr>
 *   <tr><td>三</td><td>主数据零命中，但其【直接子节点】命中「材质元素」页签</td>
 *       <td>零件（结构推导）—— <b>仅 lenient 模式</b>，见下</td></tr>
 *   <tr><td>六</td><td>主数据零命中且规则三不成立</td>
 *       <td>strict → <b>400 {@code LEAF_PART_NOT_IN_MASTER}</b>；lenient → {@code null}</td></tr>
 * </table>
 *
 * <h3>规则五（成品拦截）改读主数据后的新判据 —— B-20 明确要求写明，🚫 不许静默删</h3>
 * 改读主数据后<b>成品也是物料</b>（成品料号同样落在 {@code ds_quote_material} 里），若把主数据判定
 * 放在前面，成品会被判成「零件」而被放行挂为他人叶子。故规则五<b>必须最先执行</b>，且判据扩为两条
 * 并集：
 * <ol>
 *   <li>命中「主件」页签（存量组件路径，与改动前逐字一致）；</li>
 *   <li><b>新增</b>：该料号是本行 BOM 树的根节点（{@code __lvl == 0} / 无 {@code __parentNo}）——
 *       结构判据，不依赖 {@code tab_type} 配置，对「没有配主件页签」的新模型组件同样成立。
 *       一个报价行 = 一个成品 = 一个树根，故该判据不会误伤子件。</li>
 * </ol>
 *
 * <h3>规则三（结构推导）改读主数据后的处置 —— 同样 B-20 要求写明</h3>
 * 主数据两表覆盖了 BOM 投入料号的绝大多数（实证：材质 6 / 物料 31 / 两表都不在 1），规则三退化为
 * <b>lenient 模式专用的兜底</b>：物化 spine 时若某中间节点在主数据里查不到，仍按「直接子节点是材质
 * ⇒ 自身是零件」推导，避免 {@code __nodeType} 大面积变 null（存量 9256 条 {@code __nodeType} 的
 * 连续性）。<b>strict 模式（加叶子）不走规则三</b>——AC-6 明确要求「两表都不存在的料号必须 400」，
 * 让规则三在 strict 下兜底会把这条护栏漏掉。
 *
 * <h3>为什么 {@link MasterTypeIndex} 是显式附加的</h3>
 * 本类<b>仍然不查库</b>（纯逻辑，便于单测）。主数据由调用方经 {@link TabHitContext#attachMasterTypes}
 * 批量预取后附加（整单/整请求一次，见 {@code QuotationTreeService#buildHitContext} 与
 * {@code ConfigureSnapshotService}），本类只读它。<b>未附加</b>（{@code masterTypes == null}）时
 * 退回改造前的「页签命中」判定链——该形态只在纯单测里出现；生产路径一律附加（哪怕是空索引，
 * 空索引 ≠ 未附加，空索引意味着「查过了，没有」）。
 */
@ApplicationScoped
public class BomNodeTypeResolver {

    private static final Logger LOG = Logger.getLogger(BomNodeTypeResolver.class);

    public static final String MATERIAL = "材质";
    public static final String PART = "零件";
    public static final String OUTSOURCED = "外购件";
    /** 「主件」命中永远是错误（成品=树根），不作为可返回的节点类型，仅内部判断用。 */
    private static final String FINISHED_TAB_TYPE = "主件";

    private static final String TT_MATERIAL = "材质元素";
    private static final String TT_PART = "零件";
    private static final String TT_OUTSOURCED = "外购件";

    /** {@code ds_quote_material.material_type} 的取值（实测值域：外购件 / 零件 / NULL）。 */
    private static final String MT_OUTSOURCED = "外购件";

    // =========================================================================
    // 主数据索引（task-260904 B-5）
    // =========================================================================

    /**
     * 主数据类型索引：调用方批量预取（2 条 SQL，与料号数无关）后附加到 {@link TabHitContext}。
     * 本类只读，不查库。
     */
    public static final class MasterTypeIndex {
        /** 命中 {@code material_recipe.code} 的料号。 */
        private final Set<String> recipeCodes;
        /** 命中 {@code ds_quote_material.material_no} 的料号 → 其 {@code material_type}（可能为 null）。 */
        private final Map<String, String> materialTypeByNo;

        public MasterTypeIndex(Set<String> recipeCodes, Map<String, String> materialTypeByNo) {
            this.recipeCodes = recipeCodes == null ? Set.of() : recipeCodes;
            this.materialTypeByNo = materialTypeByNo == null ? Map.of() : materialTypeByNo;
        }

        /** 空索引 = 「查过了，一个都没命中」，与「未附加」语义不同，不要混用。 */
        public static MasterTypeIndex empty() {
            return new MasterTypeIndex(Set.of(), Map.of());
        }

        public boolean inRecipe(String partNo) { return recipeCodes.contains(partNo); }
        public boolean inMaterial(String partNo) { return materialTypeByNo.containsKey(partNo); }
        /** 主数据里是否认得这个料号（B-6 的存在性护栏判据：两表任一命中即可）。 */
        public boolean known(String partNo) { return inRecipe(partNo) || inMaterial(partNo); }
        /** 仅在 {@link #inMaterial} 为 true 时有意义；返回 null = 该行 {@code material_type} 为空。 */
        public String materialType(String partNo) { return materialTypeByNo.get(partNo); }
    }

    /**
     * 页签命中上下文：由调用方基于「当前报价单各类型页签已渲染行」构建，本类不查库。
     */
    public static final class TabHitContext {
        /** tabType(材质元素/零件/外购件/主件) → 命中的料号集合（跨同类型多页签合并去重）。 */
        private final Map<String, Set<String>> hitPartsByTabType = new LinkedHashMap<>();
        /** materialNo → 树上直接子件料号集合（供规则三结构推导；调用方传入本行 spine 边）。 */
        private final Map<String, Set<String>> childrenByMaterialNo = new LinkedHashMap<>();
        /** task-260904 B-20：本行 BOM 树的根料号集合（规则五的结构判据，见类注释）。 */
        private final Set<String> rootPartNos = new LinkedHashSet<>();
        /**
         * task-260904 AC-26（2026-09-05 用户裁决扩宽）：<b>本报价单全部行</b>的成品料号
         * （{@code quotation_line_item.product_part_no_snapshot}）。
         *
         * <p>与 {@link #rootPartNos}（只含<b>本行</b>树根）的区别就是「跨行」：AC-26 原文是
         * 「某报价行的轴值」，不限本行 —— 成品是树根，挂到<b>谁</b>的树下都是错的。
         * 调用方整单一次查出来传进来（1 条 SQL，与行数无关），本类只读。
         */
        private final Set<String> quotationFinishedPartNos = new LinkedHashSet<>();
        /** task-260904 B-5：主数据索引；null = 未附加（纯单测形态），非 null = 已批量预取。 */
        private MasterTypeIndex masterTypes;

        public void addHit(String tabType, String materialNo) {
            if (tabType == null || tabType.isBlank() || materialNo == null || materialNo.isBlank()) return;
            hitPartsByTabType.computeIfAbsent(tabType, k -> new LinkedHashSet<>()).add(materialNo);
        }

        public void addChild(String parentMaterialNo, String childMaterialNo) {
            if (parentMaterialNo == null || childMaterialNo == null) return;
            if (parentMaterialNo.equals(childMaterialNo)) return; // 防自环
            childrenByMaterialNo.computeIfAbsent(parentMaterialNo, k -> new LinkedHashSet<>()).add(childMaterialNo);
        }

        /** task-260904 B-20：登记树根料号（{@code __lvl==0} 或无 {@code __parentNo} 的树行）。 */
        public void addRoot(String rootPartNo) {
            if (rootPartNo == null || rootPartNo.isBlank()) return;
            rootPartNos.add(rootPartNo);
        }

        /** task-260904 AC-26：登记本报价单某一行的成品料号（跨行，调用方整单一次预取）。 */
        public void addQuotationFinishedPartNo(String partNo) {
            if (partNo == null || partNo.isBlank()) return;
            quotationFinishedPartNos.add(partNo);
        }

        /** task-260904 B-5：附加批量预取好的主数据索引（生产路径必调，哪怕结果为空）。 */
        public void attachMasterTypes(MasterTypeIndex index) {
            this.masterTypes = index;
        }

        /** 本上下文里出现过的全部料号（树节点 + 各页签命中值），供调用方一次性批量预取主数据。 */
        public Set<String> allKnownPartNos() {
            Set<String> out = new LinkedHashSet<>();
            for (Set<String> s : hitPartsByTabType.values()) out.addAll(s);
            for (Map.Entry<String, Set<String>> e : childrenByMaterialNo.entrySet()) {
                out.add(e.getKey());
                out.addAll(e.getValue());
            }
            out.addAll(rootPartNos);
            out.addAll(quotationFinishedPartNos);
            return out;
        }

        boolean hits(String tabType, String materialNo) {
            Set<String> s = hitPartsByTabType.get(tabType);
            return s != null && s.contains(materialNo);
        }

        boolean isRoot(String materialNo) {
            return rootPartNos.contains(materialNo);
        }

        boolean isQuotationFinished(String materialNo) {
            return quotationFinishedPartNos.contains(materialNo);
        }

        Set<String> childrenOf(String materialNo) {
            return childrenByMaterialNo.getOrDefault(materialNo, Set.of());
        }

        MasterTypeIndex masterTypes() { return masterTypes; }
    }

    /** 解析结果：strict 模式失败会抛异常，不会返回本类实例。 */
    public static final class Resolution {
        /** 材质 / 零件 / 外购件。 */
        public final String nodeType;
        /** 是否走了规则三（结构推导）。 */
        public final boolean structural;
        /**
         * task-260904 AC-12③：是否走了「{@code material_type} 为空 → 默认零件」的兜底分支。
         * 供日志/响应标注，便于将来 {@code material_type} 数据补齐后回归对照。
         */
        public final boolean materialTypeFallback;

        Resolution(String nodeType, boolean structural) {
            this(nodeType, structural, false);
        }

        Resolution(String nodeType, boolean structural, boolean materialTypeFallback) {
            this.nodeType = nodeType;
            this.structural = structural;
            this.materialTypeFallback = materialTypeFallback;
        }
    }

    /**
     * 宽松解析（供 B3 批量物化 spine 节点用）：无法确定类型 → 返回 {@code null}，不抛异常
     * （api.md §0.2：{@code __nodeType} 允许 {@code null} = 未判定，因树上存在大量纯结构性中间节点）。
     */
    public Resolution resolveLenient(String materialNo, TabHitContext ctx) {
        return resolveInternal(materialNo, ctx, false);
    }

    /**
     * 严格解析（供 B6 加叶子用）：无法确定 / 判错 → 抛 {@link BusinessException}(400) /
     * {@link LeafAddRejectedException}(400) 或 {@link TreeConflictException}(409)。
     */
    public Resolution resolveStrict(String materialNo, TabHitContext ctx) {
        return resolveInternal(materialNo, ctx, true);
    }

    private Resolution resolveInternal(String materialNo, TabHitContext ctx, boolean strict) {
        if (materialNo == null || materialNo.isBlank()) {
            if (strict) throw new BusinessException(400, "料号不能为空");
            return null;
        }
        if (ctx == null) {
            if (strict) throw new BusinessException(400, materialNo + " 不在任何页签中，不是有效的报价产品");
            return null;
        }

        // ── 规则五：成品拦截（必须最先执行，理由见类注释；AC-26） ──
        // 三条判据的并集：
        //   ① 命中「主件」页签 —— 存量路径，与改动前逐字一致；
        //   ② 该料号就是本行 BOM 树的根 —— 结构判据，对没有主件页签的新模型组件同样成立；
        //   ③ 🆕 该料号是本报价单【任意一行】的成品（2026-09-05 用户裁决扩宽）——
        //      AC-26 原文是「某报价行的轴值」，不限本行；成品是树根，挂到谁的树下都是错的。
        //
        // ⚠️ ③ <b>只在 strict（加叶子）生效，lenient（spine 物化）不用它</b>：物化跑在整棵树上，
        //    把「别的行的成品」也判成不可判定，会把「同一子装配件既单卖又作子件」这种合法跨行复用
        //    行的 __nodeType 抹成 null —— 那是改坏存量渲染，不是本 AC 要的。AC-26 的操作面
        //    （"尝试加叶子到别的节点下"）本来也只覆盖 strict。
        boolean finished = ctx.hits(FINISHED_TAB_TYPE, materialNo)
                || ctx.isRoot(materialNo)
                || (strict && ctx.isQuotationFinished(materialNo));
        if (finished) {
            if (strict) throw new BusinessException(400, materialNo + " 是成品料号，不能作为子件挂入");
            return null;
        }

        MasterTypeIndex master = ctx.masterTypes();
        if (master == null) {
            // 未附加主数据索引 = 纯单测形态；退回改造前的页签命中判定链（生产路径不会走到这里）。
            return resolveByTabHits(materialNo, ctx, strict);
        }

        boolean inRecipe = master.inRecipe(materialNo);
        boolean inMaterial = master.inMaterial(materialNo);

        // 防御性冲突分支（api.md §3.4）：改读主数据后结构上不再可能触发（两表实测交集 0），
        // 保留错误码与分支，不删——万一将来两表出现同码，宁可 409 让人去修基础数据。
        if (inRecipe && inMaterial) {
            if (strict) {
                List<String> tabs = List.of(TT_MATERIAL, TT_PART);
                throw new TreeConflictException(
                        materialNo + " 同时存在于材质库与物料表，类型有歧义，请先修正基础数据", tabs);
            }
            return null;
        }

        if (inRecipe) return new Resolution(MATERIAL, false);

        if (inMaterial) {
            String mt = master.materialType(materialNo);
            if (MT_OUTSOURCED.equals(mt)) return new Resolution(OUTSOURCED, false);
            boolean fallback = (mt == null || mt.isBlank());
            if (fallback) {
                // A0-1 裁决：material_type 为空 → 默认判「零件」（更宽松的一端，可再挂下级）。
                LOG.infof("[bom-node-type] %s 在 ds_quote_material 中 material_type 为空，按 A0-1 兜底判「零件」", materialNo);
            }
            return new Resolution(PART, false, fallback);
        }

        // ── 主数据两表皆不命中 ──
        if (strict) {
            // AC-6：strict 不走规则三兜底，否则「两表都不存在必须 400」这条护栏会被漏掉。
            throw LeafAddRejectedException.partNotInMaster(materialNo);
        }
        // lenient：规则三（直接子节点是材质 ⇒ 自身是零件）兜底，保持 spine 物化的 __nodeType 连续性。
        for (String child : ctx.childrenOf(materialNo)) {
            if (isMaterialChild(child, ctx, master)) {
                return new Resolution(PART, true);
            }
        }
        return null;
    }

    /** 规则三的「子节点是材质吗」：优先主数据，退回页签命中（两者都只读内存，不查库）。 */
    private static boolean isMaterialChild(String childPartNo, TabHitContext ctx, MasterTypeIndex master) {
        if (master != null && master.inRecipe(childPartNo)) return true;
        return ctx.hits(TT_MATERIAL, childPartNo);
    }

    /**
     * 改造前的「页签命中」判定链（规则一/二/四 + 冲突 + 规则三 + 规则六）。
     *
     * <p>🚫 <b>生产路径不再走这里</b> —— 只有 {@link TabHitContext} 未附加主数据索引时才到达，
     * 即纯单测形态（{@code BomNodeTypeResolverTest} 手工构造 ctx）。保留它是为了让「主数据索引
     * 没被附加」这件事有确定行为而不是 NPE，同时守住既有单测覆盖的那套语义。
     */
    private Resolution resolveByTabHits(String materialNo, TabHitContext ctx, boolean strict) {
        List<String> hitTypes = new ArrayList<>();
        if (ctx.hits(TT_MATERIAL, materialNo)) hitTypes.add(MATERIAL);
        if (ctx.hits(TT_PART, materialNo)) hitTypes.add(PART);
        if (ctx.hits(TT_OUTSOURCED, materialNo)) hitTypes.add(OUTSOURCED);

        if (hitTypes.size() >= 2) {
            List<String> tabTypeLabels = toTabTypeLabels(hitTypes);
            if (strict) {
                throw new TreeConflictException(
                        materialNo + " 同时出现在「" + String.join("」「", tabTypeLabels) + "」页签，请先修正基础数据",
                        tabTypeLabels);
            }
            return null;
        }
        if (hitTypes.size() == 1) {
            return new Resolution(hitTypes.get(0), false);
        }

        for (String child : ctx.childrenOf(materialNo)) {
            if (ctx.hits(TT_MATERIAL, child)) {
                return new Resolution(PART, true);
            }
        }

        if (strict) {
            throw new BusinessException(400, materialNo + " 不在任何页签中，不是有效的报价产品");
        }
        return null;
    }

    /** nodeType(材质/零件/外购件) → tabType 标签(材质元素/零件/外购件)，供错误文案/前端冲突展示。 */
    private static List<String> toTabTypeLabels(List<String> nodeTypes) {
        List<String> out = new ArrayList<>();
        for (String t : nodeTypes) {
            if (MATERIAL.equals(t)) out.add(TT_MATERIAL);
            else if (PART.equals(t)) out.add(TT_PART);
            else if (OUTSOURCED.equals(t)) out.add(TT_OUTSOURCED);
        }
        return Collections.unmodifiableList(out);
    }
}
