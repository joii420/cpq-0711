package com.cpq.component.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateComponentRequest {
    public String name;
    public String code;
    public UUID directoryId;
    public String componentType;
    /** Y1.5 行驱动 BNF 路径,可选 */
    public String dataDriverPath;
    public String status;
    // Accept fields/formulas as either parsed list or raw JSON string
    public List<Map<String, Object>> fields;
    public List<Map<String, Object>> formulas;
}
