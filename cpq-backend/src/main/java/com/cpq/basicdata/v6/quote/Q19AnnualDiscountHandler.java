package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q19 年降系数 → annual_discount（{@code discount_type=FINISHED}，整单级年降）。
 *
 * <p>repair-0804：原走 {@code AnnualDiscountRepository} 的行级 upsert（空值不覆盖、无版本化、
 * 无 pending 隔离、无客户维度），现统一为组级版本化写入。
 * <p>groupKey=(QUOTE, customer_no, FINISHED, material_no, target_no=null)；行集维度=discount_order。
 */
@ApplicationScoped
public class Q19AnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "年降系数"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            // 年降顺序必填由 Phase 1 拦截；此处只做兜底（Phase 1 已全量校验，走到这里属竞态）
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }
            // FINISHED 为整单级年降，无挂载目标
            List<Object> key = Arrays.asList(materialNo, null);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("FINISHED", ctx.customerNo, materialNo, null),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
