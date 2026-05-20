package com.cpq.configtemplate.dto;

import com.cpq.configtemplate.entity.ConfigTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConfigTemplateDTO {
    public UUID id;
    public String code;
    public String name;
    public String description;
    public String status;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public UUID createdBy;
    public OffsetDateTime publishedAt;
    /** 嵌套的大类列表 (仅 detail 端点返回, list 端点为空避免 N+1). */
    public List<ConfigCategoryDTO> categories = new ArrayList<>();

    public static ConfigTemplateDTO from(ConfigTemplate t) {
        ConfigTemplateDTO d = new ConfigTemplateDTO();
        d.id = t.id;
        d.code = t.code;
        d.name = t.name;
        d.description = t.description;
        d.status = t.status;
        d.createdAt = t.createdAt;
        d.updatedAt = t.updatedAt;
        d.createdBy = t.createdBy;
        d.publishedAt = t.publishedAt;
        return d;
    }
}
