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

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "物料与元素BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "content", "scrap_rate");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // 分组维度 = (material_no=销售料号, material_part_no=材质料号); 组内按(项次,元素代码)去重。
        // 核价该 sheet 材质料号列名仍为"物料料号"(未随报价侧改名), 故读列 材质料号->物料料号 双兼容。
        Map<List<String>, Map<List<Object>, Map<String, Object>>> childByKey = new LinkedHashMap<>();
        Map<List<String>, String[]> keyMeta = new LinkedHashMap<>();   // key -> [materialNo, materialPartNo]
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "物料料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String materialPartNo = row.getStr("材质料号", "物料料号");
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素代码");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            List<String> gkey = Arrays.asList(materialNo, materialPartNo == null ? "" : materialPartNo);
            keyMeta.putIfAbsent(gkey, new String[]{materialNo, materialPartNo});
            childByKey.computeIfAbsent(gkey, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 元素代码)
            result.successRows++;
        }

        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "PRICING");
                masterGk.put("customer_no", CUSTOMER);
                masterGk.put("material_no", meta[0]);
                masterGk.put("material_part_no", meta[1]);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("element_bom", "characteristic",
                    Map.of("bom_type", "MATERIAL"), "element_bom_item", "characteristic",
                    CHILD_CONTENT, items);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "PRICING");
                    masterGk.put("customer_no", CUSTOMER);
                    masterGk.put("material_no", meta[0]);
                    masterGk.put("material_part_no", meta[1]);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                    writer.writeVersionedMasterDetail(
                        "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                        "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows);
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + meta[0] + "/part=" + meta[1] + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }
}
