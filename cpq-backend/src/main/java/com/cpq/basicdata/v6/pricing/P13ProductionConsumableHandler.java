package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** P13 生产耗材BOM → unit_price (PRICING/CONSUMABLE/耗材) 整组版本化。 */
@ApplicationScoped
public class P13ProductionConsumableHandler implements SheetHandler {
    @Inject VersionedV6Writer writer;
    @Override public String sheetName() { return "生产耗材BOM"; }
    private static final List<String> CONTENT = List.of("operation_no", "pricing_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");
    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // code(销售料号) → operation_no(组内去重键,末值覆盖) → content row
        Map<String, LinkedHashMap<String, Map<String, Object>>> byCode = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            String operationNo = row.getStr("工序编号");
            if (code == null) {
                result.recordError(row.rowNo, "宏丰料号", "为空");
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("operation_no", operationNo);
            BigDecimal price = row.getDecimal("耗材成本单价");
            c.put("pricing_price", price == null ? BigDecimal.ZERO : price);
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            c.put("production_no", row.getStr("生产料号"));
            byCode.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(nz(operationNo), c);
            result.successRows++;
        }
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byCode.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", "CONSUMABLE");
            gk.put("cost_type", "耗材");
            gk.put("code", e.getKey());
            groups.put(gk, new ArrayList<>(e.getValue().values()));
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
