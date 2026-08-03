package com.cpq.quotation.resource;

import org.jboss.logging.Logger;

import com.cpq.quotation.service.PendingHygieneService;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * BL-0092：孤儿 pending 占号的体检与清理端点（运维用）。
 *
 * <ul>
 *   <li>{@code GET  /api/cpq/admin/pending-hygiene/inspect} —— 只读体检，列出各表孤儿数</li>
 *   <li>{@code POST /api/cpq/admin/pending-hygiene/cleanup?dryRun=true} —— 干跑，看将删什么</li>
 *   <li>{@code POST /api/cpq/admin/pending-hygiene/cleanup?dryRun=false} —— 真删</li>
 * </ul>
 *
 * <p><b>为什么默认 dryRun=true</b>：本端点是 DELETE 语义且影响 9 张基础资料表，
 * 误触代价高。强制调用方显式传 {@code dryRun=false} 才真删，避免手滑。
 *
 * <p>语义与业务背景见 {@link PendingHygieneService} 类注释。
 */
@Path("/api/cpq/admin/pending-hygiene")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PendingHygieneResource {

    private static final Logger LOG = Logger.getLogger(PendingHygieneResource.class);

    @Inject
    PendingHygieneService pendingHygieneService;

    /** 只读体检：各表孤儿 pending 数量 + 未纳管的 pending 表告警。 */
    @GET
    @Path("/inspect")
    public Response inspect() {
        PendingHygieneService.InspectResult r = pendingHygieneService.inspect();
        LOG.infof("[BL-0092] pending 体检：孤儿总数=%d 明细=%s 未纳管表=%s",
                r.totalOrphans(), r.orphanCountByTable(), r.unmanagedTables());
        return Response.ok(r).build();
    }

    /**
     * 清理孤儿 pending 行。
     *
     * @param dryRun 默认 {@code true}（只统计不删）；真删必须显式传 {@code false}
     */
    @POST
    @Path("/cleanup")
    public Response cleanup(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        PendingHygieneService.CleanupResult r = pendingHygieneService.cleanup(dryRun);
        return Response.ok(r).build();
    }
}
