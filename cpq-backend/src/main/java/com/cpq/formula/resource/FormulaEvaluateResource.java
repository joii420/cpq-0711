package com.cpq.formula.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
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

/**
 * 公式求值 REST 端点 — 包装 FormulaEngine,让前端在以下场景能调用:
 * <ul>
 *   <li>组件管理:校验/试算 BNF 路径公式语法(留 customerId/partNo 空,只测试解析)</li>
 *   <li>报价单运行时:含 BNF 路径的公式实时求值(传 customerId + partNo + bindings)</li>
 * </ul>
 *
 * <p>路由:
 * <ul>
 *   <li>POST /api/cpq/formulas/evaluate — 单条求值（含进程级缓存）</li>
 *   <li>POST /api/cpq/formulas/batch-evaluate — 批量求值，上限 200，复用单条缓存</li>
 * </ul>
 *
 * <p>缓存策略（{@link FormulaEvalCache}）：
 * <ul>
 *   <li>key: expression:customerId:partNo（null 用 "_" 占位）</li>
 *   <li>仅在 bindings 和 driverRow 均为空时走缓存（含动态行数据的请求 key 不稳定）</li>
 *   <li>仅缓存 success=true 的响应（错误不固化）</li>
 *   <li>TTL 30s，maximumSize 10000</li>
 * </ul>
 */
@Path("/api/cpq/formulas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class FormulaEvaluateResource {

    private static final Logger LOG = Logger.getLogger(FormulaEvaluateResource.class);
    // 2026-05-15 从 200 提到 5000 — 与前端 BATCH_EVALUATE_CHUNK 对齐, 让一张报价单
    // 一次 batch-evaluate 搞定 (88 partNo × ~10 path ≈ 700~5000), 不再拆 N 次 HTTP
    private static final int BATCH_MAX = 5000;

    @Inject
    FormulaEngine formulaEngine;

    @Inject
    DataLoader dataLoader;

    // -------------------------------------------------------------------------
    // 判断请求是否可走缓存
    // -------------------------------------------------------------------------

    /**
     * bindings 和 driverRow 均为空时，请求结果仅由 expression + customerId + partNo 决定，可安全缓存。
     */
    private static boolean isCacheable(EvaluateRequest req) {
        boolean emptyBindings = req.bindings == null || req.bindings.isEmpty();
        boolean emptyDriverRow = req.driverRow == null || req.driverRow.isEmpty();
        return emptyBindings && emptyDriverRow;
    }

    // -------------------------------------------------------------------------
    // POST /evaluate — 单条求值（含缓存）
    // -------------------------------------------------------------------------

    @POST
    @Path("/evaluate")
    public ApiResponse<EvaluateResponse> evaluate(EvaluateRequest req,
                                                   @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest httpReq) {
        if (req == null || req.expression == null || req.expression.isBlank()) {
            return ApiResponse.success(EvaluateResponse.error("PARSE_ERROR", "expression 不能为空"));
        }
        // (debug 2026-05-15) 死循环排查 — httpReq 非空说明是真 HTTP 入口（batch 内部调用 evaluate() 不传 httpReq）
        if (httpReq != null) {
            String referer = httpReq.getHeader("Referer");
            String ua = httpReq.getHeader("User-Agent");
            // 把请求 body 完整内容 + 头打印出来,推断前端调用方
            LOG.infof("[single-evaluate-http] referer=%s ua-len=%d expr=%s partNo=%s customerId=%s bindings-keys=%s driverRow-keys=%s",
                    referer,
                    ua == null ? 0 : ua.length(),
                    req.expression, req.partNo, req.customerId,
                    req.bindings == null ? "null" : req.bindings.keySet(),
                    req.driverRow == null ? "null" : req.driverRow.keySet());
        }

        // 缓存命中检查（仅对无动态行数据的请求）
        if (isCacheable(req)) {
            String key = FormulaEvalCache.buildKey(req.expression, req.customerId, req.partNo);
            EvaluateResponse cached = FormulaEvalCache.getIfPresent(key);
            if (cached != null) {
                return ApiResponse.success(cached);
            }

            EvaluateResponse resp = doEvaluate(req);
            // 仅缓存 success=true 的结果，避免临时错误固化
            if (resp.success) {
                FormulaEvalCache.put(key, resp);
            }
            return ApiResponse.success(resp);
        }

        // 含 bindings / driverRow — 绕过缓存
        return ApiResponse.success(doEvaluate(req));
    }

    // -------------------------------------------------------------------------
    // POST /batch-evaluate — 批量求值（复用单条缓存逻辑，顺序执行，独立 try-catch）
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

        for (EvaluateRequest t : req.tasks) {
            BatchEvaluateResponse.Result r = new BatchEvaluateResponse.Result();
            // key 格式与缓存 key 一致
            r.key = (t != null && t.expression != null ? t.expression : "_")
                    + ":" + (t != null && t.customerId != null ? t.customerId.toString() : "_")
                    + ":" + (t != null && t.partNo != null && !t.partNo.isBlank() ? t.partNo : "_");
            try {
                // 通过 evaluate() 复用缓存逻辑（不传 HTTP context，避免 debug 日志混入 batch 内部调用）
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

            // FormulaEngine 用 FormulaError 表示求值失败(不抛异常)
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
