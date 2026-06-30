package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.entity.UnitPrice;
import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.UnitPriceWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/** P23 其他外加工成本 → unit_price (PRICING/MATERIAL/其他加工费)。 */
@ApplicationScoped
public class P23OutsourceProcessFeeHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "其他外加工成本"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("宏丰料号");
                String operationNo = row.getStr("工序编号");
                if (code == null || operationNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                    continue;
                }
                UnitPrice p = UnitPriceWriter.newRow("PRICING", PricingPriceType.OUTSOURCE_PROCESS, "其他加工费", null, null, ctx.importedBy);
                p.code = code;
                p.operationNo = operationNo;
                p.pricingPrice = row.getDecimal("外加工费用");
                if (p.pricingPrice == null) p.pricingPrice = java.math.BigDecimal.ZERO;
                p.currency = row.getStr("币种");
                p.unit = row.getStr("单位");
                writer.upsert(p);
                result.successRows++;
                result.recordWrite("unit_price", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
