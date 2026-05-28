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

/** P15 来料加工费 → unit_price (PRICING/MATERIAL/来料加工费)。 */
@ApplicationScoped
public class P15IncomingProcessFeeHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "来料加工费"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("来料料号");
                if (code == null) { result.recordError(row.rowNo, "来料料号", "为空"); continue; }
                UnitPrice p = UnitPriceWriter.newRow("PRICING", "MATERIAL", "来料加工费", null, null, ctx.importedBy);
                p.code = code;
                p.finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
                p.pricingPrice = row.getDecimal("加工费");
                if (p.pricingPrice == null) p.pricingPrice = java.math.BigDecimal.ZERO;
                p.currency = row.getStr("币种");
                p.unit = row.getStr("计量单位");
                p.defectRate = row.getDecimal("损耗");
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
