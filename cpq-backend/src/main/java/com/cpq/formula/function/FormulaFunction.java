package com.cpq.formula.function;

import com.cpq.formula.EvaluationContext;

import java.util.List;

/**
 * 公式函数统一接口。
 *
 * <p>每个实现类对应 v5.1 §3.2 中一个函数。
 * FunctionRegistry 按 {@link #name()} 查找并路由调用。
 *
 * <p>类型严格性（v5.1 §3.2 决策）：
 * 参数类型不匹配时抛 {@link com.cpq.formula.FormulaError}（通过 return 返回错误值，
 * 而非 throw），让 FormulaEngine 可以继续处理其他单元格。
 */
public interface FormulaFunction {

    /**
     * 函数名（全大写，与公式中使用的名称一致）。
     */
    String name();

    /**
     * 执行函数，返回计算结果。
     *
     * <p>约定：
     * <ul>
     *   <li>返回值为 {@link com.cpq.formula.FormulaError} 时表示计算失败，调用方应按 ERROR 处理</li>
     *   <li>实现不应抛出受检异常；运行时异常应捕获并转换为 FormulaError</li>
     *   <li>参数类型校验严格（v5.1 §3.2：不自动类型转换）</li>
     * </ul>
     *
     * @param args 已求值的参数列表（与公式文本中的参数顺序一致）
     * @param ctx  求值上下文
     * @return 计算结果或 FormulaError
     */
    Object invoke(List<Object> args, EvaluationContext ctx);
}
