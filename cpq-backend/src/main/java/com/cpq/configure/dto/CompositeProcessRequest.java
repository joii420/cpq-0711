package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class CompositeProcessRequest {
    public String defCode;                          // 'RIVET' / 'RESISTANCE_WELD' / ...
    public List<Integer> participatingPartIndexes;  // [0,1] 引用 parts 数组下标
    public Map<String, Object> params;              // { pressure: 5.0, height: 3.2 }
}
