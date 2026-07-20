package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.ComponentDirectoryDTO;
import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.CreateComponentDirectoryRequest;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.dto.ImportPreviewResult;
import com.cpq.component.service.ComponentDirectoryService;
import com.cpq.component.service.ComponentExportService;
import com.cpq.component.service.ComponentImportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/component-directories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class ComponentDirectoryResource {

    @Inject
    ComponentDirectoryService directoryService;

    @Inject
    ComponentExportService exportService;

    @Inject
    ComponentImportService importService;

    /**
     * P1 导出: 把该目录**直属**组件(含 fields/formulas/data_driver_path/component_sql_view + 依赖清单)
     * 打包为 JSON bundle 下载。纯只读。
     */
    @GET
    @Path("/{id}/export")
    public Response export(@PathParam("id") UUID id) {
        ComponentExportBundle bundle = exportService.exportDirectory(id);
        String filename = "components-" + id + ".json";
        return Response.ok(bundle)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * P2 导入预览(dry-run,不写库): 校验依赖存在性 + 按冲突策略生成每组件动作计划。
     * body = 导出的 bundle JSON。提交(dryRun=false)为 P3,尚未实现。
     */
    @POST
    @Path("/{id}/import")
    public ApiResponse<ImportPreviewResult> importBundle(
            @PathParam("id") UUID id,
            @QueryParam("conflictPolicy") @DefaultValue("RENAME") String conflictPolicy,
            ComponentExportBundle bundle) {
        // 该端点恒为预览(dry-run, 不写库);实际写入走 /{id}/import/commit
        return ApiResponse.success(importService.preview(id, bundle, conflictPolicy));
    }

    /**
     * P3 导入提交: 单事务按计划 INSERT 新组件 + 其 component_sql_view(全新 UUID),
     * 不动任何现有数据、不绑定模板。依赖缺失默认阻止(ignoreMissingDeps=true 显式忽略)。
     */
    @POST
    @Path("/{id}/import/commit")
    public ApiResponse<ImportCommitResult> importCommit(
            @PathParam("id") UUID id,
            @QueryParam("conflictPolicy") @DefaultValue("RENAME") String conflictPolicy,
            @QueryParam("ignoreMissingDeps") @DefaultValue("false") boolean ignoreMissingDeps,
            ComponentExportBundle bundle) {
        return ApiResponse.success(importService.commit(id, bundle, conflictPolicy, ignoreMissingDeps));
    }

    @GET
    public ApiResponse<List<ComponentDirectoryDTO>> tree(
            @QueryParam("keyword") String keyword,
            @QueryParam("includeDisabled") @DefaultValue("false") boolean includeDisabled) {
        return ApiResponse.success(directoryService.listTree(keyword, includeDisabled));
    }

    @POST
    public ApiResponse<ComponentDirectoryDTO> create(CreateComponentDirectoryRequest request) {
        return ApiResponse.success(directoryService.create(request.name, request.parentId, request.sortOrder));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ComponentDirectoryDTO> update(
            @PathParam("id") UUID id,
            CreateComponentDirectoryRequest request) {
        return ApiResponse.success(directoryService.update(id, request.name, request.sortOrder));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        directoryService.delete(id);
        return ApiResponse.success();
    }
}
