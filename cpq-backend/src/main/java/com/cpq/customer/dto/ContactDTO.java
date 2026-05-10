package com.cpq.customer.dto;

import com.cpq.customer.entity.CustomerContact;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ContactDTO {

    public UUID id;
    public UUID customerId;
    public String name;
    public String role;
    public String phone;
    public String email;
    public String wechat;
    public Boolean isPrimary;
    public OffsetDateTime createdAt;

    public static ContactDTO from(CustomerContact contact) {
        ContactDTO dto = new ContactDTO();
        dto.id = contact.id;
        dto.customerId = contact.customerId;
        dto.name = contact.name;
        dto.role = contact.role;
        dto.phone = contact.phone;
        dto.email = contact.email;
        dto.wechat = contact.wechat;
        dto.isPrimary = contact.isPrimary;
        dto.createdAt = contact.createdAt;
        return dto;
    }
}
