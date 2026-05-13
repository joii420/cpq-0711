package com.cpq.configure.dto;

import java.util.List;

public class ConfigureProductRequest {
    public String productType;                              // 'SIMPLE' | 'COMPOSITE'
    public List<PartRequest> parts;                         // SIMPLE 时 size=1; COMPOSITE 时 size>=2
    public List<CompositeProcessRequest> compositeProcesses; // 仅 COMPOSITE 才用
}
