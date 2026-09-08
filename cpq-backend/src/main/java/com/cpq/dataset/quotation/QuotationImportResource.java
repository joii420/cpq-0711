package com.cpq.dataset.quotation;

import com.cpq.basicdata.v6.service.CreateQuotationMaterializer;
import com.cpq.basicdata.v6.service.MaterializeExecutor;
import com.cpq.basicdata.v6.service.V6QuotationCommitService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.customer.entity.Customer;
import com.cpq.importexcel.entity.ImportRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-260907 · B-1 / B-5 / B-6 / B-8：报价数据<b>导入建单</b>专用端点（api.md §1 / §2 / §3）。
 *
 * <pre>
 * POST /api/cpq/dataset/quote/quotation-import              选客户 + 上传（异步，立即返 PROCESSING）
 * GET  /api/cpq/dataset/quote/quotation-import/{recordId}   轮询进度 / 结果 / 错误清单
 * POST /api/cpq/dataset/quote/create-quotation              由已成功的批次建报价单
 * </pre>
 *
 * <h3>🚫 刻意<b>不</b>写进 {@code DatasetImportResource}</h3>
 * 那个类承载 {@code POST /dataset/{dataset}/import} ——【基础资料维护】页签与本任务
 * <b>共用</b>同一个端点（{@code DatasetPartListTab.tsx:185}），给它加必选 {@code customerId}
 * 会直接打断维护页签（N-10）。⇒ 另开端点、另开文件，那个类<b>一个字节没改</b>（AC-15）。
 *
 * <h3>@Path 前缀为什么与 {@code DatasetImportResource} 相同</h3>
 * 三个 Resource 类（本类 / {@code DatasetImportResource} / {@code DatasetMaintenanceResource}）
 * 共用 {@code @Path("/api/cpq/dataset")} 前缀，子路径不重叠。
 * 🚫 <b>不要图省事把本类的 {@code @Path} 写成 {@code "/api/cpq/dataset/quote"}</b> ——
 * JAX-RS 的资源类匹配是「先选出最匹配的<b>类</b>，再在该类内找方法」，一个更长的字面前缀会把
 * {@code /api/cpq/dataset/quote/import}（维护页签在用）一并吸进本类，然后因为本类没有对应方法
 * 而 404。与既有两个类保持同一前缀是本仓库已验证的写法。
 */
@Path("/api/cpq/dataset")
@Produces(MediaType.APPLICATION_JSON)
public class QuotationImportResource {

    private static final Logger LOG = Logger.getLogger(QuotationImportResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject QuotationImportService importService;
    @Inject QuotationImportRecordWriter recordWriter;
    @Inject DatasetQuotationCommitService commitService;
    @Inject CreateQuotationMaterializer materializer;
    @Inject SessionHelper sessionHelper;
    /**
     * 🚨 <b>导入派发必须用这个 cleared(ThreadContext.CDI) executor，不是全局默认
     * {@code ManagedExecutor}</b>。开发期实测：用全局默认 executor 时本链路 100% 失败于
     * <b>Phase 1 的第一条主数据 SELECT</b>（「neither a transaction nor a CDI request context
     * is active」）—— 因为 Phase 1 是刻意事务外的（AC-4 的结构保证），没有事务可以替它兜底。
     * 完整判据与「为什么 V6 侧同款写法没炸」见 {@link QuotationImportExecutor} 的 javadoc。
     */
    @Inject @QuotationImportExecutor ManagedExecutor importExecutor;
    /**
     * 🚨 <b>物化派发必须用这个 cleared(ThreadContext.CDI) executor，不是全局默认 {@code ManagedExecutor}</b>
     * （{@code repair-260829 B-2} 实证：默认 executor 在 fire-and-forget 下把即将销毁的 request
     * context 传播进后台线程 → {@code @ActivateRequestContext} 误判已激活而不新建 → 下游
     * {@code EntityManager} 不可用 → 卡片值静默写 0 行。背靠背对照 40/40 vs 3/40）。
     */
    @Inject @MaterializeExecutor ManagedExecutor materializeExecutor;

    @Context HttpServerRequest httpRequest;

    // ==================================================================
    // §1 导入（异步）
    // ==================================================================

    /**
     * B-1：{@code POST /api/cpq/dataset/quote/quotation-import}。
     *
     * <p>同步段只做两件事：建 {@code import_record}（PROCESSING）+ <b>在请求线程内</b>把上传文件
     * 读进 {@code byte[]}。
     * ⚠️ 读文件必须在请求线程完成 —— 上传临时文件在请求结束后可能被回收
     * （{@code BasicDataImportV6Resource:88} 同一约定）。
     *
     * <p>校验顺序与 V6 侧逐行对齐：customerId → file → 客户存在 → 客户有 code → 登录态。
     */
    @POST
    @jakarta.ws.rs.Path("/quote/quotation-import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationImportDTOs.StartResult> startImport(
            @RestForm("customerId") UUID customerId,
            @RestForm("file") FileUpload file) {

        if (customerId == null) throw new BusinessException(400, "customerId 不能为空");
        if (file == null) throw new BusinessException(400, "file 不能为空");

        Customer customer = Customer.findById(customerId);
        if (customer == null) throw new BusinessException(404, "客户不存在: " + customerId);
        String customerNo = customer.code;
        if (customerNo == null || customerNo.isBlank()) {
            throw new BusinessException(400, "客户未配置 code（业务编号），无法作为 customer_no");
        }

        UUID importedBy = sessionHelper.getCurrentUserId(httpRequest);
        if (importedBy == null) throw new BusinessException(401, "未登录");

        UUID recordId = recordWriter.createProcessing(customerId, file.fileName(), importedBy);

        final byte[] bytes;
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            bytes = in.readAllBytes();   // 必须在请求线程读完
        } catch (Exception e) {
            recordWriter.finalizeFailed(recordId, 0, "读取上传文件失败: " + e.getMessage(), List.of());
            throw new BusinessException(500, "读取上传文件失败: " + e.getMessage());
        }

        final String fileName = file.fileName();
        importExecutor.runAsync(() ->
                importService.processImport(recordId, customerNo, fileName, bytes, importedBy));

        LOG.infof("[quotation-import] 受理 record=%s customer=%s file=%s bytes=%d",
                recordId, customerNo, fileName, bytes.length);
        return ApiResponse.success(new QuotationImportDTOs.StartResult(
                recordId, QuotationImportRecordWriter.SYSTEM_TYPE, "PROCESSING"));
    }

