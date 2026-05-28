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

/** P01 元素核价价格表 → unit_price (PRICING/ELEMENT/元素核价价格)。 */
@ApplicationScoped
public class P01ElementPricingPriceHandler implements SheetHandler {

    @Inject UnitPriceWriter writer;

    @Override public String sheetName() { return "元素核价价格表"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String code = row.getStr("元素代码");
                if (code == null) { result.recordError(row.rowNo, "元素代码", "为空"); continue; }
                String versionNo = row.getStr("元素价格版本");
                UnitPrice p = UnitPriceWriter.newRow("PRICING", "ELEMENT", "元素核价价格", versionNo, null, ctx.importedBy);
                p.code = code;
                p.pricingPrice = row.getDecimal("核价单价");
                if (p.pricingPrice == null) p.pricingPrice = java.math.BigDecimal.ZERO;
                p.marketRefPrice = row.getDecimal("市场参考价");
                p.sourceUrl = row.getStr("参考价来源网址", "网址");
                p.sourceName = row.getStr("网站名称");
                p.fetchRule = row.getStr("参考价取用规则", "取用规则");
                p.currency = row.getStr("币种");
                p.unit = row.getStr("计量单位");
                p.recoveryDiscount = row.getDecimal("回收折扣");
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
