package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.quote.QuoteImportService;
import com.cpq.basicdata.v6.service.CreateQuotationMaterializer;
import com.cpq.basicdata.v6.service.MaterializeExecutor;
import com.cpq.basicdata.v6.service.V6QuotationCommitService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.customer.entity.Customer;
import com.cpq.importexcel.entity.ImportRecord;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * V6 基础数据导入端点。
 *
 * <p>路由：
 * <ul>
 *   <li>🚫 POST /api/cpq/basic-data-import/v6/quote — <b>已停用，恒返 410</b>
 *       （task-260907 · B-10；替代者 {@code POST /api/cpq/dataset/quote/quotation-import}）</li>
 *   <li>🚫 POST /api/cpq/basic-data-import/v6/quote/create-quotation — <b>已停用，恒返 410</b>
 *       （替代者 {@code POST /api/cpq/dataset/quote/create-quotation}）</li>
 *   <li>✅ GET  /api/cpq/basic-data-import/v6/{recordId} — 查询历史导入结果，<b>保留</b>
 *       （报价与核价共用的轮询端点，且【导入历史】页要读它）</li>
 * </ul>
 *
 * <p>⚠️ <b>本类现在只剩一个活端点</b>，但 {@code QuoteImportService} / 17 个 {@code Q*Handler}
 * 等实现类<b>全部保留</b>（N-7 / D-13）——停的是 HTTP 入口，不是代码。
 * </ul>
 */
@Path("/api/cpq/basic-data-import/v6")
@Produces(MediaType.APPLICATION_JSON)
public class BasicDataImportV6Resource {

    // ⚠️ task-260907 · B-10 之后，下面 4 个注入点（quoteService / commitService / materializer /
    //    managedExecutor / materializeExecutor）已【无调用方】—— 两个 POST 端点改成恒返 410 了。
    //    🚫 刻意保留、不删：
    //      ① N-7 / D-13 明确「只摘 HTTP 入口，不删 Service / Handler」，保留注入点让这条决定
    //         在代码里可见，而不是只写在任务文档里；
    //      ② 本类正被另一条任务线（task-260907-移除料号核价功能）并发编辑过，
    //         把改动面压到最小可以避开无谓的合并冲突。
    //    📌 它们都是 @ApplicationScoped 且无 @Startup —— 不被调用就不会实例化，保留零运行时代价。
    @Inject QuoteImportService quoteService;
    @Inject V6QuotationCommitService commitService;
    @Inject CreateQuotationMaterializer materializer;
    @Inject SessionHelper sessionHelper;
    @Inject org.eclipse.microprofile.context.ManagedExecutor managedExecutor;
    // repair-260829 B-2（方案丙）：cleared(ThreadContext.CDI) 的专用 executor，
    // 避免 fire-and-forget 场景下误判「传播进来的（已销毁的）request context 已激活」。
    // ⚠️ task-260907 B-10 更正：本注释原写「专供 :177 的 materializer.materialize(bg) 派发使用」，
    //    那两个派发点随 B-10（两个 POST 改返 410）一起消失了，:177 这个行号也早已不存在
    //    —— 留着会把读者指到一段不存在的代码上。同款用法现在活在
    //    com.cpq.dataset.quotation.QuotationImportResource（新链路的建单物化派发）。
    //    本字段在本类已无调用方，保留理由见上方注释块。
    @Inject @MaterializeExecutor org.eclipse.microprofile.context.ManagedExecutor materializeExecutor;

    @Context HttpServerRequest httpRequest;

    /**
     * 🚫 <b>已停用（task-260907 · B-10 / S-6 / AC-14）—— 恒返 410 Gone。</b>
     *
     * <p>报价基础数据导入已整体迁移到新链路
     * {@code POST /api/cpq/dataset/quote/quotation-import}（写 {@code ds_quote_*} 16 张新表）。
     * 本端点原先写 V6 表，是<b>报价侧最后一个仍在写 V6 的入口</b>；它不停，V6 就永远下不了线。
     *
     * <p>🚫 <b>只摘 HTTP 入口，不删任何 Service / Handler</b>（N-7 / D-13）：
     * {@link QuoteImportService} 与 17 个 {@code Q*Handler} 代码原样保留，只是没有调用方了。
     * 删它们会连带打断 {@code QuoteImportValidator} 等一批仍被引用的类，且存量数据的排查手段也没了。
     *
     * <p>⚠️ <b>为什么是 410 而不是 404</b>：404 的语义是「从来没有过」，会让老客户端以为是拼错了路径
     * 而反复重试；410 的语义是「曾经有，已永久移除」，配上 body 里的迁移指引，
     * 调用方一眼就知道该改调哪个端点。
     *
     * <p>📌 {@code GET /{recordId}} <b>不停</b> —— 它是报价与核价<b>共用</b>的轮询端点，
     * 且【导入历史】页要读它（api.md §4）。
     */
    @POST
    @Path("/quote")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public Response importQuote() {
        return goneResponse();
    }

    /**
     * 🚫 <b>已停用（task-260907 · B-10 / S-6 / AC-14）—— 恒返 410 Gone。</b>
     *
     * <p>替代者：{@code POST /api/cpq/dataset/quote/create-quotation}
     * （由 {@code ds_quote_*} 的导入批次建单，明细行候选按 {@code metadata.batchParts} 精确框定）。
     *
     * <p>🚨 AC-14 的断言是「返回 410，<b>且不建单</b>，{@code quotation} 行数不变」——
     * 所以本方法体<b>必须一行业务逻辑都不留</b>：不校验参数、不查库、不调 commitService。
     * 🚫 不要写成「先校验再返 410」，那样参数合法时仍可能走进建单路径。
     */
    @POST
    @Path("/quote/create-quotation")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public Response createQuotation() {
        return goneResponse();
    }

    /**
     * B-10 的统一 410 响应体。用 {@link ApiResponse} 包装以保持与全站错误体同形
     * （{@code code} / {@code message}），前端既有错误处理不需要为它特判。
     */
    private static Response goneResponse() {
        return Response.status(Response.Status.GONE)
                .entity(ApiResponse.error(410, "报价基础数据导入已迁移至『导入报价数据』"))
                .build();
    }

    @GET
    @Path("/{recordId}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Object>> getResult(@PathParam("recordId") UUID recordId) {
        ImportRecord rec = ImportRecord.findById(recordId);
        if (rec == null) throw new BusinessException(404, "导入记录不存在: " + recordId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importRecordId", rec.id);
        out.put("systemType", rec.systemType);
        out.put("status", rec.importStatus);
        out.put("totalRows", rec.totalRows);
        out.put("successRows", rec.successRows);
        out.put("failedRows", rec.unmatchedRows);
        out.put("originalFileName", rec.originalFileName);
        out.put("createdAt", rec.createdAt);
        out.put("metadata", rec.metadata);
        return ApiResponse.success(out);
    }
}
