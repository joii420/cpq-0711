package com.cpq.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 公式循环引用（repair-0803）：携带**可读的**环链路，供前端弹抽屉逐条展示。
 *
 * <p>形态照既有先例 {@link RowKeyConflictException} / {@link TreeConflictException} ——
 * {@link GlobalExceptionMapper} 走 {@code ApiResponse.error(code, message, data)} 下发，
 * 响应体格式一个字段不改，不消费 {@code data} 的既有前端路径零感知。
 *
 * <p><b>契约铁律</b>：所有面向用户的标识一律为<b>名称</b>（组件名称 / 公式名称 / 字段名称），
 * <b>不得</b>出现 UUID 或字段 id —— 详见 {@code dev-docs/repair-0803-.../api.md} §1、AC-11。
 */
public class FormulaCycleException extends BusinessException {

    /** 环的粒度。 */
    public static final String SCOPE_FIELD = "FIELD";   // 同组件内、字段之间
    public static final String SCOPE_TAB   = "TAB";     // 跨页签、组件之间

    /**
     * 环上的一个节点。
     *
     * @param componentName 组件（页签）名称
     * @param fieldName     字段名称（{@code scope=FIELD} 时有值；TAB 环为 null）
     * @param formulaName   该节点当前绑定的公式名（解析不到时 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String componentName, String fieldName, String formulaName) {}

    /**
     * 环上的一条边：{@code from} 因为 {@code viaFormulaName} 这条公式而依赖 {@code to}。
     *
     * @param from            起点名称（FIELD 环=字段名；TAB 环=组件名）
     * @param to              终点名称（同上）
     * @param col             被引用的列名（TAB 环有值，用于说明「引用了对方的哪一列」；FIELD 环为 null）
     * @param colType         被引用列的类型说明（如「公式列」，TAB 环有值）——解释为何构成先后依赖
     * @param viaFormulaName  该引用出自哪条公式
     * @param viaDesc         来源描述，沿用 {@code DepEdge.via} 既有措辞（如「公式」「条件规则2命中的公式」）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(String from, String to, String col, String colType,
                       String viaFormulaName, String viaDesc) {}

    /**
     * 一个环。
     *
     * @param scope         {@link #SCOPE_FIELD} / {@link #SCOPE_TAB}
     * @param componentName 环所在组件名称（{@code scope=FIELD} 时有值）
     * @param nodes         按链路顺序排列，<b>首尾不重复</b>（前端渲染时自行闭合回首节点）
     * @param edges         边数 = 节点数（含闭合边）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cycle(String scope, String componentName, List<Node> nodes, List<Edge> edges) {}

    private final List<Cycle> cycles;

    public FormulaCycleException(String message, List<Cycle> cycles) {
        super(400, message);
        this.cycles = cycles == null ? List.of() : List.copyOf(cycles);
    }

    public List<Cycle> getCycles() { return cycles; }

    /** 标准提示语：「公式存在循环引用（N 处）」。 */
    public static String defaultMessage(int count) {
        return "公式存在循环引用（" + count + " 处）";
    }
}
