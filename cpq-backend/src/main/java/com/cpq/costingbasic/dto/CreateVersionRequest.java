package com.cpq.costingbasic.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateVersionRequest {
    /** ELEMENT / MATERIAL / EXCHANGE */
    @NotBlank
    public String versionKind;

    /** 版本号（用户自定义，如 2000） */
    @NotBlank
    public String versionNumber;

    public String notes;

    /** 创建时是否同时设为默认（仅在 publish 后生效） */
    public Boolean isDefault;
}
