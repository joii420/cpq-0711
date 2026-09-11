package com.cpq.component.service;

import com.cpq.datasource.sqlview.VersionFilterMacro;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 核价树递归 SQL 保存期 dry-run 校验器。
 *
 * <p>契约：递归 SQL 必须引用具名参数 {@code :production_part_nos}（text[]），
 * 输出列必须逐字包含 {@code root_no / material_no / bom_version / parent_no / node_path} 五列。
 * 保存前用空 seed（{@code ARRAY[]::text[]}）包一层 {@code LIMIT 0} 子查询探测，
 * 既验证可执行性，又不产生实际数据行。
 */
@ApplicationScoped
public class CostingTreeSqlValidator {

    @Inject
    DataSource dataSource;

    /**
     * task-260911：行级维度集合占位符（{@code :<列>s}）的名字来源。
     * dry-run 走裸 JDBC，模板里留一个未绑定的 {@code :xxx} 会让 PG 直接报语法错，必须加桩；
     * 🚫 名字不许写死（AC-9：加第二个 {@code ROW_SCOPE} 列不得要求改 Java）。
     */
    @Inject
    com.cpq.semanticgraph.service.SemanticGraphLoader semanticGraphLoader;

    public static final List<String> REQUIRED_COLS = List.of("root_no", "material_no", "bom_version", "parent_no", "node_path");

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public Result validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(false, "SQL 不能为空");
        }
        if (!sql.contains(":production_part_nos")) {
            return new Result(false, "递归 SQL 必须引用 :production_part_nos");
        }
        String forValidation;
        try {
            forValidation = VersionFilterMacro.expandForValidation(sql);
        } catch (IllegalArgumentException e) {
            return new Result(false, "递归 SQL 的 :versionFilter 宏语法错误: " + e.getMessage());
        }
        // task-260907 · B-7a：:customerCode 同样要加桩。dry-run 走裸 JDBC，模板里留一个未绑定的
        // :customerCode 会让 PG 直接报语法错（`:` 在 PG 里不是占位符语法），保存报价树配置当场失败——
        // 而报出来的错长得像「SQL 写错了」，排查方向会整个跑偏到模板上。
        // 桩值用 NULL::varchar 而不是某个具体客户码：dry-run 只验「可执行 + 输出列齐」，
        // 外层还有 LIMIT 0，不产生也不需要真实数据行。
        // 🔄 task-260911（AC-7 / AC-9）：行级维度占位符已改成**复数集合**形态 :<列>s，桩值必须是
        //    **空数组**而不是 NULL::varchar。
        //
        // 🚨 这里原来写的是 .replace(":customerProductNo", "NULL::varchar")，对新的
        //    ":customerProductNos" 会拼出 "NULL::varchars" —— psql 实测 `type "varchars" does not exist`，
        //    保存核价树配置当场失败，且错得像"SQL 写错了"。字符串 replace 不认词边界，这类
        //    「单数占位符改复数」的改动必须逐个复查所有 replace 打桩点。
        //
        // 🚫 名字从语义图枚举，不写死列名（AC-9）。
        String stubbed = forValidation.replace(":production_part_nos", "ARRAY[]::text[]");
        try {
            for (String p : com.cpq.semanticgraph.service.RowScopeSupport
                    .setParamNames(semanticGraphLoader.get())) {
                stubbed = stubbed.replace(":" + p, "(ARRAY[]::text[])");
            }
        } catch (Exception ignored) {
            // 语义图不可用 → 不加桩；未绑占位符会让下面 dry-run 直接报错，不会静默放行
        }
        // 过渡期：尚未重编译的存量模板仍带单数标量占位符（repair-260910）。
        // ⚠️ 顺序要紧：复数的桩必须在单数之前打完（上面的循环），否则 ":customerProductNos"
        //    会被下面这条 replace 先吃掉前缀，留下一个悬空的 "s"。
        stubbed = stubbed.replace(":customerProductNo", "NULL::varchar")
                         .replace(":customerCode", "NULL::varchar");
        String probe = "SELECT * FROM (" + stubbed + ") q LIMIT 0";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(probe);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            Set<String> cols = new HashSet<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                cols.add(meta.getColumnLabel(i).toLowerCase());
            }
            for (String need : REQUIRED_COLS) {
                if (!cols.contains(need)) {
                    return new Result(false, "递归 SQL 缺输出列: " + need);
                }
            }
            return new Result(true, "ok");
        } catch (Exception e) {
            return new Result(false, "递归 SQL 无法执行: " + e.getMessage());
        }
    }
}
