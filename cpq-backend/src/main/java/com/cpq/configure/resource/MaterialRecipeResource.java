package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.service.MaterialRecipeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/material-recipes")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class MaterialRecipeResource {

    @Inject
    MaterialRecipeService service;

    @GET
    public List<MaterialRecipeDTO> list() {
        return service.listActive();
    }

    @GET
    @Path("/{id}")
    public MaterialRecipeDTO detail(@PathParam("id") UUID id) {
        return service.getDetail(id);
    }
}
