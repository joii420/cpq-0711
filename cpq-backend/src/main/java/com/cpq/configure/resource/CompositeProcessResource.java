package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.CompositeProcessCandidateDTO;
import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.dto.CompositeProcessUpsertRequest;
import com.cpq.configure.service.CompositeProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * 组合工艺端点。
 *
 * <p><b>B6（架构决策 2-2A 定稿，task-0712）职责拆分</b>：
 * <ul>
 *   <li>{@link #list()} —— 选配候选源，{@code GET /api/cpq/composite-processes}
 *       改读工序库 {@code process_master}(ASSEMBLY)，标识锚点 = {@code process_no}。</li>
 *   <li>{@link #getById}/{@link #create}/{@link #update}/{@link #delete} —— 仍管理
 *       {@code composite_process_def}（表保留给 v0.4 configurator，选配侧不再读它作候选，
 *       当前无前端管理页引用，保留 CRUD 供后续 v0.4/管理端复用）。</li>
 * </ul>
 */
@Path("/api/cpq/composite-processes")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CompositeProcessResource {

    @Inject
    CompositeProcessService service;

    /** 选配候选：{@code process_master WHERE process_category='ASSEMBLY'}（B6，见类注释）。 */
    @GET
    public List<CompositeProcessCandidateDTO> list() {
        return service.listAssemblyCandidates();
    }

    @GET
    @Path("/{id}")
    public CompositeProcessDefDTO getById(@PathParam("id") UUID id) {
        return service.getById(id);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public CompositeProcessDefDTO create(CompositeProcessUpsertRequest req) {
        return service.create(req);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SYSTEM_ADMIN"})
    public CompositeProcessDefDTO update(@PathParam("id") UUID id, CompositeProcessUpsertRequest req) {
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
