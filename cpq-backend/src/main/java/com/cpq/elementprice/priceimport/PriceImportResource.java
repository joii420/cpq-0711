package com.cpq.elementprice.priceimport;

import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 价格导入端点（task-0722 · B5，契约见 api.md §2）。
 */
@Path("/api/cpq/element-price")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class PriceImportResource {

    @Inject
    PriceImportService service;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    /** GET /import-template — 下载导入模板（4 列表头 + 示例 + 填写说明）。*/
    @GET
    @Path("/import-template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response downloadTemplate() {
        byte[] xlsx = service.generateTemplate();
        String encoded = URLEncoder.encode("元素价格导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(xlsx)
                .header("Content-Disposition",
                        "attachment; filename=\"element_price_import_template.xlsx\"; filename*=UTF-8''" + encoded)
                .build();
    }

    /** POST /import — 上传 xlsx，逐行独立写入（部分成功，§11.3.2）。*/
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public PriceImportResultDTO importPrices(@RestForm("file") FileUpload file,
                                              @RestForm("sourceId") String sourceIdStr,
                                              @RestForm("priceDate") String priceDateStr) {
        if (file == null) throw new BusinessException(400, "file 不能为空");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(400, "读取上传文件失败: " + e.getMessage());
        }
        UUID sourceId = parseUuid(sourceIdStr);
        LocalDate priceDate = parseDate(priceDateStr);
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.importPrices(bytes, sourceId, priceDate, userId);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) throw new BusinessException(400, "sourceId 不能为空");
        try {
            return UUID.fromString(s.trim());
        } catch (Exception e) {
            throw new BusinessException(400, "sourceId 格式无效: " + s);
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) throw new BusinessException(400, "priceDate 不能为空");
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new BusinessException(400, "priceDate 格式无效，期望 yyyy-MM-dd: " + s);
        }
    }
}
