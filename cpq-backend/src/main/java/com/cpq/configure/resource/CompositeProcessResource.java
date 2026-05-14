package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.dto.CompositeProcessUpsertRequest;
import com.cpq.configure.service.CompositeProcessService;
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

@Path("/api/cpq/composite-processes")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CompositeProcessResource {

    @Inject
    CompositeProcessService service;

    @GET
    public List<CompositeProcessDefDTO> list() {
        return service.listActive();
    }

    @GET
    @Path("/{id}")
    public CompositeProcessDefDTO getById(@PathParam("id") UUID id) {
        return service.getById(id);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public CompositeProcessDefDTO create(CompositeProcessUpsertRequest req) {
        return service.create(req);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public CompositeProcessDefDTO update(@PathParam("id") UUID id, CompositeProcessUpsertRequest req) {
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
