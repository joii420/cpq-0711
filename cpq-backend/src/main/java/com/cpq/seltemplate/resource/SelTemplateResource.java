package com.cpq.seltemplate.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.seltemplate.dto.SelTemplateDTO;
import com.cpq.seltemplate.dto.SelTemplateUpsertRequest;
import com.cpq.seltemplate.service.SelTemplateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/sel-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"PRICING_MANAGER", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class SelTemplateResource {
    @Inject SelTemplateService templateService;
    @Inject com.cpq.seltemplate.service.EffectiveTemplateService effectiveTemplateService;

    @GET
    public ApiResponse<List<SelTemplateDTO>> list() {
        return ApiResponse.success(templateService.list());
    }

    @GET
    @Path("/effective")
    public ApiResponse<com.cpq.seltemplate.dto.EffectiveTemplateDTO> effective(
            @QueryParam("customerNo") String customerNo) {
        if (customerNo == null || customerNo.isBlank())
            throw new com.cpq.common.exception.BusinessException("customerNo 不能为空");
        return ApiResponse.success(effectiveTemplateService.getEffective(customerNo));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<SelTemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.getById(id));
    }

    @POST
    public ApiResponse<SelTemplateDTO> upsert(@Valid SelTemplateUpsertRequest req) {
        return ApiResponse.success(templateService.upsert(req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        templateService.delete(id);
        return ApiResponse.success();
    }
}
