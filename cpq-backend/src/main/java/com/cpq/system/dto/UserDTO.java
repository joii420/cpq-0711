package com.cpq.system.dto;

import com.cpq.system.entity.User;
import java.time.OffsetDateTime;
import java.util.UUID;

public class UserDTO {
    public UUID id;
    public String username;
    public String fullName;
    public String email;
    public String role;
    public UUID regionId;
    public UUID departmentId;
    public String status;
    public Boolean isFirstLogin;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public String initialPassword; // Only on create response

    public static UserDTO from(User entity) {
        UserDTO dto = new UserDTO();
        dto.id = entity.id;
        dto.username = entity.username;
        dto.fullName = entity.fullName;
        dto.email = entity.email;
        dto.role = entity.role;
        dto.regionId = entity.regionId;
        dto.departmentId = entity.departmentId;
        dto.status = entity.status;
        dto.isFirstLogin = entity.isFirstLogin;
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
        return dto;
    }
}
