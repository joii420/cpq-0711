package com.cpq.dataset.registry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import static com.cpq.dataset.registry.ColumnDef.col;
import static com.cpq.dataset.registry.ColumnDef.nameCol;

/**
 * 详细核价数据集（`cost-detail`）· sheet 19 张
 * （带版本 17 / 免版本 2），轴 = 生产料号 {@code production_no}。
 *
 * <p><b>本文件与 V407__ds_cost_detail_tables.sql + V408__ds_history_tables.sql 同源</b>，
 * 两侧都逐列抄自 {@code dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md}。
 * 启动时由 {@link DatasetSchemaSelfCheck} 逐表比对 {@code information_schema.columns}，对不上直接启动失败。
 *
 * <p>🚨 {@code sheetName} 与 {@code label} 必须与 Excel 逐字相等 —— 导入靠它们匹配 sheet 与表头。
 * <p>⚠️ 白底列（{@code role=NAME}）在字段矩阵里标 ❌ 不建字段，这里只声明取数来源供 JOIN 带出，<b>不建 DB 列</b>（AC-3）。
 */
@ApplicationScoped
public class CostDetailRegistry extends AbstractDatasetRegistry {

    private static final List<String> CURRENCY = List.of("CNY", "USD", "EUR", "JPY");
    private static final List<String> UNIT = List.of("PCS", "KG", "H", "SET", "M", "G");

