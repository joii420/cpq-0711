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

/** Q09 来料回收折扣 → unit_price (price=MATERIAL, cost=回收折扣)。 */
@ApplicationScoped
public class Q09IncomingRecoveryHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "来料回收折扣"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("投入料号");
                if (code == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
                UnitPrice p = UnitPriceWriter.newRow("QUOTE", "MATERIAL", "回收折扣", null, ctx.customerNo, ctx.importedBy);
                p.code = code;
                p.finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
                p.seqNo = row.getInt("项次");
                p.costRatio = row.getDecimal("回收折扣");
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
