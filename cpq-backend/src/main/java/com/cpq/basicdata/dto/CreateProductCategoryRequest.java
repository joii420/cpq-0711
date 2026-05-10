package com.cpq.basicdata.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class CreateProductCategoryRequest {

    @NotBlank
    public String code;

    @NotBlank
    public String name;

    public String description;

    public UUID parentId;

    public String status;

    public Integer sortOrder;
}
