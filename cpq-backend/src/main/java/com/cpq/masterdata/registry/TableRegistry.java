package com.cpq.masterdata.registry;

import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hard-coded registry of the 13 v5.1 physical tables that UI-4 (Master Data Maintenance)
 * exposes for read-only browsing.
 *
 * <p>Rationale: the 13 entities were deliberately NOT modelled as JPA entities (architect
 * decision: use raw SQL only). A compile-time registry avoids reflection-based discovery
 * and keeps the list stable across refactorings.
 */
@ApplicationScoped
public class TableRegistry {

    public record TableMeta(
            String tableName,
            String displayName,
            String group,          // GLOBAL | CUSTOMER | ELEMENT
            boolean customerScoped,
            boolean v1Enabled,
            String primaryKeyField,
            String searchField
    ) {}

    private static final List<TableMeta> ALL = List.of(
            new TableMeta("mat_part",                       "物料主档",             "GLOBAL",   false, true,  "part_no",        "part_no"),
            new TableMeta("mat_bom",                        "物料 BOM",             "GLOBAL",   false, true,  "id",             "hf_part_no"),
            new TableMeta("plating_plan",                   "电镀方案 (V125 弃用)", "GLOBAL",   false, true,  "id",             "plan_code"),
            new TableMeta("mat_plating_plan",               "电镀方案 (报价)",      "GLOBAL",   false, true,  "id",             "plan_code"),
            new TableMeta("mat_customer_part_mapping",      "客户料号映射",         "CUSTOMER", true,  true,  "id",             "hf_part_no"),
            new TableMeta("mat_process",                    "工艺基础",             "CUSTOMER", true,  true,  "id",             "hf_part_no"),
            new TableMeta("mat_composite_process",          "组合工艺 (跨子件)",    "GLOBAL",   false, true,  "id",             "hf_part_no"),
            new TableMeta("mat_fee",                        "料号费用",             "CUSTOMER", true,  true,  "id",             "hf_part_no"),
            new TableMeta("plating_fee",                    "电镀费用 (V125 弃用)", "CUSTOMER", true,  true,  "id",             "hf_part_no"),
            new TableMeta("mat_plating_fee",                "电镀费用 (报价)",      "CUSTOMER", true,  true,  "id",             "hf_part_no"),
            new TableMeta("costing_part_plating_fee",       "电镀费用 (核价)",      "GLOBAL",   false, true,  "id",             "hf_part_no"),
            new TableMeta("exchange_rate",                  "汇率",                 "CUSTOMER", true,  true,  "id",             "from_currency"),
            new TableMeta("customer_tax",                   "客户税率",             "CUSTOMER", true,  true,  "id",             "id"),
            new TableMeta("element_price",                  "元素价格",             "ELEMENT",  true,  false, "id",             "element_name"),
            new TableMeta("element_daily_price",            "元素每日价格",         "ELEMENT",  false, false, "id",             "element_name"),
            new TableMeta("element_price_source",           "元素价格来源",         "ELEMENT",  false, false, "id",             "source_name"),
            new TableMeta("element_price_fetch_rule",       "元素价格抓取规则",     "ELEMENT",  false, false, "id",             "rule_code")
    );

    private static final Map<String, TableMeta> INDEX =
            ALL.stream().collect(Collectors.toUnmodifiableMap(TableMeta::tableName, Function.identity()));

    /** Look up metadata by table name. */
    public Optional<TableMeta> get(String tableName) {
        return Optional.ofNullable(INDEX.get(tableName));
    }

    /** All registered tables (V125: 16). */
    public List<TableMeta> all() {
        return ALL;
    }

    /**
     * Return the TableMeta for {@code tableName}, or throw a 400 BusinessException
     * if the table is not registered.
     * <p>Note: v1Enabled=false tables are NOT rejected here — the service layer is
     * responsible for returning the v1Disabled flag instead of querying the DB.
     */
    public TableMeta requireEnabled(String tableName) {
        return get(tableName).orElseThrow(
                () -> new BusinessException(400, "INVALID_TABLE: 未知表名 '" + tableName + "'"));
    }
}
