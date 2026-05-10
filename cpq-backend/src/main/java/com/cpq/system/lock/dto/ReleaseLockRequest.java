package com.cpq.system.lock.dto;

import jakarta.validation.constraints.Pattern;

public class ReleaseLockRequest {

    @Pattern(regexp = "COMPLETED|CANCELLED|TIMEOUT|ADMIN_FORCE|ERROR",
            message = "reason 须为 COMPLETED/CANCELLED/TIMEOUT/ADMIN_FORCE/ERROR 之一")
    public String reason = "COMPLETED";
}
