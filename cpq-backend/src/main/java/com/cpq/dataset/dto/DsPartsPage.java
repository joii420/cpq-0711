package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * api.md §3 料号列表分页响应。
 *
 * <p>⚠️ 响应体<b>只有</b> {@code total} + {@code items}（api.md §3 未约定回显 page/size），
 * 不要顺手补字段 —— 前端按契约取值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsPartsPage {

    public long total;
    public List<Item> items;

    public DsPartsPage(long total, List<Item> items) {
        this.total = total;
        this.items = items;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Item {
        public String axisValue;
        public String materialName;
        public String specification;
        public String dimension;
        public String oldMaterialNo;
        /** numeric(26,12) —— 以字符串回传，避免 JS 精度丢失（与 api.md §5 同口径）。 */
        public String unitWeight;
        /**
         * 生产料号（api.md §3，2026-09-03 契约补修）—— <b>键的有无由「数据集」决定，不由「行的值」决定</b>。
         *
         * <ul>
         *   <li>{@code dataset=quote}：{@code ds_quote_material} 建了「生产料号」这一列 ⇒ <b>键恒出现</b>，
         *       该行没填就是 {@code "productionNo": null}（与 specification / dimension 的空值口径一致）；</li>
         *   <li>{@code cost-basic} / {@code cost-detail}：物料表<b>压根没有</b>这一列
         *       （轴本身就是生产料号，已由 {@code axisValue} 表达）⇒ <b>整个键不出现</b>。</li>
         * </ul>
         *
         * <p>⚠️ <b>为什么不是一个普通字段 + {@code @JsonInclude(NON_NULL)}</b>：那样「报价里这一行没填生产料号」
         * 与「核价数据集根本没有这一列」会输出成<b>同一种形状</b>（键都消失），前端要靠键的有无判断列是否存在时
         * 就会误判。用 {@code @JsonAnyGetter} 才能做到「按数据集决定键在不在，按行决定值是不是 null」。
         */
        @JsonIgnore
        private final Map<String, Object> dynamic = new LinkedHashMap<>();

        /** 只有该数据集的物料表真有这一列时才调用；调了键就出现（值可为 null）。 */
        public void putProductionNo(String value) {
            dynamic.put("productionNo", value);
        }

        @JsonAnyGetter
        public Map<String, Object> getDynamic() {
            return dynamic;
        }
        /** 该轴值在<b>带版本表</b>中至少有 1 行数据的 sheet 数（列表「已配置 6/9」徽标）。 */
        public int configuredCount;
        public int totalSheetCount;
        public OffsetDateTime lastUpdatedAt;
    }
}
