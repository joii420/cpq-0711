package com.cpq.dataset.quotation;

import com.cpq.dataset.dto.DatasetSheetSummaryDTO;
import com.cpq.dataset.dto.DsValidationError;
import com.cpq.importexcel.entity.ImportRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-260907 · B-1 / B-2 / B-4：{@code import_record} 的独立事务写入器。
 *
 * <h3>🚨 为什么单独成一个 bean，而不是把这几个方法写进 {@link QuotationImportService}</h3>
 * {@code @Transactional(REQUIRES_NEW)} 由 CDI <b>拦截器</b>实现，只在<b>经代理调用</b>时生效。
 * 若把 {@link #updateProgress} 写在 {@code QuotationImportService} 内部再由该类自己调用，
 * 那是 {@code this.} 自调用 —— 拦截器<b>不会触发</b>，方法会跑在调用方的事务里；
 * 一旦 Phase 2 整单回滚，已写的进度会跟着消失，前端轮询看到的是「进度倒退回空」。
 * （V6 侧 {@code QuoteImportService#writeAll → updateProgress} 正是这个自调用形态，
 * 🚫 本任务不照抄那一处。）
 *
 * <p>拆成独立 bean 后，{@code QuotationImportService} 持有的是 CDI 客户端代理，
 * 每次调用都过拦截器，「进度独立提交」从注释承诺变成结构上的事实。
 *
 * <h3>metadata 结构（本任务自有约定）</h3>
 * <pre>
 * 进行中： {"dataset":"quote","progress":{"done":6,"total":18,"current":"物料与元素BOM"}}
 * 成功：   {"dataset":"quote","durationMs":1234,"summary":[DatasetSheetSummaryDTO…]}
 * 失败：   {"dataset":"quote","message":"…","errors":[DsValidationError…]}
 * </pre>
 * {@code GET …/quotation-import/{recordId}} 负责把它翻译成 api.md §2 的对外形状。
 */
@ApplicationScoped
public class QuotationImportRecordWriter {

    /** {@code import_record.system_type}，等于 {@code DatasetImportService.systemTypeOf(quoteRegistry)}。 */
    public static final String SYSTEM_TYPE = "DATASET_QUOTE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;

    /** B-1 ③：同步段建记录（PROCESSING）。REQUIRES_NEW + flush，保证 HTTP 返回前 id 已落库、可被轮询查到。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public UUID createProcessing(UUID customerId, String fileName, UUID importedBy) {
        ImportRecord rec = new ImportRecord();
        rec.customerId = customerId;
        rec.systemType = SYSTEM_TYPE;
        rec.originalFileName = (fileName == null || fileName.isBlank())
                ? "quotation-import.xlsx" : fileName;
        rec.importStatus = "PROCESSING";
        rec.importedBy = importedBy;
        rec.createdAt = OffsetDateTime.now();
        rec.persist();
        rec.flush();
        return rec.id;
    }

    /**
     * B-4：写进度。单条 native UPDATE（不走 findById，省一次远程 SELECT 往返），
     * jsonb 列显式 CAST。写失败吞掉 —— 进度是观测信息，不许连累导入本身。
     *
     * <p>🚫 <b>本方法不做节流</b>，节流判据在调用方（{@link QuotationImportService}）——
     * 「写不写」是编排层的决定，「怎么写」才是本类的职责。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateProgress(UUID recordId, int done, int total, String current) {
        try {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("done", done);
            progress.put("total", total);
            progress.put("current", current == null ? "" : current);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("dataset", "quote");
            meta.put("progress", progress);
            em.createNativeQuery("UPDATE import_record SET metadata = CAST(:m AS jsonb) WHERE id = :id")
              .setParameter("m", MAPPER.writeValueAsString(meta))
              .setParameter("id", recordId)
              .executeUpdate();
        } catch (Exception ignore) {
            // 进度写入失败不影响导入主流程
        }
    }

    /**
     * B-2 ④：Phase 2 成功 → SUCCESS + 逐 sheet 三态计数 + <b>本批次客户料号清单</b>。
     *
     * <h3>🔄 D-26：{@code batchParts} 为什么必须落库</h3>
     * 建单的明细行候选要按「<b>本次导入批次</b>」框定，而 {@code ds_quote_customer_part}
     * <b>没有任何批次维度列</b>（无 import_record_id、无 pending 列）。
     * <p>只按 {@code customer_no} 查会把该客户的<b>全部历史料号</b>都建成明细行 ——
     * 实证：一份 <b>3 行</b>的 Excel 建出了 <b>15</b> 行（表里另有 12 行历史）。
     * <p>🚫 也不走 V6 的 {@code hfPairs + created_at ±时间窗}：那是<b>近似</b>，
     * 并发导入 / 补导会互相串。这里存的是<b>精确清单</b>，由 Phase 1 已解析的行直接得到。
     *
     * @param batchParts 本批次「客户料号」sheet 的 {@code (customer_no, customer_product_no)} 清单，
     *                   已去重、保持 Excel 顺序。<b>允许为空列表</b>（该 sheet 一行没有，AC-18），
     *                   但 <b>🚫 不允许不写这个键</b> —— 建单侧靠「键在不在」区分
     *                   「本批次确实 0 行」与「这是本改动之前建的老批次」，见
     *                   {@code DatasetQuotationCommitService#batchProductNosOf}。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void finalizeSuccess(UUID recordId, int totalRows,
                                List<DatasetSheetSummaryDTO> summary, long durationMs,
                                List<Map<String, String>> batchParts) {
        ImportRecord rec = ImportRecord.findById(recordId);
        if (rec == null) return;
        rec.importStatus = "SUCCESS";
        rec.totalRows = totalRows;
        rec.successRows = totalRows;
        rec.unmatchedRows = 0;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataset", "quote");
        meta.put("durationMs", durationMs);
        meta.put("summary", summary);
        meta.put("batchParts", batchParts == null ? List.of() : batchParts);
        rec.metadata = toJson(meta);
        // managed entity，字段改动随事务提交自动 flush
    }

    /**
     * B-2 ②：Phase 1 校验未通过 / 后台线程任何失败 → FAILED + <b>全量</b>错误。
     *
     * <p>🚫 {@code errors} 必须是全部错误而不是第一条（AC-4 / task-260902 AC-10 同源语义）：
     * 用户一次只看到一个错就得改一次重传一次，几百行的模板要传几十遍。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void finalizeFailed(UUID recordId, int totalRows, String message,
                               List<DsValidationError> errors) {
        ImportRecord rec = ImportRecord.findById(recordId);
        if (rec == null) return;
        rec.importStatus = "FAILED";
        rec.totalRows = totalRows;
        rec.successRows = 0;
        rec.unmatchedRows = errors == null ? 0 : errors.size();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataset", "quote");
        meta.put("message", message);
        meta.put("errors", errors == null ? List.of() : errors);
        rec.metadata = toJson(meta);
    }

    private static String toJson(Map<String, Object> meta) {
        try {
            return MAPPER.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }
}
