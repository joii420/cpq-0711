package com.cpq.quotation.service.backfill;

import java.util.List;
import java.util.Set;

/**
 * task-0721 报价数据版本升级 · B5 —— 7 张版本化表的「组轴（groupKey）/ 版本列 / 主从关系」静态登记表。
 *
 * <p><b>为什么要硬编码</b>：{@code unit_price} 被 10+ 个 QUOTE 侧 Handler（Q01/Q06~Q11/Q13/Q15/Q17）
 * 用不同的 {@code price_type} 子集共享，各 Handler 的 {@code groupKeyOf} Map 只放自己关心的列
 * （见各 Handler javadoc）。backtask B5.2 要求"复用对应 Handler 的声明,勿另立口径"，但没有一个
 * Handler 声明了"全表统一轴"。本类走查了全部 QUOTE 侧 Handler 源码（2026-07-21），取其
 * <b>并集</b>作为统一轴：某具体 price_type 未用到的轴列在该表行上恒为 NULL，用 NULL 安全比较
 * （{@code IS NOT DISTINCT FROM}，{@code VersionedV6Writer} 已保证）分组结果与"只用窄子集"完全
 * 等价——多出的轴列不会把本该同组的行拆散，也不会把不同组的行错误合并（因为 price_type/code 等
 * 本就已经把组区分开）。此并集口径的正确性已在 backtask 研究阶段用 unique index
 * （{@code uq_unit_price} 等）交叉验证：unique index 列集 = 组轴 ∪ 组内行区分列（seq_no/item_seq/
 * discount_order/effective_date 等），本类只取"组轴"部分（对齐各 Handler 的 groupKey，不含
 * 区分列——区分列留在 content，允许一组内多行）。
 *
 * <p><b>使用方式</b>：B5 回填时不重新发明"哪些列是轴"，而是从 {@code __v6_id} 定位到的行、或
 * 手工新增行的上下文，读出本表登记的轴列集合对应的值，构造 {@code VersionedGroupSpec.groupKeyColumns}；
 * 其余非系统列一律进 {@code contentColumns}（不需要精确复刻每个 Handler 的 CONTENT 常量——
 * "更宽的 contentColumns"只会让升版判定更保守（多看几列变化），不会导致漏判/错分组）。
 */
final class QuoteTableAxis {

    private QuoteTableAxis() {}

    /** 系统列：所有表通用，永不进 groupKey/contentColumns。 */
    static final Set<String> SYSTEM_COLUMNS = Set.of(
        "id", "created_at", "updated_at", "created_by", "updated_by", "is_current", "source",
        "pending_quotation_id", "pending_supersedes");

    /** 单表（非主从）7 张受管表中的 4 张：unit_price / capacity / plating_scheme。 */
    static final class Spec {
        final String table;
        final String versionColumn;
        final List<String> axisColumns;
        /** 内容列：该表全部物理列 - 系统列 - 轴列 - 版本列（据 2026-07-21 DB schema 现场核对，
         *  比逐 Handler CONTENT 常量更宽——多看几列变化只会让升版判定更保守，不会漏判/错分组）。 */
        final List<String> contentColumns;
        /** 主从表：非 null 时本表是「子表」，需要联动写对应主表。 */
        final MasterSpec master;

        Spec(String table, String versionColumn, List<String> axisColumns, List<String> contentColumns, MasterSpec master) {
            this.table = table; this.versionColumn = versionColumn;
            this.axisColumns = axisColumns; this.contentColumns = contentColumns; this.master = master;
        }
    }

    /** 子表对应的主表信息（material_bom / element_bom）。 */
    static final class MasterSpec {
        final String masterTable;
        final String masterVersionColumn;
        /** 主表固定列：从子表行集合派生（见 {@link QuoteBackfillCollector#deriveMasterFixedColumns}）。 */
        final List<String> masterFixedColumnNames;

        MasterSpec(String masterTable, String masterVersionColumn, List<String> masterFixedColumnNames) {
            this.masterTable = masterTable; this.masterVersionColumn = masterVersionColumn;
            this.masterFixedColumnNames = masterFixedColumnNames;
        }
    }

    static final Spec UNIT_PRICE = new Spec("unit_price", "version_no", List.of(
        "system_type", "customer_no", "price_type", "cost_type", "code",
        "supplier_no", "finished_material_no", "operation_no"),
        List.of("name", "specification", "dimension", "seq_no", "plating_scheme_no", "pricing_price",
            "cost_ratio", "market_ref_price", "currency", "unit", "conversion_rate", "recovery_discount",
            "life_qty", "life_unit", "supplier_name", "customer_name", "data_type", "source_url",
            "source_name", "fetch_rule", "premium_fee", "fetched_price", "fetch_time", "effective_date",
            "expire_date", "base_value", "is_fluctuate_with_material", "material_increase_ratio",
            "material_fixed_increase", "defect_rate", "discount_order", "item_seq", "production_no"),
        null);

