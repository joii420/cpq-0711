package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.quotation.service.CardSnapshotService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * 报价单运维管理端点（SYSTEM_ADMIN 专用）。
 *
 * <p>当前端点：
 * <ul>
 *   <li>{@code POST /api/cpq/admin/quotations/migrate-freeze-drafts} —— 存量 DRAFT 草稿迁移
 *       （D1，2026-06-18）：清掉草稿卡片值里的 {@code #ERROR[QUERY_ERROR]} 脏值。
 *       默认 {@code dryRun=true}（安全扫描），传 {@code ?dryRun=false} 执行实际重烤。</li>
 * </ul>
 */
@Path("/api/cpq/admin/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuotationAdminResource {

    private static final Logger LOG = Logger.getLogger(QuotationAdminResource.class);

    @Inject
    CardSnapshotService cardSnapshotService;

    /**
     * 存量 DRAFT 草稿迁移：清掉 quote_card_values 里的 #ERROR 脏值（D1）。
     *
     * <p><b>dryRun=true（默认）</b>：只扫描，不改数据。
     * 返回每个 DRAFT 报价单是否含 #ERROR 以及错误行数，status=DRY_RUN。
     *
     * <p><b>dryRun=false</b>：对每个 DRAFT 报价单调 refreshDraftQuoteCards（force=true 重烤）。
     * 重烤后再检查是否仍含 #ERROR，status=OK/STILL_ERROR/FAILED。
     *
     * <p>单单失败不中断整体，失败单 status=FAILED。
     *
     * @param dryRun 默认 true（安全）；false 执行实际重烤
     * @return ApiResponse 包汇总列表，每项含 {quotationId, quoteNo, before,
     *         errorLineCount(dryRun) / refreshedLines+after(非dryRun), status}
     */
    @POST
    @Path("/migrate-freeze-drafts")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<List<Map<String, Object>>> migrateFreezeDrafts(
            @QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        LOG.infof("[admin] migrate-freeze-drafts called dryRun=%b", dryRun);
        List<Map<String, Object>> result = cardSnapshotService.migrateFreezeDrafts(dryRun);
        return ApiResponse.success(result);
    }
}
