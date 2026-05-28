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

/** Q01 元素单价 → unit_price (system=QUOTE, price=ELEMENT, cost=元素价格)。 */
@ApplicationScoped
public class Q01ElementPriceHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "元素单价"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("单个元素", "所有元素", "元素代码", "元素名称");
                if (code == null) { result.recordError(row.rowNo, "元素代码", "列值为空"); continue; }
                UnitPrice p = UnitPriceWriter.newRow("QUOTE", "ELEMENT", "元素价格", null, ctx.customerNo, ctx.importedBy);
                p.code = code;
                p.customerName = row.getStr("客户名称");
                p.seqNo = row.getInt("项次");
                p.sourceUrl = row.getStr("网址");
                p.sourceName = row.getStr("网站名称");
                p.fetchRule = row.getStr("取用规则");
                p.premiumFee = row.getDecimal("升水价", "手续费");
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
