package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.ProcessNoResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q15 组装加工费年降 → annual_discount（{@code discount_type=ASSEMBLY_PROCESS}）。
 *
 * <p>groupKey=(QUOTE, customer_no, ASSEMBLY_PROCESS, material_no=销售料号,
 * target_no=真工序编号)；行集维度=discount_order。
 *
 * <p>repair-0727：「组装工序」列原始值已在 Phase 1
 * （{@code QuoteImportValidator#validateAssemblyAnnualDiscount}）解析为真工序编号，本 handler
 * 只从 {@code ctx.sharedCache["assemblyProcessNo"]} 取回落 {@code target_no}，<b>不写名称</b>
 * （名称由视图 JOIN process_master 取）。该列<b>允许为空</b> → {@code target_no} 为 null。
 */
@ApplicationScoped
public class Q15AssemblyAnnualDiscountHandler implements SheetHandler {

    @Inject AnnualDiscountWriter annualDiscountWriter;

    @Override public String sheetName() { return "组装加工费年降"; }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        @SuppressWarnings("unchecked")
        Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo =
            (Map<List<String>, ProcessNoResolver.Resolved>) ctx.sharedCache.get("assemblyProcessNo");
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            if (row.getInt("年降顺序") == null) {
                result.recordError(row.rowNo, "年降顺序", "为空");
                continue;
            }

            String rawProcess = row.getStr("组装工序");
            String targetNo = null;
            if (rawProcess != null) {
                ProcessNoResolver.Resolved resolved = assemblyProcessNo == null ? null
                    : assemblyProcessNo.get(List.of("组装加工费年降", materialNo.strip(), rawProcess.strip()));
                if (resolved == null) {
                    result.recordError(row.rowNo, "组装工序",
                        "工序「" + rawProcess + "」未在 Phase 1 解析结果中找到（Phase 1 理论上已全量拦截，"
                            + "此处出现属竞态/数据不一致），导入中止");
                    continue;
                }
                targetNo = resolved.processNo();
            }

            List<Object> key = Arrays.asList(materialNo, targetNo);
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key,
                AnnualDiscountWriter.groupKey("ASSEMBLY_PROCESS", ctx.customerNo, materialNo, targetNo),
                AnnualDiscountWriter.readContent(row));
            result.successRows++;
        }

        annualDiscountWriter.write(groupKeyOf, contentOf, result, ctx.pendingQuotationId);
        return result;
    }
}
