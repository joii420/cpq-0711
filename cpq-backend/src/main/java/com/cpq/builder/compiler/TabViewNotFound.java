package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.semanticgraph.entity.SemanticTabView;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 页签视图查找失败（{@code COMPILE_TABVIEW_NOT_FOUND}）的<b>唯一</b>报文构造点
 * （task-260819 <b>B-60</b>，AC-127⑤）。
 *
 * <p><b>要解决的问题</b>：原报文只有「未找到页签视图: {@code <收到的值>}/{@code <变体>}（数据集 X）」——
 * 只点名了<b>收到的值</b>，没点名<b>合法的值</b>。取数配置器的使用者是不写 SQL 的实施顾问，
 * 拿到这句话只能靠猜。AC-127⑤ 的判据是「必须 400 且<b>点名合法值域</b>」。
 *
 * <p><b>两种失败必须分开说</b>（本类的第二个职责）：
 * <ol>
 *   <li><b>页签类型本身非法</b>（图里该数据集下根本没有这个 {@code tab_type}）
 *       ⇒ 列出该数据集下真实可用的页签类型清单；</li>
 *   <li><b>页签类型合法、只是变体没对上</b>（典型：{@code tabType=费用类} 不传 {@code variantKey}，
 *       费用类因 D-34 分立建模只有带变体的行）⇒ 原报文长得和情况 1 一模一样
 *       （「未找到页签视图: 费用类/（数据集 QUOTE）」），会让人误判成「费用类不存在」，
 *       而实际上它存在、前端只是还没让用户选变体。这里改成明说「类型存在、缺/错变体」
 *       并列出可选变体。</li>
 * </ol>
 *
 * <p>🚫 <b>值域一律从图（{@code semantic_tab_view}）按 dialect 实时取，不写死常量</b>。
 * 写死是需求文档 <b>D-39</b>（存储值 vs 显示名）踩过三次的坑
 * （前端 {@code TAB_TYPES} / {@code V413} 种子 / {@code FieldTreeBuilder.ALL_TAB_TYPES} 原值），
 * 第四次就发生在这种「顺手拼个提示语」的地方。本类唯一引用
 * {@link FieldTreeBuilder#ALL_TAB_TYPES} 的地方是<b>排序</b>——让报错里的清单顺序和前端下拉
 * 顺序一致；<b>清单内容 100% 来自 {@code snap.tabViews}</b>，图里没有的值永远不会被列出来。
 *
 * <p>结构化字段（{@code availableTabTypes} / {@code availableVariants}）同时进
 * {@link BuilderApiException} 的 extra，由 {@code GlobalExceptionMapper} 平铺进响应根，
 * 供前端直接消费，避免任何一方去 parse 中文 message。
 *
 * <p>N+1 自检：全程只遍历传入的不可变内存快照 {@link SemanticGraphSnapshot}，<b>零 SQL</b>；
 * 且只在抛错路径上执行一次，与页签数/列数无关。
 */
final class TabViewNotFound {

    private TabViewNotFound() {}

    /**
     * 构造 {@code COMPILE_TABVIEW_NOT_FOUND}。
     *
     * @param httpStatus 保持各调用点原有的状态码（编译期 400 / 字段树 404），本次不改契约
     * @param graphDialect 已解析好的图侧方言名（{@code CompileDialect#graphDialect()} 的结果）
     */
    static BuilderApiException of(SemanticGraphSnapshot snap, int httpStatus,
                                  String tabType, String variantKey, String graphDialect) {
        String vk = variantKey == null ? "" : variantKey;
        String tt = tabType == null ? "" : tabType;

        List<SemanticTabView> inDialect = snap.tabViews.stream()
                .filter(t -> graphDialect.equals(t.dialect))
                .collect(Collectors.toList());

        List<String> availableTabTypes = orderForDisplay(inDialect.stream()
                .map(t -> t.tabType)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

        StringBuilder msg = new StringBuilder()
                .append("未找到页签视图: ").append(tt).append("/").append(vk)
                .append("（数据集 ").append(graphDialect).append("）");

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dialect", graphDialect);
        extra.put("tabType", tt);
        extra.put("variantKey", vk);
        extra.put("availableTabTypes", availableTabTypes);

        if (availableTabTypes.isEmpty()) {
            // 整个数据集一行页签视图都没有 —— 这是环境问题（种子没落库/被清空），不是用户选错了。
            // 报「合法值域为空」比报「你选的不对」更接近真相，否则用户会一个一个去试那 6 个值。
            msg.append("；数据集 ").append(graphDialect)
               .append(" 下当前一个页签视图都没有（semantic_tab_view 无该数据集的行），")
               .append("这通常是语义图种子缺失，请联系管理员而不是改选页签类型");
            return new BuilderApiException(httpStatus, "COMPILE_TABVIEW_NOT_FOUND", msg.toString(), extra);
        }

        if (!availableTabTypes.contains(tt)) {
            // 情况 1：页签类型本身非法 —— AC-127⑤ 要的就是这一句。
            msg.append("；数据集 ").append(graphDialect).append(" 下合法的页签类型为: ")
               .append(String.join(" | ", availableTabTypes))
               .append("（共 ").append(availableTabTypes.size()).append(" 个）");
            return new BuilderApiException(httpStatus, "COMPILE_TABVIEW_NOT_FOUND", msg.toString(), extra);
        }

        // 情况 2：页签类型合法，是变体没对上。
        List<SemanticTabView> sameType = inDialect.stream()
                .filter(t -> tt.equals(t.tabType))
                .collect(Collectors.toList());
        List<Map<String, String>> variants = sameType.stream()
                .map(t -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("key", t.variantKey);
                    m.put("label", t.variantLabel == null ? "" : t.variantLabel);
                    return m;
                })
                .collect(Collectors.toList());
        extra.put("availableVariants", variants);
        String variantsDesc = sameType.stream().map(TabViewNotFound::describeVariant)
                .collect(Collectors.joining(" | "));
        boolean acceptsNoVariant = sameType.stream().anyMatch(t -> t.variantKey == null || t.variantKey.isEmpty());

        msg.append("；页签类型「").append(tt).append("」在该数据集下是存在的");
        if (vk.isEmpty()) {
            // 主线实测的那一例：GET /field-tree?tabType=费用类 不带 variantKey。
            // 原文案「未找到页签视图: 费用类/（数据集 QUOTE）」看着像"费用类不存在"，实则相反。
            msg.append("，但它按变体分立建模，必须同时指定变体（variantKey）；可选变体为: ");
        } else if (!acceptsNoVariant) {
            msg.append("，但变体「").append(vk).append("」不存在；可选变体为: ");
        } else {
            msg.append("，但它不分变体（variantKey 应为空），收到的是「").append(vk).append("」；可选变体为: ");
        }
        msg.append(variantsDesc);
        return new BuilderApiException(httpStatus, "COMPILE_TABVIEW_NOT_FOUND", msg.toString(), extra);
    }

    private static String describeVariant(SemanticTabView t) {
        String key = t.variantKey == null ? "" : t.variantKey;
        String label = t.variantLabel;
        if (key.isEmpty()) return label == null || label.isBlank() ? "(不带变体)" : label + "(不带变体)";
        return label == null || label.isBlank() ? key : key + "(" + label + ")";
    }

    /**
     * 只做<b>排序</b>：先按 {@link FieldTreeBuilder#ALL_TAB_TYPES} 的展示顺序，图里有、标准值里
     * 没有的（种子写了别名/错别字）追加在后面 —— 与 {@code FieldTreeBuilder.build} 里
     * {@code availableTabTypes} 的收窄逻辑逐字对齐，保证「报错里列的顺序」＝「下拉里看到的顺序」。
     *
     * <p>🚨 再强调一次：这里<b>不</b>用 {@code ALL_TAB_TYPES} 决定「有哪些值」，只决定「谁排前面」。
     * 入参 {@code inGraph} 已经是图里的真实值，本方法不增不减。
     */
    private static List<String> orderForDisplay(Set<String> inGraph) {
        List<String> ordered = FieldTreeBuilder.ALL_TAB_TYPES.stream()
                .filter(inGraph::contains).collect(Collectors.toCollection(ArrayList::new));
        for (String t : inGraph) if (!ordered.contains(t)) ordered.add(t);
        return ordered;
    }
}
