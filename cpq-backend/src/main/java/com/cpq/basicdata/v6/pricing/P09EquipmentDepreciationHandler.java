package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.*;

/** P09 设备折旧成本 → production_energy (price_type=DEPRECIATION) 整组版本化。 */
@ApplicationScoped
public class P09EquipmentDepreciationHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "设备折旧成本"; }

    private static final List<String> CONTENT = List.of("process_no", "unit_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<String, List<Map<String, Object>>> byMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String processNo = row.getStr("工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("unit_price", row.getDecimal("折旧单价"));
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            c.put("production_no", row.getStr("生产料号"));   // 描述列
            byMat.computeIfAbsent(materialNo, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byMat.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("material_no", e.getKey());
            gk.put("price_type", "DEPRECIATION");
            groups.put(gk, e.getValue());
        }
        try {
            writer.writeVersionedGroups("production_energy", "calc_version", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("production_energy", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
