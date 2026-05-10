package com.cpq.formula.dto;

/**
 * 公式求值响应。
 * - success=true: result 为求值结果(Number / String / Boolean / null)
 * - success=false: error 含解析或求值错误信息
 */
public class EvaluateResponse {

    public boolean success;
    public Object result;
    public String error;
    public String errorType;  // 'PARSE_ERROR' / 'EVAL_ERROR' / 'CONTEXT_MISSING'

    public static EvaluateResponse ok(Object result) {
        EvaluateResponse r = new EvaluateResponse();
        r.success = true;
        r.result = result;
        return r;
    }

    public static EvaluateResponse error(String errorType, String message) {
        EvaluateResponse r = new EvaluateResponse();
        r.success = false;
        r.errorType = errorType;
        r.error = message;
        return r;
    }
}
