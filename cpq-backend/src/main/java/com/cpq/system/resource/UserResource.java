package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.system.dto.CreateUserRequest;
import com.cpq.system.dto.UpdateUserRequest;
import com.cpq.system.dto.UserDTO;
import com.cpq.system.dto.UserImportReportDTO;
import com.cpq.system.service.UserExportImportService;
import com.cpq.system.service.UserService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class UserResource {

    @Inject
    UserService userService;

    @Inject
    UserExportImportService exportImportService;

    @GET
    public ApiResponse<PageResult<UserDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("role") String role,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(userService.list(page, size, role, status, keyword));
    }

    /**
     * GET /users/export — 导出用户 xlsx（task-260902 · B-3，api.md B-3）。
     *
     * <p>三个筛选参数与 {@code GET /users} 同名同义（复用 {@code UserService.buildFilter}），
     * 但<b>不受分页限制</b> ⇒ 导出的是筛选结果全量。
     * 前 6 列＝导入模板列（可回导），后 2 列（状态 / 创建时间）只读。
     *
     * <p>🚫 不含 id、不含任何密码字段。类级已限 {@code SYSTEM_ADMIN}，方法级再标一次做显式兜底。
     */
    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response export(@QueryParam("keyword") String keyword,
                           @QueryParam("role") String role,
                           @QueryParam("status") String status) {
        byte[] xlsx = exportImportService.export(keyword, role, status);
        return Response.ok(xlsx)
            .header("Content-Disposition", "attachment; filename=\"users.xlsx\"")
            .build();
    }

    /** GET /users/import/template — 下载用户导入模板 xlsx（task-260902 · B-4）。 */
    @GET
    @Path("/import/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response downloadImportTemplate() {
        byte[] xlsx = exportImportService.generateTemplate();
        return Response.ok(xlsx)
            .header("Content-Disposition", "attachment; filename=\"user_import_template.xlsx\"")
            .build();
    }

    /**
     * POST /users/import — 上传 xlsx 批量导入用户（task-260902 · B-5，api.md B-5）。
     *
     * <p><b>只新增，不修改，不删除</b>；部分成功、不整单回滚。
     * 400 仅用于「文件本身不可用」（非 xlsx / 前 6 列表头不符）；
     * 只有表头、0 行数据走 200 + 全 0 报告。
     *
     * <p>⚠️ 类级 {@code @Consumes(APPLICATION_JSON)} 在这里必须被 multipart 覆盖。
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<UserImportReportDTO> importUsers(@RestForm("file") FileUpload file) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            bytes = in.readAllBytes();   // 请求线程读完（上传临时文件请求结束后可能被回收）
        } catch (Exception e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
        return ApiResponse.success(exportImportService.importUsers(bytes));
    }

    @POST
    public ApiResponse<UserDTO> create(@Valid CreateUserRequest request) {
        return ApiResponse.success(userService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<UserDTO> update(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.update(id, request));
    }

    @PATCH
    @Path("/{id}")
    public ApiResponse<UserDTO> updateStatus(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.updateStatus(id, request.status));
    }

    @POST
    @Path("/{id}/reset-password")
    public ApiResponse<Map<String, String>> resetPassword(@PathParam("id") UUID id) {
        UserDTO dto = userService.resetPassword(id);
        return ApiResponse.success(Map.of("initialPassword", dto.initialPassword));
    }
}
