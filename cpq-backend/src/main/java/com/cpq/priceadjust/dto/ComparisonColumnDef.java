package com.cpq.priceadjust.dto;

/**
 * task-0729 B4.3 · 比对列定义 —— 复用 task-0717 {@code ColumnDef} schema（前端
 * {@code comparisonViewService.ts ColumnDef}），供 {@code comparison_column_config.columns}
 * JSONB 反序列化。后端此前完全没有这个类型（{@code ComparisonConfigDTO.java:20} 注释
 * "后端只存不解释内容"；{@code com.cpq.basicdata.v6.maintenance.dto.ColumnDef} 是同名不同物，
 * 本类专属 task-0729/0717 比对列语义，不与之混用）。
 */
public class ComparisonColumnDef {
    public String id;
    /** PRODUCT_TOTAL | TAB_PAIR */
    public String kind;
    public int sortOrder;
    /** 差异阈值，默认 0 */
    public java.math.BigDecimal threshold = java.math.BigDecimal.ZERO;

    // kind=TAB_PAIR 时必填
    public String quoteComponentId;
    public String quoteMetric;
    public String quoteLabel;
    public String costingComponentId;
    public String costingMetric;
    public String costingLabel;

    /**
     * api.md §1.9：默认「产品总价」列不可删除（验收 #24③），其余列可删。
     *
     * <p>🔒 <b>这是派生字段，不是独立事实</b>。它的值恒等于 {@code !isProductTotal()}，由
     * {@code PriceAdjustComparisonColumnService#normalizeRemovable} 在每次回显前统一改写，
     * <b>不采信库里存的值</b>——历史数据里存过 {@code PRODUCT_TOTAL + removable=true} 的组合，
     * 那会让前端 {@code ComparisonColumnPanel.tsx:151} 渲染出一个点了必然吃 400 的删除按钮，
     * 同时 {@code :136} 的「默认列」标签消失，把不可删的默认列整体伪装成普通可删列。
     */
    public Boolean removable = Boolean.TRUE;

    /**
     * 🔒 <b>「这一列能不能删」在本系统里的唯一权威判定</b>。两个调用方共用本方法，不得各判各的：
     * <ol>
     *   <li><b>写守卫</b>：{@code PriceAdjustComparisonColumnService#doPutColumns} 用它断言
     *       保存内容仍含至少一条 PRODUCT_TOTAL，否则 400；</li>
     *   <li><b>读回显</b>：{@code #normalizeRemovable} 用它推导 {@link #removable}。</li>
     * </ol>
     * 曾经这两处各写一份 {@code "PRODUCT_TOTAL".equals(kind)}，结果就是守卫拦得住、回显却说
     * 可删——同一个不变量有两份实现，必然漂移。改判定规则只改这里。
     *
     * <p>🔒 必须 {@code @JsonIgnore}——否则 Jackson 按 bean 约定把它序列化成 JSON 属性
     * {@code "productTotal"}，往返（PUT 落库再 GET 读回反序列化）时因该属性无对应可写字段，
     * 触发 {@code UnrecognizedPropertyException}，真实联调时复现为「保存成功但读回来还是默认列」
     * （异常被 parseColumns 的兜底 catch 悄悄吞掉，表现成静默丢数据，非常隐蔽）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isProductTotal() { return "PRODUCT_TOTAL".equals(kind); }
}
