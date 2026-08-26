package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.SemanticNode;

/**
 * 边基数校验 / CI 反证测试共用的"有效判别式"解析（task-260819，2026-08-21 实测发现后抽取）。
 *
 * <p>{@code MATERIAL_BOM} 节点自身 {@code discriminator} 是 NULL——AC-6 要求它按"来向哪条边"
 * 动态推导 characteristic（ELEMENT_BOM_ITEM→RECIPE / SELF_PROCESS→ASSEMBLY；作为锚点直接使用时
 * 还有"外购件"→OUTSOURCED 分支，但那属于编译期 tabType 语境，不是边基数校验的场景，此类不处理）。
 *
 * <p>若只看 {@code to.discriminator}（NULL），基数校验会把 RECIPE/ASSEMBLY/OUTSOURCED 三种
 * characteristic 的行混在一起比对右键唯一性，产生假 FAIL——这条边其实唯一，只是没按判别式收窄。
 *
 * <p>{@link com.cpq.builder.compiler.SemanticCompiler#resolveDiscriminator} 的编译期分支是同一条
 * 业务规则的另一处应用（那边还要处理"作为锚点"的 tabType 分支），两处不能直接复用同一方法体
 * （所在包/依赖方向不同），但**判别值必须保持一致**——改这条规则时两处都要看。
 */
public final class DiscriminatorResolver {

    private DiscriminatorResolver() {}

    /** @param from 边的来源节点（{@code semantic_edge.from_node_id}），仅在 to=MATERIAL_BOM 且自身无声明时用到 */
    public static String resolve(SemanticNode from, SemanticNode to) {
        if (to == null) return null;
        if (to.discriminator != null) return to.discriminator;
        if (!"MATERIAL_BOM".equals(to.nodeKey) || from == null) return null;
        if ("ELEMENT_BOM_ITEM".equals(from.nodeKey)) return "characteristic = 'RECIPE'";
        if ("SELF_PROCESS".equals(from.nodeKey)) return "characteristic = 'ASSEMBLY'";
        return null;
    }
}
