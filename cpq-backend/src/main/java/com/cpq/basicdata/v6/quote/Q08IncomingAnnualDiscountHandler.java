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

/** Q08 来料年降 → unit_price (price=MATERIAL, cost=年降系数)。年降顺序借用 seq_no 维度落库。 */
@ApplicationScoped
public class Q08IncomingAnnualDiscountHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "来料年降"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("投入料号");
                if (code == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
                UnitPrice p = UnitPriceWriter.newRow("QUOTE", "MATERIAL", "年降系数", null, ctx.customerNo, ctx.importedBy);
                p.code = code;
                p.finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
                p.seqNo = row.getInt("项次");               // §8: 项次 → seq_no
                p.discountOrder = row.getInt("年降顺序");     // §8: 年降顺序 → discount_order（具名列）
                p.costRatio = row.getDecimal("年降系数");
                p.pricingPrice = row.getDecimal("单次固定年降值");  // 比例/固定二选一，空值保留 NULL（D1）
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
