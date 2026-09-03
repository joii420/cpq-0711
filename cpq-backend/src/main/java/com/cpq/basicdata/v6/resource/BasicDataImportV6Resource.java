package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.pricing.PricingImportService;
import com.cpq.basicdata.v6.pricing.PricingTemplateService;
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
 *   <li>POST /api/cpq/basic-data-import/v6/quote — 报价基础数据 19 Sheet（按 customerId 注入 customer_no）</li>
 *   <li>POST /api/cpq/basic-data-import/v6/pricing — 核价基础数据 24 Sheet（customer_no 从 Excel 行读）</li>
 *   <li>GET  /api/cpq/basic-data-import/v6/{recordId} — 查询历史导入结果</li>
 * </ul>
 */
@Path("/api/cpq/basic-data-import/v6")
@Produces(MediaType.APPLICATION_JSON)
public class BasicDataImportV6Resource {

    @Inject QuoteImportService quoteService;
    @Inject PricingImportService pricingService;
    @Inject PricingTemplateService pricingTemplateService;
    @Inject V6QuotationCommitService commitService;
    @Inject CreateQuotationMaterializer materializer;
    @Inject SessionHelper sessionHelper;
    @Inject org.eclipse.microprofile.context.ManagedExecutor managedExecutor;
    // repair-260829 B-2（方案丙）：专供 :177 的 materializer.materialize(bg) 派发使用，
    // cleared(ThreadContext.CDI) 避免 fire-and-forget 场景下误判「传播进来的（已销毁的）
    // request context 已激活」。不影响本类 :87（Step 1 导入）与 priceadjust 6 处注入点
    // 仍使用的全局默认 managedExecutor——两个 executor 各司其职，见 MaterializeExecutor 的 javadoc。
    @Inject @MaterializeExecutor org.eclipse.microprofile.context.ManagedExecutor materializeExecutor;

    @Context HttpServerRequest httpRequest;

    @POST
    @Path("/quote")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResultDTO> importQuote(
            @RestForm("customerId") UUID customerId,
            @RestForm("file") FileUpload file) {

        if (customerId == null) throw new BusinessException(400, "customerId 不能为空");
        if (file == null) throw new BusinessException(400, "file 不能为空");

        Customer customer = Customer.findById(customerId);
        if (customer == null) throw new BusinessException(404, "客户不存在: " + customerId);
        String customerNo = customer.code;
        if (customerNo == null || customerNo.isBlank()) {
            throw new BusinessException(400, "客户未配置 code（业务编号），无法作为 V6 customer_no");
        }

        UUID importedBy = sessionHelper.getCurrentUserId(httpRequest);
        if (importedBy == null) throw new BusinessException(401, "未登录");

        // 异步导入：同步建记录 + 读文件入内存 → 后台线程处理 → 立即返回 PROCESSING。
        // 前端用 GET /v6/{recordId} 轮询，避免大文件导入撞 HTTP/代理超时。
        UUID recordId = quoteService.createImportRecord(customerId, file.fileName(), importedBy);
        final byte[] bytes;
        try (InputStream stream = Files.newInputStream(file.uploadedFile())) {
            bytes = stream.readAllBytes();   // 必须在请求线程读完：上传临时文件请求结束后可能被回收
        } catch (Exception e) {
            throw new BusinessException(500, "读取上传文件失败: " + e.getMessage());
        }
        final String fname = file.fileName();
        managedExecutor.runAsync(() ->
            quoteService.processImport(recordId, customerNo, fname, bytes, importedBy));

        ImportResultDTO pending = new ImportResultDTO();
        pending.importRecordId = recordId;
        pending.systemType = "QUOTE";
        pending.status = "PROCESSING";
        return ApiResponse.success(pending);
    }

