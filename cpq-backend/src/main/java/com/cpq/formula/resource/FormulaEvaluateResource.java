package com.cpq.formula.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.dto.BatchEvaluateRequest;
import com.cpq.formula.dto.BatchEvaluateResponse;
import com.cpq.formula.dto.EvaluateRequest;
import com.cpq.formula.dto.EvaluateResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/**
 * 公式求值 REST 端点 — 包装 FormulaEngine,让前端在以下场景能调用:
 * <ul>
 *   <li>组件管理:校验/试算 BNF 路径公式语法(留 customerId/partNo 空,只测试解析)</li>
 *   <li>报价单运行时:含 BNF 路径的公式实时求值(传 customerId + partNo + bindings)</li>
 *   <li>Excel 视图渲染（Phase 1）:传 costingTemplateId + quotationId + quotationStatus</li>
 * </ul>
 *
 * <p>路由:
 * <ul>
 *   <li>POST /api/cpq/formulas/evaluate — 单条求值（含进程级缓存）</li>
 *   <li>POST /api/cpq/formulas/batch-evaluate — 批量求值，上限 5000，复用单条缓存</li>
 * </ul>
 *
 * <p>缓存策略（{@link FormulaEvalCache}）：
 * <ul>
 *   <li>key: expression:customerId:partNo:costingTemplateId（null 用 "_" 占位）</li>
 *   <li>仅在 bindings 和 driverRow 均为空时走缓存（含动态行数据的请求 key 不稳定）</li>
 *   <li>仅缓存 success=true 的响应（错误不固化）</li>
 *   <li>TTL 30s，maximumSize 10000</li>
 * </ul>
 *
 * <p>Phase 2（V249）：若请求含 {@code templateId} 非空（或向后兼容的 {@code costingTemplateId} 非空），
 * 在求值前设置 {@link SqlViewRuntimeContext} ownerType=TEMPLATE，保证 {@code $view.col} 路径
 * 查 template_sql_view 表（V249 起，原 costing_template_sql_view 已废弃）。finally 块恢复旧值。
 */
