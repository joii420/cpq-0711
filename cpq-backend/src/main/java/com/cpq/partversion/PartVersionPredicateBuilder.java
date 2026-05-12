package com.cpq.partversion;

import java.util.Set;

/**
 * 料号版本谓词构建工具 (S6).
 *
 * <p>设计原则: 工具类, 不接入任何主流程. 业务功能不变.
 *
 * <p>提供两种谓词生成模式:
 * <ul>
 *   <li>动态: {@link #buildPredicate(String, String, String)} 调用 V158 的 SQL function
 *       current_part_version(cpn, hf) 取当前激活版本</li>
 *   <li>锁定: {@link #buildPredicateWithLockedVersion(String, int)} 传入显式版本号 (报价单上下文,
 *       使用 quotation_line_item.part_version_locked)</li>
 * </ul>
 *
 * <p>未来集成路径 (后续 PR 启用, feature flag):
 * <pre>
 *   // ImplicitJoinRewriter 拼接 WHERE 时附加:
 *   if (PartVersionPredicateBuilder.isVersionedTable(table) &amp;&amp; ctx.hasPartVersion()) {
 *       sql.append(' ')
 *          .append(PartVersionPredicateBuilder.buildPredicateWithLockedVersion(table, ctx.partVersion));
 *   }
 * </pre>
 */
public final class PartVersionPredicateBuilder {

    /** 14 张参与版本管理的表白名单 (V153 已加 part_version 列). mat_part 不在内. */
    public static final Set<String> VERSIONED_TABLES = Set.of(
            "mat_bom",
            "mat_process",
            "mat_fee",
            "mat_plating_plan",
            "mat_plating_fee",
            "costing_part_process_cost",
            "costing_part_tooling_cost",
            "costing_part_material_bom",
            "costing_part_element_bom",
            "costing_part_quality_check",
            "costing_part_plating",
            "costing_part_plating_fee",
            "costing_part_design_cost",
            "costing_part_weight"
    );

    private PartVersionPredicateBuilder() {}

    /** 判断表是否参与版本管理. */
    public static boolean isVersionedTable(String tableName) {
        if (tableName == null) return false;
        return VERSIONED_TABLES.contains(tableName.trim().toLowerCase());
    }

    /**
     * 动态谓词: 调用 V158 SQL function current_part_version(cpn, hf) 取当前激活版本.
     * 适合"主数据上下文" — 无报价单锁版本时.
     *
     * <p>注意: cpnParam / hfParam 通常应该是 PreparedStatement 占位符 (?), 由调用方做参数化.
     * 此方法仅生成 SQL 模板供拼接.
     *
     * @return 形如 " AND mat_process.part_version = current_part_version(?, ?)"
     */
    public static String buildPredicate(String tableAlias, String cpnParam, String hfParam) {
        if (tableAlias == null || tableAlias.isBlank()) {
            return "";
        }
        String alias = tableAlias.trim();
        String cpn = (cpnParam == null || cpnParam.isBlank()) ? "?" : cpnParam;
        String hf = (hfParam == null || hfParam.isBlank()) ? "?" : hfParam;
        return " AND " + alias + ".part_version = current_part_version(" + cpn + ", " + hf + ")";
    }

    /**
     * 锁定版本谓词: 直接用传入的版本号.
     * 适合"报价单上下文" — 取 quotation_line_item.part_version_locked.
     *
     * @return 形如 " AND mat_process.part_version = 2000"
     */
    public static String buildPredicateWithLockedVersion(String tableAlias, int version) {
        if (tableAlias == null || tableAlias.isBlank()) {
            return "";
        }
        return " AND " + tableAlias.trim() + ".part_version = " + version;
    }

    /**
     * 拼接版本谓词到现有 WHERE SQL.
     * 仅当表参与版本管理且 version >= 2000 (合法版本号) 才追加, 否则 no-op.
     */
    public static String appendIfVersioned(String existingWhereSql, String tableAlias, int version) {
        if (!isVersionedTable(tableAlias) || version < 2000) {
            return existingWhereSql == null ? "" : existingWhereSql;
        }
        String base = existingWhereSql == null ? "" : existingWhereSql;
        return base + buildPredicateWithLockedVersion(tableAlias, version);
    }
}
