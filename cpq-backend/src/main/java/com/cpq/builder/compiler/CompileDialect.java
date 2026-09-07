package com.cpq.builder.compiler;

/**
 * 编译方言（task-260819 B-40，S-21 / AC-101 / AC-107 / AC-108 / AC-110）。
 *
 * <p>🔄 <b>2026-09-03 v9 三数据集范围替换</b>：原两值 {@code QUOTE / COSTING}（V6 时代，
 * 「同一份 V6 节点声明按侧产出两种形态」）已整块作废，改为三值 —— 三套物理数据集
 * （{@code ds_quote_*} / {@code ds_cost_basic_*} / {@code ds_cost_detail_*}）各自在语义图里
 * 有独立的节点 + 页签视图行，{@code semantic_node.dialect} / {@code semantic_tab_view.dialect}
 * 的取值就是本枚举的 {@link #name()}（D-77：唯一键已含 dialect，扩值即得三套并行声明，
 * <b>表结构零改动</b>）。
 *
 * <p>三处按方言分叉的规则（其余一律同形）：
 * <ol>
 *   <li><b>轴列</b>（{@link #axisColumn()}）：{@code QUOTE} = 销售料号 {@code material_no}；
 *       两个 {@code COST_*} = 生产料号 {@code production_no}（§9.1.1 实测基线）。收窄谓词
 *       {@code <轴列> = ANY(:total_material_no)} 由 {@code SemanticCompiler#applyFullScope} 发出（AC-108）。</li>
 *   <li><b>列别名</b>（{@link AliasGenerator}）：{@code QUOTE} → {@code _<短名>_<显示名>}；
 *       两个 {@code COST_*} → 裸英文 {@code dbColumn}。<b>🚫 不要"顺手统一"</b> —— 报价侧渲染层
 *       拿中文字段名当公式 token（N-10 / {@code BL-0090} 未解），统一会打断存量公式（AC-110）。</li>
 *   <li><b>版本谓词</b>（{@link #isCosting()}）：只有核价两套建了 {@code v_<主表>_all} 全版本视图
 *       （S-31/D-84），编译器对它们发 {@code :versionFilter(...)} + 输出 {@code view_version}
 *       约定列（AC-109）；报价侧节点直接指主表、无 {@code is_current} 列，不发（AC-107）。</li>
 * </ol>
 *
 * <p>🚫 <b>不再有 {@code system_type} / {@code customer_no} 收窄</b>：{@code ds_*} 45 张表里
 * 只有 {@code ds_quote_customer_part} 有 {@code customer_no}，而它按 N-19 不进图（实测
 * {@code information_schema} 逐表确认，2026-09-03）。旧 V6 分支已随 V6 节点一并删除（B-41④）。
 *
 * <p>📌 <b>已删除的 {@code bindingKeyName()}</b>（原「{@code COSTING → basic_data_path}，
 * 否则 {@code default_source.path}」）：该方法自 D-73/B-30 起就没有任何调用方 —— 绑定键改为
 * <b>按 {@code field_type} 分派</b>（{@code BASIC_DATA → basic_data_path}，{@code INPUT_* →
 * default_source.path}，见 {@code BuilderService#buildComponentUpdateRequest}），<b>明确不按侧决定</b>。
 * 留着一个与现行裁决相反的死方法，下一个人照它写就是 bug，故随本次扩枚举一并删除。
 */
public enum CompileDialect {

    /** 报价数据集 {@code ds_quote_*}（轴 = 销售料号）。 */
    QUOTE("material_no"),
    /** 基础核价数据集 {@code ds_cost_basic_*}（轴 = 生产料号）。 */
    COST_BASIC("production_no"),
    /** 明细核价数据集 {@code ds_cost_detail_*}（轴 = 生产料号）。 */
    COST_DETAIL("production_no");

    private final String axisColumn;

    CompileDialect(String axisColumn) {
        this.axisColumn = axisColumn;
    }

    /**
     * 该数据集的轴列物理名（AC-108）。收窄谓词只在目标表<b>真的有这一列</b>时才生成 ——
     * 表里没有轴列（如 {@code ds_*_plating_scheme} 按 {@code scheme_no} 建模）就不发，
     * 靠连接键自身收窄，不硬造一个不存在的列引用。
     */
    public String axisColumn() {
        return axisColumn;
    }

    /**
     * 是否核价侧。两个 {@code COST_*} 在别名规则 / 轴列 / 版本谓词上<b>完全同形</b>，
     * 差别只在物理表族（由语义图节点声明承担），所以编译器里凡是"报价 vs 核价"的分叉
     * 一律走本方法，不要逐个枚举值写 {@code ==} 比较（漏一个就是静默走错分支）。
     */
    public boolean isCosting() {
        return this != QUOTE;
    }

    /** {@code semantic_node.dialect} / {@code semantic_tab_view.dialect} 列里的取值。 */
    public String graphDialect() {
        return name();
    }

    /**
     * 解析外部传入的方言字符串（task-260819 B-46，主线 2026-09-03 裁决）。
     *
     * <p><b>缺省仍是 {@link #QUOTE}</b>（不传 = 报价侧，与改动前一致）；但<b>显式传了非三值之一
     * 就直接 400</b>，不再静默回落。
     *
     * <p>🔑 <b>为什么必须拒绝而不是容错</b>：原 {@code BuilderService#resolveDialect} 对无法识别的值
     * 一律回落 {@code QUOTE}（B-22/D-59 为零回归定的口径，当时只有两个方言、且核价侧还没真正启用）。
     * v9 三方言并存后，这条回落的后果变成「<b>用户选了基础核价，系统按报价侧编译，全程不报错</b>」——
     * 与 {@code SemanticCompiler#resolveTabView} 漏 dialect 过滤是同一类静默故障。更糟的是
     * <b>AC-107 / AC-108 的断言在这种回落下会照样通过</b>（它们只检查产物里没有 {@code system_type}、
     * 轴列对不对，不检查"当初要的是哪个方言"）⇒ 自动化验收给不出任何信号。
     *
     * <p>⚠️ 旧值 {@code "COSTING"} <b>一并拒绝</b>：它已随 V6 整块作废（本枚举不再有这个常量），
     * 留着容错等于给"用错方言"开一个后门，而且是最容易被老客户端踩中的那一个。
     */
    public static CompileDialect parse(String raw) {
        if (raw == null || raw.isBlank()) return QUOTE;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (CompileDialect d : values()) {
            if (d.name().equals(v)) return d;
        }
        throw new com.cpq.builder.exception.BuilderApiException(400, "BUILDER_DIALECT_UNKNOWN",
                "无法识别的数据集（dialect）：「" + raw + "」。合法值：QUOTE（报价）/ COST_BASIC（基础核价）"
                        + " / COST_DETAIL（明细核价）"
                        + ("COSTING".equals(v) ? "。COSTING 已随 V6 数据集整体作废，核价请改用 COST_BASIC 或 COST_DETAIL" : ""),
                java.util.Map.of("received", raw, "allowed",
                        java.util.List.of(QUOTE.name(), COST_BASIC.name(), COST_DETAIL.name())));
    }
}
