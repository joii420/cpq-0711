package com.cpq.auth.dto;

import java.util.UUID;

public class LoginResponse {

    public UUID id;
    public String username;
    public String fullName;
    public String role;
    public boolean forceChangePassword;

    public LoginResponse(UUID id, String username, String fullName, String role, boolean forceChangePassword) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.forceChangePassword = forceChangePassword;
    }
}
