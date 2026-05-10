package com.cpq.approval.resource;

import com.cpq.approval.dto.ApprovalRuleDTO;
import com.cpq.approval.dto.CreateApprovalRuleRequest;
import com.cpq.approval.service.ApprovalRuleService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/approval-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class ApprovalRuleResource {

    @Inject
    ApprovalRuleService approvalRuleService;

    @GET
    public ApiResponse<List<ApprovalRuleDTO>> list() {
        return ApiResponse.success(approvalRuleService.list());
    }

    @POST
    public ApiResponse<ApprovalRuleDTO> create(@Valid CreateApprovalRuleRequest request) {
        return ApiResponse.success(approvalRuleService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ApprovalRuleDTO> update(@PathParam("id") UUID id,
                                                @Valid CreateApprovalRuleRequest request) {
        return ApiResponse.success(approvalRuleService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        approvalRuleService.delete(id);
        return ApiResponse.success();
    }
}
