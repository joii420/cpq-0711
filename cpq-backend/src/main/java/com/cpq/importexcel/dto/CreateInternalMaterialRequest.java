package com.cpq.importexcel.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateInternalMaterialRequest {

    @NotBlank
    public String materialNo;

    @NotBlank
    public String name;

    public String specification;
    public String size;
    public String statusCode = "Y";
}
