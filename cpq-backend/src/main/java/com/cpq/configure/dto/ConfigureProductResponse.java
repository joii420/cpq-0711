package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class ConfigureProductResponse {
    public List<Map<String, Object>> lineItems;    // SIMPLE: 1 行;COMPOSITE: 1 父 + N 子
    public boolean fingerprintMatched;             // 至少 1 个料号是复用
    public List<String> reusedHfPartNos;           // 哪些料号是复用的
}
