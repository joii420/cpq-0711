package com.cpq.configure.dto;

import java.util.List;
import java.util.UUID;

public class MaterialRecipeDTO {
    public UUID id;
    public String code;
    public String symbol;
    public String name;
    public String specLabel;
    public String recipeType;
    public String status;
    public Integer sortOrder;
    /** Only populated in detail endpoint (GET /{id}); list endpoint leaves it null. */
    public List<MaterialRecipeElementDTO> elements;
}
