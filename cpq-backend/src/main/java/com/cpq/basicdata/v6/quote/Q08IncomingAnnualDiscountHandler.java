package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
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
 * Q08 来料年降 → unit_price (price=INCOMING_MATERIAL_REDUCTION, cost=年降系数)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 年降系数, code, finished_material_no)，
 * content=[discount_order, cost_ratio, pricing_price, currency, unit]。
 * <p>行集维度 = discount_order（年降顺序，在 uq_unit_price 内）；**seq_no 丢列**（已确认无下游消费）。
 */
@ApplicationScoped
public class Q08IncomingAnnualDiscountHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "来料年降"; }

    private static final List<String> CONTENT = List.of(
        "discount_order", "cost_ratio", "pricing_price", "currency", "unit");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
        for (SheetRow row : rows) {
            result.totalRows++;
            String inputName = row.exact("投入料号名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(code, inputName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);
            String finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
            List<Object> key = Arrays.asList(code, finishedMaterialNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "INCOMING_MATERIAL_REDUCTION");
                g.put("cost_type", "年降系数");
                g.put("code", code);
                g.put("finished_material_no", finishedMaterialNo);
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
        for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                result.recordWrite("unit_price", e.getValue().size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", ex.getMessage());
            }
        }
        return result;
    }
}
