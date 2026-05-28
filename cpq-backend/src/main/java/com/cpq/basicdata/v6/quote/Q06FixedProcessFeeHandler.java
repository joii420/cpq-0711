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

/** Q06 来料固定加工费 → unit_price (price=MATERIAL, cost=来料加工费)。 */
@ApplicationScoped
public class Q06FixedProcessFeeHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "来料固定加工费"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("投入料号");
                if (code == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
                UnitPrice p = UnitPriceWriter.newRow("QUOTE", "MATERIAL", "来料加工费", null, ctx.customerNo, ctx.importedBy);
                p.code = code;
                p.finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
                p.seqNo = row.getInt("项次");
                p.baseValue = row.getDecimal("基准值");
                p.costRatio = row.getDecimal("比例");
                p.currency = row.getStr("货币");
                p.unit = row.getStr("计价单位");
                p.isFluctuateWithMaterial = row.getBool("是否随材料价格波动");
                p.materialIncreaseRatio = row.getDecimal("材料结算涨幅比例");
                p.materialFixedIncrease = row.getDecimal("材料固定的涨幅值");
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
