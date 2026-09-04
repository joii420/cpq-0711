package com.cpq.dataset.registry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import static com.cpq.dataset.registry.ColumnDef.col;
import static com.cpq.dataset.registry.ColumnDef.nameCol;

/**
 * 基础核价数据集（`cost-basic`）· sheet 10 张
 * （带版本 9 / 免版本 1），轴 = 生产料号 {@code production_no}。
 *
 * <p><b>本文件与 V406__ds_cost_basic_tables.sql + V408__ds_history_tables.sql 同源</b>，
 * 两侧都逐列抄自 {@code dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md}。
 * 启动时由 {@link DatasetSchemaSelfCheck} 逐表比对 {@code information_schema.columns}，对不上直接启动失败。
 *
 * <p>🚨 {@code sheetName} 与 {@code label} 必须与 Excel 逐字相等 —— 导入靠它们匹配 sheet 与表头。
 * <p>⚠️ 白底列（{@code role=NAME}）在字段矩阵里标 ❌ 不建字段，这里只声明取数来源供 JOIN 带出，<b>不建 DB 列</b>（AC-3）。
 */
@ApplicationScoped
public class CostBasicRegistry extends AbstractDatasetRegistry {

    private static final List<String> CURRENCY = List.of("CNY", "USD", "EUR", "JPY");
    private static final List<String> UNIT = List.of("PCS", "KG", "H", "SET", "M", "G");

    public CostBasicRegistry() {
        super("cost-basic", "基础核价", "ds_cost_basic_",
              "production_no", "生产料号", "ds_cost_basic_material");

        // ── 01 · sheet「物料」 → ds_cost_basic_material（免版本）
        reg(SheetDef.unversioned("MATERIAL", "物料", "ds_cost_basic_material", 1,
                List.of("production_no"), List.of(
                col("production_no", "生产料号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("material_name", "品名", "VALUE", "STRING", "varchar(128)", true, false),
                col("specification", "规格", "VALUE", "STRING", "varchar(128)", false, false),
                col("dimension", "尺寸", "VALUE", "STRING", "varchar(128)", false, false),
                col("old_material_no", "旧料号", "VALUE", "STRING", "varchar(128)", false, false),
                col("unit_weight", "单重", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                // D-30（AC-64）：硬枚举 零件/外购件，域外值 Phase 1 整份拒收；空值仍放行。
                // 三套物料表都有这一列 ⇒ 三套都校验（与 D-27 的「产品分类只加报价侧」不是同一批）。
                col("material_type", "类型", "VALUE", "STRING", "varchar(128)", false, false)
                        .strictOptions(QuoteRegistry.MATERIAL_TYPE)
        )));

        // ── 02 · sheet「物料BOM」 → ds_cost_basic_material_bom（带版本）
        reg(SheetDef.versioned("MATERIAL_BOM", "物料BOM", "ds_cost_basic_material_bom", 2,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
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

        // ── 03 · sheet「物料与元素BOM」 → ds_cost_basic_element_bom（带版本）
        reg(SheetDef.versioned("ELEMENT_BOM", "物料与元素BOM", "ds_cost_basic_element_bom", 3,
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

        // ── 04 · sheet「来料加工费」 → ds_cost_basic_incoming_process_fee（带版本）
        reg(SheetDef.versioned("INCOMING_PROCESS_FEE", "来料加工费", "ds_cost_basic_incoming_process_fee", 4,
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

        // ── 05 · sheet「来料其他费用」 → ds_cost_basic_incoming_other_fee（带版本）
        reg(SheetDef.versioned("INCOMING_OTHER_FEE", "来料其他费用", "ds_cost_basic_incoming_other_fee", 5,
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

        // ── 06 · sheet「来料其他固定费用」 → ds_cost_basic_incoming_other_fixed_fee（带版本）
        reg(SheetDef.versioned("INCOMING_OTHER_FIXED_FEE", "来料其他固定费用", "ds_cost_basic_incoming_other_fixed_fee", 6,
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

        // ── 07 · sheet「加工费&组装费」 → ds_cost_basic_process_assembly_fee（带版本）
        reg(SheetDef.versioned("PROCESS_ASSEMBLY_FEE", "加工费&组装费", "ds_cost_basic_process_assembly_fee", 7,
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

        // ── 08 · sheet「其他外加工成本」 → ds_cost_basic_outsourced_process（带版本）
        reg(SheetDef.versioned("OUTSOURCED_PROCESS", "其他外加工成本", "ds_cost_basic_outsourced_process", 8,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", true, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("outsourced_fee", "外加工费用", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("unit", "单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 09 · sheet「成品其他比例费用」 → ds_cost_basic_finished_ratio_fee（带版本）
        reg(SheetDef.versioned("FINISHED_RATIO_FEE", "成品其他比例费用", "ds_cost_basic_finished_ratio_fee", 9,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 10 · sheet「成品其他固定费用」 → ds_cost_basic_finished_fixed_fee（带版本）
        reg(SheetDef.versioned("FINISHED_FIXED_FEE", "成品其他固定费用", "ds_cost_basic_finished_fixed_fee", 10,
                "production_no", "生产料号", List.of(
                col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                nameCol("production_no_name", "品名", "production_no", "material_master", "production_no", "material_name"),
                nameCol("production_no_spec", "规格", "production_no", "material_master", "production_no", "specification"),
                nameCol("production_no_dim", "尺寸", "production_no", "material_master", "production_no", "dimension"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("fee", "费用", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "币种", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

    }
}
