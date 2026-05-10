package com.cpq.product.dto;

import com.cpq.product.entity.Process;
import com.cpq.product.entity.ProductProcess;

import java.util.UUID;

public class ProductProcessDTO {

    public UUID id;
    public UUID processId;
    public String code;
    public String name;
    public String description;
    public String category;
    public boolean isRequired;
    public int sortOrder;

    public static ProductProcessDTO from(ProductProcess pp, Process process) {
        ProductProcessDTO dto = new ProductProcessDTO();
        dto.id = pp.id;
        dto.processId = pp.processId;
        dto.code = process.code;
        dto.name = process.name;
        dto.description = process.description;
        dto.category = process.category;
        dto.isRequired = pp.isRequired;
        dto.sortOrder = pp.sortOrder;
        return dto;
    }
}
