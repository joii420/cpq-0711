package com.cpq.configure.dto;

import java.util.UUID;

public class CompositeProcessDefDTO {
    public UUID id;
    public String code;
    public String name;
    public String icon;
    public String description;
    /** JSON string (raw passthrough from DB JSONB column). 前端解析为参数数组. */
    public String paramSchema;
    public int sortOrder;
}
