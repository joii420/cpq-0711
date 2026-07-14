package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.ColumnDef;
import com.cpq.basicdata.v6.pricing.PricingPriceType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核价基础数据维护 · 16 个"版本组"元数据登记（task-0712 · backtask §B2 核心）。
 *
 * <p><b>同源真源</b>：每组的 groupKey 固定常量 / contentColumns / descriptorColumns 逐列抄自对应
 * {@code com.cpq.basicdata.v6.pricing.P*Handler}（见每组注释「对应 PxxHandler」）。改 handler 的 groupKey/content
 * 必须同步改这里，否则维护保存的升版口径与导入不一致（虚假升版 / 匹配错组）。
 *
 * <p>三个错位点已在登记中体现：
 * <ul>
 *   <li>P16+P17 合并为 INCOMING_OTHER、P19+P20 合并为 FINISHED_OTHER（各一组，cost_type 进 content 不进 gk）；</li>
 *   <li>P08 产能 sheet 拆 CAPACITY(capacity 表) + LABOR_RATE(labor_rate 表) 两条独立版本线；</li>
 *   <li>P09 折旧 / P10 能耗同落 production_energy 表，靠 price_type=DEPRECIATION/ENERGY 分、各自独立版本。</li>
 * </ul>
 *
 * <p>ELEMENT_BOM 特殊：版本组 = (material_no, material_part_no)，一个销售料号可挂多材质料号 = 多版本线；
 * 本期按"单 tab 合并展示"（material_part_no 作行内只读子维度），读写按 material_part_no 分组处理（见 Service）。
 */
@ApplicationScoped
public class PricingSheetRegistry {

    // currency/unit 无字典表，用现网导入常见值作固定枚举；前端未知可输入回退（需求 §4.4.0 B）。
    private static final List<String> CURRENCY = List.of("CNY", "USD", "EUR", "JPY");
    private static final List<String> UNIT = List.of("PCS", "KG", "H", "SET", "M", "G");

    private final Map<String, PricingSheetDef> byKey = new LinkedHashMap<>();

