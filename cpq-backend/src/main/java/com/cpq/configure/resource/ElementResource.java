package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.ElementDTO;
import com.cpq.configure.dto.ElementImportReportDTO;
import com.cpq.configure.dto.ElementUpsertRequest;
import com.cpq.configure.service.ElementService;
import com.cpq.configure.service.ElementBulkService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * 元素主表管理端点（task-0709 / BL-0040）。契约见 api.md。
 * 读=多角色；写(create/update/delete)=SYSTEM_ADMIN。路径主键用 element_no（不可改业务主键）。
 */
@Path("/api/cpq/elements")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ElementResource {

    @Inject
    ElementService service;

    @Inject
    ElementBulkService bulkService;

    @GET
    @Path("/import/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response downloadImportTemplate() {
        return Response.ok(bulkService.generateTemplate())
            .header("Content-Disposition", "attachment; filename=element_template.xlsx").build();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ElementImportReportDTO importElements(@org.jboss.resteasy.reactive.RestForm("file")
                                                 org.jboss.resteasy.reactive.multipart.FileUpload file) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.uploadedFile())) {
            return bulkService.importElements(in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response exportElements() {
        return Response.ok(bulkService.exportElements())
            .header("Content-Disposition", "attachment; filename=elements.xlsx").build();
    }

    /** GET /elements?keyword= — 列表（搜索 element_no/符号/中文 + 被引用数 + 排序）。 */
    @GET
    public List<ElementDTO> list(@QueryParam("keyword") String keyword) {
        return service.list(keyword);
    }

    /** POST /elements — 新建（elementNo/elementCode 唯一）。 */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ElementDTO create(ElementUpsertRequest req) {
        return service.create(req);
    }

    /** PUT /elements/{elementNo} — 编辑（符号被引用即锁；elementNo 不可改）。 */
    @PUT
    @Path("/{elementNo}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ElementDTO update(@PathParam("elementNo") String elementNo, ElementUpsertRequest req) {
        return service.update(elementNo, req);
    }

    /** DELETE /elements/{elementNo} — 停用（软删，204，幂等）。 */
    @DELETE
    @Path("/{elementNo}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response delete(@PathParam("elementNo") String elementNo) {
        service.softDelete(elementNo);
        return Response.noContent().build();
    }
}
