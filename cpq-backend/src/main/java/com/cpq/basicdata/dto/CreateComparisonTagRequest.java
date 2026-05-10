package com.cpq.basicdata.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateComparisonTagRequest {

    @NotBlank
    public String code;

    @NotBlank
    public String label;

    @NotBlank
    public String groupName;

    public Integer groupSortOrder;
    public Integer tagSortOrder;
    public String status;
    public String description;
}
