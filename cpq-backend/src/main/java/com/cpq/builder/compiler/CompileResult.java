package com.cpq.builder.compiler;

import java.util.ArrayList;
import java.util.List;

/** 编译产物（task-260819 B-5，api.md §2.2）。 */
public class CompileResult {
    public String sql;
    public List<String> declaredColumns = new ArrayList<>();
    public List<String> requiredVariables = new ArrayList<>();
    public List<String> grain = new ArrayList<>();
    public boolean rewriterCompatible;
    public List<String> warnings = new ArrayList<>();

    /**
     * 补齐后的有效列清单（原始 {@code cfg.columns} + 价格策略自动带出的成员，如「元素」编码列）。
     * 供保存流程（B-13）据此构造 {@code component.fields[]}——AC-2①「7 项」正是靠这份清单，
     * 而不是原始请求里用户手拖的那 6 项。
     */
    public List<BuilderConfig.ColumnConfig> effectiveColumns = new ArrayList<>();
}
