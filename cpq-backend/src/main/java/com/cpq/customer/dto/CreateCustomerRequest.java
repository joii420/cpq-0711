package com.cpq.customer.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class CreateCustomerRequest {

    @NotBlank
    public String name;

    public String level;
    public String industry;
    public String industryCode;
    // task-0712 update-071501: 产品分类绑定。前端保证必选(默认"默认分类")；
    // 后端兜底: 为空时自动填 name='默认分类' 的 id(不强加 @NotNull，避免存量兼容问题，见 CustomerService.create)。
    public UUID productCategoryId;
    public String region;
    public String address;
    public BigDecimal creditLimit;
    public String paymentMethod;
    public String remarks;
    public List<ContactDTO> contacts;
}
