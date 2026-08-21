package com.cpq.builder.compiler;

/**
 * 方言参数化（task-260819 B-10，AC-37）。
 *
 * <p>同一份语义图节点声明（图里 {@code dialect} 列一期恒为 {@code QUOTE}），编译期可以按
 * 这个枚举分别产出报价侧 / 核价侧两种形态——dialect 是**编译参数**，不是图查询条件
 * （图声明本身不需要为核价侧再登记一份重复节点）。
 *
 * <p>二期只把该有的分支占位占好（本轮 AC-37 覆盖的 4 点差异：别名规则 / 收窄策略 /
 * 字段绑定键 / 是否输出 view_version 列），不追加更多核价侧节点声明——backtask.md §3.9
 * 明确"二期只填声明不改架构"。
 */
public enum CompileDialect {
    QUOTE,
    COSTING;

    /** 字段绑定 JSON 里用哪个 key（{@code default_source.path} vs {@code basic_data_path}）。 */
    public String bindingKeyName() {
        return this == COSTING ? "basic_data_path" : "default_source.path";
    }
}
