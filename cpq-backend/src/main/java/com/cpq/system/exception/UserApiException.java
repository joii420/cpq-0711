package com.cpq.system.exception;

import com.cpq.common.exception.BusinessException;

/**
 * 用户模块的业务错误（task-260902 · B-5，api.md B-5 的错误码表）。
 *
 * <p>响应体沿用全局 {@code ApiResponse} 信封，错误码放在 {@code data.code}：
 * <pre>{ "code": 400, "message": "…文案…", "data": { "code": "IMPORT_HEADER_INVALID" } }</pre>
 * 与 {@code MaterialRecipeApiException} 同款（{@code GlobalExceptionMapper} 里有对应分支）。
 * <p>🚫 前端判定一律读 {@code data.code}，禁止按 message 文本匹配。
 */
public class UserApiException extends BusinessException {

    private final String errorCode;

    public UserApiException(int httpStatus, String errorCode, String message) {
        super(httpStatus, message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public static UserApiException badRequest(String errorCode, String message) {
        return new UserApiException(400, errorCode, message);
    }
}
