package com.cpq.modelconfig.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.modelconfig.dto.ModelConfigDTO;
import com.cpq.modelconfig.service.ModelConfigService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * 3D 模型配置端点（task-0712 B5，新表 model_config）。api.md §4。
 *
 * <p>维护动作（列表/上传/版本/设为当前/删除）限配置中心角色；{@code current} + 文件回源
 * 额外放开 SALES_REP（选配/已有产品添加抽屉运行时调用，D15）。
 */
@Path("/api/cpq/model-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"PRICING_MANAGER", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class ModelConfigResource {

    @Inject
    ModelConfigService service;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<PageResult<ModelConfigDTO>> list(
            @QueryParam("subjectType") String subjectType,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return ApiResponse.success(service.list(subjectType, keyword, page, size));
    }

    @GET
    @Path("/versions")
    public ApiResponse<List<ModelConfigDTO>> versions(
            @QueryParam("subjectType") String subjectType,
            @QueryParam("subjectKey") String subjectKey) {
        return ApiResponse.success(service.versions(subjectType, subjectKey));
    }

    @GET
    @Path("/current")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ModelConfigDTO> current(
            @QueryParam("subjectType") String subjectType,
            @QueryParam("subjectKey") String subjectKey) {
        return ApiResponse.success(service.current(subjectType, subjectKey));
    }

    /** 文件回源（本地磁盘存储，见 ModelFileStorageService 类注释）。 */
    @GET
    @Path("/files/{fileId}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response downloadFile(@PathParam("fileId") UUID fileId) {
        ModelConfigService.FileDownload fd = service.resolveDownload(fileId);
        StreamingOutput stream = out -> Files.copy(fd.path(), out);
        return Response.ok(stream).type(fd.contentType()).build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ApiResponse<ModelConfigDTO> upload(
            @RestForm("subjectType") String subjectType,
            @RestForm("subjectKey") String subjectKey,
            @RestForm("label") String label,
            @RestForm("glbFile") FileUpload glbFile,
            @RestForm("thumbnailFile") FileUpload thumbnailFile,
            @RestForm("setCurrent") String setCurrentStr,
            @Context HttpServerRequest httpRequest) {
        boolean setCurrent = Boolean.parseBoolean(setCurrentStr);
        UUID uploadedBy = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(
                service.upload(subjectType, subjectKey, label, glbFile, thumbnailFile, setCurrent, uploadedBy));
    }

    @PUT
    @Path("/{id}/set-current")
    public ApiResponse<ModelConfigDTO> setCurrent(@PathParam("id") UUID id) {
        return ApiResponse.success(service.setCurrent(id));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
