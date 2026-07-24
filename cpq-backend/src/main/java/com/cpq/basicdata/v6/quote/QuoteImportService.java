package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.ExcelParserService;
import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.importexcel.entity.ImportRecord;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报价基础数据导入服务（update-0723 起 17 Sheet，两阶段全量校验 + 单一事务写入）。
 *
 * <p><b>Phase 1</b>：{@link QuoteImportValidator} 一次性解析全 sheet + 跑 B2 类型推断 + 全量校验，
 * <b>零写库</b>，收集全部错误；任一错误 → 整单 {@code FAILED}，不进入 Phase 2。
 * <p><b>Phase 2</b>：全部通过后，{@link #writeAll} 在<b>单一</b> {@code @Transactional(REQUIRES_NEW)}
 * 事务内依次调用全部 SheetHandler（含物料BOM三态合并），handler 方法的事务传播由历史的
 * {@code REQUIRES_NEW}（各自独立提交）改为 {@code MANDATORY}（强制 join 本方法开启的外层事务）：
 * 任一 handler 报错（{@code SheetImportResult.failedRows > 0}）或抛异常，整单一起回滚，
 * 不再产生 {@code PARTIAL} 状态（update-0723 U6/U7）。
 */
@ApplicationScoped
public class QuoteImportService {

    @Inject ExcelParserService parser;
    @Inject EntityManager em;
    @Inject QuoteImportValidator validator;

    /** 16 个 SheetHandler（物料BOM 三态合并 + 15 个费用/关系类 sheet；元素单价 Q01 已随新模板下线 update-0723 B1）。 */
    @Inject MaterialBomMergeHandler bomMerge;    // 物料BOM（三态：材质/零件/外购件，B3）
    @Inject Q18UnitWeightHandler q18;            // 单重
    @Inject Q02CustomerMapHandler q02;           // 客户料号与宏丰料号的关系
    @Inject Q04ElementBomHandler q04;            // 物料与元素BOM
    @Inject Q05ElementRecoveryHandler q05;       // 元素回收折扣 UPDATE
    @Inject Q06FixedProcessFeeHandler q06;       // 来料固定加工费
    @Inject Q07IncomingOtherFeeHandler q07;      // 来料其他费用
    @Inject Q08IncomingAnnualDiscountHandler q08;// 来料年降
    @Inject Q09IncomingRecoveryHandler q09;      // 来料回收折扣
    @Inject Q10SelfProcessFeeHandler q10;        // 自制加工费
    @Inject Q11FinishedOtherFeeHandler q11;      // 成品其他费用
    @Inject Q13ComponentOtherFeeHandler q13;     // 组成件其他费用
    @Inject Q14AssemblyProcessFeeHandler q14;    // 组装加工费 (capacity)
    @Inject Q15AssemblyAnnualDiscountHandler q15;// 组装加工费年降
    @Inject Q16PlatingSchemeHandler q16;         // 电镀方案
    @Inject Q17PlatingCostHandler q17;           // 电镀费用
    @Inject Q19AnnualDiscountHandler q19;        // 年降系数

    /** 写入顺序：物料BOM 三态合并须早于依赖其落库结果的 sheet（如 Q05 更新 element_bom_item 依赖 Q04 先写）。 */
    private List<SheetHandler> orderedHandlers() {
        return List.of(bomMerge, q18, q02, q04, q05,
                       q06, q07, q08, q09, q10, q11, q13, q14, q15, q16, q17, q19);
    }

    /**
     * 后台执行报价基础数据导入（异步）。调用方先 {@link #createImportRecord} 拿到 recordId 并把
     * Excel 读入内存 bytes，再经 ManagedExecutor 调本方法；HTTP 请求立即返回 PROCESSING，前端轮询
     * {@code GET /v6/{recordId}} 查进度。
     *
     * <p>{@code @ActivateRequestContext}：让 request-scoped 的 EntityManager 在后台线程可用。
     * <p>顶层 try/catch：后台线程的任何失败都 finalize 记录为 FAILED（轮询可见），不静默吞没。
     *
     * @param recordId   已建 import_record 主键（status=PROCESSING）
     * @param customerNo 客户编码
     * @param fileName   上传文件名
     * @param bytes      Excel 二进制（请求线程已读入内存，避免上传临时文件被回收）
     * @param importedBy 当前用户 UUID
     */
    @ActivateRequestContext
    public void processImport(UUID recordId, String customerNo, String fileName,
                              byte[] bytes, UUID importedBy) {
        ImportContext ctx = new ImportContext();
        ctx.customerNo = customerNo;
        ctx.systemType = "QUOTE";
        ctx.importedBy = importedBy;
        ctx.importRecordId = recordId;
        // task-0721 B2：报价基础数据导入发生在「创建报价单」之前（此刻无 quotationId），
        // 先用 importRecordId 作为临时 pending 归属 key 落库；createQuotation 时"过户"为真实
        // quotationId（见 V6QuotationCommitService#repointPendingOwnership）。
        ctx.pendingQuotationId = recordId;
        clearPreviousPending(recordId);   // 重导覆盖（task-0721 B2 第 4 点）：先清本单上一次 pending 残留

        ImportResultDTO out = new ImportResultDTO();
        out.importRecordId = recordId;
        out.systemType = "QUOTE";

        final int totalSteps = 2 + orderedHandlers().size();   // 解析 + 校验 + N 个写入 handler
        long importT0 = System.nanoTime();

        Map<String, List<SheetRow>> sheetsByName;
        try (XSSFWorkbook wb = parser.open(new ByteArrayInputStream(bytes))) {
            updateProgress(recordId, 0, totalSteps, "解析中");
            sheetsByName = parseAllSheets(wb);
        } catch (Exception e) {
            // 后台线程：解析失败不抛出（无处可抛），落 FAILED 供前端轮询
            Log.error("Excel 解析失败", e);
            out.status = "FAILED";
            out.sheetResults = List.of();
            finalizeImportRecord(recordId, out, List.of());
            return;
        }

        // ===== Phase 1：全量校验，零写库（update-0723 B7/B8）=====
        updateProgress(recordId, 1, totalSteps, "校验中");
        QuoteImportValidator.Outcome vo = validator.validate(sheetsByName, ctx);
        if (vo.hasErrors()) {
            List<SheetResultDTO> dtos = vo.toDtos();
            out.status = "FAILED";
            out.sheetResults = dtos;
            out.totalSuccessRows = sumSuccess(dtos);
            out.totalFailedRows = sumFailed(dtos);
            Log.debug(String.format("[v6import] QUOTE Phase1 校验未通过 sheets=%d failedRows=%d elapsed=%.0fms",
                dtos.size(), out.totalFailedRows, (System.nanoTime() - importT0) / 1e6));
            finalizeImportRecord(recordId, out, dtos);
            return;
        }
        ctx.sharedCache.put("partTypeIndex", vo.typeIndex);
        ctx.sharedCache.put("selfProcessOperationNo", vo.selfProcessOperationNo);
        // R2（协调方 2026-07-23 补充口径）：全 handler 共享一个 MaterialNoResolver.BatchState，
        // 并用 Phase 1 收集的批量级名称→料号种子预灌，防止同一物理件跨 sheet 被二次发号（重号）。
        com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState sharedBatch =
            new com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState();
        sharedBatch.customerNo = ctx.customerNo;
        sharedBatch.yyMm = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMM"));
        sharedBatch.pendingQuotationId = ctx.pendingQuotationId;
        vo.typeIndex.seedBatchState(sharedBatch);
        ctx.sharedCache.put("materialNoBatchState", sharedBatch);

        // ===== Phase 2：单一事务写入，全部 handler MANDATORY join（update-0723 B7）=====
        List<SheetResultDTO> sheetDtos = new ArrayList<>();
        try {
            writeAll(sheetsByName, ctx, recordId, totalSteps, sheetDtos);
            out.status = "SUCCESS";
        } catch (Exception ex) {
            Log.error("[v6import] QUOTE Phase2 写入失败，整单回滚", ex);
            out.status = "FAILED";
        }
        out.sheetResults = sheetDtos;
        out.totalSuccessRows = sumSuccess(sheetDtos);
        out.totalFailedRows = sumFailed(sheetDtos);
        Log.debugf("[v6import] QUOTE TOTAL elapsed=%.0fms status=%s sheets=%d",
            (System.nanoTime() - importT0) / 1e6, out.status, totalSteps);
        finalizeImportRecord(recordId, out, sheetDtos);
    }

    /**
     * 进度写入节流（2026-07-23 性能验收反馈）：{@link #writeAll} 原每 handler 一次
     * {@link #updateProgress} 独立 REQUIRES_NEW 远程往返（BEGIN+UPDATE+COMMIT 三次网络往返/次），
     * 17 次合计 ~800ms 是黄金样例场景下端到端超 2s（U8）的主因（纯事务开销，不随行数放大）。
     * 优化方向是<b>减少写入次数</b>，不是改事务传播（progress 必须继续独立提交：整单回滚时
     * 进度记录不能跟着消失，前端轮询也需要看到实时进度）。
     *
     * <p>双重判据（任一命中即写）：
     * <ul>
     *   <li><b>固定检查点</b>（主力，确定性，不受运行时抖动影响）：整个 handler 序列按"均匀分桶"
     *       精确切成 {@link #PROGRESS_CHECKPOINT_COUNT} 个检查点（桶号 {@code i*(N-1)/(handlerCount-1)}
     *       较上一步变化才写；首/尾 handler 桶号恒为 0 / N-1，天然落在检查点上，等价"关键节点必写"，
     *       不需要额外特判），黄金样例 17 handler 时精确写 2 次（较原 17 次降约 88%）。</li>
     *   <li><b>静默超时兜底</b>（{@link #PROGRESS_MAX_SILENCE_NANOS}）：单个 handler 处理耗时
     *       异常长（如千行级大文件单 sheet 卡住）时，即使未到检查点也强制写一次，避免进度条
     *       长时间静止误导用户"卡死"。</li>
     * </ul>
     */
    private static final int PROGRESS_CHECKPOINT_COUNT = 2;
    private static final long PROGRESS_MAX_SILENCE_NANOS = 800_000_000L;

    /**
     * Phase 2 单一事务写入体（update-0723 B7）：依次调用 {@link #orderedHandlers()}，任一
     * handler 报错（{@code failedRows > 0}）或抛异常都会向外传播，触发本方法（REQUIRES_NEW）
     * 整体回滚——13+ 个 handler 的写方法必须从 {@code REQUIRES_NEW} 改为 {@code MANDATORY} 才能
     * join 本事务（各自独立提交则无法被"整体回滚"，见需求澄清 U6 技术边界备案）。
     *
     * <p>{@code sheetDtos} 由调用方传入并在此就地累加：即使本方法抛异常导致 DB 事务回滚，
     * 已追加的 Java 对象不受影响，调用方仍可读到"失败前各 sheet 的处理详情"用于 FAILED 展示。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeAll(Map<String, List<SheetRow>> sheetsByName, ImportContext ctx, UUID recordId,
                         int totalSteps, List<SheetResultDTO> sheetDtos) {
        int done = 2;
        List<SheetHandler> handlers = orderedHandlers();
        int handlerCount = handlers.size();
        long lastProgressNanos = System.nanoTime();
        int lastBucket = -1;
        for (int i = 0; i < handlerCount; i++) {
            SheetHandler h = handlers.get(i);
            // 均匀分桶：桶号 0..N-1，i=0 恒落桶 0，i=handlerCount-1 恒落桶 N-1 —— 首尾天然是检查点。
            int bucket = handlerCount <= 1 ? 0
                : (int) ((long) i * (PROGRESS_CHECKPOINT_COUNT - 1) / (handlerCount - 1));
            boolean isCheckpoint = bucket != lastBucket;
            long now = System.nanoTime();
            boolean silenceTimeout = (now - lastProgressNanos) >= PROGRESS_MAX_SILENCE_NANOS;
            if (isCheckpoint || silenceTimeout) {
                updateProgress(recordId, done, totalSteps, h.sheetName());
                lastProgressNanos = now;
                lastBucket = bucket;
            }
            List<SheetRow> rows = sheetsByName.getOrDefault(h.sheetName(), List.of());
            SheetImportResult r;
            try {
                com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().reset();
                long handleT0 = System.nanoTime();
                r = h.handle(rows, ctx);
                double handleMs = (System.nanoTime() - handleT0) / 1e6;
                Log.debugf("[v6import] QUOTE sheet=%s rows=%d handle=%.0fms writer{%s}",
                    h.sheetName(), rows.size(), handleMs,
                    com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().summary());
            } catch (RuntimeException ex) {
                Log.error("Sheet [" + h.sheetName() + "] Phase2 写入异常", ex);
                SheetImportResult err = new SheetImportResult(h.sheetName());
                err.recordError(0, "_sheet_", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                sheetDtos.add(SheetResultDTO.from(err));
                throw ex;   // 必须继续向外抛出以触发整单回滚（B7 §8.2/§8.3），此处仅记录诊断信息。
            }
            sheetDtos.add(SheetResultDTO.from(r));
            done++;
            if (r.failedRows > 0) {
                // Phase 1 理论上已全量拦截；仍出现说明是竞态/DB 层意外（如跨客户串号），
                // 按 B7 §8.3 约定：Phase 2 出现的任何 recordError 级问题都必须整体回滚。
                throw new QuoteImportWriteFailedException(
                    "sheet=[" + h.sheetName() + "] " + r.failedRows + " 处写入失败(兜底整单回滚): "
                        + (r.errors.isEmpty() ? "" : r.errors.get(0).message));
            }
        }
    }

    /** 一次性解析本次导入涉及的全部 sheet（handler 声明的 sheetName 去重后各解析一次），供 Phase 1/2 共用。 */
    private Map<String, List<SheetRow>> parseAllSheets(XSSFWorkbook wb) {
        Map<String, List<SheetRow>> out = new LinkedHashMap<>();
        for (SheetHandler h : orderedHandlers()) {
            String name = h.sheetName();
            if (out.containsKey(name)) continue;
            var sheet = wb.getSheet(name);
            out.put(name, sheet != null ? parser.parseSheet(sheet) : List.of());
        }
        return out;
    }

    private static int sumSuccess(List<SheetResultDTO> dtos) {
        int s = 0; for (SheetResultDTO d : dtos) s += d.successRows; return s;
    }

    private static int sumFailed(List<SheetResultDTO> dtos) {
        int s = 0; for (SheetResultDTO d : dtos) s += d.failedRows; return s;
    }

    /**
     * task-0721 B2：重导覆盖 —— 同一 pending 归属 key（此刻 = importRecordId；createQuotation 后
     * = quotationId，见 backtask B2 第 4 点）再次导入前，先清掉 7 张版本化表 + 占号表里属于它的旧
     * pending 残留行，再走本次 pending 写入。当前 UI 流程每次上传都会铸新 importRecordId（不存在
     * "同一 importRecordId 再导一次"的真实调用路径），此清理对首次导入是 no-op（0 行），为未来
     * 若开放"报价单创建前多次重传同一草稿"预留正确性保障，零风险。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void clearPreviousPending(UUID pendingQuotationId) {
        for (String table : PENDING_TABLES) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE pending_quotation_id = :pq")
              .setParameter("pq", pendingQuotationId).executeUpdate();
        }
    }

    /** 7 张版本化表 + 占号表（task-0721 B1 pending 列覆盖范围，见 V349 迁移）。 */
    private static final List<String> PENDING_TABLES = List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "material_customer_map");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public UUID createImportRecord(UUID customerId, String fileName, UUID importedBy) {
        ImportRecord rec = new ImportRecord();
        // 不手动赋 id；@GeneratedValue 让 Hibernate 自动生成 UUID
        rec.customerId = customerId;
        rec.systemType = "QUOTE";
        rec.originalFileName = fileName == null ? "quote-import.xlsx" : fileName;
        rec.importStatus = "PROCESSING";
        rec.importedBy = importedBy;
        rec.createdAt = OffsetDateTime.now();
        rec.persist();
        rec.flush();
        return rec.id;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void finalizeImportRecord(UUID recordId, ImportResultDTO out, List<SheetResultDTO> sheetDtos) {
        ImportRecord rec = ImportRecord.findById(recordId);
        if (rec == null) return;
        rec.importStatus = out.status;
        rec.successRows = out.totalSuccessRows;
        rec.unmatchedRows = out.totalFailedRows;
        rec.totalRows = out.totalSuccessRows + out.totalFailedRows;
        // metadata 写 sheet_summary JSON
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sheetResults", sheetDtos);
        try {
            rec.metadata = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            rec.metadata = "{}";
        }
        // managed entity 字段改动自动 flush，无需 persist()
    }

    /**
     * 增量写导入进度到 import_record.metadata（{@code {progress:{done,total,current}}}），REQUIRES_NEW 立即
     * 提交，供前端轮询渲染真实进度条；处理结束后由 {@link #finalizeImportRecord} 把 metadata 覆盖为
     * sheetResults。进度写入失败不影响导入本身（吞掉异常）。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateProgress(UUID recordId, int done, int total, String current) {
        try {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("done", done);
            progress.put("total", total);
            progress.put("current", current == null ? "" : current);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("progress", progress);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
            // 单条 native UPDATE（不 findById，省一次远程 SELECT 往返）。metadata 为 jsonb，显式 CAST。
            em.createNativeQuery("UPDATE import_record SET metadata = CAST(:m AS jsonb) WHERE id = :id")
              .setParameter("m", json)
              .setParameter("id", recordId)
              .executeUpdate();
        } catch (Exception e) {
            // 进度写入失败忽略，不影响导入主流程
        }
    }
}
