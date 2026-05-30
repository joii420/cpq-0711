package com.cpq.basicdata.v6.quote;

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

/** Q11 成品其他费用 → unit_price (price=MATERIAL, cost=要素名称动态)。 */
@ApplicationScoped
public class Q11FinishedOtherFeeHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "成品其他费用"; }

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
                UnitPrice p = UnitPriceWriter.newRow("QUOTE", "MATERIAL", costType, null, ctx.customerNo, ctx.importedBy);
                p.code = code;
                p.seqNo = row.getInt("项次");
                p.pricingPrice = row.getDecimal("值");   // 固定金额写值；比例费用留 NULL（D1）
                p.costRatio = row.getDecimal("比例");
                p.currency = row.getStr("货币");
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
