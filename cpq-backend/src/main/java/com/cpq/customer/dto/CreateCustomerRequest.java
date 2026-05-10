package com.cpq.customer.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public class CreateCustomerRequest {

    @NotBlank
    public String name;

    public String level;
    public String industry;
    public String region;
    public String address;
    public BigDecimal creditLimit;
    public String paymentMethod;
    public String remarks;
    public List<ContactDTO> contacts;
}
