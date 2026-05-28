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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
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
    @Inject Q03MaterialBomHandler q03;           // 物料BOM + material_master.material_type
    @Inject Q04ElementBomHandler q04;            // 元素BOM
    @Inject Q05ElementRecoveryHandler q05;       // 元素回收折扣 UPDATE
    @Inject Q12AssemblyBomHandler q12;           // 组成件BOM
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
        return List.of(q18, q02, q03, q04, q05, q12,
                       q01, q06, q07, q08, q09, q10, q11, q13, q14, q15, q16, q17, q19);
    }

    /**
     * 执行报价基础数据导入。
     * @param customerNo 客户编码（由调用方从 customerId 查出）
     * @param fileName 上传文件名（落 import_record.original_file_name）
     * @param stream Excel 二进制流
     * @param importedBy 当前用户 UUID
     * @return ImportResultDTO（含 importRecordId + per-Sheet 结果）
     */
    public ImportResultDTO importExcel(UUID customerId, String customerNo, String fileName,
                                       InputStream stream, UUID importedBy) {
        UUID recordId = createImportRecord(customerId, fileName, importedBy);
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

        try (XSSFWorkbook wb = parser.open(stream)) {
            for (SheetHandler h : orderedHandlers()) {
                SheetImportResult r;
                try {
                    var sheet = wb.getSheet(h.sheetName());
                    if (sheet == null) {
                        r = new SheetImportResult(h.sheetName());
                        // 不视为错误，仅记 0 行
                    } else {
                        List<SheetRow> rows = parser.parseSheet(sheet);
                        r = h.handle(rows, ctx);
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
            }
        } catch (Exception e) {
            Log.error("Excel 解析失败", e);
            throw new RuntimeException("Excel 解析失败: " + e.getMessage(), e);
        }

        out.sheetResults = sheetDtos;
        out.totalSuccessRows = totalSuccess;
        out.totalFailedRows = totalFailed;
        out.status = totalFailed == 0 ? "SUCCESS" : (totalSuccess > 0 ? "PARTIAL" : "FAILED");
        finalizeImportRecord(recordId, out, sheetDtos);
        return out;
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
}
