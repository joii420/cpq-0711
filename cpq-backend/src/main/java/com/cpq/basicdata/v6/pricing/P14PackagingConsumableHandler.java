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

/** P14 包装材料BOM → unit_price (PRICING/MATERIAL/包装)。 */
@ApplicationScoped
public class P14PackagingConsumableHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "包装材料BOM"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("宏丰料号");
                if (code == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
                String versionNo = row.getStr("取用的耗材版本");
                UnitPrice p = UnitPriceWriter.newRow("PRICING", "MATERIAL", "包装", versionNo, null, ctx.importedBy);
                p.code = code;
                p.finishedMaterialNo = code;
                p.operationNo = row.getStr("工序编号");
                p.pricingPrice = row.getDecimal("包装成本单价");
                if (p.pricingPrice == null) p.pricingPrice = java.math.BigDecimal.ZERO;
                p.currency = row.getStr("币种");
                p.unit = row.getStr("计量单位");
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
