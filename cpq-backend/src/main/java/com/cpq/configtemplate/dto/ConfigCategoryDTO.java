package com.cpq.configtemplate.dto;

import com.cpq.configtemplate.entity.ConfigCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConfigCategoryDTO {
    public UUID id;
    public UUID templateId;
    public String code;
    public String name;
    public Integer sortOrder;
    public String status;
    public List<ConfigItemDTO> items = new ArrayList<>();

    public static ConfigCategoryDTO from(ConfigCategory c) {
        ConfigCategoryDTO d = new ConfigCategoryDTO();
        d.id = c.id;
        d.templateId = c.templateId;
        d.code = c.code;
        d.name = c.name;
        d.sortOrder = c.sortOrder;
        d.status = c.status;
        return d;
    }
}
