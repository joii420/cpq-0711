package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.service.MaterialVersionUpgradeService;
import com.cpq.quotation.service.CardSnapshotService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Inject
    MaterialVersionUpgradeService materialVersionUpgradeService;

    @Inject
    com.cpq.priceadjust.service.PriceReconciler priceReconciler;

    @Inject
    com.cpq.priceadjust.service.PriceAdjustNotificationService priceAdjustNotificationService;

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

    /**
     * task-0729 B8.2：《SUBTOTAL 双端对拍清单》一次性生成端点（合并前置，技术总监确认用）。
     * 100% 只读——{@link CardSnapshotService#reconcileQuoteSubtotalsForTask0729B8} 内部只
     * SELECT + 纯内存重算，不写任何字段。逐 line item 返回 quotationNo/materialNo/liSubtotal/
     * computedSubtotal/diff，供人工逐行定性（口径差已修复 / 历史时点漂移 / 其他）。
     *
     * <p>非常驻业务端点，用完可删；先按此临时端点跑出清单，不预先建正式菜单/前端页面。
     */
    @GET
    @Path("/task0729-subtotal-reconcile")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<List<Map<String, Object>>> task0729SubtotalReconcile() {
        LOG.info("[admin] task0729-subtotal-reconcile called (read-only)");
        return ApiResponse.success(cardSnapshotService.reconcileQuoteSubtotalsForTask0729B8());
    }

    /**
     * task-0729 B0：{@code MaterialVersionUpgradeService.upgrade} 验证入口（临时，B0 开发期用）。
     * 目前只实现 S1+S2，恒不写库；{@code dryRun} 透传但当前无实际写库分支可区分。
     */
    @POST
    @Path("/task0729-b0-upgrade-preview")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<UpgradeResult> task0729B0UpgradePreview(
            @QueryParam("lineItemId") String lineItemId,
            @QueryParam("targetVersionId") String targetVersionId,
            @QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        UpgradeResult r = materialVersionUpgradeService.upgrade(
            UUID.fromString(lineItemId), UUID.fromString(targetVersionId), dryRun);
        return ApiResponse.success(r);
    }

    /**
     * task-0729 B10：{@code PriceReconciler} 验证入口（临时，B10 开发期用）。真实执行（非 dryRun，
     * 归位机制本身就是幂等的，不需要 dryRun 语义）；耗时随响应一并返回，用于 E14-7 性能自检。
     */
    @POST
    @Path("/task0729-b10-reconcile-preview")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Object>> task0729B10ReconcilePreview(
            @QueryParam("quotationId") String quotationId) {
        long t0 = System.nanoTime();
        priceReconciler.ensureInitialRevisionPlaceholder(UUID.fromString(quotationId));
        com.cpq.priceadjust.service.PriceReconciler.ReconcileResult r =
            priceReconciler.reconcileQuotation(UUID.fromString(quotationId));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("lineItemsInScope", r.lineItemsInScope);
        out.put("rowsChanged", r.rowsChanged);
        out.put("elapsedMs", ms);
        return ApiResponse.success(out);
    }

    /**
     * task-0729 B11：{@code PriceAdjustNotificationService} 验证入口（临时，B11 开发期用）。
     * 直接对已存在的 {@code material_price_update_job} 触发一次通知（幂等，{@code notified}
     * 已为 true 时会原样跳过——与生产路径 {@code finalizeJob} 调用的是同一方法）。
     */
    @POST
    @Path("/task0729-b11-notify-preview")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<String> task0729B11NotifyPreview(@QueryParam("jobId") String jobId) {
        priceAdjustNotificationService.notifyJobCompletion(UUID.fromString(jobId));
        return ApiResponse.success("done");
    }

    /**
     * task-0729 现场缺口修复验证入口（临时）：只重算传入 lineItem 的核价卡片值
     * （{@code CardSnapshotService#refreshCostingCardValuesForLine}），不触碰报价侧
     * snapshot_rows/row_data，用于验证 V373（wl_ys_bom_view 元素单价 JOIN 归一到根料号）
     * 修复效果，最小侵入。
     */
    @POST
    @Path("/task0729-costing-view-fix-preview")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<String> task0729CostingViewFixPreview(@QueryParam("lineItemId") String lineItemId) {
        cardSnapshotService.refreshCostingCardValuesForLine(UUID.fromString(lineItemId));
        return ApiResponse.success("done");
    }
}
