package com.cpq.configure.resource;

import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.BindPartsRequest;
import com.cpq.configure.dto.BindingSuggestionDTO;
import com.cpq.configure.dto.ConfirmBindingsRequest;
import com.cpq.configure.dto.MaterialImportReportDTO;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipePartDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.service.MaterialRecipeImportService;
import com.cpq.configure.service.MaterialRecipeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/material-recipes")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class MaterialRecipeResource {

    @Inject
    MaterialRecipeService service;

    @Inject
    MaterialRecipeImportService importService;

    /**
     * POST /material-recipes/import — 上传 xlsx 导入材质库（task-0708 · B5）。
     * 只读 材质编号 + 材质对应元素 两 sheet；脏数据走 200 + 报告，不报 400。
     */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public MaterialImportReportDTO importLibrary(@RestForm("file") FileUpload file) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            bytes = in.readAllBytes();   // 请求线程读完（上传临时文件请求结束后可能被回收）
        } catch (Exception e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
        return importService.importLibrary(bytes);
    }

    /** GET /material-recipes/import/template — 下载干净两 sheet 导入模板（task-0708 · B5）。 */
    @GET
    @Path("/import/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response downloadTemplate() {
        byte[] xlsx = importService.generateTemplate();
        return Response.ok(xlsx)
            .header("Content-Disposition", "attachment; filename=\"material_library_template.xlsx\"")
            .build();
    }

    /**
     * GET /material-recipes?keyword=&withCount= — 管理端列表（task-0708 · B3）。
     * 全状态 + 关键字搜索（材质编号/材质名称/元素符号/元素中文名）+ 排序（启用优先→改时倒序→建时倒序）。
     * withCount=true 时每条 DTO 带 boundPartsCount（本期前端不展示，保留兼容，可不传）。
     */
    @GET
    public List<MaterialRecipeDTO> list(
            @QueryParam("keyword") String keyword,
            @QueryParam("withCount") @DefaultValue("false") boolean withCount) {
        return service.list(keyword, withCount);
    }

    @GET
    @Path("/{id}")
    public MaterialRecipeDTO detail(@PathParam("id") UUID id) {
        return service.getDetail(id);
    }

    // ── 材质-料号 绑定关系管理(Phase 1 新增)──

    /** GET /material-recipes/{id}/parts — 该材质下的料号分页列表 */
    @GET
    @Path("/{id}/parts")
    public PageResult<MaterialRecipePartDTO> listParts(
            @PathParam("id") UUID id,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.listParts(id, keyword, page, size);
    }

    /** POST /material-recipes/{id}/bind-parts — 批量绑定料号到本材质 */
    @POST
    @Path("/{id}/bind-parts")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Integer> bindParts(@PathParam("id") UUID id, BindPartsRequest req) {
        int updated = service.bindParts(id, req == null ? null : req.partNos);
        return Map.of("updated", updated);
    }

    /**
     * POST /material-recipes/{id}/unbind-parts — 批量解绑(置 material_recipe_id=NULL).
     * id 占位仅做 URL 风格一致,实际不校验 partNos 是否绑过该材质.
     */
    @POST
    @Path("/{id}/unbind-parts")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Integer> unbindParts(@PathParam("id") UUID id, BindPartsRequest req) {
        int updated = service.unbindParts(req == null ? null : req.partNos);
        return Map.of("updated", updated);
    }

    /**
     * GET /material-recipes/search-parts?q=...&onlyUnbound=true&size=50 —
     * 供「材质管理 → +绑定料号」子 Drawer 搜 mat_part.
     */
    @GET
    @Path("/search-parts")
    public List<MaterialRecipePartDTO> searchParts(
            @QueryParam("q") String q,
            @QueryParam("onlyUnbound") @DefaultValue("false") boolean onlyUnbound,
            @QueryParam("size") @DefaultValue("50") int size) {
        return service.searchPartsForBinding(q, onlyUnbound, size);
    }

    // ── 智能推断(Phase 3 新增)──

    /**
     * GET /material-recipes/suggest-bindings — 扫所有未绑材质料号给出绑定建议.
     * 详见 {@link MaterialRecipeService#suggestBindings()}.
     */
    @GET
    @Path("/suggest-bindings")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public List<BindingSuggestionDTO> suggestBindings() {
        return service.suggestBindings();
    }

    /**
     * POST /material-recipes/confirm-bindings — 批量执行人工确认的绑定决策.
     */
    @POST
    @Path("/confirm-bindings")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Integer> confirmBindings(ConfirmBindingsRequest req) {
        int updated = service.confirmBindings(req);
        return Map.of("updated", updated);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public MaterialRecipeDTO create(MaterialRecipeUpsertRequest req) {
        return service.create(req);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public MaterialRecipeDTO update(@PathParam("id") UUID id, MaterialRecipeUpsertRequest req) {
        return service.update(id, req);
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response delete(@PathParam("id") UUID id) {
        service.deleteSoft(id);
        return Response.noContent().build();
    }
}
