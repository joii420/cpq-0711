package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.basicdata.v6.dto.ProcessMasterImportReportDTO;
import com.cpq.basicdata.v6.dto.ProcessMasterUpsertRequest;
import com.cpq.basicdata.v6.service.ProcessMasterImportService;
import com.cpq.basicdata.v6.service.ProcessMasterReadService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * V6 工序主数据查询端点（只读）+ 批量导入端点（task-0712 · childtask-1 · B1）。
 *
 * <p>路由：{@code GET /api/cpq/v6/process-master}
 */
@Path("/api/cpq/v6/process-master")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProcessMasterResource {

    @Inject
    ProcessMasterReadService service;

    @Inject
    ProcessMasterImportService importService;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    /**
     * 分页查询工序主数据列表，支持 processNo / processName 模糊搜索 + 排序 + 过滤。
     *
     * <p>task-0728 · api.md A2：{@code sortBy} / {@code sortOrder} / {@code isOutsource} /
     * {@code processCategory} 四个参数全部可选，不传时行为与改造前一致。非法 sortBy 不报错，
     * 由 service 回退默认序（api.md §6），故此处不做校验、直接透传。
     *
     * @param page            页码，从 0 开始（默认 0）
     * @param size            每页条数（默认 20，最大 200）
     * @param keyword         关键字，可为空
     * @param sortBy          排序字段（白名单外忽略）
     * @param sortOrder       asc / desc
     * @param isOutsource     true=外协、false=自制、不传=全部
     * @param processCategory 工序分类精确匹配，取值来自 {@code GET /categories}
     */
    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<ProcessMasterDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortOrder") @DefaultValue("asc") String sortOrder,
            @QueryParam("isOutsource") Boolean isOutsource,
            @QueryParam("processCategory") String processCategory) {
        return ApiResponse.success(
            service.list(page, size, keyword, sortBy, sortOrder, isOutsource, processCategory));
    }

    /**
     * GET /v6/process-master/categories — 工序分类去重列表（task-0728 · api.md A3）。
     *
     * <p>供「工序分类」过滤下拉取选项：当前页只有 20 行，前端自行去重会漏掉其它页的分类。
     * 空表返回 {@code []}（不是 null）。
     */
    @GET
    @Path("/categories")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<String>> categories() {
        return ApiResponse.success(service.listCategories());
    }

    /**
     * POST /v6/process-master/import — 上传 xlsx 批量导入工序主数据（upsert 覆盖，task-0712 · childtask-1 · B1）。
     * 首个 sheet（或名为「工序」的 sheet），首行表头按中文列名读；脏数据不报 400，走 200 + 报告逐条列明原因。
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<ProcessMasterImportReportDTO> importProcesses(@RestForm("file") FileUpload file) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(importService.importProcesses(bytes, userId));
    }

    /** GET /v6/process-master/import/template — 下载干净导入模板 xlsx（登录即可，同 list() 权限口径）。 */
    @GET
    @Path("/import/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response downloadTemplate() {
        byte[] xlsx = importService.generateTemplate();
        return Response.ok(xlsx)
            .header("Content-Disposition", "attachment; filename=\"process_master_template.xlsx\"")
            .build();
    }

    /** 新建工序主数据。 */
    @POST
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProcessMasterDTO> create(ProcessMasterUpsertRequest req) {
        return ApiResponse.success(service.create(req));
    }

    /** 编辑工序主数据(processNo 业务键锁定, 不可改)。 */
    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProcessMasterDTO> update(@PathParam("id") UUID id, ProcessMasterUpsertRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    /** 硬删除工序主数据。 */
    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<UUID> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success(id);
    }
}
