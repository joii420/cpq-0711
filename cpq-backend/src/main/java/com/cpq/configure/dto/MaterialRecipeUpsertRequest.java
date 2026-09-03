package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * 材质新建 / 编辑请求（task-260901 改造）。
 *
 * <p><b>BC-2</b>：原 {@code elements} 移除。
 * <ul>
 *   <li><b>POST（新建态）</b>：带 {@link #configs}（至少 1 组，每组自带元素与含量）。
 *       🚫 请求体<b>不含</b> {@code composition} —— 元素组成由服务端从 configs 推导
 *       （各组元素种类须相同，取第 1 组的元素与顺序），与导入侧 M-5b 同一条规则。
 *       {@code code} 由服务端自动发号（B-6），请求体传了也忽略。</li>
 *   <li><b>PUT（编辑态）</b>：带 {@link #composition}，<b>不带配置</b>；配置的增删改走配置端点。
 *       {@code code} 只读。</li>
 * </ul>
 */
public class MaterialRecipeUpsertRequest {
    /** 只读：POST 由服务端发号、PUT 强制沿用既有值；请求体传了一律忽略 */
    public String code;
    public String symbol;
    public String name;
    public String specLabel;
    public String recipeType;       // 'locked' | 'editable' | 'partial'
    public Integer sortOrder;
    public String status;           // 'ACTIVE' | 'INACTIVE'

    /** ★M-5：是否支持自定义含量，默认 false */
    public Boolean allowCustomContent;

    /** ★PUT 用：材质的元素组成（M-0b 决定可否变更） */
    public List<CompositionUpsert> composition;

    /** ★POST 用：一次建好若干组含量配置（前端的「配方卡片」） */
    public List<ConfigUpsert> configs;

    public static class CompositionUpsert {
        /** 权威元素链；与 elementCode 至少给一个（优先 elementNo） */
        public String elementNo;
        public String elementCode;
        public Integer sortOrder;
    }

    public static class ConfigUpsert {
        public String remark;
        public List<ElementUpsert> elements;
    }

    public static class ElementUpsert {
        public String elementNo;
        public String elementCode;
        /**
         * 含量（100 制）。<b>契约字段名是 {@code defaultPct}</b>（主线 2026-09-02 统一口径：
         * 材质与配置两侧的写入一律 {@code defaultPct}；{@code pct} 只保留在 api.md §2.4 的选配请求里）。
         * <p>{@code pct} 在这里仅作<b>过渡期别名</b>保留 —— 前端若还带着旧字段名发过来，
         * 不至于静默变成「含量为空」。契约定稿落地后可删。
         */
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal defaultPct;
        /** @deprecated 过渡期别名，见 {@link #defaultPct}。 */
        @Deprecated
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal pct;

        public BigDecimal effectivePct() {
            return defaultPct != null ? defaultPct : pct;
        }
    }
}
