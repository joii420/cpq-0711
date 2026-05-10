package com.cpq.basicdata.dto;

import com.cpq.basicdata.entity.ProductCategory;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ProductCategoryDTO {

    public UUID id;
    public String code;
    public String name;
    public String description;
    public UUID parentId;
    public String status;
    public Integer sortOrder;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static ProductCategoryDTO from(ProductCategory c) {
        ProductCategoryDTO dto = new ProductCategoryDTO();
        dto.id = c.id;
        dto.code = c.code;
        dto.name = c.name;
        dto.description = c.description;
        dto.parentId = c.parentId;
        dto.status = c.status;
        dto.sortOrder = c.sortOrder;
        dto.createdAt = c.createdAt;
        dto.updatedAt = c.updatedAt;
        return dto;
    }
}
