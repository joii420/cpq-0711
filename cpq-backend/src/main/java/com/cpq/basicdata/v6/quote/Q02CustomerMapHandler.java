package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/** Q02 客户料号与宏丰料号的关系 → material_customer_map。 */
@ApplicationScoped
public class Q02CustomerMapHandler implements SheetHandler {

    @Inject MaterialCustomerMapRepository repo;

    @Override public String sheetName() { return "客户料号与宏丰料号的关系"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                String customerProductNo = row.getStr("客户产品编号");
                if (materialNo == null || customerProductNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/客户产品编号", "必填项为空");
                    continue;
                }
                repo.upsert(
                    materialNo,
                    ctx.customerNo,
                    row.getStr("客户名称"),
                    row.getStr("客户料号名称"),
                    customerProductNo,
                    row.getStr("客户图号"),
                    null,                            // seq_no 报价表无项次列
                    row.getStr("付款方式"),
                    row.getStr("基础货币"),
                    row.getStr("报价货币"),
                    row.getDecimal("汇率"),
                    ctx.importedBy);
                result.successRows++;
                result.recordWrite("material_customer_map", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
