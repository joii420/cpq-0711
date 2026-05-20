package com.cpq.configtemplate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TemplateRequest {
    @NotBlank(message = "code 必填")
    @Size(max = 50)
    public String code;

    @NotBlank(message = "name 必填")
    @Size(max = 200)
    public String name;

    public String description;
}
