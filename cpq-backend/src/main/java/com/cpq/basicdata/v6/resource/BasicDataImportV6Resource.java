package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.pricing.PricingImportService;
import com.cpq.basicdata.v6.quote.QuoteImportService;
import com.cpq.basicdata.v6.service.CreateQuotationMaterializer;
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
    @Inject V6QuotationCommitService commitService;
    @Inject CreateQuotationMaterializer materializer;
    @Inject SessionHelper sessionHelper;
    @Inject org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

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
     * V6 commit Step 2：导入完成后建报价单（不填 LineItem，由编辑页 autoPopulate 自动生成）。
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
            // createQuotation @Transactional 已提交 → 明细行对新事务可见。后置物化 REQUIRES_NEW 必须在此之后。
            materializer.materialize(r);
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
