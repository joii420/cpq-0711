package com.cpq.health;

import com.cpq.common.dto.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/cpq/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @GET
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
            "status", "UP",
            "service", "CPQ Quotation System"
        ));
    }
}
