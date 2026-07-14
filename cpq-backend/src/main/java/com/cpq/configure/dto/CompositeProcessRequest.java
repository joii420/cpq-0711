package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class CompositeProcessRequest {
    // B6（架构决策 2-2A，task-0712）: defCode 语义变为 process_master.process_no（如
    // 'MRO-AS-0001'，来自 GET /composite-processes 候选），不再是 composite_process_def.code。
    public String defCode;
    public List<Integer> participatingPartIndexes;  // [0,1] 引用 parts 数组下标
    public Map<String, Object> params;              // { pressure: 5.0, height: 3.2 }
}
