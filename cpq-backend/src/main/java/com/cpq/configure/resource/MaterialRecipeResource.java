package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.service.MaterialRecipeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public MaterialRecipeDTO create(MaterialRecipeUpsertRequest req) {
        return service.create(req);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public MaterialRecipeDTO update(@PathParam("id") UUID id, MaterialRecipeUpsertRequest req) {
        return service.update(id, req);
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response delete(@PathParam("id") UUID id) {
        service.deleteSoft(id);
        return Response.noContent().build();
    }
}
