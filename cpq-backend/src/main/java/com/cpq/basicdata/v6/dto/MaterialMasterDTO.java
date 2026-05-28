package com.cpq.basicdata.v6.dto;

import com.cpq.basicdata.v6.entity.MaterialMaster;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V6 料号主数据 DTO（用于「产品管理 → 产品主数据」UI）。
 * 字段名 1:1 映射 material_master 表。
 */
public class MaterialMasterDTO {

    public UUID id;
    public String materialNo;
    public String materialName;
    public String specification;
    public String dimension;
    public String oldMaterialNo;
    /** 1.银点类 / 2.非银点类 / 组成件 / 边角料 */
    public String materialType;
    /** 1.正常 / 2.回收料 */
    public String usageProperty;
    public BigDecimal unitWeight;
    public String standardUnit;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public UUID createdBy;
    public UUID updatedBy;

    public static MaterialMasterDTO from(MaterialMaster e) {
        if (e == null) return null;
        MaterialMasterDTO d = new MaterialMasterDTO();
        d.id = e.id;
        d.materialNo = e.materialNo;
        d.materialName = e.materialName;
        d.specification = e.specification;
        d.dimension = e.dimension;
        d.oldMaterialNo = e.oldMaterialNo;
        d.materialType = e.materialType;
        d.usageProperty = e.usageProperty;
        d.unitWeight = e.unitWeight;
        d.standardUnit = e.standardUnit;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        d.createdBy = e.createdBy;
        d.updatedBy = e.updatedBy;
        return d;
    }
}
