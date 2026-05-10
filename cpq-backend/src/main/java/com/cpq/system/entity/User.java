package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "\"user\"")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 100)
    public String username;

    @Column(name = "full_name", nullable = false, length = 200)
    public String fullName;

    @Column(nullable = false, unique = true, length = 200)
    public String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    public String passwordHash;

    @Column(nullable = false, length = 30)
    public String role;

    @Column(name = "region_id")
    public UUID regionId;

    @Column(name = "department_id")
    public UUID departmentId;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "is_first_login", nullable = false)
    public Boolean isFirstLogin = true;

    @Column(name = "initial_password_expires_at")
    public OffsetDateTime initialPasswordExpiresAt;

    @Column(name = "failed_login_attempts", nullable = false)
    public Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    public OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
