package com.cpq.dataset.registry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import static com.cpq.dataset.registry.ColumnDef.col;
import static com.cpq.dataset.registry.ColumnDef.nameCol;

/**
 * 报价数据数据集（`quote`）· sheet 16 张
 * （带版本 13 / 免版本 3），轴 = 销售料号 {@code material_no}。
 *
 * <p><b>本文件与 V405__ds_quote_tables.sql + V408__ds_history_tables.sql 同源</b>，
 * 两侧都逐列抄自 {@code dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md}。
 * 启动时由 {@link DatasetSchemaSelfCheck} 逐表比对 {@code information_schema.columns}，对不上直接启动失败。
 *
 * <p>🚨 {@code sheetName} 与 {@code label} 必须与 Excel 逐字相等 —— 导入靠它们匹配 sheet 与表头。
 * <p>⚠️ 白底列（{@code role=NAME}）在字段矩阵里标 ❌ 不建字段，这里只声明取数来源供 JOIN 带出，<b>不建 DB 列</b>（AC-3）。
 */
@ApplicationScoped
public class QuoteRegistry extends AbstractDatasetRegistry {

    private static final List<String> CURRENCY = List.of("CNY", "USD", "EUR", "JPY");
    private static final List<String> UNIT = List.of("PCS", "KG", "H", "SET", "M", "G");
    /**
     * 料号类型的<b>受控值域</b>（D-30 / AC-64）。走 {@code strictOptions} ⇒ 域外值 Phase 1 整份拒收。
     * <p>🚫 别顺手改成 {@code options} —— 那样 {@code 零部件} 会静默入库，
     * 而下游取数配置器发的是 {@code WHERE material_type IN ('零件','外购件')}，
     * 症状是「配置器里那个页签少行」，离根因隔了两层。
     */
    static final List<String> MATERIAL_TYPE = List.of("零件", "外购件");

