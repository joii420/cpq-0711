package com.cpq.quotation.service.reconcile;

/**
 * task-0806 阶段① API-3：提交闸门 409 响应里 {@code data.conflicts[]} 的单条明细。
 * 契约见 api.md §API-3。字段形态照既有先例 {@code RowKeyConflictDTO}。
 *
 * @param lineItemId     报价单明细行 id
 * @param productPartNo  料号（= product_part_no_snapshot，前端定位卡片用）
 * @param tabName        页签中文名
 * @param rowKey         组合行键
 * @param fieldName      字段名
 * @param frontendValue  最近一次上报的前端值（原样透传，不做类型收窄）
 * @param backendValue   最近一次上报的后端值
 */
public record SubmitConflictDTO(
        String lineItemId,
        String productPartNo,
        String tabName,
        String rowKey,
        String fieldName,
        Object frontendValue,
        Object backendValue) {
}
