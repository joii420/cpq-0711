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
        /**
         * 调试: driver 改写后的最终执行 SQL (含 ? 占位符 + 参数)。仅请求 debugSql=true 时填充。
         * 放在 Result 顶层(而非 data 内), 这样即便 status=ERROR(data=null) 也能看到失败的那条 SQL。
         */
        public String debugSql;
    }
}
