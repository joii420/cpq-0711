package com.cpq.customer.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_contact")
public class CustomerContact extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(length = 50)
    public String role;

    @Column(nullable = false, length = 20)
    public String phone;

    @Column(length = 200)
    public String email;

    @Column(length = 100)
    public String wechat;

    @Column(name = "is_primary", nullable = false)
    public Boolean isPrimary = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
