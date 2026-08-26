package com.cpq.builder.exception;

import com.cpq.common.exception.BusinessException;

import java.util.Map;

/**
 * 取数配置器 builder 端点族的结构化错误（task-260819 B-11/12/13，api.md §2.5）。
 *
 * <p>响应一律「裸体」——不套 {@code ApiResponse} 信封（api.md §1.5③），{@code code}/
 * {@code message}/{@code detail} 及其余字段直接在响应根，由 {@link com.cpq.common.exception.GlobalExceptionMapper}
 * 的对应分支平铺进 body（见该类新增的 handleBuilderApiException 分支）。
 */
public class BuilderApiException extends BusinessException {

    private final String code;
    private final Map<String, Object> extra;

    public BuilderApiException(int httpStatus, String code, String message, Map<String, Object> extra) {
        super(httpStatus, message);
        this.code = code;
        this.extra = extra == null ? Map.of() : extra;
    }

    public String getErrorCode() { return code; }
    public Map<String, Object> getExtra() { return extra; }
}
