package com.cpq.basicdata.v6.pricing;

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
import jakarta.inject.Inject;
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
 * 核价基础数据导入服务（24 Sheet）。
 *
 * <p>调度：每个 SheetHandler 在 REQUIRES_NEW 事务里独立跑。
 * customer_no 在 BOM 表用 "_GLOBAL_" 哨兵；客户料号关系 Sheet 从 Excel 行读取 customer_no。
 */
@ApplicationScoped
public class PricingImportService {

    @Inject ExcelParserService parser;

    // 24 个 Handler 按方案"多表写入顺序"排列：料号 → 关系 → 汇率 → BOM主 → BOM子 → 单价 → 其余
    @Inject P24UnitWeightHandler p24;
    @Inject P05CustomerMapHandler p05;
    @Inject P03ExchangeRateHandler p03;
    @Inject P04PricingVersionHandler p04;
    @Inject P06MaterialBomHandler p06;
    @Inject P07ElementBomHandler p07;
    @Inject P01ElementPricingPriceHandler p01;
    @Inject P02MaterialPricingPriceHandler p02;
    @Inject P08CapacityHandler p08;
    @Inject P09EquipmentDepreciationHandler p09;
    @Inject P10ProductionEnergyHandler p10;
    @Inject P11AuxiliaryEnergyHandler p11;
    @Inject P12ToolingCostHandler p12;
    @Inject P13ProductionConsumableHandler p13;
    @Inject P14PackagingConsumableHandler p14;
    @Inject P15IncomingProcessFeeHandler p15;
    @Inject P16IncomingOtherRatioFeeHandler p16;
    @Inject P17IncomingOtherFixedFeeHandler p17;
    @Inject P18SelfProcessAssemblyFeeHandler p18;
    @Inject P19FinishedOtherRatioFeeHandler p19;
    @Inject P20FinishedOtherFixedFeeHandler p20;
    @Inject P21PlatingSchemeHandler p21;
    @Inject P22PlatingCostHandler p22;
    @Inject P23OutsourceProcessFeeHandler p23;

    private List<SheetHandler> orderedHandlers() {
        return List.of(p24, p05, p03, p04, p06, p07, p01, p02, p08, p09, p10, p11, p12,
                       p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23);
    }

    public ImportResultDTO importExcel(String fileName, InputStream stream, UUID importedBy) {
        UUID recordId = createImportRecord(fileName, importedBy);
        ImportContext ctx = new ImportContext();
        ctx.customerNo = null;           // 核价无客户上下文；P05 从 Excel 行读
        ctx.systemType = "PRICING";
        ctx.importedBy = importedBy;
        ctx.importRecordId = recordId;

        ImportResultDTO out = new ImportResultDTO();
        out.importRecordId = recordId;
        out.systemType = "PRICING";
        List<SheetResultDTO> sheetDtos = new ArrayList<>();
        int totalSuccess = 0, totalFailed = 0;

        try (XSSFWorkbook wb = parser.open(stream)) {
            for (SheetHandler h : orderedHandlers()) {
                SheetImportResult r;
                try {
                    var sheet = wb.getSheet(h.sheetName());
                    if (sheet == null) {
                        r = new SheetImportResult(h.sheetName());
                    } else {
                        List<SheetRow> rows = parser.parseSheet(sheet);
                        r = h.handle(rows, ctx);
                    }
                } catch (Exception ex) {
                    Log.error("Sheet [" + h.sheetName() + "] 导入异常", ex);
                    r = new SheetImportResult(h.sheetName());
                    r.recordError(0, "_sheet_", ex.getMessage());
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
    public UUID createImportRecord(String fileName, UUID importedBy) {
        ImportRecord rec = new ImportRecord();
        // 不手动赋 id；@GeneratedValue 让 Hibernate 自动生成 UUID
        rec.customerId = null;           // 核价无客户上下文（V221 已改 nullable）
        rec.systemType = "PRICING";
        rec.originalFileName = fileName == null ? "pricing-import.xlsx" : fileName;
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
