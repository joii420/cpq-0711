package com.cpq.seltemplate.dto;

import com.cpq.seltemplate.entity.SelParamType;

public class SelParamTypeDTO {
    public String code, name, valueMode, dataSourceKey, persistHandlerKey;
    public Integer sortOrder;

    public static SelParamTypeDTO from(SelParamType e) {
        SelParamTypeDTO d = new SelParamTypeDTO();
        d.code = e.code; d.name = e.name; d.valueMode = e.valueMode;
        d.dataSourceKey = e.dataSourceKey; d.persistHandlerKey = e.persistHandlerKey;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
