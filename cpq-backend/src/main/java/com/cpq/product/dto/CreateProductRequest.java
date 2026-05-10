package com.cpq.product.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public class CreateProductRequest {

    @NotBlank
    public String name;

    @NotBlank
    public String partNo;

    public String category;

    public UUID categoryId;

    public String specification;

    public String drawingNo;

    public String dimension;

    public String material;

    public String status;

    public List<String> tags;
}
