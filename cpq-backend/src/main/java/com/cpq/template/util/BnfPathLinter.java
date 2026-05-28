package com.cpq.template.util;

import com.cpq.datasource.sqlview.SqlViewRuntimeContext.OwnerType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * BNF 路径质量 Lint 工具（Phase 2 迁移自 costing.util 包）。
 *
 * <p>职责：检查 {@code variable_path} 是否合规，输出 {@link LintResult}（OK / WARN / ERROR）。
 *
 * <p>调用场景：
 * <ul>
 *   <li>保存模板列时：ownerType=TEMPLATE，status=DRAFT → ERROR 阻断保存</li>
 *   <li>发布模板时：ownerType=TEMPLATE，status=PUBLISHED → ERROR 强阻断发布</li>
 *   <li>盘点 legacy 路径端点（GET /legacy-paths）：全量扫描所有模板列</li>
 * </ul>
 *
 * <p>AP-53：V44 废弃表黑名单（mat_part / mat_bom / mat_process / mat_fee / plating_plan /
 * mat_customer_part_mapping / element_price* / element_daily_price / customer_tax）。
 *
 * <p>V249 起 ownerType=TEMPLATE（从 COSTING_TEMPLATE 改名），校验语义不变：禁止 $$ 跨引用。
 */
@ApplicationScoped
public class BnfPathLinter {

    // ─────────────────── V44 废弃表黑名单（AP-53）───────────────────────────

    /**
     * V44 + V76 废弃表前缀（AP-53 规定）。命中即代表使用了老表直引。
     * <p>V259: 补充 V76 costing_part_* 9 个关键词（原 V44 9 个 + V76 9 个 = 18 个总计）。
     * 错误码：SQL_VIEW_DEPRECATED_TABLE，HTTP 400。
     */
    private static final Set<String> DEPRECATED_TABLE_PREFIXES = Set.of(
            // V44 废弃表（AP-53 原始条目）
            "mat_part",
            "mat_bom",
            "mat_process",
            "mat_fee",
            "plating_plan",
            "mat_customer_part_mapping",
            "element_price",
            "element_daily_price",
            "customer_tax",
            // V76 废弃表（AP-53 + V259 新增）
            "costing_part_material_bom",
            "costing_part_element_bom",
            "costing_part_process_cost",
            "costing_part_plating",
            "costing_part_plating_fee",
            "costing_part_tooling_cost",
            "costing_part_weight",
            "costing_part_quality_check",
            "costing_part_design_cost"
    );

    /** {code} 简写（lineItem 字段变量），如 {code} / {hf_part_no} / {qty} 等。 */
    private static final Pattern LEGACY_VAR_CODE = Pattern.compile("^\\{[a-zA-Z_][a-zA-Z0-9_.]*\\}$");

    // ─────────────────── 公开 API ─────────────────────────────────────────────

