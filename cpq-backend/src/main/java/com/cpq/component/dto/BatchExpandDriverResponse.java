package com.cpq.component.dto;

import java.util.List;

/**
 * POST /api/cpq/components/batch-expand 响应体。
 *
 * <pre>
 * {
 *   "results": [
 *     {
 *       "key": "componentId:customerId:partNo",
 *       "status": "OK",
 *       "data": { "rowCount": 3, "driverPath": "...", "rows": [...] },
 *       "error": null
 *     },
 *     {
 *       "key": "componentId2:_:_",
 *       "status": "ERROR",
 *       "data": null,
 *       "error": "Component not found: ..."
 *     }
 *   ]
 * }
 * </pre>
 */
public class BatchExpandDriverResponse {

    public List<Result> results;

    public static class Result {
        /** 与缓存 key 格式一致：componentId:customerId:partNo（null 用 "_" 占位） */
        public String key;
        /** "OK" 或 "ERROR" */
        public String status;
        /** status=OK 时的展开数据 */
        public ExpandDriverResponse data;
        /** status=ERROR 时的错误信息 */
        public String error;
    }
}
