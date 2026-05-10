package com.cpq.template.dto;

import com.cpq.template.entity.ProductTemplateBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProductTemplateBindingDTO {

    public UUID id;
    public UUID productId;
    public List<String> processIds;
    public String processIdsHash;
    public UUID templateId;
    public Boolean isDefault;
    public OffsetDateTime createdAt;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProductTemplateBindingDTO from(ProductTemplateBinding binding) {
        ProductTemplateBindingDTO dto = new ProductTemplateBindingDTO();
        dto.id = binding.id;
        dto.productId = binding.productId;
        dto.processIds = parseStringList(binding.processIds);
        dto.processIdsHash = binding.processIdsHash;
        dto.templateId = binding.templateId;
        dto.isDefault = binding.isDefault;
        dto.createdAt = binding.createdAt;
        return dto;
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
