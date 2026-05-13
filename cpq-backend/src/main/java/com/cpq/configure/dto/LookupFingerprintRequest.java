package com.cpq.configure.dto;

import java.util.List;

public class LookupFingerprintRequest {
    public String productType;             // 'SIMPLE' | 'COMPOSITE'
    public String recipeCode;              // SIMPLE 时必填
    public List<ElementOverride> elements; // SIMPLE 时必填
    public List<String> childHfPartNos;    // COMPOSITE 时必填
}
