package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.SheetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * 核价基础数据 20 个 Sheet Handler 的登记表（task-0728 · B4；task-0812 由 24 收敛为 20）。
 *
 * <p><b>为什么单独一个类</b>：模板生成（{@link PricingTemplateService}）必须与导入解析
 * （{@link PricingImportService}）用**同一份 handler 集合、同一个 {@code sheetName()}**，
 * 否则 handler 改名时模板会静默失配 —— 下载的模板导不进去。此处集中登记，
 * 两侧都从这里取 sheet 名，杜绝手写常量数组。
 *
 * <p><b>顺序</b>：{@link #all()} 按 P03 → P24 的编号顺序返回（= 模板 sheet 顺序，也是业务人员
 * 熟悉的 Excel 排布）。注意它**不是**导入写库顺序：{@code PricingImportService.orderedHandlers()}
 * 另按「料号 → 关系 → 汇率 → BOM主 → BOM子 → 单价 → 其余」的多表写入依赖排列，
 * 且 P16/P17、P19/P20 两对 Sheet 走合并 bean（{@code IncomingOtherMergeHandler} /
 * {@code FinishedOtherMergeHandler}）不进那个循环 —— 但它们的 Sheet 依然要出现在模板里，
 * 故本登记表是**全量 20 个**，与导入侧的 16 个循环项不同，这是有意的。
 *
 * <p><b>task-0812 停用</b>：元素核价价格表(P01) / 材料核价价格表(P02) / 核价版本(P04) /
 * 宏丰-客户料号对应关系(P05) 4 个 Sheet 已停用（不再解析、不再写库、不再出现在模板/导入结果）。
 * 4 个 Handler 类保留（原样不改），仅从本方法与 {@link PricingImportService#orderedHandlers()}
 * 摘除调用点；如需恢复，只需把对应 {@code @Inject} 字段重新加回下面两处 {@code List.of(...)}。
 */
@ApplicationScoped
public class PricingHandlerCatalog {

    @Inject P01ElementPricingPriceHandler p01;   // task-0812 停用：元素核价价格表（不进 all()，恢复=加回下面 List）
    @Inject P02MaterialPricingPriceHandler p02;  // task-0812 停用：材料核价价格表（不进 all()，恢复=加回下面 List）
    @Inject P03ExchangeRateHandler p03;
    @Inject P04PricingVersionHandler p04;         // task-0812 停用：核价版本（不进 all()，恢复=加回下面 List）
    @Inject P05CustomerMapHandler p05;            // task-0812 停用：宏丰-客户料号对应关系（不进 all()，恢复=加回下面 List）
    @Inject P06MaterialBomHandler p06;
    @Inject P07ElementBomHandler p07;
    @Inject P08CapacityHandler p08;
    @Inject P09EquipmentDepreciationHandler p09;
    @Inject P10ProductionEnergyHandler p10;
    @Inject P11AuxiliaryEnergyHandler p11;
    @Inject P12ToolingCostHandler p12;
    @Inject P13ProductionConsumableHandler p13;
    @Inject P14PackagingConsumableHandler p14;
    @Inject P15IncomingProcessFeeHandler p15;
    @Inject P16IncomingOtherRatioFeeHandler p16;
    @Inject P17IncomingOtherFixedFeeHandler p17;
    @Inject P18SelfProcessAssemblyFeeHandler p18;
    @Inject P19FinishedOtherRatioFeeHandler p19;
    @Inject P20FinishedOtherFixedFeeHandler p20;
    @Inject P21PlatingSchemeHandler p21;
    @Inject P22PlatingCostHandler p22;
    @Inject P23OutsourceProcessFeeHandler p23;
    @Inject P24UnitWeightHandler p24;

    /** 全部 20 个 handler，按 P03 → P24 编号顺序（= 模板 sheet 顺序）。 */
    public List<SheetHandler> all() {
        return List.of(p03, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15, p16,
                       p17, p18, p19, p20, p21, p22, p23, p24);
    }
}
