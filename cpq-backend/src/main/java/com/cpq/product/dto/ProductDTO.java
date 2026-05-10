package com.cpq.product.dto;

import com.cpq.product.entity.Product;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProductDTO {

    public UUID id;
    public String name;
    public String partNo;
    public String category;
    public UUID categoryId;
    public String categoryName;
    public String specification;
    public String drawingNo;
    public String dimension;
    public String material;
    public String status;
    public List<String> tags;
    public String externalId;
    public OffsetDateTime lastSyncedAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProductDTO from(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.id = product.id;
        dto.name = product.name;
        dto.partNo = product.partNo;
        dto.category = product.category;
        dto.categoryId = product.categoryId;
        if (product.categoryId != null) {
            com.cpq.basicdata.entity.ProductCategory pc = com.cpq.basicdata.entity.ProductCategory.findById(product.categoryId);
            if (pc != null) dto.categoryName = pc.name;
        }
        dto.specification = product.specification;
        dto.drawingNo = product.drawingNo;
        dto.dimension = product.dimension;
        dto.material = product.material;
        dto.status = product.status;
        dto.externalId = product.externalId;
        dto.lastSyncedAt = product.lastSyncedAt;
        dto.createdAt = product.createdAt;
        dto.updatedAt = product.updatedAt;
        dto.tags = parseTags(product.tags);
        return dto;
    }

    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
