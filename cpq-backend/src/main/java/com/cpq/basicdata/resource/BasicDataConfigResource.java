package com.cpq.basicdata.resource;

import com.cpq.basicdata.dto.*;
import com.cpq.basicdata.service.BasicDataConfigService;
import com.cpq.basicdata.service.BasicDataConfigService.ExtensibleTableDTO;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/basic-data-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BasicDataConfigResource {

    @Inject
    BasicDataConfigService service;

    // ===== 辅助：可选物理表清单 =====

    /**
     * V58：返回 TableRegistry 全部表摘要，供前端下拉选择 target_table。
     * <p>GET /api/cpq/basic-data-config/extensible-tables
     */
    @GET
    @Path("/extensible-tables")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<List<ExtensibleTableDTO>> listExtensibleTables() {
        return ApiResponse.success(service.listExtensibleTables());
    }

    // ===== Sheet =====

    @GET
    @Path("/sheets")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<BasicDataConfigDTO>> listSheets(
            @QueryParam("status") String status,
            // V79：组件 PathPickerDrawer 按"报价/核价"过滤可选 sheet
            @QueryParam("templateKind") String templateKind) {
        return ApiResponse.success(service.listSheets(status, templateKind));
    }

    @GET
    @Path("/sheets/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<BasicDataConfigDTO> getSheet(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getSheet(id));
    }

    @POST
    @Path("/sheets")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<BasicDataConfigDTO> createSheet(@Valid CreateBasicDataConfigRequest req) {
        return ApiResponse.success(service.createSheet(req));
    }

    @PUT
    @Path("/sheets/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<BasicDataConfigDTO> updateSheet(@PathParam("id") UUID id,
                                                        CreateBasicDataConfigRequest req) {
        return ApiResponse.success(service.updateSheet(id, req));
    }

    @DELETE
    @Path("/sheets/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteSheet(@PathParam("id") UUID id) {
        service.deleteSheet(id);
        return ApiResponse.success();
    }

    // ===== Attributes =====

    @GET
    @Path("/attributes")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<BasicDataAttributeDTO>> listAttributes(@QueryParam("sheetId") UUID sheetId,
                                                                    @QueryParam("status") String status) {
        return ApiResponse.success(service.listAttributes(sheetId, status));
    }

    @POST
    @Path("/attributes")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<BasicDataAttributeDTO> createAttribute(@Valid CreateBasicDataAttributeRequest req) {
        return ApiResponse.success(service.createAttribute(req));
    }

    @PUT
    @Path("/attributes/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<BasicDataAttributeDTO> updateAttribute(@PathParam("id") UUID id,
                                                               CreateBasicDataAttributeRequest req) {
        return ApiResponse.success(service.updateAttribute(id, req));
    }

    @DELETE
    @Path("/attributes/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> disableAttribute(@PathParam("id") UUID id) {
        service.disableAttribute(id);
        return ApiResponse.success();
    }

    /**
     * D-5：专用端点 — 更新字段重要性（importanceLevel + affectsCalculation）。
     * 仅 SYSTEM_ADMIN 可调用，防止普通业务角色随意修改影响公式计算的配置。
     *
     * PATCH /api/cpq/basic-data-config/attributes/{id}/importance
     * Body: { "importanceLevel": "CRITICAL", "affectsCalculation": true }
     */
    @PATCH
    @Path("/attributes/{id}/importance")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<BasicDataAttributeDTO> updateAttributeImportance(
            @PathParam("id") UUID id,
            UpdateAttributeImportanceRequest req) {
        return ApiResponse.success(
                service.updateAttributeImportance(id, req.importanceLevel, req.affectsCalculation));
    }

    /** D-5：字段重要性更新请求体 */
    public static class UpdateAttributeImportanceRequest {
        public String importanceLevel;
        public Boolean affectsCalculation;
    }

    // ===== Derived =====

    @GET
    @Path("/derived")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<DerivedAttributeDTO>> listDerived(@QueryParam("sheetId") UUID sheetId,
                                                               @QueryParam("status") String status) {
        return ApiResponse.success(service.listDerived(sheetId, status));
    }

    @POST
    @Path("/derived")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DerivedAttributeDTO> createDerived(@Valid CreateDerivedAttributeRequest req) {
        return ApiResponse.success(service.createDerived(req));
    }

    @PUT
    @Path("/derived/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DerivedAttributeDTO> updateDerived(@PathParam("id") UUID id,
                                                           CreateDerivedAttributeRequest req) {
        return ApiResponse.success(service.updateDerived(id, req));
    }

    @DELETE
    @Path("/derived/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> disableDerived(@PathParam("id") UUID id) {
        service.disableDerived(id);
        return ApiResponse.success();
    }

    // ===== Excel 解析 =====

    @POST
    @Path("/parse-excel")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ParsedExcelStructureDTO> parseExcel(@RestForm("file") FileUpload file) {
        if (file == null || file.uploadedFile() == null) {
            throw new BusinessException(400, "file 参数缺失：请使用 multipart/form-data 上传一个名为 'file' 的 Excel 文件");
        }
        try (InputStream is = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            return ApiResponse.success(service.parseExcel(is));
        } catch (Exception e) {
            throw new BusinessException(400, "Parse failed: " + e.getMessage());
        }
    }
}