    static final Spec CAPACITY = new Spec("capacity", "calc_version", List.of(
        "system_type", "material_no", "resource_group_no"),
        List.of("material_name", "specification", "dimension", "process_no", "process_name",
            "resource_group_name", "production_type", "fixed_lead_time", "variable_time",
            "variable_time_batch", "capacity_unit", "default_defect_rate", "cost_type", "fixed_cost",
            "cost_ratio", "annual_discount_factor", "is_effective", "currency", "seq_no", "version_no",
            "production_no"),
        null);

    static final Spec PLATING_SCHEME = new Spec("plating_scheme", "scheme_version", List.of(
        "system_type", "scheme_no"),
        List.of("seq_no", "plating_element", "plating_method", "surface_area", "plating_area",
            "plating_thickness", "plating_requirement", "density", "element_usage", "element_usage_unit",
            "effective_date", "expire_date", "source_url", "source_name", "fetch_rule", "hf_part_no"),
        null);

    static final Spec MATERIAL_BOM_ITEM = new Spec("material_bom_item", "bom_version", List.of(
        "system_type", "customer_no", "material_no"),
        List.of("seq_no", "component_no", "part_no", "effective_datetime", "expire_datetime",
            "operation_no", "operation_seq", "item_seq", "issue_unit", "composition_qty", "base_qty",
            "component_usage_type", "feature_mgmt", "upper_limit_pct", "lower_limit_pct", "scrap_batch",
            "scrap_rate", "fixed_scrap", "issue_location", "issue_storage", "fas_group", "plug_position",
            "ref_rd_center", "is_optional", "wo_expand_option", "is_purchase_replace", "component_lead_time",
            "main_substitute", "attached_part", "ecn_no", "use_qty_formula", "qty_formula", "scrap_rate_type",
            "is_backflush", "is_customer_supply", "defect_rate", "calc_type", "recovery_discount",
            "recovery_currency", "recovery_unit", "rough_weight", "net_weight", "weight_unit",
            "production_no", "characteristic"),
        new MasterSpec("material_bom", "bom_version", List.of("bom_type", "characteristic")));

    static final Spec ELEMENT_BOM_ITEM = new Spec("element_bom_item", "characteristic", List.of(
        "system_type", "customer_no", "material_no", "material_part_no"),
        List.of("component_no", "part_no", "effective_datetime", "expire_datetime", "operation_no",
            "operation_seq", "seq_no", "issue_unit", "composition_qty", "base_qty", "component_usage_type",
            "feature_mgmt", "content", "upper_limit_pct", "lower_limit_pct", "scrap_batch", "scrap_rate",
            "defect_rate", "fixed_scrap", "issue_location", "issue_storage", "fas_group", "plug_position",
            "ref_rd_center", "is_optional", "wo_expand_option", "is_purchase_replace", "component_lead_time",
            "main_substitute", "attached_part", "ecn_no", "use_qty_formula", "qty_formula", "scrap_rate_type",
            "is_backflush", "is_customer_supply", "recovery_discount", "recovery_currency", "recovery_unit",
            "hf_part_no", "production_no"),
        new MasterSpec("element_bom", "characteristic", List.of("bom_type")));

    /** 按表名取登记的轴定义；未登记（非 7 张受管表之一）返回 null。 */
    static Spec of(String table) {
        return switch (table) {
            case "unit_price" -> UNIT_PRICE;
            case "capacity" -> CAPACITY;
            case "plating_scheme" -> PLATING_SCHEME;
            case "material_bom_item" -> MATERIAL_BOM_ITEM;
            case "element_bom_item" -> ELEMENT_BOM_ITEM;
            default -> null;
        };
    }

    /** 7 张受管表全集（回填 B5.1 用于扫描"无 snapshot 表征"的纯 pending 组，路径②）。 */
    static final List<String> ALL_MANAGED_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme");

    /** 路径②/③扫描对象：单表或"子表代表主从组"的 7 张受管表清单（子表齐全即代表整组，主表不单独扫描）。 */
    static final List<String> SCAN_TABLES = List.of(
        "unit_price", "material_bom_item", "element_bom_item", "capacity", "plating_scheme");
}
