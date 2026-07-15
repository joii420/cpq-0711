package com.cpq.component.service;

import com.cpq.component.dto.DryRunSqlViewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

/**
 * 组件 SQL 视图安全校验 + dry-run 列签名提取。
 *
 * <p>方案文档 §4.2 用户 SQL 必要约束：
 * <ol>
 *   <li>禁止 DDL/DML 关键字（INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/TRUNCATE）</li>
 *   <li>禁用 :hfPartNo 标量占位符（保留 :hfPartNos 数组形式由外层 batch 注入）</li>
 *   <li>保存时 EXPLAIN dry-run，确认 PG 能解析</li>
 *   <li>提取 declared_columns 列签名 + required_variables 占位符清单</li>
 * </ol>
 */
@ApplicationScoped
public class SqlViewValidator {

    private static final Logger LOG = Logger.getLogger(SqlViewValidator.class);

    /**
     * V44 + V76 废弃表黑名单（AP-53 + V259）。
     * <p>保存 component_sql_view / template_sql_view 时在 EXPLAIN dry-run 之前先做 token 黑名单扫描：
     * 命中任一 token → success=false，错误码 SQL_VIEW_DEPRECATED_TABLE，HTTP 400。
     */
    private static final List<String> FORBIDDEN_TABLE_TOKENS = Arrays.asList(
            // V44 废弃表
            "mat_part", "mat_bom", "mat_process", "mat_fee",
            "plating_plan", "mat_customer_part_mapping",
            "element_price", "element_daily_price", "customer_tax",
            // V76 废弃表（AP-53 + V259 新增）
            "costing_part_material_bom", "costing_part_element_bom",
            "costing_part_process_cost", "costing_part_plating",
            "costing_part_plating_fee", "costing_part_tooling_cost",
            "costing_part_weight", "costing_part_quality_check",
            "costing_part_design_cost"
    );

