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
 * Q12 组成件BOM → material_bom (bom_type=ASSEMBLY) + material_bom_item。
 *
 * <p>版本化（Task 5 / V293）：按 material_no 分组调 {@link VersionedV6Writer#writeVersionedMasterDetail}。
 * 主表 masterGk 含 bom_type=ASSEMBLY + characteristic='ASSEMBLY'（material_bom uq 不含 bom_type，
 * 靠 characteristic 与 Q03 物理隔离）。子表按 bom_version 多版本保留（V293 起），历史版本行 is_current=false 留存。
 */
@ApplicationScoped
public class Q12AssemblyBomHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "组成件BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "operation_no", "item_seq", "component_no", "composition_qty", "issue_unit");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, Map<List<Object>, Map<String, Object>>> childByMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            Integer seq = row.getInt("项次（一级）", "项次");
            String componentNo = row.getStr("组成件料号");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("operation_no", row.getStr("工序编号"));
            c.put("item_seq", row.getIntNth("项次", 2));  // 第2个"项次"=二级(裸重复表头按列序)
            c.put("component_no", componentNo);
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("issue_unit", row.getStr("组成单位"));
            childByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次一级, 组成件料号)
            result.successRows++;
        }

        for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childByMat.entrySet()) {
            String materialNo = e.getKey();
            List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
            try {
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", materialNo);
                masterGk.put("bom_type", "ASSEMBLY");
                masterGk.put("characteristic", "ASSEMBLY");   // uq 隔离 Q03(NULL)/Q12(ASSEMBLY)
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "QUOTE");
                childGk.put("customer_no", ctx.customerNo);
                childGk.put("material_no", materialNo);
                childGk.put("characteristic", "ASSEMBLY");
                writer.writeVersionedMasterDetail(
                    "material_bom", "bom_version", masterGk, Map.of(),
                    "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows);
                result.recordWrite("material_bom", 1);
                result.recordWrite("material_bom_item", childRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
            }
        }
        return result;
    }
}
