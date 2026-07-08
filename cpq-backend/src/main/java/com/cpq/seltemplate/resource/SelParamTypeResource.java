package com.cpq.seltemplate.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.seltemplate.dto.ParamCandidateDTO;
import com.cpq.seltemplate.dto.SelParamTypeDTO;
import com.cpq.seltemplate.service.SelParamCandidateService;
import com.cpq.seltemplate.service.SelParamTypeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/cpq/sel-param-types")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"PRICING_MANAGER", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class SelParamTypeResource {
    @Inject SelParamTypeService paramTypeService;
    @Inject SelParamCandidateService candidateService;

    @GET
    public ApiResponse<List<SelParamTypeDTO>> list() {
        return ApiResponse.success(paramTypeService.listAll());
    }

    @GET
    @Path("/{code}/candidates")
    public ApiResponse<List<ParamCandidateDTO>> candidates(@PathParam("code") String code) {
        return ApiResponse.success(candidateService.candidates(code));
    }
}
