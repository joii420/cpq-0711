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
import jakarta.enterprise.inject.Instance;
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
 * 报价基础数据导入服务（19 Sheet）。
 *
 * <p>调度：每个 SheetHandler 在 REQUIRES_NEW 事务里独立跑（per-Sheet 事务），
 * 一个 Sheet 失败不影响其它 Sheet 已成功的写入。
 */
@ApplicationScoped
public class QuoteImportService {

    @Inject ExcelParserService parser;
    @Inject EntityManager em;

    /** 19 个 Handler 通过 CDI Instance 收集。按落库方案"多表写入顺序" 排序。 */
    @Inject Instance<SheetHandler> handlerInstances;

    // 19 个 Handler 按写入顺序排列（料号 → 关系 → BOM主 → BOM子 → 单价 → 年降 → 其它）
    @Inject Q18UnitWeightHandler q18;            // 料号 unit_weight
    @Inject Q02CustomerMapHandler q02;           // 客户料号映射
    @Inject MaterialBomMergeHandler bomMerge;    // 物料BOM⇄组成件BOM 去重合并(替代 Q03/Q12)
    @Inject Q04ElementBomHandler q04;            // 元素BOM
    @Inject Q05ElementRecoveryHandler q05;       // 元素回收折扣 UPDATE
    @Inject Q01ElementPriceHandler q01;          // 元素单价
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

    private List<SheetHandler> orderedHandlers() {
        return List.of(q18, q02, q04, q05,
                       q01, q06, q07, q08, q09, q10, q11, q13, q14, q15, q16, q17, q19);
    }

