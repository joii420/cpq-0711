package com.cpq.basicdata.v6;

/**
 * {@code material_bom_item.characteristic} 三态常量与派生规则（单一定义点）。
 *
 * <p>核价侧（system_type='PRICING'）的 characteristic 是由 {@code calc_type} **派生**的量，
 * 不是用户输入。导入器（P06MaterialBomHandler）与维护端（PricingMaintenanceService）
 * 必须共用 {@link #fromCalcType}，否则两条写路径会漂移——这正是本类被抽出的原因。
 *
 * <p>报价侧（QUOTE）的 characteristic 来自 Excel「组成类型」列，走
 * {@code MaterialBomMergeHandler.kindToCharacteristic}，不在本类职责内。
 */
public final class BomCharacteristic {

    /** 材质。 */
    public static final String RECIPE = "RECIPE";
    /** 组成件·零件。 */
    public static final String ASSEMBLY = "ASSEMBLY";
    /** 外购件。 */
    public static final String OUTSOURCED = "OUTSOURCED";

    private BomCharacteristic() {}

    /**
     * 核价侧派生：{@code calc_type='元素'} → {@link #RECIPE}；其余（含 null）→ {@link #ASSEMBLY}。
     * <p>与 Flyway V344 的 {@code CASE WHEN calc_type='元素' THEN 'RECIPE' ELSE 'ASSEMBLY' END} 同语义，
     * 两侧对 null 都落 ASSEMBLY，不得漂移。
     */
    public static String fromCalcType(Object calcType) {
        return "元素".equals(calcType) ? RECIPE : ASSEMBLY;
    }
}
