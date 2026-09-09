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

    /** 轴范围取值：只出<b>卡片自己那个料号</b>的行（「主件」页签）。 */
    public static final String AXIS_SCOPE_SELF = "SELF";
    /** 轴范围取值：出<b>本单/本卡 BOM 闭包</b>的行（其余全部页签，= 改动前行为）。 */
    public static final String AXIS_SCOPE_CLOSURE = "CLOSURE";

    /**
     * 轴范围声明（repair-260908 B-3，AC-7）：{@link #AXIS_SCOPE_SELF} / {@link #AXIS_SCOPE_CLOSURE}。
     *
     * <p><b>它解决的是缺陷②</b>：{@code :total_material_no} 是**整单闭包**（成品 + 全部后代），
     * 渲染时 {@code ComponentDriverService} 还会把 outer {@code hfPartNos} 加宽到「这张卡自己的
     * BOM 闭包」（task-260819 D-58 有意为之，因为多数页签**确实需要子件行**）。
     * 但「主件」页签描述的是<b>卡片自己那个成品</b>，加宽让它多出了后代料号的行。
     *
     * <p>🔑 <b>三个方言都产出该声明</b>（口径统一、未来可用），但**只有报价侧驱动层消费它**（D-2）：
     * 核价渲染按 spine 全节点渲染、整单一次 expand（{@code BomTreeRenderService} 的 partNo 恒传 null），
     * 压根没有「当前卡片料号」这个维度，{@code SELF} 无处可落。
     *
     * <p>🔑 <b>判据只有一条</b>：页签类型是不是 {@code "主件"}（{@code ROOT_SOURCE_TAB_TYPE}）。
     * 🚫 不要改成按锚点表名/方言/视图名判 —— 那些都是"碰巧对"的判据。
     */
    public String axisScope = AXIS_SCOPE_CLOSURE;
}
