package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
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
 * Q03 物料BOM → material_bom（主） + material_bom_item（子） + material_master（同步 upsert）。
 *
 * <p>版本化（Task 5 / V293）：按 material_no 分组调 {@link VersionedV6Writer#writeVersionedMasterDetail}。
 * 主表 bom_version max+1（首版 2000，旧 'V1' 非数字被忽略）；子表按 bom_version 多版本保留（V293 起），历史版本行 is_current=false 留存。
 * <p>主/子用 bom_type=MATERIAL（主表 groupKey 维度）+ 子表 characteristic=NULL，与 Q12(ASSEMBLY) 物理隔离。
 */
@ApplicationScoped
public class Q03MaterialBomHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "物料BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, Map<List<Object>, Map<String, Object>>> childByMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String componentUsageType = row.getStr("产出料号类型");
            String componentNo = row.getStr("投入料号");

            // §3 料号表同步（保留原副作用）
            if (componentNo != null) {
                materialMasterRepo.upsertByMaterialNo(componentNo, row.getStr("投入料号名称"),
                    null, null, null, digitsOnly(componentUsageType), null, null, null, ctx.importedBy);
                result.recordWrite("material_master", 1);
            }

            Integer seq = row.getInt("项次");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("component_usage_type", componentUsageType);
            c.put("composition_qty", row.getDecimal("材料毛重", "毛重"));
            c.put("base_qty", row.getDecimal("材料净重", "净重"));
            c.put("issue_unit", row.getStr("重量单位"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
            childByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 投入料号)
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
                masterGk.put("bom_type", "MATERIAL");
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "QUOTE");
                childGk.put("customer_no", ctx.customerNo);
                childGk.put("material_no", materialNo);
                childGk.put("characteristic", null);   // Q03 子表 characteristic=NULL
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

    /** §3「material_type 只写数字」：从「1.银点类」提取首段数字；无数字返 null。 */
    private static String digitsOnly(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (sb.length() > 0) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