@Path("/api/cpq/formulas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class FormulaEvaluateResource {

    private static final Logger LOG = Logger.getLogger(FormulaEvaluateResource.class);
    private static final int BATCH_MAX = 5000;

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    // -------------------------------------------------------------------------
    // 判断请求是否可走缓存
    // -------------------------------------------------------------------------

    /**
     * bindings 和 driverRow 均为空时，请求结果仅由 expression + customerId + partNo + templateId
     * 决定，可安全缓存。
     */
    private static boolean isCacheable(EvaluateRequest req) {
        boolean emptyBindings = req.bindings == null || req.bindings.isEmpty();
        boolean emptyDriverRow = req.driverRow == null || req.driverRow.isEmpty();
        return emptyBindings && emptyDriverRow;
    }

    /**
     * 解析有效 templateId：优先取 req.templateId，若为 null 则 fallback 到 req.costingTemplateId（向后兼容）。
     */
    private static UUID resolveTemplateId(EvaluateRequest req) {
        if (req == null) return null;
        return req.templateId != null ? req.templateId : req.costingTemplateId;
    }

    // -------------------------------------------------------------------------
    // POST /evaluate — 单条求值（含缓存 + Phase 1 ThreadLocal 包裹）
    // -------------------------------------------------------------------------

    @POST
    @Path("/evaluate")
    public ApiResponse<EvaluateResponse> evaluate(EvaluateRequest req,
                                                   @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest httpReq) {
        if (req == null || req.expression == null || req.expression.isBlank()) {
            return ApiResponse.success(EvaluateResponse.error("PARSE_ERROR", "expression 不能为空"));
        }
        if (httpReq != null) {
            String referer = httpReq.getHeader("Referer");
            String ua = httpReq.getHeader("User-Agent");
            UUID effectiveTemplateId = resolveTemplateId(req);
            LOG.infof("[single-evaluate-http] referer=%s ua-len=%d expr=%s partNo=%s customerId=%s templateId=%s bindings-keys=%s driverRow-keys=%s",
                    referer,
                    ua == null ? 0 : ua.length(),
                    req.expression, req.partNo, req.customerId, effectiveTemplateId,
                    req.bindings == null ? "null" : req.bindings.keySet(),
                    req.driverRow == null ? "null" : req.driverRow.keySet());
        }

        // ── Phase 2 ThreadLocal 包裹（模板 SQL 视图上下文，V249 改名）──────────────────────
        SqlViewRuntimeContext.Snapshot prevCtx = null;
        boolean needsRestore = false;
        UUID effectiveTemplateId = resolveTemplateId(req);
        try {
            if (effectiveTemplateId != null) {
                prevCtx = SqlViewRuntimeContext.setNestedTemplate(
                        effectiveTemplateId, req.quotationId, req.quotationStatus);
                needsRestore = true;
            }

            // 缓存命中检查（仅对无动态行数据的请求）
            if (isCacheable(req)) {
                String key = FormulaEvalCache.buildKey(req.expression, req.customerId, req.partNo,
                        effectiveTemplateId);
                EvaluateResponse cached = FormulaEvalCache.getIfPresent(key);
                if (cached != null) {
                    return ApiResponse.success(cached);
                }

                EvaluateResponse resp = doEvaluate(req);
                if (resp.success) {
                    FormulaEvalCache.put(key, resp);
                }
                return ApiResponse.success(resp);
            }


            // 含 bindings / driverRow — 绕过缓存
            return ApiResponse.success(doEvaluate(req));

        } finally {
            if (needsRestore) {
                SqlViewRuntimeContext.restore(prevCtx);
            }
        }
    }

    // -------------------------------------------------------------------------
    // POST /batch-evaluate — 批量求值（每个 task 独立 set/restore ThreadLocal）
    // -------------------------------------------------------------------------

    @POST
    @Path("/batch-evaluate")
    public ApiResponse<BatchEvaluateResponse> batchEvaluate(BatchEvaluateRequest req) {
        if (req == null || req.tasks == null) {
            BatchEvaluateResponse empty = new BatchEvaluateResponse();
            empty.results = Collections.emptyList();
            return ApiResponse.success(empty);
        }
        if (req.tasks.size() > BATCH_MAX) {
            throw new BusinessException(400, "batch tasks 上限 " + BATCH_MAX + ", 当前 " + req.tasks.size());
        }

        BatchEvaluateResponse batchResp = new BatchEvaluateResponse();
        batchResp.results = new ArrayList<>(req.tasks.size());

        for (int idx = 0; idx < req.tasks.size(); idx++) {
            EvaluateRequest t = req.tasks.get(idx);
            BatchEvaluateResponse.Result r = new BatchEvaluateResponse.Result();
            // key 格式与缓存 key 一致（含 templateId 维度；向后兼容 costingTemplateId）
            UUID batchTemplateId = resolveTemplateId(t);
            r.key = (t != null && t.expression != null ? t.expression : "_")
                    + ":" + (t != null && t.customerId != null ? t.customerId.toString() : "_")
                    + ":" + (t != null && t.partNo != null && !t.partNo.isBlank() ? t.partNo : "_")
                    + ":" + (batchTemplateId != null ? batchTemplateId.toString() : "_");
            try {
                // 每个 task 单独 set/restore ThreadLocal（不同 task 的 costingTemplateId 应一致但防御性处理）
                ApiResponse<EvaluateResponse> single = evaluate(t, null);
                r.data = single.getData();
                r.status = "OK";
            } catch (Exception e) {
                r.status = "ERROR";
                r.error = e.getMessage();
            }
            batchResp.results.add(r);
        }

        return ApiResponse.success(batchResp);
    }

    // -------------------------------------------------------------------------
    // 内部求值（不含缓存，供 evaluate() 在 cache miss 时调用）
    // -------------------------------------------------------------------------

    private EvaluateResponse doEvaluate(EvaluateRequest req) {
        try {
            EvaluationContext.Builder builder = EvaluationContext.builder()
                    .dataLoader(dataLoader);
            if (req.customerId != null) builder.customerId(req.customerId);
            if (req.partNo != null && !req.partNo.isBlank()) builder.partNo(req.partNo);
            if (req.bindings != null) builder.bindings(req.bindings);
            if (req.driverRow != null && !req.driverRow.isEmpty()) builder.driverRow(req.driverRow);

            Object result = formulaEngine.evaluate(req.expression, builder.build());

            if (result instanceof FormulaError fe) {
                return EvaluateResponse.error("EVAL_ERROR", fe.toString());
            }
            return EvaluateResponse.ok(result);
        } catch (Exception e) {
            LOG.warnf("Formula evaluate error: expr=%s, err=%s", req.expression, e.getMessage());
            return EvaluateResponse.error("PARSE_ERROR", e.getMessage());
        }
    }
}
