package com.cpq.customer.dto;

import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class CustomerDTO {

    public UUID id;
    public String name;
    public String code;
    public String level;
    public String industry;
    public String industryCode;
    public UUID productCategoryId;   // task-0712 update-071501; 不返 productCategoryName(D5，前端自行按 product-categories 列表映射)
    public String region;
    public String address;
    public BigDecimal accumulatedAmount;
    public BigDecimal creditLimit;
    public String paymentMethod;
    public String remarks;
    public String status;
    public Integer version;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public List<ContactDTO> contacts = new ArrayList<>();
    public Long quotationCount;
    public Double avgDiscountRate;

    public static CustomerDTO from(Customer customer) {
        CustomerDTO dto = new CustomerDTO();
        dto.id = customer.id;
        dto.name = customer.name;
        dto.code = customer.code;
        dto.level = customer.level;
        dto.industry = customer.industry;
        dto.industryCode = customer.industryCode;
        dto.productCategoryId = customer.productCategoryId;
        dto.region = customer.region;
        dto.address = customer.address;
        dto.accumulatedAmount = customer.accumulatedAmount;
        dto.creditLimit = customer.creditLimit;
        dto.paymentMethod = customer.paymentMethod;
        dto.remarks = customer.remarks;
        dto.status = customer.status;
        dto.version = customer.version;
        dto.createdAt = customer.createdAt;
        dto.updatedAt = customer.updatedAt;
        return dto;
    }

    public static CustomerDTO from(Customer customer, List<CustomerContact> contacts) {
        CustomerDTO dto = from(customer);
        dto.contacts = contacts.stream().map(ContactDTO::from).collect(Collectors.toList());
        return dto;
    }
}
