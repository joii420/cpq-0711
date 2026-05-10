package com.cpq.basicdata.dto;

import com.cpq.basicdata.entity.BasicDataAttribute;

import java.time.OffsetDateTime;
import java.util.UUID;

public class BasicDataAttributeDTO {

    public UUID id;
    public UUID configId;
    public String columnLetter;
    public String columnTitle;
    public String variableCode;
    public String variableLabel;
    public String dataType;
    public String status;
    public Integer sortOrder;

    /**
     * 字段重要性等级：CRITICAL / IMPORTANT / NORMAL
     * D-5 v5.1 §6.2 CONF-2
     */
    public String importanceLevel;

    /**
     * 字段变更是否触发公式重算
     * D-5 v5.1 §6.2 CONF-2
     */
    public Boolean affectsCalculation;

    /**
     * V58: 该列是否必填，导入解析时为空抛 ValidationResult.error。
     */
    public Boolean isRequired;

    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static BasicDataAttributeDTO from(BasicDataAttribute a) {
        BasicDataAttributeDTO dto = new BasicDataAttributeDTO();
        dto.id = a.id;
        dto.configId = a.configId;
        dto.columnLetter = a.columnLetter;
        dto.columnTitle = a.columnTitle;
        dto.variableCode = a.variableCode;
        dto.variableLabel = a.variableLabel;
        dto.dataType = a.dataType;
        dto.status = a.status;
        dto.sortOrder = a.sortOrder;
        dto.importanceLevel = a.importanceLevel;
        dto.affectsCalculation = a.affectsCalculation;
        dto.isRequired = a.isRequired;
        dto.createdAt = a.createdAt;
        dto.updatedAt = a.updatedAt;
        return dto;
    }
}
