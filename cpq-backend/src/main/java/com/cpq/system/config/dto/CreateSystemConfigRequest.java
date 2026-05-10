package com.cpq.system.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateSystemConfigRequest {

    @NotBlank(message = "config_key 不能为空")
    @Pattern(regexp = "^[a-z0-9_]+\\.[a-z0-9_]+$", message = "config_key 格式须为 'category.key'（小写字母、数字和下划线）")
    public String configKey;

    @NotBlank(message = "config_value 不能为空")
    public String configValue;

    @NotBlank(message = "default_value 不能为空")
    public String defaultValue;

    @NotBlank(message = "data_type 不能为空")
    @Pattern(regexp = "STRING|NUMBER|BOOLEAN|JSON", message = "data_type 须为 STRING/NUMBER/BOOLEAN/JSON 之一")
    public String dataType;

    @NotBlank(message = "category 不能为空")
    @Pattern(regexp = "validation|import|retention|element_price|business",
            message = "category 须为 validation/import/retention/element_price/business 之一")
    public String category;

    @Size(max = 500, message = "description 不能超过 500 个字符")
    public String description;

    @Pattern(regexp = "SYSTEM_ADMIN|SALES_MANAGER", message = "modifiable_by 须为 SYSTEM_ADMIN 或 SALES_MANAGER")
    public String modifiableBy = "SYSTEM_ADMIN";
}
