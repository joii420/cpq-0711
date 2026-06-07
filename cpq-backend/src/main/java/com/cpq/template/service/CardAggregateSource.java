package com.cpq.template.service;

import com.cpq.quotation.service.card.CardDataProvider;
import java.util.*;

/** 求值期 ThreadLocal：当前料号卡片 provider + 聚合源token→{页签key + 别名映射 + (可空)动态谓词}。由 Excel 求值入口 set/clear。 */
public final class CardAggregateSource {
    public static final class Binding {
        public final String tabKey;
        public final Map<String, String> aliasToField; // c0 → 工序
        public final String dynamicPredicate;          // 按本产品行算好的 JEXL 谓词(别名左值); 可空 → 走公式文本谓词
        public Binding(String tabKey, Map<String, String> aliasToField) {
            this(tabKey, aliasToField, null);
        }
        public Binding(String tabKey, Map<String, String> aliasToField, String dynamicPredicate) {
            this.tabKey = tabKey;
            this.aliasToField = aliasToField != null ? aliasToField : Map.of();
            this.dynamicPredicate = dynamicPredicate;
        }
    }
    public static final class Ctx {
        public final CardDataProvider provider;
        public final Map<String, Binding> sourceToken;
        public Ctx(CardDataProvider p, Map<String, Binding> map) { this.provider = p; this.sourceToken = map; }
    }
    private static final ThreadLocal<Ctx> TL = new ThreadLocal<>();
    public static void set(Ctx c) { TL.set(c); }
    public static void clear() { TL.remove(); }
    public static Ctx get() { return TL.get(); }

    /** 命中卡片源 → 返回已按别名重映射 key 的行集(c0/c1...→值)，供 JEXL 行求值；否则 null(回退 dataLoader)。 */
    public static List<Map<String, Object>> rowsFor(String sourceToken) {
        Ctx c = TL.get();
        if (c == null || sourceToken == null) return null;
        Binding b = c.sourceToken.get(sourceToken.trim());
        if (b == null) return null;
        List<Map<String, Object>> raw = c.provider.rowsOf(b.tabKey);
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            Map<String, Object> aliased = new HashMap<>();
            for (Map.Entry<String, String> e : b.aliasToField.entrySet())
                aliased.put(e.getKey(), row.get(e.getValue()));
            out.add(aliased);
        }
        return out;
    }

    /** 该源 token 绑定的动态谓词(按本产品行算好); 无 Ctx / 无 binding / 无动态谓词 → null(走公式文本谓词)。 */
    public static String predicateFor(String sourceToken) {
        Ctx c = TL.get();
        if (c == null || sourceToken == null) return null;
        Binding b = c.sourceToken.get(sourceToken.trim());
        return b == null ? null : b.dynamicPredicate;
    }
}