    @POST
    @Path("/pricing")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResultDTO> importPricing(@RestForm("file") FileUpload file) {
        if (file == null) throw new BusinessException(400, "file 不能为空");
        UUID importedBy = sessionHelper.getCurrentUserId(httpRequest);
        if (importedBy == null) throw new BusinessException(401, "未登录");
        try (InputStream stream = Files.newInputStream(file.uploadedFile())) {
            ImportResultDTO result = pricingService.importExcel(file.fileName(), stream, importedBy);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "核价基础数据导入失败: " + e.getMessage());
        }
    }

    /**
     * GET /basic-data-import/v6/pricing/template — 下载核价基础数据 24 Sheet 空模板
     * （task-0728 · api.md A4）。
     *
     * <p>权限：登录即可（四角色），与 {@code v6/process-master/import/template}、
     * {@code material-recipes/import/template} 对齐 —— 下载空模板无副作用，故比导入端点
     * （{@code SALES_MANAGER}/{@code SYSTEM_ADMIN}）宽。
     * <p>响应体是裸 xlsx 字节流，不包 {@code ApiResponse}（与另两个模板下载端点同约定）。
     */
    @GET
    @Path("/pricing/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response pricingTemplate() {
        byte[] xlsx = pricingTemplateService.generateTemplate();
        return Response.ok(xlsx)
            .header("Content-Disposition", "attachment; filename=\"pricing_basic_data_template.xlsx\"")
            .build();
    }

    /**
     * V6 commit Step 2：导入完成后建报价单（不填 LineItem，由编辑页 autoPopulate 自动生成）。
     *
     * <p><b>task-260825 B-17（D-5，2026-08-26 用户真机测试后裁决，第三次扩范围）</b>：
     * 本端点原先同步做完"建单+建行+四步物化"全部工作才返回（1845 行实测 132s）——后端数据
     * 是好的，但前端 axios 全局 30s 超时会先把请求 cancel 掉，用户体感是"依然超时失败"。
     * 现拆两段：同步段只做 {@code commitService.createQuotation}（建单+建行，实测很快）并
     * 立即返回；{@code materializer.materialize(...)} 转后台执行（{@link #managedExecutor}，
     * 与既有 Step 1 导入异步化 {@code QuoteImportService#processImport} 同一模式）。
     * 响应体新增 {@code materializing=true}，让前端<b>显式</b>知道要去轮询既有的
     * {@code POST /quotations/{id}/ensure-card-values} 端点，而不是靠
     * {@code cardValuesReady==false} 去猜（那样区分不了"真的算失败了"和"根本还没开始算"）。
     */
    @POST
    @Path("/quote/create-quotation")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<V6QuotationCommitService.CommitResult> createQuotation(
            CreateQuotationFromImportRequest req) {
        if (req == null || req.importRecordId == null || req.customerId == null || req.name == null) {
            throw new BusinessException(400, "importRecordId / customerId / name 不能为空");
        }
        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        if (userId == null) throw new BusinessException(401, "未登录");
        try {
            V6QuotationCommitService.CommitResult r = commitService.createQuotation(req, userId);
            // B-17：同步段到此为止；cardValuesReady/costingTreeRows 维持构造默认值(false/0)，
            // 不代表真实物化结果——materializing=true 是本次响应唯一的、显式的"要去轮询"信号。
            r.materializing = true;
            // B-18：不把 r 本身交给后台任务持有——r 马上要被框架序列化进本次 HTTP 响应，
            // materialize() 内部会写 cardValuesReady/costingTreeRows/warnings 三个字段，
            // 若后台线程与序列化线程并发读写同一个对象存在数据竞争（轻则响应体不确定，
            // 重则序列化期间遇到可变 List 被并发 add 抛 ConcurrentModificationException）。
            // 后台任务另建一份局部 CommitResult，只做后台自身的降级记录（写日志用），
            // 不影响已经交给框架序列化的这一份、也没有第三方读取它。
            V6QuotationCommitService.CommitResult bg = new V6QuotationCommitService.CommitResult(
                r.quotationId, r.importRecordId, r.hfPairsCount);
            bg.lineItemsCount = r.lineItemsCount;
            // createQuotation @Transactional 已提交 → 明细行对新事务可见。后置物化必须在此之后。
            // managedExecutor.runAsync：受管线程池，与本类 :87 既有的 Step 1 导入异步化同一模式，
            // 非本次新发明；materialize() 自身带 @ActivateRequestContext（见其 javadoc），
            // 后台线程可正常使用 request-scoped EntityManager。
            // repair-260829 B-2：改用 materializeExecutor（cleared CDI），不再用全局默认
            // managedExecutor——后者在 fire-and-forget 下会把即将销毁的请求 context 传播进
            // 后台线程，致 @ActivateRequestContext 误判已激活而不新建，下游 SUPPORTS 事务的
            // EntityManager 不可用（见 CreateQuotationMaterializer 与 MaterializeExecutor 的 javadoc）。
            materializeExecutor.runAsync(() -> materializer.materialize(bg));
            return ApiResponse.success(r);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "创建报价单失败: " + e.getMessage());
        }
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
