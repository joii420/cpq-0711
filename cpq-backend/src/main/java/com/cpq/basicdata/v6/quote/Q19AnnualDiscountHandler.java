package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/** Q19 年降系数 → annual_discount。biz_type 报价默认 INCOMING。 */
@ApplicationScoped
public class Q19AnnualDiscountHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "年降系数"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                Integer order = row.getInt("年降顺序");
                if (materialNo == null || order == null) {
                    result.recordError(row.rowNo, "宏丰料号/年降顺序", "必填项为空");
                    continue;
                }
                em.createNativeQuery(
                        "INSERT INTO annual_discount (biz_type, material_no, discount_strategy, " +
                        "  discount_order, discount_ratio, fixed_discount_value, currency, unit, discount_times, " +
                        "  created_at, updated_at, updated_by) " +
                        "VALUES (:bt, :m, :ds, :o, :r, :v, :c, :u, :dt, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (biz_type, material_no, discount_strategy, discount_order) DO UPDATE SET " +
                        "  discount_ratio = COALESCE(EXCLUDED.discount_ratio, annual_discount.discount_ratio), " +
                        "  fixed_discount_value = COALESCE(EXCLUDED.fixed_discount_value, annual_discount.fixed_discount_value), " +
                        "  currency = COALESCE(EXCLUDED.currency, annual_discount.currency), " +
                        "  unit = COALESCE(EXCLUDED.unit, annual_discount.unit), " +
                        "  discount_times = COALESCE(EXCLUDED.discount_times, annual_discount.discount_times), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("bt", "INCOMING")
                    .setParameter("m", materialNo)
                    .setParameter("ds", "来料年降")
                    .setParameter("o", order)
                    .setParameter("r", row.getDecimal("年降系数"))
                    .setParameter("v", row.getDecimal("单次固定年降金额"))
                    .setParameter("c", row.getStr("货币"))
                    .setParameter("u", row.getStr("计价单位"))
                    .setParameter("dt", row.getInt("降价次数"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("annual_discount", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
