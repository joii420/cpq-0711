package com.cpq.datasource.dto;

import com.cpq.datasource.entity.DataSourceParam;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DataSourceParamDTO {

    public UUID id;
    public UUID datasourceId;
    public Integer paramOrder;
    public String paramCode;
    public String paramName;
    public String sourceType;
    public String systemParamCode;
    public Boolean isRequired;
    public String description;
    public OffsetDateTime createdAt;

    public static DataSourceParamDTO from(DataSourceParam param) {
        DataSourceParamDTO dto = new DataSourceParamDTO();
        dto.id = param.id;
        dto.datasourceId = param.datasourceId;
        dto.paramOrder = param.paramOrder;
        dto.paramCode = param.paramCode;
        dto.paramName = param.paramName;
        dto.sourceType = param.sourceType;
        dto.systemParamCode = param.systemParamCode;
        dto.isRequired = param.isRequired;
        dto.description = param.description;
        dto.createdAt = param.createdAt;
        return dto;
    }
}
