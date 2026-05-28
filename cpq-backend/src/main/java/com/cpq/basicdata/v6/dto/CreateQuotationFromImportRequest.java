package com.cpq.basicdata.v6.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * V6 commit 阶段：导入完成后建报价单的请求。
 * <p>对应前端 V6 Drawer Step 2 "选模板 + 填报价单名" 表单。
 */
public class CreateQuotationFromImportRequest {

    @NotNull public UUID importRecordId;

    @NotNull public UUID customerId;

    @NotNull
    @Size(max = 500)
    public String name;

    public UUID categoryId;
    public UUID customerTemplateId;
    public UUID costingTemplateId;
}
