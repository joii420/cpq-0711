package com.cpq.quotation.service.rowkey;

import java.util.List;

/**
 * 单条行键冲突：某组件下，组合行键 rowKey 在多行重复。
 * rowIndices 为 0 基行序（baseRows 中的位置），用于定位与报错明细。
 */
public record RowKeyConflict(String componentName, String rowKey, List<Integer> rowIndices) {

    /** 人类可读的冲突描述，用于拼装提交报错信息。 */
    public String describe() {
        return "组件「" + componentName + "」行键 [" + rowKey + "] 在第 "
            + rowIndices.stream().map(i -> String.valueOf(i + 1)).reduce((a, b) -> a + "," + b).orElse("")
            + " 行重复";
    }
}