    /**
     * 后台执行报价基础数据导入（异步）。调用方先 {@link #createImportRecord} 拿到 recordId 并把
     * Excel 读入内存 bytes，再经 ManagedExecutor 调本方法；HTTP 请求立即返回 PROCESSING，前端轮询
     * {@code GET /v6/{recordId}} 查进度。业务处理逻辑与原同步路径逐字一致，仅执行边界改为后台线程。
     *
     * <p>{@code @ActivateRequestContext}：让 request-scoped 的 EntityManager 在后台线程可用
     * （与原 HTTP 请求内单一 request-scoped EM 跨多 Sheet 事务的语义一致）。
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

        ImportResultDTO out = new ImportResultDTO();
        out.importRecordId = recordId;
        out.systemType = "QUOTE";
        List<SheetResultDTO> sheetDtos = new ArrayList<>();
        int totalSuccess = 0, totalFailed = 0;

        // 进度：总步数 = 1(物料BOM/组成件BOM 合并) + 各 Sheet handler。每步前增量写 import_record.metadata，
        // 供前端轮询渲染真实进度条；done = 已完成步数，current = 当前正在处理的 Sheet。
        final int totalSteps = 1 + orderedHandlers().size();
        int done = 0;

        long importT0 = System.nanoTime();
        try (XSSFWorkbook wb = parser.open(new ByteArrayInputStream(bytes))) {
            updateProgress(recordId, done, totalSteps, "物料BOM/组成件BOM 合并");
            // 物料BOM ⇄ 组成件BOM 去重合并（两 sheet 单一事务，组成件优先；替代 Q03/Q12 各写各的）
            {
                long parseT0 = System.nanoTime();
                var matSheet = wb.getSheet("物料BOM");
                var asmSheet = wb.getSheet("组成件BOM");
                List<SheetRow> matRows = matSheet != null ? parser.parseSheet(matSheet) : List.of();
                List<SheetRow> asmRows = asmSheet != null ? parser.parseSheet(asmSheet) : List.of();

                // repair-2 决策 D 前置：merge() 组成件分支要判定"是否命中本次导入材质料号集"，
                // 集合 = 物料BOM.材质料号 ∪ 物料与元素BOM(Q04源).材质料号，且必须在 merge() 之前收集齐
                // （merge() 早于 Q04，见架构评审 §4.1）。放 ctx.sharedCache，key="quoteMaterialNoSet"。
                java.util.Set<String> quoteMaterialNoSet = new java.util.LinkedHashSet<>();
                for (SheetRow row : matRows) {
                    String v = row.exact("材质料号");
                    if (v == null) v = row.exact("投入料号");   // 兼容旧文件字段名(V3 前)
                    if (v != null) quoteMaterialNoSet.add(v);
                }
                var q04Sheet = wb.getSheet("物料与元素BOM");
                if (q04Sheet != null) {
                    for (SheetRow row : parser.parseSheet(q04Sheet)) {
                        String v = row.getStr("材质料号");
                        if (v != null) quoteMaterialNoSet.add(v);
                    }
                }
                ctx.sharedCache.put("quoteMaterialNoSet", quoteMaterialNoSet);

                double parseMs = (System.nanoTime() - parseT0) / 1e6;
                com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().reset();
                long mergeT0 = System.nanoTime();
                SheetImportResult mr;
                try {
                    mr = bomMerge.merge(matRows, asmRows, ctx);
                    Log.debugf("[v6import] QUOTE sheet=物料BOM+组成件BOM(合并) rows=%d/%d parse=%.0fms handle=%.0fms writer{%s}",
                        matRows.size(), asmRows.size(), parseMs, (System.nanoTime() - mergeT0) / 1e6,
                        com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().summary());
                } catch (Exception ex) {
                    Log.error("物料BOM/组成件BOM 合并导入异常", ex);
                    mr = new SheetImportResult("物料BOM+组成件BOM(合并)");
                    mr.recordError(0, "_sheet_", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                sheetDtos.add(SheetResultDTO.from(mr));
                totalSuccess += mr.successRows;
                totalFailed += mr.failedRows;
            }
            done++;   // 合并步完成
            for (SheetHandler h : orderedHandlers()) {
                updateProgress(recordId, done, totalSteps, h.sheetName());
                SheetImportResult r;
                try {
                    var sheet = wb.getSheet(h.sheetName());
                    if (sheet == null) {
                        r = new SheetImportResult(h.sheetName());
                        // 不视为错误，仅记 0 行
                    } else {
                        long parseT0 = System.nanoTime();
                        List<SheetRow> rows = parser.parseSheet(sheet);
                        double parseMs = (System.nanoTime() - parseT0) / 1e6;
                        // 写入器分段计时：sheet 边界 reset → handle → 读 summary
                        com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().reset();
                        long handleT0 = System.nanoTime();
                        r = h.handle(rows, ctx);
                        double handleMs = (System.nanoTime() - handleT0) / 1e6;
                        Log.debugf("[v6import] QUOTE sheet=%s rows=%d parse=%.0fms handle=%.0fms writer{%s}",
                            h.sheetName(), rows.size(), parseMs, handleMs,
                            com.cpq.basicdata.v6.versioning.VersionedV6Writer.profile().summary());
                    }
                } catch (Exception ex) {
                    Log.error("Sheet [" + h.sheetName() + "] 导入异常", ex);
                    r = new SheetImportResult(h.sheetName());
                    StringBuilder sb = new StringBuilder();
                    sb.append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
                    Throwable root = ex;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    if (root != ex) sb.append(" | root=").append(root.getClass().getSimpleName())
                                       .append(": ").append(root.getMessage());
                    StackTraceElement[] st = ex.getStackTrace();
                    for (int i = 0; i < Math.min(5, st.length); i++) {
                        sb.append(" @").append(st[i].getClassName().replaceFirst(".*\\.", ""))
                          .append(".").append(st[i].getMethodName())
                          .append(":").append(st[i].getLineNumber());
                    }
                    r.recordError(0, "_sheet_", sb.toString());
                }
                sheetDtos.add(SheetResultDTO.from(r));
                totalSuccess += r.successRows;
                totalFailed += r.failedRows;
                done++;
            }
            Log.debugf("[v6import] QUOTE TOTAL elapsed=%.0fms sheets=%d (含BOM合并步)",
                (System.nanoTime() - importT0) / 1e6, totalSteps);
        } catch (Exception e) {
            // 后台线程：解析/未知失败不抛出（无处可抛），落 FAILED 供前端轮询
            Log.error("Excel 解析失败", e);
            out.sheetResults = sheetDtos;
            out.totalSuccessRows = totalSuccess;
            out.totalFailedRows = totalFailed;
            out.status = "FAILED";
            finalizeImportRecord(recordId, out, sheetDtos);
            return;
        }

        out.sheetResults = sheetDtos;
        out.totalSuccessRows = totalSuccess;
        out.totalFailedRows = totalFailed;
        out.status = totalFailed == 0 ? "SUCCESS" : (totalSuccess > 0 ? "PARTIAL" : "FAILED");
        finalizeImportRecord(recordId, out, sheetDtos);
    }

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
     * sheetResults。进度写入失败不影响导入本身（吞掉异常）。与 finalizeImportRecord 同为本 bean 的
     * REQUIRES_NEW 方法、同样在 processImport 内自调用——既有 finalize 路径已验证此模式可正常提交。
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