    public QuoteRegistry() {
        super("quote", "报价数据", "ds_quote_",
              "material_no", "销售料号", "ds_quote_material");

        // ── 01 · sheet「物料」 → ds_quote_material（免版本）
        reg(SheetDef.unversioned("MATERIAL", "物料", "ds_quote_material", 1,
                List.of("material_no"), List.of(
                col("material_no", "销售料号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("material_name", "品名", "VALUE", "STRING", "varchar(128)", true, false),
                col("specification", "规格", "VALUE", "STRING", "varchar(128)", false, false),
                col("dimension", "尺寸", "VALUE", "STRING", "varchar(128)", false, false),
                col("old_material_no", "旧料号", "VALUE", "STRING", "varchar(128)", false, false),
                col("unit_weight", "单重", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                // D-29（AC-62）+ D-31（AC-65）：生产料号有第二条写入路径（PUT /parts/{axisValue}），
                // 故 ① 进单列更新白名单 ② 导入时 Excel 空着不覆盖旧值（COALESCE）。
                col("production_no", "生产料号", "VALUE", "STRING", "varchar(128)", false, false)
                        .partEditable().preserveOnNull(),
                // D-30（AC-64）：硬枚举，域外值整份拒收；空值仍放行（required=false 不因加枚举而变）。
                col("material_type", "类型", "VALUE", "STRING", "varchar(128)", false, false)
                        .strictOptions(MATERIAL_TYPE),
                // D-27（AC-59/60/61）：产品分类编码。🟡 选填 —— 空值由 Phase 1 填 000000（默认分类），
                // 🚫 不是靠 DB 列 DEFAULT（全列 INSERT 下永不触发，R-1.7 实证）。
                // 只有报价侧这一张表有；核价两套的物料表【不加】。
                col("category_code", "产品分类", "VALUE", "STRING", "varchar(128)", false, false)
                        .categoryRef()
        )));

        // ── 02 · sheet「客户料号」 → ds_quote_customer_part（免版本）
        reg(SheetDef.unversioned("CUSTOMER_PART", "客户料号", "ds_quote_customer_part", 2,
                List.of("customer_no", "customer_product_no"), List.of(
                col("customer_no", "客户编号", "SUBDIM", "STRING", "varchar(20)", true, false).master("customer", null),
                col("customer_part_name", "客户料号名称", "VALUE", "STRING", "varchar(256)", false, false),
                col("customer_product_no", "客户产品编号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("customer_drawing_no", "客户图号", "VALUE", "STRING", "varchar(128)", false, false),
                col("material_no", "销售料号", "VALUE", "STRING", "varchar(128)", true, false)
        )));

        // ── 03 · sheet「物料BOM」 → ds_quote_material_bom（带版本）
        reg(SheetDef.versioned("MATERIAL_BOM", "物料BOM", "ds_quote_material_bom", 3,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("unit_weight", "单重", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("output_material_type", "产出料号类型", "VALUE", "STRING", "varchar(128)", false, true),
                col("component_qty", "组成数量", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("gross_weight", "材料毛重", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("net_weight", "材料净重", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("weight_unit", "重量单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("material_ratio", "材料占比（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("loss_rate", "损耗率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("defect_rate", "不良率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 04 · sheet「物料与元素BOM」 → ds_quote_element_bom（带版本）
        reg(SheetDef.versioned("ELEMENT_BOM", "物料与元素BOM", "ds_quote_element_bom", 4,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("material_part_no", "材质料号", "SUBDIM", "STRING", "varchar(128)", true, true).master("recipe", "material_part_no_name"),
                nameCol("material_part_no_name", "材质料号名称", "material_part_no", "material_recipe", "code", "name"),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("element_code", "元素", "SUBDIM", "STRING", "varchar(128)", true, true).master("element", null),
                col("content_pct", "组成含量（%）", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("loss_rate", "损耗率%", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("gross_usage", "毛用量", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("gross_usage_unit", "毛用量单位", "VALUE", "STRING", "varchar(128)", false, true),
                col("net_usage", "净用量", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("net_usage_unit", "净用量单位", "VALUE", "STRING", "varchar(128)", false, true),
                col("recovery_discount", "回收折扣(%)", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("recovery_qty", "回收量", "VALUE", "STRING", "varchar(128)", false, true)
        )));

        // ── 05 · sheet「来料固定加工费」 → ds_quote_incoming_fixed_fee（带版本）
        reg(SheetDef.versioned("INCOMING_FIXED_FEE", "来料固定加工费", "ds_quote_incoming_fixed_fee", 5,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("base_value", "基准值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("follow_material_price", "是否随材料价格波动", "VALUE", "STRING", "boolean", false, true),
                col("material_increase_ratio", "材料结算涨幅比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("material_increase_value", "材料固定的涨幅值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("increase_currency", "涨幅货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("increase_unit", "涨幅单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 06 · sheet「来料其他费用」 → ds_quote_incoming_other_fee（带版本）
        reg(SheetDef.versioned("INCOMING_OTHER_FEE", "来料其他费用", "ds_quote_incoming_other_fee", 6,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("element_item_seq", "要素项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("value", "值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 07 · sheet「来料回收折扣」 → ds_quote_incoming_recovery（带版本）
        reg(SheetDef.versioned("INCOMING_RECOVERY", "来料回收折扣", "ds_quote_incoming_recovery", 7,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("recovery_discount", "回收折扣（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("recovery_value", "回收值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("recovery_source", "回收来源", "VALUE", "STRING", "varchar(256)", false, true)
        )));

        // ── 08 · sheet「自制加工费」 → ds_quote_self_process_fee（带版本）
        reg(SheetDef.versioned("SELF_PROCESS_FEE", "自制加工费", "ds_quote_self_process_fee", 8,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("operation_item_seq", "工序项次", "VALUE", "NUMBER", "integer", false, true),
                col("operation_no", "工序编号", "SUBDIM", "STRING", "varchar(128)", false, true).master("process", "operation_no_name"),
                nameCol("operation_no_name", "工序名称", "operation_no", "process_master", "process_no", "process_name"),
                col("value", "值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 09 · sheet「成品其他费用」 → ds_quote_finished_other_fee（带版本）
        reg(SheetDef.versioned("FINISHED_OTHER_FEE", "成品其他费用", "ds_quote_finished_other_fee", 9,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("value", "值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("ratio_pct", "比例（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 10 · sheet「组成件其他费用」 → ds_quote_sub_component_fee（带版本）
        reg(SheetDef.versioned("SUB_COMPONENT_FEE", "组成件其他费用", "ds_quote_sub_component_fee", 10,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("sub_component_no", "组成件料号", "VALUE", "STRING", "varchar(128)", true, true).masterNoCheck("material", "sub_component_no_name"),
                nameCol("sub_component_no_name", "组成件名称", "sub_component_no", "material_master", "material_no", "material_name"),
                col("supplier_no", "供应商编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("supplier_name", "供应商名称", "VALUE", "STRING", "varchar(256)", false, true),
                col("element_item_seq", "要素项次", "VALUE", "NUMBER", "integer", true, true),
                col("element_name", "要素名称", "VALUE", "STRING", "varchar(256)", true, true),
                col("value", "值", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT)
        )));

        // ── 11 · sheet「组装加工费」 → ds_quote_assembly_fee（带版本）
        reg(SheetDef.versioned("ASSEMBLY_FEE", "组装加工费", "ds_quote_assembly_fee", 11,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                col("assembly_operation", "组装工序", "VALUE", "STRING", "varchar(128)", true, true),
                col("assembly_fee", "组装加工费", "VALUE", "DECIMAL", "numeric(26,12)", true, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("defect_rate", "拒收率/不良率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 12 · sheet「组装加工费年降」 → ds_quote_assembly_fee_annual（带版本）
        reg(SheetDef.versioned("ASSEMBLY_FEE_ANNUAL", "组装加工费年降", "ds_quote_assembly_fee_annual", 12,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", false, false),
                col("assembly_operation", "组装工序", "VALUE", "STRING", "varchar(128)", false, true),
                col("discount_seq", "年降顺序", "VALUE", "NUMBER", "integer", false, true),
                col("discount_rate", "年降系数（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("fixed_discount_value", "单次固定年降值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("discount_times", "降价次数", "VALUE", "NUMBER", "integer", false, true)
        )));

        // ── 13 · sheet「电镀费用」 → ds_quote_plating_fee（带版本）
        reg(SheetDef.versioned("PLATING_FEE", "电镀费用", "ds_quote_plating_fee", 13,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("plating_scheme_no", "电镀方案编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("plating_version", "版本编号", "VALUE", "STRING", "varchar(128)", false, true),
                col("plating_process_fee", "电镀加工费", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("plating_material_fee", "电镀材料费", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("defect_rate", "不良率（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true)
        )));

        // ── 14 · sheet「电镀方案」 → ds_quote_plating_scheme（免版本）
        reg(SheetDef.unversioned("PLATING_SCHEME", "电镀方案", "ds_quote_plating_scheme", 14,
                List.of("scheme_no", "scheme_version", "item_seq"), List.of(
                col("scheme_no", "方案编号", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("scheme_version", "版本", "SUBDIM", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "SUBDIM", "NUMBER", "integer", true, false),
                col("plating_element", "电镀元素名称", "VALUE", "STRING", "varchar(256)", false, false),
                col("price_source_url", "元素单价来源网站网址", "VALUE", "STRING", "varchar(512)", false, false),
                col("price_source_name", "元素单价来源网站名称", "VALUE", "STRING", "varchar(256)", false, false),
                col("price_fetch_rule", "元素单价抓取规则", "VALUE", "STRING", "varchar(256)", false, false),
                col("plating_area", "电镀面积（cm2）", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                col("coating_thickness", "镀层厚度（μm）", "VALUE", "DECIMAL", "numeric(26,12)", false, false),
                col("plating_requirement", "电镀要求", "VALUE", "STRING", "varchar(256)", false, false)
        )));

        // ── 15 · sheet「来料年降」 → ds_quote_incoming_annual（带版本）
        reg(SheetDef.versioned("INCOMING_ANNUAL", "来料年降", "ds_quote_incoming_annual", 15,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("item_seq", "项次", "VALUE", "NUMBER", "integer", false, false),
                col("input_material_no", "投入料号", "VALUE", "STRING", "varchar(128)", false, true).masterNoCheck("recipe", "input_material_no_name"),
                nameCol("input_material_no_name", "投入料号名称", "input_material_no", "material_recipe", "code", "name"),
                col("discount_seq", "年降顺序", "VALUE", "NUMBER", "integer", false, true),
                col("discount_rate", "年降系数（%）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("fixed_discount_value", "单次固定年降值", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("discount_times", "降价次数", "VALUE", "NUMBER", "integer", false, true)
        )));

        // ── 16 · sheet「年降系数」 → ds_quote_annual_discount（带版本）
        reg(SheetDef.versioned("ANNUAL_DISCOUNT", "年降系数", "ds_quote_annual_discount", 16,
                "material_no", "销售料号", List.of(
                col("material_no", "销售料号", "AXIS", "STRING", "varchar(128)", true, false),
                col("discount_seq", "年降顺序", "VALUE", "NUMBER", "integer", false, true),
                col("discount_rate", "年降系数（%/年）", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("fixed_discount_value", "单次固定年降金额", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                col("currency", "货币", "VALUE", "ENUM", "varchar(128)", false, true).options(CURRENCY),
                col("pricing_unit", "计价单位", "VALUE", "ENUM", "varchar(128)", false, true).options(UNIT),
                col("discount_times", "降价次数", "VALUE", "NUMBER", "integer", false, true)
        )));

    }
}
