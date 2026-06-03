package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
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
 * P07 物料与元素BOM (PRICING) → element_bom + element_bom_item，主从版本化（对齐 Q04）。
 *
 * <p>版本列 characteristic：子表行集相同复用 characteristic，不同则 max+1（首版 2000）+ is_current 翻转。
 * <p>核价全局共享：customer_no="_GLOBAL_"，system_type='PRICING'。
 */
@ApplicationScoped
public class P07ElementBomHandler implements SheetHandler {

    public static final String CUSTOMER = "_GLOBAL_";

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "物料与元素BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "content", "scrap_rate");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, Map<List<Object>, Map<String, Object>>> childByMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("物料料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "物料料号", "为空"); continue; }
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素代码");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            childByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 元素代码)
            result.successRows++;
        }

        for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childByMat.entrySet()) {
            String materialNo = e.getKey();
            List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
            try {
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "PRICING");
                masterGk.put("customer_no", CUSTOMER);
                masterGk.put("material_no", materialNo);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                writer.writeVersionedMasterDetail(
                    "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                    "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows);
                result.recordWrite("element_bom", 1);
                result.recordWrite("element_bom_item", childRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
            }
        }
        return result;
    }
}
