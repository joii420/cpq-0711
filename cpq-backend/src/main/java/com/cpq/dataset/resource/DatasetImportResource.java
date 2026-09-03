package com.cpq.dataset.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.dataset.dto.DatasetImportErrorsDTO;
import com.cpq.dataset.dto.DatasetImportResultDTO;
import com.cpq.dataset.importer.DatasetImportService;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.registry.DatasetRegistries;
import com.cpq.dataset.registry.DatasetRegistry;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 数据集导入端点（task-260902 · B-8 · api.md §1）。
 *
 * <pre>
 * POST /api/cpq/dataset/{dataset}/import    dataset ∈ quote | cost-basic | cost-detail
 * </pre>
 *
 * <p>三个数据集<b>共用同一实现</b>，靠 Registry 分流；非法 {@code dataset} 值返回 404。
 * 报价单管理的「导入报价数据」按钮调 {@code dataset=quote}，无独立端点（api.md §9）。
 *
 * <p>🚨 <b>刻意独立成一个 Resource 类</b>：维护端读写端点（B-9~B-11）由后端 #3 写在
 * {@code DatasetResource} 里。两人分文件，避免同文件并发编辑冲突。
 * 两个类的 {@code @Path} 前缀相同但子路径不重叠，JAX-RS 允许。
 *
 * <p>🚫 现有 {@code /api/cpq/basic-data-import/v6/*} 与 {@code /api/cpq/pricing-basic-data/*}
 * <b>一个字节都没改</b>（AC-43 / D-13）。
 */
@Path("/api/cpq/dataset")
@Produces(MediaType.APPLICATION_JSON)
public class DatasetImportResource {

    @Inject DatasetImportService importService;
    @Inject DatasetRegistries registries;
    @Inject SessionHelper sessionHelper;

    @Context HttpServerRequest httpRequest;

    /**
     * Excel 导入。
     *
     * <p>返回类型是 {@link Response} 而不是 {@code ApiResponse}：校验失败要返回 400 <b>并带上
     * 逐条错误清单</b>，而项目现有的 {@code GlobalExceptionMapper} 只认它自己登记过的异常子类。
     * 就地组装 Response 可以完全不碰那个现有文件（D-13）。
     */
    @POST
    @Path("/{dataset}/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response importExcel(@PathParam("dataset") String dataset,
                                @RestForm("file") FileUpload file) {
        DatasetRegistry reg = registries.byKey(dataset);
        if (reg == null) throw new BusinessException(404, "数据集不存在: " + dataset);
        if (file == null) throw new BusinessException(400, "file 不能为空");

        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        if (userId == null) throw new BusinessException(401, "未登录");
        String operator = String.valueOf(userId);

        final byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            // 必须在请求线程读完：上传临时文件在请求结束后可能被回收（与既有 V6 导入同一约定）
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(500, "读取上传文件失败: " + e.getMessage());
        }

        try {
            DatasetImportResultDTO result =
                    importService.importExcel(reg, file.fileName(), bytes, userId, operator);
            return Response.ok(ApiResponse.success(result)).build();
        } catch (DatasetValidationException ve) {
            // Phase 1 拒收：一行未写，返回全部错误（AC-6/7/8/9/10/34/40/45/46）
            return Response.status(400)
                    .entity(ApiResponse.error(400, ve.getMessage(), new DatasetImportErrorsDTO(ve.getErrors())))
                    .build();
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            // Phase 2 异常：事务已整体回滚（api.md §1）
            throw new BusinessException(500, "写入失败，已回滚：" + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
