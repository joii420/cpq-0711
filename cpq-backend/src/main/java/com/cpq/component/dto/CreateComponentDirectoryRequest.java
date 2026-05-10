package com.cpq.component.dto;

import java.util.UUID;

public class CreateComponentDirectoryRequest {
    public String name;
    public UUID parentId;
    public Integer sortOrder;
}
