package com.cpq.importsession.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importsession.dto.CommitRequest;
import com.cpq.importsession.dto.CommitResult;
import com.cpq.importsession.dto.DecisionUpdateRequest;
import com.cpq.importsession.dto.UploadResultDTO;
import com.cpq.importsession.service.ImportSessionService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * V6 导入会话 REST 端点。
 *
 * <p>路由：
 *   POST   /api/cpq/import-session/upload          — Step 1：上传 Excel，建 session，检测差异
 *   PUT    /api/cpq/import-session/{id}/decisions  — Step 2：更新版本决策（BUMP/NO_BUMP/...）
 *   POST   /api/cpq/import-session/{id}/commit     — Step 3：创建报价单，合并 staging → mat_*
 *   DELETE /api/cpq/import-session/{id}            — 取消/放弃 session
 */
@Path("/api/cpq/import-session")
@Produces(MediaType.APPLICATION_JSON)
public class ImportSessionResource {

    @Inject
    ImportSessionService importSessionService;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    /**
     * Step 1 Upload：解析 Excel，创建 import_session，检测差异，返回 DiffPayload 供前端渲染 Step 2。
     *
     * <p>Request (multipart/form-data):
     *   - customerId: UUID（必填）
     *   - file: Excel (.xlsx) 文件（必填）
     *
     * <p>Response 200:
     * <pre>{@code
     * {
     *   "code": 200,
     *   "data": {
     *     "sessionId": "3fa85f64-...",
     *     "diffPayload": {
     *       "partVersionDecisions": [...],
     *       "customerConflicts": [...],
     *       "orphanRows": [...],
     *       "validation": {"hasErrors": false, "errors": [], "warnings": []}
     *     }
     *   }
     * }
     * }</pre>
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<UploadResultDTO> upload(
            @RestForm("customerId") UUID customerId,
            @RestForm("file") FileUpload file) {

        if (customerId == null) {
            throw new BusinessException(400, "customerId 不能为空");
        }
        if (file == null) {
            throw new BusinessException(400, "file 不能为空");
        }

        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        String fileName = file.fileName() != null ? file.fileName() : "unknown.xlsx";

        try (InputStream is = Files.newInputStream(file.uploadedFile())) {
            UploadResultDTO result = importSessionService.upload(is, customerId, userId, fileName);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(400, "上传解析失败: " + e.getMessage());
        }
    }

    /**
     * Step 2 UpdateDecisions：批量 upsert 用户的 BUMP/NO_BUMP/... 决策（幂等，debounce 调用）。
     *
     * <p>Request body (application/json):
     * <pre>{@code
     * {
     *   "decisions": [
     *     {
     *       "decisionType": "PART_VERSION",
     *       "decisionKey": "CPN-001|HF-A001",
     *       "decisionValueJson": "{\"action\":\"BUMP\",\"currentVersion\":2000,\"suggestedVersion\":2001}"
     *     }
     *   ]
     * }
     * }</pre>
     *
     * <p>Response 200: ApiResponse code=200 data=null
     */
    @PUT
    @Path("/{id}/decisions")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> updateDecisions(
            @PathParam("id") UUID sessionId,
            DecisionUpdateRequest req) {

        if (req == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        importSessionService.updateDecisions(sessionId, req);
        return ApiResponse.success(null);
    }

    /**
     * Step 3 Commit：按当前决策合并 staging → mat_*，创建报价单，返回新报价单 ID。
     *
     * <p>Request body (application/json):
     * <pre>{@code
     * {
     *   "name": "2026-05-12 方案 A 报价单",
     *   "customerTemplateId": "uuid-or-null",
     *   "costingTemplateId": "uuid-or-null"
     * }
     * }</pre>
     *
     * <p>Response 200:
     * <pre>{@code
     * {
     *   "code": 200,
     *   "data": {
     *     "quotationId": "3fa85f64-...",
     *     "sessionId": "3fa85f64-..."
     *   }
     * }
     * }</pre>
     */
    @POST
    @Path("/{id}/commit")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CommitResult> commit(
            @PathParam("id") UUID sessionId,
            CommitRequest req) {

        if (req == null || req.name == null || req.name.isBlank()) {
            throw new BusinessException(400, "报价单名称（name）不能为空");
        }

        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        CommitResult result = importSessionService.commit(sessionId, req, userId);
        return ApiResponse.success(result);
    }

    /**
     * Cancel：取消/放弃当前 session，staging 数据通过 CASCADE DELETE 清除。
     *
     * <p>Response 200: ApiResponse code=200 data=null
     */
    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> cancel(@PathParam("id") UUID sessionId) {
        importSessionService.cancel(sessionId);
        return ApiResponse.success(null);
    }
}