    /** 禁用的 DDL/DML 关键字（行边界 + 大小写不敏感）。 */
    private static final Pattern FORBIDDEN_STMT = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|MERGE|REPLACE|CALL|DO)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 提取 :xxx 命名占位符。
     * 负 lookbehind (?<!:) 排除 PG `::cast` 语法（如 ::uuid / ::varchar / ::text），
     * 与 SqlViewExecutor.NAMED_PARAM 同源（V236 + 本次同步修复）。
     */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    /** 禁用标量占位符 :hfPartNo（须用 :hfPartNos 数组形式由外层 batch 注入）。 */
    private static final Pattern FORBIDDEN_PARAM_HF_PART_NO = Pattern.compile(
            ":hfPartNo\\b(?!s)", Pattern.CASE_INSENSITIVE
    );

    @Inject
    DataSource dataSource;

    /**
     * 执行完整校验 + 列签名提取。
     *
     * @param sqlTemplate 待校验 SQL 模板
     * @return DryRunSqlViewResponse（success=true 时含 declaredColumns + requiredVariables）
     */
    public DryRunSqlViewResponse validate(String sqlTemplate) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            return DryRunSqlViewResponse.fail("SQL 模板不能为空");
        }

        // 1. 必须以 SELECT / WITH 开头（去注释 + 空白后判断）
        String stripped = stripCommentsAndWhitespace(sqlTemplate);
        String upperStripped = stripped.toUpperCase();
        if (!upperStripped.startsWith("SELECT") && !upperStripped.startsWith("WITH")) {
            return DryRunSqlViewResponse.fail("SQL 必须以 SELECT 或 WITH 开头");
        }

        // 1.5. 废弃表黑名单扫描（AP-53：V44 + V76 废弃表拒绝）
        //      使用 \b 单词边界防止 mat_part_v6 / costing_part_material_bom_v6 等误报
        String lowerTemplate = sqlTemplate.toLowerCase();
        for (String token : FORBIDDEN_TABLE_TOKENS) {
            if (Pattern.compile("\\b" + Pattern.quote(token) + "\\b")
                       .matcher(lowerTemplate).find()) {
                boolean isV76 = token.startsWith("costing_part_");
                String era = isV76 ? "V76" : "V44";
                return DryRunSqlViewResponse.fail(
                        "SQL 视图 sql_template 不允许引用 " + era + " 已废弃表 `" + token
                        + "`（AP-53，错误码 SQL_VIEW_DEPRECATED_TABLE）。"
                        + "请改用对应 V6 表（material_master / material_bom_item / element_bom_item / "
                        + "fee_config / unit_price / plating_scheme 等）。"
                        + "详见 docs/方案制定前必读.md §V6 基础资料表使用规则。"
                );
            }
        }

        // 2. 禁用 DDL/DML 关键字
        Matcher forbiddenMatcher = FORBIDDEN_STMT.matcher(stripped);
        if (forbiddenMatcher.find()) {
            return DryRunSqlViewResponse.fail(
                    "SQL 中含禁止关键字：" + forbiddenMatcher.group(1).toUpperCase()
                            + "（仅允许 SELECT/WITH 查询语句）"
            );
        }

        // 3. 禁用 :hfPartNo 标量占位符
        if (FORBIDDEN_PARAM_HF_PART_NO.matcher(sqlTemplate).find()) {
            return DryRunSqlViewResponse.fail(
                    "禁止使用 :hfPartNo 标量占位符（料号 batch 由外层注入；批量请使用 :hfPartNos 数组形式）"
            );
        }

        // 3.5 保留 __sk* 前缀（spineKeys 宏内部占位符，禁止作者自定义）
        if (Pattern.compile("(?<!:):__sk", Pattern.CASE_INSENSITIVE).matcher(sqlTemplate).find()) {
            return DryRunSqlViewResponse.fail(
                    "占位符前缀 :__sk 为 spineKeys 宏保留，请勿在 SQL 模板中自定义");
        }

        // 3.5b 保留 __vf* 前缀（task-0713 versionFilter 宏内部占位符，禁止作者自定义）
        if (Pattern.compile("(?<!:):__vf", Pattern.CASE_INSENSITIVE).matcher(sqlTemplate).find()) {
            return DryRunSqlViewResponse.fail(
                    "占位符前缀 :__vf 为 versionFilter 宏保留，请勿在 SQL 模板中自定义");
        }

        // 3.6 展开 :spineKeys(...) / :versionFilter(...) 宏为校验形（无命名占位符），
        //     供后续占位符提取 + EXPLAIN dry-run；存储仍保留原始含宏 sql_template。
        String forValidation;
        try {
            forValidation = com.cpq.datasource.sqlview.SpineKeysMacro.expandForValidation(sqlTemplate);
            forValidation = com.cpq.datasource.sqlview.VersionFilterMacro.expandForValidation(forValidation);
        } catch (IllegalArgumentException e) {
            return DryRunSqlViewResponse.fail("宏语法错误：" + e.getMessage());
        }

        // 4. 提取占位符清单
        List<String> requiredVariables = extractNamedParams(forValidation);

        // 5. EXPLAIN dry-run 拿列签名
        String bound = bindWithNullPlaceholders(forValidation, requiredVariables);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(bound)) {

            // 用 LIMIT 0 子查询包装的方式拿 ResultSetMetaData
            // PG EXPLAIN 不返回列信息，所以用 "SELECT * FROM (<sql>) inner_q LIMIT 0"
            String wrappedSql = "SELECT * FROM (" + bound + ") __probe LIMIT 0";
            try (PreparedStatement probe = conn.prepareStatement(wrappedSql);
                 ResultSet rs = probe.executeQuery()) {

                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                List<DryRunSqlViewResponse.ColumnMeta> columns = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    columns.add(new DryRunSqlViewResponse.ColumnMeta(
                            meta.getColumnLabel(i),
                            meta.getColumnTypeName(i),
                            meta.isNullable(i) != ResultSetMetaData.columnNoNulls
                    ));
                }
                return DryRunSqlViewResponse.ok(columns, requiredVariables);
            }
        } catch (Exception e) {
            LOG.warnf("SQL view dry-run failed: %s", e.getMessage());
            return DryRunSqlViewResponse.fail("SQL 校验失败：" + e.getMessage());
        }
    }

    /**
     * 提取所有 :xxx 命名占位符（去重，保持出现顺序）。
     */
    public List<String> extractNamedParams(String sql) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        while (m.find()) {
            result.add(m.group(1));
        }
        return new ArrayList<>(result);
    }

    /**
     * 把 :xxx 命名占位符替换为 NULL（用于 dry-run；EXPLAIN 时谓词常量化也能通过）。
     * <p>替换正则同样加 (?<!:) 负 lookbehind 避免误伤 PG `::cast`（如 ::uuid → :uuid 被替换 → NULL:NULL 语法错）。
     */
    private String bindWithNullPlaceholders(String sql, List<String> params) {
        String result = sql;
        for (String name : params) {
            // 注意 :hfPartNos 等数组类型在 dry-run 用 ARRAY[]::text[] 兜底
            String value = name.endsWith("s") ? "(ARRAY[]::text[])" : "NULL";
            // 负 lookbehind (?<!:) 排除 `::name` cast 形式，仅替换真正的 `:name` 占位符
            result = result.replaceAll("(?<!:):" + java.util.regex.Pattern.quote(name) + "\\b", value);
        }
        return result;
    }

    /** 简单去 SQL 注释（-- 行注释 + /* 块注释）+ 多空白合一。 */
    private String stripCommentsAndWhitespace(String sql) {
        String noBlockComments = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLineComments = noBlockComments.replaceAll("--[^\\n]*", " ");
        return noLineComments.trim();
    }
}
