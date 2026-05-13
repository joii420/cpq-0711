package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class LookupFingerprintResponse {
    public boolean matched;
    public String hfPartNo;
    public Snapshot snapshot;

    public static class Snapshot {
        public BigDecimal unitWeightGrams;
        public List<Map<String, Object>> processes;          // [{processCode, seqNo, name?}]
        public List<Map<String, Object>> compositeProcesses; // 组合产品才有
    }
}
