package com.cpq.component.service;

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
 * 输出列必须逐字包含 {@code root_no / material_no / bom_version / parent_no} 四列。
 * 保存前用空 seed（{@code ARRAY[]::text[]}）包一层 {@code LIMIT 0} 子查询探测，
 * 既验证可执行性，又不产生实际数据行。
 */
@ApplicationScoped
public class CostingTreeSqlValidator {

    @Inject
    DataSource dataSource;

    public static final List<String> REQUIRED_COLS = List.of("root_no", "material_no", "bom_version", "parent_no");

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
        String probe = "SELECT * FROM (" + sql.replace(":production_part_nos", "ARRAY[]::text[]") + ") q LIMIT 0";
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
