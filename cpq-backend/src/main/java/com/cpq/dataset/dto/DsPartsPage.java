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

        /**
         * 料号类型（api.md §3，D-26 / R-1.6，2026-09-03）—— 取值域 {@code 零件} / {@code 外购件}。
         *
         * <p>与 {@link #putProductionNo} 同一套「键的有无由数据集决定」口径：三套数据集的物料表
         * 当前<b>都</b>建了这一列 ⇒ 三套的键都恒出现，该料号没填就是 {@code "materialType": null}。
         * 判定仍走 Registry 元数据而非硬编码 datasetKey —— 将来某套去掉这一列，键会自动跟着消失。
         */
        public void putMaterialType(String value) {
            dynamic.put("materialType", value);
        }

        /**
         * 产品分类编码（api.md §3，D-27 / R-1.7，2026-09-03）—— {@code product_category.code}。
         *
         * <p>同一套「键的有无由数据集决定」口径：<b>只有报价</b>的物料表建了 {@code category_code}
         * ⇒ 只有 {@code dataset=quote} 的 items 出现本键；核价两套整个键不出现。
         * <p>值永远非空：导入 Phase 1 对空格子填 {@code 000000}（默认分类），
         * 存量行由 V409 迁移回填 —— 但这里仍<b>不</b>在展示层兜底成默认值，
         * 真出现 null 说明有第三条写入路径绕过了 Phase 1，应当看得见而不是被抹平。
         */
        public void putCategoryCode(String value) {
            dynamic.put("categoryCode", value);
        }

        /**
         * 产品分类名称（api.md §3，D-27 / AC-61）—— 由 {@code LEFT JOIN product_category} 带出的显示名。
         *
         * <p>🚫 <b>不逐行查</b>：与 {@link #putCategoryCode} 同在一条 SELECT 里取回，
         * 该端点的 SQL 条数恒为 2（count + page），与料号数无关。
         * <p>code 在 {@code product_category} 里查不到（分类被删）时为 null —— 此时前端显示 code 本身即可。
         */
        public void putCategoryName(String value) {
            dynamic.put("categoryName", value);
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
