package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.util.DecimalScale;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * P15 来料加工费 → unit_price (PRICING/INCOMING_PROCESS/来料加工费) 整组版本化。
 * <p>特殊之处：本 Sheet 的分组锚点是 {@code finished_material_no}(销售料号)，而 {@code code}
 * 在本表语义是"来料料号"，属于同一销售料号下会有多条的明细维度，因此进 content 而不是 groupKey。
 */
@ApplicationScoped
public class P15IncomingProcessFeeHandler implements SheetHandler {
    @Inject VersionedV6Writer writer;
    @Override public String sheetName() { return "来料加工费"; }
    private static final List<String> CONTENT = List.of("code", "pricing_price", "currency", "unit", "defect_rate");
    private static final List<String> DESCRIPTOR = List.of("production_no");
    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // finished_material_no(销售料号) → code(来料料号,组内去重键,末值覆盖) → content row
        Map<String, LinkedHashMap<String, Map<String, Object>>> byFinishedMaterial = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String incomingCode = row.getStr("来料料号");
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (incomingCode == null || finishedMaterialNo == null) {
                result.recordError(row.rowNo, "来料料号/销售料号", "必填项为空");
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", incomingCode);
            BigDecimal price = DecimalScale.at(row.getDecimal("加工费"), 6);
            c.put("pricing_price", price == null ? BigDecimal.ZERO : price);
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            c.put("defect_rate", DecimalScale.at(row.getDecimal("损耗"), 4));
            c.put("production_no", row.getStr("生产料号"));
            byFinishedMaterial.computeIfAbsent(finishedMaterialNo, k -> new LinkedHashMap<>())
                .put(nz(incomingCode), c);
            result.successRows++;
        }
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byFinishedMaterial.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.INCOMING_PROCESS);
            gk.put("cost_type", "来料加工费");
            gk.put("finished_material_no", e.getKey());
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
