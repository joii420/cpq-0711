package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * 报价单选配产品的 REST 入口.
 *
 * <p>POST /api/cpq/configure-product/lookup-fingerprint — P2→P3 之间实时查指纹
 * <p>POST /api/cpq/configure-product/quotations/{quotationId} — P5 确认时一锅端
 *
 * <p>2026-05-18 hotfix: 类级 @Path 从 "/api/cpq/quotations" 改成 "/api/cpq/configure-product",
 *   避免和 QuotationResource (同父级路径) 的 `/{id}` / `/{id}/<literal>` 系列路由产生
 *   RestEasy 路径匹配冲突 → `/configure/lookup-fingerprint` 路由识别 404.
 *   独立父级路径后两条端点都能正常路由.
 */
@Path("/api/cpq/configure-product")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigureProductResource {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(ConfigureProductResource.class);

    // D-40：选配建单末尾补写 _record（用户 2026-09-07 改裁「本期补写入」）
    @jakarta.inject.Inject
    com.cpq.quotation.service.dsrecord.DsQuoteRecordService dsQuoteRecordService;
    @jakarta.inject.Inject
    com.cpq.quotation.service.dsrecord.DsRecordStaleService dsRecordStaleService;

    @Inject
    ConfigureProductService service;

    @Inject
    com.cpq.configure.service.ConfigureSnapshotService snapshotService;

    @Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;

    @Inject
    SecurityIdentity identity;

    @POST
    @Path("/lookup-fingerprint")
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        return service.lookupFingerprint(req);
    }

    @POST
    @Path("/quotations/{quotationId}")
    public ConfigureProductResponse configureProduct(@PathParam("quotationId") UUID quotationId,
                                                     ConfigureProductRequest req) {
        UUID operatorId = currentUserId();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId);
        // 加产品整份快照 Phase 1(加性、降级):configure 已提交,此处补整行快照,失败不影响响应。
        try {
            snapshotService.snapshotLines(quotationId, resp.lineItems);
        } catch (Exception ignore) {
            // 快照为尽力而为,异常已在 service 内降级;此处再兜底,绝不影响加产品。
        }
        // 报价单整份快照 Phase 1: 固定 4 份结构 + 算行级 4 份值
        try {
            cardSnapshotService.ensureStructure(quotationId);
            // resp.lineItems 中的 "id" 即本次新增/更新的行，直接触发值快照
            if (resp.lineItems != null) {
                for (java.util.Map<String, Object> liMap : resp.lineItems) {
                    UUID lineItemId = asUuid(liMap.get("id"));
                    if (lineItemId != null) {
                        com.cpq.quotation.entity.QuotationLineItem lineItem =
                            com.cpq.quotation.entity.QuotationLineItem.findById(lineItemId);
                        if (lineItem != null) cardSnapshotService.snapshotLineValues(lineItem);
                    }
                }
            }
        } catch (Exception e) {
            // 尽力而为，不影响加产品
        }
        // ── D-40：选配建单末尾补写 _record（AC-2④ v3 · 用户 2026-09-07 改裁）───────────────
        // 🔑 挂点必须在**上面两段物化之后**：此刻 snapshot_rows 已落库（实测选配单 14/14 行
        //    snapshot_rows + row_data + snapshot_at 三者皆有）⇒ 投影走路径① DRIVER，
        //    anchorValues 非空、第 1 趟内容对位即命中，不依赖 D-38 的 DRIVER_NOT_MATERIALIZED 兜底。
        // 🚫 N+1：整条流程只调一次（🚫 不许放进上面那个 per-line 循环）。
        // 🔒 走 syncRecordsForFlow 而不是直调 syncRecords：Resource 层**没有事务**，
        //    而此刻 service 的写入**已提交** ⇒ 由它开一个事务；直调会因无事务可用而抛。
        //    （导入侧相反 —— 那边行还没提交，必须加入外层事务，见 DsQuoteRecordService 注释。）
        // 🛡️ 失败不阻断加产品（与上面两段同款降级）。
        try {
            dsQuoteRecordService.syncRecordsForFlow(quotationId);
        } catch (RuntimeException ex) {
            dsRecordStaleService.markStale(quotationId,
                    com.cpq.quotation.service.dsrecord.DsRecordStaleService.REASON_WRITE_FAILED,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
        }
        return resp;
    }

    /**
     * 从基础刷新(加产品整份快照 Phase 3):重跑该报价单各行快照(从当前基础数据重新冻结
     * snapshot_rows),<b>保留用户编辑层 row_data</b>(writeSnapshot 为 UPSERT)。
     * 用于"基础数据更新后,用户主动把报价单刷新到最新基础值"。
     */
    @POST
    @Path("/quotations/{quotationId}/refresh-snapshot")
    public ConfigureProductResponse refreshSnapshot(@PathParam("quotationId") UUID quotationId) {
        // 🚨 repair-260908 B-8（AC-18）：开启「editRows 行键失配」收集窗口。
        //    刷新是**唯一**会大规模重算行键的用户动作，也正是缺陷① 修复后 #N 消歧后缀消失、
        //    旧 editRows 集体对不上的那一刻。不在这里报出来，用户的手工编辑就静默没了。
        //    try/finally 保证异常路径也清 ThreadLocal（否则会污染同线程的下一个请求）。
        com.cpq.quotation.rowkey.EditRowMismatchContext.begin();
        java.util.Map<String, Object> mismatch;
        try {
            snapshotService.snapshotQuotation(quotationId);
            // 核价 BOM 递归展开（P1）：刷新时一并重算核价卡片 → 存量核价单刷出整棵 BOM 树（仅 COSTING，不碰报价侧）
            try {
                cardSnapshotService.refreshCostingCardValues(quotationId);
            } catch (Exception ignore) {
                // 尽力而为，不影响刷新主流程
            }
        } finally {
            mismatch = com.cpq.quotation.rowkey.EditRowMismatchContext.drainAll();
        }

        ConfigureProductResponse resp = new ConfigureProductResponse();
        resp.lineItems = java.util.List.of();
        resp.reusedHfPartNos = java.util.List.of();
        int mismatchCount = ((Number) mismatch.getOrDefault("count", 0)).intValue();
        resp.editRowMismatchCount = mismatchCount;
        resp.editRowMismatchTruncated = Boolean.TRUE.equals(mismatch.get("truncated"));
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        java.util.List<com.cpq.quotation.rowkey.EditRowMismatchContext.Mismatch> raw =
                (java.util.List<com.cpq.quotation.rowkey.EditRowMismatchContext.Mismatch>) mismatch.get("items");
        if (raw != null) {
            for (com.cpq.quotation.rowkey.EditRowMismatchContext.Mismatch m : raw) {
                java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("componentId", m.componentId);
                one.put("rowKey", m.rowKey);
                one.put("values", m.values);
                items.add(one);
            }
        }
        resp.editRowMismatches = items;
        if (mismatchCount > 0) {
            // 🔑 端点侧再记一条：服务端日志里必须能按报价单号定位到「这一单刷新时丢了多少条编辑」，
            //    而 CardSnapshotService 那条日志只带 componentId、没有 quotationId。
            LOG.warnf("[refresh-snapshot] quotationId=%s 本次刷新有 %d 条 editRows 行键失配（用户编辑值被丢弃），"
                    + "明细已随响应返回%s", quotationId, mismatchCount,
                    Boolean.TRUE.equals(resp.editRowMismatchTruncated) ? "（明细已截断）" : "");
        }
        return resp;
    }

    /** 从 SecurityIdentity 取当前用户 id; 容错 — 取不到时返 null. */
    UUID currentUserId() {
        try {
            if (identity == null || identity.isAnonymous()) return null;
            String s = identity.getPrincipal().getName();
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
