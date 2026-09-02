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

    public MaterialRecipeApiException(int httpStatus, String errorCode, String message) {
        super(httpStatus, message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
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
