package com.cpq.basicdata.v6.pricing;

/**
 * 核价系统（system_type=PRICING）Excel 导入写入 unit_price.price_type 的细分枚举值。
 *
 * <p>背景：原本 10 个费用 Sheet 都写大类 {@code MATERIAL}，仅靠 cost_type 区分来源，混淆且
 * 4 个动态 cost_type Sheet 无法靠固定值定位。本次把超载的 MATERIAL 桶按 Sheet 细分，
 * 写入端废弃 MATERIAL。详见 docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md。
 *
 * <p>纪律（防拼写漂移）：所有 P*Handler 一律引用本类常量，禁止裸写字符串。
 *
 * <p>不在本类的两个保留值（已与 Sheet 1:1，不参与拆分）：
 * <ul>
 *   <li>{@code ELEMENT} —— P01 元素核价价格表</li>
 *   <li>{@code CONSUMABLE} —— P13 生产耗材BOM</li>
 * </ul>
 *
 * <p>原则：price_type 只表"业务类别"，更细维度交给已有字段——
 * 比例/固定看 cost_ratio vs pricing_price 哪列有值；电镀加工费/材料费看 cost_type。
 */
public final class PricingPriceType {

    private PricingPriceType() {}

    /** P02 材料核价价格表。 */
    public static final String MATERIAL_PRICE = "MATERIAL_PRICE";
    /** P14 包装材料BOM。 */
    public static final String PACKAGING = "PACKAGING";
    /** P15 来料加工费。 */
    public static final String INCOMING_PROCESS = "INCOMING_PROCESS";
    /** P16 来料其他费用（比例）+ P17 来料其他固定费用（合并：靠 cost_ratio/pricing_price 区分）。 */
    public static final String INCOMING_OTHER = "INCOMING_OTHER";
    /** P18 加工费&组装费（自制加工费）。 */
    public static final String SELF_PROCESS = "SELF_PROCESS";
    /** P19 成品其他比例费用 + P20 成品其他固定费用（合并：靠 cost_ratio/pricing_price 区分）。 */
    public static final String FINISHED_OTHER = "FINISHED_OTHER";
    /** P22 电镀成本（加工费 + 材料费两条都写，靠 cost_type 区分）。注：PLATING 值由 V297 报价侧引入并共用。 */
    public static final String PLATING = "PLATING";
    /** P23 其他外加工成本。 */
    public static final String OUTSOURCE_PROCESS = "OUTSOURCE_PROCESS";
}