    public CostDetailRegistry() {
        super("cost-detail", "详细核价", "ds_cost_detail_",
              "production_no", "生产料号", "ds_cost_detail_material");

        // ── 01 · sheet「物料」 → ds_cost_detail_material（免版本）
        reg(SheetDef.unversioned("MATERIAL", "物料", "ds_cost_detail_material", 1,
                List.of("production_no"), List.of(
                col("production_no", "生产料号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("material_name", "品名", "VALUE", "STRING", "varchar(128)", true, false),
                col("specification", "规格", "VALUE", "STRING", "varchar(128)", false, false),
                col("dimension", "尺寸", "VALUE", "STRING", "varchar(128)", false, false),
                col("old_material_no", "旧料号", "VALUE", "STRING", "varchar(128)", false, false),
                col("unit_weight", "单重", "VALUE", "DECIMAL", "numeric(26,12)", false, false)
        )));

        // ── 02 · sheet「物料BOM」 → ds_cost_detail_material_bom（带版本）
        reg(SheetDef.versioned("MATERIAL_BOM", "物料BOM", "ds_cost_detail_material_bom", 2,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("component_type", "组成类型", "VALUE", "STRING", "varchar(128)", false, true),
                col("component_no", "组成料号", "VALUE", "STRING", "varchar(128)", true, true),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", false, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("usage_characteristic", "使用特性", "VALUE", "STRING", "varchar(128)", false, true),
                col("component_qty", "组成用量", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("component_qty_unit", "组成用量单位", "VALUE", "STRING", "varchar(128)", true, true),
                col("base_qty", "底数", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("base_qty_unit", "底数单位", "VALUE", "STRING", "varchar(128)", true, true),
                col("material_loss_rate", "材料损耗率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("material_fixed_loss", "材料固定损耗量", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("defect_rate", "不良率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 03 · sheet「物料与元素BOM」 → ds_cost_detail_element_bom（带版本）
        reg(SheetDef.versioned("ELEMENT_BOM", "物料与元素BOM", "ds_cost_detail_element_bom", 3,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("material_part_no", "材质料号", "SUBDIM", "STRING", "varchar(128)", true, true).master("recipe", "material_part_no_name"),
                nameCol("material_part_no_name", "品名", "material_part_no", "material_recipe", "code", "name"),
                nameCol("material_part_no_spec", "规格", "material_part_no", "material_recipe", "code", "spec_label"),
                nameCol("material_part_no_dim", "尺寸", "material_part_no", "material_recipe", "code", null),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("element_code", "元素代码", "SUBDIM", "STRING", "varchar(128)", true, true).master("element", null),
                col("content_pct", "组成含量（%）", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("loss_rate", "损耗率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 04 · sheet「产能」 → ds_cost_detail_capacity（带版本）
        reg(SheetDef.versioned("CAPACITY", "产能", "ds_cost_detail_capacity", 4,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("labor_std_price", "人工标准单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 05 · sheet「设备折旧成本」 → ds_cost_detail_depreciation（带版本）
        reg(SheetDef.versioned("DEPRECIATION", "设备折旧成本", "ds_cost_detail_depreciation", 5,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("depreciation_price", "折旧单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 06 · sheet「生产设备能耗」 → ds_cost_detail_production_energy（带版本）
        reg(SheetDef.versioned("PRODUCTION_ENERGY", "生产设备能耗", "ds_cost_detail_production_energy", 6,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("production_energy_price", "生产能耗单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 07 · sheet「辅助设备能耗」 → ds_cost_detail_auxiliary_energy（带版本）
        reg(SheetDef.versioned("AUXILIARY_ENERGY", "辅助设备能耗", "ds_cost_detail_auxiliary_energy", 7,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("auxiliary_energy_price", "非生产能耗单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 08 · sheet「模具工装成本」 → ds_cost_detail_tooling（带版本）
        reg(SheetDef.versioned("TOOLING", "模具工装成本", "ds_cost_detail_tooling", 8,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("tooling_no", "模具台账/工装编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("tooling_cost", "单个模具/工装成本", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("tooling_life", "寿命（次）", "VALUE", "NUMBER", "integer", false, true),
                col("cycle_output", "单循环产量", "VALUE", "NUMBER", "integer", false, true),
                col("tooling_unit_price", "模具工装成本单价", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 09 · sheet「生产耗材BOM」 → ds_cost_detail_consumable（带版本）
        reg(SheetDef.versioned("CONSUMABLE", "生产耗材BOM", "ds_cost_detail_consumable", 9,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("consumable_price", "耗材成本单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 10 · sheet「包装材料BOM」 → ds_cost_detail_packaging（带版本）
        reg(SheetDef.versioned("PACKAGING", "包装材料BOM", "ds_cost_detail_packaging", 10,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("packaging_price", "包装成本单价", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 11 · sheet「来料加工费」 → ds_cost_detail_incoming_process_fee（带版本）
        reg(SheetDef.versioned("INCOMING_PROCESS_FEE", "来料加工费", "ds_cost_detail_incoming_process_fee", 11,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("incoming_material_no", "来料料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("material", "incoming_material_no_name"),
                nameCol("incoming_material_no_name", "品名", "incoming_material_no", "material_master", "production_no", "material_name"),
                nameCol("incoming_material_no_spec", "规格", "incoming_material_no", "material_master", "production_no", "specification"),
                nameCol("incoming_material_no_dim", "尺寸", "incoming_material_no", "material_master", "production_no", "dimension"),
                col("process_fee", "加工费", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("loss_rate", "损耗（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 12 · sheet「来料其他费用」 → ds_cost_detail_incoming_other_fee（带版本）
        reg(SheetDef.versioned("INCOMING_OTHER_FEE", "来料其他费用", "ds_cost_detail_incoming_other_fee", 12,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", false, false),
                col("incoming_material_no", "来料料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("material", "incoming_material_no_name"),
                nameCol("incoming_material_no_name", "品名", "incoming_material_no", "material_master", "production_no", "material_name"),
                nameCol("incoming_material_no_spec", "规格", "incoming_material_no", "material_master", "production_no", "specification"),
                nameCol("incoming_material_no_dim", "尺寸", "incoming_material_no", "material_master", "production_no", "dimension"),
                col("element_item_seq", "要素项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("fee", "费用", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 13 · sheet「来料其他固定费用」 → ds_cost_detail_incoming_other_fixed_fee（带版本）
        reg(SheetDef.versioned("INCOMING_OTHER_FIXED_FEE", "来料其他固定费用", "ds_cost_detail_incoming_other_fixed_fee", 13,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("incoming_material_no", "来料料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("material", "incoming_material_no_name"),
                nameCol("incoming_material_no_name", "品名", "incoming_material_no", "material_master", "production_no", "material_name"),
                nameCol("incoming_material_no_spec", "规格", "incoming_material_no", "material_master", "production_no", "specification"),
                nameCol("incoming_material_no_dim", "尺寸", "incoming_material_no", "material_master", "production_no", "dimension"),
                col("element_item_seq", "要素项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("fee", "费用", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 14 · sheet「加工费&组装费」 → ds_cost_detail_process_assembly_fee（带版本）
        reg(SheetDef.versioned("PROCESS_ASSEMBLY_FEE", "加工费&组装费", "ds_cost_detail_process_assembly_fee", 14,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("process_fee", "加工费", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "计量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("defect_rate", "不良率/拒收率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 15 · sheet「其他外加工成本」 → ds_cost_detail_outsourced_process（带版本）
        reg(SheetDef.versioned("OUTSOURCED_PROCESS", "其他外加工成本", "ds_cost_detail_outsourced_process", 15,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("outsourced_fee", "外加工费用", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 16 · sheet「电镀成本」 → ds_cost_detail_plating_cost（带版本）
        reg(SheetDef.versioned("PLATING_COST", "电镀成本", "ds_cost_detail_plating_cost", 16,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("plating_scheme_no", "电镀方案编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("plating_version", "版本编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("plating_process_fee", "电镀加工费", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("plating_material_fee", "电镀材料费", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("defect_rate", "不良率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 17 · sheet「成品其他比例费用」 → ds_cost_detail_finished_ratio_fee（带版本）
        reg(SheetDef.versioned("FINISHED_RATIO_FEE", "成品其他比例费用", "ds_cost_detail_finished_ratio_fee", 17,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 18 · sheet「成品其他固定费用」 → ds_cost_detail_finished_fixed_fee（带版本）
        reg(SheetDef.versioned("FINISHED_FIXED_FEE", "成品其他固定费用", "ds_cost_detail_finished_fixed_fee", 18,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("fee", "费用", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 19 · sheet「电镀方案」 → ds_cost_detail_plating_scheme（免版本）
        reg(SheetDef.unversioned("PLATING_SCHEME", "电镀方案", "ds_cost_detail_plating_scheme", 19,
                List.of("scheme_no", "scheme_version", "item_seq"), List.of(
                col("scheme_no", "方案编号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("scheme_version", "版本", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "SUBDIM", "NUMBER", "integer", true, false),
                col("plating_element", "电镀元素名称", "VALUE", "STRING", "varchar(256)", false, false),
                col("plating_area", "电镀面积（cm2）", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                col("coating_thickness", "镀层厚度（μm）", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                col("plating_requirement", "电镀要求", "VALUE", "STRING", "varchar(256)", false, false),
                col("density", "密度（g/cm3)", "VALUE", "DECIMAL", "numeric(26,12)", false, false)
        )));

    }
}
