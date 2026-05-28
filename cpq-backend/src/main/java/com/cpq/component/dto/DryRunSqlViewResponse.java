package com.cpq.component.dto;

import java.util.List;

/**
 * POST /dry-run 响应体。
 */
public class DryRunSqlViewResponse {

    /** dry-run 是否通过。 */
    public boolean success;

    /**
     * 提取的列签名（success=true 时返回）。
     * 每项: {name: "col", dataType: "text", nullable: true}
     */
    public List<ColumnMeta> declaredColumns;

    /**
     * 从 SQL 中解析出的 :xxx 占位符列表（不含 :hfPartNos）。
     */
    public List<String> requiredVariables;

    /** 错误信息（success=false 时返回）。 */
    public String error;

    public static class ColumnMeta {
        public String name;
        public String dataType;
        public boolean nullable;

        public ColumnMeta(String name, String dataType, boolean nullable) {
            this.name = name;
            this.dataType = dataType;
            this.nullable = nullable;
        }
    }

    public static DryRunSqlViewResponse ok(List<ColumnMeta> columns, List<String> vars) {
        DryRunSqlViewResponse r = new DryRunSqlViewResponse();
        r.success = true;
        r.declaredColumns = columns;
        r.requiredVariables = vars;
        return r;
    }

    public static DryRunSqlViewResponse fail(String error) {
        DryRunSqlViewResponse r = new DryRunSqlViewResponse();
        r.success = false;
        r.error = error;
        return r;
    }
}
