package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.ConfirmImportRequest;
import com.cpq.importexcel.dto.ImportPreviewDTO;
import com.cpq.importexcel.dto.ImportRecordDTO;
import com.cpq.importexcel.service.ImportExecutionService;
import com.cpq.importexcel.service.ImportRecordService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.UUID;

@Path("/api/cpq/imports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImportResource {

    @Inject
    ImportExecutionService executionService;

    @Inject
    ImportRecordService recordService;

    @Inject
    SessionHelper sessionHelper;

    @POST
    @Path("/execute")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportRecordDTO> execute(
            @RestForm("file") FileUpload file,
            @RestForm("customerId") String customerId,
            @RestForm("excelTemplateId") String excelTemplateId,
            @RestForm("mappingTemplateId") String mappingTemplateId,
            @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        try (java.io.InputStream is = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            ImportRecordDTO result = executionService.executeImport(
                    UUID.fromString(customerId),
                    UUID.fromString(excelTemplateId),
                    UUID.fromString(mappingTemplateId),
                    is,
                    file.fileName(),
                    userId);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "Import execution failed: " + e.getMessage());
        }
    }

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<ImportRecordDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerId") UUID customerId,
            @QueryParam("importStatus") String importStatus,
            @QueryParam("importedBy") UUID importedBy,
            @QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate) {
        OffsetDateTime start = startDate != null ? OffsetDateTime.parse(startDate) : null;
        OffsetDateTime end = endDate != null ? OffsetDateTime.parse(endDate) : null;
        return ApiResponse.success(recordService.list(page, size, customerId, importStatus, importedBy, start, end));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportRecordDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(recordService.getById(id));
    }

    // ===================================================================
    // v3 endpoints
    // ===================================================================

    /**
     * POST /api/cpq/imports/import-excel
     * Parse Excel and return preview data without creating a quotation.
     * The response includes parsed rows, part-number match results, and a
     * hidden __savedPath__ hint in the errors list (used by confirm-import).
     */
    @POST
    @Path("/import-excel")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportPreviewDTO> importPreview(
            @RestForm("file") FileUpload file,
            @RestForm("templateId") String templateId,
            @RestForm("customerId") String customerId,
            @Context HttpServerRequest httpRequest) {
        if (file == null) throw new BusinessException(400, "file is required");
        if (templateId == null || templateId.isBlank()) throw new BusinessException(400, "templateId is required");
        if (customerId == null || customerId.isBlank()) throw new BusinessException(400, "customerId is required");
        try (java.io.InputStream is = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            ImportPreviewDTO preview = executionService.previewImport(
                    UUID.fromString(customerId),
                    UUID.fromString(templateId),
                    is,
                    file.fileName());
            return ApiResponse.success(preview);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "Preview failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/cpq/imports/confirm-import
     * Create quotation + import record from pre-parsed preview data.
     */
    @POST
    @Path("/confirm-import")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportRecordDTO> confirmImport(
            ConfirmImportRequest request,
            @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        ImportRecordDTO result = executionService.confirmImport(request, userId);
        return ApiResponse.success(result);
    }

    @GET
    @Path("/{id}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response download(@PathParam("id") UUID id) {
        ImportRecordDTO record = recordService.getById(id);
        File file = new File(record.originalFilePath);
        if (!file.exists()) {
            throw new BusinessException(404, "File not found on server");
        }
        return Response.ok(file)
                .header("Content-Disposition", "attachment; filename=\"" + record.originalFileName + "\"")
                .build();
    }
}
