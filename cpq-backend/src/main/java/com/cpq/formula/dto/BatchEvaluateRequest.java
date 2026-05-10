package com.cpq.formula.dto;

import java.util.List;

/**
 * POST /api/cpq/formulas/batch-evaluate 请求体。
 *
 * <pre>
 * {
 *   "tasks": [
 *     {"expression": "{v_q_part_info_merged.unit_weight}", "customerId": "...", "partNo": "..."},
 *     {"expression": "{v_q_part_info_merged.unit_weight}", "customerId": "...", "partNo": "..."}
 *   ]
 * }
 * </pre>
 *
 * <p>每个 task 复用 {@link EvaluateRequest}，含 expression / customerId / partNo / bindings / driverRow。
 * 单批上限 200 条；超出抛 BusinessException(400)。
 */
public class BatchEvaluateRequest {

    /** 待求值任务列表，上限 200。 */
    public List<EvaluateRequest> tasks;
}
