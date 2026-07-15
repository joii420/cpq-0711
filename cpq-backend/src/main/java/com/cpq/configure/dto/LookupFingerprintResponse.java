package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class LookupFingerprintResponse {
    public boolean matched;

    /**
     * 命中的报价料号 / 销售料号（quote_part_no）。
     * {@code hfPartNo} 与 {@code matchedPartNo} 同值——保留 {@code hfPartNo}
     * 兼容既有字段名（前端 {@code types/configure.ts} 已声明），{@code matchedPartNo}
     * 为 3a 任务约定的字段名，二选一读取皆可。
     */
    public String hfPartNo;
    public String matchedPartNo;

    public Snapshot snapshot;

    public static class Snapshot {
        public BigDecimal unitWeightGrams;
        public List<Map<String, Object>> processes;          // [{processCode, seqNo, name?}]
        public List<Map<String, Object>> compositeProcesses; // 组合产品才有
    }
}
