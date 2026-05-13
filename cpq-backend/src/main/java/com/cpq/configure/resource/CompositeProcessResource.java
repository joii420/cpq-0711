package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.service.CompositeProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

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
}