    public PricingSheetRegistry() {
        // ============ FEE 组（落 unit_price / version_no，8 组）============

        // 1) 生产耗材BOM — 对应 P13ProductionConsumableHandler（CONTENT 行21 / groupKey 行52-55）
        reg(PricingSheetDef.builder("CONSUMABLE", "生产耗材BOM", "FEE", 1)
            .table("unit_price", "version_no", "code")
            .priceType("CONSUMABLE").fixedGk("cost_type", "耗材")   // P13 用字面量 "CONSUMABLE"（PricingPriceType 保留未登记）
            .content("operation_no", "pricing_price", "currency", "unit")
            .rowKeys("operation_no").scale("pricing_price", 6)
            .columns(
                ColumnDef.subDimMaster("operation_no", "工序号", "process", "operation_name"),
                ColumnDef.nameCol("operation_name", "工序名"),
                ColumnDef.value("pricing_price", "耗材成本单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // 2) 包装材料BOM — 对应 P14PackagingConsumableHandler（CONTENT 行21 / groupKey 行52-55）
        reg(PricingSheetDef.builder("PACKAGING", "包装材料BOM", "FEE", 2)
            .table("unit_price", "version_no", "code")
            .priceType(PricingPriceType.PACKAGING).fixedGk("cost_type", "包装")
            .content("operation_no", "pricing_price", "currency", "unit")
            .rowKeys("operation_no").scale("pricing_price", 6)
            .columns(
                ColumnDef.subDimMaster("operation_no", "工序号", "process", "operation_name"),
                ColumnDef.nameCol("operation_name", "工序名"),
                ColumnDef.value("pricing_price", "包装成本单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // 3) 来料加工费 — 对应 P15IncomingProcessFeeHandler（CONTENT 行25 / groupKey 行57-61；锚 finished_material_no，code=来料料号进 content）
        reg(PricingSheetDef.builder("INCOMING_PROCESS", "来料加工费", "FEE", 3)
            .table("unit_price", "version_no", "finished_material_no")
            .priceType(PricingPriceType.INCOMING_PROCESS).fixedGk("cost_type", "来料加工费")
            .content("code", "pricing_price", "currency", "unit", "defect_rate")
            .rowKeys("code").scale("pricing_price", 6).scale("defect_rate", 4)
            .columns(
                ColumnDef.subDimMaster("code", "来料料号", "material", "code_name"),
                ColumnDef.nameCol("code_name", "来料品名"),
                ColumnDef.value("pricing_price", "加工费", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT),
                ColumnDef.value("defect_rate", "损耗", "DECIMAL"))
            .build());

        // 4) 来料其他费用（P16+P17 合并）— 对应 IncomingOtherMergeHandler（CONTENT 行44-45 / groupKey 行110-113；cost_type 进 content）
        reg(PricingSheetDef.builder("INCOMING_OTHER", "来料其他费用", "FEE", 4)
            .table("unit_price", "version_no", "finished_material_no")
            .priceType(PricingPriceType.INCOMING_OTHER)
            .content("code", "cost_type", "seq_no", "cost_ratio", "pricing_price", "currency", "unit")
            .rowKeys("code", "cost_type", "seq_no").scale("cost_ratio", 4).scale("pricing_price", 6)
            .columns(
                ColumnDef.subDimMaster("code", "来料料号", "material", "code_name"),
                ColumnDef.nameCol("code_name", "来料品名"),
                ColumnDef.subDimFree("cost_type", "要素名称", "STRING"),
                ColumnDef.value("seq_no", "项次", "NUMBER"),
                ColumnDef.value("cost_ratio", "比例", "DECIMAL"),
                ColumnDef.value("pricing_price", "费用", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计价单位", UNIT))
            .build());

        // 5) 加工费&组装费 — 对应 P18SelfProcessAssemblyFeeHandler（CONTENT 行21 / groupKey 行52-56）
        reg(PricingSheetDef.builder("SELF_PROCESS", "加工费&组装费", "FEE", 5)
            .table("unit_price", "version_no", "code")
            .priceType(PricingPriceType.SELF_PROCESS).fixedGk("cost_type", "自制加工费")
            .content("operation_no", "pricing_price", "currency", "unit", "defect_rate")
            .rowKeys("operation_no").scale("pricing_price", 6).scale("defect_rate", 4)
            .columns(
                ColumnDef.subDimMaster("operation_no", "工序号", "process", "operation_name"),
                ColumnDef.nameCol("operation_name", "工序名"),
                ColumnDef.value("pricing_price", "加工费", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT),
                ColumnDef.value("defect_rate", "不良率", "DECIMAL"))
            .build());

        // 6) 成品其他费用（P19+P20 合并）— 对应 FinishedOtherMergeHandler（CONTENT 行39-40 / groupKey 行99-102；cost_type 进 content）
        reg(PricingSheetDef.builder("FINISHED_OTHER", "成品其他费用", "FEE", 6)
            .table("unit_price", "version_no", "code")
            .priceType(PricingPriceType.FINISHED_OTHER)
            .content("cost_type", "seq_no", "cost_ratio", "pricing_price", "currency", "unit")
            .rowKeys("cost_type", "seq_no").scale("cost_ratio", 4).scale("pricing_price", 6)
            .columns(
                ColumnDef.subDimFree("cost_type", "要素名称", "STRING"),
                ColumnDef.value("seq_no", "项次", "NUMBER"),
                ColumnDef.value("cost_ratio", "比例", "DECIMAL"),
                ColumnDef.value("pricing_price", "费用", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计价单位", UNIT))
            .build());

        // 7) 电镀成本 — 对应 P22PlatingCostHandler（CONTENT 行38-39 / groupKey 行91-94；cost_type 固定二选一进 content）
        reg(PricingSheetDef.builder("PLATING", "电镀成本", "FEE", 7)
            .table("unit_price", "version_no", "code")
            .priceType(PricingPriceType.PLATING)
            .content("cost_type", "pricing_price", "currency", "unit", "defect_rate")
            .rowKeys("cost_type").scale("pricing_price", 6).scale("defect_rate", 4)
            .columns(
                ColumnDef.valueEnum("cost_type", "费用类型", List.of("电镀加工费", "电镀材料费")),
                ColumnDef.value("pricing_price", "单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "货币", CURRENCY),
                ColumnDef.valueEnum("unit", "计价单位", UNIT),
                ColumnDef.value("defect_rate", "不良率", "DECIMAL"))
            .build());

        // 8) 其他外加工成本 — 对应 P23OutsourceProcessFeeHandler（CONTENT 行21 / groupKey 行52-55）
        reg(PricingSheetDef.builder("OUTSOURCE_PROCESS", "其他外加工成本", "FEE", 8)
            .table("unit_price", "version_no", "code")
            .priceType(PricingPriceType.OUTSOURCE_PROCESS).fixedGk("cost_type", "其他加工费")
            .content("operation_no", "pricing_price", "currency", "unit")
            .rowKeys("operation_no").scale("pricing_price", 6)
            .columns(
                ColumnDef.subDimMaster("operation_no", "工序号", "process", "operation_name"),
                ColumnDef.nameCol("operation_name", "工序名"),
                ColumnDef.value("pricing_price", "外加工费用", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "单位", UNIT))
            .build());

        // ============ BOM 组（主从版本化，2 组）============

        // 9) 物料BOM — 对应 P06MaterialBomHandler（CHILD_CONTENT 行39-42；master gk 行99-103；production_no 在 childContent+masterFixed）
        reg(PricingSheetDef.builder("MATERIAL_BOM", "物料BOM", "BOM", 9)
            .table("material_bom", "bom_version", "material_no")
            .fixedGk("customer_no", "_GLOBAL_").fixedGk("bom_type", "MATERIAL")
            .masterDetail("material_bom_item", "bom_version")
            .childFixedGk("customer_no", "_GLOBAL_").childFixedGk("characteristic", null)
            .masterDescriptors("production_no")   // 主表 production_no（per-material，不进版本比对）
            .descriptors()                         // 子表无独立 descriptor：production_no 在 childContent（参与比对，同 handler）
            .content("seq_no", "component_no", "operation_no", "component_usage_type",
                     "composition_qty", "issue_unit", "base_qty", "scrap_rate", "fixed_scrap",
                     "defect_rate", "calc_type", "production_no")
            .rowKeys("seq_no", "component_no")
            .scale("composition_qty", 6).scale("base_qty", 6).scale("scrap_rate", 4)
            .scale("fixed_scrap", 6).scale("defect_rate", 4)
            .columns(
                ColumnDef.value("seq_no", "项次", "NUMBER"),
                ColumnDef.subDimFree("component_no", "组成件", "STRING"),
                ColumnDef.subDimMaster("operation_no", "工序号", "process", "operation_name"),
                ColumnDef.nameCol("operation_name", "工序名"),
                ColumnDef.value("component_usage_type", "使用特性", "STRING"),
                ColumnDef.value("composition_qty", "组成用量", "DECIMAL"),
                ColumnDef.value("issue_unit", "组成用量单位", "STRING"),
                ColumnDef.value("base_qty", "底数", "DECIMAL"),
                ColumnDef.value("scrap_rate", "损耗率", "DECIMAL"),
                ColumnDef.value("fixed_scrap", "固定损耗", "DECIMAL"),
                ColumnDef.value("defect_rate", "不良率", "DECIMAL"),
                ColumnDef.valueEnum("calc_type", "计算类型", List.of("材料", "元素")),
                ColumnDef.value("production_no", "生产料号", "STRING"))
            .build());

        // 10) 物料与元素BOM — 对应 P07ElementBomHandler（CHILD_CONTENT 行36-37；master gk 行71-75；版本组=(material_no, material_part_no)）
        reg(PricingSheetDef.builder("ELEMENT_BOM", "物料与元素BOM", "BOM", 10)
            .table("element_bom", "characteristic", "material_no")
            .fixedGk("customer_no", "_GLOBAL_")
            .extraAnchor("material_part_no")       // 第二维度：一个 material_no 多材质料号 = 多版本组（合并展示）
            .masterDetail("element_bom_item", "characteristic")
            .childFixedGk("customer_no", "_GLOBAL_")
            .masterFixed("bom_type", "MATERIAL")   // P07 masterFixedColumns=Map.of("bom_type","MATERIAL")
            .descriptors()                          // 无 production_no
            .content("seq_no", "component_no", "content", "scrap_rate")
            .rowKeys("seq_no", "component_no")
            .scale("content", 6).scale("scrap_rate", 4)
            .columns(
                // 材质名两跳 join（task-0712 · childtask-1 · B2）：
                // material_part_no → material_master.material_recipe_id → material_recipe.name；未绑定→null（前端显示"未绑定"）。
                ColumnDef.subDimReadonlyTwoHop("material_part_no", "材质料号",
                    "material_master", "material_no", "material_recipe_id",
                    "material_recipe", "id", "name", "material_recipe_name"),
                ColumnDef.nameCol("material_recipe_name", "材质名"),
                ColumnDef.value("seq_no", "项次", "NUMBER"),
                ColumnDef.subDimMaster("component_no", "元素代码", "element", "component_name"),
                ColumnDef.nameCol("component_name", "元素名"),
                ColumnDef.value("content", "组成含量", "DECIMAL"),
                ColumnDef.value("scrap_rate", "损耗率", "DECIMAL"))
            .build());

        // ============ CAPACITY_ENERGY 组（专用表，5 组）============

        // 11) 产能 — 对应 P08CapacityHandler.capacity（CAP_CONTENT 行37-38 / groupKey 行87-91；resource_group_no 固定 PRICING_DEFAULT）
        reg(PricingSheetDef.builder("CAPACITY", "产能", "CAPACITY_ENERGY", 11)
            .table("capacity", "calc_version", "material_no")
            .fixedGk("resource_group_no", "PRICING_DEFAULT")
            .content("process_no", "production_type", "is_effective")
            .rowKeys("process_no")
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.valueEnum("production_type", "生产类型", List.of("UNIT", "BATCH", "BATCH_FIXED")),
                ColumnDef.value("is_effective", "是否有效", "BOOLEAN"))
            .build());

        // 12) 工时单价 — 对应 P08CapacityHandler.labor_rate（LABOR_CONTENT 行41-42 / groupKey 行107-109）
        reg(PricingSheetDef.builder("LABOR_RATE", "工时单价", "CAPACITY_ENERGY", 12)
            .table("labor_rate", "version_no", "material_no")
            .content("process_no", "standard_labor_rate", "currency", "unit")
            .rowKeys("process_no").scale("standard_labor_rate", 6)
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.value("standard_labor_rate", "人工标准单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // 13) 折旧 — 对应 P09EquipmentDepreciationHandler（CONTENT 行24 / groupKey 行62-65；price_type=DEPRECIATION）
        reg(PricingSheetDef.builder("DEPRECIATION", "折旧", "CAPACITY_ENERGY", 13)
            .table("production_energy", "calc_version", "material_no")
            .priceType("DEPRECIATION")
            .content("process_no", "unit_price", "currency", "unit")
            .rowKeys("process_no").scale("unit_price", 6)
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.value("unit_price", "折旧单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // 14) 能耗 — 对应 P10ProductionEnergyHandler（CONTENT 行24 / groupKey 行62-64；price_type=ENERGY）
        reg(PricingSheetDef.builder("ENERGY", "能耗", "CAPACITY_ENERGY", 14)
            .table("production_energy", "calc_version", "material_no")
            .priceType("ENERGY")
            .content("process_no", "unit_price", "currency", "unit")
            .rowKeys("process_no").scale("unit_price", 6)
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.value("unit_price", "生产能耗单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // 15) 辅助能耗 — 对应 P11AuxiliaryEnergyHandler（CONTENT 行23 / groupKey 行50-52）
        reg(PricingSheetDef.builder("AUX_ENERGY", "辅助能耗", "CAPACITY_ENERGY", 15)
            .table("auxiliary_energy", "calc_version", "material_no")
            .content("process_no", "non_production_energy_price", "currency", "unit")
            .rowKeys("process_no").scale("non_production_energy_price", 6)
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.value("non_production_energy_price", "非生产能耗单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT))
            .build());

        // ============ TOOLING 组（1 组）============

        // 16) 模具工装成本 — 对应 P12ToolingCostHandler（CONTENT 行28-30 / groupKey 行120-122；tooling_unit_price scale 8）
        reg(PricingSheetDef.builder("TOOLING", "模具工装成本", "TOOLING", 16)
            .table("tooling_cost", "calc_version", "material_no")
            .content("process_no", "seq_no", "tooling_no", "tooling_unit_cost", "tool_life",
                     "cycle_output", "tooling_unit_price", "currency", "unit", "is_effective")
            .rowKeys("process_no", "seq_no", "tooling_no")
            .scale("tooling_unit_cost", 6).scale("cycle_output", 6).scale("tooling_unit_price", 8)
            .columns(
                ColumnDef.subDimMaster("process_no", "工序号", "process", "process_name"),
                ColumnDef.nameCol("process_name", "工序名"),
                ColumnDef.value("seq_no", "项次", "NUMBER"),
                ColumnDef.subDimFree("tooling_no", "模具编号", "STRING"),
                ColumnDef.value("tooling_unit_cost", "单个模具成本", "DECIMAL"),
                ColumnDef.value("tool_life", "寿命", "NUMBER"),
                ColumnDef.value("cycle_output", "单循环产量", "DECIMAL"),
                ColumnDef.value("tooling_unit_price", "模具工装成本单价", "DECIMAL"),
                ColumnDef.valueEnum("currency", "币种", CURRENCY),
                ColumnDef.valueEnum("unit", "计量单位", UNIT),
                ColumnDef.value("is_effective", "是否有效", "BOOLEAN"))
            .build());
    }

    private void reg(PricingSheetDef def) {
        byKey.put(def.sheetKey, def);
    }

    /** 全部 16 组（按 order 插入顺序）。 */
    public List<PricingSheetDef> all() {
        return List.copyOf(byKey.values());
    }

    /** 按 sheetKey 取；不存在返 null（调用方转 404）。 */
    public PricingSheetDef get(String sheetKey) {
        return byKey.get(sheetKey);
    }

    public boolean exists(String sheetKey) {
        return byKey.containsKey(sheetKey);
    }
}
