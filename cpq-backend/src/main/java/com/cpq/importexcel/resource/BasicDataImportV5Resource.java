package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.importexcel.dto.ResolutionDTO;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * V5 基础资料导入 REST 端点。
 *
 * <p>路由:
 *   POST /api/cpq/import/basic-data/v5/preview  — 预览（解析+校验，不写库）
 *   POST /api/cpq/import/basic-data/v5/confirm  — 写入（全有全无事务）
 */
@Path("/api/cpq/import/basic-data/v5")
@Produces(MediaType.APPLICATION_JSON)
public class BasicDataImportV5Resource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    BasicDataImportServiceV5 serviceV5;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    /**
     * 预览：解析 Excel + 运行 BV-01~BV-32 校验，不写库。
     *
     * <p>Request (multipart/form-data):
     *   - customerId: UUID
     *   - file: Excel (.xlsx) 文件
     *
     * <p>Response:
     * {@code
     * {
     *   "code": 200,
     *   "data": {
     *     "status": "PREVIEW_OK" | "PREVIEW_BLOCKED",
     *     "totalRows": 15,
     *     "validation": {
     *       "hasErrors": false,
     *       "hasWarnings": true,
     *       "errors": [],
     *       "warnings": [{"bvCode": "BV-01", "row": 3, "sheet": "BOM清单", "message": "..."}]
     *     }
     *   }
     * }
     * }
     */
    @POST
    @Path("/preview")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResultDTO> preview(@RestForm("customerId") UUID customerId,
                                                 @RestForm("file") FileUpload file,
                                                 @RestForm("templateKind") String templateKind) {
        if (customerId == null) {
            throw new BusinessException(400, "customerId 不能为空");
        }
        if (file == null) {
            throw new BusinessException(400, "file 不能为空");
        }
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        // V94: templateKind=COSTING/QUOTATION 决定 sheet 配置选择, 默认 QUOTATION
        String kind = (templateKind != null && !templateKind.isBlank()) ? templateKind : "QUOTATION";
        try (InputStream is = java.nio.file.Files.newInputStream(file.uploadedFile())) {
            ImportResultDTO result = serviceV5.previewV5(is, customerId, userId, kind);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(400, "预览失败: " + e.getMessage());
        }
    }

    /**
     * 确认写入：全有全无事务，写入 14 张物理表。
     *
     * <p>Request (multipart/form-data):
     *   - customerId: UUID
     *   - file: Excel (.xlsx) 文件
     *
     * <p>Response:
     * {@code
     * {
     *   "code": 200,
     *   "data": {
     *     "importRecordId": "...",
     *     "status": "SUCCESS" | "FAILED",
     *     "totalRows": 15,
     *     "matPartCreated": 3,
     *     "matPartUpdated": 0,
     *     ...
     *   }
     * }
     * }
     */
    /**
     * 确认写入：全有全无事务，写入 14 张物理表。
     * 支持 resolutions 参数（UI-1/UI-2 决策，可为 null 或空字符串，等价于无决策）。
     *
     * <p>Request (multipart/form-data):
     *   - customerId: UUID
     *   - file: Excel (.xlsx) 文件
     *   - resolutions: JSON 字符串（可选），格式: [{type,tableName,rowKey,fieldName,decision,note,oldValueAtPreview}]
     */
    @POST
    @Path("/confirm")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResultDTO> confirm(@RestForm("customerId") UUID customerId,
                                                 @RestForm("file") FileUpload file,
                                                 @RestForm("resolutions") String resolutionsJson,
                                                 @RestForm("templateKind") String templateKind) {
        if (customerId == null) {
            throw new BusinessException(400, "customerId 不能为空");
        }
        if (file == null) {
            throw new BusinessException(400, "file 不能为空");
        }
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        List<ResolutionDTO> resolutions = parseResolutions(resolutionsJson);
        String kind = (templateKind != null && !templateKind.isBlank()) ? templateKind : "QUOTATION";
        try (InputStream is = java.nio.file.Files.newInputStream(file.uploadedFile())) {
            ImportResultDTO result = serviceV5.importBasicDataV5(is, customerId, userId, resolutions, kind);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "导入失败: " + e.getMessage());
        }
    }

    /**
     * 解析 resolutions JSON 字符串。
     * null / 空字符串 → 空列表；格式错误 → 抛 400。
     */
    private List<ResolutionDTO> parseResolutions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<ResolutionDTO>>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "resolutions 格式错误: " + e.getMessage());
        }
    }
}
