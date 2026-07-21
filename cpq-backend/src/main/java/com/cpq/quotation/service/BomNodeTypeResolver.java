package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.common.exception.TreeConflictException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * task-0721 B5：BOM 树节点类型判定服务（需求说明 §4.3 规则二，<b>6 条</b>判定链）。
 *
 * <p>⚠️ 2026-07-21 修正：设计文档 §5.1 曾漏列「命中主件页签」一条（只写了 5 条），
 * 以需求说明 §4.3 规则二为准 —— 「命中主件」与「零命中」是<b>两条独立分支、两种不同错误文案</b>：
 *
 * <table>
 *   <tr><th>序</th><th>条件</th><th>判定</th></tr>
 *   <tr><td>1</td><td>料号出现在「材质元素」类型页签</td><td>材质</td></tr>
 *   <tr><td>2</td><td>料号出现在「零件」类型页签</td><td>零件</td></tr>
 *   <tr><td>3</td><td>未出现在「零件」页签，但其【直接子节点】命中「材质元素」页签</td><td>零件（结构推导）</td></tr>
 *   <tr><td>4</td><td>料号出现在「外购件」类型页签</td><td>外购件</td></tr>
 *   <tr><td>5</td><td>料号出现在「主件」类型页签</td><td><b>错误</b>——成品不可作他人叶子挂入</td></tr>
 *   <tr><td>6</td><td>以上皆不满足（零命中）</td><td><b>错误</b>——该料号不是有效的报价产品</td></tr>
 * </table>
 *
 * <p><b>冲突粒度</b>（2026-07-21 裁决）：只有命中 ≥2 个<b>不同类型</b>的页签才算冲突（409）；
 * 同一料号命中多个<b>同类型</b>页签（如两个「材质元素」Tab）不算冲突——类型无歧义，正常判定，
 * 不静默取第一个、也不抛冲突。命中主件页签（规则五）单独判断、不参与冲突清单（它永远是错误）。
 *
 * <p><b>匹配范围</b> = 当前报价单该页签<b>已渲染的行</b>，不是基础数据主表全量——由调用方经
 * {@link TabHitContext} 传入，本类不查库、不感知报价单/组件实体，纯逻辑判定，便于单测。
 */
@ApplicationScoped
public class BomNodeTypeResolver {

    public static final String MATERIAL = "材质";
    public static final String PART = "零件";
    public static final String OUTSOURCED = "外购件";
    /** 「主件」命中永远是错误（成品=树根），不作为可返回的节点类型，仅内部判断用。 */
    private static final String FINISHED_TAB_TYPE = "主件";

    private static final String TT_MATERIAL = "材质元素";
    private static final String TT_PART = "零件";
    private static final String TT_OUTSOURCED = "外购件";

    /**
     * 页签命中上下文：由调用方基于「当前报价单各类型页签已渲染行」构建，本类不查库。
     */
    public static final class TabHitContext {
        /** tabType(材质元素/零件/外购件/主件) → 命中的料号集合（跨同类型多页签合并去重）。 */
        private final Map<String, Set<String>> hitPartsByTabType = new LinkedHashMap<>();
        /** materialNo → 树上直接子件料号集合（供规则三结构推导；调用方传入本行 spine 边）。 */
        private final Map<String, Set<String>> childrenByMaterialNo = new LinkedHashMap<>();

        public void addHit(String tabType, String materialNo) {
            if (tabType == null || tabType.isBlank() || materialNo == null || materialNo.isBlank()) return;
            hitPartsByTabType.computeIfAbsent(tabType, k -> new LinkedHashSet<>()).add(materialNo);
        }

        public void addChild(String parentMaterialNo, String childMaterialNo) {
            if (parentMaterialNo == null || childMaterialNo == null) return;
            if (parentMaterialNo.equals(childMaterialNo)) return; // 防自环
            childrenByMaterialNo.computeIfAbsent(parentMaterialNo, k -> new LinkedHashSet<>()).add(childMaterialNo);
        }

        boolean hits(String tabType, String materialNo) {
            Set<String> s = hitPartsByTabType.get(tabType);
            return s != null && s.contains(materialNo);
        }

        Set<String> childrenOf(String materialNo) {
            return childrenByMaterialNo.getOrDefault(materialNo, Set.of());
        }
    }

    /** 解析结果：strict 模式失败会抛异常，不会返回本类实例。 */
    public static final class Resolution {
        /** 材质 / 零件 / 外购件。 */
        public final String nodeType;
        /** 是否走了规则三（结构推导）。 */
        public final boolean structural;

        Resolution(String nodeType, boolean structural) {
            this.nodeType = nodeType;
            this.structural = structural;
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
     * 严格解析（供 B6 加叶子用）：无法确定 / 判错 → 抛 {@link BusinessException}(400) 或
     * {@link TreeConflictException}(409)。
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

        // 规则五：命中主件页签 → 永远是错误（成品不可作他人叶子挂入），单独判断,不参与冲突清单。
        if (ctx.hits(FINISHED_TAB_TYPE, materialNo)) {
            if (strict) throw new BusinessException(400, materialNo + " 是成品料号，不能作为子件挂入");
            return null;
        }

        // 规则一/二/四：按类型收集命中(同类型多页签已在 TabHitContext.addHit 合并去重,不重复计入)
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
            return null; // lenient：冲突时不确定，不静默取第一个
        }
        if (hitTypes.size() == 1) {
            return new Resolution(hitTypes.get(0), false);
        }

        // 规则三（2026-07-21 裁决 Q4：仅查【直接子节点】，不做任意深度子孙检索）：
        // 语义是「由材质直接构成的零件」——放宽到任意深度会让几乎所有中间节点都命中,
        // 规则失去区分力。故只看 materialNo 的直接子节点是否命中「材质元素」页签。
        for (String child : ctx.childrenOf(materialNo)) {
            if (ctx.hits(TT_MATERIAL, child)) {
                return new Resolution(PART, true);
            }
        }

        // 规则六：零命中
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
        return out;
    }
}
