package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.AnnualDiscountRepository;
import com.cpq.basicdata.v6.repository.AnnualDiscountRepository.DiscountRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Q19 年降系数 → annual_discount。biz_type 报价默认 INCOMING。 */
@ApplicationScoped
public class Q19AnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountRepository repo;

    @Override public String sheetName() { return "年降系数"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // §P1-Q19 延后批量：按冲突键 (material_no, discount_order) 去重 + 末值非空胜（逐字段），
        // 循环后一次多值 INSERT...ON CONFLICT，与逐行 upsertOne 等价。biz_type/discount_strategy 为常量。
        Map<String, DiscountRow> acc = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                Integer order = row.getInt("年降顺序");
                if (materialNo == null || order == null) {
                    result.recordError(row.rowNo, "宏丰料号/年降顺序", "必填项为空");
                    continue;
                }
                AnnualDiscountRepository.accDiscount(acc, new DiscountRow(
                    materialNo, order,
                    row.getDecimal("年降系数"),
                    row.getDecimal("单次固定年降金额"),
                    row.getStr("货币"),
                    row.getStr("计价单位"),
                    row.getInt("降价次数")));
                result.successRows++;
                result.recordWrite("annual_discount", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        if (!acc.isEmpty()) {
            repo.upsertBatch("INCOMING", "来料年降", new ArrayList<>(acc.values()), ctx.importedBy);
        }
        return result;
    }
}