    // ==================================================================
    // §2 轮询
    // ==================================================================

    /** B-5：{@code GET /api/cpq/dataset/quote/quotation-import/{recordId}}。记录不存在返 404。 */
    @GET
    @jakarta.ws.rs.Path("/quote/quotation-import/{recordId}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationImportDTOs.StatusResult> getImportStatus(
            @PathParam("recordId") UUID recordId) {

        ImportRecord rec = ImportRecord.findById(recordId);
        if (rec == null) throw new BusinessException(404, "导入记录不存在: " + recordId);

        QuotationImportDTOs.StatusResult out = new QuotationImportDTOs.StatusResult();
        out.importRecordId = rec.id;
        out.systemType = rec.systemType;
        out.status = rec.importStatus;
        out.originalFileName = rec.originalFileName;
        out.totalRows = rec.totalRows;
        out.successRows = rec.successRows;
        out.failedRows = rec.unmatchedRows;
        out.createdAt = rec.createdAt;
        out.quotationId = rec.quotationId;

        // metadata → api.md §2 的 progress / summary / errors。
        // 解析失败不抛 500：轮询端点必须永远可用，否则前端进度条会因为一个格式问题整个卡死。
        try {
            if (rec.metadata != null && !rec.metadata.isBlank()) {
                JsonNode meta = MAPPER.readTree(rec.metadata);
                JsonNode progress = meta.get("progress");
                if (progress != null && !progress.isNull()) {
                    out.progress = MAPPER.treeToValue(progress, QuotationImportDTOs.ProgressView.class);
                }
                // 🚨 summary / errors 一律<b>直接读 JsonNode</b>，🚫 不反序列化回
                // DatasetSheetSummaryDTO / DsValidationError。
                // 实测教训（2026-09-07，本任务开发期）：DsValidationError 只有一个三参构造、
                // 没有无参构造 ⇒ Jackson 反序列化抛
                // 「no Creators, like default constructor, exist」，被本方法的兜底 catch 吞掉，
                // 症状是 failedRows=59 而 errors 整个字段消失（AC-4「逐条列出问题行」直接失效，
                // 且不报错）。给那个共用 DTO 加无参构造会波及维护页签，⇒ 改成读 JsonNode。
                out.summary = readSummary(meta.get("summary"));
                out.errors = readErrors(meta.get("errors"));
                JsonNode message = meta.get("message");
                if (message != null && message.isTextual()) out.message = message.asText();
            }
        } catch (Exception e) {
            LOG.warnf("[quotation-import] metadata 解析失败 record=%s: %s", recordId, e.getMessage());
        }
        return ApiResponse.success(out);
    }

    // ==================================================================
    // §3 建单
    // ==================================================================

