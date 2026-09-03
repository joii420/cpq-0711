package com.cpq.configure.exception;

import com.cpq.common.exception.BusinessException;

/**
 * 材质模块的业务错误（task-260901，api.md §2 的错误码表）。
 *
 * <p>响应体沿用全局 {@code ApiResponse} 信封，错误码放在 {@code data.code}：
 * <pre>{ "code": 409, "message": "…文案…", "data": { "code": "COMPOSITION_LOCKED" } }</pre>
 * 与 {@code ComponentElementBindingRequiredException} 同款，也正好对上前端
 * {@code buildApiError}（message 取信封 message、payload 取信封 data）。
 * <p>🚫 前端判定一律读 {@code data.code}，禁止按 message 文本匹配。
 */
public class MaterialRecipeApiException extends BusinessException {

    private final String errorCode;

    /**
     * task-260902：结构化补充信息，随响应信封的 {@code data.detail} 一起下发（可空）。
     *
     * <p>存在的理由是 AC-4：占比合计不对时提示必须写出<b>实际合计值</b>（「90%」），
     * 而不是「合计不正确」这种形容词 —— 前端要把 {@code detail.actualSum} 直接显示出来。
     */
    private final java.util.Map<String, Object> detail;

    public MaterialRecipeApiException(int httpStatus, String errorCode, String message) {
        this(httpStatus, errorCode, message, null);
    }

    public MaterialRecipeApiException(int httpStatus, String errorCode, String message,
                                      java.util.Map<String, Object> detail) {
        super(httpStatus, message);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** 可空；非空时进响应 {@code data.detail}。 */
    public java.util.Map<String, Object> getDetail() {
        return detail;
    }

    // ── 便捷工厂（HTTP 状态按 api.md 固定，调用点不再各写各的）──

    public static MaterialRecipeApiException badRequest(String errorCode, String message) {
        return new MaterialRecipeApiException(400, errorCode, message);
    }

    public static MaterialRecipeApiException conflict(String errorCode, String message) {
        return new MaterialRecipeApiException(409, errorCode, message);
    }

    public static MaterialRecipeApiException forbidden(String errorCode, String message) {
        return new MaterialRecipeApiException(403, errorCode, message);
    }

    public static MaterialRecipeApiException notFound(String errorCode, String message) {
        return new MaterialRecipeApiException(404, errorCode, message);
    }
}
