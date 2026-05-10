package com.cpq.costingbasic.dto;

import com.cpq.costingbasic.entity.CostingPriceVersion;

import java.time.OffsetDateTime;
import java.util.UUID;

public class CostingPriceVersionDTO {
    public UUID id;
    public String versionKind;
    public String versionNumber;
    public String status;
    public String notes;
    public Boolean isDefault;
    public OffsetDateTime publishedAt;
    public UUID publishedBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public UUID createdBy;
    /** 该版本下明细行数（前端展示用） */
    public Long rowCount;

    public static CostingPriceVersionDTO from(CostingPriceVersion v) {
        return from(v, null);
    }

    public static CostingPriceVersionDTO from(CostingPriceVersion v, Long rowCount) {
        CostingPriceVersionDTO d = new CostingPriceVersionDTO();
        d.id = v.id;
        d.versionKind = v.versionKind;
        d.versionNumber = v.versionNumber;
        d.status = v.status;
        d.notes = v.notes;
        d.isDefault = v.isDefault;
        d.publishedAt = v.publishedAt;
        d.publishedBy = v.publishedBy;
        d.createdAt = v.createdAt;
        d.updatedAt = v.updatedAt;
        d.createdBy = v.createdBy;
        d.rowCount = rowCount;
        return d;
    }
}
