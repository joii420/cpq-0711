package com.cpq.basicdata.v6.quote;

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
 * Q04 物料与元素BOM → element_bom 主表 + element_bom_item 子表。
 *
 * <p>版本化（Task 5）：按 material_no（投入料号）分组，调
 * {@link VersionedV6Writer#writeVersionedMasterDetail}：子表行集指纹相同复用 characteristic，
 * 不同则 characteristic max+1 + 主/子 is_current 翻转（子表 uq 含 characteristic → 多版本保留）。
 * <p>主表 bom_type='MATERIAL'（NOT NULL，经 masterFixedColumns 写入）。
 * <p>替代原 fingerprintExisting/fingerprintRows/nv() 自写指纹逻辑（已删）。
 */
@ApplicationScoped
public class Q04ElementBomHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "物料与元素BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "content", "scrap_rate", "composition_qty", "issue_unit", "base_qty");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // 按 material_no 分组；组内按 (seq_no, component_no) 去重（后写覆盖，匹配原 ON CONFLICT 语义）
        Map<String, Map<List<Object>, Map<String, Object>>> childDedupByMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("投入料号");
            if (materialNo == null) { result.recordError(row.rowNo, "投入料号", "为空（应作为主件料号）"); continue; }
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("composition_qty", row.getDecimal("毛用量"));
            c.put("issue_unit", row.getStr("毛用量单位"));
            c.put("base_qty", row.getDecimal("净用量"));
            childDedupByMat
                .computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 元素)
            result.successRows++;
        }

        for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childDedupByMat.entrySet()) {
            String materialNo = e.getKey();
            List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
            try {
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", materialNo);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);   // element_bom_item 同身份
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
