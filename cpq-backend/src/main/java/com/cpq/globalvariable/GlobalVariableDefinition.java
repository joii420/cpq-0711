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
    /** V188: KV_TABLE | COSTING_VIEW — 分发到 global_variable_value 单表 还是 source_view */
    public String valueSourceType;
    /** V188: PUBLIC | COSTING_INTERNAL — UI 列表是否过滤 */
    public String visibility;
    /** COSTING_VIEW 模式下的源视图; KV_TABLE 模式下可空 */
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

    public boolean isKvTable() {
        return "KV_TABLE".equals(valueSourceType);
    }

    public boolean isCostingView() {
        return "COSTING_VIEW".equals(valueSourceType);
    }

    public boolean isCostingInternal() {
        return "COSTING_INTERNAL".equals(visibility);
    }
}
