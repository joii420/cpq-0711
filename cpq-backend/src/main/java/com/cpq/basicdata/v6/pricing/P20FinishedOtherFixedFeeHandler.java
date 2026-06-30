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

/** P20 成品其他固定费用 → unit_price (cost_type 动态)。 */
@ApplicationScoped
public class P20FinishedOtherFixedFeeHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "成品其他固定费用"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("宏丰料号");
                String costType = row.getStr("要素名称");
                if (code == null || costType == null) {
                    result.recordError(row.rowNo, "宏丰料号/要素名称", "必填项为空");
                    continue;
                }
                UnitPrice p = UnitPriceWriter.newRow("PRICING", PricingPriceType.FINISHED_OTHER, costType, null, null, ctx.importedBy);
                p.code = code;
                p.seqNo = row.getInt("项次");
                p.pricingPrice = row.getDecimal("费用");
                if (p.pricingPrice == null) p.pricingPrice = java.math.BigDecimal.ZERO;
                p.currency = row.getStr("币种");
                p.unit = row.getStr("计价单位");
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
