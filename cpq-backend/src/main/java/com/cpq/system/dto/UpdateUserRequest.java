package com.cpq.system.dto;

import java.util.UUID;

public class UpdateUserRequest {
    public String fullName;
    public String email;
    public String role;
    public UUID regionId;
    public UUID departmentId;
    public String status;
}
