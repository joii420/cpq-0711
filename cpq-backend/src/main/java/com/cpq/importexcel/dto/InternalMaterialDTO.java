package com.cpq.importexcel.dto;

import com.cpq.importexcel.entity.InternalMaterial;

import java.time.OffsetDateTime;
import java.util.UUID;

public class InternalMaterialDTO {

    public UUID id;
    public String materialNo;
    public String name;
    public String specification;
    public String size;
    public String statusCode;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static InternalMaterialDTO from(InternalMaterial m) {
        InternalMaterialDTO dto = new InternalMaterialDTO();
        dto.id = m.id;
        dto.materialNo = m.materialNo;
        dto.name = m.name;
        dto.specification = m.specification;
        dto.size = m.size;
        dto.statusCode = m.statusCode;
        dto.createdAt = m.createdAt;
        dto.updatedAt = m.updatedAt;
        return dto;
    }
}
