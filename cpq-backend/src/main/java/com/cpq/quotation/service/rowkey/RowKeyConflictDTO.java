package com.cpq.quotation.service.rowkey;

import java.util.List;

/**
 * 提交期行键冲突的结构化明细（前端定位用）。
 *
 * @param lineItemId   报价单明细行 id（后端装配恒非空；前端按它定位卡片）
 * @param productName  产品名（= line item label，Drawer 主展示）
 * @param productPartNo 料号（= product_part_no_snapshot，前端兜底匹配卡片用；与 productName 来源不同，勿混用）
 * @param componentId  页签组件 id（前端切 Tab 用）
 * @param tabName      页签中文名（取不到回退 componentId）
 * @param rowKey       组合行键
 * @param rowIndices   1 基重复行号（已 +1，展示用；与异常文案同口径）
 */
public record RowKeyConflictDTO(
        String lineItemId,
        String productName,
        String productPartNo,
        String componentId,
        String tabName,
        String rowKey,
        List<Integer> rowIndices) {

    /** 人类可读冲突描述，与旧 RowKeyConflict.describe() 文案逐字一致；rowIndices 已是 1 基，直接 join。 */
    public String describe() {
        String rows = rowIndices.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return "组件「" + tabName + "」行键 [" + rowKey + "] 在第 " + rows + " 行重复";
    }
}
