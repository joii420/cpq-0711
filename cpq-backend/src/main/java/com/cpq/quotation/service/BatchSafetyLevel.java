package com.cpq.quotation.service;

/**
 * task-0806 · 核价树批量渲染的「批量安全级别」（需求文档 §5.2 / backtask §3.2，FR-4 守卫 1）。
 *
 * <p>按 driver 组件的 {@code component_sql_view.sql_template} 占位符判定：
 * <ul>
 *   <li>{@link #GLOBAL}——无维度占位符，可跨全批（同一 {@code costingCardTemplateId}）共享一次渲染；</li>
 *   <li>{@link #PER_PRICE_BASE_DATE}——仅含 {@code :priceBaseDate}，必须按取价基准日
 *       （{@link com.cpq.datasource.sqlview.PriceBaseDateUtil}）分组，跨日不可共享
 *       （需求文档 D-3 反例：跨建单日整批渲染会把组长的取价基准日套给全批）；</li>
 *   <li>{@link #PER_LINE_ITEM}——含 {@code :quotationId} 或 {@code :lineItemId}，或解析失败/
 *       读不到视图（保守兜底，绝不猜），强制逐 line item 渲染。</li>
 * </ul>
 *
 * <p>枚举常量顺序即"不安全程度"升序，{@link #ordinal()} 可直接用于"取分组内最不安全级别"的比较
 * （见 {@code DriverBatchSafetyAuditor#worst}）。
 */
public enum BatchSafetyLevel {
    GLOBAL,
    PER_PRICE_BASE_DATE,
    PER_LINE_ITEM
}
