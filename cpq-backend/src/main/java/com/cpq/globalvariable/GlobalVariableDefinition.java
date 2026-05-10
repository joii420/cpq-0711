package com.cpq.globalvariable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * V104 注册表行的轻量 DTO/POJO. 不走 Panache 实体路线 — 服务层用 native query 加载,
 * 这样可以保持表结构与运行期 cache 解耦, 同时方便 JSONB key_columns 的反序列化。
 */
public class GlobalVariableDefinition {

    public String code;
    public String name;
    /** LOOKUP_TABLE | SCALAR */
    public String varType;
    public String sourceView;
    /** 物理列名清单, 单键长度=1, 复合键长度>1 */
    public List<String> keyColumns;
    public String valueColumn;
    public String labelTemplate;
    public String unit;
    public String description;
    public Integer sortOrder;
    public Boolean isActive;

    @JsonIgnore
    public OffsetDateTime updatedAt;

    public boolean isLookup() {
        return "LOOKUP_TABLE".equals(varType);
    }
}
