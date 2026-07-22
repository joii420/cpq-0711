package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q15 组装加工费年降 → unit_price (price=COMPONENT_REDUCTION, cost=年降系数)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, COMPONENT, 年降系数, code, finished_material_no, operation_no)，
 * content=[discount_order, cost_ratio, pricing_price, currency, unit]。
 * <p>**校验发现 #6：code 必须进 groupKey**（unit_price.code NOT NULL；code=宏丰料号）。
 * <p>行集维度 = discount_order；**seq_no 丢列**（已确认无下游消费）。
 */
@ApplicationScoped
public class Q15AssemblyAnnualDiscountHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "组装加工费年降"; }

    private static final List<String> CONTENT = List.of(
        "discount_order", "cost_ratio", "pricing_price", "currency", "unit");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            if (code == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            String operationNo = row.getStr("组装工序");
            List<Object> key = Arrays.asList(code, finishedMaterialNo, operationNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "COMPONENT_REDUCTION");
                g.put("cost_type", "年降系数");
                g.put("code", code);                          // #6：code NOT NULL，必须进 groupKey
                g.put("finished_material_no", finishedMaterialNo);
                g.put("operation_no", operationNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("discount_order", row.getInt("年降顺序"));
            c.put("cost_ratio", row.getDecimal("年降系数"));
            c.put("pricing_price", row.getDecimal("单次固定年降值"));   // 比例/固定二选一，空值留 NULL（D1）
            c.put("currency", row.getStr("货币"));
            c.put("unit", row.getStr("计价单位"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, List.of(), groups, ctx.pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("unit_price", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue(), null, ctx.pendingQuotationId));
                    result.recordWrite("unit_price", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }
}
