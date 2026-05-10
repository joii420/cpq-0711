package com.cpq.costingpart.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costingpart.entity.*;
import com.cpq.costingpart.service.CostingPartDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * 料号级核价数据 API。
 * 资源粒度：8 类各一组 list / save / delete；list 按料号过滤。
 */
@Path("/api/cpq/costing-part")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CostingPartDataResource {

    @Inject CostingPartDataService svc;

    // ── 1. 工序级单价 ──────────────────────────────────────────────
    @GET @Path("/process-cost")
    public ApiResponse<List<CostingPartProcessCost>> listProcessCost(
            @QueryParam("hfPartNo") String hfPartNo,
            @QueryParam("costType") String costType) {
        return ApiResponse.success(svc.listProcessCost(hfPartNo, costType));
    }
    @POST @Path("/process-cost")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartProcessCost> saveProcessCost(CostingPartProcessCost req) {
        return ApiResponse.success(svc.saveProcessCost(req));
    }
    @DELETE @Path("/process-cost/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteProcessCost(@PathParam("id") UUID id) {
        svc.deleteProcessCost(id); return ApiResponse.success();
    }

    // ── 2. 模具工装 ────────────────────────────────────────────────
    @GET @Path("/tooling")
    public ApiResponse<List<CostingPartToolingCost>> listTooling(@QueryParam("hfPartNo") String hfPartNo) {
        return ApiResponse.success(svc.listTooling(hfPartNo));
    }
    @POST @Path("/tooling")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartToolingCost> saveTooling(CostingPartToolingCost req) {
        return ApiResponse.success(svc.saveTooling(req));
    }
    @DELETE @Path("/tooling/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteTooling(@PathParam("id") UUID id) {
        svc.deleteTooling(id); return ApiResponse.success();
    }

    // ── 3. 材料 BOM ────────────────────────────────────────────────
    @GET @Path("/material-bom")
    public ApiResponse<List<CostingPartMaterialBom>> listMaterialBom(@QueryParam("hfPartNo") String hfPartNo) {
        return ApiResponse.success(svc.listMaterialBom(hfPartNo));
    }
    @POST @Path("/material-bom")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartMaterialBom> saveMaterialBom(CostingPartMaterialBom req) {
        return ApiResponse.success(svc.saveMaterialBom(req));
    }
    @DELETE @Path("/material-bom/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteMaterialBom(@PathParam("id") UUID id) {
        svc.deleteMaterialBom(id); return ApiResponse.success();
    }

    // ── 4. 元素 BOM ────────────────────────────────────────────────
    @GET @Path("/element-bom")
    public ApiResponse<List<CostingPartElementBom>> listElementBom(@QueryParam("inputMaterialNo") String inputMaterialNo) {
        return ApiResponse.success(svc.listElementBom(inputMaterialNo));
    }
    @POST @Path("/element-bom")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartElementBom> saveElementBom(CostingPartElementBom req) {
        return ApiResponse.success(svc.saveElementBom(req));
    }
    @DELETE @Path("/element-bom/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteElementBom(@PathParam("id") UUID id) {
        svc.deleteElementBom(id); return ApiResponse.success();
    }

    // ── 5. 质量检验 ────────────────────────────────────────────────
    @GET @Path("/quality-check")
    public ApiResponse<List<CostingPartQualityCheck>> listQualityCheck(
            @QueryParam("hfPartNo") String hfPartNo,
            @QueryParam("stage") String stage) {
        return ApiResponse.success(svc.listQualityCheck(hfPartNo, stage));
    }
    @POST @Path("/quality-check")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartQualityCheck> saveQualityCheck(CostingPartQualityCheck req) {
        return ApiResponse.success(svc.saveQualityCheck(req));
    }
    @DELETE @Path("/quality-check/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteQualityCheck(@PathParam("id") UUID id) {
        svc.deleteQualityCheck(id); return ApiResponse.success();
    }

    // ── 6. 电镀 ────────────────────────────────────────────────────
    @GET @Path("/plating")
    public ApiResponse<List<CostingPartPlating>> listPlating(@QueryParam("platingNo") String platingNo) {
        return ApiResponse.success(platingNo == null ? svc.listAllPlating() : svc.listPlating(platingNo));
    }
    @POST @Path("/plating")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartPlating> savePlating(CostingPartPlating req) {
        return ApiResponse.success(svc.savePlating(req));
    }
    @DELETE @Path("/plating/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deletePlating(@PathParam("id") UUID id) {
        svc.deletePlating(id); return ApiResponse.success();
    }

    // ── 6.b 电镀费用 (按 hfPartNo, 只读) ──────────────────────────
    @GET @Path("/plating-fee")
    public ApiResponse<List<PlatingFee>> listPlatingFee(@QueryParam("hfPartNo") String hfPartNo) {
        return ApiResponse.success(svc.listPlatingFee(hfPartNo));
    }

    // ── 7. 设计成本 ────────────────────────────────────────────────
    @GET @Path("/design-cost")
    public ApiResponse<List<CostingPartDesignCost>> listDesignCost(@QueryParam("hfPartNo") String hfPartNo) {
        return ApiResponse.success(svc.listDesignCost(hfPartNo));
    }
    @POST @Path("/design-cost")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartDesignCost> saveDesignCost(CostingPartDesignCost req) {
        return ApiResponse.success(svc.saveDesignCost(req));
    }
    @DELETE @Path("/design-cost/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteDesignCost(@PathParam("id") UUID id) {
        svc.deleteDesignCost(id); return ApiResponse.success();
    }

    // ── 8. 重量 ────────────────────────────────────────────────────
    @GET @Path("/weight")
    public ApiResponse<CostingPartWeight> getWeight(@QueryParam("hfPartNo") String hfPartNo) {
        return ApiResponse.success(svc.getWeight(hfPartNo));
    }
    @POST @Path("/weight")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPartWeight> saveWeight(CostingPartWeight req) {
        return ApiResponse.success(svc.saveWeight(req));
    }
    @DELETE @Path("/weight/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteWeight(@PathParam("id") UUID id) {
        svc.deleteWeight(id); return ApiResponse.success();
    }
}
