package com.cpq.basicdata.v6.dto;

import com.cpq.basicdata.v6.entity.ProcessMaster;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V6 工序主数据 DTO（只读，1:1 映射 process_master 表 11 个业务字段）。
 */
public class ProcessMasterDTO {

    public UUID id;
    public String processNo;
    public String processName;
    /** 制造 / 组装 / 电镀 / 外协 / 包装 / 清洗 */
    public String processCategory;
    public Boolean isOutsource;
    public String standardCurrency;
    public String standardUnit;
    public BigDecimal defaultDefectRate;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public UUID createdBy;
    public UUID updatedBy;

    public static ProcessMasterDTO from(ProcessMaster e) {
        if (e == null) return null;
        ProcessMasterDTO d = new ProcessMasterDTO();
        d.id = e.id;
        d.processNo = e.processNo;
        d.processName = e.processName;
        d.processCategory = e.processCategory;
        d.isOutsource = e.isOutsource;
        d.standardCurrency = e.standardCurrency;
        d.standardUnit = e.standardUnit;
        d.defaultDefectRate = e.defaultDefectRate;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        d.createdBy = e.createdBy;
        d.updatedBy = e.updatedBy;
        return d;
    }
}
