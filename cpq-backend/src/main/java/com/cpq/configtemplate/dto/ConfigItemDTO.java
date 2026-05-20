package com.cpq.configtemplate.dto;

import com.cpq.configtemplate.entity.ConfigItem;

import java.util.UUID;

public class ConfigItemDTO {
    public UUID id;
    public UUID categoryId;
    public String code;
    public String name;
    public String defaultValue;
    public Integer sortOrder;
    public String status;

    public static ConfigItemDTO from(ConfigItem i) {
        ConfigItemDTO d = new ConfigItemDTO();
        d.id = i.id;
        d.categoryId = i.categoryId;
        d.code = i.code;
        d.name = i.name;
        d.defaultValue = i.defaultValue;
        d.sortOrder = i.sortOrder;
        d.status = i.status;
        return d;
    }
}