    /**
     * B-6 / B-8：{@code POST /api/cpq/dataset/quote/create-quotation}。
     *
     * <p><b>同步段</b>（{@code commitService.createQuotation}，一个事务）= 建单 + 建行；
     * <b>异步段</b> = 卡片值物化，转后台。响应恒带 {@code materializing=true}，
     * 前端据此去轮询既有 {@code POST /quotations/{id}/ensure-card-values}
     * —— 🚫 不许靠 {@code cardValuesReady==false} 去猜（D-5 教训：区分不了「真失败」和「还没开始算」）。
     *
     * <p>🚨 <b>不把响应对象本身交给后台任务持有</b>：它马上要被框架序列化进本次 HTTP 响应，
     * 而 {@code materialize()} 会写它的 {@code cardValuesReady / costingTreeRows / warnings}
     * 三个字段 —— 并发读写轻则响应体不确定，重则序列化期撞
     * {@code ConcurrentModificationException}。后台任务另建一份局部对象（V6 侧 B-18 的做法）。
     */
    @POST
    @jakarta.ws.rs.Path("/quote/create-quotation")
    @Consumes(MediaType.APPLICATION_JSON)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationImportDTOs.CreateQuotationResult> createQuotation(
            QuotationImportDTOs.CreateQuotationRequest req) {

        if (req == null || req.importRecordId == null || req.customerId == null
                || req.name == null || req.name.isBlank()) {
            throw new BusinessException(400, "importRecordId / customerId / name 不能为空");
        }
        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        if (userId == null) throw new BusinessException(401, "未登录");

        DatasetQuotationCommitService.Commit commit;
        try {
            commit = commitService.createQuotation(req, userId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            // 事务已整体回滚（api.md §3：500 建单失败）
            throw new BusinessException(500, "创建报价单失败: " + rootMessage(e));
        }

        QuotationImportDTOs.CreateQuotationResult out = new QuotationImportDTOs.CreateQuotationResult(
                commit.quotationId(), commit.quotationNumber(),
                commit.importRecordId(), commit.lineItemsCount());
        out.reentered = commit.reentered();
        out.materializing = true;

        // 建单事务已提交 → 明细行对新事务可见，后置物化必须在此之后派发。
        V6QuotationCommitService.CommitResult bg = new V6QuotationCommitService.CommitResult(
                commit.quotationId(), commit.importRecordId(), 0);
        bg.lineItemsCount = commit.lineItemsCount();
        materializeExecutor.runAsync(() -> materializer.materialize(bg));

        return ApiResponse.success(out);
    }

    /**
     * {@code metadata.summary} → api.md §2 的 {@code summary[]}。
     * 存的是 {@code DatasetSheetSummaryDTO} 的序列化形状（{@code sheet/versioned/…}），
     * 这里翻译成对外的 {@code sheetName/kind/…}。纯内存，无查库。
     */
    private static List<QuotationImportDTOs.SheetSummaryView> readSummary(JsonNode arr) {
        if (arr == null || !arr.isArray()) return null;
        List<QuotationImportDTOs.SheetSummaryView> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            QuotationImportDTOs.SheetSummaryView v = new QuotationImportDTOs.SheetSummaryView();
            v.sheetName = text(n, "sheet");
            boolean versioned = n.path("versioned").asBoolean(false);
            v.kind = versioned ? "VERSIONED" : "PLAIN";
            if (versioned) {
                v.axisCount = intOrNull(n, "axisCount");
                v.created   = intOrNull(n, "created");
                v.upgraded  = intOrNull(n, "upgraded");
                v.unchanged = intOrNull(n, "unchanged");
            } else {
                v.inserted = intOrNull(n, "inserted");
                v.updated  = intOrNull(n, "updated");
            }
            out.add(v);
        }
        return out;
    }

    /**
     * {@code metadata.errors} → api.md §2 的 {@code errors[]}（{@code sheet/row/column} →
     * {@code sheetName/rowNum/columnLabel}）。<b>全部</b>错误，不截断（AC-4）。
     */
    private static List<QuotationImportDTOs.ValidationErrorView> readErrors(JsonNode arr) {
        if (arr == null || !arr.isArray()) return null;
        List<QuotationImportDTOs.ValidationErrorView> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            QuotationImportDTOs.ValidationErrorView v = new QuotationImportDTOs.ValidationErrorView();
            v.sheetName = text(n, "sheet");
            v.rowNum = n.path("row").asInt(0);
            v.columnLabel = text(n, "column");
            v.value = text(n, "value");     // B-16：出错单元格原始值；无对应单元格时为 null
            v.reason = text(n, "reason");
            out.add(v);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
