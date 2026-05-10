package com.cpq.formula;

/**
 * 表示公式运行时错误的值对象。
 *
 * <p>当公式执行遇到除零、类型不匹配、路径解析失败等情况时，
 * FormulaEngine 返回此对象而非抛出异常（IFERROR 可捕获）。
 * 前端收到 FormulaError 时应以红色单元格展示 message。
 *
 * <p>v5.1 §3.2 决策："ERROR 单元格：返回特殊 FormulaError(message) 对象，红色显示由前端处理"
 */
public final class FormulaError {

    private final String message;
    private final String errorCode;

    public FormulaError(String message) {
        this(message, "FORMULA_ERROR");
    }

    public FormulaError(String message, String errorCode) {
        this.message = message != null ? message : "未知错误";
        this.errorCode = errorCode != null ? errorCode : "FORMULA_ERROR";
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "#ERROR[" + errorCode + "]: " + message;
    }

    // ── 工厂方法 ─────────────────────────────────────────────────────────────

    public static FormulaError divisionByZero() {
        return new FormulaError("除零错误", "DIV_ZERO");
    }

    public static FormulaError typeMismatch(String expected, String actual) {
        return new FormulaError("类型不匹配：期望 " + expected + "，实际 " + actual, "TYPE_MISMATCH");
    }

    public static FormulaError notFound(String path) {
        return new FormulaError("路径未找到数据：" + path, "NOT_FOUND");
    }

    public static FormulaError invalidArgs(String functionName, String reason) {
        return new FormulaError("函数 " + functionName + " 参数错误：" + reason, "INVALID_ARGS");
    }

    public static FormulaError unsupported(String reason) {
        return new FormulaError(reason, "UNSUPPORTED");
    }
}
