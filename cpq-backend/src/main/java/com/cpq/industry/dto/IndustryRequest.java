package com.cpq.industry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class IndustryRequest {
    @NotBlank(message = "行业编码不能为空")
    @Size(max = 50)
    public String code;

    @NotBlank(message = "行业名称不能为空")
    @Size(max = 100)
    public String name;

    @Size(max = 20)
    public String status;
}
