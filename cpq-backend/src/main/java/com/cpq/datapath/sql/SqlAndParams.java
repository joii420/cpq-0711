package com.cpq.datapath.sql;

import java.util.List;

/**
 * 编译后的参数化 SQL 及其绑定参数。
 *
 * @param sql             参数化 SQL（使用 ? 占位符）
 * @param params          按顺序对应 ? 的参数值列表
 * @param selectedColumns 查询的目标列名列表
 */
public record SqlAndParams(
        String       sql,
        List<Object> params,
        List<String> selectedColumns
) {
    public SqlAndParams {
        params          = List.copyOf(params);
        selectedColumns = List.copyOf(selectedColumns);
    }

    /** 便于日志调试：拼出带参数的伪 SQL（仅用于打印，不可直接执行） */
    public String toDebugString() {
        String result = sql;
        for (Object param : params) {
            String replacement = (param instanceof String)
                    ? "'" + param + "'"
                    : String.valueOf(param);
            result = result.replaceFirst("\\?", replacement);
        }
        return result;
    }
}
