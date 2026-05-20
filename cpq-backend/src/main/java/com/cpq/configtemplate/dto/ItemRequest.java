package com.cpq.configtemplate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ItemRequest {
    @NotBlank(message = "code 必填")
    @Size(max = 50)
    public String code;

    @NotBlank(message = "name 必填")
    @Size(max = 200)
    public String name;

    @Size(max = 500)
    public String defaultValue;

    public Integer sortOrder;
    public String status;
}