    /**
     * Lint 一条 variable_path。
     *
     * @param variablePath BNF 路径（如 {@code $view.col} / {@code v_xxx.col} / {@code {code}} 等）
     * @param ownerType    所有者类型（影响 $$ 跨引用是否允许；TEMPLATE 禁止 $$）
     * @param status       模板状态（DRAFT / PUBLISHED；PUBLISHED 状态 ERROR 强阻断）
     * @return {@link LintResult}
     */
    public LintResult lint(String variablePath, OwnerType ownerType, String status) {
        if (variablePath == null || variablePath.isBlank()) {
            return LintResult.ok("空路径，跳过 lint");
        }
        String p = variablePath.trim();

        // 1. {code} 简写（lineItem 字段变量）— 无风险，直接 OK
        if (LEGACY_VAR_CODE.matcher(p).matches()) {
            return LintResult.ok("{code} 变量引用，OK");
        }

        // 2. $$ 跨组件形态
        if (p.startsWith("$$")) {
            if (ownerType == OwnerType.TEMPLATE) {
                return LintResult.error(
                        "模板 Excel 视图路径不允许跨组件引用（$$ 形态）。请改用本模板自有的 SQL 视图 $<view>。",
                        "将 " + p + " 替换为 $<本模板视图名>.<列名>");
            }
            // 组件上下文：GLOBAL 跨组件引用，合规
            return LintResult.ok("$$ 跨组件 GLOBAL 引用，仅允许在组件上下文使用");
        }

        // 3. $ 本 owner 形态（$view.col / $view[pred].col）— 合规
        if (p.startsWith("$")) {
            return LintResult.ok("$ 本 owner SQL 视图引用，OK");
        }

        // 4. PG 直引形态（v_xxx.col / mat_xxx.col 等）— 检测废弃表黑名单
        String pathLower = p.toLowerCase();
        for (String deprecated : DEPRECATED_TABLE_PREFIXES) {
            if (pathLower.startsWith(deprecated + ".") || pathLower.startsWith(deprecated + "[")) {
                boolean isV76 = deprecated.startsWith("costing_part_");
                String era = isV76 ? "V76" : "V44";
                String msg = "路径含 " + era + " 废弃表 " + deprecated + "（AP-53 规定禁用直引）。"
                        + "错误码：SQL_VIEW_DEPRECATED_TABLE。";
                String suggestion = "新建本模板 SQL 视图，SQL 使用对应 V6 表（material_bom_item / element_bom_item / "
                        + "fee_config / unit_price / plating_scheme 等），路径改为 $<view>." + extractColumn(p);
                // PUBLISHED 状态强 ERROR；DRAFT 状态降级 WARN（给迁移窗口）
                if ("PUBLISHED".equals(status)) {
                    return LintResult.error(msg, suggestion);
                }
                return LintResult.warn(msg + "（DRAFT 状态允许保留，发布前须迁移）", suggestion);
            }
        }

        // 5. v_ 开头（PG 视图直引，非废弃表但也是 AP-53 的迁移目标）
        if (pathLower.startsWith("v_")) {
            String suggestion = "建议新建本模板 SQL 视图，引用 SELECT ... FROM " + extractTableName(p)
                    + "，路径改为 $<view>." + extractColumn(p);
            return LintResult.warn(
                    "PG 视图直引建议迁移到本模板自有 SQL 视图（AP-53 治理目标）。",
                    suggestion);
        }

        // 6. 其他（自定义表名、函数引用等）— 不强制，返 OK
        return LintResult.ok("未识别形态，视为合规");
    }

    // ─────────────────── 工具方法 ─────────────────────────────────────────────

    /** 从 "table.col" 形态提取 col 部分（容错）。 */
    private static String extractColumn(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    /** 从 "table.col" 形态提取 table 部分（容错）。 */
    private static String extractTableName(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int end = dot >= 0 ? dot : path.length();
        if (bracket >= 0 && bracket < end) end = bracket;
        return path.substring(0, end);
    }

    // ─────────────────── 结果类型 ─────────────────────────────────────────────

    public enum LintLevel {
        OK,
        WARN,
        ERROR
    }

    public static final class LintResult {
        public final LintLevel level;
        public final String message;
        public final String suggestion;

        private LintResult(LintLevel level, String message, String suggestion) {
            this.level = level;
            this.message = message;
            this.suggestion = suggestion;
        }

        public static LintResult ok(String message) {
            return new LintResult(LintLevel.OK, message, null);
        }

        public static LintResult warn(String message, String suggestion) {
            return new LintResult(LintLevel.WARN, message, suggestion);
        }

        public static LintResult error(String message, String suggestion) {
            return new LintResult(LintLevel.ERROR, message, suggestion);
        }

        public boolean isOk()    { return level == LintLevel.OK; }
        public boolean isWarn()  { return level == LintLevel.WARN; }
        public boolean isError() { return level == LintLevel.ERROR; }

        @Override
        public String toString() {
            return level + ": " + message + (suggestion != null ? " [建议: " + suggestion + "]" : "");
        }
    }
}
