package com.cpq.system.config.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateSystemConfigRequest {

    @NotBlank(message = "config_value 不能为空")
    public String configValue;

    public String description;
}
