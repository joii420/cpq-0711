package com.cpq.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public class CreateUserRequest {
    @NotBlank(message = "用户名不能为空") public String username;
    @NotBlank(message = "姓名不能为空") public String fullName;
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") public String email;
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "SALES_REP|SALES_MANAGER|PRICING_MANAGER|SYSTEM_ADMIN", message = "无效的角色")
    public String role;
    public UUID regionId;
    public UUID departmentId;
}
