package com.cpq.formula.dto;

import java.util.List;

/**
 * POST /api/cpq/formulas/batch-evaluate 响应体。
 *
 * <pre>
 * {
 *   "results": [
 *     {
 *       "key": "expression:customerId:partNo",
 *       "status": "OK",
 *       "data": { "success": true, "result": 1.23 },
 *       "error": null
 *     },
 *     {
 *       "key": "expression2:_:_",
 *       "status": "ERROR",
 *       "data": null,
 *       "error": "..."
 *     }
 *   ]
 * }
 * </pre>
 */
public class BatchEvaluateResponse {

    public List<Result> results;

    public static class Result {
        /** key 格式：expression:customerId:partNo（null 用 "_" 占位） */
        public String key;
        /** "OK" 或 "ERROR" */
        public String status;
        /** status=OK 时的求值数据 */
        public EvaluateResponse data;
        /** status=ERROR 时的错误信息 */
        public String error;
    }
}
