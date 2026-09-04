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

    /**
     * 锚点物理表/视图名与轴列名（task-260819 B-48）。{@code /preview} 走裸 JDBC、拿不到编译期上下文，
     * 但要给 {@code :total_material_no} 造一个"本数据集下有意义"的值，必须知道从哪张表取轴值。
     * 🚫 不是给渲染链路用的——渲染走 {@code SqlViewExecutor}，那边不需要这两个字段。
     */
    public String anchorTable;
    public String axisColumn;
}
