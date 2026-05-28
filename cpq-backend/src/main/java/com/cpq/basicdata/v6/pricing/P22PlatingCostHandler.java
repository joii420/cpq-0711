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

import java.math.BigDecimal;
import java.util.List;

/** P22 电镀成本 → unit_price 一行拆两条 (PRICING/电镀加工费 + 电镀材料费)。 */
@ApplicationScoped
public class P22PlatingCostHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "电镀成本"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("宏丰料号");
                if (code == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
                String platingSchemeNo = row.getStr("电镀方案编号");
                if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                    result.successRows++; continue;
                }
                String versionNo = row.getStr("版本编号");
                BigDecimal processFee = row.getDecimal("电镀加工费");
                BigDecimal materialFee = row.getDecimal("电镀材料费");
                String currency = row.getStr("货币");
                String unit = row.getStr("计价单位");
                BigDecimal defectRate = row.getDecimal("不良率");

                UnitPrice p1 = UnitPriceWriter.newRow("PRICING", "MATERIAL", "电镀加工费", versionNo, null, ctx.importedBy);
                p1.code = code;
                p1.pricingPrice = processFee != null ? processFee : BigDecimal.ZERO;
                p1.currency = currency; p1.unit = unit; p1.defectRate = defectRate;
                writer.upsert(p1);
                result.recordWrite("unit_price", 1);

                UnitPrice p2 = UnitPriceWriter.newRow("PRICING", "MATERIAL", "电镀材料费", versionNo, null, ctx.importedBy);
                p2.code = code;
                p2.pricingPrice = materialFee != null ? materialFee : BigDecimal.ZERO;
                p2.currency = currency; p2.unit = unit; p2.defectRate = defectRate;
                writer.upsert(p2);
                result.recordWrite("unit_price", 1);

                result.successRows++;
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
