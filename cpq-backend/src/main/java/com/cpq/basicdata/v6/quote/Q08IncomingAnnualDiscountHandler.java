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
 * Q08 来料年降 → annual_discount（{@code discount_type=INCOMING_MATERIAL}）。
 *
 * <p>groupKey=(QUOTE, customer_no, INCOMING_MATERIAL, material_no=销售料号,
 * target_no=投入料号)；行集维度=discount_order。
 *
 * <p>task-0717 扩围：投入料号=材质料号，恒按材质处理 —— 原始码直接作 {@code target_no}，
 * 不 resolve、不铸号、不登记 material_customer_map、不登记 material_master
 * （名称走 material_recipe 兜底，年降表不冗余存名称）。
 */
@ApplicationScoped
public class Q08IncomingAnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "来料年降"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            // exact 而非 getStr：避开 contains 命中「投入料号名称」列
            String targetNo = row.exact("投入料号");
            if (targetNo == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
            String materialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }
            List<Object> key = Arrays.asList(materialNo, targetNo);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", ctx.customerNo, materialNo, targetNo),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
