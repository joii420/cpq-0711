package com.cpq.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    /**
     * task-260902：错误信封的<b>结构化附加载荷</b>（与 {@link #data} 不同层，仅错误响应使用）。
     *
     * <p>🚨 存在的理由：{@link #code} 是 {@code int}（HTTP 状态码），<b>类型上就装不下业务码字符串</b>，
     * 而前端需要按业务码分支（文案会改、错误码不会）。⇒ 约定：
     * <pre>{ "code": 400, "message": "材质占比合计为 90%，需要正好 100%",
     *   "detail": { "bizCode": "MATERIAL_RATIO_SUM_INVALID", "actualSum": "90", "expected": "100" } }</pre>
     * 🚫 <b>不要为了塞业务码去改 {@code code} 的类型</b> —— 那会波及全仓所有端点。
     * 前端取数：{@code error.response.data.detail}（{@code services/api.ts} 的 {@code buildApiError}）。
     */
    private Object detail;

    private ApiResponse() {}

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> success(T data, int code) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = data;
        return response;
    }

    /** 错误响应专用：带结构化 {@code detail}（含 {@code bizCode}）。 */
    public static <T> ApiResponse<T> error(int code, String message, T data, Object detail) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = data;
        response.detail = detail;
        return response;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public Object getDetail() { return detail; }
}
