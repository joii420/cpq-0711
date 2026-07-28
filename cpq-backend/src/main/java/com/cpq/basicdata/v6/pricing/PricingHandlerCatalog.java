package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.SheetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * 核价基础数据 24 个 Sheet Handler 的登记表（task-0728 · B4）。
 *
 * <p><b>为什么单独一个类</b>：模板生成（{@link PricingTemplateService}）必须与导入解析
 * （{@link PricingImportService}）用**同一份 handler 集合、同一个 {@code sheetName()}**，
 * 否则 handler 改名时模板会静默失配 —— 下载的模板导不进去。此处集中登记，
 * 两侧都从这里取 sheet 名，杜绝手写常量数组。
 *
 * <p><b>顺序</b>：{@link #all()} 按 P01 → P24 的编号顺序返回（= 模板 sheet 顺序，也是业务人员
 * 熟悉的 Excel 排布）。注意它**不是**导入写库顺序：{@code PricingImportService.orderedHandlers()}
 * 另按「料号 → 关系 → 汇率 → BOM主 → BOM子 → 单价 → 其余」的多表写入依赖排列，
 * 且 P16/P17、P19/P20 两对 Sheet 走合并 bean（{@code IncomingOtherMergeHandler} /
 * {@code FinishedOtherMergeHandler}）不进那个循环 —— 但它们的 Sheet 依然要出现在模板里，
 * 故本登记表是**全量 24 个**，与导入侧的 20 个循环项不同，这是有意的。
 */
@ApplicationScoped
public class PricingHandlerCatalog {

    @Inject P01ElementPricingPriceHandler p01;
    @Inject P02MaterialPricingPriceHandler p02;
    @Inject P03ExchangeRateHandler p03;
    @Inject P04PricingVersionHandler p04;
    @Inject P05CustomerMapHandler p05;
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

    /** 全部 24 个 handler，按 P01 → P24 编号顺序（= 模板 sheet 顺序）。 */
    public List<SheetHandler> all() {
        return List.of(p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12,
                       p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24);
    }
}
