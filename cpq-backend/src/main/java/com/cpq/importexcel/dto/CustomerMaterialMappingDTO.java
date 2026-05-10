package com.cpq.importexcel.dto;

import com.cpq.importexcel.entity.CustomerMaterialMapping;

import java.time.OffsetDateTime;
import java.util.UUID;

public class CustomerMaterialMappingDTO {

    public UUID id;
    public UUID customerId;
    public String customerPartNo;
    public UUID materialId;
    public String materialNo;
    public String materialName;
    public OffsetDateTime createdAt;

    public static CustomerMaterialMappingDTO from(CustomerMaterialMapping m) {
        CustomerMaterialMappingDTO dto = new CustomerMaterialMappingDTO();
        dto.id = m.id;
        dto.customerId = m.customerId;
        dto.customerPartNo = m.customerPartNo;
        dto.materialId = m.materialId;
        dto.createdAt = m.createdAt;
        return dto;
    }
}
